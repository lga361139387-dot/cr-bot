package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.company.crbot.knowledge.KbDocEntity;
import com.company.crbot.knowledge.KbDocRepository;
import com.company.crbot.rag.RagSearchService;
import com.company.crbot.review.FindingAnnotationService;
import com.company.crbot.review.ReviewFindingEntity;
import com.company.crbot.review.ReviewJobEntity;
import com.company.crbot.review.ReviewJobRepository;
import com.company.crbot.review.ReviewJobService;
import com.company.crbot.review.ReviewReportEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把评审和知识库收成 IDE 可读的 JSON。只读，不改任务状态。
 */
@Service
public class IdeMcpService implements IdeMcpPort {

    private static final Set<String> JOB_STATUS = Set.of("PENDING", "RUNNING", "SUCCESS", "FAILED");
    private static final Set<String> DOC_TYPES = Set.of("prototype", "logic", "meeting", "other", "outline", "solution", "testcase");

    private final ReviewJobRepository jobRepository;
    private final ReviewJobService reviewJobService;
    private final KbDocRepository docRepository;
    private final RagSearchService ragSearchService;
    private final ObjectMapper objectMapper;

    public IdeMcpService(ReviewJobRepository jobRepository, ReviewJobService reviewJobService,
                         KbDocRepository docRepository, RagSearchService ragSearchService, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.reviewJobService = reviewJobService;
        this.docRepository = docRepository;
        this.ragSearchService = ragSearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String listReviews(JsonNode args) {
        String projectPath = text(args, "projectPath");
        String branch = text(args, "branch");
        String commitSha = text(args, "commitSha");
        String pusher = text(args, "pusher");
        String status = text(args, "status");
        int limit = limit(args, "limit", 5, 20);
        if (commitSha != null && !commitSha.matches("[0-9a-fA-F]{7,64}")) {
            // 提交号只接受十六进制，避免把通配符带进查询
            throw new ApiException("commitSha 需要是至少 7 位的十六进制");
        }
        if (status != null && !JOB_STATUS.contains(status)) {
            throw new ApiException("status 只能是 PENDING、RUNNING、SUCCESS、FAILED");
        }
        List<ReviewJobEntity> jobs = jobRepository.findAll(reviewSpec(projectPath, branch, commitSha, pusher, status),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        ArrayNode items = objectMapper.createArrayNode();
        for (ReviewJobEntity job : jobs) {
            items.add(jobBrief(job));
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.set("reviews", items);
        return write(body);
    }

    @Override
    public String getReview(JsonNode args) {
        Long jobId = longValue(args, "jobId");
        if (jobId == null) {
            throw new ApiException("缺少参数 jobId");
        }
        ReviewJobService.Detail detail = reviewJobService.detail(jobId);
        ReviewJobEntity job = detail.job();
        if (job.getVoided() != null && job.getVoided() == 1) {
            // 作废记录不作为改代码依据
            throw new ApiException(HttpStatus.NOT_FOUND, "评审已作废");
        }
        ObjectNode body = jobBrief(job);
        ReviewReportEntity report = detail.report();
        if (report == null) {
            body.put("summary", "");
            body.putArray("findings");
            body.putArray("annotations");
            return write(body);
        }
        body.put("summary", clip(report.getSummary(), 2000));
        body.put("overallSeverity", textOrEmpty(report.getOverallSeverity()));
        body.put("businessStatus", textOrEmpty(report.getBusinessStatus()));
        body.put("businessReason", clip(report.getBusinessReason(), 1000));
        ArrayNode findings = body.putArray("findings");
        for (ReviewFindingEntity finding : detail.findings()) {
            findings.add(findingNode(finding));
        }
        ArrayNode annotations = body.putArray("annotations");
        for (FindingAnnotationService.AnnotationView view : detail.annotations()) {
            ObjectNode row = annotations.addObject();
            row.put("findingId", view.findingId() == null ? 0 : view.findingId());
            row.put("label", view.label());
            row.put("remark", clip(view.remark(), 500));
        }
        return write(body);
    }

    @Override
    public String searchKnowledge(JsonNode args) {
        String query = text(args, "query");
        if (query == null) {
            throw new ApiException("缺少参数 query");
        }
        String docType = text(args, "docType");
        if (docType != null && !DOC_TYPES.contains(docType)) {
            throw new ApiException("docType 不在知识库类型里");
        }
        int topK = limit(args, "topK", 6, 10);
        List<KbDocEntity> docs = indexedDocs(docType);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        ArrayNode hits = body.putArray("hits");
        if (docs.isEmpty()) {
            // 没有可检索文档时直接说明，避免调用方把空列表当成检索失败
            body.put("message", "没有已索引的知识库文档");
            return write(body);
        }
        Map<Long, KbDocEntity> byId = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>(docs.size());
        for (KbDocEntity doc : docs) {
            byId.put(doc.getId(), doc);
            ids.add(doc.getId());
        }
        List<RagSearchService.RagHit> found = ragSearchService.search("knowledge", ids, query, topK, null);
        for (RagSearchService.RagHit hit : found) {
            KbDocEntity doc = byId.get(hit.ownerId());
            ObjectNode row = hits.addObject();
            row.put("docId", hit.ownerId());
            row.put("title", doc == null ? hit.title() : doc.getTitle());
            row.put("docType", doc == null ? "" : doc.getDocType());
            row.put("chunkIndex", hit.chunkIndex());
            row.put("score", hit.score());
            row.put("excerpt", clip(hit.content(), 1200));
        }
        return write(body);
    }

    private Specification<ReviewJobEntity> reviewSpec(String projectPath, String branch, String commitSha,
                                                      String pusher, String status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 作废评审不再作为改代码依据
            predicates.add(cb.or(cb.isNull(root.get("voided")), cb.equal(root.get("voided"), (short) 0)));
            if (projectPath != null) {
                String literal = escapeLike(projectPath);
                // 完整路径全等；只给仓库名时匹配路径末段
                predicates.add(cb.or(
                        cb.equal(root.get("projectPath"), projectPath),
                        cb.like(root.get("projectPath"), "%/" + literal, '\\')
                ));
            }
            if (branch != null) {
                predicates.add(cb.equal(root.get("refBranch"), branch));
            }
            if (commitSha != null) {
                // 短 SHA 按前缀匹配，大小写都对得上
                predicates.add(cb.like(cb.lower(root.get("commitSha")), commitSha.toLowerCase(Locale.ROOT) + "%"));
            }
            if (pusher != null) {
                predicates.add(cb.equal(root.get("pusherName"), pusher));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private List<KbDocEntity> indexedDocs(String docType) {
        List<KbDocEntity> indexed = docRepository.findByStatusOrderByIdAsc("INDEXED");
        if (docType == null) {
            return indexed;
        }
        List<KbDocEntity> matched = new ArrayList<>();
        for (KbDocEntity doc : indexed) {
            // 类型在入库前就限定了，这里只收窄检索范围
            if (docType.equals(doc.getDocType())) {
                matched.add(doc);
            }
        }
        return matched;
    }

    private ObjectNode jobBrief(ReviewJobEntity job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.getId());
        node.put("projectPath", textOrEmpty(job.getProjectPath()));
        node.put("branch", textOrEmpty(job.getRefBranch()));
        node.put("commitSha", textOrEmpty(job.getCommitSha()));
        node.put("pusher", textOrEmpty(job.getPusherName()));
        node.put("title", textOrEmpty(job.getTitle()));
        node.put("status", textOrEmpty(job.getStatus()));
        node.put("errorMessage", clip(job.getErrorMessage(), 500));
        return node;
    }

    private ObjectNode findingNode(ReviewFindingEntity finding) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", finding.getId());
        node.put("category", textOrEmpty(finding.getCategory()));
        node.put("severity", textOrEmpty(finding.getSeverity()));
        node.put("filePath", textOrEmpty(finding.getFilePath()));
        node.put("startLine", finding.getStartLine() == null ? 0 : finding.getStartLine());
        node.put("endLine", finding.getEndLine() == null ? 0 : finding.getEndLine());
        node.put("locationStatus", finding.getLocationStatus() == null ? 0 : finding.getLocationStatus());
        node.put("locationStatusText", locationText(finding.getLocationStatus()));
        node.put("title", textOrEmpty(finding.getTitle()));
        node.put("detail", clip(finding.getDetail(), 2000));
        node.put("suggestion", clip(finding.getSuggestion(), 2000));
        node.put("existingCode", clip(finding.getExistingCode(), 2000));
        node.put("suggestionCode", clip(finding.getSuggestionCode(), 2000));
        node.put("refsJson", clip(finding.getRefsJson(), 1500));
        return node;
    }

    private String locationText(Short status) {
        if (status == null) {
            return "未知";
        }
        // 与 FindingLocator 一致：1 已在 diff 里定位到行
        if (status == 1) {
            return "已定位";
        }
        if (status == 2) {
            return "未定位";
        }
        if (status == 3) {
            return "无证据行号";
        }
        return "未知";
    }

    private int limit(JsonNode args, String field, int fallback, int max) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return fallback;
        }
        int value = node.asInt();
        if (value < 1) {
            return 1;
        }
        return Math.min(value, max);
    }

    private Long longValue(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asLong();
    }

    private String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.indexOf('%') >= 0) {
            throw new ApiException(field + " 不能包含 %");
        }
        return value;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("_", "\\_");
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ApiException("结果无法序列化");
        }
    }
}
