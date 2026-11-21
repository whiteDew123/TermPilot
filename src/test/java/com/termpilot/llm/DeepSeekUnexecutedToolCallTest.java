package com.termpilot.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import com.termpilot.plan.Planner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekUnexecutedToolCallTest {

    private static final String DSML_BLOCK = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="list_dir">
            <｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""";

    @Test
    void flagsDsmlThatWasNotConvertedToToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + contentDelta(DSML_BLOCK) + "\n\ndata: [DONE]\n\n"));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString());

            // 没有开放任何工具时，DSML 不会被转换，原样留在正文里
            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("列目录")), null);

            assertFalse(response.hasToolCalls());
            assertTrue(client.looksLikeUnexecutedToolCall(response.content()));
            assertFalse(client.looksLikeUnexecutedToolCall("普通回答，没有调用"));
            assertFalse(client.looksLikeUnexecutedToolCall(null));
        }
    }

    @Test
    void plannerReportsUnexecutedDsmlInsteadOfAJsonCharacterError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + contentDelta(DSML_BLOCK) + "\n\ndata: [DONE]\n\n"));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString());
            Planner planner = new Planner(client, new PrintStream(OutputStream.nullOutputStream()));
            planner.setProjectMemorySupplier(() -> "");

            IOException error = assertThrows(IOException.class,
                    () -> planner.createPlan("帮我制定一份计划，我想要给同学们演示plan模式"));

            assertTrue(error.getMessage().contains("工具调用未执行"), error.getMessage());
            assertTrue(error.getMessage().contains("规划阶段不提供工具"), error.getMessage());
            assertFalse(error.getMessage().contains("Unexpected character"));
            assertEquals(1, server.getRequestCount(), "不自动重试错误的规划回复");
        }
    }

    @Test
    void plannerAcceptsValidJsonThatOnlyMentionsDsmlInATaskDescription() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String planJson = """
                    {"tasks":[{"id":"a","description":"分析 <｜｜DSML｜｜ calls> 文本的处理方式"}]}
                    """;
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + contentDelta(planJson) + "\n\ndata: [DONE]\n\n"));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString());
            Planner planner = new Planner(client, new PrintStream(OutputStream.nullOutputStream()));
            planner.setProjectMemorySupplier(() -> "");

            var plan = planner.createPlan("分析 DSML 的处理方式");

            assertEquals("分析 <｜｜DSML｜｜ calls> 文本的处理方式", plan.getTask("task_1").getDescription());
            assertEquals(1, server.getRequestCount());
        }
    }

    private static String contentDelta(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"delta\":{\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}]}";
    }
}
