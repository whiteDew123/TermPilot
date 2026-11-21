package com.termpilot.web;

import okhttp3.Dns;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** HTTP(S) 地址检查，以及每个固定时间窗口内的抓取限流。 */
public class NetworkPolicy {
    private final long windowMillis;
    private final int maxPerWindow;
    private final Dns dns;
    private final LongSupplier clock;
    private long windowStart;
    private int counter;
    private boolean started;

    public NetworkPolicy() {
        this(60_000L, 30);
    }

    NetworkPolicy(long windowMillis, int maxPerWindow) {
        this(windowMillis, maxPerWindow, Dns.SYSTEM, () -> System.nanoTime() / 1_000_000L);
    }

    NetworkPolicy(long windowMillis, int maxPerWindow, Dns dns, LongSupplier clock) {
        if (windowMillis <= 0 || maxPerWindow <= 0) {
            throw new IllegalArgumentException("限流窗口和次数必须大于 0");
        }
        this.windowMillis = windowMillis;
        this.maxPerWindow = maxPerWindow;
        this.dns = dns;
        this.clock = clock;
    }

    /** 返回 null 表示通过，否则返回拒绝原因；本方法不消耗请求配额。 */
    public String checkUrl(String url) {
        try {
            resolve(url);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** 使用与 HTTP 客户端相同的地址解析规则，并把所有已检查 IP 交给连接层。 */
    ResolvedTarget resolve(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("URL 不能为空");
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null) throw new IOException("URL 格式非法或禁止的 scheme（仅允许 http、https）");
        if (!url.username().isEmpty() || !url.password().isEmpty()
                || url.uri().getRawUserInfo() != null) {
            throw new IOException("URL 不得包含账号密码");
        }
        String host = url.host();
        String lower = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw new IOException("禁止访问 localhost");
        }
        List<InetAddress> addresses;
        try {
            addresses = List.copyOf(dns.lookup(host));
        } catch (IOException e) {
            throw new IOException("无法解析主机: " + host, e);
        }
        if (addresses.isEmpty()) throw new IOException("无法解析主机: " + host);
        for (InetAddress address : addresses) {
            String reason = checkAddress(address);
            if (reason != null) throw new IOException(reason + "（" + address.getHostAddress() + "）");
        }
        return new ResolvedTarget(url, addresses);
    }

    String checkAddress(InetAddress address) {
        if (address.isLoopbackAddress()) return "禁止访问环回地址";
        if (address.isAnyLocalAddress()) return "禁止访问未指定地址";
        if (address.isLinkLocalAddress()) return "禁止访问链路本地地址";
        if (address.isSiteLocalAddress()) return "禁止访问站内地址";
        if (address.isMulticastAddress()) return "禁止访问组播地址";
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        if (bytes.length == 4) {
            int second = bytes[1] & 0xff;
            if (first == 0 || first >= 240 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && bytes[2] == 0)
                    || (first == 198 && (second == 18 || second == 19))) {
                return "禁止访问特殊用途地址";
            }
        } else if (bytes.length == 16) {
            // 仅接收全球单播范围；排除 ULA、NAT64 等可能映射到内网的特殊前缀。
            if ((first & 0xe0) != 0x20) return "禁止访问非公网 IPv6 地址";
            // Teredo、其他 2001::/23 特殊用途范围及 6to4，避免嵌入 IPv4 绕过检查。
            if ((first == 0x20 && bytes[1] == 1 && (bytes[2] & 0xfe) == 0)
                    || (first == 0x20 && bytes[1] == 2)) {
                return "禁止访问 IPv6 特殊用途地址";
            }
        }
        return null;
    }

    /** 检查窗口和更新计数必须在同一把锁内，防止并行抓取在窗口切换时多放行。 */
    public synchronized String acquire() {
        long now = clock.getAsLong();
        if (!started || now - windowStart >= windowMillis) {
            started = true;
            windowStart = now;
            counter = 0;
        }
        if (counter >= maxPerWindow) {
            long resetIn = (windowMillis - (now - windowStart)) / 1000L;
            return String.format("请求过于频繁（%d 秒内已达 %d 次上限），约 %d 秒后重试",
                    windowMillis / 1000L, maxPerWindow, Math.max(resetIn, 1L));
        }
        counter++;
        return null;
    }

    record ResolvedTarget(HttpUrl url, List<InetAddress> addresses) {}
}
