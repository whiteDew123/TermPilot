"""Chapter 2 verification and local-only article copy.

The run is accepted only from this run's screen, files, exit codes and TermPilot
ConversationLedger. Plan shape is reported exactly as observed; a DAG that does
not match the tutorial's four-independent-plus-one-summary shape can never make
the run pass.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

ARTICLE = Path("docs/articles/build-agent-p2-plan-execute.md")
SECTION = "## 09、跑起来看看"
FEEDBACK = "汇总时用表格输出"
NORMAL_TASK = "读取 README.md 的前 5 行，原样发给我"
IMAGE_NAMES = [
    "p2-01-serial-plan.png",
    "p2-02-parallel-plan.png",
    "p2-03-revised-plan.png",
    "p2-04-plan-mode-task.png",
]
JAVA_CMD = 'java -Duser.home="$PAIWARP_CREATOR_HOME" -jar target/termpilot-1.0-SNAPSHOT.jar'
STAMP = ".paiwarp-creator-last-status"
SHELL_PROMPT = "paiwarp-creator $"


def atomic(path, data):
    temp = path.with_name("." + path.name + ".part")
    temp.write_bytes(data)
    os.replace(temp, path)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def message_text(message):
    if not isinstance(message, dict):
        return ""
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, str):
                parts.append(part)
            elif isinstance(part, dict):
                text = part.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(parts)
    return ""


def parse_plan_text(text):
    if not isinstance(text, str) or not text.strip():
        return None
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.I)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        plan = json.loads(cleaned[start:end + 1])
    except (ValueError, TypeError):
        return None
    if not isinstance(plan, dict) or not isinstance(plan.get("tasks"), list) or not plan["tasks"]:
        return None
    return plan


def plan_tasks(plan):
    tasks = []
    for raw in (plan or {}).get("tasks", []):
        if not isinstance(raw, dict):
            continue
        task_id = str(raw.get("id") or "").strip()
        if not task_id:
            continue
        deps = raw.get("dependencies") or []
        if not isinstance(deps, list):
            deps = []
        tasks.append({
            "id": task_id,
            "description": str(raw.get("description") or ""),
            "type": str(raw.get("type") or "ANALYSIS"),
            "dependencies": [str(x) for x in deps if str(x)],
        })
    return tasks


def compute_batches(tasks):
    order = [t["id"] for t in tasks]
    by_id = {t["id"]: t for t in tasks}
    remaining = set(order)
    completed = set()
    batches = []
    while remaining:
        batch = [task_id for task_id in order
                 if task_id in remaining and set(by_id[task_id]["dependencies"]) <= completed]
        if not batch:
            return None
        batches.append(batch)
        for task_id in batch:
            remaining.remove(task_id)
            completed.add(task_id)
    return batches


def assess_parallel(plan):
    tasks = plan_tasks(plan)
    ids = [t["id"] for t in tasks]
    by_id = {t["id"]: t for t in tasks}
    independent = [t for t in tasks if not t["dependencies"]]
    dependent = [t for t in tasks if t["dependencies"]]
    batches = compute_batches(tasks)
    summary = dependent[0] if len(dependent) == 1 else None
    reason = ""
    compliant = True
    if len(tasks) != 5:
        compliant = False
        reason = f"任务数是 {len(tasks)}，不是 5"
    elif len(independent) != 4:
        compliant = False
        reason = f"无依赖任务数是 {len(independent)}，不是 4"
    elif summary is None:
        compliant = False
        reason = "没有唯一的汇总任务"
    else:
        expected = {t["id"] for t in independent}
        actual = set(summary["dependencies"])
        if actual != expected:
            compliant = False
            reason = "汇总任务依赖 " + ", ".join(sorted(actual)) + "，没有精确依赖四个独立任务"
        elif any(by_id[task_id]["dependencies"] for task_id in expected):
            compliant = False
            reason = "被汇总的任务仍然带有依赖"
    return {
        "compliant": compliant,
        "reason": reason,
        "taskCount": len(tasks),
        "independentIds": [t["id"] for t in independent],
        "summaryId": summary["id"] if summary else None,
        "summaryDependencies": list(summary["dependencies"]) if summary else [],
        "batches": batches,
        "tasks": tasks,
    }


def plan_mentions_table(plan):
    return "表格" in json.dumps(plan or {}, ensure_ascii=False)


def has_markdown_table(text):
    if not isinstance(text, str):
        return False
    lines = text.splitlines()
    for i in range(len(lines) - 1):
        if "|" not in lines[i] or "|" not in lines[i + 1]:
            continue
        if re.fullmatch(r"\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*", lines[i + 1]):
            return True
    return False


def chapter_goals(root):
    """Return the two exact chapter-09 text-fence goals, in document order."""
    text = (root / ARTICLE).read_text()
    start = text.index(SECTION)
    end = text.index("\n## ", start + 3)
    section = text[start:end]
    goals = re.findall(r"```text\r?\n(.*?)\r?\n```", section, flags=re.S)
    if len(goals) != 2:
        raise ValueError("第二章第 09 节必须恰好有两个 text 围栏")
    for goal in goals:
        if not goal.startswith("/plan "):
            raise ValueError("第 09 节的目标必须保留 /plan 前缀")
    return goals[0], goals[1]


def slots(data):
    start = data.index(SECTION.encode())
    end = data.index(b"\n## ", start + 3)
    section = data[start:end]
    matches = list(re.finditer(
        rb"(?m)^!\[[^\]\r\n]*\]\((https://cdn\.example\.com/[^\s()]+\.png)\)\s*$",
        section,
    ))
    if len(matches) != 4:
        raise ValueError("第二章第 09 节截图位置不是四张，保留原教程")
    return [(start + m.start(1), start + m.end(1)) for m in matches]


def replace_article_copy(root, output):
    source = root / ARTICLE
    original = source.read_bytes()
    ranges = slots(original)
    for name in IMAGE_NAMES:
        image = output / name
        if not image.is_file() or image.stat().st_size <= 1000:
            raise RuntimeError("缺少可用的阶段截图：" + name)
    updated = original
    for (start, end), name in reversed(list(zip(ranges, IMAGE_NAMES))):
        updated = updated[:start] + name.encode() + updated[end:]
    target = output / ARTICLE.name
    atomic(target, updated)
    # Prove that only the four image-link byte ranges changed.
    rebuilt = updated
    for (start, end), name in zip(ranges, IMAGE_NAMES):
        if rebuilt[start:start + len(name)] != name.encode():
            raise RuntimeError("截图地址没有写到预期位置")
        rebuilt = rebuilt[:start] + original[start:end] + rebuilt[start + len(name):]
    if rebuilt != original:
        raise RuntimeError("教程副本改动了图片地址之外的正文")
    def shown(path):
        try:
            return str(path.relative_to(root))
        except ValueError:
            return str(path)
    return {
        "path": shown(target),
        "originalSha256": sha256_bytes(original),
        "copySha256": sha256_bytes(updated),
        "links": [
            {"name": name, "path": shown(output / name),
             "bytes": (output / name).stat().st_size,
             "sha256": sha256_bytes((output / name).read_bytes())}
            for name in IMAGE_NAMES
        ],
    }


def ledger_path(home):
    files = list((home / ".termpilot/history/raw").glob("*.jsonl"))
    if not files:
        return None
    return max(files, key=lambda p: p.stat().st_mtime)


def read_ledger(home):
    path = ledger_path(home)
    if path is None:
        return []
    rows = []
    for line in path.read_bytes().splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and isinstance(row.get("sequence"), int):
            rows.append(row)
    return rows


def last_sequence(rows):
    return max((r.get("sequence", 0) for r in rows), default=0)


def tool_pairs(rows):
    names = {}
    pairs = []
    for row in rows:
        msg = row.get("message") or {}
        for call in msg.get("toolCalls") or []:
            if not isinstance(call, dict):
                continue
            function = call.get("function") or {}
            call_id = call.get("id")
            if not call_id:
                continue
            args = function.get("arguments")
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except ValueError:
                    pass
            names[call_id] = {
                "name": function.get("name") or call.get("name"),
                "arguments": args,
                "actor": row.get("actor"),
                "sequence": row.get("sequence"),
            }
        if row.get("event") == "tool_result":
            call_id = msg.get("toolCallId")
            meta = names.get(call_id, {})
            pairs.append({
                "callId": call_id,
                "name": meta.get("name"),
                "arguments": meta.get("arguments"),
                "body": message_text(msg),
                "actor": row.get("actor"),
                "sequence": row.get("sequence"),
                "timestamp": row.get("timestamp"),
            })
    return pairs


def plan_responses(rows):
    found = []
    for row in rows:
        if row.get("actor") == "planner" and row.get("source") == "llm_response":
            plan = parse_plan_text(message_text(row.get("message") or {}))
            if plan is not None:
                found.append({"plan": plan, "sequence": row.get("sequence")})
    return found


def plan_batches_text(batches):
    if not batches:
        return "无"
    return " / ".join("[" + ", ".join(batch) + "]" for batch in batches)


def find_hello(work):
    demo = work / "demo"
    sources = list(demo.rglob("Hello.java")) if demo.is_dir() else []
    classes = list(demo.rglob("Hello.class")) if demo.is_dir() else []
    return sources, classes


def write_execution_log(output, rows, report):
    calls = tool_pairs(rows)
    log = {
        "planningResponses": [
            {"sequence": item["sequence"], "plan": item["plan"]}
            for item in plan_responses(rows)
        ],
        "taskEvents": [
            {"sequence": row.get("sequence"), "timestamp": row.get("timestamp"),
             "actor": row.get("actor"), "source": row.get("source"),
             "event": row.get("event")}
            for row in rows
            if str(row.get("actor") or "").startswith("task:")
        ],
        "toolCalls": [
            {"sequence": item["sequence"], "actor": item["actor"], "name": item["name"],
             "arguments": item["arguments"], "result": (item["body"] or "")[:8000]}
            for item in calls
        ],
        "runResults": [
            {"sequence": row.get("sequence"), "content": message_text(row.get("message") or {})[:12000]}
            for row in rows
            if row.get("source") in ("run_result", "run_error")
        ],
    }
    atomic(output / "execution-log.json", json.dumps(log, ensure_ascii=False, indent=2).encode())
    report["executionLog"] = "execution-log.json"
    return log


def verify_serial(work, rows):
    plans = plan_responses(rows)
    plan = plans[-1]["plan"] if plans else None
    tasks = plan_tasks(plan)
    batches = compute_batches(tasks)
    serial_shape = bool(tasks) and len(tasks) >= 2 and batches is not None and all(len(b) == 1 for b in batches)
    sources, classes = find_hello(work)
    source_text = sources[0].read_text(errors="replace") if sources else ""
    pairs = tool_pairs(rows)
    exec_calls = [p for p in pairs if p.get("name") == "execute_command"]
    commands = []
    for item in exec_calls:
        args = item.get("arguments")
        if isinstance(args, dict) and isinstance(args.get("command"), str):
            commands.append(args["command"])
    output_ok = False
    for item in exec_calls:
        body = item.get("body") or ""
        command = ""
        args = item.get("arguments")
        if isinstance(args, dict):
            command = str(args.get("command") or "")
        if "exit code: 0" in body and re.search(r"(?m)^Hello World\s*$", body) and re.search(r"\bjava\s", command):
            output_ok = True
    run_result = "\n".join(message_text(row.get("message") or {}) for row in rows
                           if row.get("source") == "run_result")
    result_ok = "计划执行完成" in run_result
    return {
        "plan": plan,
        "tasks": tasks,
        "batches": batches,
        "planShape": serial_shape,
        "batchesText": plan_batches_text(batches),
        "sourceFiles": [str(p.relative_to(work)) for p in sources],
        "classFiles": [str(p.relative_to(work)) for p in classes],
        "sourceHasHelloWorld": "Hello World" in source_text and "class Hello" in source_text,
        "commands": commands,
        "outputOk": output_ok,
        "runComplete": result_ok,
        "toolNames": [p.get("name") for p in pairs],
        "runResult": run_result[:4000],
    }


def verify_parallel(work, rows, full_text):
    plans = plan_responses(rows)
    plan = plans[-1]["plan"] if plans else None
    shape = assess_parallel(plan)
    batch_line = ""
    for line in full_text.splitlines():
        if "本轮并行执行" in line:
            batch_line = line.strip()
    pairs = tool_pairs(rows)
    run_result = "\n".join(message_text(row.get("message") or {}) for row in rows
                           if row.get("source") == "run_result")
    summary_id = shape.get("summaryId")
    table_text = ""
    if summary_id and len(shape.get("tasks") or []) == 5:
        index = [t["id"] for t in shape["tasks"]].index(summary_id)
        actor = f"task:task_{index + 1}"
        table_text = "\n".join(message_text(row.get("message") or {}) for row in rows
                               if row.get("actor") == actor and row.get("event") == "assistant")
    if not table_text:
        table_text = run_result
    return {
        "plan": plan,
        "shape": shape,
        "batchesText": plan_batches_text(shape.get("batches")),
        "batchLine": batch_line,
        "batchCount": int(re.search(r"本轮并行执行\s+(\d+)\s+个任务", batch_line).group(1))
        if re.search(r"本轮并行执行\s+(\d+)\s+个任务", batch_line) else 0,
        "planMentionsTable": plan_mentions_table(plan),
        "tableOutput": has_markdown_table(table_text),
        "tableExcerpt": table_text[:4000],
        "runComplete": "计划执行完成" in run_result,
        "runResult": run_result[:4000],
        "toolNames": [p.get("name") for p in pairs],
    }


def verify_normal(rows, status_line):
    plans = plan_responses(rows)
    plan = plans[-1]["plan"] if plans else None
    pairs = tool_pairs(rows)
    read_results = [
        p for p in pairs
        if p.get("name") == "read_file" and isinstance(p.get("arguments"), dict)
        and "README.md" in str(p["arguments"].get("path") or "")
    ]
    command_results = [
        p for p in pairs
        if p.get("name") == "execute_command" and isinstance(p.get("arguments"), dict)
        and "README" in str(p["arguments"].get("command") or "")
    ]
    run_result = "\n".join(message_text(row.get("message") or {}) for row in rows
                           if row.get("source") == "run_result")
    read_ok = any("TermPilot" in (p.get("body") or "") for p in read_results + command_results)
    read_ok = read_ok or "TermPilot" in run_result
    return {
        "statusLine": status_line,
        "plan": plan,
        "tasks": plan_tasks(plan),
        "readFileCalls": len(read_results),
        "commandCalls": len(command_results),
        "readOk": read_ok,
        "runComplete": "计划执行完成" in run_result,
        "runResult": run_result[:4000],
    }


def finalize_video(output, report):
    recording = next(iter(output.glob("*.webm")), None)
    if recording is None:
        report["videoSeconds"] = 0
        return
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise RuntimeError("缺少 ffmpeg/ffprobe，无法生成并校验 MP4")
    target = output / "session.mp4"
    subprocess.run([
        ffmpeg, "-nostdin", "-loglevel", "error", "-i", str(recording),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(target),
    ], check=True, timeout=240)
    duration = float(subprocess.check_output([
        ffprobe, "-v", "error", "-show_entries", "format=duration",
        "-of", "default=nk=1:nw=1", str(target),
    ], text=True).strip())
    report["videoSeconds"] = duration
    report["video"] = "session.mp4"
    if duration <= 1:
        raise RuntimeError("录屏时长不足 1 秒")
    recording.unlink(missing_ok=True)


def write_live_state(live, report):
    state = {
        "phase": report.get("phase") or ("已完成" if report.get("status") == "passed" else "运行结束"),
        "status": report.get("status"),
        "run": report.get("run"),
        "dir": report.get("relativeDirectory"),
        "images": {name.rsplit(".", 1)[0]: name for name in IMAGE_NAMES
                   if (report.get("screenshots") or {}).get(name.rsplit(".", 1)[0])},
        "article": report.get("articleCopy", {}).get("path"),
        "videoSeconds": report.get("videoSeconds"),
        "error": report.get("error"),
        "updated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    atomic(live / "state.json", json.dumps(state, ensure_ascii=False).encode())


def wait_result(terminal, home, baseline_seq, timeout, what):
    terminal.wait(lambda: any(row.get("sequence", 0) > baseline_seq
                              and row.get("source") in ("run_result", "run_error")
                              for row in read_ledger(home)), timeout, what)
    return [row for row in read_ledger(home) if row.get("sequence", 0) > baseline_seq]


def execute(terminal, root, work, home, source_dir, output, report):
    serial_goal, parallel_goal = chapter_goals(root)
    report["serialGoal"] = serial_goal
    report["parallelGoal"] = parallel_goal
    terminal.phase = "等待 Bash 初始提示符"
    stamp = work / STAMP
    # Bash's first PROMPT_COMMAND writes the previous shell status. Wait for
    # both the prompt and that initial stamp before deleting it; otherwise a
    # stale 0 can be mistaken for Maven's exit code.
    wait_shell_ready(terminal, stamp, 30, "Bash 初始提示符")
    report["initialShellStatus"] = int(stamp.read_text().strip())
    stamp.unlink()
    terminal.phase = "编译本机 TermPilot"
    terminal.enter("mvn clean package")
    wait_shell_ready(terminal, stamp, 360, "Maven 返回")
    report["buildExit"] = int(stamp.read_text().strip())
    report["jarExists"] = (work / "target/termpilot-1.0-SNAPSHOT.jar").is_file()
    if report["buildExit"] != 0:
        raise RuntimeError("Maven 构建失败")
    if not report["jarExists"]:
        raise RuntimeError("Maven 没有生成 TermPilot jar")

    terminal.phase = "启动真实的 TermPilot"
    stamp.unlink(missing_ok=True)
    terminal.write("\x0c")
    terminal.enter(JAVA_CMD)
    terminal.wait(lambda: ledger_path(home) is not None and "TermPilot" in terminal.visible_text(),
                  90, "TermPilot 启动画面")
    terminal.settle(1.2)
    report["startupSeen"] = "TermPilot" in terminal.visible_text()

    baseline_seq = last_sequence(read_ledger(home))
    terminal.phase = "串行计划：生成并审阅"
    review_count = terminal.full_text().count("计划已生成")
    terminal.enter(serial_goal)
    terminal.wait(lambda: terminal.full_text().count("计划已生成") > review_count
                  and "I：输入补充要求" in terminal.visible_text(), 240, "串行计划审阅")
    terminal.capture(IMAGE_NAMES[0].rsplit(".", 1)[0])
    report["serialReviewCaptured"] = True
    terminal.phase = "串行计划：执行"
    terminal.enter("ENTER")
    serial_rows = wait_result(terminal, home, baseline_seq, 420, "串行计划执行完成")
    report["serial"] = verify_serial(work, serial_rows)
    if any(row.get("source") == "run_error" for row in serial_rows):
        raise RuntimeError("串行计划执行报错")
    wait_prompt(terminal, "串行计划返回输入框")

    baseline_seq = last_sequence(read_ledger(home))
    terminal.phase = "并行 DAG：生成并审阅"
    count_before = terminal.full_text().count("计划已生成")
    terminal.enter(parallel_goal)
    report["parallelInputMode"] = terminal.last_input_mode
    terminal.wait(lambda: terminal.full_text().count("计划已生成") > count_before
                  and "I：输入补充要求" in terminal.visible_text(), 240, "并行 DAG 计划审阅")
    terminal.capture(IMAGE_NAMES[1].rsplit(".", 1)[0])
    report["parallelReviewCaptured"] = True

    terminal.phase = "并行 DAG：按 I 修改计划"
    count_before = terminal.full_text().count("计划已生成")
    revision_seq = last_sequence(read_ledger(home))
    terminal.enter("I")
    terminal.enter(FEEDBACK)
    terminal.wait(lambda: "正在按补充要求修改计划" in terminal.visible_text(), 60, "计划修改开始")
    wait_new_planner_response(terminal, home, revision_seq, 240, "修改后的新规划事件")
    report["revisionNewPlanEvent"] = True
    report["revisionBaselineSequence"] = revision_seq
    terminal.wait(lambda: terminal.full_text().count("计划已生成") > count_before
                  and "I：输入补充要求" in terminal.visible_text(), 240, "修改后的计划审阅")
    terminal.capture(IMAGE_NAMES[2].rsplit(".", 1)[0])
    report["revisedReviewCaptured"] = True
    terminal.phase = "并行 DAG：执行"
    terminal.enter("ENTER")
    parallel_rows = wait_result(terminal, home, baseline_seq, 420, "并行 DAG 执行完成")
    report["parallel"] = verify_parallel(work, parallel_rows, terminal.full_text())
    if any(row.get("source") == "run_error" for row in parallel_rows):
        raise RuntimeError("并行 DAG 执行报错")
    wait_prompt(terminal, "并行 DAG 返回输入框")

    terminal.phase = "Shift+Tab 切到 plan 模式"
    terminal.enter("SHIFT_TAB")
    status_line = next((line.strip() for line in terminal.visible_text().splitlines()
                        if "PLAN shift+tab to cycle" in line), "")
    report["planModeStatus"] = status_line
    baseline_seq = last_sequence(read_ledger(home))
    terminal.phase = "plan 模式：普通任务"
    count_before = terminal.full_text().count("计划已生成")
    terminal.enter(NORMAL_TASK)
    terminal.wait(lambda: terminal.full_text().count("计划已生成") > count_before
                  and "I：输入补充要求" in terminal.visible_text(), 240, "plan 模式普通任务的计划审阅")
    terminal.capture(IMAGE_NAMES[3].rsplit(".", 1)[0])
    report["normalReviewCaptured"] = True
    terminal.phase = "plan 模式：执行普通任务"
    terminal.enter("ENTER")
    normal_rows = wait_result(terminal, home, baseline_seq, 420, "plan 模式普通任务执行完成")
    report["normal"] = verify_normal(normal_rows, status_line)
    if any(row.get("source") == "run_error" for row in normal_rows):
        raise RuntimeError("plan 模式普通任务执行报错")
    wait_prompt(terminal, "普通任务返回输入框")

    terminal.phase = "退出 TermPilot 并核对退出码"
    stamp.unlink(missing_ok=True)
    terminal.enter("/exit")
    terminal.wait(lambda: stamp.is_file() and stamp.read_text().strip() != "", 30, "TermPilot 返回 Shell")
    report["javaExit"] = int(stamp.read_text().strip())
    terminal.phase = "核验素材并生成本地教程副本"
    rows = read_ledger(home)
    report["ledgerEntries"] = len(rows)
    write_execution_log(output, rows, report)
    report["serialExit"] = bool(report.get("serial", {}).get("runComplete"))
    report["parallelExit"] = bool(report.get("parallel", {}).get("runComplete"))
    report["normalExit"] = bool(report.get("normal", {}).get("runComplete"))


def shell_ready(text, stamp):
    if SHELL_PROMPT not in text or not stamp.is_file():
        return False
    try:
        return bool(stamp.read_text().strip())
    except OSError:
        return False


def wait_shell_ready(terminal, stamp, timeout, what):
    terminal.wait(lambda: shell_ready(terminal.visible_text(), stamp), timeout, what)


def wait_prompt(terminal, what):
    def ready():
        lines = [line.strip() for line in terminal.visible_text().splitlines()]
        tail = lines[-18:]
        input_ok = any(
            line.startswith("*") and (
                line == "*" or "@path" in line or "@image" in line or "message /" in line
            )
            for line in tail
        )
        status_ok = any("shift+tab to cycle" in line for line in tail[-10:])
        idle_ok = any(re.search(r"\bidle\b", line, flags=re.I) for line in tail[-10:])
        return input_ok and (status_ok or idle_ok)
    terminal.wait(ready, 30, what)
    terminal.settle(0.5)


def wait_new_planner_response(terminal, home, baseline_seq, timeout, what):
    def ready():
        return any(row.get("sequence", 0) > baseline_seq
                   and row.get("actor") == "planner"
                   and row.get("source") == "llm_response"
                   for row in read_ledger(home))
    terminal.wait(ready, timeout, what)
