package com.company.crbot.review;

import com.company.crbot.common.ApiException;

import java.util.Set;

public final class AnnotationLabels {

    public static final String ACCURATE = "ACCURATE";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";
    public static final String WRONG = "WRONG";
    public static final String CORRECT = "CORRECT";
    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String PARTIAL = "PARTIAL";
    public static final String NEED_CONTEXT = "NEED_CONTEXT";

    private static final Set<String> ALL = Set.of(ACCURATE, MEDIUM, LOW, WRONG, CORRECT, FALSE_POSITIVE, PARTIAL, NEED_CONTEXT);

    private AnnotationLabels() {
    }

    public static String require(String label) {
        if (label == null || !ALL.contains(label)) {
            throw new ApiException("标注类型不正确");
        }
        return label;
    }

    public static String text(String label) {
        return switch (label) {
            case ACCURATE, CORRECT -> "准确";
            case MEDIUM, PARTIAL -> "适中";
            case LOW, NEED_CONTEXT -> "偏低";
            case WRONG, FALSE_POSITIVE -> "错误";
            default -> label;
        };
    }
}
