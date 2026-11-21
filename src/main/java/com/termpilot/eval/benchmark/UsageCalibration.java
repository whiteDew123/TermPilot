package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.*;
import com.termpilot.eval.benchmark.formal.FormalExecutionPlan;
import com.termpilot.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.termpilot.llm.LlmClient;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Provider usage is the only tokenizer evidence. Offline estimates never satisfy this gate. */
public final class UsageCalibration {
    static final ObjectMapper JSON = FrozenRemainingSource.JSON;
    public static final List<String> MODELS = List.of("deepseek-flash", "glm-5.3-flash");
    private UsageCalibration() { }

    public record Request(int schemaVersion, String caseId, String sourceSha256, String candidateJarSha256,
                          String runtimeDate, List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
        public Request {
            if (schemaVersion != 1 || !"E3".equals(caseId) || !sha(sourceSha256) || !sha(candidateJarSha256)
                    || runtimeDate == null || !java.time.LocalDate.parse(runtimeDate).toString().equals(runtimeDate)
                    || messages == null || messages.isEmpty() || !messages.get(0).role().equals("system") || tools == null)
                throw new IllegalArgumentException("invalid calibration request");
            messages = List.copyOf(messages); tools = List.copyOf(tools);
        }
        @Override public String toString() { return "UsageCalibrationRequest[case=E3, private frozen input]"; }
    }
    public record Observation(String provider, String model, String resolvedModel, String requestSha256,
                              int inputTokens, int outputTokens, int cachedInputTokens, boolean usagePresent,
                              int contextCapTokens, int maxOutputTokens, String traceSha256) {
        public Observation {
            if (!Set.of("deepseek", "glm").contains(provider) || !MODELS.contains(model) || !sha(requestSha256)
                    || inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || cachedInputTokens > inputTokens || !sha(traceSha256))
                throw new IllegalArgumentException("invalid calibration observation");
        }
    }
    public record Proof(int schemaVersion, String origin, String sourceSha256, String candidateJarSha256,
                        String requestSha256, String runtimeDate, List<Observation> observations) {
        public Proof { observations = List.copyOf(observations); }
        @Override public String toString() { return "UsageCalibrationProof[origin=" + origin + ", samples=" + observations.size() + "]"; }
    }

