package com.termpilot.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One private headless process per episode, never a user's browser profile or CDP connection. */
final class HostBrowserProcess implements AutoCloseable {
    private final Process process;
    private final Reader input;
    private final Writer output;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "benchmark-browser-host-reader"); t.setDaemon(true); return t;
    });
    private final JsonNode identity;
    private final Path python, browser;
    private final String pythonSha, browserSha;
    private boolean closed;

    HostBrowserProcess(Path episode, String html) throws IOException {
        python = executable("termpilot.benchmark.browser.python"); browser = executable("termpilot.benchmark.browser.executable");
        pythonSha = hash(python); browserSha = hash(browser);
        Path privateRoot = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("host-browser"));
        Path script = privateRoot.resolve("browser_host.py");
        byte[] source;
        try (var resource = HostBrowserProcess.class.getResourceAsStream("/benchmark/browser_host.py")) {
            if (resource == null) throw new IOException("host browser resource missing"); source = resource.readAllBytes();
        }
        Files.write(script, source, StandardOpenOption.CREATE_NEW); Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String packageRoot = System.getProperty("termpilot.benchmark.browser.python-path");
        var command = new ArrayList<>(List.of(python.toString(), "-I", "-B"));
        if (packageRoot == null) command.add(script.toString());
        else {
            Path packages = Path.of(packageRoot);
            if (!packages.isAbsolute() || !packages.equals(packages.toRealPath()) || !Files.isDirectory(packages))
                throw new IOException("browser Python package path must be canonical");
            command.addAll(List.of("-c", "import sys,runpy;sys.path.insert(0,sys.argv[1]);runpy.run_path(sys.argv[2],run_name='__main__')", packages.toString(), script.toString()));
        }
        var builder = new ProcessBuilder(command);
        builder.directory(privateRoot.toFile()); builder.redirectError(privateRoot.resolve("stderr.txt").toFile());
        BenchmarkProcessEnvironment.sanitize(builder.environment(), privateRoot,
                BenchmarkProcessEnvironment.preparePrivateDirectory(privateRoot.resolve("tmp")));
        process = builder.start();
        input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        output = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        try {
            send(FrozenRemainingSource.JSON.valueToTree(Map.of("html", html, "browser", browser.toString())));
            identity = receive(Duration.ofSeconds(30));
            if (!identity.path("url").asText().matches("http://127\\.0\\.0\\.1:[0-9]{1,5}/form")
                    || !identity.path("engine").asText().equals("playwright.chromium") || !identity.path("version").isTextual())
                throw new IOException("host browser identity invalid");
        } catch (IOException error) { close(); throw error; }
    }
    JsonNode identity() { return identity.deepCopy(); }
    JsonNode execute(String operation, JsonNode arguments) throws IOException {
        verifyUnchanged(); send(FrozenRemainingSource.JSON.valueToTree(Map.of("operation", operation, "arguments", arguments)));
        var result = receive(Duration.ofSeconds(10));
        if (!result.path("successful").isBoolean() || !result.path("text").isTextual() || !result.path("state").isObject()
                || !result.path("networkRequests").isArray() || !result.path("deniedNetworkRequests").isArray())
            throw new IOException("host browser observation missing");
        return result;
    }
    void verifyUnchanged() throws IOException {
        if (closed || !process.isAlive() || !pythonSha.equals(hash(python)) || !browserSha.equals(hash(browser)))
            throw new IOException("host browser process/artifact changed");
    }
    Map<String, String> artifacts() { return Map.of("pythonSha256", pythonSha, "browserSha256", browserSha); }
    private void send(JsonNode value) throws IOException {
        output.write(value.toString()); output.write('\n'); output.flush();
    }
    private JsonNode receive(Duration timeout) throws IOException {
        Future<String> result = reader.submit(() -> {
            var line = new StringBuilder(); int ch;
            while ((ch = input.read()) >= 0 && ch != '\n') {
                if (line.length() >= 4 * 1048576) throw new IOException("browser output exceeds limit"); line.append((char) ch);
            }
            if (ch < 0) throw new IOException("browser closed before evidence"); return line.toString();
        });
        try { return FrozenRemainingSource.JSON.readTree(result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); close(); throw new IOException("browser interrupted", error); }
        catch (TimeoutException | ExecutionException error) { close(); throw new IOException("browser observation unavailable", error); }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        // Always reap browser descendants on early failure, timeout, cancellation or normal completion.
        var descendants = process.descendants().toList(); descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        reader.shutdownNow();
        try { input.close(); output.close(); } catch (IOException ignored) { }
    }
    private static Path executable(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null) throw new IOException("configure " + property + " before approved browser execution");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.normalize().equals(path) || Files.isSymbolicLink(path)
                || !path.equals(path.toRealPath()) || !Files.isRegularFile(path) || !Files.isExecutable(path))
            throw new IOException("browser host executable must be canonical");
        return path;
    }
    private static String hash(Path path) throws IOException {
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536]; int n;
            while ((n = stream.read(bytes)) >= 0) digest.update(bytes, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
