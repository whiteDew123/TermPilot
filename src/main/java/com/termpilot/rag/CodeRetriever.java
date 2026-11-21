package com.termpilot.rag;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码检索器：语义检索 + 图谱检索的统一入口
 */
public class CodeRetriever implements AutoCloseable {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索：用自然语言查询最相关的代码块
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索：同时进行语义检索和关键词检索，合并去重
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        return search(query, topK).results();
    }

    public record SearchOutcome(List<VectorStore.SearchResult> results, boolean keywordOnly) {}

    public SearchOutcome search(String query, int topK) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new HashMap<>();
        int semanticLimit = Math.max(topK * 2, 10);
        boolean keywordOnly = false;
        try {
            for (VectorStore.SearchResult result : semanticSearch(query, semanticLimit)) {
                keepHighestScore(merged, result);
            }
        } catch (EmbeddingClient.ServiceUnavailableException e) {
            keywordOnly = true;
        }

        Map<String, VectorStore.SearchResult> bestKeywords = keywordCandidates(query, keywordOnly);

        // 每个块最多从关键词一路进入一次，此处发生合并时一定是两路都命中。
        bestKeywords.forEach((key, keyword) -> merged.merge(key, keyword, (semantic, lexical) ->
                withScore(semantic, Math.max(semantic.similarity(), lexical.similarity()) + 0.1)));

        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double typeBoost = switch (r.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };
            ranked.add(typeBoost == 0.0 ? r : withScore(r, r.similarity() + typeBoost));
        }

        ranked.sort(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed()
                .thenComparing(VectorStore.SearchResult::filePath)
                .thenComparing(VectorStore.SearchResult::name));
        return new SearchOutcome(limitPerFile(ranked, topK, 2), keywordOnly);
    }

    private Map<String, VectorStore.SearchResult> keywordCandidates(String query, boolean keywordOnly) throws SQLException {
        Map<String, VectorStore.SearchResult> candidates = new HashMap<>();
        int chunks = keywordOnly ? vectorStore.getStats().chunkCount() : 0;
        double totalWeight = 0;
        for (String word : RagQueryTokenizer.tokenize(query).stream().sorted().toList()) {
            List<VectorStore.SearchResult> hits = keywordSearch(word);
            if (hits.isEmpty()) continue;
            // 在线沿用混合检索的最高关键词分。离线按词的稀有程度和命中位置综合计分。
            if (!keywordOnly) {
                for (var hit : hits) keepHighestScore(candidates, boostKeywordMatch(hit, word));
                continue;
            }
            double weight = Math.log1p((Math.max(chunks, hits.size()) - hits.size() + 0.5) / (hits.size() + 0.5));
            totalWeight += weight;
            Map<String, VectorStore.SearchResult> perWord = new HashMap<>();
            for (var hit : hits) keepHighestScore(perWord, boostKeywordMatch(hit, word));
            perWord.forEach((key, hit) -> candidates.merge(key, withScore(hit, hit.similarity() * weight),
                    (sum, next) -> withScore(sum, sum.similarity() + next.similarity())));
        }
        if (totalWeight > 0) {
            double denominator = totalWeight;
            candidates.replaceAll((key, sum) -> withScore(sum, sum.similarity() / denominator));
        }
        return candidates;
    }

    private void keepHighestScore(Map<String, VectorStore.SearchResult> results, VectorStore.SearchResult candidate) {
        String key = candidate.filePath() + "#" + candidate.name();
        results.merge(key, candidate, (previous, current) ->
                previous.similarity() >= current.similarity() ? previous : current);
    }

    private VectorStore.SearchResult withScore(VectorStore.SearchResult result, double score) {
        return new VectorStore.SearchResult(result.filePath(), result.chunkType(), result.name(), result.content(),
                score, result.startLine(), result.endLine());
    }

    private VectorStore.SearchResult boostKeywordMatch(VectorStore.SearchResult result, String keyword) {
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().toLowerCase();
        String contentLower = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        // 加分幅度控制在 0.1~0.5，确保关键词结果（base 0.3）最高到 ~0.8，不会压过语义结果（max 1.0）
        double bonus = 0.0;
        if (nameLower.contains(keywordLower)) {
            bonus += 0.3;  // 类名/方法名精确命中是最强信号
        }
        if (fileLower.contains(keywordLower)) {
            bonus += 0.1;
        }
        if (contentLower.contains(keywordLower)) {
            bonus += 0.1;
        }

        return withScore(result, result.similarity() + bonus);
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK
     */
    private List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索：查询指定类/方法的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
