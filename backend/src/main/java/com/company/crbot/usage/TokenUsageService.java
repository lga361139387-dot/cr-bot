package com.company.crbot.usage;

import com.company.crbot.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TokenUsageService {

    private final JdbcTemplate jdbcTemplate;

    public TokenUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<UsageRow> list(Integer scene, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (scene != null) {
            where = " WHERE scene = ?";
            args.add(scene);
        }
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM token_usage" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<UsageRow> rows = jdbcTemplate.query("""
                SELECT id, scene, call_type, ref_id, title, model_name,
                       prompt_tokens, completion_tokens, total_tokens, error_message, created_at
                FROM token_usage
                """ + where + """
                 ORDER BY id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new UsageRow(rs.getLong(1), rs.getShort(2), rs.getShort(3),
                rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5), rs.getString(6),
                rs.getObject(7) == null ? null : rs.getLong(7),
                rs.getObject(8) == null ? null : rs.getLong(8),
                rs.getObject(9) == null ? null : rs.getLong(9),
                rs.getString(10), rs.getObject(11, java.time.OffsetDateTime.class)), pageArgs.toArray());
        return new PageResult<>(total == null ? 0 : total, safePage, safeSize, rows);
    }

    public Summary summary() {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(prompt_tokens), 0),
                       COALESCE(SUM(completion_tokens), 0),
                       COALESCE(SUM(total_tokens), 0),
                       COUNT(*)
                FROM token_usage
                """, (rs, rowNum) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    public record UsageRow(Long id, short scene, short callType, Long refId, String title, String modelName,
                           Long promptTokens, Long completionTokens, Long totalTokens, String errorMessage,
                           java.time.OffsetDateTime createdAt) {
    }

    public record Summary(long promptTokens, long completionTokens, long totalTokens, long calls) {
    }
}
