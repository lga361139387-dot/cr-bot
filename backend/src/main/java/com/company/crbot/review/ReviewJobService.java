package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewJobService {

    private final ReviewJobRepository jobRepository;
    private final ReviewReportRepository reportRepository;
    private final ReviewFindingRepository findingRepository;
    private final FindingAnnotationService annotationService;
    private final JdbcTemplate jdbcTemplate;

    public ReviewJobService(ReviewJobRepository jobRepository, ReviewReportRepository reportRepository,
                            ReviewFindingRepository findingRepository, FindingAnnotationService annotationService,
                            JdbcTemplate jdbcTemplate) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.findingRepository = findingRepository;
        this.annotationService = annotationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void insertPending(ReviewJobEntity job) {
        // 由调用方处理唯一约束冲突，这里只负责提交插入
        jobRepository.saveAndFlush(job);
    }

    @Transactional
    public void requeueRunning() {
        // 进程崩溃时把评审中的任务放回队列
        jdbcTemplate.update("""
                UPDATE review_job
                SET status = 'PENDING', started_at = NULL
                WHERE status = 'RUNNING'
                """);
    }

    @Transactional
    public Long claim() {
        // 同一 GitLab 项目已有评审在跑时，后面的任务留在队列里按 id 顺序等
        List<Long> ids = jdbcTemplate.query("""
                SELECT j.id FROM review_job j
                WHERE j.status = 'PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM review_job r
                    WHERE r.status = 'RUNNING'
                      AND r.gitlab_project_id = j.gitlab_project_id
                  )
                ORDER BY j.id
                FOR UPDATE OF j SKIP LOCKED
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1));
        if (ids.isEmpty()) {
            return null;
        }
        Long id = ids.get(0);
        jdbcTemplate.update("""
                UPDATE review_job
                SET status = 'RUNNING', started_at = NOW(), error_message = NULL
                WHERE id = ? AND status = 'PENDING'
                """, id);
        return id;
    }

    @Transactional
    public void failIfRunning(Long id, String message) {
        jdbcTemplate.update("""
                UPDATE review_job
                SET status = 'FAILED', error_message = ?, finished_at = NOW()
                WHERE id = ? AND status = 'RUNNING'
                """, trim(message), id);
    }

    @Transactional
    public void saveReport(Long jobId, JsonNode result, String markdown, String modelName, String promptVersion) {
        ReviewReportEntity report = new ReviewReportEntity();
        report.setJobId(jobId);
        report.setSummary(result.path("summary").asText(""));
        report.setOverallSeverity(result.path("overallSeverity").asText());
        report.setBusinessStatus(result.path("businessAlignment").path("status").asText());
        report.setBusinessReason(result.path("businessAlignment").path("reason").asText());
        report.setRawJson(result.toString());
        report.setMarkdown(markdown);
        report.setModelName(modelName);
        report.setPromptVersion(promptVersion);
        reportRepository.save(report);
        ReviewJobEntity job = jobRepository.findById(jobId).orElse(null);
        String pusherName = job == null ? null : job.getPusherName();
        for (JsonNode finding : result.path("findings")) {
            ReviewFindingEntity entity = new ReviewFindingEntity();
            entity.setReportId(report.getId());
            entity.setCategory(finding.path("category").asText());
            entity.setSeverity(finding.path("severity").asText());
            entity.setFilePath(finding.path("filePath").asText(null));
            entity.setPusherName(pusherName);
            if (finding.path("startLine").isNumber()) {
                entity.setStartLine(finding.path("startLine").asInt());
            }
            if (finding.path("endLine").isNumber()) {
                entity.setEndLine(finding.path("endLine").asInt());
            }
            entity.setTitle(trimTitle(finding.path("title").asText()));
            entity.setDetail(finding.path("detail").asText(null));
            entity.setSuggestion(finding.path("suggestion").asText(null));
            entity.setExistingCode(blankToNull(finding.path("existingCode").asText(null)));
            entity.setSuggestionCode(blankToNull(finding.path("suggestionCode").asText(null)));
            if (finding.path("locationStatus").isNumber()) {
                entity.setLocationStatus((short) finding.path("locationStatus").asInt());
            }
            if (finding.path("evidenceStrength").isNumber()) {
                entity.setEvidenceStrength((short) finding.path("evidenceStrength").asInt());
            }
            com.fasterxml.jackson.databind.JsonNode refs = finding.deepCopy();
            entity.setRefsJson(refs.toString());
            findingRepository.save(entity);
        }
        // 只有仍在评审中的任务能标完成，避免超时失败后又被改回成功
        jdbcTemplate.update("""
                UPDATE review_job
                SET status = 'SUCCESS', error_message = NULL, finished_at = NOW()
                WHERE id = ? AND status = 'RUNNING'
                """, jobId);
    }

    @Transactional
    public void saveTokens(Long jobId, ReviewTokenMeter.Snapshot usage) {
        if (usage == null) {
            // 模型没回 usage，保留已有数字，不用 0 覆盖
            return;
        }
        // 重试再跑时累加，不丢掉上一次已经花掉的 token
        jdbcTemplate.update("""
                UPDATE review_job
                SET prompt_tokens = COALESCE(prompt_tokens, 0) + ?,
                    completion_tokens = COALESCE(completion_tokens, 0) + ?,
                    total_tokens = COALESCE(total_tokens, 0) + ?
                WHERE id = ?
                """, usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), jobId);
    }

    public void retry(Long id) {
        int updated = jdbcTemplate.update("""
                UPDATE review_job
                SET status = 'PENDING', error_message = NULL, started_at = NULL, finished_at = NULL
                WHERE id = ? AND status = 'FAILED'
                """, id);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "只有失败任务可以重试");
        }
    }

    @Transactional
    public void rereview(Long id) {
        ReviewJobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
        if (!"SUCCESS".equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "只有已完成的评审可以重跑");
        }
        if (job.getVoided() != null && job.getVoided() == 1) {
            throw new ApiException(HttpStatus.CONFLICT, "这次评审已作废");
        }
        int round = job.getRereviewRound() == null ? 0 : job.getRereviewRound();
        if (round >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, "已经重跑 3 次，不能再重跑");
        }
        jdbcTemplate.update("""
                UPDATE review_finding_annotation
                SET triggered_rereview = TRUE
                WHERE job_id = ?
                """, id);
        job.setStatus("PENDING");
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setRereviewRound(round + 1);
        jobRepository.save(job);
    }

    @Transactional
    public void voidReview(Long id) {
        ReviewJobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
        int round = job.getRereviewRound() == null ? 0 : job.getRereviewRound();
        if (round < 3) {
            throw new ApiException(HttpStatus.CONFLICT, "重跑未满 3 次，不能作废");
        }
        job.setVoided((short) 1);
        jobRepository.save(job);
    }

    @Transactional
    public void bumpRound(Long id) {
        ReviewJobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
        int round = job.getRereviewRound() == null ? 0 : job.getRereviewRound();
        if (round >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, "已经重跑 3 次，不能再重跑");
        }
        job.setRereviewRound(round + 1);
        jobRepository.save(job);
    }

    public PageResult<ListItem> list(Long projectId, String branch, String status, String severity, String pusher,
                                     int page, int size) {
        // 未选项目时不允许只按分支筛
        requireProjectForBranch(projectId, branch);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<ReviewJobEntity> result = jobRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                // 页签选中的仓库，按项目编号精确匹配
                predicates.add(cb.equal(root.get("gitlabProjectId"), projectId));
            }
            if (has(branch)) {
                // 分支下拉是已有分支的原值，按全等匹配
                predicates.add(cb.equal(root.get("refBranch"), branch));
            }
            if (has(status)) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (has(pusher)) {
                // 提交人来自已有记录的去重结果，按全等匹配
                predicates.add(cb.equal(root.get("pusherName"), pusher));
            }
            if (predicates.isEmpty() && !has(severity)) {
                return cb.conjunction();
            }
            if (has(severity)) {
                // 只按该任务最新一份报告的总体级别筛选
                Subquery<Long> latest = query.subquery(Long.class);
                Root<ReviewReportEntity> latestRoot = latest.from(ReviewReportEntity.class);
                latest.select(cb.max(latestRoot.get("id")));
                latest.where(cb.equal(latestRoot.get("jobId"), root.get("id")));
                Subquery<Long> matched = query.subquery(Long.class);
                Root<ReviewReportEntity> reportRoot = matched.from(ReviewReportEntity.class);
                matched.select(reportRoot.get("jobId"));
                matched.where(
                        cb.equal(reportRoot.get("jobId"), root.get("id")),
                        cb.equal(reportRoot.get("id"), latest),
                        cb.equal(reportRoot.get("overallSeverity"), severity)
                );
                predicates.add(cb.exists(matched));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        List<ListItem> records = result.getContent().stream().map(this::toListItem).toList();
        return new PageResult<>(result.getTotalElements(), safePage, safeSize, records);
    }

    public ReviewFacets facets(Long projectId, String branch) {
        // 分支只属于已选项目
        requireProjectForBranch(projectId, branch);
        // 每个仓库一条页签，数量是该仓库的全部评审
        List<ProjectFacet> projects = jdbcTemplate.query("""
                SELECT gitlab_project_id, max(project_path), count(*)
                FROM review_job
                GROUP BY gitlab_project_id
                ORDER BY max(project_path)
                """, (rs, rowNum) -> new ProjectFacet(rs.getLong(1), rs.getString(2), rs.getLong(3)));
        List<String> branches = List.of();
        if (projectId != null) {
            // 只列出这个项目已经产生过评审的分支
            branches = jdbcTemplate.query("""
                    SELECT ref_branch
                    FROM review_job
                    WHERE gitlab_project_id = ?
                    GROUP BY ref_branch
                    ORDER BY ref_branch
                    """, (rs, rowNum) -> rs.getString(1), projectId);
        }
        StringBuilder pusherSql = new StringBuilder("""
                SELECT pusher_name
                FROM review_job
                WHERE pusher_name IS NOT NULL AND btrim(pusher_name) <> ''
                """);
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            // 选了项目后，提交人只取该项目
            pusherSql.append(" AND gitlab_project_id = ?");
            args.add(projectId);
        }
        if (has(branch)) {
            // 选了分支后，提交人再收窄到该分支
            pusherSql.append(" AND ref_branch = ?");
            args.add(branch);
        }
        // 已有提交人去重后作为下拉
        pusherSql.append(" GROUP BY pusher_name ORDER BY pusher_name");
        List<String> pushers = jdbcTemplate.query(pusherSql.toString(), (rs, rowNum) -> rs.getString(1), args.toArray());
        return new ReviewFacets(projects, branches, pushers);
    }

    private void requireProjectForBranch(Long projectId, String branch) {
        if (has(branch) && projectId == null) {
            // 分支筛选必须挂在某个项目下
            throw new ApiException(HttpStatus.BAD_REQUEST, "请先选择项目再按分支筛选");
        }
    }

    public Detail detail(Long id) {
        ReviewJobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
        ReviewReportEntity report = reportRepository.findFirstByJobIdOrderByIdDesc(id).orElse(null);
        List<ReviewFindingEntity> findings = report == null ? List.of() : findingRepository.findByReportIdOrderByIdAsc(report.getId());
        return new Detail(job, report, findings, annotationService.latest(id));
    }

    public Stats stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT status, count(*) FROM review_job GROUP BY status", rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT r.overall_severity, count(*)
                FROM review_report r
                JOIN (SELECT MAX(id) AS id FROM review_report GROUP BY job_id) latest ON latest.id = r.id
                GROUP BY r.overall_severity
                """, rs -> {
            bySeverity.put(rs.getString(1), rs.getLong(2));
        });
        List<TrendPoint> trend = jdbcTemplate.query("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day, count(*)
                FROM review_job
                WHERE created_at >= NOW() - INTERVAL '14 days'
                GROUP BY day
                ORDER BY day
                """, (rs, rowNum) -> new TrendPoint(rs.getString(1), rs.getLong(2)));
        // 只加总已经记下来的 token，NULL 表示当时没返回用量
        TokenSummary tokens = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(prompt_tokens), 0),
                       COALESCE(SUM(completion_tokens), 0),
                       COALESCE(SUM(total_tokens), 0),
                       COUNT(total_tokens)
                FROM review_job
                """, (rs, rowNum) -> new TokenSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        List<TokenTrendPoint> tokenTrend = jdbcTemplate.query("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                       COALESCE(SUM(total_tokens), 0)
                FROM review_job
                WHERE created_at >= NOW() - INTERVAL '14 days'
                GROUP BY day
                ORDER BY day
                """, (rs, rowNum) -> new TokenTrendPoint(rs.getString(1), rs.getLong(2)));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long failed = byStatus.getOrDefault("FAILED", 0L);
        double failureRate = total == 0 ? 0 : (double) failed / total;
        return new Stats(total, failed, failureRate, byStatus, bySeverity, trend, tokens, tokenTrend);
    }

    private ListItem toListItem(ReviewJobEntity job) {
        String severity = reportRepository.findFirstByJobIdOrderByIdDesc(job.getId())
                .map(ReviewReportEntity::getOverallSeverity)
                .orElse(null);
        return new ListItem(job.getId(), job.getProjectPath(), job.getRefBranch(), job.getEventType(), job.getStatus(),
                job.getTitle(), job.getPusherName(), job.getCreatedAt(), job.getFinishedAt(), severity,
                job.getMrIid(), job.getCommitSha(), job.getErrorMessage(),
                job.getPromptTokens(), job.getCompletionTokens(), job.getTotalTokens());
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String message) {
        if (message == null || message.isBlank()) {
            return "评审失败";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private String trimTitle(String title) {
        if (title.length() <= 512) {
            return title;
        }
        return title.substring(0, 512);
    }

    public record ListItem(Long id, String projectPath, String refBranch, String eventType, String status, String title,
                           String pusherName, java.time.OffsetDateTime createdAt, java.time.OffsetDateTime finishedAt,
                           String overallSeverity, Integer mrIid, String commitSha, String errorMessage,
                           Long promptTokens, Long completionTokens, Long totalTokens) {
    }

    public record Detail(ReviewJobEntity job, ReviewReportEntity report, List<ReviewFindingEntity> findings,
                         List<FindingAnnotationService.AnnotationView> annotations) {
    }

    public record TrendPoint(String date, long count) {
    }

    public record TokenSummary(long promptTokens, long completionTokens, long totalTokens, long countedReviews) {
    }

    public record TokenTrendPoint(String date, long totalTokens) {
    }

    public record Stats(long total, long failed, double failureRate, Map<String, Long> byStatus,
                        Map<String, Long> bySeverity, List<TrendPoint> trend,
                        TokenSummary tokens, List<TokenTrendPoint> tokenTrend) {
    }

    public record ProjectFacet(Long gitlabProjectId, String projectPath, long count) {
    }

    public record ReviewFacets(List<ProjectFacet> projects, List<String> branches, List<String> pushers) {
    }
}
