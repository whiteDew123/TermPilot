package com.termpilot.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cross-check the new private oracles against public inputs; no source can change during compilation. */
final class RemainingCaseSourceValidation {
    private RemainingCaseSourceValidation() { }

    static void validate(Path root, String id, JsonNode oracle, ObjectMapper json) throws IOException {
        boolean browser = id.equals("D5");
        var fields = new HashSet<>(Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId", "files", "expected"));
        if (!browser) fields.addAll(Set.of("turns", "compactBeforeTurn"));
        var actual = new HashSet<String>(); oracle.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(fields) && oracle.path("schemaVersion").isIntegralNumber() && oracle.path("schemaVersion").asInt() == 1
                && oracle.path("caseId").asText().equals(id) && oracle.path("expectedToolProfile").asText().equals(browser ? "MOCK_BROWSER" : "FILE_ONLY")
                && oracle.path("variantId").asText().matches("[0-9a-f]{24}")
                && oracle.path("profile").asText().equals(browser ? "d5-local-form-v1" : id.toLowerCase() + "-continuation-v1"), "source fields");
        Set<String> wantedFiles = browser ? Set.of("form.html") : Set.of("items.json", "archive.txt");
        Set<String> declaredFiles = new HashSet<>(); oracle.path("files").fieldNames().forEachRemaining(declaredFiles::add);
        require(oracle.path("files").isObject() && declaredFiles.equals(wantedFiles), "fixture declaration");
        Path fixture = root.resolve("fixtures/final/" + id);
        require(Files.isDirectory(fixture, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(fixture), "fixture root");
        try (var paths = Files.list(fixture)) {
            require(paths.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(wantedFiles), "fixture inventory");
        }
        for (String name : wantedFiles) {
            Path path = fixture.resolve(name);
            require(oracle.path("files").path(name).isTextual() && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path) && Files.size(path) <= 1048576
                    && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                    && Files.readString(path).equals(oracle.path("files").path(name).asText()), "fixture bytes");
        }
        JsonNode expected = oracle.path("expected"); var expectedFields = new HashSet<String>(); expected.fieldNames().forEachRemaining(expectedFields::add);
        require(expected.isObject() && expectedFields.equals(browser ? Set.of("customer", "quantity", "receiptId")
                : Set.of("project", "limit", "region", "amount", "pending")), "answer schema");
        String prompt = Files.readString(root.resolve("prompts/final/" + id + ".md"));
        if (browser) {
            require(expected.path("customer").asText().matches("customer_[0-9a-f]{8}")
                    && expected.path("quantity").isIntegralNumber() && expected.path("quantity").asInt() >= 2 && expected.path("quantity").asInt() <= 9
                    && expected.path("receiptId").asText().matches("R_[0-9a-f]{8}")
                    && prompt.contains(expected.path("customer").asText())
                    && prompt.contains("数量 " + expected.path("quantity").asInt())
                    && oracle.path("files").path("form.html").asText().contains("receiptId:'" + expected.path("receiptId").asText() + "'"), "browser source binding");
        } else {
            require(expected.path("project").asText().matches(id + "_[0-9a-f]{8}") && expected.path("pending").asText().matches("P_[0-9a-f]{8}")
                    && expected.path("limit").isIntegralNumber() && expected.path("limit").asInt() >= 4 && expected.path("limit").asInt() <= 19
                    && expected.path("amount").isIntegralNumber() && expected.path("amount").asInt() >= 31 && expected.path("amount").asInt() <= 83
                    && expected.path("region").asText().equals("cn"), "continuation values");
            JsonNode items = json.readTree(oracle.path("files").path("items.json").asText());
            for (String key : List.of("project", "amount", "pending")) require(expected.path(key).equals(items.path(key)), "tool state binding");
            var turns = oracle.path("turns");
            require(turns.isArray() && turns.size() == 9 && oracle.path("compactBeforeTurn").isIntegralNumber()
                    && oracle.path("compactBeforeTurn").asInt() == (id.equals("E4") ? 8 : -1), "turn driver");
            for (JsonNode turn : turns) require(turn.isTextual() && !turn.asText().isBlank(), "turn text");
            require(turns.get(0).asText().contains(expected.path("project").asText())
                    && turns.get(7).asText().contains("limit=" + expected.path("limit").asInt())
                    && turns.get(7).asText().contains("region=cn") && prompt.contains(ContinuationCaseMaterializer.prompt(id)), "frozen user input");
            JsonNode driver = json.readTree(root.resolve("provenance/final/" + id + "/turns.json").toFile());
            require(driver.path("turns").equals(turns) && driver.path("compactBeforeTurn").equals(oracle.path("compactBeforeTurn"))
                    && driver.path("delivery").asText().equals("SAME_AGENT_INSTANCE"), "saved turn driver");
        }
    }

    private static void require(boolean ok, String reason) throws IOException {
        if (!ok) throw new IOException("remaining case source mismatch: " + reason);
    }
}
