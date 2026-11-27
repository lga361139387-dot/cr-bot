package com.company.crbot;

import com.company.crbot.common.ApiException;
import com.company.crbot.config.AppProperties;
import com.company.crbot.settings.*;
import com.company.crbot.review.*;
import com.company.crbot.rag.EmbeddingClient;
import com.company.crbot.usage.TokenLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ModelConfigurationTest {
    @Test void independentEmbeddingEndpointDoesNotReceiveChatCredential() {
        var repo = mock(AppSettingRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        var props = new AppProperties();
        props.setLlmBaseUrl("https://chat.example/v1"); props.setLlmApiKey("private-chat-key");
        props.setEmbeddingBaseUrl("http://localhost:11434/v1");
        var settings = new SettingsService(repo, mock(PromptTemplateRepository.class), props);
        assertEquals("", settings.embeddingApiKey());
        props.setEmbeddingBaseUrl(props.getLlmBaseUrl());
        assertEquals("private-chat-key", settings.embeddingApiKey());
    }
    @Test void validatesBaseUrlAndNormalizesTrailingSlashes() {
        SettingsService.validateBaseUrl("http://localhost:11434/v1");
        assertThrows(ApiException.class, () -> SettingsService.validateBaseUrl("https://user:secret@example.com/v1"));
        assertThrows(ApiException.class, () -> SettingsService.validateBaseUrl("https://example.com/v1?key=secret"));
        assertThrows(ApiException.class, () -> SettingsService.validateBaseUrl("file:///tmp/socket"));
        assertEquals("https://example.com/v1/embeddings", EmbeddingClient.join("https://example.com/v1///", "/embeddings"));
    }
    @Test void localChatOmitsAuthTemperatureAndDisabledTools() {
        var settings = mock(SettingsService.class);
        when(settings.llmBaseUrl()).thenReturn("http://localhost:11434/v1");
        when(settings.llmApiKey()).thenReturn(""); when(settings.llmModel()).thenReturn("local-model");
        when(settings.llmTemperature()).thenReturn(""); when(settings.llmToolsEnabled()).thenReturn(false);
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(content().json("{\"model\":\"local-model\",\"messages\":[]}", true))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", MediaType.APPLICATION_JSON));
        var mapper = new ObjectMapper();
        var client = new LlmClient(builder, settings, mapper, mock(ReviewTokenMeter.class), mock(TokenLedger.class));
        assertEquals("OK", client.chat(mapper.createArrayNode(), mapper.createArrayNode()).path("content").asText());
        server.verify();
    }
}
