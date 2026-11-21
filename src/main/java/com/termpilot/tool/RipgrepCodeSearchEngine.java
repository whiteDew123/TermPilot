package com.termpilot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

final class RipgrepCodeSearchEngine implements CodeSearchEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final Set<String> excludedDirs;
    private final String executable;
    private final Duration timeout;

    RipgrepCodeSearchEngine(Set<String> excludedDirs) {
        this(excludedDirs, "rg", TIMEOUT);
    }

    RipgrepCodeSearchEngine(Set<String> excludedDirs, String executable, Duration timeout) {
        this.excludedDirs = excludedDirs;
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        if (Thread.currentThread().isInterrupted()) return failure("搜索已取消");
        if (Boolean.getBoolean("termpilot.search.disable.rg")) return fallback(request);
        Process process;
        try {
            process = new ProcessBuilder(command(request)).directory(request.projectRoot().toFile()).start();
        } catch (IOException unavailable) {
            return fallback(request);
        }
        ExecutorService readers = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "termpilot-rg-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> errors = readers.submit(() -> {
                try (var stream = process.getErrorStream()) {
                    String message = new String(stream.readNBytes(4096), StandardCharsets.UTF_8);
                    stream.transferTo(java.io.OutputStream.nullOutputStream());
                    return message.strip();
                }
            });
            Future<CodeSearchResult> search = readers.submit(() -> {
                GrepPage page = new GrepPage(request);
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    if (request.mode().equals("content")) readContent(reader, request.contextLines(), page);
                    else readFileRows(reader, request.mode(), page);
                }
                if (page.full()) return page.result("rg");
                int exit = process.waitFor();
                if (exit == 0 || exit == 1) return page.result("rg");
                String message = errors.get();
                if (message.contains("regex parse error") || message.contains("regex syntax error")) {
                    return failure("正则表达式无效: " + message);
                }
                return failure(message.isBlank() ? "rg 退出码 " + exit : message);
            });
            return search.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("搜索已取消");
        } catch (TimeoutException e) {
            return failure("rg 搜索超时 " + timeout.toMillis() + " 毫秒");
        } catch (ExecutionException e) {
            return failure("rg 输出读取失败: " + e.getCause().getMessage());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            readers.shutdownNow();
        }
    }

    private static CodeSearchResult failure(String reason) {
        return new CodeSearchResult("rg", List.of(), false, reason);
    }

    private void readFileRows(Reader reader, String mode, GrepPage page) throws IOException {
        // --null 把路径和计数隔开，支持路径中的冒号和换行。
        StringBuilder value = new StringBuilder();
        int ch;
        while (!page.full() && (ch = reader.read()) != -1) {
            if (ch != 0) {
                value.append((char) ch);
                continue;
            }
            String path = value.toString();
            value.setLength(0);
            int count = 1;
            if (mode.equals("count")) {
                while ((ch = reader.read()) != -1 && ch != '\n') value.append((char) ch);
                count = Integer.parseInt(value.toString().trim());
                value.setLength(0);
            }
            page.offer(new GrepMatch(path, 0, List.of(), count));
        }
    }

    private void readContent(BufferedReader reader, int radius, GrepPage page) throws IOException {
        Deque<ContextLine> previous = new ArrayDeque<>();
        Deque<GrepMatch> pending = new ArrayDeque<>();
        String line;
        while (!page.full() && (line = reader.readLine()) != null) {
            JsonNode event = MAPPER.readTree(line);
            String type = event.path("type").asText();
            if (type.equals("end")) {
                while (!pending.isEmpty() && !page.full()) page.offer(pending.removeFirst());
                previous.clear();
                continue;
            }
            if (!type.equals("match") && !type.equals("context")) continue;
            JsonNode data = event.path("data");
            ContextLine current = toContextLine(data);
            int number = current.lineNumber();
            while (!previous.isEmpty() && previous.getFirst().lineNumber() < number - radius) previous.removeFirst();
            for (GrepMatch match : pending) {
                if (number - match.lineNumber() <= radius) match.context().add(current);
            }
            if (type.equals("match")) {
                List<ContextLine> context = new ArrayList<>(previous);
                context.add(current);
                pending.add(new GrepMatch(pathText(data), number, context));
            }
            while (!pending.isEmpty() && number - pending.getFirst().lineNumber() >= radius && !page.full()) {
                page.offer(pending.removeFirst());
            }
            previous.addLast(current);
        }
    }

    private List<String> command(CodeSearchRequest request) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("--sort=path");
        switch (request.mode()) {
            case "count" -> cmd.add("--count");
            case "files_with_matches" -> cmd.add("--files-with-matches");
            default -> cmd.add("--json");
        }
        if (!request.mode().equals("content")) {
            cmd.add("--null");
            cmd.add("--with-filename");
        }
        cmd.add("--color=never");
        cmd.add("--line-number");
        cmd.add("--max-filesize");
        cmd.add("2M");
        if (!request.caseSensitive()) {
            cmd.add("-i");
        }
        if (!request.regex()) {
            cmd.add("--fixed-strings");
        }
        if (request.mode().equals("content") && request.contextLines() > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(request.contextLines()));
        }
        if (request.glob() != null && !request.glob().isBlank()) {
            cmd.add("--glob");
            cmd.add(request.glob());
        }
        // rg 按最后匹配的 glob 决定是否收录，目录排除规则要放在文件名过滤之后。
        for (String dir : excludedDirs) {
            cmd.add("--glob");
            cmd.add("!**/" + dir + "/**");
        }
        cmd.add("--glob");
        cmd.add("!**/.claude/worktrees/**");
        cmd.add("--");
        cmd.add(request.query());
        cmd.add(relativeRoot(request.root(), request.projectRoot()));
        return cmd;
    }

    private String relativeRoot(Path root, Path projectRoot) {
        if (root.equals(projectRoot)) {
            return ".";
        }
        return projectRoot.relativize(root).toString();
    }

    private ContextLine toContextLine(JsonNode data) {
        return new ContextLine(data.path("line_number").asInt(), data.path("lines").path("text").asText().stripTrailing());
    }

    private String pathText(JsonNode data) {
        return data.path("path").path("text").asText();
    }

    private CodeSearchResult fallback(CodeSearchRequest request) {
        return new JavaCodeSearchEngine(excludedDirs).search(request);
    }
}
