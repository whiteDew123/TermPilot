package com.termpilot.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPolicyTest {

    private final NetworkPolicy policy = new NetworkPolicy();

    @Test
    void rejectsBlankUrl() {
        assertNotNull(policy.checkUrl(null));
        assertNotNull(policy.checkUrl(""));
        assertNotNull(policy.checkUrl("   "));
    }

    @Test
    void rejectsFileScheme() {
        String reason = policy.checkUrl("file:///etc/passwd");
        assertNotNull(reason);
        assertTrue(reason.contains("scheme"));
    }

    @Test
    void rejectsFtpScheme() {
        String reason = policy.checkUrl("ftp://example.com/data");
        assertNotNull(reason);
        assertTrue(reason.contains("scheme"));
    }

    @Test
    void rejectsLocalhostByName() {
        assertNotNull(policy.checkUrl("http://localhost:8080/api"));
        assertNotNull(policy.checkUrl("https://my.localhost/secret"));
    }

    @Test
    void rejectsLoopbackByIp() {
        assertNotNull(policy.checkUrl("http://127.0.0.1/admin"));
    }

    @Test
    void rejectsAnyLocal() {
        assertNotNull(policy.checkUrl("http://0.0.0.0/"));
    }

    @Test
    void rejectsSiteLocal() {
        // 192.168.x.x 是 site-local
        assertNotNull(policy.checkUrl("http://192.168.0.1/"));
    }

    @Test
    void allowsPublicHttps() throws Exception {
        NetworkPolicy policy = new NetworkPolicy(60_000, 30,
                host -> List.of(InetAddress.getByName("8.8.8.8")), () -> 0);
        assertNull(policy.checkUrl("https://example.com/path"));
        assertNull(policy.checkUrl("https://example.com"));
    }

    @Test
    void rateLimitTriggersAfterMaxPerWindow() {
        NetworkPolicy bucket = new NetworkPolicy(60_000L, 3);
        assertNull(bucket.acquire());
        assertNull(bucket.acquire());
        assertNull(bucket.acquire());
        String denied = bucket.acquire();
        assertNotNull(denied);
        assertTrue(denied.contains("过于频繁"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://[fc00::1]/", "http://[fd12::1]/", "http://[::ffff:127.0.0.1]/",
            "http://[64:ff9b::a00:1]/", "http://[2002:a00:1::]/", "http://[ff02::1]/",
            "http://[2001::1]/", "http://224.0.0.1/", "http://100.64.0.1/", "http://0.1.2.3/",
            "http://255.255.255.255/", "http://198.18.0.1/", "http://user:secret@example.com/",
            "http://localhost./", "http://127.1/"})
    void rejectsSpecialAddressesAndCredentials(String url) {
        assertNotNull(policy.checkUrl(url), url);
    }

    @Test void rejectsMixedPublicAndPrivateDnsResults() {
        NetworkPolicy mixed = new NetworkPolicy(60_000, 30,
                host -> List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")), () -> 0);
        assertNotNull(mixed.checkUrl("https://mixed.test/"));
    }

    @Test void acceptsOrdinaryPublicIpv6() {
        assertNull(policy.checkUrl("https://[2606:4700:4700::1111]/"));
    }

    @Test void concurrentRequestsRespectLimitAtStartupAndWindowRollover() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        NetworkPolicy limited = new NetworkPolicy(60_000, 3, host -> List.of(), clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            for (int window = 0; window < 3; window++) {
                clock.set(window * 60_000L);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> requests = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    requests.add(executor.submit(() -> {
                        start.await();
                        return limited.acquire() == null;
                    }));
                }
                start.countDown();
                int allowed = 0;
                for (Future<Boolean> request : requests) if (request.get(3, TimeUnit.SECONDS)) allowed++;
                org.junit.jupiter.api.Assertions.assertEquals(3, allowed);
                assertNotNull(limited.acquire());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
