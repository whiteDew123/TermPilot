package com.termpilot.cli;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import com.termpilot.mcp.resources.McpResourceDescriptor;
import com.termpilot.skill.Skill;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermPilotCompleterTest {

    @Test
    void completesManagementProvidersAndNewlyRegisteredModels() {
        var config = new com.termpilot.config.TermPilotConfig();
        var settings = new com.termpilot.config.TermPilotConfig.ProviderConfig();
        settings.getModels().put("next", com.termpilot.llm.ModelProfile.discovered());
        config.getProviders().put("deepseek", settings);
        TermPilotCompleter completer = new TermPilotCompleter(List::of, List::of, () -> config);
        List<Candidate> models = new ArrayList<>();
        completer.complete(null, parsed("/model deepseek/n", "deepseek/n"), models);
        assertTrue(models.stream().anyMatch(c -> c.value().equals("deepseek/next")));
        List<Candidate> providers = new ArrayList<>();
        completer.complete(null, parsed("/model refresh dee", "dee"), providers);
        assertTrue(providers.stream().anyMatch(c -> c.value().equals("deepseek")));
    }

    @Test
    void suggestsDeepSeekFlashModel() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, parsed("/model deep", "deep"), candidates);
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("deepseek-flash")));
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("deepseek")));
    }

    @Test
    void suggestsSlashCommandsWhenInputStartsWithSlash() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/", "/"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/model")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/browser connect")));
        assertTrue(candidates.stream().anyMatch(c -> c.displ().equals("/search <查询>")));
    }

    @Test
    void completesSubCommandWithoutDuplicatingPrefix() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp r", "r"), candidates);

        Candidate restart = candidates.stream()
                .filter(c -> c.displ().equals("/mcp restart <name>"))
                .findFirst()
                .orElseThrow();
        assertEquals("restart ", restart.value());
    }

    @Test
    void ignoresNormalWords() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("hello", "hello"), candidates);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void completesModelProviderNames() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/model st", "st"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("step")));
    }

    @Test
    void completesHunyuanModelNames() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> providerCandidates = new ArrayList<>();
        List<Candidate> modelCandidates = new ArrayList<>();

        completer.complete(null, parsed("/model hun", "hun"), providerCandidates);
        completer.complete(null, parsed("/model hy", "hy"), modelCandidates);

        assertTrue(providerCandidates.stream().anyMatch(c -> c.value().equals("hunyuan")));
        assertTrue(modelCandidates.stream().anyMatch(c -> c.value().equals("hy4-preview")));
    }

    @Test
    void completesConfigProviderCommand() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider fr", "fr"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("freellmapi ")));
    }

    @Test
    void completesAgnesProviderCommand() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider ag", "ag"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("agnes ")));
    }

    @Test
    void completesXfyunProviderCommand() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider xf", "xf"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("xfyun ")));
    }

    @Test
    void completesHunyuanProviderCommand() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/config provider hu", "hu"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("hunyuan ")));
    }

    @Test
    void completesMcpServerNamesFromResources() {
        TermPilotCompleter completer = new TermPilotCompleter(() -> List.of(
                new McpResourceDescriptor("chrome-devtools", "file:///a", "a", "", "", "text/plain", null),
                new McpResourceDescriptor("filesystem", "file:///b", "b", "", "", "text/plain", null)
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/mcp logs ch", "ch"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("chrome-devtools"))
                .findFirst()
                .orElseThrow();
        assertEquals("MCP server", candidate.group());
    }

    @Test
    void completesSkillNames() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of, () -> List.of(
                skill("web-access", "浏览器和联网策略"),
                skill("ai-article", "文章写作")
        ));
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill show web", "web"), candidates);

        Candidate candidate = candidates.stream()
                .filter(c -> c.value().equals("web-access"))
                .findFirst()
                .orElseThrow();
        assertEquals("浏览器和联网策略", candidate.descr());
    }

    @Test
    void completesSkillSubCommands() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/skill sh", "sh"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("show ")));
    }

    @Test
    void completesTaskSubCommands() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("/task ca", "ca"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("cancel ")));
    }

    @Test
    void completesLocalPathMentions() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@pom", "@pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@pom.xml")));
    }

    @Test
    void completesImagePathMentionsWithTokenPrefix() {
        TermPilotCompleter completer = new TermPilotCompleter(List::of);
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, parsed("@image:pom", "@image:pom"), candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("@image:pom.xml")));
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "1.0.0", null, List.of(), Skill.Source.USER, "body", null, null);
    }

    private static ParsedLine parsed(String line, String word) {
        return new ParsedLine() {
            @Override public String word() { return word; }
            @Override public int wordCursor() { return word.length(); }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(word); }
            @Override public String line() { return line; }
            @Override public int cursor() { return line.length(); }
        };
    }
}
