package com.termpilot.eval.benchmark.relay;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

/** Nine frozen deliveries owned by the host. No mock tools or approval are exposed. */
public interface ContinuationInteraction extends ScriptedInteraction {
    @Override default List<String> serverNames() { return List.of(); }
    @Override default JsonNode exchange(JsonNode message) throws IOException { throw new IOException("continuation has no MCP service"); }
    @Override default Decision approve(String name, String arguments) throws IOException { throw new IOException("continuation has no approval channel"); }
    default void beginProviderRequest(int turn, BenchmarkRelayProtocol.ChatRequest request) throws IOException { }
    String firstUserMessage();
    boolean compactBeforeNextTurn();
    void observe(BenchmarkRelayProtocol.ContextEvent event) throws IOException;
}
