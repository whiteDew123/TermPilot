package com.termpilot.tool;

import java.nio.file.Path;
import java.util.List;

interface CodeSearchEngine {
    CodeSearchResult search(CodeSearchRequest request);
}

record CodeSearchRequest(
        String query,
        Path root,
        Path projectRoot,
        String glob,
        boolean regex,
        boolean caseSensitive,
        int contextLines,
        int maxResults,
        int headLimit,
        int offset,
        String mode
) {}

record CodeSearchResult(
        String engine,
        List<GrepMatch> matches,
        boolean partial,
        String partialReason
) {}

record ContextLine(int lineNumber, String text) {}

record GrepMatch(String file, int lineNumber, List<ContextLine> context, int count) {
    GrepMatch(String file, int lineNumber, List<ContextLine> context) {
        this(file, lineNumber, context, 0);
    }
}
