package com.company.crbot.usage;

import com.company.crbot.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/token-usages")
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    public TokenUsageController(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    @GetMapping
    public PageResult<TokenUsageService.UsageRow> list(@RequestParam(required = false) Integer scene,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return tokenUsageService.list(scene, page, size);
    }

    @GetMapping("/summary")
    public TokenUsageService.Summary summary() {
        return tokenUsageService.summary();
    }
}
