package com.termpilot.tool;

import java.util.ArrayList;
import java.util.List;

/** 两个引擎均按路径、行号遍历。offset 按当前输出模式中的行计数。 */
final class GrepPage {
    private final CodeSearchRequest request;
    private final List<GrepMatch> rows = new ArrayList<>();
    private long seen;
    private String lastFile;
    private int inFile;
    private String reason = "";

    GrepPage(CodeSearchRequest request) {
        this.request = request;
    }

    boolean offer(GrepMatch row) {
        if (seen++ < request.offset()) return true;
        if (rows.size() == request.maxResults()) {
            reason = "已达到 max_results=" + request.maxResults();
            return false;
        }
        if (!row.file().equals(lastFile)) {
            lastFile = row.file();
            inFile = 0;
        }
        if (request.mode().equals("content") && inFile == request.headLimit()) {
            reason = "已达到单文件 head_limit=" + request.headLimit();
            return false;
        }
        rows.add(row);
        inFile++;
        return true;
    }

    boolean full() {
        return !reason.isEmpty();
    }

    CodeSearchResult result(String engine) {
        return new CodeSearchResult(engine, rows, full(), reason);
    }
}
