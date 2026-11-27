package com.company.crbot.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReviewFeedbackService {

    private static final int PROMPT_LIMIT = 1800;

    private final ReviewFindingAnnotationRepository annotationRepository;
    private final ReviewFeedbackSampleRepository sampleRepository;
    private final ReviewFindingRepository findingRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReviewFeedbackService(ReviewFindingAnnotationRepository annotationRepository,
                                 ReviewFeedbackSampleRepository sampleRepository,
                                 ReviewFindingRepository findingRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.annotationRepository = annotationRepository;
        this.sampleRepository = sampleRepository;
        this.findingRepository = findingRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public String promptBlock(ReviewJobEntity job) {
        List<String> lines = new ArrayList<>();
        if (job.getRereviewRound() != null && job.getRereviewRound() > 0) {
            lines.add("这是第 " + job.getRereviewRound() + " 次人工触发的重评审。下面是这次评审的全部便利贴和备注，请和人工对话一起吸收，不要把标成「错误」的缺陷再原样写出来。");
            List<ReviewFindingAnnotationEntity> notes = annotationRepository.findByJobIdOrderByIdDesc(job.getId());
            if (notes.isEmpty()) {
                lines.add("本次还没有便利贴。");
            }
            List<ReviewFindingAnnotationEntity> ordered = new ArrayList<>(notes);
            java.util.Collections.reverse(ordered);
            for (ReviewFindingAnnotationEntity annotation : ordered) {
                String remark = annotation.getRemark() == null ? "" : "。备注：" + clip(annotation.getRemark(), 160);
                // 没有缺陷编号的是对整次评审的标注
                if (annotation.getFindingId() == null) {
                    lines.add("- " + annotation.getOperator() + " 认为本次评审" + AnnotationLabels.text(annotation.getLabel()) + remark);
                    continue;
                }
                ReviewFindingEntity finding = findingRepository.findById(annotation.getFindingId()).orElse(null);
                String title = finding == null ? "" : finding.getTitle();
                lines.add("- " + annotation.getOperator() + " 认为「" + title + "」" + AnnotationLabels.text(annotation.getLabel()) + remark);
            }
        }
        int others = 0;
        List<String> samples = new ArrayList<>();
        for (ReviewFeedbackSampleEntity sample : sampleRepository.findTop12ByOrderByIdDesc()) {
            if (job.getId() != null && job.getId().equals(sample.getJobId())) {
                continue;
            }
            samples.add("- 样本 " + clip(sample.getTitle(), 40) + " / " + AnnotationLabels.text(sample.getLabel())
                    + (sample.getRemark() == null ? "" : "：" + clip(sample.getRemark(), 80)));
            others++;
            if (others >= 6) {
                break;
            }
        }
        if (!samples.isEmpty()) {
            lines.add("历史人工标注仅供参考，不能替代本次 diff。");
            lines.addAll(samples);
        }
        if (lines.isEmpty()) {
            return "";
        }
        return clip(String.join("\n", lines), PROMPT_LIMIT);
    }

    public String chatBlock(Long jobId) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT role, speaker, content FROM review_feedback_chat WHERE job_id = ? ORDER BY id
                """, jobId)) {
            boolean bot = Short.valueOf(row.get("role").toString()) == 2;
            String who = bot ? "CRBOT" : (row.get("speaker") == null ? "有人" : String.valueOf(row.get("speaker")));
            lines.add(who + "：" + clip(String.valueOf(row.get("content")), 200));
        }
        if (lines.isEmpty()) {
            return "";
        }
        return clip(String.join("\n", lines), PROMPT_LIMIT);
    }

    static Map<Long, ReviewFindingAnnotationEntity> latest(List<ReviewFindingAnnotationEntity> rows) {
        Map<Long, ReviewFindingAnnotationEntity> latest = new LinkedHashMap<>();
        for (ReviewFindingAnnotationEntity row : rows) {
            latest.putIfAbsent(row.getFindingId(), row);
        }
        return latest;
    }

    private static String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
