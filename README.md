# TermPilot

TermPilot 是一个跑在终端里的 AI 编码 Agent：用自然语言下指令，它在当前仓库里自己读文件、搜代码、改代码、跑命令，并把每一步的理由、工具调用和结果展示出来。三种执行方式共用同一套工具、记忆和快照能力——单代理循环（ReAct）、先规划再执行的 DAG（Plan-and-Execute）、多角色协作（Plan + 执行者 + 审查者）。

## 核心能力

- **三种执行方式**：默认 ReAct 逐步推进；`/plan` 把目标拆成带依赖的任务图，可以在审阅页里改；`/team` 让规划者、执行者、审查者分工，审查不通过会自动重做。
- **工具执行有边界**：路径围栏把读写限制在项目目录内，命令有黑名单，危险操作按模式确认。并行只对只读工具开放，写文件和执行命令严格串行。
- **上下文自己管**：工具结果过大就落盘、旧结果回收、超出窗口再摘要压缩；长期记忆按项目存，多进程写入加锁。
- **可追溯**：每轮工具调用、确认记录、文件改动都可回看，Git 侧历史快照支持回退到某一轮之前。
- **可扩展**：MCP 接入外部工具（stdio / 远程），Skill 用 Markdown 打包"专家手册"，按需加载进上下文。
- **多模型**：DeepSeek / GLM / 混元 / Step / Kimi 等 8 家 provider，会话内 `/model` 切换，思考模型的推理内容会回传。
- **联网与浏览器**：搜索、抓正文、Chrome DevTools 控制页面；可访问的网址只能来自用户本轮输入或本轮搜索到的结果。
- **离线评测**：内置 AgentBench 题集与 LLM-as-a-Judge，用来衡量改动是否真的让 Agent 变强。

## 技术栈

Java 17 / Maven 构建，单 jar 发布（shade 打 fat jar）；终端界面用手写 TUI 渲染器（支持 Markdown、表格、代码高亮），浏览器侧走 Chrome DevTools MCP，代码检索用自建向量索引 + ripgrep。

## 快速开始

```bash
cp .env.example .env          # 至少填一个模型的 API Key
mvn clean package             # 默认跳过测试
java -jar target/termpilot-1.0-SNAPSHOT.jar
```

需要 Java 17+ 和 Maven；`ripgrep` 可选（装了会让代码搜索更快）。可用的 Key 包括 `DEEPSEEK_API_KEY`、`GLM_API_KEY`、`HUNYUAN_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY` 等，配了哪个就用哪个，默认优先 DeepSeek。

配置落在 `~/.termpilot/`：`config.json` 存模型与偏好，`mcp.json` 存 MCP server，`skills/` 放自定义 Skill，`history/raw/` 是只追加的会话记录。

## 交互命令

| 命令 | 作用 |
| --- | --- |
| `/plan` / `/team` | 切到规划执行 / 多角色协作 |
| `/mode auto\|plan\|ask` | 确认严格程度（Shift+Tab 轮换） |
| `/hitl on\|default` | 每次写操作都确认 / 按分类器自动放行 |
| `/model list\|add\|refresh\|info` | 模型管理 |
| `/memory` / `/save` / `/compact` / `/context` | 长期记忆、压缩、上下文用量 |
| `/resume` / `/export` / `/init` | 恢复会话、导出、生成项目说明 |
| `/mcp` / `/skill` | MCP server 与 Skill 管理 |
| `/wechat` | 微信通道绑定与状态 |

## 架构

```
src/main/java/com/termpilot/
├── agent/     ReAct、Plan-and-Execute、多 Agent 编排
├── tool/      工具注册与执行（18 个内置工具）
├── policy/    路径围栏、命令守卫、审计日志
├── hitl/      人工确认
├── llm/       8 个 provider 客户端与工厂
├── mcp/       MCP 连接、重启、信任
├── skill/     Skill 索引与注入
├── rag/       代码切分、向量化、检索
├── memory/    短期上下文与长期记忆
├── lsp/       LSP 诊断注入
├── snapshot/  Git 侧历史快照
├── runtime/   HTTP Runtime API 与后台任务
├── render/ tui/  终端渲染
└── eval/      LLM-as-a-Judge 与 benchmark
```

三种执行方式都通过同一个 `ToolRegistry` 执行工具，共用 `MemoryManager`、`SnapshotService`、`ConversationLedger`，所以换执行方式不会换掉工具语义和审计记录。

## 内置工具

`read_file`、`write_file`、`edit_file`（默认只替换唯一一处匹配）、`list_dir`、`glob_files`、`grep_code`、`search_code`（语义检索）、`execute_command`、`create_project`、`web_search`、`web_fetch`、`browser_connect` / `browser_disconnect` / `browser_status`、`save_memory`、`load_skill`、`save_skill_reference`、`revert_turn`。MCP 工具在运行时注册为 `mcp__{server}__{tool}`。

## 安全模型

检查顺序固定：本轮工具策略 → 人工确认 → 参数校验 → 路径围栏 / 命令守卫。被安全策略拒绝的操作，用户也无法批准。

- **auto**（默认）：读写文件直接执行；`execute_command` 先由分类器审一遍，通过就执行并写审计；不通过直接以失败返回给模型，不弹框。
- **plan**：普通输入走规划执行，先看计划再动手。
- **ask**：`write_file`、`edit_file`、`create_project` 额外要求确认。

上网和浏览器另有一套规则：能打开的网址只能来自用户本轮原文或本轮搜索返回的结果，工具输出里的链接不会扩大可访问范围。

## Runtime API

```bash
java -jar target/termpilot-1.0-SNAPSHOT.jar serve --http --port 8080
```

需要先设置 `TERMPILOT_RUNTIME_API_KEY`，用于把 TermPilot 挂到外部系统里做后台任务。

## 测试

```bash
mvn test -Pquick                            # 常规回归
mvn test -Pphase16-smoke                    # 终端界面相关
mvn test -Dtest=XxxTest -DskipTests=false   # 单个测试（必须带 -DskipTests=false）
```

`-Pquick` 里有一批历史上就失败的用例（评测包与 `TerminalMarkdownRendererTest`），判断有没有引入新问题要对比改动前后的失败名单，而不是只看总数。

## 还没做的

没有通用容器/虚拟机沙箱（命令沙箱默认关闭，沙箱里没有网络也访问不到 `~/.m2`）；MCP 的 OAuth 登录和 sampling 未支持；HTTP 类型 MCP server 断线不自动重连。`ROADMAP.md` 里写的是计划，不代表已经实现。
