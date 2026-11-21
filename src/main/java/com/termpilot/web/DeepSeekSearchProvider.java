package com.termpilot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** DeepSeek 独立的网页搜索与抓取接口，共用对话的 API Key。 */
public final class DeepSeekSearchProvider implements SearchProvider, PageFetcher {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final String ENDPOINT = "https://api.deepseek.com/v1/tools/";

    private final String apiKey;
    private final OkHttpClient httpClient;

    public DeepSeekSearchProvider(String apiKey) {
        this(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build(), apiKey);
    }

    /** 独立搜索接口不再需要模型名，保留此构造方法以兼容已有调用方。 */
    @Deprecated
    public DeepSeekSearchProvider(String apiKey, String model) {
        this(apiKey);
    }

    DeepSeekSearchProvider(OkHttpClient httpClient, String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "请在 .env 中设置 DEEPSEEK_API_KEY，或通过 SEARCH_PROVIDER 选择其他搜索服务。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 128) {
            throw new IOException("DeepSeek 搜索关键词需为 1 到 128 个字符");
        }
        int limit = topK > 0 ? Math.min(topK, 50) : 10;
        JsonNode items = post("search", MAPPER.createObjectNode()
                .put("query", query).put("count", limit)).path("results");
        if (!items.isArray()) {
            throw new IOException("DeepSeek 搜索响应缺少 results 数组");
        }
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            String url = item.path("url").asText("").trim();
            if (isHttpUrlWithoutCredentials(url) && seen.add(url)) {
                results.add(SearchResult.of(results.size() + 1, item.path("title").asText(""),
                        url, item.path("snippet").asText("")));
                if (results.size() == limit) {
                    break;
                }
            }
        }
        return results;
    }

    @Override
    public FetchResult fetchPage(String url, int maxChars) throws IOException {
        if (url == null || url.codePointCount(0, url.length()) > 8192 || !isHttpUrlWithoutCredentials(url)) {
            throw new IOException("DeepSeek 抓取需要不含账号密码、最多 8192 字符的 HTTP(S) 网址");
        }
        JsonNode page = post("fetch", MAPPER.createObjectNode().put("url", url));
        if (!page.path("title").isTextual() || !page.path("content").isTextual()) {
            throw new IOException("DeepSeek 抓取结果缺少标题或 Markdown 正文字段");
        }
        return FetchResult.fromMarkdown(url, page.get("title").textValue(), page.get("content").textValue(), maxChars);
    }

    private static boolean isHttpUrlWithoutCredentials(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode post(String operation, ObjectNode payload) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        Request request = new Request.Builder()
                .url(ENDPOINT + operation)
                .header("Authorization", "Bearer " + apiKey)
                .post(oneShotBody(payload.toString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String hint = switch (response.code()) {
                    case 401, 403 -> "，请检查 DEEPSEEK_API_KEY 及账号权限";
                    case 402 -> "，请检查账户余额";
                    case 429 -> "，已触发限流";
                    default -> "";
                };
                throw new IOException("DeepSeek " + operation + " 请求失败 (HTTP " + response.code() + ")" + hint);
            }
            if (response.body() == null) {
                throw new IOException("DeepSeek 返回空响应");
            }
            byte[] bytes = response.body().byteStream().readNBytes(WebFetcher.DEFAULT_MAX_BYTES + 1);
            if (bytes.length > WebFetcher.DEFAULT_MAX_BYTES) {
                throw new IOException("DeepSeek 响应超过 5 MB");
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(bytes);
            } catch (IOException e) {
                throw new IOException("DeepSeek 返回了无效 JSON");
            }
            if (root == null || !root.isObject()) {
                throw new IOException("DeepSeek 响应缺少 JSON 对象");
            }
            return root;
        }
    }

    private static RequestBody oneShotBody(String json) {
        RequestBody delegate = RequestBody.create(json, JSON_MEDIA);
        // retryOnConnectionFailure=false 仍不足以阻止 503 + Retry-After: 0 的跟进请求。
        // 标记为一次性请求体，防止 HTTP 客户端重放计费请求。
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return delegate.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return delegate.contentLength();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                delegate.writeTo(sink);
            }

            @Override
            public boolean isOneShot() {
                return true;
            }
        };
    }
}
