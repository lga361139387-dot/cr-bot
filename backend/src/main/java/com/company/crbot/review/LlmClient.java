package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.settings.SettingsService;
import com.company.crbot.usage.TokenLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class LlmClient {

    private final RestClient.Builder restClientBuilder;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final ReviewTokenMeter tokenMeter;
    private final TokenLedger tokenLedger;

    public LlmClient(RestClient.Builder restClientBuilder, SettingsService settingsService, ObjectMapper objectMapper,
                     ReviewTokenMeter tokenMeter, TokenLedger tokenLedger) {
        this.restClientBuilder = restClientBuilder;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.tokenMeter = tokenMeter;
        this.tokenLedger = tokenLedger;
    }

    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        String baseUrl = settingsService.llmBaseUrl();
        String apiKey = settingsService.llmApiKey();
        String model = settingsService.llmModel();
        if (baseUrl.isBlank() || model.isBlank()) {
            throw new ApiException("未配置 LLM：需要接口地址和对话模型；云服务还需要 API Key");
        }
        try {
            return complete(baseUrl, apiKey, model, messages, settingsService.llmToolsEnabled() ? tools : null);
        } catch (RestClientResponseException ex) {
            String response = ex.getResponseBodyAsString();
            // 失败响应通常没有 usage，仍记一条调用，token 留空
            tokenLedger.record(TokenLedger.CHAT, model, null, trimError(response));
            // 网关不支持 tools 时，规格允许改为单次生成；上下文已经预先检索好
            if (tools != null && ex.getStatusCode().value() == 400 && response.toLowerCase().contains("tool")) {
                try {
                    return complete(baseUrl, apiKey, model, messages, null);
                } catch (RestClientResponseException again) {
                    throw new ApiException("LLM 调用失败: " + again.getStatusCode().value());
                }
            }
            throw new ApiException("LLM 调用失败: " + ex.getStatusCode().value());
        }
    }

    private JsonNode complete(String baseUrl, String apiKey, String model, ArrayNode messages, ArrayNode tools) {
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        if (!settingsService.llmTemperature().isBlank()) body.put("temperature", Double.parseDouble(settingsService.llmTemperature()));
        body.set("messages", messages);
        if (tools != null) {
            body.set("tools", tools);
        }
        String response = restClientBuilder.build()
                .post()
                .uri(com.company.crbot.rag.EmbeddingClient.join(baseUrl, "/chat/completions"))
                .headers(headers -> { if (!apiKey.isBlank()) headers.setBearerAuth(apiKey); })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            // 对话用量记到当前评审，响应没有 usage 时由计量器跳过
            tokenMeter.record(root.path("usage"));
            tokenLedger.record(TokenLedger.CHAT, model, root.path("usage"), null);
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode() || message.isNull()) {
                throw new ApiException("LLM 响应缺少 message");
            }
            return message;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("解析 LLM 响应失败");
        }
    }

    private String trimError(String response) {
        if (response == null || response.isBlank()) {
            return "LLM 调用失败";
        }
        return response.length() > 500 ? response.substring(0, 500) : response;
    }
}
