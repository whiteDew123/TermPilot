package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.termpilot.agent.Agent;
import com.termpilot.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.termpilot.history.ConversationLedger;
import com.termpilot.llm.LlmClient;
import com.termpilot.memory.AutoCompactionManager;
import com.termpilot.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline native Agent controls. No provider client is constructed and no model API is called. */
public final class ContinuationControlMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ContinuationControlMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("E3", "E4").contains(args[1]))
            throw new IllegalArgumentException("usage: <private-source-root> E3|E4 <new-private-episode-root>");
        run(Path.of(args[0]), args[1], Path.of(args[2]), false);
    }

    public static Path run(Path source, String id, Path episode, boolean wrongAnswer) throws Exception {
        if (!List.of("E3", "E4").contains(id)) throw new IOException("unsupported control");
        new FinalSourceGenerator().inspect(source);
        if (!episode.isAbsolute() || !episode.equals(episode.normalize()) || Files.exists(episode, LinkOption.NOFOLLOW_LINKS)
                || !episode.getParent().equals(episode.getParent().toRealPath())) throw new IOException("new canonical episode root required");
        for (Path p = episode.getParent(); p != null; p = p.getParent())
            if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("control must stay outside Git");
        Files.createDirectory(episode, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path workspace = Files.createDirectory(episode.resolve("workspace"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        JsonNode oracle = JSON.readTree(source.resolve("validators/final/_private/oracles/" + id + ".json").toFile());
        for (var iterator = oracle.path("files").fields(); iterator.hasNext(); ) {
            var file = iterator.next(); Path path = workspace.resolve(file.getKey());
            Files.writeString(path, file.getValue().asText()); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        String priorHome = System.getProperty("user.home");
        System.setProperty("user.home", episode.toString());
        String priorSessionMemory = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
        try {
            var script = new Script(oracle, wrongAnswer);
            var tools = new BenchmarkToolRegistry(BenchmarkToolProfile.FILE_ONLY);
            tools.setProjectPath(workspace.toString());
            var audit = new ArrayList<Map<String, Object>>();
            tools.setExecutionObserver(result -> {
                try {
                    JsonNode args = JSON.readTree(result.argumentsJson());
                    var event = new LinkedHashMap<String, Object>();
                    event.put("name", result.name()); event.put("path", args.path("path").asText());
                    event.put("id", result.id()); event.put("arguments", args);
                    event.put("successful", result.successful()); event.put("rawResult", result.result());
                    if (result.name().equals("write_file")) event.put("content", args.path("content").asText());
                    if (result.name().equals("read_file")) {
                        // Preserve raw results separately; content read-back is cross-checked against the raw line-numbered output.
                        Path path = workspace.resolve(args.path("path").asText());
                        if (result.successful() && Files.isRegularFile(path)) event.put("result", Files.readString(path));
                    }
                    audit.add(event);
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            var agent = new Agent(script, tools);
            agent.setReturnFinalResponseWhenStreamed(true);
            var ledger = ConversationLedger.open(episode.resolve("history"), "continuation-control");
            agent.setConversationLedger(ledger);
            var requests = new ArrayList<Map<String, Object>>();
            var trace = new LinkedHashMap<String, Object>();
            trace.put("referenceOrigin", "NATIVE_AGENT_SCRIPT_CONTROL_NOT_MODEL_EVIDENCE");
            trace.put("sourceSha256", sha(Files.readAllBytes(source.resolve("validators/final/_private/oracles/" + id + ".json"))));
            trace.put("beforeView", List.of()); trace.put("afterView", List.of());
            trace.put("ledgerBefore", ""); trace.put("ledgerAfter", "");
            String answer = "";
            for (int turn = 0; turn < oracle.path("turns").size(); turn++) {
                if (turn == oracle.path("compactBeforeTurn").asInt()) {
                    trace.put("beforeView", agent.getConversationHistory());
                    trace.put("ledgerBefore", Files.readString(ledger.file()));
                    script.summary = true;
                    var compacted = agent.compactHistoryNow();
                    script.summary = false;
                    if (!compacted.compacted()) throw new IOException("native compaction did not happen: " + compacted.error());
                    trace.put("afterView", agent.getConversationHistory());
                    trace.put("ledgerAfter", Files.readString(ledger.file()));
                }
                script.turn = turn; script.iteration = 0;
                answer = agent.runExplicitTask(oracle.path("turns").get(turn).asText(), oracle.path("turns").get(turn).asText());
                requests.add(Map.of("turn", turn, "messages", script.lastMessages, "response", script.lastResponse));
            }
            trace.put("requests", requests); trace.put("summaryRequests", script.summaries);
            trace.put("providerRequests", script.allRequests); trace.put("tools", audit);
            // Template supplies the development envelope shape only. Replace its reference trace and metrics.
            ObjectNode evidence = (ObjectNode) JSON.readTree(source.resolve("references/final/" + id + "/evidence.json").toFile());
            evidence.put("answer", answer); evidence.set("context", JSON.valueToTree(trace));
            ((ObjectNode) evidence.path("llmMetrics")).put("calls", script.allRequests.size())
                    .put("successfulCalls", script.allRequests.size()).put("toolCalls", audit.size()).put("resolvedModel", "offline-script-control");
            evidence.set("toolExecutions", JSON.valueToTree(audit));
            Path evidencePath = episode.resolve("evidence.json");
            Files.writeString(evidencePath, evidence.toString()); Files.setPosixFilePermissions(evidencePath, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(episode.resolve("control-status.json"), JSON.writeValueAsString(Map.of("caseId", id, "realProviderCalls", 0,
                    "publishable", false, "wrongAnswerControl", wrongAnswer, "nativeAgent", true, "nativeCompaction", id.equals("E4"))));
            Files.setPosixFilePermissions(episode.resolve("control-status.json"), PosixFilePermissions.fromString("rw-------"));
            return evidencePath;
        } finally {
            System.setProperty("user.home", priorHome);
            if (priorSessionMemory == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
            else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, priorSessionMemory);
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class Script implements LlmClient {
        final JsonNode oracle;
        final boolean wrong;
        int turn, iteration;
        boolean summary;
        String lastResponse;
        List<Message> lastMessages;
        final List<Map<String, Object>> summaries = new ArrayList<>(), allRequests = new ArrayList<>();
        Script(JsonNode oracle, boolean wrong) { this.oracle = oracle; this.wrong = wrong; }
        public int maxContextWindow() { return 1_000_000; }
        public String getProviderName() { return "offline-control"; }
        public String getModelName() { return "offline-script-control"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, null); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            List<ToolCall> calls = List.of(); String body;
            if (summary) {
                JsonNode value = oracle.path("expected");
                body = "## 当前目标与成功条件\n完成 result.json\n## 用户要求与已确认决定\nproject=" + value.path("project").asText()
                        + "\n## 已完成工作及证据\namount=" + value.path("amount").asText() + ",pending=" + value.path("pending").asText()
                        + "\n## 未解决问题与下一步\n遵守最新 limit 与 region，写完回读。";
            } else if (turn == 0 && iteration++ == 0) {
                calls = List.of(call("initial-read", "read_file", Map.of("path", "items.json"))); body = "";
            } else if (turn == oracle.path("turns").size() - 1) {
                ObjectNode output = (ObjectNode) oracle.path("expected").deepCopy();
                if (wrong) output.put("limit", 99).put("region", "old");
                body = output.toString();
                if (iteration++ == 0) { calls = List.of(call("final-write", "write_file", Map.of("path", "result.json", "content", body))); body = ""; }
                else if (iteration == 2) { calls = List.of(call("final-read", "read_file", Map.of("path", "result.json"))); body = ""; }
            } else body = "收到";
            var entry = new LinkedHashMap<String, Object>();
            entry.put("turn", turn); entry.put("phase", summary ? "summary" : "turn");
            entry.put("messages", List.copyOf(messages)); entry.put("response", body); entry.put("toolCalls", calls);
            allRequests.add(entry);
            if (summary) summaries.add(entry);
            else { lastMessages = List.copyOf(messages); lastResponse = body; }
            if (listener != null) { if (!body.isEmpty()) listener.onContentDelta(body); }
            return new ChatResponse("assistant", body, null, calls, 1, 1);
        }
        private ToolCall call(String id, String name, Map<String, Object> args) throws IOException {
            return new ToolCall(id, new ToolCall.Function(name, JSON.writeValueAsString(args)));
        }
    }
}
