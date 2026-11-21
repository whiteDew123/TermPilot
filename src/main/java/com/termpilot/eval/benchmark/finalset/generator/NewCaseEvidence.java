package com.termpilot.eval.benchmark.finalset.generator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Synthetic reference envelope v10; callers must not present it as provider evidence. */
final class NewCaseEvidence {
    private NewCaseEvidence() { }
    static Map<String, Object> envelope(String id, String profile, String answer, String key, Object trace, int calls, int tools) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 10); result.put("caseId", id); result.put("repeat", 1);
        result.put("mode", "react"); result.put("toolProfile", profile); result.put("answer", answer);
        result.put("toolExecutions", List.of());
        var metrics = new LinkedHashMap<String, Object>();
        for (String count : List.of("calls", "successfulCalls", "inputTokens", "outputTokens")) metrics.put(count, calls);
        metrics.put("toolCalls", tools); metrics.put("elapsedMillis", 0); metrics.put("cachedInputTokens", 0);
        metrics.put("resolvedModel", "synthetic-reference"); metrics.put("resolvedModelConsistent", false);
        metrics.put("usageComplete", false); metrics.put("requestFingerprintComplete", false);
        metrics.put("systemPromptSha256", null); metrics.put("initialToolSchemaSha256", com.termpilot.eval.benchmark.relay.BenchmarkRelayProtocol.textSha256("synthetic-continuation-tools-v1"));
        result.put("llmMetrics", metrics);
        for (String scope : List.of("Workspace", "Bundle")) for (String field : List.of("TreeSha256", "FileCount", "TotalBytes"))
            result.put("verifier" + scope + field, null);
        result.put(key, trace);
        return result;
    }
}
