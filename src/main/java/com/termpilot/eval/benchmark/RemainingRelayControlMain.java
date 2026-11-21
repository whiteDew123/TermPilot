package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.*;
import com.termpilot.eval.benchmark.finalset.generator.*;
import com.termpilot.eval.benchmark.formal.*;
import com.termpilot.eval.benchmark.relay.*;
import com.termpilot.eval.benchmark.scoring.ScoringContract;
import com.termpilot.llm.LlmClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** In-memory native worker/host relay control. Zero provider APIs, zero Docker; private artifacts only. */
public final class RemainingRelayControlMain {
    private static final ObjectMapper JSON = FrozenRemainingSource.JSON;
    private RemainingRelayControlMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("D5", "E3", "E4").contains(args[1]) || !Set.of("correct", "wrong", "duplicate", "destructive", "external").contains(args[3]))
            throw new IllegalArgumentException("usage: <private-draft-source> D5|E3|E4 <new-private-output> correct|wrong|duplicate|destructive|external");
        run(Path.of(args[0]), args[1], Path.of(args[2]), args[3], "2026-10-10");
    }
    static Path run(Path source, String id, Path output, String scenario, String runtimeDate) throws Exception {
        new FinalSourceGenerator().inspect(source);
        if (!output.isAbsolute() || !output.equals(output.normalize()) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                || !output.getParent().equals(output.getParent().toRealPath())) throw new IOException("new canonical control directory required");
        for (Path p = output.getParent(); p != null; p = p.getParent()) if (Files.exists(p.resolve(".git"))) throw new IOException("control must remain outside Git");
        Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var plan = captureDraftCase(source, id, output.resolve("frozen-control"));
        Path workspace = Files.createDirectory(output.resolve("workspace")), home = Files.createDirectory(output.resolve("home"));
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwx------")); Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        var fixture = FormalFixtureMaterializer.materialize(plan.fixture(), workspace); fixture.verifyReady();
        var oracle = JSON.readTree(source.resolve("validators/final/_private/oracles/" + id + ".json").toFile());
        var provider = new Script(oracle, scenario);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null, "offline-control-key", "react",
                BenchmarkToolProfile.valueOf(plan.toolProfile().name()), new BenchmarkProtocol.AgentLimits(plan.tokenBudget(), plan.hardMaxIterations(), plan.stagnationWindow(), 1000000, 16384),
                runtimeDate, plan.prompt(), workspace.toString(), home.toString(), output.toString());
        var browser = id.equals("D5") ? FormalBrowserBinding.capture(plan).newSession() : null;
        var continuation = id.equals("D5") ? null : FormalContinuationBinding.capture(plan).newSession();
        if (browser != null) browser.start(request, workspace, home); else continuation.begin(request, workspace, home);
        var endpoint = browser != null ? browser : continuation;
        var capped = ContextWindowCappedLlmClient.cap(provider, 1000000, 16384);
        var tracing = new TracingLlmClient(capped, output.resolve("llm-trace.jsonl"), null, null, continuation);
        var start = DockerBenchmarkWorkerProcess.session(request, tracing, Duration.ofSeconds(90), endpoint.serverNames(),
                continuation == null ? BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN : BenchmarkRelayProtocol.InteractionMode.NINE_TURN_CONTINUATION,
                continuation == null ? browser.firstUserMessage() : continuation.firstUserMessage());
        String previousHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString());
        var pool = Executors.newSingleThreadExecutor();
        try (var workerInput = new PipedInputStream(4 * 1048576); var hostOutput = new PipedOutputStream(workerInput);
             var hostInput = new PipedInputStream(4 * 1048576); var workerOutput = new PipedOutputStream(hostInput)) {
            var worker = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerInput, workerOutput, workspace); return null; });
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostInput, hostOutput), start, tracing, endpoint);
            BenchmarkProviderRelay.ServeResult status;
            do { status = relay.serveNext(); } while (status != BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE && status != BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);
            worker.get(90, TimeUnit.SECONDS);
            if (!(relay.terminalFrame() instanceof BenchmarkRelayProtocol.WorkerComplete complete)) throw new IOException("offline native worker failed");
            JsonNode trace = browser != null ? browser.evidence() : continuation.evidence();
            if (continuation != null) ((com.fasterxml.jackson.databind.node.ObjectNode) trace).put("executionOrigin", "OFFLINE_SCRIPT_CONTROL");
            var envelope = JSON.createObjectNode().put("schemaVersion", 10).put("caseId", id).put("repeat", 1).put("mode", "react")
                    .put("toolProfile", plan.toolProfile().name()).put("answer", complete.answer());
            envelope.set("llmMetrics", JSON.valueToTree(BenchmarkEvidenceEnvelope.EvidenceMetricsV2.from(tracing.metrics())));
            envelope.set("toolExecutions", JSON.valueToTree(complete.toolExecutions())); envelope.set(browser != null ? "browser" : "context", trace);
            write(output.resolve("evidence.json"), envelope);
            write(output.resolve("control-status.json"), JSON.valueToTree(Map.of("caseId", id, "scenario", scenario,
                    "realProviderCalls", 0, "dockerCalls", 0, "publishable", false, "formalHostRelayCode", true, "nativeAgent", true)));
            return output.resolve("evidence.json");
        } finally {
            if (browser != null) browser.close(); pool.shutdownNow(); System.setProperty("user.home", previousHome);
        }
    }
    static FormalExecutionPlan.CasePlan captureDraftCase(Path source, String id, Path target) throws Exception {
        var contract = FinalCaseContractCompiler.compile(source, id);
        Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var paths = new TreeSet<>(contract.verifierDependencyPaths()); paths.add("prompts/final/" + id + ".md");
        try (var files = Files.walk(source.resolve("fixtures/final/" + id))) {
            for (Path path : files.filter(Files::isRegularFile).toList()) paths.add(source.relativize(path).toString());
        }
        for (String relative : paths) {
            Path destination = target.resolve(relative); Files.createDirectories(destination.getParent());
            Files.copy(source.resolve(relative), destination);
        }
        try (var dirs = Files.walk(target)) {
            for (Path path : dirs.toList()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "r-x------"
                    : path.equals(target.resolve(contract.verifierEntryPath())) ? "r-x------" : "r--------"));
        }
        var dependencies = new ArrayList<FormalExecutionPlan.VerifierDependency>();
        for (String path : contract.verifierDependencyPaths()) dependencies.add(new FormalExecutionPlan.VerifierDependency(path, target.resolve(path),
                path.equals(contract.verifierEntryPath()) ? "0500" : "0400", Files.size(target.resolve(path)), UsageCalibration.hash(target.resolve(path))));
        var files = new ArrayList<FormalExecutionPlan.FixtureFile>(); String fixturePath = "fixtures/final/" + id;
        try (var walk = Files.walk(target.resolve(fixturePath))) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) files.add(new FormalExecutionPlan.FixtureFile(target.relativize(path).toString(),
                    UsageCalibration.hash(path), Files.size(path), "0400"));
        }
        var text = new StringBuilder("termpilot-formal-fixture-snapshot-v1\0DIRECTORY\0" + fixturePath + "\n");
        for (var file : files) text.append(file.frozenPath()).append('\0').append(file.sha256()).append('\0').append(file.mode()).append('\0').append(file.size()).append('\n');
        var fixture = new FormalExecutionPlan.FixtureSnapshot(fixturePath, fixturePath, target.resolve(fixturePath), FormalBenchmarkPreflight.FixtureKind.DIRECTORY,
                UsageCalibration.digest(text.toString().getBytes(StandardCharsets.UTF_8)), files.size(), files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
        String prompt = Files.readString(target.resolve("prompts/final/" + id + ".md"));
        Path scoringPath = target.resolve(contract.scoringContractPath()); var scoring = ScoringContract.load(scoringPath);
        return new FormalExecutionPlan.CasePlan(1, contract, scoring, scoringPath, UsageCalibration.hash(scoringPath), prompt,
                BenchmarkRelayProtocol.textSha256(prompt), fixture, new FormalExecutionPlan.VerifierCommand(target,
                    List.of(contract.verifierEntryPath(), "{workspace}", "{evidence}"), contract.verifierEntryPath(), target.resolve(contract.verifierEntryPath()),
                    contract.verifierSha256(), dependencies, contract.verifierBundleSha256()));
    }
    static void write(Path path, JsonNode value) throws IOException {
        Files.write(path, JSON.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        if (!JSON.readTree(JSON.writeValueAsBytes(value)).equals(JSON.readTree(Files.readAllBytes(path)))) throw new IOException("control artifact readback mismatch");
    }
    private static final class Script implements LlmClient {
        final JsonNode oracle; final String scenario; int iteration, activeTurn = -1; final List<String> snapshots = new ArrayList<>();
        Script(JsonNode oracle, String scenario) { this.oracle = oracle; this.scenario = scenario; }
        public int maxContextWindow() { return 1000000; }
        public String getProviderName() { return "deepseek"; }
        public String getModelName() { return "deepseek-v4-flash"; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, null); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            var calls = new ArrayList<ToolCall>(); String body = "收到";
            if (oracle.path("caseId").asText().equals("D5")) {
                String url = tools.stream().filter(t -> t.name().equals("mcp__browser__navigate")).findFirst().orElseThrow().description().split(": ", 2)[1].split(" ")[0];
                var expected = oracle.path("expected");
                switch (iteration++) {
                    case 0 -> calls.add(call("navigate", Map.of("url", url)));
                    case 1 -> calls.add(call("snapshot", Map.of()));
                    case 2 -> {
                        if (scenario.equals("destructive")) calls.add(call("click", Map.of("ref", "delete")));
                        calls.add(call("fill", Map.of("ref", "customer", "value", expected.path("customer").asText())));
                        calls.add(call("fill", Map.of("ref", "quantity", "value", expected.path("quantity").asText())));
                    }
                    case 3 -> {
                        if (scenario.equals("external")) calls.add(call("navigate", Map.of("url", "https://example.invalid/export")));
                        calls.add(call("click", Map.of("ref", "submit")));
                        if (scenario.equals("duplicate")) calls.add(call("click", Map.of("ref", "submit")));
                    }
                    case 4 -> calls.add(call("snapshot", Map.of()));
                    default -> body = expected.toString();
                }
            } else {
                String lastUser = messages.stream().filter(m -> m.role().equals("user")).reduce((a,b) -> b).orElseThrow().content();
                int turn = -1;
                for (int n = 0; n < 9; n++) if (lastUser.equals(oracle.path("turns").get(n).asText())) turn = n;
                if (turn != activeTurn) { activeTurn = turn; iteration = 0; }
                var value = oracle.path("expected");
                if (turn == -1) body = "## 当前目标与成功条件\n完成 result.json\n## 用户要求与已确认决定\nproject=" + value.path("project").asText()
                        + "\n## 已完成工作及证据\namount=" + value.path("amount").asText() + ",pending=" + value.path("pending").asText()
                        + "\n## 未解决问题与下一步\n遵守最新 limit 与 region，写完回读。";
                else if (turn == 0 && iteration++ == 0) calls.add(call("read_file", Map.of("path", "items.json")));
                else if (turn == 8) {
                    var result = value.deepCopy(); if (scenario.equals("wrong")) ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("limit",99).put("region","old");
                    body = result.toString();
                    if (iteration++ == 0) calls.add(call("write_file", Map.of("path", "result.json", "content", body)));
                    else if (iteration == 2) calls.add(call("read_file", Map.of("path", "result.json")));
                }
            }
            if (!calls.isEmpty()) body = "";
            if (listener != null && !body.isEmpty()) listener.onContentDelta(body);
            return new ChatResponse("assistant", body, null, calls, 1, 1, 0, getModelName(), true);
        }
        ToolCall call(String name, Map<String, Object> args) throws IOException {
            String actual = oracle.path("caseId").asText().equals("D5") ? "mcp__browser__" + (name.equals("snapshot") ? "take_snapshot" : name) : name;
            return new ToolCall("control-" + UUID.randomUUID(), new ToolCall.Function(actual, JSON.writeValueAsString(args)));
        }
    }
}
