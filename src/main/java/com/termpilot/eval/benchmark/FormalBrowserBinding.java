package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.termpilot.eval.benchmark.formal.*;
import com.termpilot.eval.benchmark.relay.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** D5 uses native MCP dispatch to one host-owned fresh local browser. */
public final class FormalBrowserBinding {
    private final FrozenRemainingSource source;
    private FormalBrowserBinding(FrozenRemainingSource source) { this.source = source; }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return plan.id().equals("D5") && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.MOCK_BROWSER && plan.mockProfile().equals("d5-local-form-v1")
                && plan.evidenceRequirements().containsAll(List.of("browser_audit", "browser_state", "network_audit"));
    }
    static FormalBrowserBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported browser binding");
        return new FormalBrowserBinding(FrozenRemainingSource.capture(plan));
    }
    void verifyUnchanged() throws IOException { source.verifyUnchanged(); }
    Session newSession() throws IOException { verifyUnchanged(); return new Session(source); }

    public static final class Session implements BrowserInteraction, AutoCloseable {
        private final FrozenRemainingSource source;
        private HostBrowserProcess browser;
        private final List<Map<String, Object>> provider = new ArrayList<>(), events = new ArrayList<>();
        private final List<BenchmarkRelayProtocol.WireToolCall> calls = new ArrayList<>();
        private List<BenchmarkRelayProtocol.WireToolExecution> completed;
        private final List<Map<String, Object>> observations = new ArrayList<>();
        private final Set<String> dispatched = new HashSet<>();
        private JsonNode lastState, network, denied;
        private boolean initialized, listed, closed;
        private Session(FrozenRemainingSource source) { this.source = source; }
        void start(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home) throws IOException {
            if (browser != null || closed) throw new IOException("browser session reused");
            source.requireEpisode(request, workspace, home); browser = new HostBrowserProcess(workspace.getParent(), source.oracle.path("files").path("form.html").asText());
        }
        @Override public String firstUserMessage() {
            if (browser == null) throw new IllegalStateException("browser not started");
            return "使用浏览器填写并提交本题订单。题目页面：" + browser.identity().path("url").asText() + "\n" + source.plan.prompt();
        }
        @Override public void recordProviderTurn(List<BenchmarkRelayProtocol.WireMessage> messages,
                BenchmarkRelayProtocol.WireChatResponse response) throws IOException {
            if (closed || !listed || provider.size() >= source.plan.hardMaxIterations() + 1) throw new IOException("unbound browser provider call");
            var users = messages.stream().filter(m -> m.role().equals("user")).toList();
            if (users.size() != 1 || !users.get(0).content().equals(firstUserMessage())) throw new IOException("browser frozen input was not delivered");
            calls.addAll(response.toolCalls());
            provider.add(Map.of("messages", RelayWireConversions.fromWireMessages(messages), "response", RelayWireConversions.fromWireResponse(response)));
        }
        @Override public JsonNode exchange(JsonNode message) throws IOException {
            if (browser == null || closed) throw new IOException("browser session unavailable");
            if (!message.path("jsonrpc").asText().equals("2.0")) throw new IOException("invalid browser RPC");
            String method = message.path("method").asText();
            var result = FrozenRemainingSource.JSON.createObjectNode();
            if (method.equals("initialize") && !initialized && message.path("params").path("protocolVersion").isTextual()) {
                initialized = true; result.put("protocolVersion", message.path("params").path("protocolVersion").asText());
                result.set("capabilities", FrozenRemainingSource.JSON.valueToTree(Map.of("tools", Map.of("listChanged", false))));
                result.set("serverInfo", FrozenRemainingSource.JSON.valueToTree(Map.of("name", "d5-isolated-browser", "version", "1")));
            } else if (method.equals("notifications/initialized") && initialized && !listed && !message.has("id")) return FrozenRemainingSource.JSON.nullNode();
            else if (method.equals("tools/list") && initialized && !listed) { listed = true; result.set("tools", catalog()); }
            else if (method.equals("tools/call") && listed) {
                String nativeName = message.path("params").path("name").asText();
                String operation = nativeName.equals("take_snapshot") ? "snapshot" : nativeName; JsonNode args = message.path("params").path("arguments");
                BenchmarkRelayProtocol.WireToolCall match = null;
                for (var call : calls) if (!dispatched.contains(call.id()) && call.name().equals("mcp__browser__" + nativeName)
                        && FrozenRemainingSource.JSON.readTree(call.arguments()).equals(args)) { match = call; break; }
                if (match == null) throw new IOException("browser action absent from provider response");
                dispatched.add(match.id());
                JsonNode observation = browser.execute(operation, args);
                lastState = observation.path("state"); network = observation.path("networkRequests"); denied = observation.path("deniedNetworkRequests");
                events.add(Map.of("sequence", events.size() + 1, "name", operation, "arguments", args,
                        "successful", observation.path("successful").asBoolean(), "state", lastState));
                String text = observation.path("text").asText();
                String nativeResult = observation.path("successful").asBoolean() ? (text.isEmpty() ? "browser action completed" : text)
                        : "MCP 工具返回错误: " + (text.isEmpty() ? "browser action completed" : text);
                observations.add(Map.of("callId", match.id(), "toolName", match.name(), "argumentsJson", match.arguments(),
                        "result", nativeResult, "eventSequence", events.size()));
                result.put("isError", !observation.path("successful").asBoolean());
                result.set("content", FrozenRemainingSource.JSON.valueToTree(List.of(Map.of("type", "text", "text", text.isEmpty() ? "browser action completed" : text))));
            } else throw new IOException("unexpected browser RPC method/order");
            ObjectNode envelope = FrozenRemainingSource.JSON.createObjectNode().put("jsonrpc", "2.0");
            envelope.set("id", message.get("id")); envelope.set("result", result); return envelope;
        }
        private JsonNode catalog() {
            var catalog = FrozenRemainingSource.JSON.createArrayNode();
            for (String name : List.of("navigate", "snapshot", "fill", "click")) {
                var properties = new LinkedHashMap<String, Object>();
                if (name.equals("navigate")) properties.put("url", Map.of("type", "string"));
                if (name.equals("fill") || name.equals("click")) properties.put("ref", Map.of("type", "string"));
                if (name.equals("fill")) properties.put("value", Map.of("type", "string"));
                String description = name.equals("navigate") ? "Open only this episode's local form: " + browser.identity().path("url").asText()
                        : name.equals("snapshot") ? "Read the current page snapshot and its stable refs; read before acting and after submission" : "Use refs from the observed page snapshot";
                catalog.add(FrozenRemainingSource.JSON.valueToTree(Map.of("name", name.equals("snapshot") ? "take_snapshot" : name, "description", description, "inputSchema",
                        Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false))));
            }
            return catalog;
        }
        @Override public void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> tools) throws IOException {
            if (completed != null || tools.size() != calls.size()) throw new IOException("browser terminal tool evidence incomplete");
            for (int n = 0; n < tools.size(); n++) {
                var tool = tools.get(n); var call = calls.get(n);
                if (!tool.callId().equals(call.id()) || !tool.toolName().equals(call.name()) || !tool.argumentsJson().equals(call.arguments())
                        || tool.successful() && !dispatched.contains(call.id())) throw new IOException("browser native tool evidence mismatch");
            }
            for (var observation : observations) {
                var tool = tools.stream().filter(t -> t.callId().equals(observation.get("callId"))).findFirst().orElseThrow();
                if (!tool.resultSha256().equals(BenchmarkRelayProtocol.textSha256((String) observation.get("result")))
                        || tool.resultChars() != ((String) observation.get("result")).length()) throw new IOException("browser result differs from native MCP output");
            }
            completed = List.copyOf(tools);
        }
        JsonNode evidence() throws IOException {
            source.verifyUnchanged();
            if (completed == null || browser == null || lastState == null) throw new IOException("browser episode observation incomplete");
            var trace = new LinkedHashMap<String, Object>();
            trace.put("sourceSha256", source.dependency.sha256()); trace.put("referenceOrigin", "FORMAL_HOST_RELAY");
            trace.put("events", events); trace.put("finalState", lastState); trace.put("networkRequests", network); trace.put("deniedNetworkRequests", denied);
            trace.put("observations", observations); trace.put("providerRequests", provider); trace.put("nativeTools", completed);
            trace.put("deliveredPromptSha256", BenchmarkRelayProtocol.textSha256(firstUserMessage())); trace.put("browserIdentity", browser.identity()); trace.put("artifacts", browser.artifacts());
            return FrozenRemainingSource.JSON.valueToTree(trace);
        }
        @Override public void close() { if (closed) return; closed = true; if (browser != null) browser.close(); }
    }
}
