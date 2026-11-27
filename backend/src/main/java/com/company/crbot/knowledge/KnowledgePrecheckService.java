package com.company.crbot.knowledge;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import com.company.crbot.review.LlmClient;
import com.company.crbot.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgePrecheckService {

    static final String OPEN = "OPEN";
    static final String DONE = "DONE";
    static final String SUPERSEDED = "SUPERSEDED";
    private static final int PROMPT_CHARS = 20000;

    private static final String PROMPT = """
            你是知识库入库预审员。下面是解析后的原始文本。
            禁止修改、增删、改写、概括原文。不要输出修订稿。
            只识别：歧义、信息缺失、互相冲突、语义模糊、需要人工确认的片段。
            excerpt 必须是原文中连续出现的原句，逐字复制。
            没有疑点时 doubts 为空数组。
            只输出 JSON：{"doubts":[{"excerpt":"原文片段","question":"为什么存疑"}]}
            """;

    private final KbPrecheckRepository precheckRepository;
    private final KbPrecheckDoubtRepository doubtRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public KnowledgePrecheckService(KbPrecheckRepository precheckRepository, KbPrecheckDoubtRepository doubtRepository,
                                    LlmClient llmClient, ObjectMapper objectMapper, SettingsService settingsService) {
        this.precheckRepository = precheckRepository;
        this.doubtRepository = doubtRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public boolean enabled() {
        return settingsService.kbPrecheckEnabled();
    }

    public boolean needsReview(String docType, String extension) {
        return PrecheckTexts.needsReview(settingsService.kbPrecheckEnabled(), docType, extension,
                settingsService.kbPrecheckSkipExtensions(), settingsService.kbPrecheckForceDocTypes());
    }

    /**
     * 把全文交给模型梳理。禁止精简，只理顺结构，返回可入库的正文。
     */
    public String organize(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiException("正文为空，不能梳理");
        }
        StringBuilder organized = new StringBuilder();
        for (String part : split(content, 8000)) {
            if (part.isBlank()) {
                continue;
            }
            String piece = askOrganize(part);
            // 梳理只能理顺原文。短于七成是被精简了，长出一截以上是模型补写了原文没有的内容
            if (part.length() > 400 && piece.length() * 10 < part.length() * 7) {
                throw new ApiException("梳理结果短于原文，未替换入库");
            }
            if (piece.length() > part.length() + Math.max(part.length(), 400)) {
                throw new ApiException("梳理结果补充了原文没有的内容，未替换入库");
            }
            if (organized.length() > 0) {
                organized.append("\n\n");
            }
            organized.append(piece);
        }
        return organized.toString().trim();
    }

    private String askOrganize(String content) {
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", """
                把下面的知识库原文梳理成清楚的正文。
                保留全部事实、数字、名称、条件、步骤和原意，禁止精简、省略、概括性缩写，禁止补充原文没有的结论。
                可以分段和加小标题。只输出梳理后的正文，不要 JSON，不要前言。
                """);
        messages.addObject().put("role", "user").put("content", content);
        String text = llmClient.chat(messages, null).path("content").asText("").trim();
        if (text.isBlank()) {
            throw new ApiException("模型没有返回梳理正文");
        }
        return text;
    }

    private static List<String> split(String content, int size) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + size);
            if (end < content.length()) {
                int breakAt = content.lastIndexOf('\n', end);
                if (breakAt > start + size / 2) {
                    end = breakAt;
                }
            }
            parts.add(content.substring(start, end).trim());
            start = end;
        }
        return parts;
    }

    /**
     * 调用模型识别疑点。返回 true 表示必须等人确认后才能分块入库。
     * 返回 false 表示没有可确认的疑点，调用方继续用原文入库。
     */
    @Transactional
    public boolean open(KbDocEntity doc) {
        supersede(doc.getId());
        List<PrecheckTexts.Doubt> doubts = ask(doc.getContentText());
        KbPrecheckEntity session = new KbPrecheckEntity();
        session.setDocId(doc.getId());
        session.setIterationId(doc.getIterationId());
        session.setOperator(CurrentUser.username());
        if (doubts.isEmpty()) {
            session.setStatus(DONE);
            session.setFinishedAt(OffsetDateTime.now());
            precheckRepository.save(session);
            return false;
        }
        session.setStatus(OPEN);
        precheckRepository.save(session);
        for (PrecheckTexts.Doubt doubt : doubts) {
            KbPrecheckDoubtEntity row = new KbPrecheckDoubtEntity();
            row.setPrecheckId(session.getId());
            row.setDocId(doc.getId());
            row.setExcerpt(doubt.excerpt());
            row.setQuestion(doubt.question());
            doubtRepository.save(row);
        }
        return true;
    }

    @Transactional
    public void confirm(Long docId, List<PrecheckTexts.Decision> decisions) {
        KbPrecheckEntity session = openSession(docId);
        List<KbPrecheckDoubtEntity> rows = doubtRepository.findByPrecheckIdOrderByIdAsc(session.getId());
        Set<Long> ids = new LinkedHashSet<>();
        for (KbPrecheckDoubtEntity row : rows) {
            ids.add(row.getId());
        }
        PrecheckTexts.requireDecisions(ids, decisions);
        OffsetDateTime now = OffsetDateTime.now();
        String operator = CurrentUser.username();
        for (KbPrecheckDoubtEntity row : rows) {
            PrecheckTexts.Decision decision = find(decisions, row.getId());
            row.setDecision(decision.action().trim());
            row.setNote(blankToNull(decision.note()));
            row.setOperator(operator);
            row.setDecidedAt(now);
            doubtRepository.save(row);
        }
        session.setStatus(DONE);
        session.setFinishedAt(now);
        session.setOperator(operator);
        precheckRepository.save(session);
    }

    public PrecheckView view(Long docId) {
        KbPrecheckEntity session = precheckRepository.findFirstByDocIdOrderByIdDesc(docId).orElse(null);
        if (session == null) {
            return new PrecheckView(null, null, List.of());
        }
        List<DoubtView> doubts = new ArrayList<>();
        for (KbPrecheckDoubtEntity row : doubtRepository.findByPrecheckIdOrderByIdAsc(session.getId())) {
            doubts.add(new DoubtView(row.getId(), row.getExcerpt(), row.getQuestion(), row.getDecision(),
                    row.getNote(), row.getOperator(), row.getDecidedAt()));
        }
        return new PrecheckView(session.getId(), session.getStatus(), doubts);
    }

    private List<PrecheckTexts.Doubt> ask(String content) {
        String sample = content == null ? "" : content;
        boolean truncated = sample.length() > PROMPT_CHARS;
        if (truncated) {
            sample = sample.substring(0, PROMPT_CHARS);
        }
        ArrayNode messages = objectMapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", PROMPT);
        String user = truncated ? "原文较长，下面只给出开头，疑点必须来自这段文字。\n" + sample : sample;
        messages.addObject().put("role", "user").put("content", user);
        JsonNode message = llmClient.chat(messages, null);
        List<PrecheckTexts.Doubt> raw = readDoubts(message.path("content").asText(""));
        List<PrecheckTexts.Doubt> accepted = PrecheckTexts.accepted(truncated ? sample : content, raw);
        if (!raw.isEmpty() && accepted.isEmpty()) {
            throw new ApiException("模型返回的疑点不在原文中");
        }
        return accepted;
    }

    private List<PrecheckTexts.Doubt> readDoubts(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ApiException("模型没有返回预审 JSON");
        }
        try {
            JsonNode doubts = objectMapper.readTree(trimmed.substring(start, end + 1)).path("doubts");
            List<PrecheckTexts.Doubt> values = new ArrayList<>();
            if (doubts.isArray()) {
                for (JsonNode node : doubts) {
                    values.add(new PrecheckTexts.Doubt(node.path("excerpt").asText(""), node.path("question").asText("")));
                }
            }
            return values;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("解析预审结果失败");
        }
    }

    private void supersede(Long docId) {
        for (KbPrecheckEntity old : precheckRepository.findByDocIdAndStatus(docId, OPEN)) {
            old.setStatus(SUPERSEDED);
            old.setFinishedAt(OffsetDateTime.now());
            precheckRepository.save(old);
        }
    }

    private KbPrecheckEntity openSession(Long docId) {
        KbPrecheckEntity session = precheckRepository.findFirstByDocIdOrderByIdDesc(docId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "没有预审记录"));
        if (!OPEN.equals(session.getStatus())) {
            throw new ApiException("当前预审已结束");
        }
        return session;
    }

    private PrecheckTexts.Decision find(List<PrecheckTexts.Decision> decisions, Long id) {
        for (PrecheckTexts.Decision decision : decisions) {
            if (id.equals(decision.doubtId())) {
                return decision;
            }
        }
        throw new ApiException("请先确认全部疑点");
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record PrecheckView(Long id, String status, List<DoubtView> doubts) {
    }

    public record DoubtView(Long id, String excerpt, String question, String decision, String note,
                            String operator, OffsetDateTime decidedAt) {
    }
}
