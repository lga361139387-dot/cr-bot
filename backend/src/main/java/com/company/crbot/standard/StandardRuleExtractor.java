package com.company.crbot.standard;

import java.util.ArrayList;
import java.util.List;

public final class StandardRuleExtractor {

    private StandardRuleExtractor() {
    }

    public record Rule(String code, String title, String summary, List<String> requirements, List<String> counterexamples) {
    }

    public static List<Rule> extract(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] lines = normalized.split("\n", -1);
        List<Rule> rules = new ArrayList<>();
        String title = "";
        StringBuilder summary = new StringBuilder();
        List<String> requirements = new ArrayList<>();
        List<String> counterexamples = new ArrayList<>();
        boolean inRule = false;
        boolean counterMode = false;
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        for (String line : lines) {
            // 围栏里的示例不拆成条款，整段挂到当前规则
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    String sample = code.toString().trim();
                    if (!sample.isBlank()) {
                        if (counterMode) {
                            counterexamples.add(sample);
                        } else {
                            requirements.add(sample);
                        }
                    }
                    code.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                if (code.length() > 0) {
                    code.append('\n');
                }
                code.append(line);
                continue;
            }
            // 二级标题才是一条规则，一级标题是文档名
            if (line.matches("^#{2,6}\\s+.*")) {
                if (inRule) {
                    rules.add(build(rules.size() + 1, title, summary, requirements, counterexamples));
                }
                title = line.replaceFirst("^#{2,6}\\s+", "").trim();
                summary.setLength(0);
                requirements = new ArrayList<>();
                counterexamples = new ArrayList<>();
                inRule = true;
                counterMode = false;
                continue;
            }
            if (!inRule || line.isBlank()) {
                continue;
            }
            // 「反例」之后的句子和列表都算反例，不再混进必须项
            if (line.matches("^反例[:：]?.*")) {
                counterMode = true;
                String rest = line.replaceFirst("^反例[:：]?\\s*", "").trim();
                if (!rest.isBlank()) {
                    counterexamples.add(rest);
                }
                continue;
            }
            String text = line.replaceFirst("^[-*]\\s+", "").trim();
            if (text.isBlank()) {
                continue;
            }
            if (counterMode) {
                counterexamples.add(text);
            } else if (line.matches("^[-*]\\s+.*") || !requirements.isEmpty()) {
                requirements.add(text);
            } else {
                if (summary.length() > 0) {
                    summary.append(' ');
                }
                summary.append(text);
            }
        }
        if (inRule) {
            rules.add(build(rules.size() + 1, title, summary, requirements, counterexamples));
        }
        return rules;
    }

    public static String render(String docTitle, List<Rule> rules) {
        StringBuilder builder = new StringBuilder();
        builder.append("规范: ").append(docTitle).append('\n');
        for (Rule rule : rules) {
            builder.append(rule.code()).append(' ').append(rule.title()).append('\n');
            if (!rule.summary().isBlank()) {
                builder.append(rule.summary()).append('\n');
            }
            for (String requirement : rule.requirements()) {
                builder.append("- ").append(requirement).append('\n');
            }
            for (String counterexample : rule.counterexamples()) {
                builder.append("反例: ").append(counterexample).append('\n');
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private static Rule build(int index, String title, StringBuilder summary, List<String> requirements, List<String> counterexamples) {
        return new Rule("R" + index, title, summary.toString().trim(), List.copyOf(requirements), List.copyOf(counterexamples));
    }
}
