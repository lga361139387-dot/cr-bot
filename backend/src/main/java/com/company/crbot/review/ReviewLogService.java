package com.company.crbot.review;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReviewLogService {

    public static final short AGENT = 1;
    public static final short FILE_READ = 2;
    public static final short SEARCH = 3;
    public static final short FILTERED = 4;

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public ReviewLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(Long jobId) {
        jdbcTemplate.update("DELETE FROM review_log WHERE job_id = ?", jobId);
    }

    public void add(Long jobId, short kind, String title, String detail) {
        add(jobId, kind, title, detail, 0);
    }

    public void add(Long jobId, short kind, String title, String detail, int round) {
        if (jobId == null) {
            return;
        }
        Object lock = locks.computeIfAbsent(jobId, key -> new Object());
        synchronized (lock) {
            Integer seq = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM review_log WHERE job_id = ?", Integer.class, jobId);
            jdbcTemplate.update("""
                    INSERT INTO review_log (job_id, seq, kind, title, detail, round)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, jobId, seq, kind, trim(title, 255), trim(detail, 8000), round);
        }
    }

    public List<Row> list(Long jobId) {
        return jdbcTemplate.query("""
                SELECT id, seq, kind, title, detail, created_at, round
                FROM review_log
                WHERE job_id = ?
                ORDER BY round, seq
                """, (rs, row) -> new Row(rs.getLong(1), rs.getInt(2), rs.getShort(3), rs.getString(4),
                present(rs.getShort(3), rs.getString(4), rs.getString(5)), rs.getTimestamp(6).toInstant().toString(),
                rs.getInt(7)), jobId);
    }

    static String present(short kind, String title, String detail) {
        String text = detail == null ? "" : detail.trim();
        if (kind == AGENT) {
            String first = text.split("\\R", 2)[0];
            if (first.startsWith("未调用工具") || first.startsWith("调用 ") || first.startsWith("模型")) {
                return first.startsWith("调用 ") ? "模型" + first : first;
            }
            if ("get_diff_file".equals(title)) {
                return "读取了本次变更的 diff";
            }
            if (text.startsWith("已收下") || text.startsWith("已结束") || text.startsWith("没有")) {
                return text.split("\\R", 2)[0];
            }
            return "完成了这一步";
        }
        if (kind == FILE_READ) {
            String first = text.split("\\R", 2)[0];
            if (first.startsWith("读取") || first.startsWith("作为") || first.startsWith("模型")) {
                return first;
            }
            return "读取了源码";
        }
        if (kind == SEARCH) {
            return text.startsWith("在仓库") || text.startsWith("按文件名") ? text.split("\\R", 2)[0] : "在仓库中检索";
        }
        return text.split("\\R", 2)[0];
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    public record Row(Long id, int seq, short kind, String title, String detail, String createdAt, int round) {
    }
}
