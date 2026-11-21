# 普通命令行案例：Python 标准库待办清单

这个案例不使用模型、不需要 API Key、不依赖 TermPilot，用来证明 PaiWarp 只是操作真实终端，没有隐藏的 Java、Maven 或模型依赖。适合第一次安装后做冒烟测试。

## 准备

- 用 `paiwarp_open` 在**任意空目录**打开终端（不是插件目录，也不是 TermPilot 仓库）。
- 把本目录的 `todo.py` 复制进去，或直接引用绝对路径。
- 只需要 Python 3.9+ 标准库。

## 步骤

1. `paiwarp_open`，`title: "todo 示例"`，可以同时 `record: true`。
2. 输入 `python3 todo.py todos.json`，等待出现 `todo>` 提示符，截图 `todo-start`。
3. 输入 `add 买牛奶`，再输入 `add 写测试`。
4. 输入 `list`，截图 `todo-list`；屏幕上应看到两条待办，状态为未完成。
5. 输入 `done 1`，再输入 `list`，第一条应变成 `[x]`。
6. 输入 `quit`。程序会打印保存路径，终端回到 shell。
7. `paiwarp_close`，停止录屏。

## 核对

用 `paiwarp_verify`，至少包含：

```json
{"checks": [
  {"id": "json", "kind": "file_exists", "path": "todos.json"},
  {"id": "milk", "kind": "file_contains", "path": "todos.json", "text": "买牛奶"},
  {"id": "done", "kind": "file_contains", "path": "todos.json", "text": "\"done\": true"}
]}
```

文件内容才是证据；终端里出现 “保存到” 只说明程序打印了这句话。

## 可选：干净环境

想确认没有依赖私人目录时，用空的 `HOME` 和最小 `PATH` 再跑一次：

```sh
env -i HOME=/tmp/paiwarp-home PATH=/usr/bin:/bin:/usr/sbin:/sbin \
  SHELL=/bin/bash PYTHONPATH=<site-packages> \
  PLAYWRIGHT_BROWSERS_PATH=<playwright 浏览器目录> \
  python3 -u <插件目录>/lib/terminal_host.py \
  --workspace /tmp/paiwarp-demo --assets /tmp/paiwarp-demo/run \
  --live /tmp/paiwarp-demo/live --id demo --shell /bin/bash --norc
```

插件自带的 `tests/demo_clean_env.py --plugin <插件目录>` 会自动做这件事：新工作区、空 HOME、最小 PATH，并检查没有创建 `~/.codex`。
