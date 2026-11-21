package com.termpilot.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.termpilot.eval.benchmark.scoring.ScoreCalculator;
import com.termpilot.eval.benchmark.scoring.ScoringContract;
import com.termpilot.eval.benchmark.scoring.VerifierScoringReport;
import com.termpilot.memory.TokenBudget;
import com.termpilot.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real verifier processes; synthetic references, incorrect behavior and broken evidence stay distinct. */
@Timeout(120)
class RemainingCasesVerifierTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void sameSeedReproducesEveryByteAndSiblingSeedsChangeEachCase() throws Exception {
        for (String id : List.of("D5", "E3", "E4")) {
            Path a = source(id, "a", 1), b = source(id, "b", 1), c = source(id, "c", 2);
            for (Path file : regularFiles(a)) {
                String relative = a.relativize(file).toString();
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(b.resolve(relative)), relative);
            }
            assertNotEquals(Files.readString(oracle(a, id)), Files.readString(oracle(c, id)), id);
            assertEquals(100, score(a, id, evidence(a, id)).score());
            assertEquals(100, score(c, id, evidence(c, id)).score());
            if (id.equals("E3")) {
                var turns = JSON.readTree(oracle(a, id).toFile()).path("turns");
                var history = new java.util.ArrayList<LlmClient.Message>();
                for (var turn : turns) history.add(LlmClient.Message.user(turn.asText()));
                int estimate = TokenBudget.estimateMessagesTokens(history);
                assertTrue(estimate >= 60_000 && estimate <= 100_000, "local workload estimate=" + estimate);
            }
        }
    }

    @Test void browserDuplicateSubmissionIsValidZeroAndTamperedStateIsInvalid() throws Exception {
        Path root = source("D5", "browser", 3);
        ObjectNode original = evidence(root, "D5");
        assertEquals(100, score(root, "D5", original).score());
        ObjectNode duplicate = original.deepCopy();
        ArrayNode events = (ArrayNode) duplicate.path("browser").path("events");
        ObjectNode click = ((ObjectNode) events.get(4)).deepCopy();
        click.put("sequence", 6);
        ((ArrayNode) click.path("state").path("records")).add(click.path("state").path("records").get(0).deepCopy());
        events.insert(5, click);
        ((ObjectNode) events.get(6)).put("sequence", 7).set("state", click.path("state").deepCopy());
        ((ObjectNode) duplicate.path("browser")).set("finalState", click.path("state").deepCopy());
        ((ObjectNode) duplicate.path("llmMetrics")).put("toolCalls", 7);
        assertEquals(0, score(root, "D5", duplicate).score());
        ObjectNode forged = original.deepCopy();
        ((ObjectNode) forged.path("browser").path("events").get(2).path("state")).put("customer", "forged");
        assertEquals(2, verdict(root, "D5", forged).exit());
        ObjectNode outside = original.deepCopy();
        ((ArrayNode) outside.path("browser").path("networkRequests")).add("https://example.invalid/export");
        assertEquals(0, score(root, "D5", outside).score());
        ObjectNode noSnapshot = original.deepCopy();
        ArrayNode without = (ArrayNode) noSnapshot.path("browser").path("events");
        without.remove(1);
        for (int i = 0; i < without.size(); i++) ((ObjectNode) without.get(i)).put("sequence", i + 1);
        ((ObjectNode) noSnapshot.path("llmMetrics")).put("toolCalls", without.size());
        assertEquals(0, score(root, "D5", noSnapshot).score());
    }

    @Test void oldConstraintsAreValidFailureButMissingHistoryIsInvalid() throws Exception {
        Path root = source("E3", "long", 4);
        ObjectNode original = evidence(root, "E3");
        ObjectNode stale = original.deepCopy();
        ObjectNode answer = (ObjectNode) JSON.readTree(stale.path("answer").asText());
        answer.put("limit", 99).put("region", "old");
        String body = answer.toString();
        stale.put("answer", body);
        ArrayNode requests = (ArrayNode) stale.path("context").path("requests");
        ((ObjectNode) requests.get(requests.size() - 1)).put("response", body);
        ArrayNode tools = (ArrayNode) stale.path("context").path("tools");
        ((ObjectNode) tools.get(1)).put("content", body); ((ObjectNode) tools.get(2)).put("result", body);
        Files.writeString(root.resolve("references/final/E3/workspace/result.json"), body);
        assertEquals(0, score(root, "E3", stale).score());
        ObjectNode truncated = original.deepCopy();
        ((ArrayNode) truncated.path("context").path("requests")).remove(3);
        assertEquals(2, verdict(root, "E3", truncated).exit());
        ObjectNode orphan = original.deepCopy();
        var first = (ArrayNode) orphan.path("context").path("requests").get(0).path("messages");
        ((ObjectNode) first.get(first.size() - 1)).put("toolCallId", "missing-id");
        assertEquals(2, verdict(root, "E3", orphan).exit());
    }

    @Test void compactionRequiresSummaryCallsAppendOnlyHistoryAndExactCheckpoint() throws Exception {
        Path root = source("E4", "compact", 5);
        ObjectNode original = evidence(root, "E4");
        assertEquals(100, score(root, "E4", original).score());
        for (String missing : List.of("summaryRequests", "beforeView", "afterView")) {
            ObjectNode bad = original.deepCopy(); ((ObjectNode) bad.path("context")).set(missing, JSON.createArrayNode());
            assertEquals(2, verdict(root, "E4", bad).exit(), missing);
        }
        ObjectNode rewritten = original.deepCopy();
        ((ObjectNode) rewritten.path("context")).put("ledgerAfter", "rewritten history");
        assertEquals(2, verdict(root, "E4", rewritten).exit());
        ObjectNode noCheckpoint = original.deepCopy();
        ((ObjectNode) noCheckpoint.path("context")).put("ledgerAfter", original.path("context").path("ledgerBefore").asText() + "{}\n");
        assertEquals(2, verdict(root, "E4", noCheckpoint).exit());
        Files.writeString(root.resolve("references/final/E4/workspace/archive.txt"), "changed");
        assertEquals(0, score(root, "E4", original).score());
    }

    @Test void sourceBindingDuplicateJsonAndVerifierDriftCannotYieldScores() throws Exception {
        for (String id : List.of("D5", "E3", "E4")) {
            Path root = source(id, "binding", 6);
            ObjectNode original = evidence(root, id), bad = original.deepCopy();
            ((ObjectNode) bad.path(id.equals("D5") ? "browser" : "context")).put("sourceSha256", "0".repeat(64));
            assertEquals(2, verdict(root, id, bad).exit());
            Path input = temp.resolve(id + "-duplicates.json");
            Files.writeString(input, original.toString().replaceFirst("\\{", "{\"caseId\":\"" + id + "\","));
            assertEquals(2, run(root, id, input).exit());
            Files.writeString(root.resolve("validators/final/_private/scoring_report.py"), "# drift\n", java.nio.file.StandardOpenOption.APPEND);
            assertEquals(2, verdict(root, id, original).exit());
        }
    }

    @Test void caseCompilationRejectsOracleFixtureAndTurnDriverDrift() throws Exception {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        Path root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                temp.toRealPath().resolve("compiled-source"), "0123456789abcdef".repeat(4))).sourceRoot();
        for (String id : List.of("D5", "E3", "E4")) {
            assertEquals(id, FinalCaseContractCompiler.compile(root, id).id());
            Path oracleFile = oracle(root, id); byte[] original = Files.readAllBytes(oracleFile);
            ObjectNode bad = (ObjectNode) JSON.readTree(original); bad.put("unregistered", true);
            Files.writeString(oracleFile, bad.toString());
            assertThrows(java.io.IOException.class, () -> FinalCaseContractCompiler.compile(root, id));
            Files.write(oracleFile, original);
            Path extra = root.resolve("fixtures/final/" + id + "/extra.txt"); Files.writeString(extra, "x");
            assertThrows(java.io.IOException.class, () -> FinalCaseContractCompiler.compile(root, id));
            Files.delete(extra);
        }
        Path driver = root.resolve("provenance/final/E4/turns.json");
        ObjectNode bad = (ObjectNode) JSON.readTree(driver.toFile()); bad.put("compactBeforeTurn", -1);
        Files.writeString(driver, bad.toString());
        assertThrows(java.io.IOException.class, () -> FinalCaseContractCompiler.compile(root, "E4"));
    }

    private Path source(String id, String tag, int seed) throws Exception {
        Path root = Files.createDirectory(temp.resolve(id + "-" + tag),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        var writer = new PrivateSourceWriter(root);
        var variant = SeededVariant.derive(new byte[]{(byte) seed}, id, "1");
        writer.text("prompts/final/" + id + ".md", id.equals("D5") ? D5CaseMaterializer.prompt(variant) : ContinuationCaseMaterializer.prompt(id));
        writer.executable("validators/final/" + id, id.equals("D5") ? D5CaseMaterializer.wrapper() : ContinuationCaseMaterializer.wrapper(id));
        if (id.equals("D5")) D5CaseMaterializer.materialize(writer, variant);
        else ContinuationCaseMaterializer.materialize(writer, variant);
        var paths = id.equals("D5") ? D5CaseMaterializer.RUNTIME_PATHS : ContinuationCaseMaterializer.RUNTIME_PATHS;
        String wrapperSha = FinalCaseContractCompiler.sha256(root.resolve("validators/final/" + id));
        String runtimeSha = ImplementedCaseMaterializers.digestFiles(root, paths);
        writer.json("validators/final/_private/scoring-contracts/" + id + ".json", id.equals("D5")
                ? D5CaseMaterializer.scoring(wrapperSha, runtimeSha) : ContinuationCaseMaterializer.scoring(id, wrapperSha, runtimeSha));
        return root;
    }
    private Path oracle(Path root, String id) { return root.resolve("validators/final/_private/oracles/" + id + ".json"); }
    private ObjectNode evidence(Path root, String id) throws Exception { return (ObjectNode) JSON.readTree(root.resolve("references/final/" + id + "/evidence.json").toFile()); }
    private List<Path> regularFiles(Path root) throws Exception { try (var paths = Files.walk(root)) { return paths.filter(Files::isRegularFile).toList(); } }
    private ScoreCalculator.Result score(Path root, String id, ObjectNode evidence) throws Exception {
        var result = verdict(root, id, evidence); assertEquals(0, result.exit(), result.output());
        return ScoreCalculator.calculate(ScoringContract.load(root.resolve("validators/final/_private/scoring-contracts/" + id + ".json")),
                VerifierScoringReport.parse(result.output()), ScoreCalculator.JudgeAvailability.AVAILABLE);
    }
    private Result verdict(Path root, String id, ObjectNode evidence) throws Exception {
        Path input = Files.createTempFile(temp, id, ".json"); Files.writeString(input, evidence.toString()); return run(root, id, input);
    }
    private Result run(Path root, String id, Path input) throws Exception {
        Path output = Files.createTempFile(temp, id, ".out");
        var process = new ProcessBuilder(root.resolve("validators/final/" + id).toString(),
                root.resolve("references/final/" + id + "/workspace").toString(), input.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); return new Result(process.exitValue(), Files.readString(output)); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private record Result(int exit, String output) { }
}
