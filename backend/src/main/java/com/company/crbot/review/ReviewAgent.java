package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.gitlab.GitlabClient;
import com.company.crbot.knowledge.KbDocEntity;
import com.company.crbot.knowledge.KbDocRepository;
import com.company.crbot.mcp.DbToolPort;
import com.company.crbot.mcp.JdbcDbToolAdapter;
import com.company.crbot.mcp.McpDbToolAdapter;
import com.company.crbot.rag.RagSearchService;
import com.company.crbot.settings.SettingsService;
import com.company.crbot.standard.StandardDocEntity;
import com.company.crbot.standard.StandardDocRepository;
import com.company.crbot.standard.StandardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class ReviewAgent {

    private final ReviewJobRepository jobRepository;
    private final ReviewJobService jobService;
    private final GitlabClient gitlabClient;
    private final SettingsService settingsService;
    private final StandardDocRepository standardDocRepository;
    private final StandardService standardService;
    private final KbDocRepository kbDocRepository;
    private final RagSearchService ragSearchService;
    private final LlmClient llmClient;
    private final JdbcDbToolAdapter jdbcDbToolAdapter;
    private final McpDbToolAdapter mcpDbToolAdapter;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ReviewTokenMeter tokenMeter;
    private final com.company.crbot.usage.TokenLedger tokenLedger;
    private static final int MAX_TOOL_TURNS = 100;
    private static final int MAX_EMPTY_ROUNDS = 3;

    private final RepoWorkspace repoWorkspace;
    private final ReviewLogService reviewLogService;
    private final ReviewFeedbackService reviewFeedbackService;
    private final ExecutorService groupExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "crbot-review-group");
        thread.setDaemon(true);
        return thread;
    });

    public ReviewAgent(ReviewJobRepository jobRepository, ReviewJobService jobService, GitlabClient gitlabClient,
                       SettingsService settingsService, StandardDocRepository standardDocRepository,
                       StandardService standardService, KbDocRepository kbDocRepository, RagSearchService ragSearchService,
                       LlmClient llmClient, JdbcDbToolAdapter jdbcDbToolAdapter, McpDbToolAdapter mcpDbToolAdapter,
                       com.fasterxml.jackson.databind.ObjectMapper objectMapper, ReviewTokenMeter tokenMeter,
                       com.company.crbot.usage.TokenLedger tokenLedger, RepoWorkspace repoWorkspace,
                       ReviewLogService reviewLogService, ReviewFeedbackService reviewFeedbackService) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.gitlabClient = gitlabClient;
        this.settingsService = settingsService;
        this.standardDocRepository = standardDocRepository;
        this.standardService = standardService;
        this.kbDocRepository = kbDocRepository;
        this.ragSearchService = ragSearchService;
        this.llmClient = llmClient;
        this.jdbcDbToolAdapter = jdbcDbToolAdapter;
        this.mcpDbToolAdapter = mcpDbToolAdapter;
        this.objectMapper = objectMapper;
        this.tokenMeter = tokenMeter;
        this.tokenLedger = tokenLedger;
        this.repoWorkspace = repoWorkspace;
        this.reviewLogService = reviewLogService;
        this.reviewFeedbackService = reviewFeedbackService;
    }

    public void review(Long jobId) {
        // 从取 diff 到写报告，中间的对话和向量检索都算这一次评审
        tokenMeter.start();
        tokenLedger.open(com.company.crbot.usage.TokenLedger.REVIEW, jobId, null);
        boolean started = false;
        try {
            try {
                ReviewJobEntity job = jobRepository.findById(jobId)
                        .orElseThrow(() -> new ApiException("评审任务不存在"));
                tokenLedger.setTitle(job.getTitle());
                if (!"RUNNING".equals(job.getStatus())) {
                    return;
                }
                started = true;
                run(job);
            } catch (Exception ex) {
                jobService.failIfRunning(jobId, ex.getMessage());
            }
        } finally {
            tokenLedger.close();
            ReviewTokenMeter.Snapshot usage = tokenMeter.finish();
            if (started) {
                // 失败任务也同样记下已经消耗的 token
                jobService.saveTokens(jobId, usage);
            }
        }
    }

    private void run(ReviewJobEntity job) {
        GitlabClient.DiffBundle bundle = "merge_request".equals(job.getEventType())
                ? gitlabClient.fetchMergeRequestDiff(job.getGitlabProjectId(), job.getMrIid())
                : gitlabClient.fetchPushDiff(job.getGitlabProjectId(), job.getBeforeSha(), job.getAfterSha());
        DiffFilter.Result filtered = DiffFilter.partition(bundle.files());
        List<GitlabClient.DiffFile> files = filtered.kept();
        int round = job.getRereviewRound() == null ? 0 : job.getRereviewRound();
        for (DiffFilter.Skipped skipped : filtered.skipped()) {
            reviewLogService.add(job.getId(), ReviewLogService.FILTERED,
                    skipped.path().isBlank() ? "未命名文件" : skipped.path(), skipped.reason(), round);
        }
        for (GitlabClient.DiffFile file : files) {
            // 送进提示词的源码也记成文件读取，不只记录模型事后又点开的那一次
            reviewLogService.add(job.getId(), ReviewLogService.FILE_READ, file.path(), "作为本次 diff 送入模型", round);
        }
        String description = bundle.description();
        // 白名单过滤后没有源码时不调用模型，避免空 diff 被写成业务结论
        if (files.isEmpty()) {
            ObjectNode empty = emptyResult("变更文件均不在评审范围内", "无待评审源码");
            jobService.saveReport(job.getId(), empty, ReportMarkdown.render(empty, objectMapper), "none",
                    settingsService.activePrompt().getVersion());
            return;
        }
        // 评审前把该项目更新到这次提交。同一项目同时只有一个评审，可以共用这一份目录
        RepoWorkspace.Checkout checkout = repoWorkspace.sync(job);
        String query = SymbolExtractor.query(job.getTitle(), files, description);
        List<Long> standardIds = standardDocRepository.findByActiveTrueAndDeletedFalse().stream()
                .filter(doc -> "INDEXED".equals(doc.getStatus()))
                .map(StandardDocEntity::getId)
                .toList();
        List<RagSearchService.RagHit> standards = ragSearchService.search("standard", standardIds, query, -1, null);
        // 启用规范的规则全文随评审下发，不依赖向量是否命中
        String enabledRules = standardService.enabledRulePrompt();
        List<KbDocEntity> indexedDocs = kbDocRepository.findByStatusOrderByIdAsc("INDEXED");
        List<Long> businessIds = indexedDocs.stream()
                .filter(doc -> isBusinessDoc(doc.getDocType()))
                .map(KbDocEntity::getId)
                .toList();
        List<Long> functionIds = indexedDocs.stream()
                .filter(doc -> isFunctionDoc(doc.getDocType()))
                .map(KbDocEntity::getId)
                .toList();
        List<RagSearchService.RagHit> businessHits = ragSearchService.search("knowledge", businessIds, query, -1, 0.80);
        List<RagSearchService.RagHit> functionHits = ragSearchService.search("knowledge", functionIds, query, -1, 0.80);
        boolean businessHit = !businessHits.isEmpty();
        boolean functionHit = !functionHits.isEmpty();
        SymbolExtractor.DbChange dbChange = SymbolExtractor.dbChange(files);
        // 没配数据源时不把查库工具交给模型，避免模型一调用就把整单评审打失败
        boolean dbReady = jdbcDbToolAdapter.hasReadyDatasource();
        DbLookup dbLookup = databaseContext(job, files, dbChange, dbReady);
        String dbContext = dbLookup.text();
        // 表结构已经写进提示词，不再让模型循环调用查库工具，避免评审超时
        boolean exposeDbTools = false;
        List<List<GitlabClient.DiffFile>> groups = conversationGroups(files);
        String planText = "";
        String planStatus = "skipped";
        if (DiffBatcher.changedLines(files) >= settingsService.planLineThreshold()) {
            tokenMeter.beginPhase("plan");
            planText = plan(job, files);
            planStatus = planText.isBlank() ? "failed" : "ran";
        }
        tokenMeter.beginPhase("main");
        FindingCollector collector = new FindingCollector();
        List<JsonNode> parts = new ArrayList<>();
        List<String> groupErrors = new ArrayList<>();
        Object sharedMeter = tokenMeter.share();
        Object sharedScope = tokenLedger.capture();
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (List<GitlabClient.DiffFile> group : groups) {
            String planForGroup = planText;
            futures.add(groupExecutor.submit(() -> {
                tokenMeter.use(sharedMeter);
                tokenLedger.install(sharedScope);
                try {
                    FindingCollector groupCollector = new FindingCollector();
                    JsonNode part = askModel(job, description, group, standards, enabledRules, businessHits, functionHits, dbContext, exposeDbTools, groupCollector, planForGroup, checkout);
                    collector.absorb(groupCollector);
                    return part;
                } finally {
                    tokenMeter.use(null);
                    tokenLedger.install(null);
                }
            }));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                parts.add(futures.get(i).get());
            } catch (Exception ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                // 一组失败不影响其他组，全部失败才让这次评审失败
                String path = groups.get(i).isEmpty() ? "未知文件" : groups.get(i).get(0).path();
                groupErrors.add(path + "：" + cause.getMessage());
            }
        }
        if (parts.isEmpty()) {
            throw new ApiException(groupErrors.isEmpty() ? "评审分组没有结果" : groupErrors.get(0));
        }
        JsonNode merged = merge(parts, businessHit);
        merged = attachCollected(merged, collector, businessHit);
        if (!groupErrors.isEmpty() && merged instanceof ObjectNode grouped) {
            grouped.put("summary", grouped.path("summary").asText("") + "\n未完成的分组：" + String.join("、", groupErrors));
        }
        if (merged instanceof ObjectNode object) {
            // 没检索到对应资料的维度强制跳过，避免模型编造该维度结论
            merged = ReviewResultParser.normalizeDimensions(object, businessHit, functionHit);
        }
        // 实体删了字段但库里的列还在时，直接写成高危，不依赖模型有没有写到
        merged = appendColumnDrift(merged, dbLookup.drifts());
        tokenMeter.beginPhase("locate");
        // 行号以证据片段在 diff 中的匹配为准，对不上就标未定位
        FindingLocator.apply(merged, files);
        if (settingsService.relocateEnabled()) {
            relocateWithModel(merged, files);
            FindingLocator.apply(merged, files);
        }
        int filterRemoved = 0;
        String filterStatus = "off";
        if (settingsService.postFilterEnabled() && merged instanceof ObjectNode filteredRoot) {
            tokenMeter.beginPhase("filter");
            filterStatus = "ran";
            filterRemoved = filterFindings(filteredRoot, files);
        }
        if (merged instanceof ObjectNode pipelineRoot) {
            writePipeline(pipelineRoot, planStatus, filterStatus, filterRemoved, List.of(), files, collector);
        }
        String markdown = ReportMarkdown.render(merged, objectMapper);
        if (settingsService.postMrNote() && "merge_request".equals(job.getEventType()) && job.getMrIid() != null) {
            postNote(job, merged);
        }
        jobService.saveReport(job.getId(), merged, markdown, settingsService.llmModel(),
                settingsService.activePrompt().getVersion());
    }

    private JsonNode askModel(ReviewJobEntity job, String description, List<GitlabClient.DiffFile> files,
                              List<RagSearchService.RagHit> standards, String enabledRules,
                              List<RagSearchService.RagHit> businessHits, List<RagSearchService.RagHit> functionHits,
                              String dbContext, boolean exposeDbTools, FindingCollector collector, String planText,
                              RepoWorkspace.Checkout checkout) {
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", settingsService.activePrompt().getContent());
        String prompt = userPrompt(job, description, files, standards, enabledRules, businessHits, functionHits, dbContext, checkout);
        if (settingsService.pathRulesEnabled()) {
            String checklist = com.company.crbot.standard.PathRuleRegistry.checklist(files);
            if (!checklist.isBlank()) {
                prompt += "\n## 路径清单\n内置清单不能冒充团队规范编号。\n" + checklist + "\n";
            }
        }
        if (planText != null && !planText.isBlank()) {
            prompt += "\n## 计划\n只作风险提示，最终结论仍以 diff 为准。\n" + limit(planText, 2000) + "\n";
        }
        messages.addObject().put("role", "user").put("content", prompt);
        ArrayNode tools = toolDefinitions(exposeDbTools, checkout != null);
        JsonNode first = runTurns(job, messages, tools, files, standards, enabledRules, businessHits, functionHits, exposeDbTools, collector, checkout);
        if (!settingsService.secondRoundEnabled() || collector.isDone()) {
            return first;
        }
        StringBuilder known = new StringBuilder();
        for (ObjectNode finding : collector.findings()) {
            known.append(finding.path("filePath").asText("")).append(' ').append(finding.path("title").asText("")).append('\n');
        }
        // 第二轮留在同一段对话里，丢掉计划，只找还没写过的问题
        messages.addObject().put("role", "user").put("content",
                "开始第二轮。忽略上面的计划，不要受它限制。下面这些发现已经确认，不要重复：\n"
                        + (known.isEmpty() ? "（还没有）\n" : known)
                        + "请只找新问题。需要时继续查阅源码。完成后调用 task_done。");
        collector.reopen();
        return runTurns(job, messages, tools, files, standards, enabledRules, businessHits, functionHits, exposeDbTools, collector, checkout);
    }

    private JsonNode runTurns(ReviewJobEntity job, ArrayNode messages, ArrayNode tools, List<GitlabClient.DiffFile> files,
                              List<RagSearchService.RagHit> standards, String enabledRules,
                              List<RagSearchService.RagHit> businessHits, List<RagSearchService.RagHit> functionHits,
                              boolean exposeDbTools, FindingCollector collector, RepoWorkspace.Checkout checkout) {
        java.util.Map<String, Integer> streak = new java.util.HashMap<>();
        Set<String> paths = new java.util.HashSet<>();
        for (GitlabClient.DiffFile file : files) {
            paths.add(file.path());
        }
        int emptyRounds = 0;
        String lastContent = "";
        String rememberedSummary = "";
        for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
            if (messages.toString().length() > 160_000) {
                messages = compressHistory(messages);
            }
            JsonNode message = llmClient.chat(messages, tools);
            messages.add(message.deepCopy());
            JsonNode toolCalls = message.path("tool_calls");
            lastContent = message.path("content").asText("");
            rememberedSummary = rememberSummary(lastContent, rememberedSummary);
            StringBuilder called = new StringBuilder();
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    if (called.length() > 0) {
                        called.append('、');
                    }
                    called.append(call.path("function").path("name").asText(""));
                }
            }
            reviewLogService.add(job.getId(), ReviewLogService.AGENT, "第 " + (turn + 1) + " 轮",
                    called.isEmpty() ? "模型直接给出文字，没有调用工具" : "模型调用了 " + called, logRound(job));
            if (collector.isDone()) {
                return parseOrCollected(lastContent, collector, rememberedSummary);
            }
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                emptyRounds++;
                if (emptyRounds >= MAX_EMPTY_ROUNDS) {
                    return parseOrCollected(lastContent, collector, rememberedSummary);
                }
                messages.addObject().put("role", "user").put("content",
                        "你没有调用工具。请继续调用工具查阅代码或提交发现。如果这组已经评完，调用 task_done 结束。");
                continue;
            }
            emptyRounds = 0;
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                String result = executeTool(job, call, files, standards, enabledRules, businessHits, functionHits, exposeDbTools, collector, paths, checkout);
                if ("get_diff_file".equals(name)) {
                    String path = call.path("function").path("arguments").asText("");
                    reviewLogService.add(job.getId(), ReviewLogService.FILE_READ,
                            path.isBlank() ? "diff" : path, "模型再次读取了该文件的 diff", logRound(job));
                }
                if (!"read_source".equals(name) && !"search_source".equals(name) && !"find_source".equals(name) && !"get_diff_file".equals(name)) {
                    reviewLogService.add(job.getId(), ReviewLogService.AGENT, name, briefTool(name, result), logRound(job));
                }
                if (result.startsWith("错误")) {
                    int count = streak.getOrDefault(name, 0) + 1;
                    streak.put(name, count);
                    if (count >= 3) {
                        result = "该工具已连续失败，已跳过，请继续评审";
                    }
                } else {
                    streak.put(name, 0);
                }
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.path("id").asText())
                        .put("content", limit(result, 8000));
            }
            if (collector.isDone()) {
                return parseOrCollected("", collector, rememberedSummary);
            }
        }
        if (!collector.isEmpty() || !lastContent.isBlank()) {
            return parseOrCollected(lastContent, collector, rememberedSummary);
        }
        throw new ApiException("模型未产出最终 JSON");
    }

    private String rememberSummary(String content, String current) {
        if (content == null || content.isBlank()) {
            return current;
        }
        try {
            String summary = ReviewResultParser.parse(content, objectMapper).path("summary").asText("");
            if (!summary.isBlank()) {
                return summary;
            }
        } catch (RuntimeException ex) {
            // 这轮不是评审 JSON，沿用之前记下的摘要
        }
        return current;
    }

    private static int logRound(ReviewJobEntity job) {
        return job.getRereviewRound() == null ? 0 : job.getRereviewRound();
    }

    private static String briefTool(String name, String result) {
        if ("get_diff_file".equals(name)) {
            return "读取了本次变更的 diff";
        }
        if ("submit_findings".equals(name) || "task_done".equals(name) || "search_standards".equals(name) || "search_knowledge".equals(name)) {
            String line = result == null ? "" : result.split("\\R", 2)[0];
            return line.isBlank() ? "已完成" : line;
        }
        return "已调用 " + name;
    }

    private JsonNode parseOrCollected(String content, FindingCollector collector, String rememberedSummary) {
        if (content != null && !content.isBlank()) {
            try {
                return ReviewResultParser.parse(content, objectMapper);
            } catch (RuntimeException ex) {
                if (collector.isEmpty()) {
                    throw new ApiException("模型未产出最终 JSON");
                }
            }
        }
        if (!collector.isEmpty()) {
            String summary = rememberedSummary == null || rememberedSummary.isBlank()
                    ? collectedSummary(collector) : rememberedSummary;
            ObjectNode root = emptyResult(summary, "");
            ArrayNode findings = root.putArray("findings");
            for (ObjectNode finding : collector.findings()) {
                findings.add(finding);
            }
            return root;
        }
        throw new ApiException("模型未产出最终 JSON");
    }

    private static String collectedSummary(FindingCollector collector) {
        StringBuilder titles = new StringBuilder();
        int count = 0;
        for (ObjectNode finding : collector.findings()) {
            count++;
            if (titles.length() > 0) {
                titles.append("；");
            }
            titles.append(finding.path("title").asText("未命名发现"));
        }
        return "代码质量发现 " + count + " 条：" + titles;
    }

    private List<List<String>> semanticGroups(List<GitlabClient.DiffFile> files) {
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            messages.addObject().put("role", "system").put("content", "把相关文件分成若干组。同模块、接口和实现、Mapper 和 XML 放在一组。只输出 JSON：{\"groups\":[[\"path\"]]}。每个路径只能出现一次。");
            StringBuilder body = new StringBuilder();
            for (GitlabClient.DiffFile file : files) {
                body.append(file.path()).append('\n');
            }
            messages.addObject().put("role", "user").put("content", body.toString());
            JsonNode groups = objectMapper.readTree(llmClient.chat(messages, null).path("content").asText("")).path("groups");
            List<List<String>> parsed = new ArrayList<>();
            if (groups.isArray()) {
                for (JsonNode group : groups) {
                    List<String> paths = new ArrayList<>();
                    if (group.isArray()) {
                        for (JsonNode path : group) {
                            paths.add(path.asText());
                        }
                    }
                    if (!paths.isEmpty()) {
                        parsed.add(paths);
                    }
                }
            }
            return parsed;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private ArrayNode compressHistory(ArrayNode messages) {
        ArrayNode local = ConversationCompressor.compress(messages);
        ObjectNode note = null;
        for (JsonNode message : local) {
            if (message.path("content").asText("").startsWith("已压缩的评审过程") && message instanceof ObjectNode object) {
                note = object;
            }
        }
        if (note == null) {
            return local;
        }
        try {
            ArrayNode ask = objectMapper.createArrayNode();
            ask.addObject().put("role", "system").put("content", "把评审过程压成简短中文，保留已确认发现、查过的表、命中的规范编号、还没做的事。不要写新结论。");
            ask.addObject().put("role", "user").put("content", note.path("content").asText(""));
            String rewritten = llmClient.chat(ask, null).path("content").asText("");
            if (!rewritten.isBlank()) {
                note.put("content", rewritten);
            }
        } catch (RuntimeException ex) {
            // 压缩模型失败时保留本地摘要，主评审继续
        }
        return local;
    }

    private List<List<GitlabClient.DiffFile>> conversationGroups(List<GitlabClient.DiffFile> files) {
        if (files.size() <= 1) {
            return List.of(files);
        }
        List<List<String>> semantic = semanticGroups(files);
        List<List<GitlabClient.DiffFile>> grouped = new ArrayList<>();
        if (semantic.isEmpty()) {
            grouped.addAll(DiffBatcher.packByContext(files).batches());
        } else {
            for (List<GitlabClient.DiffFile> group : DiffBatcher.arrange(files, semantic, Integer.MAX_VALUE).batches()) {
                // 放得进一次上下文的相关文件留在同一组，共用一段对话
                grouped.addAll(DiffBatcher.packByContext(group).batches());
            }
        }
        if (grouped.isEmpty()) {
            return List.of(files);
        }
        return grouped;
    }

    private DiffBatcher.BatchPlan packGroups(DiffBatcher.BatchPlan arranged) {
        List<List<GitlabClient.DiffFile>> batches = new ArrayList<>();
        for (List<GitlabClient.DiffFile> group : arranged.batches()) {
            // 语义分组后再按上下文切开，保证每一批都能放进一次请求
            batches.addAll(DiffBatcher.packByContext(group).batches());
        }
        return new DiffBatcher.BatchPlan(batches, arranged.skipped());
    }

    private String plan(ReviewJobEntity job, List<GitlabClient.DiffFile> files) {
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            messages.addObject().put("role", "system").put("content", "只列出这份变更的风险和需要核对的文件。不要写最终发现，不要使用 Markdown 围栏。");
            StringBuilder body = new StringBuilder();
            body.append(job.getTitle()).append('\n');
            for (GitlabClient.DiffFile file : files) {
                body.append(file.path()).append('\n').append(limit(file.patch(), 1200)).append('\n');
            }
            messages.addObject().put("role", "user").put("content", body.toString());
            return llmClient.chat(messages, null).path("content").asText("");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String uncovered(List<GitlabClient.DiffFile> batch, FindingCollector collector) {
        Set<String> covered = new java.util.HashSet<>();
        for (ObjectNode finding : collector.findings()) {
            covered.add(finding.path("filePath").asText(""));
        }
        StringBuilder gaps = new StringBuilder();
        for (GitlabClient.DiffFile file : batch) {
            if (!covered.contains(file.path())) {
                gaps.append(file.path()).append('\n');
            }
        }
        return gaps.toString();
    }

    private JsonNode attachCollected(JsonNode merged, FindingCollector collector, boolean businessHit) {
        if (collector.isEmpty() || !(merged instanceof ObjectNode root)) {
            return merged;
        }
        boolean addedToQuality = false;
        for (JsonNode dimension : root.path("dimensions")) {
            if (!"quality".equals(dimension.path("code").asText()) || !(dimension.path("findings") instanceof ArrayNode findings) || findings.isEmpty()) {
                continue;
            }
            for (ObjectNode finding : collector.findings()) {
                findings.add(finding);
            }
            addedToQuality = true;
        }
        if (!addedToQuality && root.path("findings").isEmpty()) {
            ArrayNode findings = root.putArray("findings");
            for (ObjectNode finding : collector.findings()) {
                findings.add(finding.deepCopy());
            }
        }
        return root;
    }

    private int filterFindings(ObjectNode root, List<GitlabClient.DiffFile> files) {
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            messages.addObject().put("role", "system").put("content", ReviewFactFilter.PROMPT);
            StringBuilder body = new StringBuilder();
            for (GitlabClient.DiffFile file : files) {
                body.append("FILE ").append(file.path()).append('\n').append(limit(file.patch(), 2000)).append('\n');
            }
            body.append(root.path("findings").toString());
            messages.addObject().put("role", "user").put("content", body.toString());
            String content = llmClient.chat(messages, null).path("content").asText("");
            JsonNode verdict = objectMapper.readTree(content);
            Set<String> removeIds = new java.util.HashSet<>();
            for (JsonNode id : verdict.path("remove")) {
                removeIds.add(id.asText());
            }
            removeIds.retainAll(ReviewFactFilter.removableIds(root.path("findings")));
            return ReviewFactFilter.remove(root, removeIds);
        } catch (Exception ex) {
            // 核查失败时保留原报告，不把主评审打成失败
            return 0;
        }
    }

    private void relocateWithModel(JsonNode merged, List<GitlabClient.DiffFile> files) {
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            messages.addObject().put("role", "system").put("content", "只从给出的 diff 中原样摘出能对上的代码。不要编造。输出 JSON：{\"items\":[{\"id\":\"\",\"existingCode\":\"\"}]}");
            messages.addObject().put("role", "user").put("content", merged.path("findings").toString());
            String content = llmClient.chat(messages, null).path("content").asText("");
            JsonNode items = objectMapper.readTree(content).path("items");
            if (!items.isArray()) {
                return;
            }
            for (JsonNode item : items) {
                String id = item.path("id").asText("");
                String code = item.path("existingCode").asText("");
                if (id.isBlank() || code.isBlank()) {
                    continue;
                }
                for (JsonNode finding : merged.path("findings")) {
                    if (id.equals(finding.path("id").asText()) && finding instanceof ObjectNode node && node.path("locationStatus").asInt() != FindingLocator.RESOLVED) {
                        node.put("existingCode", code);
                    }
                }
            }
        } catch (Exception ex) {
            // 重定位失败就保持未定位
        }
    }

    private void writePipeline(ObjectNode root, String planStatus, String filterStatus, int filterRemoved,
                               List<DiffBatcher.SkippedFile> skippedFiles, List<GitlabClient.DiffFile> files, FindingCollector collector) {
        ObjectNode pipeline = root.putObject("pipeline");
        pipeline.put("plan", planStatus);
        pipeline.put("filter", filterStatus);
        pipeline.put("filterRemoved", filterRemoved);
        ArrayNode skipped = pipeline.putArray("skippedFiles");
        for (DiffBatcher.SkippedFile file : skippedFiles) {
            skipped.addObject().put("path", file.path()).put("reason", file.reason());
        }
        Set<String> covered = new java.util.HashSet<>();
        for (ObjectNode finding : collector.findings()) {
            covered.add(finding.path("filePath").asText(""));
        }
        for (JsonNode finding : root.path("findings")) {
            covered.add(finding.path("filePath").asText(""));
        }
        ArrayNode gaps = pipeline.putArray("coverageGaps");
        for (GitlabClient.DiffFile file : files) {
            if (!covered.contains(file.path())) {
                gaps.add(file.path());
            }
        }
        if (!skippedFiles.isEmpty()) {
            String summary = root.path("summary").asText("");
            StringBuilder names = new StringBuilder();
            for (DiffBatcher.SkippedFile file : skippedFiles) {
                if (names.length() > 0) {
                    names.append('、');
                }
                names.append(file.path());
            }
            root.put("summary", summary + "\n未纳入评审：" + names);
        }
        ArrayNode phases = pipeline.putArray("phases");
        for (ReviewTokenMeter.Phase phase : tokenMeter.phases()) {
            ObjectNode item = phases.addObject();
            item.put("name", phase.name());
            item.put("elapsedMs", phase.elapsedMs());
            item.put("promptTokens", phase.promptTokens());
            item.put("completionTokens", phase.completionTokens());
            item.put("totalTokens", phase.totalTokens());
        }
    }

    private String executeTool(ReviewJobEntity job, JsonNode call, List<GitlabClient.DiffFile> files,
                               List<RagSearchService.RagHit> standards, String enabledRules,
                               List<RagSearchService.RagHit> businessHits, List<RagSearchService.RagHit> functionHits,
                               boolean exposeDbTools, FindingCollector collector, Set<String> paths,
                               RepoWorkspace.Checkout checkout) {
        String name = call.path("function").path("name").asText();
        JsonNode rawArgs = call.path("function").path("arguments");
        // 有的网关把 arguments 放成对象，有的放成 JSON 字符串
        JsonNode args = rawArgs.isObject() ? rawArgs : readArgs(rawArgs.asText("{}"));
        if ("submit_findings".equals(name)) {
            return collector.submit(args, paths);
        }
        if ("task_done".equals(name)) {
            return collector.finish();
        }
        if ("get_diff_file".equals(name)) {
            String path = args.path("path").asText("");
            return files.stream().filter(file -> file.path().equals(path)).map(GitlabClient.DiffFile::patch)
                    .findFirst().orElse("未找到文件");
        }
        if ("search_standards".equals(name)) {
            // 工具返回启用规则全文，检索命中只作补充
            if (enabledRules == null || enabledRules.isBlank()) {
                return "没有启用中的编码规范";
            }
            return enabledRules + (standards.isEmpty() ? "" : "\n\n检索补充:\n" + formatHits(standards));
        }
        if ("search_knowledge".equals(name)) {
            return "业务资料:\n" + (businessHits.isEmpty() ? "未匹配\n" : formatHits(businessHits))
                    + "功能资料:\n" + (functionHits.isEmpty() ? "未匹配\n" : formatHits(functionHits));
        }
        if ("read_source".equals(name) || "search_source".equals(name) || "find_source".equals(name)) {
            if (checkout == null) {
                return "本地代码未检出";
            }
            if ("read_source".equals(name)) {
                String path = args.path("path").asText("");
                int start = args.path("start_line").asInt(1);
                int end = args.path("end_line").asInt(0);
                String result = repoWorkspace.readSource(checkout, path, start, end);
                reviewLogService.add(job.getId(), ReviewLogService.FILE_READ, path, "读取第 " + start + " 到 " + end + " 行", logRound(job));
                return result;
            }
            if ("search_source".equals(name)) {
                String query = args.path("query").asText("");
                String result = repoWorkspace.searchSource(checkout, query, args.path("path").asText(""));
                reviewLogService.add(job.getId(), ReviewLogService.SEARCH, query, "在仓库中搜索", logRound(job));
                return result;
            }
            String nameQuery = args.path("name").asText("");
            String found = repoWorkspace.findSource(checkout, nameQuery);
            reviewLogService.add(job.getId(), ReviewLogService.SEARCH, nameQuery, "按文件名查找", logRound(job));
            return found;
        }
        if (isDbTool(name)) {
            if (!exposeDbTools) {
                // 没配数据源或这次 diff 不该查库时，工具返回说明，不把评审打成失败
                return "未查库。没有可用只读数据源，或本次 diff 没有字段变更和复杂 SQL。不要编造表结构、索引或慢 SQL 结论，其余评审继续。";
            }
            return queryDb(name, args);
        }
        throw new ApiException("未知工具: " + name);
    }

    private String queryDb(String name, JsonNode args) {
        DbToolPort port = dbPort();
        try {
            if ("db_list_tables".equals(name)) {
                return String.join("\n", port.listTables(args.path("schema").asText(null)));
            }
            if ("db_describe_table".equals(name)) {
                return port.describeTable(args.path("table").asText());
            }
            if ("db_list_indexes".equals(name)) {
                return port.listIndexes(args.path("table").asText());
            }
            if ("db_explain_sql".equals(name)) {
                return port.explainSql(args.path("sql").asText());
            }
        } catch (ApiException ex) {
            if (isMissingDatasource(ex)) {
                // 查库中途发现没有数据源，跳过这一次调用
                return "未配置启用且通过只读校验的数据源，已跳过查库。不要因此中断评审。";
            }
            throw ex;
        }
        throw new ApiException("未知工具: " + name);
    }

    private boolean isDbTool(String name) {
        return "db_list_tables".equals(name) || "db_describe_table".equals(name)
                || "db_list_indexes".equals(name) || "db_explain_sql".equals(name);
    }

    private boolean isMissingDatasource(ApiException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("未配置启用且通过只读校验的数据源");
    }

    private DbLookup databaseContext(ReviewJobEntity job, List<GitlabClient.DiffFile> files, SymbolExtractor.DbChange change, boolean dbReady) {
        if (!change.needsLookup()) {
            // 注解、空判断这类改动不查库
            return new DbLookup("本次 diff 没有字段变更，也没有复杂 SQL，未查库。不要写表结构或慢 SQL 结论。", List.of());
        }
        if (!dbReady) {
            // 有库表相关改动但没配数据源时跳过，评审的其他部分照常做
            return new DbLookup("本次 diff 有字段或复杂 SQL 变更，但未配置可用的只读数据源，已跳过查库。继续完成其余评审，不要编造表结构、索引或 EXPLAIN。", List.of());
        }
        Set<String> tables = new LinkedHashSet<>(change.schemaTables());
        StringBuilder missing = new StringBuilder();
        for (String path : change.entityPaths()) {
            GitlabClient.DiffFile matched = files.stream().filter(file -> path.equals(file.path())).findFirst().orElse(null);
            if (matched != null && SymbolExtractor.tableNameIn(matched.patch()) != null) {
                // diff 里已经有表名，不用再读整文件
                continue;
            }
            String ref = job.getAfterSha() == null || job.getAfterSha().isBlank() ? job.getBeforeSha() : job.getAfterSha();
            try {
                String content = gitlabClient.fetchFile(job.getGitlabProjectId(), path, ref);
                String table = SymbolExtractor.tableNameIn(content);
                if (table == null) {
                    missing.append(path).append(" 没有读到 @TableName\n");
                } else {
                    tables.add(table);
                }
            } catch (ApiException ex) {
                missing.append(path).append(" 读取失败: ").append(ex.getMessage()).append('\n');
            }
        }
        DbToolPort port = dbPort();
        StringBuilder builder = new StringBuilder();
        if (!change.removedColumns().isEmpty()) {
            // 删掉的映射列要和库里所有模式的同名表对照
            builder.append("实体删除的映射列: ").append(String.join(", ", change.removedColumns())).append('\n');
        }
        if (!change.addedColumns().isEmpty()) {
            builder.append("实体新增的映射列: ").append(String.join(", ", change.addedColumns())).append('\n');
        }
        builder.append("查表不限于数据源页面填写的库名，会返回该连接能看到的所有模式。\n");
        if (missing.length() > 0) {
            builder.append(missing);
        }
        List<ColumnDrift> drifts = new ArrayList<>();
        String filePath = String.join(", ", change.entityPaths());
        for (String table : tables) {
            // 字段变更对照库里各模式的列和索引
            String described = safeDb(() -> port.describeTable(table));
            // 提示词只保留和本次增删列有关的行，避免整表结构把评审拖超时
            builder.append("表 ").append(table).append(" 与本次字段相关的列\n");
            builder.append(relevantLines(described, change.removedColumns(), change.addedColumns())).append('\n');
            for (String column : change.removedColumns()) {
                for (String schema : schemasContaining(described, column)) {
                    drifts.add(new ColumnDrift(filePath, table, schema, column));
                }
            }
        }
        for (String table : change.sqlTables()) {
            if (change.schemaTables().contains(table)) {
                continue;
            }
            // 复杂 SQL 只核对涉及表有没有索引
            builder.append("复杂 SQL 涉及表 ").append(table).append(" 的索引\n");
            builder.append(safeDb(() -> port.listIndexes(table))).append('\n');
        }
        for (String sql : change.complexSql()) {
            String select = selectStatement(sql);
            if (select == null) {
                continue;
            }
            // 能抽出单条 SELECT 时才做 EXPLAIN，用来看慢 SQL 风险
            builder.append("EXPLAIN ").append(select).append('\n');
            builder.append(safeDb(() -> port.explainSql("EXPLAIN " + select))).append('\n');
        }
        if (builder.isEmpty()) {
            return new DbLookup("有字段或复杂 SQL 变更，但没有解析出可查询的表或 SELECT。没有库内证据时不要下慢 SQL 结论。", List.of());
        }
        if (!drifts.isEmpty()) {
            builder.append("上面已经确认：实体删除的映射列在数据库里还存在。这是高危 database 风险，系统会写入发现。\n");
        }
        return new DbLookup(builder.toString(), drifts);
    }

    private String relevantLines(String described, Set<String> removedColumns, Set<String> addedColumns) {
        if (described == null || described.isBlank()) {
            return "无结果";
        }
        StringBuilder matched = new StringBuilder();
        for (String line : described.split("\\R")) {
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            boolean hit = false;
            for (String column : removedColumns) {
                if (upper.contains(column.toUpperCase(java.util.Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                for (String column : addedColumns) {
                    if (upper.contains(column.toUpperCase(java.util.Locale.ROOT))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                matched.append(line).append('\n');
            }
        }
        if (matched.isEmpty()) {
            return "可见模式中没有这些列";
        }
        return matched.toString();
    }

    private List<String> schemasContaining(String described, String column) {
        List<String> schemas = new ArrayList<>();
        if (described == null || column == null) {
            return schemas;
        }
        for (String line : described.split("\\R")) {
            String[] cells = line.split(" \\| ");
            if (cells.length < 2) {
                continue;
            }
            // 第一列是模式，第二列是字段名
            if (cells[1].trim().equalsIgnoreCase(column) && !schemas.contains(cells[0].trim())) {
                schemas.add(cells[0].trim());
            }
        }
        return schemas;
    }

    private JsonNode appendColumnDrift(JsonNode merged, List<ColumnDrift> drifts) {
        if (drifts.isEmpty() || !(merged instanceof ObjectNode object)) {
            return merged;
        }
        ObjectNode quality = null;
        for (JsonNode dimension : object.path("dimensions")) {
            if ("quality".equals(dimension.path("code").asText()) && dimension instanceof ObjectNode node) {
                quality = node;
            }
        }
        if (quality == null || !quality.path("findings").isArray() || !object.path("findings").isArray()) {
            return merged;
        }
        ArrayNode qualityFindings = (ArrayNode) quality.path("findings");
        ArrayNode findings = (ArrayNode) object.path("findings");
        for (ColumnDrift drift : drifts) {
            ObjectNode finding = objectMapper.createObjectNode();
            finding.put("category", "database");
            finding.put("severity", "high");
            finding.put("filePath", drift.filePath());
            finding.put("title", "实体已删除字段 " + drift.column() + "，数据库 " + drift.schema() + "." + drift.table() + " 仍有该列");
            finding.put("detail", "数据源能看到实例下的各个库。实体映射列已删除，但 " + drift.schema() + " 的表 " + drift.table() + " 仍然存在 " + drift.column() + "。");
            finding.put("suggestion", "确认是否同步删除该数据库列，或把实体字段加回去。列还在时，后续 SQL 会和实体不一致。");
            finding.put("locked", true);
            finding.put("id", "drift-" + drift.schema() + "-" + drift.table() + "-" + drift.column());
            finding.put("dimension", "quality");
            qualityFindings.add(finding);
            findings.add(finding.deepCopy());
        }
        object.put("overallSeverity", "high");
        quality.put("severity", "high");
        return object;
    }

    private record DbLookup(String text, List<ColumnDrift> drifts) {
    }

    private record ColumnDrift(String filePath, String table, String schema, String column) {
    }

    private String safeDb(java.util.function.Supplier<String> call) {
        try {
            return call.get();
        } catch (ApiException ex) {
            if (isMissingDatasource(ex)) {
                return "未配置启用且通过只读校验的数据源，已跳过。";
            }
            // 单表查不到不让整单评审失败
            return ex.getMessage();
        }
    }

    private String selectStatement(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim();
        int start = trimmed.toLowerCase(java.util.Locale.ROOT).indexOf("select");
        if (start < 0) {
            return null;
        }
        String statement = trimmed.substring(start).replace('\n', ' ').trim();
        int semicolon = statement.indexOf(';');
        if (semicolon >= 0) {
            statement = statement.substring(0, semicolon).trim();
        }
        if (statement.length() > 1000) {
            statement = statement.substring(0, 1000);
        }
        return statement;
    }

    private static boolean isBusinessDoc(String docType) {
        return "prototype".equals(docType) || "meeting".equals(docType) || "logic".equals(docType);
    }

    private static boolean isFunctionDoc(String docType) {
        return "testcase".equals(docType) || "outline".equals(docType) || "solution".equals(docType);
    }

    private DbToolPort dbPort() {
        // jdbc 与 mcp 走同一个工具名，由配置决定适配器
        if ("mcp".equals(settingsService.dbToolTransport())) {
            return mcpDbToolAdapter;
        }
        return jdbcDbToolAdapter;
    }

    private void postNote(ReviewJobEntity job, JsonNode result) {
        String marker = "<!-- crbot-job:" + job.getId() + " -->";
        if (gitlabClient.noteExists(job.getGitlabProjectId(), job.getMrIid(), marker)) {
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append(marker).append("\n## CRBOT 评审摘要\n\n");
        body.append(result.path("summary").asText("")).append("\n\n");
        int shown = 0;
        for (JsonNode finding : result.path("findings")) {
            if (shown >= 5) {
                break;
            }
            body.append("- [").append(finding.path("severity").asText()).append("] ")
                    .append(finding.path("title").asText());
            if (finding.path("locationStatus").asInt() == FindingLocator.RESOLVED && finding.path("startLine").asInt() > 0) {
                body.append(" (").append(finding.path("filePath").asText()).append(':').append(finding.path("startLine").asInt()).append(')');
            }
            body.append('\n');
            if (settingsService.postMrInline() && finding.path("locationStatus").asInt() == FindingLocator.RESOLVED
                    && ("high".equals(finding.path("severity").asText()) || "medium".equals(finding.path("severity").asText()))) {
                // 11.8 不支持行级讨论时，摘要里的 file:line 仍然保留
                gitlabClient.tryInlineComment(job.getGitlabProjectId(), job.getMrIid(), finding.path("title").asText(),
                        finding.path("filePath").asText(), finding.path("startLine").asInt(), job.getBeforeSha(), job.getAfterSha());
            }
            shown++;
        }
        gitlabClient.postMergeRequestNote(job.getGitlabProjectId(), job.getMrIid(), limit(body.toString(), 4000));
    }

    private JsonNode merge(List<JsonNode> parts, boolean knowledgeHit) {
        if (parts.size() == 1) {
            return align(parts.get(0), knowledgeHit);
        }
        ObjectNode root = objectMapper.createObjectNode();
        StringBuilder summary = new StringBuilder();
        String severity = "info";
        String status = "skipped";
        String reason = "";
        ArrayNode refs = objectMapper.createArrayNode();
        ArrayNode findings = objectMapper.createArrayNode();
        Map<String, Boolean> seenRefs = new LinkedHashMap<>();
        for (JsonNode part : parts) {
            if (summary.length() > 0) {
                summary.append('\n');
            }
            summary.append(part.path("summary").asText(""));
            if (rank(part.path("overallSeverity").asText()) > rank(severity)) {
                severity = part.path("overallSeverity").asText();
            }
            String partStatus = part.path("businessAlignment").path("status").asText();
            if ("unmatched".equals(partStatus)) {
                status = "unmatched";
                reason = part.path("businessAlignment").path("reason").asText();
            } else if ("matched".equals(partStatus) && !"unmatched".equals(status)) {
                status = "matched";
                reason = part.path("businessAlignment").path("reason").asText();
            } else if (reason.isBlank()) {
                reason = part.path("businessAlignment").path("reason").asText();
            }
            for (JsonNode ref : part.path("businessAlignment").path("kbRefs")) {
                String key = ref.path("docId").asText() + ref.path("title").asText();
                if (seenRefs.putIfAbsent(key, true) == null) {
                    refs.add(ref);
                }
            }
            for (JsonNode finding : part.path("findings")) {
                findings.add(finding);
            }
        }
        root.put("summary", limit(summary.toString(), 4000));
        root.put("overallSeverity", severity);
        ObjectNode alignment = root.putObject("businessAlignment");
        alignment.put("status", status);
        alignment.put("reason", reason);
        alignment.set("kbRefs", refs);
        root.set("findings", findings);
        return align(root, knowledgeHit);
    }

    private JsonNode align(JsonNode raw, boolean knowledgeHit) {
        ObjectNode root = (ObjectNode) raw.deepCopy();
        ReviewResultParser.AlignmentFix fix = ReviewResultParser.enforceAlignment(
                root.path("businessAlignment").path("status").asText(),
                root.path("businessAlignment").path("reason").asText(),
                knowledgeHit);
        ObjectNode alignment = (ObjectNode) root.with("businessAlignment");
        alignment.put("status", fix.status());
        alignment.put("reason", fix.reason());
        if (!knowledgeHit) {
            alignment.putArray("kbRefs");
        }
        return root;
    }

    private ObjectNode emptyResult(String summary, String reason) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("summary", summary);
        root.put("overallSeverity", "info");
        ObjectNode alignment = root.putObject("businessAlignment");
        alignment.put("status", "skipped");
        alignment.put("reason", reason);
        alignment.putArray("kbRefs");
        root.putArray("findings");
        return root;
    }

    private String userPrompt(ReviewJobEntity job, String description, List<GitlabClient.DiffFile> files,
                              List<RagSearchService.RagHit> standards, String enabledRules,
                              List<RagSearchService.RagHit> businessHits, List<RagSearchService.RagHit> functionHits,
                              String dbContext, RepoWorkspace.Checkout checkout) {
        StringBuilder builder = new StringBuilder();
        builder.append("仓库: ").append(job.getProjectPath()).append('\n');
        builder.append("分支: ").append(job.getRefBranch()).append('\n');
        builder.append("标题: ").append(job.getTitle()).append('\n');
        if (description != null && !description.isBlank()) {
            builder.append("描述: ").append(description.length() > 200 ? description.substring(0, 200) : description).append('\n');
        }
        builder.append("\n## Diff\n");
        for (GitlabClient.DiffFile file : files) {
            builder.append("FILE ").append(file.path()).append('\n').append(file.patch()).append('\n');
        }
        builder.append("\n## 已启用编码规则\n");
        if (enabledRules == null || enabledRules.isBlank()) {
            builder.append("未启用编码规范。不要编造条款。\n");
        } else {
            builder.append("下面是已启用规范提炼出的规则。diff 违反哪条，finding.category 用 standard，severity 必须是 high，standardRefs.title 用规则标题，clauseId 用规则编号。没有违反不要硬写规范问题。\n");
            builder.append(enabledRules).append('\n');
            if (!standards.isEmpty()) {
                builder.append("\n检索补充:\n").append(formatHits(standards));
            }
        }
        builder.append("\n## 业务资料（原型、会议纪要、业务逻辑）\n");
        if (businessHits.isEmpty()) {
            builder.append("未匹配。dimensions 里 business.status 必须是 skipped，不要写该维度发现。\n");
        } else {
            builder.append(formatHits(businessHits));
        }
        builder.append("\n## 功能资料（测试用例、概要设计、技术方案）\n");
        if (functionHits.isEmpty()) {
            builder.append("未匹配。dimensions 里 function.status 必须是 skipped，不要写该维度发现。\n");
        } else {
            builder.append(formatHits(functionHits));
        }
        builder.append("\n## 数据库工具结果\n").append(dbContext).append('\n');
        String feedback = reviewFeedbackService.promptBlock(job);
        if (!feedback.isBlank()) {
            builder.append("\n## 人工标注\n").append(feedback).append('\n');
        }
        if (checkout != null) {
            builder.append("\n## 本地源码\n已检出提交 ").append(checkout.sha())
                    .append("。diff 不够判断时，可以调用 read_source、search_source、find_source。是否查阅由你决定。发现的 path 仍必须落在本次 diff 的文件上。\n");
        }
        String chat = reviewFeedbackService.chatBlock(job.getId());
        if (!chat.isBlank()) {
            builder.append("\n## 人工对话\n和上面的便利贴一起作为人工意见。\n").append(chat).append('\n');
        }
        builder.append("\n请分三个维度输出 dimensions，summary 写综合结论。代码质量维度的性能和规范问题 severity 必须是 high。\n");
        builder.append("请只输出 JSON。");
        return builder.toString();
    }

    private String formatHits(List<RagSearchService.RagHit> hits) {
        StringBuilder builder = new StringBuilder();
        for (RagSearchService.RagHit hit : hits) {
            builder.append("clauseId=").append(hit.clauseId())
                    .append(" title=").append(hit.title())
                    .append(" score=").append(String.format("%.3f", hit.score()))
                    .append("\n").append(hit.content()).append("\n\n");
        }
        return builder.toString();
    }

    private ArrayNode toolDefinitions(boolean exposeDbTools, boolean sourceReady) {
        ArrayNode tools = objectMapper.createArrayNode();
        if (sourceReady) {
            addTool(tools, "read_source", "按行读取已检出提交里的源码。一次最多 500 行",
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"end_line\":{\"type\":\"integer\"}},\"required\":[\"path\"]}");
            addTool(tools, "search_source", "在已检出的仓库里搜索源码",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
            addTool(tools, "find_source", "按文件名查找已检出仓库中的文件",
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}");
        }
        addTool(tools, "get_diff_file", "读取本次评审中的单个文件 diff",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
        addTool(tools, "search_standards", "查看已检索到的编码规范条款",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"top_k\":{\"type\":\"integer\"}}}");
        addTool(tools, "search_knowledge", "查看已检索到的业务知识库片段",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"top_k\":{\"type\":\"integer\"}}}");
        addTool(tools, "submit_findings", "提交本批文件内的结构化发现",
                "{\"type\":\"object\",\"properties\":{\"findings\":{\"type\":\"array\"}},\"required\":[\"findings\"]}");
        addTool(tools, "task_done", "结束本批评审",
                "{\"type\":\"object\",\"properties\":{}}");
        if (!exposeDbTools) {
            // 没有字段/复杂 SQL，或没有数据源时，不提供查库工具
            return tools;
        }
        addTool(tools, "db_list_tables", "列出只读库表",
                "{\"type\":\"object\",\"properties\":{\"schema\":{\"type\":\"string\"}}}");
        addTool(tools, "db_describe_table", "字段变更时查看表结构",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}");
        addTool(tools, "db_list_indexes", "复杂 SQL 时查看表索引，判断慢 SQL 风险",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}");
        addTool(tools, "db_explain_sql", "只允许 EXPLAIN，用于复杂 SQL 的执行计划",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}");
        return tools;
    }

    private void addTool(ArrayNode tools, String name, String description, String schema) {
        try {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", name);
            function.put("description", description);
            function.set("parameters", objectMapper.readTree(schema));
        } catch (Exception ex) {
            throw new ApiException("组装工具定义失败");
        }
    }

    private JsonNode readArgs(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private List<List<GitlabClient.DiffFile>> partition(List<GitlabClient.DiffFile> files, int size) {
        List<List<GitlabClient.DiffFile>> batches = new ArrayList<>();
        for (int i = 0; i < files.size(); i += size) {
            batches.add(files.subList(i, Math.min(files.size(), i + size)));
        }
        return batches;
    }

    private int rank(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
