import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import creator_chapter2 as ch


def plan_with(tasks):
    return {"summary": "test", "tasks": tasks}


COMPLIANT = plan_with([
    {"id": "1", "description": "读取 pom.xml", "type": "FILE_READ", "dependencies": []},
    {"id": "2", "description": "列出 src/main/java", "type": "FILE_READ", "dependencies": []},
    {"id": "3", "description": "列出 src/test/java", "type": "FILE_READ", "dependencies": []},
    {"id": "4", "description": "读取 README.md 前 80 行", "type": "FILE_READ", "dependencies": []},
    {"id": "5", "description": "汇总并用表格输出", "type": "ANALYSIS", "dependencies": ["1", "2", "3", "4"]},
])


class PlanShapeTest(unittest.TestCase):
    def test_compliant_dag(self):
        result = ch.assess_parallel(COMPLIANT)
        self.assertTrue(result["compliant"], result["reason"])
        self.assertEqual(result["independentIds"], ["1", "2", "3", "4"])
        self.assertEqual(result["summaryId"], "5")
        self.assertEqual(result["batches"], [["1", "2", "3", "4"], ["5"]])

    def test_missing_edge_is_not_success(self):
        bad = json.loads(json.dumps(COMPLIANT))
        bad["tasks"][4]["dependencies"] = ["1", "2", "3"]
        result = ch.assess_parallel(bad)
        self.assertFalse(result["compliant"])
        self.assertIn("没有精确依赖四个独立任务", result["reason"])
        self.assertEqual(result["summaryDependencies"], ["1", "2", "3"])

    def test_extra_parallel_task_is_not_success(self):
        bad = json.loads(json.dumps(COMPLIANT))
        bad["tasks"].insert(4, {"id": "x", "description": "额外任务", "type": "ANALYSIS", "dependencies": []})
        bad["tasks"][5]["dependencies"] = ["1", "2", "3", "4"]
        result = ch.assess_parallel(bad)
        self.assertFalse(result["compliant"])
        self.assertIn("任务数是 6", result["reason"])

    def test_plan_parser_accepts_fenced_json(self):
        text = "```json\n" + json.dumps(COMPLIANT, ensure_ascii=False) + "\n```"
        self.assertEqual(ch.parse_plan_text(text)["tasks"][0]["id"], "1")


class TableTest(unittest.TestCase):
    def test_table(self):
        self.assertTrue(ch.has_markdown_table("| 项目 | 内容 |\n| --- | --- |\n| 构建 | Maven |"))
        self.assertFalse(ch.has_markdown_table("项目：Maven"))

    def test_revision_mentions_table(self):
        self.assertTrue(ch.plan_mentions_table({"summary": "汇总时用表格输出", "tasks": []}))
        no_table = json.loads(json.dumps(COMPLIANT))
        no_table["tasks"][4]["description"] = "汇总结果"
        self.assertFalse(ch.plan_mentions_table(no_table))


class ArticleCopyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        article = self.root / ch.ARTICLE
        article.parent.mkdir(parents=True)
        self.original = (
            "---\ntitle: 第二章\n---\n\n"
            "## 08、怎么进入计划模式\n\n![旧图](https://cdn.example.com/old.png)\n\n"
            "## 09、跑起来看看\n\n"
            "```bash\nmvn clean package\n```\n\n"
            "![串行](https://cdn.example.com/a.png)\n\n"
            "![并行](https://cdn.example.com/b.png)\n\n"
            "![修改](https://cdn.example.com/c.png)\n\n"
            "![plan](https://cdn.example.com/d.png)\n\n"
            "## 简历怎么写\n\n正文保持不变。\n"
        ).encode()
        article.write_bytes(self.original)
        self.output = self.root / "cases/paiwarp-creator/runs/run-1"
        self.output.mkdir(parents=True)
        for name in ch.IMAGE_NAMES:
            (self.output / name).write_bytes(b"\x89PNG\r\n\x1a\n" + b"x" * 1200)

    def tearDown(self):
        self.temp.cleanup()

    def test_only_four_links_change(self):
        report = ch.replace_article_copy(self.root, self.output)
        copied = (self.output / ch.ARTICLE.name).read_bytes()
        self.assertEqual((self.root / ch.ARTICLE).read_bytes(), self.original)
        self.assertEqual(report["originalSha256"], ch.sha256_bytes(self.original))
        for name in ch.IMAGE_NAMES:
            self.assertIn(name.encode(), copied)
        self.assertNotIn(b"https://cdn.example.com/a.png", copied)
        self.assertIn(b"https://cdn.example.com/old.png", copied)
        # Restore the four new links and compare every other byte.
        rebuilt = copied
        for (start, end), name in zip(ch.slots(self.original), ch.IMAGE_NAMES):
            rebuilt = rebuilt[:start] + self.original[start:end] + rebuilt[start + len(name):]
        self.assertEqual(rebuilt, self.original)

    def test_missing_stage_image_fails(self):
        (self.output / ch.IMAGE_NAMES[2]).unlink()
        with self.assertRaises(RuntimeError):
            ch.replace_article_copy(self.root, self.output)

    def test_slot_count_is_four(self):
        broken = self.original.replace(b"![plan](https://cdn.example.com/d.png)\n", b"")
        with self.assertRaises(ValueError):
            ch.slots(broken)


