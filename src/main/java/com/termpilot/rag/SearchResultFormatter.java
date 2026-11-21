package com.termpilot.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索结果展示格式化。
 *
 * 保持实现简单：不额外调用 LLM，只根据查询和 Top 结果生成简短摘要，
 * 让 /search 更像“可读搜索结果”，而不是只打印原始代码片段。
 */
public final class SearchResultFormatter {

    private SearchResultFormatter() {
    }

    public static String formatForCli(String query, List<VectorStore.SearchResult> results) {
        return formatForCli(query, new CodeRetriever.SearchOutcome(results, false));
    }

    public static String formatForCli(String query, CodeRetriever.SearchOutcome outcome) {
        return format(query, outcome, false);
    }

    public static String formatForTool(String query, List<VectorStore.SearchResult> results) {
        return formatForTool(query, new CodeRetriever.SearchOutcome(results, false));
    }

    public static String formatForTool(String query, CodeRetriever.SearchOutcome outcome) {
        return format(query, outcome, true);
    }

    private static String format(String query, CodeRetriever.SearchOutcome outcome, boolean forTool) {
        var results = outcome.results();
        StringBuilder sb = new StringBuilder();
        if (outcome.keywordOnly()) {
            sb.append("向量服务暂不可用，本次仅使用本地关键词搜索。\n");
        }
        if (results.isEmpty()) {
            return sb.append(forTool ? "未找到与查询相关的代码。" : "📭 未找到相关代码").toString();
        }

        if (forTool) {
            sb.append("检索摘要:\n");
        } else {
            sb.append("📋 找到 ").append(results.size()).append(" 个相关代码块:\n\n");
        }
        sb.append(buildSummary(query, results, outcome.keywordOnly())).append("\n\n");
        if (forTool) {
            sb.append("检索结果:\n");
        }
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);
            sb.append(String.format("%d. [%s:%s] (综合得分: %.3f) %s%n",
                    i + 1, result.chunkType(), result.name(), result.similarity(), result.location()));
            sb.append("   ").append(buildSnippet(result.content(), forTool ? 180 : 120).replace("\n", "\n   "))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String buildSummary(String query, List<VectorStore.SearchResult> results, boolean keywordOnly) {
        VectorStore.SearchResult top = results.get(0);
        Set<String> fileNames = results.stream()
                .map(result -> Paths.get(result.filePath()).getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> queryTokens = RagQueryTokenizer.tokenize(query).stream()
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String topFile = shortenPath(top.filePath());
        String relatedFiles = fileNames.stream().limit(3).collect(Collectors.joining("、"));
        String tokenText = queryTokens.isEmpty() ? "自然语言语义" : String.join("、", queryTokens);

        StringBuilder sb = new StringBuilder("搜索摘要:\n");
        sb.append("- 最相关的入口是 [")
                .append(top.chunkType())
                .append(":")
                .append(top.name())
                .append("]，位于 ")
                .append(topFile)
                .append("。\n");
        sb.append("- 当前结果主要集中在 ")
                .append(relatedFiles)
                .append(fileNames.size() > 3 ? " 等文件" : " 这些文件")
                .append("。\n");
        sb.append("- 这次排序综合参考了 ")
                .append(tokenText)
                .append(keywordOnly ? " 等关键词和代码块类型。" : " 等关键词与语义相似度。");
        if (top.startLine() > 0) {
            sb.append("可用 read_file 从第 ").append(top.startLine()).append(" 行读取第 1 条结果。");
        }
        return sb.toString();
    }

    private static String buildSnippet(String content, int maxChars) {
        String normalized = content == null ? "" : content.trim().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return "(无内容片段)";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private static String shortenPath(String filePath) {
        Path path = Paths.get(filePath);
        int count = path.getNameCount();
        if (count <= 3) {
            return filePath;
        }
        return path.subpath(count - 3, count).toString();
    }
}
