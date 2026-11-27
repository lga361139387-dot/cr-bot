package com.company.crbot.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
public class GitlabWebhookController {

    private final GitlabWebhookService webhookService;

    public GitlabWebhookController(GitlabWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/gitlab")
    public Map<String, Object> gitlab(@RequestHeader(value = "X-Gitlab-Token", required = false) String token,
                                      @RequestBody JsonNode body) {
        // 只做验签和入库，评审放到异步 Worker
        return webhookService.handle(token, body);
    }
}
