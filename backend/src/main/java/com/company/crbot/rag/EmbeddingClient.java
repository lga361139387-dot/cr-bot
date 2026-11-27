package com.company.crbot.rag;

import com.company.crbot.common.ApiException;
import com.company.crbot.review.ReviewTokenMeter;
import com.company.crbot.usage.TokenLedger;
import com.company.crbot.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Component
public class EmbeddingClient {

    public static final int DIMENSION = 1024;

    private final RestClient.Builder restClientBuilder;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final ReviewTokenMeter tokenMeter;
    private final TokenLedger tokenLedger;

    public EmbeddingClient(RestClient.Builder restClientBuilder, SettingsService settingsService, ObjectMapper objectMapper,
                           ReviewTokenMeter tokenMeter, TokenLedger tokenLedger) {
        this.restClientBuilder = restClientBuilder;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.tokenMeter = tokenMeter;
        this.tokenLedger = tokenLedger;
    }

    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        String baseUrl = settingsService.embeddingBaseUrl();
        String apiKey = settingsService.embeddingApiKey();
        String model = settingsService.embeddingModel();
        // 缺少地址、密钥或模型时直接失败，不改用关键词检索冒充向量
        if (baseUrl.isBlank() || model.isBlank()) {
            throw new ApiException("未配置 Embedding：需要向量接口地址和 EMBEDDING_MODEL；云服务还需要 API Key");
        }
        List<float[]> vectors = new ArrayList<>();
        int batchSize = 16;
        for (int offset = 0; offset < inputs.size(); offset += batchSize) {
            List<String> batch = inputs.subList(offset, Math.min(inputs.size(), offset + batchSize));
            vectors.addAll(embedBatch(baseUrl, apiKey, model, batch));
        }
        return vectors;
    }

    private List<float[]> embedBatch(String baseUrl, String apiKey, String model, List<String> batch) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String text : batch) {
            input.add(text);
        }
        String response;
        try {
            response = restClientBuilder.build()
                    .post()
                    .uri(join(baseUrl, "/embeddings"))
                    .headers(headers -> { if (!apiKey.isBlank()) headers.setBearerAuth(apiKey); })
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String error = ex.getResponseBodyAsString();
            tokenLedger.record(TokenLedger.EMBEDDING, model, null, error == null || error.length() <= 500 ? error : error.substring(0, 500));
            throw ex;
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            // 评审合计走计量器；流水账无论是否在评审里都记
            tokenMeter.record(root.path("usage"));
            tokenLedger.record(TokenLedger.EMBEDDING, model, root.path("usage"), null);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw new ApiException("Embedding 响应条数与输入不一致");
            }
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.size() != DIMENSION) {
                    throw new ApiException("Embedding 维度不是 " + DIMENSION + "，请更换模型或在网关侧指定 dimensions");
                }
                float[] vector = new float[DIMENSION];
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("解析 Embedding 响应失败");
        }
    }

    public static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    public static String join(String baseUrl, String path) {
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        return trimmed + path;
    }
}
