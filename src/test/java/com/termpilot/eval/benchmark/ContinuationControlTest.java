package com.termpilot.eval.benchmark;

import com.termpilot.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.termpilot.eval.benchmark.scoring.ScoreCalculator;
import com.termpilot.eval.benchmark.scoring.ScoringContract;
import com.termpilot.eval.benchmark.scoring.VerifierScoringReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(120)
class ContinuationControlTest {
    @TempDir Path temp;

    @Test void nativeAgentAndNativeCompactionProduceReplayablePositiveAndNegativeControls() throws Exception {
        Path parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Path source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "0123456789abcdef".repeat(4))).sourceRoot();
        for (String id : List.of("E3", "E4")) for (boolean wrong : List.of(false, true)) {
            Path episode = parent.resolve(id + (wrong ? "-wrong" : "-correct"));
            Path evidence = ContinuationControlMain.run(source, id, episode, wrong);
            Path stdout = episode.resolve("verifier.json"), stderr = episode.resolve("verifier-error.txt");
            var verifier = new ProcessBuilder(source.resolve("validators/final/" + id).toString(),
                    episode.resolve("workspace").toString(), evidence.toString()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertTrue(verifier.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, verifier.exitValue(), id + ": " + Files.readString(stderr));
                var score = ScoreCalculator.calculate(ScoringContract.load(source.resolve("validators/final/_private/scoring-contracts/" + id + ".json")),
                        VerifierScoringReport.load(stdout), ScoreCalculator.JudgeAvailability.AVAILABLE);
                assertEquals(wrong ? 0 : 100, score.score(), id);
                assertTrue(Files.readString(episode.resolve("control-status.json")).contains("\"realProviderCalls\":0"));
            } finally { if (verifier.isAlive()) verifier.destroyForcibly(); }
        }
    }
}
