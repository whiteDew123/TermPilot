package com.termpilot.web;

import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本机抓取：逐跳检查并绑定 IP，限时限量读取，再识别编码和提取正文。 */
public class WebFetcher implements PageFetcher {
    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> TEXT_TYPES = Set.of("text/html", "application/xhtml+xml",
            "text/plain", "text/markdown", "text/x-markdown", "application/json", "application/xml", "text/xml");

    private final OkHttpClient httpClient;
    private final NetworkPolicy policy;
    private final int maxBytes;

    public WebFetcher() {
        this(DEFAULT_MAX_BYTES);
    }

    public WebFetcher(int maxBytes) {
        this(maxBytes, defaultClient(), new NetworkPolicy());
    }

    /** 与 ToolRegistry 共用限流器；首次请求配额由 ToolRegistry 申请，跳转在此追加计数。 */
    public WebFetcher(NetworkPolicy policy) {
        this(DEFAULT_MAX_BYTES, defaultClient(), policy);
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    WebFetcher(int maxBytes, OkHttpClient httpClient, NetworkPolicy policy) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes 必须大于 0 且能容纳额外的截断探测字节");
        }
        this.maxBytes = maxBytes;
        this.policy = policy;
        this.httpClient = httpClient.newBuilder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                // 代理可能重新解析目标域名，绕过下面绑定的 IP；本机安全抓取只走直连。
                .proxy(Proxy.NO_PROXY).build();
    }

    @Override
    public FetchResult fetchPage(String url, int maxChars) throws IOException {
        RawResponse raw = fetch(url);
        boolean html = raw.contentType().isBlank() || mediaType(raw.contentType()).equals("text/html")
                || mediaType(raw.contentType()).equals("application/xhtml+xml");
        HtmlExtractor.Extracted page = html
                ? new HtmlExtractor().extract(raw.body(), raw.url())
                : new HtmlExtractor.Extracted("", raw.body());
        return FetchResult.fromMarkdown(raw.url(), page.title(), page.markdown(), maxChars, raw.truncated());
    }

    public RawResponse fetch(String url) throws IOException {
        long timeoutMillis = httpClient.callTimeoutMillis() > 0
                ? httpClient.callTimeoutMillis() : TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_SECONDS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Set<String> visited = new HashSet<>();
        ConnectionPool pool = new ConnectionPool();
        String current = url;
        try {
            for (int redirects = 0; ; redirects++) {
                NetworkPolicy.ResolvedTarget target = policy.resolve(current);
                HttpUrl targetUrl = target.url().newBuilder().fragment(null).build();
                if (!visited.add(targetUrl.toString())) throw new IOException("网页重定向循环");
                if (redirects > 0) {
                    String denial = policy.acquire();
                    if (denial != null) throw new IOException(denial);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("网页抓取超时（含重定向）");
                // 同域跳转也可能得到新的 DNS 答案，不能复用上一跳连接而绕过本次绑定。
                pool.evictAll();
                AtomicBoolean sent = new AtomicBoolean();
                OkHttpClient client = httpClient.newBuilder().connectionPool(pool)
                        .dns(host -> {
                            if (!host.equals(target.url().host())) throw new UnknownHostException("未检查的主机");
                            return target.addresses();
                        })
                        .callTimeout(remaining, TimeUnit.NANOSECONDS)
                        // GET 也可能因 503 + Retry-After: 0 被重发；禁止隐式跟进请求。
                        .addNetworkInterceptor(chain -> {
                            if (!sent.compareAndSet(false, true)) throw new IOException("不自动重试网页请求");
                            return chain.proceed(chain.request());
                        }).build();
                Request request = new Request.Builder().url(targetUrl)
                        .header("Accept", "text/html,application/xhtml+xml,text/plain,text/markdown;q=0.9")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("User-Agent", "Mozilla/5.0 (compatible; termpilot-web-fetch/1.0)")
                        .get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (Set.of(301, 302, 303, 307, 308).contains(response.code())) {
                        if (redirects >= MAX_REDIRECTS) throw new IOException("网页重定向超过 5 次");
                        String location = response.header("Location");
                        HttpUrl next = location == null ? null : targetUrl.resolve(location);
                        if (next == null) throw new IOException("网页重定向地址无效");
                        current = next.toString();
                        continue;
                    }
                    if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + response.message());
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("响应体为空");
                    String contentType = response.header("Content-Type", "");
                    if (!contentType.isBlank() && !TEXT_TYPES.contains(mediaType(contentType))) {
                        throw new IOException("不支持的网页响应类型: " + mediaType(contentType));
                    }
                    byte[] bytes = readBounded(body.byteStream());
                    boolean truncated = bytes.length > maxBytes;
                    if (truncated) bytes = Arrays.copyOf(bytes, maxBytes);
                    rejectBinary(bytes);
                    Charset charset = resolveCharset(body, bytes);
                    String text = new String(bytes, charset);
                    if (text.startsWith("\uFEFF")) text = text.substring(1);
                    return new RawResponse(targetUrl.toString(), text, contentType, charset.name(), truncated);
                }
            }
        } finally {
            pool.evictAll();
        }
    }

    private static String mediaType(String contentType) {
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private Charset resolveCharset(ResponseBody body, byte[] bytes) throws IOException {
        if (body.contentType() != null && body.contentType().charset() != null) {
            return body.contentType().charset();
        }
        // 在解码之前让 Jsoup 从 BOM、meta charset 或 http-equiv 中识别编码，失败用 UTF-8。
        return Jsoup.parse(new ByteArrayInputStream(bytes, 0, Math.min(bytes.length, 8192)), null, "").charset();
    }

    private static void rejectBinary(byte[] bytes) throws IOException {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 32), StandardCharsets.ISO_8859_1);
        if (prefix.startsWith("%PDF-") || prefix.startsWith("PK\u0003\u0004")
                || prefix.startsWith("\u0089PNG") || prefix.startsWith("GIF8")
                || prefix.startsWith("\u00ff\u00d8\u00ff") || prefix.startsWith("\u001f\u008b")
                || prefix.startsWith("RIFF") || prefix.startsWith("OggS")) {
            throw new IOException("响应是二进制文件，不能作为网页正文读取");
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = maxBytes + 1;
        while (remaining > 0) {
            int n = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n == -1) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    public record RawResponse(String url, String body, String contentType, String charset, boolean truncated) {}
}
