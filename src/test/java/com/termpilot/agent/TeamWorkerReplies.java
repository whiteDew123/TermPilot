package com.termpilot.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Model wire reply for a successful Worker in offline Team tests. */
public final class TeamWorkerReplies {
    private TeamWorkerReplies() { }

    public static String completed(String result) {
        return JsonNodeFactory.instance.objectNode().put("status", "completed").put("result", result).toString();
    }
}
