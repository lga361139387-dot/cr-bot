package com.company.crbot.knowledge;

import com.company.crbot.common.ApiException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 预审规则与原文约束。模型给出的疑点片段必须能在原文中逐字找到，不能改写后入库。
 */
public final class PrecheckTexts {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String SUPPLEMENT = "SUPPLEMENT";
    public static final String DISCARD = "DISCARD";

    private PrecheckTexts() {
    }

    public static boolean needsReview(boolean enabled, String docType, String extension,
                                      Set<String> skipExtensions, Set<String> forceDocTypes) {
        if (!enabled) {
            return false;
        }
        if (docType != null && forceDocTypes.contains(docType)) {
            return true;
        }
        String ext = normalizeExt(extension);
        // 白名单里的简单文本可以跳过；没有扩展名时仍走预审
        return ext.isBlank() || !skipExtensions.contains(ext);
    }

    public static List<Doubt> accepted(String content, List<Doubt> raw) {
        List<Doubt> kept = new ArrayList<>();
        String source = content == null ? "" : content;
        for (Doubt doubt : raw) {
            String excerpt = doubt.excerpt() == null ? "" : doubt.excerpt().trim();
            String question = doubt.question() == null ? "" : doubt.question().trim();
            if (excerpt.isBlank() || question.isBlank()) {
                continue;
            }
            // 片段必须是原文子串，模型改写的句子直接丢掉
            if (!source.contains(excerpt)) {
                continue;
            }
            kept.add(new Doubt(excerpt, question));
        }
        return kept;
    }

    public static void requireDecisions(Set<Long> doubtIds, List<Decision> decisions) {
        if (doubtIds.isEmpty()) {
            throw new ApiException("没有待确认的疑点");
        }
        Set<Long> seen = new HashSet<>();
        if (decisions != null) {
            for (Decision decision : decisions) {
                if (decision == null || decision.doubtId() == null || !doubtIds.contains(decision.doubtId())) {
                    throw new ApiException("疑点不存在");
                }
                if (!seen.add(decision.doubtId())) {
                    throw new ApiException("同一个疑点不能重复确认");
                }
                String action = decision.action() == null ? "" : decision.action().trim();
                if (!CONFIRMED.equals(action) && !SUPPLEMENT.equals(action) && !DISCARD.equals(action)) {
                    throw new ApiException("确认动作不正确");
                }
                if (SUPPLEMENT.equals(action) && (decision.note() == null || decision.note().isBlank())) {
                    throw new ApiException("补充说明不能为空");
                }
            }
        }
        if (!seen.equals(doubtIds)) {
            throw new ApiException("请先确认全部疑点");
        }
    }

    public static String normalizeExt(String extension) {
        if (extension == null) {
            return "";
        }
        String value = extension.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        return value;
    }

    public record Doubt(String excerpt, String question) {
    }

    public record Decision(Long doubtId, String action, String note) {
    }
}
