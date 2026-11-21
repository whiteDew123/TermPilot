package com.termpilot.web;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebFetcherTest {
    private MockWebServer server;

    @BeforeEach void setup() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach void shutdown() throws IOException {
        server.shutdown();
    }

    private String url(String path) {
        return server.url(path).newBuilder().host("web.test").build().toString();
    }

    // 仅测试允许回环连接；生产构造方法没有关闭地址检查的开关。
    private NetworkPolicy localPolicy(Dns dns) {
        return new NetworkPolicy(60_000L, 30, dns, System::currentTimeMillis) {
            @Override String checkAddress(InetAddress address) {
                return address.isLoopbackAddress() ? null : super.checkAddress(address);
            }
        };
    }

    private NetworkPolicy localPolicy() {
        return localPolicy(host -> host.equals("web.test")
                ? List.of(InetAddress.getByName("127.0.0.1")) : Dns.SYSTEM.lookup(host));
    }

    private WebFetcher fetcher(int maxBytes) {
        return new WebFetcher(maxBytes, new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(), localPolicy());
    }

    private MockResponse html(String body) {
        return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body);
    }

    @Test void fetchesSuccessfulHtml() throws IOException {
        server.enqueue(html("<html><body><article><h1>Hello</h1></article></body></html>"));
        var raw = fetcher(1024).fetch(url("/"));
        assertTrue(raw.body().contains("Hello"));
        assertFalse(raw.truncated());
        assertEquals("UTF-8", raw.charset());
    }

    @Test void productionFetcherRejectsLocalNetworkBeforeConnecting() {
        assertThrows(IOException.class, () -> new WebFetcher().fetch(server.url("/").toString()));
        assertEquals(0, server.getRequestCount());
    }

    @Test void validatesRedirectTargetBeforeConnecting() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/private"));
        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/")));
        assertTrue(error.getMessage().contains("站内地址"));
        assertEquals(1, server.getRequestCount());
    }

    @Test void checksAllRedirectAddressesIncludingPrivateAnswer() {
        NetworkPolicy policy = localPolicy(host -> host.equals("web.test")
                ? List.of(InetAddress.getByName("127.0.0.1"))
                : List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "http://other.test/private"));
        assertThrows(IOException.class, () -> new WebFetcher(1024, new OkHttpClient(), policy).fetch(url("/")));
        assertEquals(1, server.getRequestCount());
    }

    @Test void followsRelativeRedirectAndUsesFinalUrlForLinks() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/docs/page"));
        server.enqueue(html("<article><a href='next'>next</a></article>"));
        var page = fetcher(1024).fetchPage(url("/old"), 8000);
        assertEquals(url("/docs/page"), page.url());
        assertTrue(page.markdown().contains(url("/docs/next")));
        assertEquals(2, server.getRequestCount());
    }

    @Test void pinsCheckedDnsAnswerAndDoesNotUseProxy() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        NetworkPolicy policy = localPolicy(host -> List.of(InetAddress.getByName(
                lookups.incrementAndGet() == 1 ? "127.0.0.1" : "10.0.0.1")));
        try (MockWebServer proxy = new MockWebServer()) {
            proxy.start();
            OkHttpClient client = new OkHttpClient.Builder()
                    .dns(host -> { throw new AssertionError("连接不能再次使用原始 DNS"); })
                    .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy.getPort()))).build();
            server.enqueue(html("<p>checked IP</p>"));
            assertTrue(new WebFetcher(1024, client, policy).fetch(url("/")).body().contains("checked IP"));
            assertEquals(1, lookups.get());
            assertEquals(0, proxy.getRequestCount());
        }
    }

    @Test void rechecksDnsOnSameHostRedirect() {
        AtomicInteger lookups = new AtomicInteger();
        NetworkPolicy policy = localPolicy(host -> List.of(InetAddress.getByName(
                lookups.incrementAndGet() == 1 ? "127.0.0.1" : "10.0.0.1")));
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next"));
        assertThrows(IOException.class, () -> new WebFetcher(1024, new OkHttpClient(), policy).fetch(url("/")));
        assertEquals(2, lookups.get());
        assertEquals(1, server.getRequestCount());
    }

    @Test void stopsRedirectLoopsAndExcessiveHops() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/loop#fragment"));
        assertTrue(assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/loop"))).getMessage().contains("循环"));
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/hop" + (i + 1)));
        }
        assertTrue(assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/hop0"))).getMessage().contains("超过 5 次"));
        assertEquals(7, server.getRequestCount());
    }

    @Test void redirectConsumesSharedRateLimit() {
        NetworkPolicy policy = new NetworkPolicy(60_000, 1, host -> List.of(InetAddress.getByName("127.0.0.1")), System::currentTimeMillis) {
            @Override String checkAddress(InetAddress address) { return null; }
        };
        assertNull(policy.acquire()); // 首次配额由 ToolRegistry 申请。
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next"));
        assertTrue(assertThrows(IOException.class,
                () -> new WebFetcher(1024, new OkHttpClient(), policy).fetch(url("/"))).getMessage().contains("过于频繁"));
        assertEquals(1, server.getRequestCount());
    }

    @Test void doesNotReplay503() {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        server.enqueue(html("unexpected replay"));
        assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/")));
        assertEquals(1, server.getRequestCount());
    }

    @Test void preservesDownloadTruncationEvenWhenExtractedTextIsShort() throws IOException {
        server.enqueue(html("<body><p>keep</p><script>" + "x".repeat(2000) + "</script><p>lost</p></body>"));
        var page = fetcher(128).fetchPage(url("/"), 8000);
        assertTrue(page.truncated());
        assertTrue(page.hint().contains("全文长度未知"));
        assertTrue(page.markdown().contains("keep"));
        assertFalse(page.markdown().contains("lost"));
    }

    @Test void exactByteLimitIsNotTruncation() throws IOException {
        server.enqueue(html("a".repeat(128)));
        assertFalse(fetcher(128).fetch(url("/")).truncated());
        server.enqueue(html("a".repeat(129)));
        var raw = fetcher(128).fetch(url("/"));
        assertTrue(raw.truncated());
        assertEquals(128, raw.body().length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "video/mp4", "audio/mpeg", "application/zip", "application/octet-stream"})
    void refusesNonTextTypesWithoutWaitingForBody(String type) {
        server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody("binary")
                .setBodyDelay(2, TimeUnit.SECONDS));
        IOException error = assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/")));
        assertTrue(error.getMessage().contains("响应类型"));
    }

    @Test void refusesPdfEvenWhenTypeIsMissingOrWrong() {
        for (String type : List.of("", "text/html")) {
            server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody("%PDF-1.7 text"));
            assertTrue(assertThrows(IOException.class, () -> fetcher(1024).fetchPage(url("/"), 8000))
                    .getMessage().contains("二进制"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<meta charset='GBK'>", "<meta http-equiv='Content-Type' content='text/html; charset=GBK'>"})
    void respectsHtmlEncodingBeforeDecoding(String meta) throws IOException {
        byte[] bytes = ("<html><head>" + meta + "</head><body>中文正文</body></html>").getBytes(Charset.forName("GBK"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody(new Buffer().write(bytes)));
        assertTrue(fetcher(1024).fetchPage(url("/"), 8000).markdown().contains("中文正文"));
    }

    @Test void headerEncodingTakesPriorityOverMeta() throws IOException {
        server.enqueue(html("<meta charset='GBK'><body>中文正文</body>"));
        assertTrue(fetcher(1024).fetchPage(url("/"), 8000).markdown().contains("中文正文"));
    }

    @Test void detectsUtf16BomAndFallsBackFromInvalidMeta() throws IOException {
        byte[] bytes = "<html><body>中文正文</body></html>".getBytes(StandardCharsets.UTF_16);
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody(new Buffer().write(bytes)));
        assertTrue(fetcher(1024).fetchPage(url("/"), 8000).markdown().contains("中文正文"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<meta charset='invalid-charset'><body>中文正文</body>"));
        assertTrue(fetcher(1024).fetchPage(url("/"), 8000).markdown().contains("中文正文"));
    }

    @Test void preservesPlainTextAndMarkdownAngleBrackets() throws IOException {
        for (String type : List.of("text/plain", "text/markdown")) {
            server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody("List<String>\n\n# heading"));
            assertEquals("List<String>\n\n# heading", fetcher(1024).fetchPage(url("/"), 8000).markdown());
        }
    }

    @Test void httpErrorThrows() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertTrue(assertThrows(IOException.class, () -> fetcher(1024).fetch(url("/"))).getMessage().contains("404"));
    }

    @Test void timeoutCoversEntireRedirectChain() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/next")
                .setHeadersDelay(600, TimeUnit.MILLISECONDS));
        server.enqueue(html("ok").setHeadersDelay(600, TimeUnit.MILLISECONDS));
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build();
        assertThrows(IOException.class, () -> new WebFetcher(1024, client, localPolicy()).fetch(url("/")));
    }

    @Test void readTimeoutThrows() {
        server.enqueue(html("partial-").throttleBody(1, 5, TimeUnit.SECONDS));
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS).build();
        assertThrows(IOException.class, () -> new WebFetcher(1024, client, localPolicy()).fetch(url("/")));
    }
}
