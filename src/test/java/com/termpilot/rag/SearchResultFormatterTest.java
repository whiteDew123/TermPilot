package com.termpilot.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultFormatterTest {

    @Test
    void cliFormatIncludesReadableSummaryBeforeResults() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "/Users/termpilot/Documents/GitHub/termpilot/src/main/java/com/termpilot/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环：读取用户输入，思考，调用工具，再继续下一轮。",
                        1.42, 20, 40
                )
        );

        String output = SearchResultFormatter.formatForCli("Agent的ReAct循环是怎么实现的", results);

        assertTrue(output.contains("搜索摘要:"));
        assertTrue(output.contains("最相关的入口是 [method:Agent.run(String userInput)]"));
        assertTrue(output.contains("1. [method:Agent.run(String userInput)]"));
        assertTrue(output.contains("Agent.java:20-40"));
        assertTrue(SearchResultFormatter.formatForTool("Agent", results).contains("Agent.java:20-40"));
        assertTrue(output.contains("综合得分: 1.420"));
        assertTrue(SearchResultFormatter.formatForTool("Agent", results).contains("综合得分: 1.420"));
    }
}
