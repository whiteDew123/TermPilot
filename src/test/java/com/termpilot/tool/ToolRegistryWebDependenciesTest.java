package com.termpilot.tool;

import com.termpilot.eval.benchmark.mock.D4WebMock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryWebDependenciesTest {
    @Test void downloadTruncationWarningReachesTheModelWithoutGrantingNewUrls() {
        var mock = new D4WebMock(new byte[32]);
        var registry = new EmbeddedRegistry();
        registry.installWebDependencies(mock.searchProvider(), new com.termpilot.web.WebFetcher() {
            @Override public RawResponse fetch(String url) {
                return new RawResponse("https://final.example/page", "<p>部分正文</p>", "text/html", "UTF-8", true);
            }
        }, mock.networkPolicy());
        var output = registry.executeToolOutput("web_fetch", "{\"url\":\"" + mock.definition().releaseUrl() + "\"}");
        assertTrue(output.text().contains("部分正文"));
        assertTrue(output.text().contains("已截断"));
        assertTrue(output.text().contains("全文长度未知"));
        assertTrue(output.text().contains("已提取正文"));
        assertTrue(output.discoveredUrls().isEmpty());
    }

    @Test void partialInstallHasNoEffectAndCompleteInstallCannotBeReplaced() {
        var mock = new D4WebMock(new byte[32]);
        var registry = new EmbeddedRegistry();
        assertThrows(NullPointerException.class, () -> registry.installWebDependencies(mock.searchProvider(), mock.fetcher(), null));
        registry.install(mock);
        var output = registry.executeToolOutput("web_search", "{\"query\":\"current SDK defaults\",\"top_k\":2}");
        assertTrue(output.successful()); assertEquals(2, output.discoveredUrls().size());
        assertThrows(IllegalStateException.class, () -> registry.install(mock));
        assertEquals(1, mock.audit().size());
    }

    @Test void preexistingProductionOrTestProviderCannotBeReplacedByOfflineInstall() {
        var mock = new D4WebMock(new byte[32]);
        var registry = new EmbeddedRegistry();
        registry.setSearchProvider(mock.searchProvider());
        assertThrows(IllegalStateException.class, () -> registry.install(mock));
        assertTrue(mock.audit().isEmpty());
    }

    private static final class EmbeddedRegistry extends ToolRegistry {
        void install(D4WebMock mock) { installWebDependencies(mock.searchProvider(), mock.fetcher(), mock.networkPolicy()); }
    }
}
