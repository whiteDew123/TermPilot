package com.termpilot.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 逐行读取 UTF-8，超长行只保留前缀，跳过的行不进入结果。 */
final class TextFileReader {
    private static final int MAX_LINE_CHARS = 2_000;
    static final String LINE_TRUNCATED = "…（单行已截断）";

    static String read(Path file, boolean ranged, int offset, int limit) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder body = new StringBuilder();
            long row = 1;
            int ch = reader.read();
            while (ch != -1) {
                boolean keep = !ranged || row >= offset;
                if (keep && ranged) {
                    body.append(String.format("%5d | ", row));
                }
                int chars = 0;
                while (ch != -1 && ch != '\n' && ch != '\r') {
                    if (keep) {
                        if (!Character.isLowSurrogate((char) ch)) {
                            chars = Math.min(chars + 1, MAX_LINE_CHARS + 1);
                        }
                        if (chars <= MAX_LINE_CHARS) {
                            body.append((char) ch);
                        }
                    }
                    ch = reader.read();
                }
                if (chars > MAX_LINE_CHARS) {
                    body.append(LINE_TRUNCATED);
                }
                int separator = ch;
                if (separator != -1) {
                    ch = reader.read();
                    if (!ranged) {
                        body.append((char) separator);
                    }
                    if (separator == '\r' && ch == '\n') {
                        if (!ranged) body.append('\n');
                        ch = reader.read();
                    }
                }
                if (ranged && keep) {
                    body.append('\n');
                    if (row - offset + 1 == limit || ch == -1) {
                        String header = "文件内容: " + file.getFileName() + " (lines " + offset + "-" + row
                                + (ch == -1 ? " of " + row : "") + ")\n";
                        if (ch != -1) body.append("...(已截断，可用 offset=").append(row + 1).append(" 继续读取)");
                        return (header + body).trim();
                    }
                }
                row++;
            }
            return ranged ? "文件内容: " + file.getFileName() + " 共 " + (row - 1) + " 行，offset 超出范围"
                    : "文件内容:\n" + body;
        }
    }
}
