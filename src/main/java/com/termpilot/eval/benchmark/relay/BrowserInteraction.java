package com.termpilot.eval.benchmark.relay;

import java.io.IOException;
import java.util.List;

/** Host browser audit receives raw provider calls before any model action is dispatched. */
public interface BrowserInteraction extends BenchmarkProviderRelay.MockMcpEndpoint {
    @Override default List<String> serverNames() { return List.of("browser"); }
    @Override default com.fasterxml.jackson.databind.JsonNode exchange(String server, com.fasterxml.jackson.databind.JsonNode message) throws IOException {
        if (!"browser".equals(server)) throw new IOException("browser server mismatch");
        return exchange(message);
    }
    String firstUserMessage();
    void recordProviderTurn(List<BenchmarkRelayProtocol.WireMessage> messages,
                            BenchmarkRelayProtocol.WireChatResponse response) throws IOException;
    void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> tools) throws IOException;
}
