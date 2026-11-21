package com.termpilot.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class JavaCodeSearchEngine implements CodeSearchEngine {
    private static final long MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024;

    private final Set<String> excludedDirs;

    JavaCodeSearchEngine(Set<String> excludedDirs) {
        this.excludedDirs = excludedDirs;
    }

    @Override
    public CodeSearchResult search(CodeSearchRequest request) {
        Pattern contentPattern;
        try {
            int flags = request.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            contentPattern = Pattern.compile(request.regex() ? request.query() : Pattern.quote(request.query()), flags);
        } catch (PatternSyntaxException e) {
            return new CodeSearchResult("java", List.of(), false, "正则表达式无效: " + e.getMessage());
        }

        PathMatcher globMatcher = null;
        PathMatcher fileNameGlobMatcher = null;
        if (request.glob() != null && !request.glob().isBlank()) {
            globMatcher = request.projectRoot().getFileSystem().getPathMatcher("glob:" + normalizeGlob(request.glob()));
            fileNameGlobMatcher = request.projectRoot().getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(request.glob()));
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        List<Path> files = new ArrayList<>();
        PathMatcher finalGlobMatcher = globMatcher;
        PathMatcher finalFileNameGlobMatcher = fileNameGlobMatcher;
        GrepPage page = new GrepPage(request);
        try {
            Files.walkFileTree(request.root(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkDeadline(deadline);
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(request.projectRoot()) && (excludedDirs.contains(name)
                            || dir.endsWith(Path.of(".claude", "worktrees")))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    checkDeadline(deadline);
                    if (attrs.isRegularFile() && attrs.size() <= MAX_SEARCH_FILE_BYTES
                            && (finalGlobMatcher == null || finalGlobMatcher.matches(request.projectRoot().relativize(file))
                            || finalFileNameGlobMatcher.matches(file.getFileName()))) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                checkDeadline(deadline);
                if (page.full()) break;
                collectMatches(file, contentPattern, request, page, deadline);
            }
        } catch (IOException | CancellationException e) {
            return new CodeSearchResult("java", List.of(), false, e.getMessage());
        }
        return page.result("java");
    }

    private void collectMatches(Path file, Pattern pattern, CodeSearchRequest request, GrepPage page, long deadline) {
        try {
            try (var input = Files.newInputStream(file)) {
                for (byte b : input.readNBytes(4096)) if (b == 0) return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String path = request.projectRoot().relativize(file).toString();
            int count = 0;
            for (int i = 0; i < lines.size(); i++) {
                checkDeadline(deadline);
                CharSequence line = request.regex() ? new TimedLine(lines.get(i), deadline) : lines.get(i);
                if (!pattern.matcher(line).find()) continue;
                count++;
                if (request.mode().equals("files_with_matches")) break;
                if (!request.mode().equals("content")) continue;
                List<ContextLine> context = new ArrayList<>();
                int from = Math.max(0, i - request.contextLines());
                int to = Math.min(lines.size() - 1, i + request.contextLines());
                for (int j = from; j <= to; j++) context.add(new ContextLine(j + 1, lines.get(j)));
                if (!page.offer(new GrepMatch(path, i + 1, context))) return;
            }
            if (count > 0 && !request.mode().equals("content")) {
                page.offer(new GrepMatch(path, 0, List.of(), count));
            }
        } catch (IOException ignored) {
            // 跳过无法解码或在搜索期间已移除的文件。
        }
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("搜索已取消");
        if (System.nanoTime() - deadline >= 0) throw new CancellationException("Java 搜索超时 8 秒");
    }

    // 正则引擎可能长时间回溯同一行，在读取字符时也检查取消和超时。
    private record TimedLine(String text, long deadline) implements CharSequence {
        public int length() { return text.length(); }
        public char charAt(int index) {
            checkDeadline(deadline);
            return text.charAt(index);
        }
        public CharSequence subSequence(int start, int end) {
            return new TimedLine(text.substring(start, end), deadline);
        }
        public String toString() { return text; }
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
