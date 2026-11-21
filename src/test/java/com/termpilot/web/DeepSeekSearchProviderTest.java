package com.termpilot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekSearchProviderTest {
    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    assertEquals("https", chain.request().url().scheme());
                    assertEquals("api.deepseek.com", chain.request().url().host());
                    return chain.proceed(chain.request().newBuilder()
                            .url(server.url(chain.request().url().encodedPath())).build());
                }).build();
    }

    @AfterEach
    void shutdown() throws IOException {
        server.shutdown();
    }

    private DeepSeekSearchProvider provider() {
        return new DeepSeekSearchProvider(client, "test-key");
    }

    @Test
    void sendsNativeSearchRequestAndParsesOnlyStructuredUrls() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"results":[
                  {"url":"file:///tmp/invalid"},
                  {"url":"https://example.com/article/1","title":"示例站点","snippet":"见 https://untrusted.example"},
                  {"url":"https://example.com/article/1"},
                  {"url":"https://example.com/2"},
                  {"url":"https://example.com/3"}
                ]}
                """));
        var results = provider().search("Java 教程", 2);
        assertEquals(2, results.size());
        assertEquals(SearchResult.of(1, "示例站点", "https://example.com/article/1", "见 https://untrusted.example"), results.get(0));
        assertEquals(SearchResult.of(2, "", "https://example.com/2", ""), results.get(1));
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/tools/search", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertEquals(2, body.size());
        assertEquals("Java 教程", body.path("query").asText());
        assertEquals(2, body.path("count").intValue());
    }

    @Test
    void acceptsEmptyResultsAndCountsUnicodeCodePoints() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"results\":[]}"));
        assertTrue(provider().search("🌐".repeat(128), 100).isEmpty());
        var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertEquals(50, body.get("count").intValue());
        assertThrows(IOException.class, () -> provider().search("🌐".repeat(129), 1));
        assertEquals(1, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "{}", "{\"results\":{}}", "{\"text\":\"https://example.com\"}"})
    void rejectsMissingOrMalformedSearchResults(String response) {
        server.enqueue(new MockResponse().setBody(response));
        assertThrows(IOException.class, () -> provider().search("test", 5));
    }

    @Test
    void fetchPreservesMarkdownAndAppliesCharacterLimit() throws Exception {
        String url = "https://example.com/page?a=1&b=2";
        String markdown = "## 标题\n\n`<tag>`";
        ObjectMapper mapper = new ObjectMapper();
        server.enqueue(new MockResponse().setBody(mapper.createObjectNode()
                .put("title", "示例").put("content", markdown).toString()));
        FetchResult page = provider().fetchPage(url, markdown.length() - 1);
        assertEquals(url, page.url());
        assertEquals("示例", page.title());
        assertEquals(markdown.substring(0, markdown.length() - 1), page.markdown());
        assertEquals(markdown.length(), page.contentLength());
        assertTrue(page.truncated());
        var request = server.takeRequest();
        assertEquals("/v1/tools/fetch", request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        assertEquals(mapper.createObjectNode().put("url", url), mapper.readTree(request.getBody().readUtf8()));
    }

    @Test
    void distinguishesEmptyContentFromMalformedAndOversizedResponses() throws Exception {
        String url = "https://example.com/";
        server.enqueue(new MockResponse().setBody("{\"title\":\"\",\"content\":\"\"}"));
        assertTrue(provider().fetchPage(url, 8000).bodyEmpty());
        server.enqueue(new MockResponse().setBody("{\"title\":\"page\",\"content\":{}}"));
        assertThrows(IOException.class, () -> provider().fetchPage(url, 8000));
        server.enqueue(new MockResponse().setBody(" ".repeat(WebFetcher.DEFAULT_MAX_BYTES + 1)));
        assertTrue(assertThrows(IOException.class, () -> provider().fetchPage(url, 8000))
                .getMessage().contains("5 MB"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:password@example.com", "file:///tmp/secret", "https:///path"})
    void rejectsInvalidFetchUrlBeforeSending(String url) {
        assertThrows(IOException.class, () -> provider().fetchPage(url, 5));
        assertEquals(0, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 408, 429, 500, 503})
    void reportsHttpErrorsWithoutLeakingBodyOrRetrying(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("test-key"));
        server.enqueue(new MockResponse().setBody("""
                {"results":[]}
                """));
        IOException error = assertThrows(IOException.class, () -> provider().search("test", 5));
        assertTrue(error.getMessage().contains(Integer.toString(status)));
        assertFalse(error.getMessage().contains("test-key"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void refusesRedirects() throws Exception {
        try (MockWebServer destination = new MockWebServer()) {
            destination.start();
            server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", destination.url("/steal")));
            IOException error = assertThrows(IOException.class, () -> provider().search("test", 5));
            assertTrue(error.getMessage().contains("307"));
            assertEquals(0, destination.getRequestCount());
        }
    }

    @Test
    void rejectsMissingKeyAndBlankQueryBeforeMakingRequest() {
        assertFalse(new DeepSeekSearchProvider(null).isReady());
        assertFalse(new DeepSeekSearchProvider("  ").isReady());
        assertTrue(provider().isReady());
        assertEquals("deepseek", provider().name());
        IOException error = assertThrows(IOException.class,
                () -> new DeepSeekSearchProvider(client, "").search("test", 5));
        assertTrue(error.getMessage().contains("DEEPSEEK_API_KEY"));
        assertThrows(IOException.class, () -> provider().search(null, 5));
        assertThrows(IOException.class, () -> provider().search("  ", 5));
        assertEquals(0, server.getRequestCount());
    }
}
