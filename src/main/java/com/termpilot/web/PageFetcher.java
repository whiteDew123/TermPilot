package com.termpilot.web;

import java.io.IOException;

/** 抓取已通过网络策略检查的网址，返回可放入对话的 Markdown 正文。 */
public interface PageFetcher {
    FetchResult fetchPage(String url, int maxChars) throws IOException;
}
