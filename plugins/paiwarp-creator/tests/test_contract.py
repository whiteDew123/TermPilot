import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ContractTest(unittest.TestCase):
    def test_manifest(self):
        data = json.loads((ROOT / "package.json").read_text())
        self.assertEqual(data["name"], "paiwarp-dsh-creator")
        self.assertEqual(data["dsh"]["bundle"]["patch"], "./cordis.patch.yml")
        self.assertTrue(data["dsh"]["client"]["immediately"])
        self.assertIn("paiwarp_creator_run_chapter_two", (ROOT / "host.js").read_text())
        self.assertIn("paiwarp_creator_terminal_input", (ROOT / "host.js").read_text())

    def test_no_old_tools_or_codex_path(self):
        files = [ROOT / "host.js", ROOT / "client.js",
                 ROOT / "creator_runner.py", ROOT / "creator_terminal.py",
                 ROOT / "creator_chapter2.py", ROOT / "package.json",
                 ROOT / "cordis.patch.yml"]
        texts = "\n".join(path.read_text(errors="replace") for path in files)
        self.assertNotIn("~/.codex", texts)
        self.assertNotIn("paiwarp_run_chapter_one", texts)
        self.assertNotIn("paiwarp_terminal_input", texts)
        self.assertNotIn("paiwarp_creator_run_chapter_one", texts)

    def test_host_output_contract_and_deepseek_env_whitelist(self):
        result = subprocess.run(
            ["node", "tests/test_host_output.mjs"],
            cwd=ROOT, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        host = (ROOT / "host.js").read_text()
        self.assertIn("'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL'", host)
        self.assertNotIn("SEARCH_PROVIDER", host)
        self.assertNotIn("SEARXNG_URL", host)
        self.assertNotIn("TERMPILOT_AUTO_CLASSIFIER_MODEL", host)

    def test_client_registers_creator_surface(self):
        text = (ROOT / "client.js").read_text()
        self.assertIn("paiwarp-dsh-creator", text)
        self.assertIn("paiwarp_creator_run_chapter_two", text)
        self.assertIn("sidebar.right.pane.tab", text)
        self.assertIn("tool.call.toolview", text)

    def test_article_has_four_slots(self):
        sys.path.insert(0, str(ROOT))
        import creator_chapter2 as ch
        article = (ROOT.parents[1] / ch.ARTICLE).read_bytes()
        self.assertEqual(len(ch.slots(article)), 4)


if __name__ == "__main__":
    unittest.main()
