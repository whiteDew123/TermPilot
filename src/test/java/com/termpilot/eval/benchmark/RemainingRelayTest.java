package com.termpilot.eval.benchmark;

import com.termpilot.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.termpilot.eval.benchmark.scoring.*;
import com.termpilot.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180) @ResourceLock(Resources.SYSTEM_PROPERTIES)
class RemainingRelayTest {
    @TempDir Path temp;
    @BeforeEach void privateRoot() throws Exception { temp = temp.toRealPath(); Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------")); }
    @AfterEach void thaw() throws Exception { try (var paths = Files.walk(temp)) { for (Path p : paths.toList()) if (!Files.isSymbolicLink(p))
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------")); } }
    Path source() throws Exception { return new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(temp.resolve("source"), "0123456789abcdef".repeat(4))).sourceRoot(); }
    @Test void sameNativeAgentNineHostDeliveriesAndCompactionAreIndependentlyReplayable() throws Exception {
        Path source = source();
        for (String id : List.of("E3", "E4")) for (String scenario : List.of("correct", "wrong")) {
            Path episode = temp.resolve(id + scenario);
            Path evidence = RemainingRelayControlMain.run(source, id, episode, scenario, "2026-10-10");
            assertScore(source, id, episode, evidence, scenario.equals("correct") ? 100 : 0);
            var trace = FrozenRemainingSource.JSON.readTree(Files.readAllBytes(evidence));
            assertEquals(9, trace.path("context").path("requests").size());
            assertTrue(trace.path("llmMetrics").path("requestFingerprintComplete").asBoolean(),
                    "native summary is separately bound to the same frozen host session");
            assertEquals(id.equals("E4") ? 1 : 0, trace.path("context").path("summaryRequests").size());
            assertTrue(Files.readString(episode.resolve("control-status.json")).contains("\"realProviderCalls\":0"));
            if (id.equals("E4") && scenario.equals("correct")) {
                var forged = trace.deepCopy();
                var system = (com.fasterxml.jackson.databind.node.ObjectNode) forged.path("context")
                        .path("providerRequests").get(1).path("messages").get(0);
                system.put("content", "changed task system prompt");
                Path tampered = episode.resolve("tampered-system.json");
                Files.write(tampered, FrozenRemainingSource.JSON.writeValueAsBytes(forged));
                var verifier = new ProcessBuilder(source.resolve("validators/final/E4").toString(),
                        episode.resolve("workspace").toString(), tampered.toString())
                        .redirectOutput(episode.resolve("tampered-score.json").toFile())
                        .redirectError(episode.resolve("tampered-error.txt").toFile()).start();
                try {
                    assertTrue(verifier.waitFor(30, TimeUnit.SECONDS));
                    assertEquals(2, verifier.exitValue(), "system prompt drift must invalidate evidence");
                } finally { if (verifier.isAlive()) verifier.destroyForcibly(); }
            }
        }
    }
    @Test void frozenBindingRejectsPromptDriftAndSourceReplacement() throws Exception {
        Path source = source(); var plan = RemainingRelayControlMain.captureDraftCase(source,"E3",temp.resolve("frozen"));
        var binding = FormalContinuationBinding.capture(plan); binding.verifyUnchanged();
        assertThrows(java.io.IOException.class, binding.newSession()::evidence);
        var changed = new com.termpilot.eval.benchmark.formal.FormalExecutionPlan.CasePlan(plan.ordinal(),plan.contract(),plan.scoringContract(),plan.scoringContractFile(),
                plan.scoringContractSha256(), plan.prompt()+"changed", BenchmarkRelayProtocol.textSha256(plan.prompt()+"changed"), plan.fixture(),plan.verifier());
        assertThrows(java.io.IOException.class, () -> FormalContinuationBinding.capture(changed));
        Path oracle = temp.resolve("frozen/validators/final/_private/oracles/E3.json");
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("rw-------")); Files.writeString(oracle,"{}"); Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("r--------"));
        assertThrows(java.io.IOException.class, binding::verifyUnchanged);
    }
    @Test @EnabledIfSystemProperty(named="termpilot.test.browser.offline", matches="true")
    void freshHeadlessBrowserThroughNativeMcpRelayAcceptsCorrectAndScoresDuplicateAndDestructiveZero() throws Exception {
        Path source = source();
        for (String scenario : List.of("correct", "duplicate", "destructive", "external")) {
            Path episode = temp.resolve("D5-" + scenario); Path evidence = RemainingRelayControlMain.run(source,"D5",episode,scenario,"2026-10-10");
            assertScore(source,"D5",episode,evidence,scenario.equals("correct") ? 100 : 0);
        }
    }
    private void assertScore(Path source, String id, Path episode, Path evidence, int expected) throws Exception {
        var process = new ProcessBuilder(source.resolve("validators/final/"+id).toString(), episode.resolve("workspace").toString(),evidence.toString())
                .redirectOutput(episode.resolve("score.json").toFile()).redirectError(episode.resolve("score-error.txt").toFile()).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),Files.readString(episode.resolve("score-error.txt")));
            assertEquals(expected,ScoreCalculator.calculate(ScoringContract.load(source.resolve("validators/final/_private/scoring-contracts/"+id+".json")),
                    VerifierScoringReport.load(episode.resolve("score.json")),ScoreCalculator.JudgeAvailability.UNAVAILABLE).score());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
