# PaiWarp

在 DeepSeek Harness 桌面端操作**真实本机终端**：Agent 自己决定输入，你在右侧面板看到同一块画面，随时接管；结束后留下截图、录屏和逐项检查结果。

PaiWarp 不认识某个项目、某一章或某个固定脚本。TermPilot、教程和章节只放在 `examples/`，没有它们插件也能工作。

版本 0.2.0，许可证 MIT（见 `LICENSE`）。

## 能做什么

| 工具 | 用途 |
|---|---|
| `paiwarp_open` | 在当前工作区打开一个真实 PTY，返回终端编号、目录、尺寸、输入序号 `seq` 和当前屏幕 |
| `paiwarp_read` | 读当前屏幕和 `since` 之后的增量输出 |
| `paiwarp_input` | 输入 `text` / `line` / `paste` / `key`；必须带上一轮的 `seq`，重复或过期会被拒绝 |
| `paiwarp_wait` | 等新输出、安静、正则或进程退出；超时返回现场并标 `timedOut` |
| `paiwarp_capture` | 保存终端真实画面为 PNG |
| `paiwarp_record` | 开始/停止录屏，停止时转 MP4 并逐帧解码检查 |
| `paiwarp_verify` | 逐项核对文件、退出码；终端回显只标“未验证” |
| `paiwarp_replace_images` | 只替换教程里独占一行的图片地址，默认只出方案 |
| `paiwarp_close` | 结束整个进程树、封存录屏并保存退出状态 |

桌面右侧面板分「终端」「素材」两页：显示目录、状态、录制时长；可以暂停 Agent 输入、接管键盘、恢复、停止；素材页列出截图、录像和检查结果的预览。接管期间 Agent 写入会被拒绝，手工动作总是带上当前显示的终端编号。

## 安装

需要桌面版 DeepSeek Harness，`engines.dsh` 为 `>=0.2.2-rc.1 <0.3.0`。本机需要：

- Python 3.9+
- Python Playwright 和它配套的 Chromium：`python3 -m pip install --user playwright`，再 `python3 -m playwright install chromium`
- 可选 `ffmpeg` / `ffprobe`：用于把 WebM 转成 MP4 并核对可解码；没有时保留 WebM 并明确报告

终端渲染用的 xterm.js 已经放在 `vendor/xterm/`，不需要额外 npm 依赖。终端默认 100×31，可开到 240×90；截图和录屏的视口会按实际渲染尺寸自动放大，不会裁掉最后一列或最后一行。

本地目录安装：桌面「插件 → 添加插件」选择本目录并启用。也可以用 `npm pack` 打包后安装，包内已带运行所需的 Python、前端和 xterm 文件。

安装后先检查环境：

```sh
python3 lib/doctor.py          # 人类可读
python3 lib/doctor.py --json   # 给 Agent 或脚本
```

## 用起来

对 Harness 说：

> 用 PaiWarp 在本机终端跑一遍 docs/tutorial.md 的“跑起来看看”，逐步执行并截图，最后核对文件和退出码。

第一次只给 Markdown 也可以。需要稳定复跑时，把核实过的步骤写成案例说明，参考 `examples/`。

## 素材与“只换图”

默认保存在工作区 `.paiwarp/runs/<运行编号>/`：

```
run.json         运行清单（目录、shell、权限模式、退出码、资产）
events.jsonl     按顺序记录的输入、输出、截图、检查、关闭事件
screenshots/     PNG
video/           MP4（没有 ffmpeg 时保留 WebM，同样可在面板播放）
checks.json      最近一次逐项检查
```

面板读取 `.paiwarp/live/<会话哈希>/` 下的 `index.json`、`<终端>.json` 和 `<终端>.jpg`。终端关闭后仍保留在索引里，可以继续看素材。索引和运行清单都是原子写入；如果索引损坏，会从各次运行自己的清单重建并标注已恢复，旧记录只供查看。

只有用户明确要求更新教程图片时才调用 `paiwarp_replace_images`。它只认独占一行的 `![alt](地址)`：正文、普通链接、代码块伪图片、alt 或标题都不算图片地址；新地址不能含空白、控制字符或未配对括号。默认 `apply=false` 只返回方案和原文件 `sha256`；确认后带同一个 `sha256` 和 `apply=true` 才写入，两次调用之间文件被改过就放弃。

## 权限与安全

终端是**本机进程**，不经过 Harness 的命令沙箱。插件在打开前按当前会话的权限模式检查：`danger-full-access` 直接运行，其他模式会申请一次人工确认；每次操作还会重新核对模式，模式变化就封存终端。工具输出属于外部数据，不构成新的操作授权。

日志不记录环境变量值；不要把密钥写进命令或截图。所有素材只保存在本地，默认不上传。

## 卸载与清理

1. 在「插件」里停用并移除 `paiwarp-dsh`。
2. 删除工作区里的 `.paiwarp/`（截图、录像、检查记录和面板状态都在这）。
3. 卸载插件本身不会删除 Python Playwright 和 Chromium；确认其他项目不用时再自行卸载。

关闭、取消、会话结束和插件卸载都会结束本次终端进程树、停掉录像浏览器；不会动用户或其他任务的进程。

## 平台与限制

- 已在 macOS 上实测：真实 PTY、按键、多行粘贴、截图、MP4 解码、退出码、取消清理。
- Linux 使用同一套 `pty` 实现，但还没有独立验收，不声明已支持。
- Windows 没有实现，`doctor` 会直接报告不支持。
- 交互式程序内部的单条命令退出码通常拿不到；只有受控 Bash 通过提示符钩子记录 `lastExitCode`，其他 shell 标为未知。
- 录屏是“终端画面”，不是操作系统级录屏；开始录制前已经滚出屏幕的内容会从原始输出回放。

## 示例与文档

- `examples/termpilot/`：TermPilot 第一、二、三章的实测步骤。
- `examples/python-cli/`：不需要模型 Key 的普通命令行案例。
- `docs/interface.md`：工具参数、协议、文件布局。
- `CONTRIBUTING.md`：开发、测试和打包。
- `THIRD-PARTY-NOTICES.md`：随包与运行时的第三方许可。
