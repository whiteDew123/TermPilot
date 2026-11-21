package com.termpilot.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FetchResultTest {
    @Test void reportsBothTruncationReasons() {
        var page = FetchResult.fromMarkdown("https://example.com", "", "正文内容", 2, true);
        assertTrue(page.truncated());
        assertEquals("正文", page.markdown());
        assertTrue(page.hint().contains("全文长度未知"));
        assertTrue(page.hint().contains("max_chars"));
        assertEquals(4, page.contentLength());
    }

    @Test void emptyPageKeepsDownloadWarning() {
        var page = FetchResult.fromMarkdown("https://example.com", "", "", 8000, true);
        assertTrue(page.bodyEmpty());
        assertTrue(page.truncated());
        assertTrue(page.hint().contains("未提取到正文"));
        assertTrue(page.hint().contains("全文长度未知"));
    }

    @Test void characterLimitDoesNotSplitEmoji() {
        var page = FetchResult.fromMarkdown("https://example.com", "", "a😀b", 2);
        assertEquals("a", page.markdown());
        assertTrue(page.truncated());
    }
}
