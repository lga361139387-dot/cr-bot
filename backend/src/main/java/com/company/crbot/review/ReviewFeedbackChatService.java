package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import com.company.crbot.usage.TokenLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReviewFeedbackChatService {

    private static final short USER = 1;
    private static final short ASSISTANT = 2;
    private static final short MODE_FULL = 1;
    private static final short MODE_ADJUST = 2;

    private final JdbcTemplate jdbcTemplate;
    private final ReviewJobRepository jobRepository;
    private final ReviewReportRepository reportRepository;
    private final ReviewFindingRepository findingRepository;
    private final ReviewJobService jobService;
    private final LlmClient llmClient;
    private final ReviewTokenMeter tokenMeter;
    private final TokenLedger tokenLedger;
    private final ObjectMapper objectMapper;

    public ReviewFeedbackChatService(JdbcTemplate jdbcTemplate, ReviewJobRepository jobRepository,
                                     ReviewReportRepository reportRepository, ReviewFindingRepository findingRepository,
                                     ReviewJobService jobService, LlmClient llmClient, ReviewTokenMeter tokenMeter,
                                     TokenLedger tokenLedger, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.findingRepository = findingRepository;
        this.jobService = jobService;
        this.llmClient = llmClient;
        this.tokenMeter = tokenMeter;
        this.tokenLedger = tokenLedger;
        this.objectMapper = objectMapper;
    }

    public List<Message> list(Long jobId) {
        requireJob(jobId);
        return jdbcTemplate.query("""
                SELECT id, role, speaker, content, need_change, mode, confirmed, created_at
                FROM review_feedback_chat WHERE job_id = ? ORDER BY id
                """, (rs, row) -> new Message(rs.getLong(1), rs.getShort(2), rs.getString(3), rs.getString(4), rs.getShort(5),
                rs.getObject(6) == null ? null : rs.getShort(6), rs.getShort(7), rs.getTimestamp(8).toInstant().toString()), jobId);
    }

    @Transactional
    public List<Message> talk(Long jobId, String content) {
        ReviewJobEntity job = requireOpen(jobId);
        if (content == null || content.isBlank()) {
            throw new ApiException("请填写要反馈的内容");
        }
        // 发言人只取当前登录账号，不采用页面传入的姓名
        String speaker = CurrentUser.username();
        insert(jobId, USER, speaker, content.trim(), (short) 0, null, (short) 0);
        String reply = ask(job);
        Decision decision = readDecision(reply);
        insert(jobId, ASSISTANT, "CRBOT", decision.reply(), decision.needChange(), decision.mode(), (short) 0);
        return list(jobId);
    }

    @Transactional
    public List<Message> confirm(Long jobId) {
        ReviewJobEntity job = requireOpen(jobId);
        Message pending = latestOffer(jobId);
        if (pending == null) {
            throw new ApiException("当前没有待确认的变更");
        }
        jdbcTemplate.update("UPDATE review_feedback_chat SET confirmed = 1 WHERE id = ?", pending.id());
        if (pending.mode() != null && pending.mode() == MODE_ADJUST) {
            applyAdjust(job, pending.content());
            jobService.bumpRound(jobId);
        } else {
            jobService.rereview(jobId);
        }
        return list(jobId);
    }

    private void applyAdjust(ReviewJobEntity job, String assistantText) {
        ReviewReportEntity report = reportRepository.findFirstByJobIdOrderByIdDesc(job.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审报告不存在"));
        String note = "人工反馈后的调整：" + assistantText;
        String summary = report.getSummary() == null ? note : report.getSummary() + "\n" + note;
        report.setSummary(summary);
        try {
            ObjectNode raw = (ObjectNode) objectMapper.readTree(report.getRawJson());
            raw.put("summary", summary);
            report.setRawJson(raw.toString());
        } catch (Exception ex) {
            throw new ApiException("报告内容无法更新");
        }
        reportRepository.save(report);
    }

    private String ask(ReviewJobEntity job) {
        tokenMeter.start();
        tokenLedger.open(TokenLedger.REVIEW, job.getId(), "标注对话");
        try {
            ArrayNode messages = objectMapper.createArrayNode();
            messages.addObject().put("role", "system").put("content",
                    "你在和评审人讨论一份已经完成的代码评审。只输出 JSON："
                            + "{\"reply\":\"给用户的话\",\"needChange\":false,\"mode\":1}。"
                            + "needChange 为 true 才表示结论或缺陷需要改。mode 1 是完整重跑评审，mode 2 是只改综述和备注、不重跑。"
                            + "用户只是在确认或补充、现有结论仍然成立时，needChange 必须是 false。");
            StringBuilder history = new StringBuilder();
            for (Message message : list(job.getId())) {
                history.append(message.role() == USER ? message.speaker() + "：" : "CRBOT：").append(message.content()).append('\n');
            }
            messages.addObject().put("role", "user").put("content", history.toString());
            return llmClient.chat(messages, null).path("content").asText("");
        } finally {
            tokenLedger.close();
            jobService.saveTokens(job.getId(), tokenMeter.finish());
        }
    }

    private Decision readDecision(String raw) {
        try {
            JsonNode node = objectMapper.readTree(stripFence(raw));
            boolean need = node.path("needChange").asBoolean(false);
            short mode = node.path("mode").asInt(1) == 2 ? MODE_ADJUST : MODE_FULL;
            String reply = node.path("reply").asText("").trim();
            if (reply.isBlank()) {
                reply = need ? "建议按你的反馈修改这次评审。" : "现有结论可以保持。";
            }
            return new Decision(reply, need ? (short) 1 : (short) 0, need ? mode : null);
        } catch (Exception ex) {
            return new Decision(raw == null || raw.isBlank() ? "没有形成可执行的修改意见。" : raw.trim(), (short) 0, null);
        }
    }

    private Message latestOffer(Long jobId) {
        List<Message> messages = list(jobId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == ASSISTANT && message.needChange() == 1 && message.confirmed() == 0) {
                return message;
            }
        }
        return null;
    }

    private void insert(Long jobId, short role, String speaker, String content, short needChange, Short mode, short confirmed) {
        jdbcTemplate.update("""
                INSERT INTO review_feedback_chat (job_id, role, speaker, content, need_change, mode, confirmed)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, jobId, role, speaker, content, needChange, mode, confirmed);
    }

    private ReviewJobEntity requireOpen(Long jobId) {
        ReviewJobEntity job = requireJob(jobId);
        if (!"SUCCESS".equals(job.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "只有已完成的评审可以反馈");
        }
        if (job.getVoided() != null && job.getVoided() == 1) {
            throw new ApiException(HttpStatus.CONFLICT, "这次评审已作废");
        }
        int round = job.getRereviewRound() == null ? 0 : job.getRereviewRound();
        if (round >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, "已经重跑 3 次，不能再通过对话发起变更");
        }
        return job;
    }

    private ReviewJobEntity requireJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评审任务不存在"));
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                return text.substring(start + 1, end).trim();
            }
        }
        return text;
    }

    public record Message(Long id, short role, String speaker, String content, short needChange, Short mode, short confirmed, String createdAt) {
    }

    private record Decision(String reply, short needChange, Short mode) {
    }

    public record TalkRequest(String content, String speaker) {
    }
}
