package com.termpilot.agent;

import com.termpilot.llm.LlmClient;
import com.termpilot.prompt.TurnReminder;
import com.termpilot.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgentAutoFactExtractionTest {
    @TempDir Path tempDir;

    @Test
    void completedUserTurnIsExtractedAndRetrievedOnNextRequest() {
        String prior = System.getProperty("termpilot.memory.dir");
        String oldDate = System.getProperty(TurnReminder.RUNTIME_DATE_PROPERTY);
        System.setProperty(TurnReminder.RUNTIME_DATE_PROPERTY, "2026-11-09");
        System.setProperty("termpilot.memory.dir", tempDir.resolve("memory").toString());
        try {
            StubClient client = new StubClient(
                    "好的。",
                    "{\"facts\":[{\"quote\":\"我偏好中文回答\"}]}",
                    "我会用中文回答。",
                    "今天也用中文。");
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            Agent agent = new Agent(client, tools);
            agent.getMemoryManager().setAutoFactExtractionEnabled(true);

            assertTrue(agent.run("我偏好中文回答。现在先打个招呼。").contains("好的"));
            assertEquals(1, agent.getMemoryManager().getLongTermMemory().size());
            assertEquals(2, client.requests.size());

            agent.run("请按中文回答下一题。");
            assertEquals(3, client.requests.size());
            List<LlmClient.Message> first = client.requests.get(0);
            List<LlmClient.Message> second = client.requests.get(2);
            var secondInput = second.get(second.size() - 1);
            assertEquals(first.get(0), second.get(0));
            assertTrue(first.get(1).turnReminder().memory().isEmpty());
            assertTrue(secondInput.turnReminder().memory().contains("我偏好中文回答"));
            assertEquals("请按中文回答下一题。", secondInput.content());

            System.setProperty(TurnReminder.RUNTIME_DATE_PROPERTY, "2026-11-10");
            agent.run(secondInput.content());
            List<LlmClient.Message> third = client.requests.get(3);
            assertEquals(second, third.subList(0, second.size()));
            var thirdInput = third.get(third.size() - 1);
            assertEquals("2026-11-09", secondInput.turnReminder().date());
            assertEquals("2026-11-10", thirdInput.turnReminder().date());
            assertEquals(secondInput.turnReminder().zone(), thirdInput.turnReminder().zone());
        } finally {
            if (oldDate == null) System.clearProperty(TurnReminder.RUNTIME_DATE_PROPERTY);
            else System.setProperty(TurnReminder.RUNTIME_DATE_PROPERTY, oldDate);
            if (prior == null) System.clearProperty("termpilot.memory.dir");
            else System.setProperty("termpilot.memory.dir", prior);
        }
    }

    private static final class StubClient implements LlmClient {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();

        private StubClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                            StreamListener listener) throws IOException {
            requests.add(List.copyOf(messages));
            String content = responses.poll();
            if (content == null) throw new IOException("missing response");
            return new ChatResponse("assistant", content, null, 10, 5);
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test-provider"; }
        @Override public boolean supportsTools() { return false; }
    }
}
