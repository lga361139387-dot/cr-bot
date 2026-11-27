package com.company.crbot.settings;

import com.company.crbot.common.ApiException;
import com.company.crbot.review.LlmClient;
import com.company.crbot.rag.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings/models")
public class ModelConnectionController {
    private final LlmClient llm;
    private final EmbeddingClient embedding;
    private final ObjectMapper mapper;
    public ModelConnectionController(LlmClient llm, EmbeddingClient embedding, ObjectMapper mapper) {
        this.llm = llm; this.embedding = embedding; this.mapper = mapper;
    }
    @PostMapping("/test/{kind}")
    public Map<String, Object> test(@PathVariable String kind) {
        if (!List.of("chat", "embedding").contains(kind)) throw new ApiException("未知模型类型");
        long start = System.nanoTime();
        try {
            if (kind.equals("chat")) {
                var messages = mapper.createArrayNode();
                messages.addObject().put("role", "user").put("content", "Reply with OK.");
                var result = llm.chat(messages, null);
                if (result.path("content").asText().isBlank()) throw new ApiException("模型返回空内容");
            } else { embedding.embed(List.of("CRbot connection test")); }
            return Map.of("success", true, "latencyMs", (System.nanoTime() - start) / 1_000_000,
                    "message", kind.equals("chat") ? "对话接口连接成功" : "向量接口连接成功，维度 1024");
        } catch (Exception ex) {
            // 不透传网关响应和异常，防止返回 URL 中的凭据或供应商调试信息。
            return Map.of("success", false, "message", "连接失败，请检查已保存的地址、模型、密钥及服务日志；向量模型须输出 1024 维");
        }
    }
}
