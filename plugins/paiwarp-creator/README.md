# PaiWarp 创造版

`PaiWarp 创造版` 是 DeepSeek Harness 的本地桌面插件，包名 `paiwarp-dsh-creator`。它把 TermPilot 第二章《Plan-and-Execute》第 09 节在真实本机终端里复跑一遍，并把结果留在工作区里，不依赖 Codex、`~/.codex`、图床账号，也不调用已有 PaiWarp 插件的工具。

## 一句话验收

在标准模式、选中 `termpilot` 工作区后输入：

```text
用 PaiWarp 创造版复跑第二章
```

Agent 会调用 `paiwarp_creator_run_chapter_two` 打开右侧实况面板，然后按 `waiting_input` 返回的 `run`、`turn`、`expected` 分步调用 `paiwarp_creator_terminal_input`。完全权限会话直接运行；其他权限模式会走 Harness 自己的单次审批。

## 实际执行的内容

1. 在临时目录中准备 `pom.xml`、`src` 和 `README.md`，不修改当前仓库源码。
2. 键入 `mvn clean package`，启动 `target/termpilot-1.0-SNAPSHOT.jar`。
3. 输入原文串行计划：`/plan 创建一个 Java 项目叫 demo，写一个 Hello 类输出 Hello World，然后编译运行`，在计划审阅处回车，核对 `demo` 下的源码、`Hello.class`、真实命令结果和 `Hello World` 输出。
4. 用 bracketed paste 输入原文多行并行 DAG，在审阅处按 `I`，输入 `汇总时用表格输出`，再在修改后的计划上回车执行。
5. 从本轮 `ConversationLedger` 读取计划 JSON、任务依赖、并行批次、工具调用和结果；只有四个独立任务加一个精确依赖四者的汇总任务才算 DAG 合格。
6. 按 `Shift+Tab` 切到 plan 模式，输入普通任务 `读取 README.md 的前 5 行，原样发给我`，验证它没有带 `/plan` 也自动生成计划并执行。
7. 输入 `/exit`，核对 TermPilot 退出码。
8. 在第 09 节四个原图位置分别截取 `p2-01-serial-plan.png`、`p2-02-parallel-plan.png`、`p2-03-revised-plan.png`、`p2-04-plan-mode-task.png`，录制并转码 `session.mp4`，写 `result.json` 和 `execution-log.json`。
9. 只把 `docs/articles/build-agent-p2-plan-execute.md` 的演示副本写到本次运行目录，并把第 09 节四处图片地址改成同目录的相对文件名；原教程不改。

如果计划没有按要求拆成“4 个独立任务 + 1 个依赖前四者的汇总任务”，或者最终没有表格、普通任务没有真实读文件、退出码非 0，`result.json` 会写 `failed`，并把实际依赖、批次和执行情况写清楚，不会填成功。

## 安装

插件目录：

```text
plugins/paiwarp-creator
```

先安装前端依赖：

```bash
cd plugins/paiwarp-creator
npm install
```

然后打开 Harness 的“插件 → 添加插件 → 本地目录”，选择这个目录并启用。显示名是 **PaiWarp 创造版**，不需要安装 Codex，也不读取 `~/.codex` 或旧 PaiWarp 的运行目录。

运行 TermPilot Case 还需要这台 Mac 具备：

- Java 17+、Maven
- Node.js（Harness 桌面端已有）
- `/usr/bin/python3` 及其 `playwright` 包和配套 Chromium
- `ffmpeg`、`ffprobe`

Python Playwright 首次准备：

```bash
/usr/bin/python3 -m pip install --user playwright
/usr/bin/python3 -m playwright install chromium
```

插件只会用环境变量或项目 `.env` 里的模型配置启动被测 TermPilot；报告不记录 API Key。没有云存储账号也能用，因为截图和教程副本都留在本地。

## 工具与命令

| 名称 | 作用 |
|---|---|
| `paiwarp_creator_run_chapter_two` | 打开真实 PTY，完成第二章第 09 节全流程，返回 `waiting_input` 或最终结果 |
| `paiwarp_creator_terminal_input` | 按 `run`、`turn`、`expected` 发送按键或一整行；`ENTER`、`I`、`SHIFT_TAB` 是按键标记 |
| `/paiwarp-creator view` | 查看本次实况目录 |
| `/paiwarp-creator pause\|resume\|stop` | 暂停、交回 Agent 或停止 |
| `/paiwarp-creator key <JSON字符串>` | 人工接管时发送原始按键 |

## 输出

每次运行新开一个目录：

```text
cases/paiwarp-creator/runs/<时间戳>-<短编号>/
├── p2-01-serial-plan.png
├── p2-02-parallel-plan.png
├── p2-03-revised-plan.png
├── p2-04-plan-mode-task.png
├── session.mp4
├── result.json
├── execution-log.json
└── build-agent-p2-plan-execute.md   # 仅第 09 节四处图片地址改为本地相对路径
```

右侧栏的“PaiWarp 创造版”页签显示实况 JPEG；运行结束后可点四张截图、录屏和教程副本预览。教程副本里的相对链接和图片在同一个目录，直接用本地 Markdown 阅读器打开即可。

## 复用了哪些公开源码

新插件是独立实现，但按用户允许的范围，改写/复用了仓库里公开的 PTY、xterm 和第一章验证代码：

- `plugins/paiwarp-dsh/terminal.py`：PTY、xterm.js 页面写入、Playwright 截图/录像、帧文件原子更新。
- `plugins/paiwarp-dsh/terminal.html`：xterm.js 页面壳。
- `plugins/paiwarp-dsh/chapter.py`：从 `ConversationLedger` 提取工具调用/结果、检查 `execute_command` 的退出码和程序输出、用字节区间定位图片地址。
- `plugins/paiwarp-dsh/run.py`：临时源码/用户目录隔离、报告落盘、WebM 转 MP4 和 `ffprobe` 校验。
- `plugins/paiwarp-dsh/host.js`：外层 Agent 与 PTY 运行器之间的 `waiting_input` 握手和 `run/turn/expected` 校验；本插件的工具名和第二章流程是新的。
- `plugins/paiwarp-dsh/client.js`：右侧栏页签、工具卡片和实况/结果预览的注册方式。

没有调用旧插件的工具，没有读取旧运行目录，也不依赖旧插件的安装状态。

## 离线自检

```bash
node --check host.js
node --check client.js
python3 -m py_compile creator_runner.py creator_terminal.py creator_chapter2.py
python3 -m unittest discover -s tests -v
```

自检覆盖计划 DAG 判定、表格判定，以及“只改四张图地址、正文逐字节不变”的教程副本保护。
