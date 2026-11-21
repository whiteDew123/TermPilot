package com.termpilot.prompt;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class PromptAssembler {
    /** 兼容原先通过 PromptAssembler 配置评测时钟的调用方。 */
    public static final String RUNTIME_DATE_PROPERTY = TurnReminder.RUNTIME_DATE_PROPERTY;
    public static final String RUNTIME_ZONE_PROPERTY = TurnReminder.RUNTIME_ZONE_PROPERTY;
    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        String base = repository.loadRequired("base.md");
        if (!ctx.toolsEnabled()) {
            base = stripToolSections(base);
        }
        validateLanguageSection(base, "base.md");

        StringBuilder prompt = new StringBuilder();
        append(prompt, base);
        if (!ctx.toolsEnabled()) {
            append(prompt, noToolsSection(mode));
        }
        append(prompt, repository.loadRequired("personalities/calm.md"));
        append(prompt, applyVariables(repository.loadRequired(mode.resourcePath()), ctx));
        append(prompt, repository.loadRequired("approvals/" + approvalMode(ctx) + ".md"));
        append(prompt, repository.loadRequired("context/context-management.md"));
        append(prompt, repository.loadRequired("handoff.md"));
        append(prompt, dynamicSection("Project Context", ctx.projectMemoryContext()));
        append(prompt, dynamicSection("Skills", ctx.skillIndex()));
        append(prompt, ctx.externalContext());

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    private String approvalMode(PromptContext context) {
        String mode = context.approvalMode();
        if (mode == null || mode.isBlank()) {
            return "suggest";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "never" -> normalized;
            default -> "suggest";
        };
    }

    private static String applyVariables(String template, PromptContext context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.variables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        result = result.replace("{{taskType}}", context.variable("taskType"));
        result = result.replace("{{taskDescription}}", context.variable("taskDescription"));
        return result;
    }

    private static String dynamicSection(String title, String... values) {
        StringBuilder body = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!body.isEmpty()) {
                    body.append("\n\n");
                }
                body.append(value.trim());
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        return "## " + title + "\n\n" + body;
    }

    private static String stripToolSections(String base) {
        String withoutTools = base.replaceFirst("(?s)\\n## Tools\\n.*?(?=\\n## Browser Policy\\n)", "\n");
        return withoutTools.replaceFirst("(?s)\\n## Tool Policy\\n.*?(?=\\n## Browser Policy\\n)", "\n");
    }

    private static String noToolsSection(PromptMode mode) {
        if (mode == PromptMode.TEAM_PLANNER || mode == PromptMode.TEAM_REVIEWER) {
            String instruction = mode == PromptMode.TEAM_PLANNER
                    ? "把需要读取文件、执行命令等操作写成计划步骤，交给执行者。"
                    : "根据收到的任务与执行结果进行审查，证据不足时在 issues 中说明。";
            return "## Tool Availability\n\n本角色不提供工具。" + instruction + "只返回规定格式的 JSON。";
        }
        if (mode == PromptMode.PLANNER) {
            return """
                    ## Tool Availability

                    本轮只生成计划，不执行任务。规划阶段不提供工具，这与当前供应商是否支持工具无关，不要要求用户切换供应商。
                    需要读取文件、搜索代码或运行命令时，把这些操作写成计划里的任务，交给用户审阅后的执行阶段完成。
                    不要输出工具调用、DSML 或 XML 标签，不要声称已经读取文件或执行命令。只返回规定格式的计划 JSON。
                    """;
        }
        return """
                ## Tool Availability

                当前模型不支持 TermPilot 原生工具调用。本轮不要声称已经读取、搜索、执行或修改了任何本地文件、命令、浏览器、MCP resource 或外部工具结果。

                绝对不要输出伪造的工具标签或 XML，例如 `<toolcall>`、`<read_file>`、`<list_dir>`。如果用户请求必须依赖本地文件、代码搜索、命令执行或联网工具，请直接说明当前 provider 不支持工具调用，并提示切换到支持 tools 的 provider 后重试。
                """;
    }

    private static void append(StringBuilder sb, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(section.trim());
    }

    private static void validateLanguageSection(String prompt, String source) {
        if (prompt == null || !prompt.contains("## Language")) {
            throw new IllegalStateException("Prompt " + source + " must contain a '## Language' section");
        }
    }
}
