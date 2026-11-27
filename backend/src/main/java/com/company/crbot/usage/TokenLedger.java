package com.company.crbot.usage;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenLedger {

    public static final short REVIEW = 1;
    public static final short KB_IMPORT = 2;
    public static final short KB_REINDEX = 3;
    public static final short STANDARD_INDEX = 4;
    public static final short OTHER = 5;
    public static final short CHAT = 1;
    public static final short EMBEDDING = 2;

    private final JdbcTemplate jdbcTemplate;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    public TokenLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void open(short scene, Long refId, String title) {
        // 外层已经开了场景时不覆盖，避免导入里的重建被记成另一次重建
        if (current.get() != null) {
            return;
        }
        current.set(new Scope(scene, refId, trim(title, 255)));
    }

    public boolean openIfAbsent(short scene, Long refId, String title) {
        if (current.get() != null) {
            return false;
        }
        open(scene, refId, title);
        return true;
    }

    public void bind(Long refId) {
        Scope scope = current.get();
        if (scope != null && refId != null) {
            scope.refId = refId;
        }
    }

    public void setTitle(String title) {
        Scope scope = current.get();
        if (scope != null) {
            scope.title = trim(title, 255);
        }
    }

    public Object capture() {
        return current.get();
    }

    public void install(Object scope) {
        if (scope == null) {
            current.remove();
            return;
        }
        current.set((Scope) scope);
    }

    public void close() {
        current.remove();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(short callType, String model, JsonNode usage, String error) {
        Long prompt = null;
        Long completion = null;
        Long total = null;
        if (usage != null && usage.isObject() && (usage.has("prompt_tokens") || usage.has("completion_tokens") || usage.has("total_tokens"))) {
            prompt = usage.path("prompt_tokens").asLong(0);
            completion = usage.path("completion_tokens").asLong(0);
            total = usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asLong() : prompt + completion;
        }
        if (prompt == null && (error == null || error.isBlank())) {
            // 成功响应里没有 usage，不编造 token
            return;
        }
        Scope scope = current.get();
        short scene = scope == null ? OTHER : scope.scene;
        Long refId = scope == null ? null : scope.refId;
        String title = scope == null ? null : scope.title;
        jdbcTemplate.update("""
                INSERT INTO token_usage
                    (scene, call_type, ref_id, title, model_name, prompt_tokens, completion_tokens, total_tokens, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scene, callType, refId, title, trim(model, 128), prompt, completion, total, trim(error, 500));
    }

    private String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static final class Scope {
        private final short scene;
        private Long refId;
        private String title;

        private Scope(short scene, Long refId, String title) {
            this.scene = scene;
            this.refId = refId;
            this.title = title;
        }
    }
}
