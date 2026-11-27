package com.company.crbot;

import com.company.crbot.common.ApiException;
import com.company.crbot.knowledge.KnowledgeFiles;
import com.company.crbot.mcp.SqlFirewall;
import com.company.crbot.rag.Chunker;
import com.company.crbot.rag.RagSearchService;
import com.company.crbot.review.ConversationCompressor;
import com.company.crbot.review.DiffBatcher;
import com.company.crbot.review.DiffFilter;
import com.company.crbot.review.FindingCollector;
import com.company.crbot.review.FindingLocator;
import com.company.crbot.review.ReviewFactFilter;
import com.company.crbot.standard.PathRuleRegistry;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.company.crbot.review.RepoWorkspace;
import com.company.crbot.review.ReviewResultParser;
import com.company.crbot.review.SymbolExtractor;
import com.company.crbot.gitlab.GitlabClient;
import com.company.crbot.standard.StandardRuleExtractor;
import com.company.crbot.subscription.BranchPatternMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrbotCoreTest {

    @Test
    void branchPatternSupportsExactPrefixAndWildcard() {
        // 精确、单层通配、跨目录通配都要能区分
        assertTrue(BranchPatternMatcher.matches("master", "master"));
        assertFalse(BranchPatternMatcher.matches("master", "master2"));
        assertTrue(BranchPatternMatcher.matches("release/*", "release/1.0"));
        assertFalse(BranchPatternMatcher.matches("release/*", "release/1.0/hotfix"));
        assertTrue(BranchPatternMatcher.matches("feature/**", "feature/pay/refund"));
        assertFalse(BranchPatternMatcher.matches("feature/**", "hotfix/pay"));
    }

    @Test
    void sqlFirewallRejectsUpdateAndMultiStatement() {
        assertThrows(RuntimeException.class, () -> SqlFirewall.requireExplain("UPDATE orders SET status = 1"));
        assertThrows(RuntimeException.class, () -> SqlFirewall.requireExplain("EXPLAIN SELECT 1; SELECT 2"));
        assertThrows(RuntimeException.class, () -> SqlFirewall.requireExplain("EXPLAIN ANALYZE SELECT 1"));
        assertEquals("EXPLAIN SELECT 1", SqlFirewall.requireExplain("EXPLAIN SELECT 1;"));
        assertEquals("public.orders", SqlFirewall.requireTable("public.orders"));
        assertThrows(RuntimeException.class, () -> SqlFirewall.requireTable("orders;drop"));
    }

    @Test
    void reviewJsonMustMatchSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {"summary":"无规范问题","overallSeverity":"info",
                "businessAlignment":{"status":"skipped","reason":"知识库未命中","kbRefs":[]},
                "findings":[]}
                """;
        ReviewResultParser.parse(json, mapper);
        String raised = """
                {"summary":"缺注释","overallSeverity":"low",
                "businessAlignment":{"status":"skipped","reason":"知识库未命中","kbRefs":[]},
                "findings":[
                  {"title":"缺注释","category":"standard","severity":"low"},
                  {"title":"循环查库","category":"performance","severity":"medium"}
                ]}
                """;
        JsonNode fixed = ReviewResultParser.parse(raised, mapper);
        assertEquals("high", fixed.path("overallSeverity").asText());
        assertEquals("high", fixed.path("findings").get(0).path("severity").asText());
        assertEquals("high", fixed.path("findings").get(1).path("severity").asText());
        JsonNode dimensions = ReviewResultParser.normalizeDimensions(fixed, false, true);
        assertEquals("skipped", dimensions.path("dimensions").get(1).path("status").asText());
        assertEquals("reviewed", dimensions.path("dimensions").get(2).path("status").asText());
        assertThrows(ReviewResultParser.ReviewParseException.class,
                () -> ReviewResultParser.parse("{\"summary\":\"x\"}", mapper));
        ReviewResultParser.AlignmentFix skipped = ReviewResultParser.enforceAlignment("matched", "臆造", false);
        assertEquals("skipped", skipped.status());
        ReviewResultParser.AlignmentFix forced = ReviewResultParser.enforceAlignment("skipped", "跳过", true);
        assertEquals("unmatched", forced.status());
    }

    @Test
    void precheckKeepsOnlyVerbatimExcerptsAndRequiresEveryDecision() {
        String source = "退款在 T+1 到账。手续费另计。";
        var accepted = com.company.crbot.knowledge.PrecheckTexts.accepted(source, java.util.List.of(
                new com.company.crbot.knowledge.PrecheckTexts.Doubt("退款在 T+1 到账。", "到账时点没有说明工作日"),
                new com.company.crbot.knowledge.PrecheckTexts.Doubt("退款次日到账", "这是模型改写，不能当作原文"),
                new com.company.crbot.knowledge.PrecheckTexts.Doubt("  ", "空片段")
        ));
        assertEquals(1, accepted.size());
        assertEquals("退款在 T+1 到账。", accepted.get(0).excerpt());
        assertFalse(com.company.crbot.knowledge.PrecheckTexts.needsReview(false, "meeting", "docx", java.util.Set.of("txt"), java.util.Set.of("meeting")));
        assertTrue(com.company.crbot.knowledge.PrecheckTexts.needsReview(true, "meeting", "md", java.util.Set.of("md"), java.util.Set.of("meeting")));
        assertFalse(com.company.crbot.knowledge.PrecheckTexts.needsReview(true, "logic", "md", java.util.Set.of("md"), java.util.Set.of("meeting")));
        assertTrue(com.company.crbot.knowledge.PrecheckTexts.needsReview(true, "logic", "", java.util.Set.of("md"), java.util.Set.of("meeting")));
        var ids = java.util.Set.of(1L, 2L);
        assertThrows(ApiException.class, () -> com.company.crbot.knowledge.PrecheckTexts.requireDecisions(ids, java.util.List.of(
                new com.company.crbot.knowledge.PrecheckTexts.Decision(1L, "CONFIRMED", null))));
        assertThrows(ApiException.class, () -> com.company.crbot.knowledge.PrecheckTexts.requireDecisions(ids, java.util.List.of(
                new com.company.crbot.knowledge.PrecheckTexts.Decision(1L, "SUPPLEMENT", "  "),
                new com.company.crbot.knowledge.PrecheckTexts.Decision(2L, "DISCARD", null))));
        com.company.crbot.knowledge.PrecheckTexts.requireDecisions(ids, java.util.List.of(
                new com.company.crbot.knowledge.PrecheckTexts.Decision(1L, "SUPPLEMENT", "按自然日"),
                new com.company.crbot.knowledge.PrecheckTexts.Decision(2L, "DISCARD", null)));
        assertEquals("错误", com.company.crbot.review.AnnotationLabels.text("FALSE_POSITIVE"));
        assertThrows(ApiException.class, () -> com.company.crbot.review.AnnotationLabels.require("MAYBE"));
    }

    @Test
    void onlyFieldOrComplexSqlNeedsDatabase() {
        String annotationOnly = """
                @@ -19,7 +19,7 @@
                -@EqualsAndHashCode(callSuper = true)
                +@EqualsAndHashCode(callSuper = false)
                 @Data
                 @TableName("CHAIN_EQUITY_SPU")
                """;
        SymbolExtractor.DbChange annotation = SymbolExtractor.dbChange(List.of(new GitlabClient.DiffFile(
                "dbsdk/entity/EquitySpuDo.java", "dbsdk/entity/EquitySpuDo.java", annotationOnly, false, false)));
        assertFalse(annotation.needsLookup());
        String field = """
                @@
                +    private String supplierCode;
                 @TableName("CHAIN_EQUITY_SPU")
                """;
        SymbolExtractor.DbChange schema = SymbolExtractor.dbChange(List.of(new GitlabClient.DiffFile(
                "dbsdk/entity/EquitySpuDo.java", "dbsdk/entity/EquitySpuDo.java", field, false, false)));
        assertTrue(schema.needsLookup());
        assertTrue(schema.schemaTables().contains("CHAIN_EQUITY_SPU"));
        String join = """
                @@
                + select * from CHAIN_EQUITY_SPU a join CHAIN_EQUITY_SKU b on a.id = b.spu_id where a.name like '%x'
                """;
        SymbolExtractor.DbChange sql = SymbolExtractor.dbChange(List.of(new GitlabClient.DiffFile(
                "mapper/SpuMapper.xml", "mapper/SpuMapper.xml", join, false, false)));
        assertTrue(sql.needsLookup());
        assertTrue(sql.sqlTables().contains("CHAIN_EQUITY_SPU"));
        String removed = """
                @@ -105,9 +105,6 @@ public class ActivityDo {
                     private Integer isDeleted;
                -
                -    /** 预热天数。 */
                -    @TableField("WARM_UP_DAYS")
                -    private Integer warmUpDays;
                """;
        SymbolExtractor.DbChange deletion = SymbolExtractor.dbChange(List.of(new GitlabClient.DiffFile(
                "dbsdk/entity/ActivityDo.java", "dbsdk/entity/ActivityDo.java", removed, false, false)));
        assertTrue(deletion.needsLookup());
        assertTrue(deletion.schemaTables().isEmpty());
        assertTrue(deletion.removedColumns().contains("WARM_UP_DAYS"));
        assertEquals("MALL_ACTIVITY", SymbolExtractor.tableNameIn("@TableName(\"MALL_ACTIVITY\")\npublic class ActivityDo {}"));
    }

    @Test
    void existingCodeResolvesLineOrStaysUnresolved() {
        String patch = """
                @@ -10,3 +20,4 @@
                 context
                -old line
                +private Integer warmUpDays;
                 context2
                """;
        FindingLocator.Location hit = FindingLocator.locate("A.java", "private Integer warmUpDays;", patch);
        assertEquals(FindingLocator.RESOLVED, hit.status());
        assertEquals(21, hit.startLine());
        assertEquals(21, hit.endLine());
        FindingLocator.Location miss = FindingLocator.locate("A.java", "this code is not in the diff", patch);
        assertEquals(FindingLocator.UNRESOLVED, miss.status());
        assertEquals(0, miss.startLine());
        FindingLocator.Location marked = FindingLocator.locate("A.java", "+private Integer warmUpDays;\r", patch);
        assertEquals(FindingLocator.RESOLVED, marked.status());
        assertEquals(21, marked.startLine());
        FindingLocator.Location empty = FindingLocator.locate("A.java", "  ", patch);
        assertEquals(FindingLocator.SKIPPED, empty.status());
    }

    @Test
    void batchesKeepMapperPairsAndSkipHugeFiles() {
        List<GitlabClient.DiffFile> files = List.of(
                new GitlabClient.DiffFile("a/OrderMapper.java", "a/OrderMapper.java", "interface OrderMapper {}", false, false),
                new GitlabClient.DiffFile("a/OrderMapper.xml", "a/OrderMapper.xml", "<mapper/>", false, false),
                new GitlabClient.DiffFile("b/Huge.sql", "b/Huge.sql", "x".repeat(200), false, false)
        );
        DiffBatcher.BatchPlan plan = DiffBatcher.plan(files, 8, 50);
        assertEquals(1, plan.skipped().size());
        assertEquals("too_large", plan.skipped().get(0).reason());
        assertEquals(1, plan.batches().size());
        assertEquals(2, plan.batches().get(0).size());
    }

    @Test
    void packByContextReviewsWholeChangeInLoops() {
        String huge = "x".repeat(DiffBatcher.CONTEXT_CHARS + 500);
        List<GitlabClient.DiffFile> files = List.of(
                new GitlabClient.DiffFile("a/OrderMapper.java", "a/OrderMapper.java", "interface OrderMapper {}", false, false),
                new GitlabClient.DiffFile("a/OrderMapper.xml", "a/OrderMapper.xml", "<mapper/>", false, false),
                new GitlabClient.DiffFile("b/Huge.sql", "b/Huge.sql", huge, false, false)
        );
        DiffBatcher.BatchPlan plan = DiffBatcher.packByContext(files);
        // 超长改动拆成多批，不跳过
        assertTrue(plan.skipped().isEmpty());
        int pieces = 0;
        for (List<GitlabClient.DiffFile> batch : plan.batches()) {
            int chars = 0;
            for (GitlabClient.DiffFile file : batch) {
                chars += file.patch() == null ? 0 : file.patch().length();
                pieces++;
            }
            assertTrue(chars <= DiffBatcher.CONTEXT_CHARS);
        }
        assertTrue(pieces >= 3);
    }

    @Test
    void pathChecklistFollowsFileType() {
        String javaOnly = PathRuleRegistry.checklist(List.of(new GitlabClient.DiffFile("A.java", "A.java", "class A {}", false, false)));
        assertTrue(javaOnly.contains("空指针"));
        assertFalse(javaOnly.contains("拼接"));
        String mapper = PathRuleRegistry.checklist(List.of(new GitlabClient.DiffFile("OrderMapper.xml", "OrderMapper.xml", "<mapper/>", false, false)));
        assertTrue(mapper.contains("#{}"));
        assertFalse(mapper.contains("空指针"));
    }

    @Test
    void factFilterKeepsProtectedFindings() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode findings = mapper.createArrayNode();
        findings.addObject().put("id", "s1").put("category", "security").put("title", "注入");
        findings.addObject().put("id", "d1").put("category", "database").put("title", "列还在").put("locked", true);
        findings.addObject().put("id", "q1").put("category", "quality").put("title", "命名");
        assertFalse(ReviewFactFilter.removableIds(findings).contains("s1"));
        assertFalse(ReviewFactFilter.removableIds(findings).contains("d1"));
        assertTrue(ReviewFactFilter.removableIds(findings).contains("q1"));
        ObjectNode root = mapper.createObjectNode();
        root.set("findings", findings);
        root.putArray("dimensions");
        assertEquals(1, ReviewFactFilter.remove(root, Set.of("q1", "d1")));
        assertEquals(2, root.path("findings").size());
    }

    @Test
    void compressorDropsMiddleMessages() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode messages = mapper.createArrayNode();
        for (int i = 0; i < 6; i++) {
            messages.addObject().put("role", i == 0 ? "system" : "user").put("content", "m" + i);
        }
        ArrayNode compressed = ConversationCompressor.compress(messages, 80);
        assertTrue(compressed.size() < messages.size());
        assertEquals("system", compressed.get(0).path("role").asText());
    }

    @Test
    void collectorRejectsPathOutsideBatch() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.putArray("findings").addObject().put("filePath", "other.java").put("category", "quality").put("severity", "low").put("title", "x");
        FindingCollector collector = new FindingCollector();
        String result = collector.submit(args, Set.of("A.java"));
        assertTrue(result.startsWith("错误"));
        assertTrue(collector.isEmpty());
    }

    @Test
    void relocateOnlyWhenTheSnippetIsUnique() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode finding = mapper.createObjectNode();
        finding.put("filePath", "A.java");
        finding.put("existingCode", "only in b");
        finding.put("locationStatus", FindingLocator.UNRESOLVED);
        String patchA = "@@ -1,1 +1,1 @@\n-old\n+aaa\n";
        String patchB = "@@ -1,1 +1,1 @@\n-old\n+only in b\n";
        List<GitlabClient.DiffFile> files = List.of(
                new GitlabClient.DiffFile("A.java", "A.java", patchA, false, false),
                new GitlabClient.DiffFile("B.java", "B.java", patchB, false, false)
        );
        FindingLocator.apply(mapper.createObjectNode().set("findings", mapper.createArrayNode().add(finding)), files);
        assertEquals("B.java", finding.path("filePath").asText());
        assertEquals(FindingLocator.RESOLVED, finding.path("locationStatus").asInt());
    }

    @Test
    void diffFilterDropsLockFilesAndKeepsJava() {
        List<GitlabClient.DiffFile> files = List.of(
                new GitlabClient.DiffFile("src/A.java", "src/A.java", "class A {}", false, false),
                new GitlabClient.DiffFile("package-lock.json", "package-lock.json", "{}", false, false),
                new GitlabClient.DiffFile("web/app.min.js", "web/app.min.js", "var a=1", false, false)
        );
        List<GitlabClient.DiffFile> kept = DiffFilter.filter(files);
        assertEquals(1, kept.size());
        assertEquals("src/A.java", kept.get(0).path());
        assertEquals(2, DiffFilter.partition(files).skipped().size());
        assertEquals("锁文件或压缩脚本", DiffFilter.partition(files).skipped().get(0).reason());
    }

    @Test
    void chunkerKeepsHeadingAndSplitsLongText() {
        String text = """
                # 日志
                禁止 System.out。
                # 其他
                使用日志框架。
                """;
        List<String> chunks = Chunker.chunkStandard(text);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("禁止 System.out")));
        String longText = "甲".repeat(2000);
        assertTrue(Chunker.window(longText, 420, 40).size() > 1);
        String table = "模块\t用例名称\n" + "代客兑换\t" + "查".repeat(800) + "\n" + "代客兑换\t空列表\n";
        List<String> tableChunks = Chunker.chunkKnowledge("logic", table);
        assertTrue(tableChunks.size() > 1);
        assertTrue(tableChunks.stream().allMatch(chunk -> chunk.length() <= 420));
        assertTrue(tableChunks.stream().anyMatch(chunk -> chunk.contains("空列表")));
    }

    @Test
    void standardMarkdownBecomesNumberedRules() {
        String text = """
                # 团队编码规范
                评审时引用标题。
                ## Java 必须写行级注释
                适用范围：Java。
                - if 必须有注释
                反例：分支没有任何注释。
                ## 不要写兜底
                - 禁止自行降级
                """;
        List<StandardRuleExtractor.Rule> rules = StandardRuleExtractor.extract(text);
        assertEquals(2, rules.size());
        assertEquals("R1", rules.get(0).code());
        assertEquals("Java 必须写行级注释", rules.get(0).title());
        assertTrue(rules.get(0).requirements().contains("if 必须有注释"));
        assertTrue(rules.get(0).counterexamples().contains("分支没有任何注释。"));
        assertEquals("R2", rules.get(1).code());
        assertTrue(StandardRuleExtractor.render("规范", rules).contains("R2 不要写兜底"));
    }

    @Test
    void hybridScoreUsesSpecifiedWeights() {
        assertEquals(0.7, RagSearchService.hybrid(1, 0), 0.0001);
        assertEquals(0.3, RagSearchService.hybrid(0, 1), 0.0001);
        assertEquals(0.5, RagSearchService.keywordScore("禁止 System.out", List.of("System", "missing")), 0.0001);
    }

    @Test
    void sourceReadStaysInsideRepoAndLimitsLines() {
        Path root = Path.of("data", "repos", "1");
        assertThrows(ApiException.class, () -> RepoWorkspace.resolveInside(root, "../secret.txt"));
        String text = RepoWorkspace.slice("A.java", List.of("class A {}", "void run() {}"), 1, 0);
        assertTrue(text.contains("1|class A {}"));
        assertTrue(text.contains("LINE_RANGE: 1-2"));
        String limited = RepoWorkspace.slice("A.java", java.util.Collections.nCopies(600, "line"), 1, 600);
        assertTrue(limited.contains("结果已截到 500 行"));
    }

    @Test
    void xmindReadsTopicsUnderNamedCanvas() throws Exception {
        String json = """
                [{"title":"思维导图","rootTopic":{"title":"代客兑换","children":{"attached":[{"title":"兑换条件","notes":{"plain":{"content":"需登录"}}}]}}}]
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("content.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String text = KnowledgeFiles.xmindText(out.toByteArray());
        assertTrue(text.contains("代客兑换"));
        assertTrue(text.contains("兑换条件"));
        assertTrue(text.contains("需登录"));
    }
}
