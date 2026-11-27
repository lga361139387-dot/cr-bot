package com.company.crbot.reserved;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后续迭代的入口。本次只声明接口，不统计、不阻断合并。
 */
@RestController
@RequestMapping("/api/v1/reserved")
public class ReservedCapabilityController {

    @GetMapping("/quality-board")
    public Map<String, Object> qualityBoard() {
        return Map.of(
                "enabled", false,
                "roles", List.of("DEV", "LEAD", "DBA", "PM"),
                "message", "个人和团队代码质量看板尚未开放"
        );
    }

    @GetMapping("/iterations/{id}/delivery")
    public Map<String, Object> delivery(@PathVariable Long id) {
        return Map.of(
                "enabled", false,
                "iterationId", id,
                "message", "迭代变更汇总尚未开放"
        );
    }

    @PostMapping("/gates/merge")
    public Map<String, Object> mergeGate() {
        return Map.of(
                "enabled", false,
                "blocked", false,
                "message", "CI 门禁尚未启用，不会阻断合并"
        );
    }
}
