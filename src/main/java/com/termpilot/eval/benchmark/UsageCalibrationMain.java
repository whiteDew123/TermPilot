package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.termpilot.config.TermPilotConfig;
import com.termpilot.llm.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit preparation and approved real calibration are separate commands. Neither starts Docker. */
public final class UsageCalibrationMain {
    private UsageCalibrationMain() { }
    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception error) {
            System.err.println("Usage calibration rejected (" + error.getClass().getSimpleName() + "); no valid proof produced.");
            System.exit(2);
        }
    }
    private static void execute(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("prepare")) {
            Path source = Path.of(args[1]), candidate = Path.of(args[2]), output = Path.of(args[3]);
            String date = args[4];
            Path loaded = Path.of(UsageCalibrationMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate) || !candidate.equals(candidate.toRealPath())
                    || !candidate.equals(loaded.toRealPath())) throw new IOException("prepare must run from the exact candidate jar being calibrated");
            if (!output.isAbsolute() || !output.equals(output.normalize()) || Files.exists(output) || !output.getParent().equals(output.getParent().toRealPath()))
                throw new IOException("new canonical calibration output required");
            for (Path p = output.getParent(); p != null; p = p.getParent()) if (Files.exists(p.resolve(".git"))) throw new IOException("calibration must remain outside Git");
            Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path control = output.resolve("offline-native-control");
            Path evidence = RemainingRelayControlMain.run(source, "E3", control, "correct", date);
            var events = UsageCalibration.JSON.readTree(Files.readAllBytes(evidence)).path("context").path("providerRequests");
            com.fasterxml.jackson.databind.JsonNode sampled = null;
            for (var event : events) if (event.path("phase").asText().equals("turn") && event.path("turn").asInt() == 7) sampled = event;
            if (sampled == null) throw new IOException("calibration history unavailable");
            var messages = UsageCalibration.JSON.convertValue(sampled.path("messages"), new TypeReference<List<LlmClient.Message>>() {});
            var tools = UsageCalibration.JSON.convertValue(sampled.path("tools"), new TypeReference<List<LlmClient.Tool>>() {});
            var request = new UsageCalibration.Request(1, "E3", UsageCalibration.hash(source.resolve("validators/final/_private/oracles/E3.json")),
                    UsageCalibration.hash(candidate), date, messages, tools);
            RemainingRelayControlMain.write(output.resolve("request.json"), UsageCalibration.JSON.valueToTree(request));
            RemainingRelayControlMain.write(output.resolve("status.json"), UsageCalibration.JSON.valueToTree(Map.of(
                    "status", "PREPARED_NOT_CALIBRATED", "realProviderCalls", 0, "dockerCalls", 0, "publishable", false,
                    "requestSha256", UsageCalibration.requestDigest(request), "targetProviderInputTokens", List.of(60000,100000))));
            System.out.println("Prepared offline: " + output + "; realProviderCalls=0; not calibrated"); return;
        }
        if (args.length == 2 && args[0].equals("execute-approved")) {
            Path output = Path.of(args[1]).toRealPath();
            var request = UsageCalibration.JSON.readValue(Files.readAllBytes(UsageCalibration.privateFile(output.resolve("request.json"))), UsageCalibration.Request.class);
            for (String name : List.of("proof.json", "deepseek.trace.jsonl", "glm.trace.jsonl", "started.json"))
                if (Files.exists(output.resolve(name), LinkOption.NOFOLLOW_LINKS)) throw new IOException("calibration already attempted; no retry or replacement");
            var config = TermPilotConfig.load(); String deepseek = config.getApiKey("deepseek"), glm = config.getApiKey("glm");
            if (deepseek == null || deepseek.isBlank() || glm == null || glm.isBlank()) throw new IOException("both frozen providers require credentials");
            RemainingRelayControlMain.write(output.resolve("started.json"), UsageCalibration.JSON.valueToTree(Map.of("status", "REAL_CALIBRATION_STARTED", "publishable", false)));
            var observations = new ArrayList<UsageCalibration.Observation>();
            observations.add(UsageCalibration.measure(request, new DeepSeekClient(deepseek, UsageCalibration.MODELS.get(0),16384), output.resolve("deepseek.trace.jsonl")));
            observations.add(UsageCalibration.measure(request, new GLMClient(glm, UsageCalibration.MODELS.get(1),16384), output.resolve("glm.trace.jsonl")));
            var proof = new UsageCalibration.Proof(1, "REAL_PROVIDER_CALIBRATION", request.sourceSha256(), request.candidateJarSha256(),
                    UsageCalibration.requestDigest(request), request.runtimeDate(), observations);
            UsageCalibration.validate(proof, request);
            RemainingRelayControlMain.write(output.resolve("proof.json"), UsageCalibration.JSON.valueToTree(proof));
            System.out.println("Provider calibration saved: " + output.resolve("proof.json") + "; no Docker or formal score produced"); return;
        }
        throw new IllegalArgumentException("usage: prepare <private-source> <exact-candidate.jar> <new-private-output> <YYYY-MM-DD> | execute-approved <prepared-root>");
    }
}