if __name__ == "__main__":
    unittest.main()


class EvidenceTest(unittest.TestCase):
    def test_serial_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            work = Path(folder)
            demo = work / "demo"
            demo.mkdir()
            (demo / "Hello.java").write_text('public class Hello { public static void main(String[] a) { System.out.println("Hello World"); } }')
            (demo / "Hello.class").write_bytes(b"class")
            call = {
                "sequence": 22, "actor": "task:task_3", "source": "llm_response",
                "event": "tool_call", "message": {"toolCalls": [{
                    "id": "c1", "function": {"name": "execute_command",
                    "arguments": '{"command":"cd demo && javac Hello.java && java Hello"}'}}]},
            }
            result = {
                "sequence": 23, "actor": "task:task_3", "event": "tool_result",
                "message": {"toolCallId": "c1", "content": "exit code: 0\nHello World"},
            }
            plan_row = {
                "sequence": 10, "actor": "planner", "source": "llm_response", "event": "assistant",
                "message": {"content": json.dumps(plan_with([
                    {"id": "1", "description": "写文件", "type": "FILE_WRITE", "dependencies": []},
                    {"id": "2", "description": "编译", "type": "COMMAND", "dependencies": ["1"]},
                    {"id": "3", "description": "运行", "type": "COMMAND", "dependencies": ["2"]},
                ]), ensure_ascii=False)},
            }
            done = {"sequence": 30, "source": "run_result", "actor": "plan-agent",
                    "message": {"content": "✅ 计划执行完成！"}}
            evidence = ch.verify_serial(work, [plan_row, call, result, done])
            self.assertTrue(evidence["planShape"])
            self.assertTrue(evidence["outputOk"])
            self.assertTrue(evidence["runComplete"])
            self.assertTrue(evidence["sourceHasHelloWorld"])

    def test_parallel_table_from_summary_actor(self):
        rows = [
            {"sequence": 10, "actor": "planner", "source": "llm_response", "event": "assistant",
             "message": {"content": json.dumps(COMPLIANT, ensure_ascii=False)}},
            {"sequence": 11, "actor": "task:task_5", "source": "llm_response", "event": "assistant",
             "message": {"content": "| 项目 | 内容 |\n| --- | --- |\n| 构建 | Maven |"}},
            {"sequence": 12, "actor": "plan-agent", "source": "run_result",
             "message": {"content": "✅ 计划执行完成！"}},
        ]
        evidence = ch.verify_parallel(None, rows, "⚡ 本轮并行执行 4 个任务: 1, 2, 3, 4")
        self.assertTrue(evidence["shape"]["compliant"])
        self.assertEqual(evidence["batchCount"], 4)
        self.assertTrue(evidence["planMentionsTable"])
        self.assertTrue(evidence["tableOutput"])

    def test_normal_read(self):
        rows = [
            {"sequence": 1, "actor": "planner", "source": "llm_response", "event": "assistant",
             "message": {"content": json.dumps(plan_with([
                 {"id": "1", "description": "读取 README.md 前 5 行", "type": "FILE_READ", "dependencies": []},
             ]), ensure_ascii=False)}},
            {"sequence": 2, "actor": "task:task_1", "event": "tool_call",
             "message": {"toolCalls": [{"id": "c", "function": {"name": "read_file",
                 "arguments": '{"path":"README.md"}'}}]}},
            {"sequence": 3, "actor": "task:task_1", "event": "tool_result",
             "message": {"toolCallId": "c", "content": "# TermPilot\n一个 Java Agent"}},
            {"sequence": 4, "actor": "plan-agent", "source": "run_result",
             "message": {"content": "✅ 计划执行完成！\n# TermPilot"}},
        ]
        evidence = ch.verify_normal(rows, "PLAN shift+tab to cycle")
        self.assertTrue(evidence["readOk"])
        self.assertTrue(evidence["runComplete"])


class RealArticleCopyTest(unittest.TestCase):
    def test_real_chapter_two_copy_has_four_local_links(self):
        root = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            for name in ch.IMAGE_NAMES:
                (output / name).write_bytes(b"\x89PNG\r\n\x1a\n" + b"x" * 1200)
            report = ch.replace_article_copy(root, output)
            text = (output / ch.ARTICLE.name).read_text()
            self.assertEqual(sum(text.count(name) for name in ch.IMAGE_NAMES), 4)
            self.assertEqual(report["originalSha256"], ch.sha256_bytes((root / ch.ARTICLE).read_bytes()))
            self.assertTrue(report["copySha256"])


