"""Run chapter 2 in an isolated copy of the TermPilot checkout.

The temporary worktree contains pom.xml, src and README.md. The real repository,
the original tutorial and the existing plugins are never edited. The run writes
only to one new directory under cases/paiwarp-creator/runs/.
"""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

from creator_chapter2 import (
    ARTICLE, IMAGE_NAMES, atomic, execute, finalize_video, read_ledger,
    replace_article_copy, sha256_bytes, write_execution_log, write_live_state,
)
from creator_terminal import Terminal


def iso_now():
    return datetime.now().astimezone().isoformat()


def git_head(root):
    try:
        return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"],
                                       text=True, timeout=5).strip()
    except Exception:
        return None


def build_env(home):
    env = os.environ.copy()
    env.update({
        "BASH_SILENCE_DEPRECATION_WARNING": "1",
        "TERM": "xterm-256color",
        "COLORTERM": "truecolor",
        "LANG": "en_US.UTF-8",
        "LC_ALL": "en_US.UTF-8",
        "PS1": "paiwarp-creator $ ",
        "HISTFILE": "/dev/null",
        "PAIWARP_CREATOR_HOME": str(home),
        "PROMPT_COMMAND": "echo $? > .paiwarp-creator-last-status",
    })
    return env


def summarize_checks(report, output):
    serial = report.get("serial") or {}
    parallel = report.get("parallel") or {}
    normal = report.get("normal") or {}
    shape = parallel.get("shape") or {}
    images = [output / name for name in IMAGE_NAMES]
    checks = {
        "build": report.get("buildExit") == 0 and bool(report.get("jarExists")),
        "startup": bool(report.get("startupSeen")),
        "serialPlan": bool(serial.get("planShape")) and bool(serial.get("runComplete")),
        "serialOutput": bool(serial.get("sourceHasHelloWorld")) and bool(serial.get("classFiles"))
        and bool(serial.get("outputOk")),
        "parallelDag": bool(shape.get("compliant")),
        "parallelBatch": parallel.get("batchCount") == 4
        and bool(shape.get("batches"))
        and len(shape.get("batches") or []) == 2
        and len(shape.get("batches")[0]) == 4
        and len(shape.get("batches")[1]) == 1,
        "parallelPlanTable": bool(parallel.get("planMentionsTable")),
        "parallelOutputTable": bool(parallel.get("tableOutput")),
        "parallelExecution": bool(parallel.get("runComplete")),
        "parallelBracketedPaste": report.get("parallelInputMode") == "bracketed-paste",
        "revisionNewPlanEvent": bool(report.get("revisionNewPlanEvent")),
        "planModeAuto": bool(report.get("planModeStatus")) and bool(normal.get("plan")),
        "normalTask": bool(normal.get("readOk")) and bool(normal.get("runComplete"))
        and (int(normal.get("readFileCalls") or 0) + int(normal.get("commandCalls") or 0) > 0),
        "javaExit": report.get("javaExit") == 0,
        "screenshots": all(image.is_file() and image.stat().st_size > 1000 for image in images),
        "video": float(report.get("videoSeconds") or 0) > 1,
        "articleCopy": bool(report.get("articleCopy")),
        "articleOriginalUnchanged": bool(report.get("articleOriginalUnchanged")),
    }
    return checks


def run(root, output, live, xterm):
    original = (root / ARTICLE).read_bytes()
    report = {
        "schema": 1,
        "chapter": 2,
        "status": "running",
        "workspace": str(root),
        "run": output.name,
        "relativeDirectory": str(output.relative_to(root)),
        "started": iso_now(),
        "article": str(ARTICLE),
        "articleOriginalSha256": sha256_bytes(original),
        "phase": "准备临时工作区",
        "tools": {name: shutil.which(name) for name in ("mvn", "java", "ffmpeg", "ffprobe")},
        "revision": git_head(root),
    }
    terminal = None
    rows = []
    try:
        with tempfile.TemporaryDirectory(prefix="paiwarp-creator-ch02-") as folder:
            work = Path(folder)
            shutil.copytree(root / "src", work / "src")
            shutil.copy2(root / "pom.xml", work / "pom.xml")
            shutil.copy2(root / "README.md", work / "README.md")
            with tempfile.TemporaryDirectory(prefix="paiwarp-creator-home-") as private_home:
                home = Path(private_home)
                report["temporaryWorktree"] = str(work)
                report["privateHome"] = str(home)
                try:
                    with Terminal(work, output, live, xterm, build_env(home)) as terminal:
                        try:
                            execute(terminal, root, work, home, root, output, report)
                        except Exception:
                            try:
                                terminal.capture("failure")
                            except Exception:
                                pass
                            raise
                finally:
                    rows = read_ledger(home)
            report["screenshots"] = {
                name.rsplit(".", 1)[0]: (output / name).stat().st_size
                for name in IMAGE_NAMES if (output / name).is_file()
            }
            report["images"] = [name for name in IMAGE_NAMES if (output / name).is_file()]
            try:
                finalize_video(output, report)
            except Exception as exc:
                report["videoError"] = f"{type(exc).__name__}: {exc}"
            try:
                report["articleCopy"] = replace_article_copy(root, output)
            except Exception as exc:
                report["articleError"] = f"{type(exc).__name__}: {exc}"
            report["articleOriginalUnchanged"] = sha256_bytes((root / ARTICLE).read_bytes()) == report["articleOriginalSha256"]
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if rows:
            try:
                write_execution_log(output, rows, report)
            except Exception as exc:
                report.setdefault("error", f"执行日志写入失败：{type(exc).__name__}: {exc}")
        if "screenshots" not in report:
            report["screenshots"] = {
                name.rsplit(".", 1)[0]: (output / name).stat().st_size
                for name in IMAGE_NAMES if (output / name).is_file()
            }
        checks = summarize_checks(report, output)
        report["checks"] = checks
        if report.get("status") != "failed":
            if all(checks.values()):
                report["status"] = "passed"
            else:
                failed = [name for name, ok in checks.items() if not ok]
                report["status"] = "failed"
                detail = [name for name in ("videoError", "articleError") if report.get(name)]
                report["error"] = "验证未通过：" + ", ".join(failed)
                if detail:
                    report["error"] += "；" + "；".join(f"{name}: {report[name]}" for name in detail)
        report["serialExit"] = bool((report.get("serial") or {}).get("runComplete"))
        report["parallelExit"] = bool((report.get("parallel") or {}).get("runComplete"))
        report["normalExit"] = bool((report.get("normal") or {}).get("runComplete"))
        report["ended"] = iso_now()
        report["phase"] = ("已完成" if report["status"] == "passed" else "运行结束")
        atomic(output / "result.json", json.dumps(report, ensure_ascii=False, indent=2).encode())
        write_live_state(live, report)
        print(json.dumps({
            "status": report["status"],
            "result": str(output / "result.json"),
            "error": report.get("error"),
            "articleCopy": report.get("articleCopy", {}).get("path"),
        }, ensure_ascii=False), flush=True)
    return report["status"] == "passed"


if __name__ == "__main__":
    root, output, live, xterm = map(Path, sys.argv[1:5])
    def cancel(*_args):
        raise RuntimeError("本次运行已取消")
    signal.signal(signal.SIGTERM, cancel)
    lock_dir = root / ".termpilot" / "paiwarp-creator"
    lock_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (lock_dir / "run.lock").open("a") as lock:
        import fcntl
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps({"status": "failed", "error": "已有第二章程在运行"}, ensure_ascii=False), flush=True)
            sys.exit(1)
        sys.exit(0 if run(root, output, live, xterm) else 1)