    /** Test seam uses a script client. The CLI owns all real provider construction. Never retries. */
    static Observation measure(Request request, LlmClient client, Path privateTrace) throws IOException {
        int ordinal = MODELS.indexOf(client.getModelName());
        if (ordinal < 0 || !client.getProviderName().equals(ordinal == 0 ? "deepseek" : "glm"))
            throw new IOException("calibration cohort mismatch");
        var cap = ContextWindowCappedLlmClient.cap(client, 1_000_000, 16_384);
        var trace = new TracingLlmClient(cap, privateTrace);
        trace.chat(request.messages(), request.tools());
        var metrics = trace.metrics();
        if (BenchmarkProviderEvidenceGate.failureType(client.getModelName(),
                new BenchmarkProtocol.AgentLimits(1_000_000, 48, 8, 1_000_000, 16_384), metrics) != null)
            throw new IOException("calibration provider identity/usage/cap not proven");
        return new Observation(client.getProviderName(), client.getModelName(), metrics.resolvedModel(), requestDigest(request),
                Math.toIntExact(metrics.inputTokens()), Math.toIntExact(metrics.outputTokens()), Math.toIntExact(metrics.cachedInputTokens()),
                metrics.usageComplete(), metrics.effectiveContextWindowCapTokens(), metrics.maxOutputTokensPerCall(), hash(privateTrace));
    }
    static void validate(Proof proof, Request request) throws IOException {
        if (proof.schemaVersion() != 1 || !proof.origin().equals("REAL_PROVIDER_CALIBRATION")
                || !proof.sourceSha256().equals(request.sourceSha256()) || !proof.candidateJarSha256().equals(request.candidateJarSha256())
                || !proof.requestSha256().equals(requestDigest(request)) || !proof.runtimeDate().equals(request.runtimeDate())
                || proof.observations().size() != 2) throw new IOException("calibration missing, synthetic or stale");
        for (int n = 0; n < 2; n++) {
            var observed = proof.observations().get(n);
            if (!observed.provider().equals(n == 0 ? "deepseek" : "glm") || !observed.model().equals(MODELS.get(n))
                    || !observed.resolvedModel().equals(observed.model()) || !observed.requestSha256().equals(proof.requestSha256())
                    || !observed.usagePresent() || observed.inputTokens() < 60_000 || observed.inputTokens() > 100_000
                    || observed.contextCapTokens() != 1_000_000 || observed.maxOutputTokens() != 16_384
                    || observed.outputTokens() > 16_384 || observed.cachedInputTokens() > observed.inputTokens())
                throw new IOException("provider calibration outside frozen requirements");
        }
    }
    static Binding capture(FormalExecutionPlan plan, Path proofFile) throws IOException {
        if (proofFile == null) throw new IOException("E3 needs real provider calibration before credentials are loaded");
        Path proofPath = privateFile(proofFile), requestPath = privateFile(proofPath.resolveSibling("request.json"));
        var request = JSON.readValue(Files.readAllBytes(requestPath), Request.class);
        var proof = JSON.readValue(Files.readAllBytes(proofPath), Proof.class); validate(proof, request);
        var source = FrozenRemainingSource.capture(plan.requireCase("E3"));
        if (!request.sourceSha256().equals(source.dependency.sha256()) || !request.candidateJarSha256().equals(plan.artifacts().candidateJarSha256())
                || !request.runtimeDate().equals(plan.formalBatchContract().runtimeDate())
                || !plan.formalBatchContract().models().stream().map(m -> m.model()).toList().equals(MODELS))
            throw new IOException("calibration does not belong to the admitted candidate/source/cohort");
        // The same frozen six background deliveries must occur in the measured provider request.
        var users = request.messages().stream().filter(m -> m.role().equals("user")).map(LlmClient.Message::content).toList();
        var expected = new ArrayList<String>(); for (int i = 0; i < 8; i++) expected.add(source.oracle.path("turns").get(i).asText());
        if (!users.equals(expected)) throw new IOException("calibration does not represent the full E3 pre-final history");
        var artifacts = new LinkedHashMap<Path, String>(); artifacts.put(proofPath, hash(proofPath)); artifacts.put(requestPath, hash(requestPath));
        for (var observation : proof.observations()) {
            Path trace = privateFile(proofPath.resolveSibling(observation.provider() + ".trace.jsonl"));
            if (!hash(trace).equals(observation.traceSha256())) throw new IOException("calibration trace drift");
            var rows = Files.readAllLines(trace).stream().filter(line -> !line.isBlank()).toList();
            if (rows.size() != 1) throw new IOException("calibration must retain exactly one call per provider");
            var row = JSON.readTree(rows.get(0));
            if (!row.path("status").asText().equals("SUCCESS") || !row.path("provider").asText().equals(observation.provider())
                    || !row.path("model").asText().equals(observation.model()) || !row.path("resolvedModel").asText().equals(observation.resolvedModel()) || !row.path("usagePresent").asBoolean()
                    || row.path("inputTokens").asInt() != observation.inputTokens() || row.path("outputTokens").asInt() != observation.outputTokens()
                    || row.path("cachedInputTokens").asInt() != observation.cachedInputTokens()) throw new IOException("calibration usage differs from raw trace");
            artifacts.put(trace, observation.traceSha256());
        }
        return new Binding(artifacts);
    }
    static final class Binding {
        private final Map<Path, String> files;
        Binding(Map<Path, String> files) { this.files = Map.copyOf(files); }
        void verifyUnchanged() throws IOException {
            for (var file : files.entrySet()) if (!hash(privateFile(file.getKey())).equals(file.getValue())) throw new IOException("calibration changed after preparation");
        }
        void requireSeparateOutput(Path output) throws IOException {
            for (Path file : files.keySet()) if (output.startsWith(file.getParent()) || file.startsWith(output)) throw new IOException("output overlaps calibration evidence");
        }
    }
    static String requestDigest(Request request) throws IOException { return digest(JSON.writeValueAsBytes(request)); }
    static String hash(Path file) throws IOException { return digest(Files.readAllBytes(file)); }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static Path privateFile(Path path) throws IOException {
        if (!path.isAbsolute() || Files.isSymbolicLink(path) || !path.equals(path.toRealPath()) || !Files.isRegularFile(path)
                || Files.size(path) > 16 * 1048576 || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !List.of("rw-------", "r--------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))))
            throw new IOException("calibration evidence must be a private bounded unique file");
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent())
            if (Files.exists(parent.resolve(".git"))) throw new IOException("calibration must remain outside Git");
        if (!List.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent()))))
            throw new IOException("calibration parent must be private");
        return path;
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