class PromptAndInputTest(unittest.TestCase):
    class FakeTerminal:
        def __init__(self, text):
            self.text = text
            self.settled = 0
        def visible_text(self):
            return self.text
        def wait(self, predicate, timeout, what):
            if not predicate():
                raise AssertionError(what)
        def settle(self, seconds=0.8):
            self.settled += 1

    def test_prompt_accepts_right_prompt_and_status(self):
        term = self.FakeTerminal(
            "输出\n*  message / @path / @image\nAUTO shift+tab to cycle\n"
            "Auto Model · deepseek-flash · idle · ctx 10%"
        )
        ch.wait_prompt(term, "input")
        self.assertEqual(term.settled, 1)

    def test_prompt_requires_status_or_idle(self):
        term = self.FakeTerminal("*  message / @path / @image")
        with self.assertRaises(AssertionError):
            ch.wait_prompt(term, "input")

    def test_markdown_bullet_is_not_a_prompt(self):
        term = self.FakeTerminal("* 普通列表项\nAUTO shift+tab to cycle")
        with self.assertRaises(AssertionError):
            ch.wait_prompt(term, "input")

    def test_exact_star_with_idle_footer_is_a_prompt(self):
        term = self.FakeTerminal("输出\n*\nAuto Model · deepseek-flash · idle")
        ch.wait_prompt(term, "input")

    def test_shell_ready_needs_prompt_and_nonempty_stamp(self):
        with tempfile.TemporaryDirectory() as folder:
            stamp = Path(folder) / ".paiwarp-creator-last-status"
            self.assertFalse(ch.shell_ready("paiwarp-creator $ ", stamp))
            stamp.write_text("0\n")
            self.assertTrue(ch.shell_ready("paiwarp-creator $ ", stamp))
            stamp.write_text("\n")
            self.assertFalse(ch.shell_ready("paiwarp-creator $ ", stamp))
            stamp.write_text("0\n")
            self.assertFalse(ch.shell_ready("other $ ", stamp))

    def test_parallel_prompt_uses_bracketed_paste(self):
        from creator_terminal import input_mode
        root = Path(__file__).resolve().parents[3]
        serial, parallel = ch.chapter_goals(root)
        self.assertEqual(input_mode(parallel), "bracketed-paste")
        self.assertIn("\n", parallel)
        self.assertEqual(input_mode(serial), "line")
        self.assertEqual(input_mode("I"), "key")
        self.assertEqual(input_mode("SHIFT_TAB"), "shift-tab")

    def test_goals_match_section_09_text_fences_byte_for_byte(self):
        root = Path(__file__).resolve().parents[3]
        serial, parallel = ch.chapter_goals(root)
        article = (root / ch.ARTICLE).read_text()
        section = article[article.index(ch.SECTION):]
        section = section[:section.index("\n## ")]
        self.assertEqual(serial, "/plan 创建一个 Java 项目叫 demo，写一个 Hello 类输出 Hello World，然后编译运行")
        self.assertEqual(parallel, """/plan 请把任务拆成可并行的 DAG：
1. 读取 pom.xml
2. 列出 src/main/java 下的文件
3. 列出 src/test/java 下的文件
4. 读取 README.md 的前 80 行
5. 最后汇总以上结果，告诉我这个项目的构建方式、源码结构和测试情况。
要求：前 4 个任务彼此独立并行执行，最后 1 个任务依赖前 4 个任务。""")
        self.assertIn(f"```text\n{serial}\n```", section)
        self.assertIn(f"```text\n{parallel}\n```", section)
        self.assertTrue(serial.startswith("/plan "))
        self.assertTrue(parallel.startswith("/plan "))
        self.assertEqual(len(parallel.splitlines()), 7)


class RevisionEventTest(unittest.TestCase):
    class FakeTerminal:
        def visible_text(self):
            return ""
        def wait(self, predicate, timeout, what):
            if not predicate():
                raise AssertionError(what)

    def test_new_planner_event_must_be_after_revision_baseline(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            raw = home / ".termpilot/history/raw"
            raw.mkdir(parents=True)
            ledger = raw / "session.jsonl"
            ledger.write_text(json.dumps({
                "sequence": 10, "actor": "planner", "source": "llm_response",
                "message": {"content": "{}"},
            }) + "\n")
            term = self.FakeTerminal()
            with self.assertRaises(AssertionError):
                ch.wait_new_planner_response(term, home, 10, 1, "new plan")
            with ledger.open("a") as stream:
                stream.write(json.dumps({
                    "sequence": 11, "actor": "planner", "source": "llm_response",
                    "message": {"content": "{}"},
                }) + "\n")
            ch.wait_new_planner_response(term, home, 10, 1, "new plan")
