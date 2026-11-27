package com.company.crbot.subscription;

import java.util.regex.Pattern;

public final class BranchPatternMatcher {

    private BranchPatternMatcher() {
    }

    public static boolean matches(String pattern, String branch) {
        // 空模式或空分支一律不命中
        if (pattern == null || pattern.isBlank() || branch == null || branch.isBlank()) {
            return false;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            // ** 跨目录，* 只匹配单层
            if (current == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (current == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        regex.append("$");
        return branch.matches(regex.toString());
    }
}
