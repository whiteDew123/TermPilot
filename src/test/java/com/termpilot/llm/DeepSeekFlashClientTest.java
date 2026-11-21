package com.termpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.termpilot.config.TermPilotConfig;
import com.termpilot.prompt.PromptAssembler;
import com.termpilot.prompt.PromptContext;
import com.termpilot.prompt.PromptMode;
import com.termpilot.prompt.TurnReminder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekFlashClientTest {
    @Test
    void factoryDefaultsToFlashWithoutOverridingExplicitModels() {
        TermPilotConfig config = mock(TermPilotConfig.class);
        when(config.getApiKey("deepseek")).thenReturn("test-key");
        LlmClient client = LlmClientFactory.create("deepseek", config);
        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek-flash", client.getModelName());
        assertTrue(client.supportsImageInput());
        assertEquals(1_000_000, client.maxContextWindow());

        when(config.getModel("deepseek")).thenReturn("deepseek-v4-pro");
        LlmClient pro = LlmClientFactory.create("deepseek", config);
        assertEquals("deepseek-v4-pro", pro.getModelName());
        assertFalse(pro.supportsImageInput());
    }

    @Test
    void changingTurnContextOnlyChangesTheNewUserMessage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            var client = new DeepSeekClient("test-key", "deepseek-flash", server.url("/chat/completions").toString());
            var assembler = PromptAssembler.createDefault();
            var project = PromptContext.builder().projectMemoryContext("团队使用 Java 17")
                    .skillIndex("代码审查").externalContext("## MCP Resources\n- demo://docs").build();
            var history = new ArrayList<LlmClient.Message>();
            history.add(LlmClient.Message.system(assembler.assemble(PromptMode.AGENT, project)));
            history.add(new LlmClient.Message("user", "昨天改了什么？", null, null, null, null,
                    new TurnReminder("2026-11-09", "Asia/Taipei", "使用 Maven")));
            JsonNode before = send(server, client, history);
            history.add(LlmClient.Message.assistant("昨天改了配置文件。"));
            history.set(0, LlmClient.Message.system(assembler.assemble(PromptMode.AGENT, project)));
            history.add(new LlmClient.Message("user", "今天呢？", null, null, null, null,
                    new TurnReminder("2026-11-10", "Asia/Taipei", "运行离线测试")));
            JsonNode after = send(server, client, history);
            assertEquals(before.get(0), after.get(0));
            assertEquals(before.get(1), after.get(1));
            assertTrue(after.get(0).get("content").asText().contains("团队使用 Java 17"));
            assertFalse(after.get(0).get("content").asText().contains("2026-11-"));
            assertTrue(before.get(1).get("content").asText().contains("2026-11-09"));
            assertTrue(before.get(1).get("content").asText().contains("使用 Maven"));
            assertTrue(after.get(3).get("content").asText().contains("2026-11-10"));
            assertTrue(after.get(3).get("content").asText().contains("运行离线测试"));
            assertTrue(after.get(3).get("content").asText().endsWith("今天呢？"));
        }
    }

    private JsonNode send(MockWebServer server, DeepSeekClient client,
                          List<LlmClient.Message> history) throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"));
        client.chat(history, null);
        return new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8()).get("messages");
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"})
    void flashSendsImagesWithThinkingSettings(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"model":"deepseek-flash","choices":[{"delta":{"content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient("test-key", model,
                    server.url("/chat/completions").toString());
            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("看图"),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
            )).withTurnReminder("")), null);

            JsonNode request = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertEquals(model, request.path("model").asText());
            assertEquals("enabled", request.path("thinking").path("type").asText());
            assertEquals("max", request.path("reasoning_effort").asText());
            JsonNode content = request.path("messages").get(0).path("content");
            assertTrue(content.get(0).path("text").asText().startsWith("<termpilot-turn-context>"));
            assertEquals("看图", content.get(1).path("text").asText());
            assertEquals("image_url", content.get(2).path("type").asText());
            assertEquals("data:image/png;base64,aGVsbG8=", content.get(2).path("image_url").path("url").asText());
            assertEquals("deepseek-flash", response.resolvedModel());
            assertTrue(response.usagePresent());
            assertEquals("ok", response.content());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner", "deepseek-custom"})
    void otherModelsDoNotClaimVisionSupport(String model) {
        assertFalse(new DeepSeekClient("test-key", model).supportsImageInput());
    }
}
