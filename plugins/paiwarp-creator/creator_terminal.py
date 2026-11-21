"""One real PTY, an xterm.js viewport, screenshots and a browser recording.

This module is adapted from the repository's public PaiWarp PTY/xterm pipeline.
It keeps the terminal bytes, keyboard input and rendered screen in one place so
the chapter verifier can inspect the same screen a human sees.
"""
import codecs
import errno
import fcntl
import json
import os
import pty
import select
import signal
import struct
import sys
import termios
import time
from pathlib import Path

COLS, ROWS = 100, 34
PORTABLE = Path(__file__).resolve().parent
SPECIAL = {"ENTER", "I", "SHIFT_TAB"}


def input_mode(text):
    if text == "ENTER":
        return "enter"
    if text == "I":
        return "key"
    if text == "SHIFT_TAB":
        return "shift-tab"
    if "\n" in text:
        return "bracketed-paste"
    return "line"


def atomic(path, data):
    temp = path.with_name("." + path.name + ".part")
    temp.write_bytes(data)
    os.replace(temp, path)


class Terminal:
    def __init__(self, work, output, live, xterm, env):
        self.work, self.output, self.live = work, output, live
        self.xterm, self.env = xterm, env
        self.decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
        self.fd = self.pid = self.play = self.browser = self.context = self.page = None
        self.next_frame = 0
        self.paused = False
        self.turn = 0
        self.expected = self.agent_input = None
        self.phase = "准备临时工作区"
        self.last_input_mode = None
        self.status = "running"
        self.images = {}
        self.article = ""
        self.closed = False

    def __enter__(self):
        try:
            from playwright.sync_api import sync_playwright
            self.play = sync_playwright().start()
            self.browser = self.play.chromium.launch()
            self.context = self.browser.new_context(
                viewport={"width": 1160, "height": 872},
                record_video_dir=str(self.output),
                record_video_size={"width": 1160, "height": 872},
            )
            self.page = self.context.new_page()
            self.video = self.page.video
            self.page.goto((PORTABLE / "terminal.html").as_uri())
            self.page.add_style_tag(path=str(self.xterm / "css/xterm.css"))
            self.page.add_script_tag(path=str(self.xterm / "lib/xterm.js"))
            self.page.evaluate("""([cols,rows]) => {
                window.term = new Terminal({cols,rows,minimumContrastRatio:7,fontSize:18,lineHeight:1.12,
                    fontFamily:'Menlo, PingFang SC, monospace', scrollback:2000,
                    theme:{background:'#14171c',foreground:'#e4e9f1',cursor:'#95beff'}});
                term.open(document.querySelector('#term')); window.replies = [];
                term.onData(data=>replies.push(data));
            }""", [COLS, ROWS])
            self.pid, self.fd = pty.fork()
            if self.pid == 0:
                try:
                    os.chdir(self.work)
                    os.execve("/bin/bash", ["bash", "--noprofile", "--norc", "-i"], self.env)
                finally:
                    os._exit(127)
            fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
            return self
        except BaseException:
            self.close()
            raise

    def visible_text(self):
        return self.page.evaluate(r"""() => {const b=term.buffer.active; return Array.from(
            {length:term.rows},(_,i)=>b.getLine(b.viewportY+i)?.translateToString(true)||'').join('\n')}""")

    def full_text(self):
        return self.page.evaluate(r"""() => {const b=term.buffer.active; return Array.from(
            {length:b.length},(_,i)=>b.getLine(i)?.translateToString(true)||'').join('\n')}""")

    def write_state(self):
        payload = {
            "phase": self.phase,
            "paused": self.paused,
            "status": self.status,
            "turn": self.turn,
            "run": self.output.name,
            "dir": str(self.output),
            "images": self.images,
            "article": self.article,
            "updated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        }
        atomic(self.live / "state.json", json.dumps(payload, ensure_ascii=False).encode())

    def pump(self):
        if select.select([self.fd], [], [], 0.04)[0]:
            try:
                data = os.read(self.fd, 65536)
            except OSError as e:
                if e.errno != errno.EIO:
                    raise
                data = b""
            if not data:
                raise RuntimeError("本机终端已退出")
            self.page.evaluate("s=>new Promise(done=>term.write(s,done))", self.decoder.decode(data))
            for reply in self.page.evaluate("()=>replies.splice(0)"):
                self.write(reply)
        if time.monotonic() >= self.next_frame:
            self.next_frame = time.monotonic() + 0.8
            atomic(self.live / "frame.jpg", self.page.screenshot(type="jpeg", quality=80))
            self.write_state()
        # Only the creator Host can write input into this pipe.
        if select.select([0], [], [], 0)[0]:
            line = sys.stdin.readline()
            if not line:
                raise RuntimeError("桌面宿主连接已关闭")
            cmd = json.loads(line)
            if cmd.get("action") == "send" and cmd.get("turn") == self.turn and cmd.get("text") == self.expected:
                self.agent_input = cmd["text"]
            if cmd.get("action") == "stop":
                raise RuntimeError("用户停止了本次运行")
            if cmd.get("action") in ("pause", "resume"):
                self.paused = cmd["action"] == "pause"
            if self.paused and cmd.get("action") == "key":
                self.write(cmd.get("data", ""))

    def wait(self, predicate, seconds, what):
        deadline = time.monotonic() + seconds
        while True:
            start = time.monotonic()
            self.pump()
            if self.paused:
                deadline += time.monotonic() - start
                continue
            if predicate():
                return
            if time.monotonic() >= deadline:
                raise TimeoutError("等待超时：" + what)

    def pause(self, seconds):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            self.pump()
            time.sleep(0.02)

    def settle(self, seconds=0.8):
        until = time.monotonic() + seconds
        self.wait(lambda: time.monotonic() >= until, seconds + 5, "终端重绘")

    def write(self, text):
        data = memoryview(text.encode())
        while data:
            data = data[os.write(self.fd, data):]

    def type_text(self, text):
        # Human-speed keying keeps the recording readable and lets the screen redraw.
        for start in range(0, len(text), 5):
            self.wait(lambda: True, 5, "等待键盘")
            self.write(text[start:start + 5])
        self.write("\r")

    def paste_text(self, text):
        # TermPilot enables bracketed paste, so a multi-line prompt stays one input.
        self.write("\x1b[200~")
        for start in range(0, len(text), 8):
            self.pause(0.01)
            self.write(text[start:start + 8])
        self.write("\x1b[201~")
        self.pause(0.15)
        self.write("\r")

    def enter(self, expected):
        self.turn += 1
        self.expected, self.agent_input = expected, None
        print(json.dumps({
            "kind": "input",
            "turn": self.turn,
            "expected": expected,
            "screen": self.visible_text()[-3200:],
            "phase": self.phase,
        }, ensure_ascii=False), flush=True)
        self.wait(lambda: self.agent_input is not None, 240, "Harness Agent 输入下一步")
        text = self.agent_input
        self.expected = self.agent_input = None
        mode = input_mode(text)
        self.last_input_mode = mode
        if mode == "enter":
            self.write("\r")
        elif mode == "key":
            self.write("i")
            self.wait(lambda: "补充>" in self.visible_text(), 20, "补充要求输入框")
        elif mode == "shift-tab":
            self.write("\x1b[Z")
            self.wait(lambda: "PLAN shift+tab to cycle" in self.visible_text(), 15, "plan 模式状态栏")
        elif mode == "bracketed-paste":
            self.paste_text(text)
        else:
            self.type_text(text)

    def capture(self, name):
        self.settle(0.9)
        path = self.output / (name + ".png")
        self.page.screenshot(path=str(path))
        self.images[name] = path.name
        self.write_state()

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            if self.fd is not None:
                try:
                    foreground = os.tcgetpgrp(self.fd)
                except OSError:
                    foreground = self.pid
                for group in {foreground, self.pid}:
                    if group and group > 1:
                        try:
                            os.killpg(group, signal.SIGHUP)
                        except ProcessLookupError:
                            pass
                try:
                    os.close(self.fd)
                except OSError:
                    pass
                try:
                    os.waitpid(self.pid, 0)
                except ChildProcessError:
                    pass
        finally:
            try:
                if self.context:
                    self.context.close()
            finally:
                try:
                    if self.browser:
                        self.browser.close()
                finally:
                    if self.play:
                        self.play.stop()

    def __exit__(self, *exc):
        self.close()
