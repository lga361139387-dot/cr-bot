package com.company.crbot.auth;

import com.company.crbot.settings.AppSettingEntity;
import com.company.crbot.settings.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 给 Cursor 等外部工具签发可吊销的 IDE MCP 令牌。
 * 明文只在签发时返回一次，库里只留 SHA-256。
 */
@Service
public class McpAccessService {

    static final String SETTING_KEY = "ide_mcp_token_sha256";
    public static final String ENDPOINT = "/api/v1/ide-mcp";

    private final AppSettingRepository settingRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public McpAccessService(AppSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    /**
     * purpose：轮换 IDE MCP 令牌。
     * business-logic：新令牌写入后旧令牌立即失效，避免多把长期令牌并存。
     * params：无。
     * return：明文令牌和接入路径；明文不会再次查询出来。
     * exception：摘要算法不可用时抛出 IllegalStateException。
     */
    @Transactional
    public IssuedToken issue() {
        byte[] raw = new byte[32];
        // 32 字节随机数，避免可猜测的短口令
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AppSettingEntity entity = settingRepository.findById(SETTING_KEY).orElseGet(AppSettingEntity::new);
        entity.setKey(SETTING_KEY);
        // 只存摘要，数据库泄露也不能直接拿去调 MCP
        entity.setValue(sha256Hex(token));
        entity.setUpdatedAt(OffsetDateTime.now());
        settingRepository.save(entity);
        return new IssuedToken(token, ENDPOINT);
    }

    /**
     * purpose：判断 Bearer 是否为当前有效的 IDE MCP 令牌。
     * business-logic：未签发过令牌时一律拒绝；比较摘要而不是明文。
     * params：token 为 Authorization 里去掉 Bearer 前缀后的值。
     * return：匹配当前摘要时为 true。
     * exception：不抛业务异常，无效输入返回 false。
     */
    public boolean matches(String token) {
        // 空令牌直接拒绝，避免对空串做摘要比较
        if (token == null || token.isBlank()) {
            return false;
        }
        return settingRepository.findById(SETTING_KEY)
                .map(entity -> sameHash(entity.getValue(), sha256Hex(token)))
                .orElse(false);
    }

    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    static boolean sameHash(String expectedHex, String actualHex) {
        // 长度不一致就不是同一把令牌
        if (expectedHex == null || actualHex == null || expectedHex.length() != actualHex.length()) {
            return false;
        }
        // 按字节比较，避免明文提前返回造成的时间差
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), actualHex.getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedToken(String token, String endpoint) {
    }
}
