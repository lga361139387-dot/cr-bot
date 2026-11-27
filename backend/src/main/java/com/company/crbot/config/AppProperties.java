package com.company.crbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crbot")
public class AppProperties {

    private String jwtSecret;
    private String adminUsername;
    private String adminPassword;
    private String gitlabBaseUrl;
    private String gitlabToken;
    private String gitlabWebhookSecret;
    private String llmBaseUrl;
    private String llmApiKey;
    private String llmModel;
    private String embeddingBaseUrl;
    private String embeddingModel;
    private String embeddingApiKey;
    private String encKey;
    private String corsOrigins;
    private String repoDir;
}
