package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class FindingAnnotationService {

    private final ReviewJobRepository jobRepository;
    private final ReviewReportRepository reportRepository;
    private final ReviewFindingRepository findingRepository;
    private final ReviewFindingAnnotationRepository annotationRepository;
    private final ReviewFeedbackSampleRepository sampleRepository;

    public FindingAnnotationService(ReviewJobRepository jobRepository, ReviewReportRepository reportRepository,
                                    ReviewFindingRepository findingRepository,
                                    ReviewFindingAnnotationRepository annotationRepository,
                                    ReviewFeedbackSampleRepository sampleRepository) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.findingRepository = findingRepository;
        this.annotationRepository = annotationRepository;
        this.sampleRepository = sampleRepository;
    }

    public List<AnnotationView> history(Long jobId) {
        requireJob(jobId);
        List<AnnotationView> views = new ArrayList<>();
        // 一次评审的便利贴按时间排在同一个入口下
        for (ReviewFindingAnnotationEntity row : annotationRepository.findByJobIdOrderByIdAsc(jobId)) {
            views.add(toView(row));
        }
        return views;
    }

    public List<AnnotationView> history(Long jobId, Long findingId) {
        requireFinding(jobId, findingId);
        List<AnnotationView> views = new ArrayList<>();
        for (ReviewFindingAnnotationEntity row : annotationRepository.findByJobIdAndFindingIdOrderByIdAsc(jobId, findingId)) {
            views.add(toView(row));
        }
        return views;
    }

    public List<AnnotationView> latest(Long jobId) {
        List<AnnotationView> views = new ArrayList<>();
        for (ReviewFindingAnnotationEntity row : ReviewFeedbackService.latest(annotationRepository.findByJobIdOrderByIdDesc(jobId)).values()) {
            views.add(toView(row));
        }
        return views;
    }

    /**
     * 只保存标注和样本，不把评审任务放回队列。
     */
    @Transactional
    public AnnotationView save(Long jobId, String label, String remark) {
        ReviewJobEntity job = requireJob(jobId);
        ReviewReportEntity report = reportRepository.findFirstByJobIdOrderByIdDesc(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审报告不存在"));
        ReviewFindingAnnotationEntity row = new ReviewFindingAnnotationEntity();
        row.setJobId(jobId);
        // 标注整次评审，不挂到某个维度或某条缺陷
        row.setFindingId(null);
        row.setIterationId(job.getIterationId());
        row.setPusherName(job.getPusherName());
        row.setLabel(AnnotationLabels.require(label));
        row.setRemark(blankToNull(remark));
        row.setTriggeredRereview(false);
        row.setOperator(CurrentUser.username());
        annotationRepository.save(row);
        ReviewFeedbackSampleEntity sample = new ReviewFeedbackSampleEntity();
        sample.setAnnotationId(row.getId());
        sample.setJobId(jobId);
        sample.setIterationId(job.getIterationId());
        sample.setPusherName(job.getPusherName());
        sample.setTitle(clip(report.getSummary() == null ? "本次评审" : report.getSummary(), 512));
        sample.setAiConclusion(clip(report.getSummary(), 2000));
        sample.setLabel(row.getLabel());
        sample.setRemark(row.getRemark());
        sample.setOperator(row.getOperator());
        sampleRepository.save(sample);
        return toView(row);
    }

    @Transactional
    public AnnotationView save(Long jobId, Long findingId, String label, String remark) {
        ReviewJobEntity job = requireJob(jobId);
        ReviewFindingEntity finding = requireFinding(jobId, findingId);
        ReviewFindingAnnotationEntity row = new ReviewFindingAnnotationEntity();
        row.setJobId(jobId);
        row.setFindingId(findingId);
        row.setIterationId(job.getIterationId());
        row.setPusherName(job.getPusherName());
        row.setLabel(AnnotationLabels.require(label));
        row.setRemark(blankToNull(remark));
        row.setTriggeredRereview(false);
        row.setOperator(CurrentUser.username());
        annotationRepository.save(row);
        ReviewFeedbackSampleEntity sample = new ReviewFeedbackSampleEntity();
        sample.setAnnotationId(row.getId());
        sample.setJobId(jobId);
        sample.setFindingId(findingId);
        sample.setIterationId(job.getIterationId());
        sample.setPusherName(job.getPusherName());
        sample.setFilePath(finding.getFilePath());
        sample.setStartLine(finding.getStartLine());
        sample.setCategory(finding.getCategory());
        sample.setSeverity(finding.getSeverity());
        sample.setTitle(clip(finding.getTitle(), 512));
        sample.setAiConclusion(clip(finding.getDetail(), 2000));
        sample.setLabel(row.getLabel());
        sample.setRemark(row.getRemark());
        sample.setOperator(row.getOperator());
        sampleRepository.save(sample);
        return toView(row);
    }

    private ReviewJobEntity requireJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
    }

    private ReviewFindingEntity requireFinding(Long jobId, Long findingId) {
        ReviewReportEntity report = reportRepository.findFirstByJobIdOrderByIdDesc(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审报告不存在"));
        ReviewFindingEntity finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "缺陷不存在"));
        if (!report.getId().equals(finding.getReportId())) {
            throw new ApiException("缺陷不属于当前报告");
        }
        return finding;
    }

    private AnnotationView toView(ReviewFindingAnnotationEntity row) {
        return new AnnotationView(row.getId(), row.getFindingId(), row.getLabel(), row.getRemark(),
                row.isTriggeredRereview(), row.getOperator(), row.getCreatedAt());
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record AnnotationView(Long id, Long findingId, String label, String remark, boolean triggeredRereview,
                                 String operator, java.time.OffsetDateTime createdAt) {
    }

    public record SaveRequest(String label, String remark) {
    }
}
