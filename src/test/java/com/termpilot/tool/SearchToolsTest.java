package com.termpilot.tool;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SearchToolsTest {
    @TempDir Path dir;
    private ToolRegistry tools;
    private String previous;

    @BeforeEach void setup() {
        previous = System.getProperty("termpilot.search.disable.rg");
        tools = new ToolRegistry();
        tools.setProjectPath(dir.toString());
    }

    @AfterEach void restore() {
        if (previous == null) System.clearProperty("termpilot.search.disable.rg");
        else System.setProperty("termpilot.search.disable.rg", previous);
    }

    @Test void readWindowStopsBeforeMalformedTailAndLimitsLongLines() throws Exception {
        Path file = dir.resolve("log");
        String longLine = "😀".repeat(2010);
        Files.writeString(file, longLine + "\r\nskip\rthird\nfourth\n" + "x".repeat(100_000));
        Files.write(file, new byte[]{(byte) 0xff}, StandardOpenOption.APPEND);
        String first = tools.executeTool("read_file", "{\"path\":\"log\",\"limit\":1}");
        assertTrue(first.contains("😀".repeat(2000) + TextFileReader.LINE_TRUNCATED));
        assertTrue(first.contains("offset=2"));
        String third = tools.executeTool("read_file", "{\"path\":\"log\",\"offset\":3,\"limit\":1}");
        assertTrue(third.contains("3 | third"));
        Files.writeString(file, longLine + "\r\nend");
        assertEquals("文件内容:\n" + "😀".repeat(2000) + TextFileReader.LINE_TRUNCATED + "\r\nend",
                tools.executeTool("read_file", "{\"path\":\"log\"}"));
        assertTrue(tools.executeTool("read_file", "{\"path\":\"log\",\"offset\":3}").contains("共 2 行"));
    }

    @Test void javaPagesAllModes() throws Exception {
        System.setProperty("termpilot.search.disable.rg", "true");
        checkPages("java");
    }

    @Test void rgPagesAllModes() throws Exception {
        requireRg();
        System.setProperty("termpilot.search.disable.rg", "false");
        checkPages("rg");
    }

    private void checkPages(String engine) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hit hit\nhit\nhit\n");
        Files.writeString(dir.resolve("b.txt"), "hit\n");
        Files.writeString(dir.resolve("c.txt"), "hit\nhit");
        String one = page("content", 0);
        assertTrue(one.contains("engine=" + engine), one);
        assertTrue(one.contains("a.txt:1") && one.contains("next_offset: 1"), one);
        assertTrue(page("content", 1).contains("a.txt:2"));
        String three = page("content", 2);
        assertTrue(three.contains("a.txt:3") && three.contains("b.txt:1") && three.contains("next_offset: 4"), three);
        assertTrue(page("content", 4).contains("c.txt:1"));
        String last = page("content", 5);
        assertTrue(last.contains("c.txt:2") && !last.contains("next_offset"));
        for (String mode : List.of("files_with_matches", "count")) {
            String first = page(mode, 0);
            assertTrue(first.contains("a.txt") && first.contains("b.txt") && first.contains("next_offset: 2"), first);
            assertFalse(first.contains("suggested_reads"));
            String tail = page(mode, 2);
            assertTrue(tail.contains("c.txt") && !tail.contains("next_offset"), tail);
        }
        assertTrue(page("count", 0).contains("a.txt: 3"));
        Files.writeString(dir.resolve("long.txt"), "hit " + "x".repeat(2500) + "\nhit last");
        String longArgs = "{\"path\":\"long.txt\",\"pattern\":\"hit\",\"max_chars\":1000,\"offset\":%d}";
        String clipped = tools.executeTool("grep_code", longArgs.formatted(0));
        assertTrue(clipped.contains("long.txt:1") && clipped.contains("next_offset: 1"), clipped);
        String next = tools.executeTool("grep_code", longArgs.formatted(1));
        assertTrue(next.contains("long.txt:2") && !next.contains("next_offset"), next);
        assertTrue(tools.executeTool("grep_code", "{\"pattern\":\"(\",\"regex\":true}").contains("正则表达式无效"));
        assertTrue(tools.executeTool("grep_code", "{\"pattern\":\"absent\"}").contains("未找到匹配内容"));
    }

    private String page(String mode, int offset) {
        return tools.executeTool("grep_code", """
                {"pattern":"hit","output_mode":"%s","max_results":2,"head_limit":1,"offset":%d}
                """.formatted(mode, offset));
    }

    private void requireRg() throws Exception {
        try {
            Assumptions.assumeTrue(new ProcessBuilder("rg", "--version").start().waitFor() == 0);
        } catch (java.io.IOException e) {
            Assumptions.abort("ripgrep 未安装");
        }
    }

    @Test void cancellationAndTimeoutStopTheChildWithoutFallingBack() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        System.setProperty("termpilot.search.disable.rg", "false");
        Path fake = dir.resolve("fake-rg");
        Files.writeString(fake, "#!/bin/sh\necho $$ > rg.pid\nexec sleep 60\n");
        assertTrue(fake.toFile().setExecutable(true));
        var request = new CodeSearchRequest("fake", dir, dir, null, false, true, 0, 5, 5, 0, "content");
        var engine = new RipgrepCodeSearchEngine(Set.of(), fake.toString(), Duration.ofSeconds(5));
        var result = new AtomicReference<CodeSearchResult>();
        var interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(engine.search(request));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Path pidFile = dir.resolve("rg.pid");
        for (int i = 0; i < 200 && !Files.exists(pidFile); i++) Thread.sleep(10);
        assertTrue(Files.exists(pidFile));
        worker.interrupt();
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertTrue(interrupted.get());
        assertEquals("搜索已取消", result.get().partialReason());
        awaitExit(pidFile);
        var slow = new RipgrepCodeSearchEngine(Set.of(), fake.toString(), Duration.ofMillis(350));
        assertTrue(slow.search(request).partialReason().contains("超时"));
        awaitExit(pidFile);
        var missing = new RipgrepCodeSearchEngine(Set.of(), dir.resolve("missing").toString(), Duration.ofSeconds(1));
        assertEquals("java", missing.search(request).engine());
    }

    private void awaitExit(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle.of(pid).ifPresent(process -> process.onExit().orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join());
        assertTrue(ProcessHandle.of(pid).isEmpty());
    }
}
