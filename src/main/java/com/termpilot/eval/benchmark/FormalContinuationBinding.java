package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.*;
import com.termpilot.eval.benchmark.formal.*;
import com.termpilot.eval.benchmark.relay.*;
import java.io.IOException;
import java.util.*;

/** Frozen E3/E4 host deliveries, native tool results and acknowledged compaction boundaries. */
public final class FormalContinuationBinding {
    private final FrozenRemainingSource source;
    private FormalContinuationBinding(FrozenRemainingSource source) { this.source = source; }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return Set.of("E3", "E4").contains(plan.id()) && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.FILE_ONLY && plan.mockProfile().equals("none")
                && plan.evidenceRequirements().containsAll(List.of("user_deliveries", "request_views", "tool_state"))
                && (!plan.id().equals("E4") || plan.evidenceRequirements().containsAll(List.of("compaction_calls", "append_only_history", "view_checkpoint")));
    }
    static FormalContinuationBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported continuation binding");
        return new FormalContinuationBinding(FrozenRemainingSource.capture(plan));
    }
    void verifyUnchanged() throws IOException { source.verifyUnchanged(); }
    Session newSession() throws IOException { verifyUnchanged(); return new Session(source); }

    public static final class Session implements ContinuationInteraction {
        private final FrozenRemainingSource source;
        private final List<Map<String, Object>> provider = new ArrayList<>(), summaries = new ArrayList<>(), requests = new ArrayList<>();
        private final List<JsonNode> tools = new ArrayList<>();
        private final List<BenchmarkRelayProtocol.WireToolCall> calls = new ArrayList<>();
        private BenchmarkRelayProtocol.ChatRequest active;
        private int current = 1, completedTools;
        private boolean compacting, compacted, closed;
        private JsonNode before = FrozenRemainingSource.JSON.createArrayNode(), after = FrozenRemainingSource.JSON.createArrayNode();
        private String ledgerBefore = "", ledgerAfter = "";
        private boolean begun;
        private int boundProviderRequests, observedFingerprintRequests;
        private List<BenchmarkRelayProtocol.WireMessage> taskSystem, summarySystem;
        private boolean fingerprintFailed;
        void begin(BenchmarkProtocol.WorkerRequest request, java.nio.file.Path workspace, java.nio.file.Path home) throws IOException {
            if (begun || closed) throw new IOException("continuation session reused");
            source.requireEpisode(request, workspace, home); begun = true;
        }
        private Session(FrozenRemainingSource source) { this.source = source; }
        public String firstUserMessage() { return source.oracle.path("turns").get(0).asText(); }
        @Override public void beginProviderRequest(int turn, BenchmarkRelayProtocol.ChatRequest request) throws IOException {
            if (!begun || closed || active != null || turn != current || provider.size() >= source.plan.hardMaxIterations() + 1)
                throw new IOException("continuation provider order mismatch");
            if (!compacting) {
                var users = request.messages().stream().filter(m -> m.role().equals("user")).toList();
                if (users.isEmpty() || !users.get(users.size() - 1).content().equals(source.oracle.path("turns").get(current - 1).asText()))
                    throw new IOException("frozen continuation input was not delivered");
            } else if (!request.tools().isEmpty()) throw new IOException("summary must not have tools");
            var systems = request.messages().stream().filter(m -> m.role().equals("system")).toList();
            if (systems.size() != 1 || systems.get(0).content() == null || systems.get(0).content().isBlank()) {
                fingerprintFailed = true; throw new IOException("continuation system prompt missing");
            }
            var expectedSystem = compacting ? summarySystem : taskSystem;
            if (expectedSystem != null && !expectedSystem.equals(systems)) {
                fingerprintFailed = true; throw new IOException("continuation system prompt changed within its phase");
            }
            if (compacting) summarySystem = systems; else taskSystem = systems;
            active = request; boundProviderRequests++;
        }
        void requireActiveProviderInput(List<com.termpilot.llm.LlmClient.Message> messages,
                List<com.termpilot.llm.LlmClient.Tool> definitions) throws IOException {
            if (fingerprintFailed || active == null || observedFingerprintRequests != boundProviderRequests - 1
                    || !RelayWireConversions.fromWireMessages(active.messages()).equals(messages)
                    || !RelayWireConversions.fromWireTools(active.tools()).equals(definitions)) {
                fingerprintFailed = true; throw new IOException("unbound continuation fingerprint request");
            }
            observedFingerprintRequests++;
        }
        boolean fingerprintEvidenceComplete(int calls) {
            return !fingerprintFailed && taskSystem != null && calls > 0
                    && calls == boundProviderRequests && calls == observedFingerprintRequests;
        }
        @Override public void recordProviderTurn(int turn, List<BenchmarkRelayProtocol.WireMessage> messages,
                List<BenchmarkRelayProtocol.WireTool> definitions, BenchmarkRelayProtocol.WireChatResponse response) throws IOException {
            if (active == null || turn != current || !active.messages().equals(messages) || !active.tools().equals(definitions))
                throw new IOException("unbound continuation response");
            var event = new LinkedHashMap<String, Object>();
            event.put("turn", turn - 1); event.put("phase", compacting ? "summary" : "turn");
            event.put("messages", RelayWireConversions.fromWireMessages(messages)); event.put("response", response.content() == null ? "" : response.content());
            event.put("toolCalls", RelayWireConversions.fromWireResponse(response).toolCalls());
            var nativeResponse = RelayWireConversions.fromWireResponse(response);
            event.put("assistant", com.termpilot.llm.LlmClient.Message.assistant(nativeResponse.reasoningContent(), nativeResponse.content(), nativeResponse.toolCalls()));
            event.put("tools", RelayWireConversions.fromWireTools(definitions));
            event.put("callId", active.callId()); event.put("inputTokens", response.inputTokens()); event.put("outputTokens", response.outputTokens());
            event.put("cachedInputTokens", response.cachedInputTokens()); event.put("resolvedModel", response.resolvedModel()); event.put("usagePresent", response.usagePresent());
            provider.add(event);
            if (compacting) { if (!response.toolCalls().isEmpty()) throw new IOException("summary returned tools"); summaries.add(event); }
            else calls.addAll(response.toolCalls());
            active = null;
        }
        @Override public String nextUserMessage(String answer) throws IOException {
            finishTurn(answer);
            if (current >= 9) throw new IOException("extra continuation delivery");
            current++;
            return source.oracle.path("turns").get(current - 1).asText();
        }
        @Override public boolean compactBeforeNextTurn() { return current - 1 == source.oracle.path("compactBeforeTurn").asInt(); }
        @Override public void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> nativeTools) throws IOException {
            for (var result : nativeTools) {
                if (completedTools >= tools.size()) throw new IOException("missing native raw tool result");
                var raw = tools.get(completedTools++);
                if (!result.callId().equals(raw.path("id").asText()) || !result.toolName().equals(raw.path("name").asText())
                        || !FrozenRemainingSource.JSON.readTree(result.argumentsJson()).equals(raw.path("arguments"))
                        || !result.resultSha256().equals(BenchmarkRelayProtocol.textSha256(raw.path("rawResult").asText()))
                        || result.resultChars() != raw.path("rawResult").asText().length() || result.successful() != raw.path("successful").asBoolean())
                    throw new IOException("terminal native tool result mismatch");
            }
        }
        @Override public void observe(BenchmarkRelayProtocol.ContextEvent event) throws IOException {
            if (closed || event.turn() != current || active != null) throw new IOException("context observation order mismatch");
            if (event.kind().equals("TOOL_RESULT")) {
                var data = event.data();
                int n = tools.size();
                if (compacting || n >= calls.size()) throw new IOException("raw tool result without provider call");
                var call = calls.get(n);
                if (!call.id().equals(data.path("id").asText()) || !call.name().equals(data.path("name").asText())
                        || !FrozenRemainingSource.JSON.readTree(call.arguments()).equals(data.path("arguments"))
                        || !data.path("rawResult").isTextual() || !data.path("successful").isBoolean()) throw new IOException("raw tool result invocation mismatch");
                tools.add(data);
            } else {
                if (current != 9 || !source.plan.id().equals("E4") || !event.data().path("view").isArray() || !event.data().path("ledger").isTextual())
                    throw new IOException("unexpected native compaction boundary");
                if (event.kind().equals("PRE_COMPACTION")) {
                    if (compacted || compacting) throw new IOException("repeated compaction");
                    before = event.data().path("view"); ledgerBefore = event.data().path("ledger").asText(); compacting = true;
                } else {
                    if (!compacting || summaries.isEmpty()) throw new IOException("summary evidence missing");
                    after = event.data().path("view"); ledgerAfter = event.data().path("ledger").asText();
                    if (!ledgerAfter.startsWith(ledgerBefore) || ledgerAfter.length() == ledgerBefore.length()) throw new IOException("history prefix changed");
                    compacting = false; compacted = true;
                }
            }
        }
        @Override public void recordExchange(int turn, BenchmarkRelayProtocol.Frame request, BenchmarkRelayProtocol.Frame response) throws IOException {
            if (request instanceof BenchmarkRelayProtocol.WorkerComplete terminal) { finishTurn(terminal.answer()); closed = true; }
        }
        private void finishTurn(String answer) throws IOException {
            var last = provider.isEmpty() ? null : provider.get(provider.size() - 1);
            if (active != null || compacting || last == null || !last.get("phase").equals("turn")
                    || !last.get("turn").equals(current - 1) || !last.get("response").equals(answer == null ? "" : answer)
                    || tools.size() != calls.size() || completedTools != tools.size()) throw new IOException("incomplete native turn evidence");
            requests.add(Map.of("turn", current - 1, "messages", last.get("messages"), "response", last.get("response"), "assistant", last.get("assistant")));
        }
        JsonNode evidence() throws IOException {
            source.verifyUnchanged();
            if (!closed || current != 9 || requests.size() != 9 || source.plan.id().equals("E4") != compacted)
                throw new IOException("continuation episode incomplete");
            var trace = new LinkedHashMap<String, Object>();
            trace.put("executionOrigin", "FORMAL_EPISODE"); trace.put("referenceOrigin", "FORMAL_HOST_RELAY"); trace.put("sourceSha256", source.dependency.sha256());
            trace.put("requests", requests); trace.put("summaryRequests", summaries); trace.put("providerRequests", provider); trace.put("tools", tools);
            trace.put("beforeView", before); trace.put("afterView", after); trace.put("ledgerBefore", ledgerBefore); trace.put("ledgerAfter", ledgerAfter);
            return FrozenRemainingSource.JSON.valueToTree(trace);
        }
    }
}
