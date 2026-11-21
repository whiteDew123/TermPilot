package com.termpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** The final Worker reply. Tool-call messages never pass through this parser. */
record TeamWorkerReport(String result, String blockedReason) {

    static TeamWorkerReport parse(String reply) throws IOException {
        JsonNode json = TeamStructuredReply.parseObject(reply);
        String result = textField(json, "result");
        String reason = textField(json, "reason");
        return switch (json.path("status").asText()) {
            case "completed" -> {
                if (result == null || result.isBlank() || reason != null && !reason.isBlank()) {
                    throw new IOException("completed 需要非空 result，不能同时报告阻塞原因");
                }
                yield new TeamWorkerReport(result, null);
            }
            case "blocked" -> {
                if (reason == null || reason.isBlank()) throw new IOException("blocked 需要说明 reason");
                yield new TeamWorkerReport(result, reason);
            }
            default -> throw new IOException("status 必须是 completed 或 blocked");
        };
    }

    private static String textField(JsonNode object, String name) throws IOException {
        JsonNode field = object.get(name);
        if (field == null) return null;
        if (!field.isTextual()) throw new IOException(name + " 必须是字符串");
        return field.textValue();
    }
}
