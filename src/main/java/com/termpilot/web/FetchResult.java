package com.termpilot.web;

/**
 * web_fetch 的结构化结果。
 *
 * <ul>
 *   <li>{@link #markdown} 为空 / {@link #bodyEmpty} = true 时，意味着抓到了 HTML 但提取不出正文，
 *       常见原因是 SPA / 防爬墙。LLM 应该意识到这是已知边界，不要反复重试。</li>
 *   <li>{@link #truncated} = true 表示下载或返回正文被截断，具体原因在 hint 中。</li>
 * </ul>
 */
public record FetchResult(
        String url,
        String title,
        String markdown,
        int contentLength,
        boolean truncated,
        boolean bodyEmpty,
        String hint
) {

    static FetchResult fromMarkdown(String url, String title, String markdown, int maxChars) {
        return fromMarkdown(url, title, markdown, maxChars, false);
    }

    static FetchResult fromMarkdown(String url, String title, String markdown, int maxChars, boolean downloadTruncated) {
        int length = markdown.length();
        boolean truncated = maxChars > 0 && length > maxChars;
        int end = truncated ? maxChars : length;
        if (end > 0 && end < length && Character.isHighSurrogate(markdown.charAt(end - 1))
                && Character.isLowSurrogate(markdown.charAt(end))) end--;
        FetchResult result = ok(url, title, markdown.substring(0, end), length, truncated || downloadTruncated);
        String hint = result.hint();
        if (downloadTruncated) hint += " 下载达到大小上限，正文仅来自已下载部分，全文长度未知。";
        if (truncated) hint += " 返回正文达到 max_chars 上限，后续内容未返回。";
        return new FetchResult(result.url(), result.title(), result.markdown(), length,
                result.truncated(), result.bodyEmpty(), hint.trim());
    }

    public static FetchResult ok(String url, String title, String markdown, int originalLength, boolean truncated) {
        boolean empty = markdown == null || markdown.isBlank();
        String hint = empty
                ? "未提取到正文。可能是 JS 渲染或防爬墙；不要反复抓取，可按浏览器访问规则补读。"
                : "";
        return new FetchResult(url, title == null ? "" : title, markdown == null ? "" : markdown,
                originalLength, truncated, empty, hint);
    }
}
