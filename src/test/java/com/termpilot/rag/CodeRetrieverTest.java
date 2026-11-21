package com.termpilot.rag;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRetrieverTest {

    private static final String TEST_PROJECT = "/tmp/termpilot-code-retriever";
    private VectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("termpilot.rag.dir", "/tmp/termpilot-test-rag-retriever");
        store = new VectorStore(TEST_PROJECT);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void hybridSearchBoostsCodeKeywordsFromNaturalLanguageQuery() throws Exception {
        CodeChunk getterChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Task.java",
                "Task.getId()",
                "public String getId() { return id; }",
                10, 12
        );
        CodeChunk agentChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Agent.java",
                "Agent.run(String userInput)",
                "ReAct 循环：读取用户输入，思考，必要时调用工具，再继续下一轮。",
                20, 40
        );

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(getterChunk, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(agentChunk, new float[]{0.80f, 0.20f})
        ));

        try (CodeRetriever retriever = new CodeRetriever(TEST_PROJECT, fixedQueryVector())) {
            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent的ReAct循环是怎么实现的", 5);

            assertFalse(results.isEmpty());
            assertEquals("Agent.run(String userInput)", results.get(0).name());
        }
    }

    @Test
    void strongerKeywordWinsRegardlessOfWordOrder() throws Exception {
        store.insertChunks(List.of(
                fileEntry("beta.md", "alpha beta", 0, 1),
                fileEntry("other.md", "other", 0.85f, (float) Math.sqrt(1 - 0.85 * 0.85))));

        try (CodeRetriever retriever = new CodeRetriever(TEST_PROJECT, fixedQueryVector())) {
            var forward = retriever.hybridSearch("alpha beta", 2);
            var reverse = retriever.hybridSearch("beta alpha", 2);
            assertEquals(reverse, forward);
            assertEquals("beta.md", forward.get(0).filePath());
            assertEquals(0.9, forward.get(0).similarity(), 1e-6);
        }
    }

    @Test
    void keywordOnlyMatchReceivesNoDualMatchBonus() throws Exception {
        var chunks = new ArrayList<VectorStore.CodeChunkEntry>();
        // topK=1 时语义路取前 10 条，这 10 条都排在 beta.md 前面。
        for (int i = 0; i < 10; i++) {
            chunks.add(fileEntry("other-" + i + ".md", "other", 0.2f, 1));
        }
        chunks.add(fileEntry("beta.md", "alpha beta", 0, 1));
        store.insertChunks(chunks);

        try (CodeRetriever retriever = new CodeRetriever(TEST_PROJECT, fixedQueryVector())) {
            var results = retriever.hybridSearch("beta alpha", 1);
            assertEquals("beta.md", results.get(0).filePath());
            assertEquals(0.8, results.get(0).similarity(), 1e-6);
        }
    }

    @Test
    void offlineServiceReturnsLocalHitsAndReportsKeywordMisses() throws Exception {
        String url;
        try (var server = new MockWebServer()) {
            server.start();
            url = server.url("/").toString();
        }
        var client = new EmbeddingClient("ollama", "test", url, "");
        store.insertChunks(List.of(fileEntry("login.md", "login", 0, 1), fileEntry("other.md", "other", 1, 0)));
        try (var retriever = new CodeRetriever(TEST_PROJECT, client)) {
            var hit = retriever.search("login", 5);
            assertTrue(hit.keywordOnly());
            assertEquals(1, hit.results().size());
            assertEquals("login.md:1-1", hit.results().get(0).location());
            assertEquals(0.8, hit.results().get(0).similarity(), 1e-6);
            var miss = retriever.search("absent", 5);
            assertTrue(miss.keywordOnly());
            assertTrue(miss.results().isEmpty());
            for (var output : List.of(SearchResultFormatter.formatForCli("login", hit),
                    SearchResultFormatter.formatForTool("login", hit))) {
                assertTrue(output.contains("仅使用本地关键词搜索"));
                assertTrue(output.contains("login.md:1-1"));
                assertFalse(output.contains("语义相似度"));
            }
            for (var output : List.of(SearchResultFormatter.formatForCli("absent", miss),
                    SearchResultFormatter.formatForTool("absent", miss))) {
                assertTrue(output.contains("仅使用本地关键词搜索"));
                assertTrue(output.contains("未找到"));
            }
        }
    }

    @Test
    void serviceRecoveryRestoresSemanticSearchAndAuthErrorsStayVisible() throws Exception {
        store.insertChunks(List.of(fileEntry("notes.md", "notes", 1, 0)));
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setBody("{\"embedding\":[1,0]}"));
            server.enqueue(new MockResponse().setResponseCode(401));
            var client = new EmbeddingClient("ollama", "test", server.url("/").toString(), "");
            try (var retriever = new CodeRetriever(TEST_PROJECT, client)) {
                assertTrue(retriever.search("unrelated", 5).keywordOnly());
                var recovered = retriever.search("unrelated", 5);
                assertFalse(recovered.keywordOnly());
                assertEquals("notes.md", recovered.results().get(0).filePath());
                assertTrue(assertThrows(IOException.class, () -> retriever.search("unrelated", 5))
                        .getMessage().contains("401"));
            }
        }
    }

    @Test
    void databaseErrorsDoNotTriggerKeywordFallback() throws Exception {
        var retriever = new CodeRetriever(TEST_PROJECT, fixedQueryVector());
        retriever.close();
        assertThrows(SQLException.class, () -> retriever.search("login", 5));
    }

    @Test
    void offlineSearchFavorsRareQueryTermsAndIsIndependentOfWordOrder() throws Exception {
        var chunks = new ArrayList<VectorStore.CodeChunkEntry>();
        for (int i = 0; i < 20; i++) chunks.add(fileEntry("common" + i, "common", 1, 0));
        chunks.add(fileEntry("caller", "rareGuard", 1, 0));
        store.insertChunks(chunks);
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(503));
            var client = new EmbeddingClient("ollama", "test", server.url("/").toString(), "");
            try (var retriever = new CodeRetriever(TEST_PROJECT, client)) {
                var forward = retriever.search("common rareGuard", 3);
                var reverse = retriever.search("rareGuard common", 3);
                assertTrue(forward.keywordOnly());
                assertEquals("caller", forward.results().get(0).filePath());
                assertEquals(forward, reverse);
            }
        }
    }

    private VectorStore.CodeChunkEntry fileEntry(String path, String text, float x, float y) {
        return new VectorStore.CodeChunkEntry(CodeChunk.fileChunk(path, text), new float[]{x, y});
    }

    private EmbeddingClient fixedQueryVector() {
        return new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };
    }
}
