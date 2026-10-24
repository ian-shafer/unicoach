import tempfile
import unittest

import fixtures
from fixtures import FakeHarness, git, make_repo, make_rs, write_exec

from ship_driver.phases import suite_needed
from ship_driver.run import Run


class SuiteRule(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        tmp = tempfile.mkdtemp()
        self.repo = make_repo(tmp)
        # Reuse asks the REAL bin/gate-scope whether the move could change the suite's answer.
        write_exec(self.repo / "bin" / "gate-scope", f'exec "{fixtures.REPO_ROOT}/bin/gate-scope" "$@"')
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "--no-verify", "-m", "the real gate-scope")
        self.run = Run(make_rs(tmp, self.repo), FakeHarness(tmp=tmp), fixtures.TOOLS)

    def record(self, exit="0", cmd=None, tree=None, with_tree=True):
        cmd = cmd or fixtures.TOOLS.suite_cmd
        tree = tree or git(self.repo, "rev-parse", "HEAD^{tree}").strip()
        with open(self.run.rs / "suite-green.txt", "a") as file:
            file.write(f"exit={exit}\ncmd={cmd}\n" + (f"tree={tree}\n" if with_tree else ""))

    def commit(self, path, text="x\n"):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "--no-verify", "-m", path)

    async def needed(self):
        return (await suite_needed(self.run))[0]

    async def test_an_identical_tree_with_a_green_record_is_reused(self):
        self.record()
        self.assertFalse(await self.needed())

    async def test_the_last_record_decides_not_the_first(self):
        self.record(exit="1")
        self.record()
        self.assertFalse(await self.needed())

    async def test_a_red_record_reruns(self):
        self.record(exit="1")
        self.assertTrue(await self.needed())

    async def test_a_scoped_command_reruns(self):
        self.record(cmd=fixtures.TOOLS.suite_cmd + " rest-server")
        self.assertTrue(await self.needed())

    async def test_the_record_the_real_ship_run_writes_for_the_drivers_suite_is_reused(self):
        await self.run.ship_run("suite", ["bin/test"], gate=True)
        self.assertEqual(await suite_needed(self.run), (False, f"tree {git(self.repo, 'rev-parse', 'HEAD^{tree}').strip()} already passed"))

    async def test_a_dirty_tree_reruns(self):
        self.record()
        (self.repo / "README.md").write_text("edited\n")
        self.assertTrue(await self.needed())

    async def test_a_kotlin_move_reruns(self):
        self.record()
        self.commit("service/src/main/kotlin/A.kt")
        self.assertTrue(await self.needed())

    async def test_a_product_only_move_reuses(self):
        self.record()
        self.commit("product/0001-brief/brief.md")
        self.assertFalse(await self.needed())

    async def test_a_move_gate_scope_calls_markdown_docs_reuses(self):
        self.record()
        self.commit("rfc/1-x.md")
        self.commit("README.md", "edited\n")
        needed, reason = await suite_needed(self.run)
        self.assertFalse(needed, reason)
        self.assertIn("scope=markdown-docs", reason)

    async def test_an_unreadable_recorded_tree_reruns(self):
        self.record(tree="0" * 40)
        self.commit("README.md", "edited\n")
        needed, reason = await suite_needed(self.run)
        self.assertTrue(needed)
        self.assertRegex(reason, f"^the recorded tree {'0' * 40} cannot be compared: (.|\n)*read-tree(.|\n)* exited 128")

    async def test_a_record_naming_no_tree_reruns_for_that_reason(self):
        self.record(with_tree=False)
        self.assertEqual(await suite_needed(self.run), (True, "the last suite record names no tree"))

    async def test_no_record_runs(self):
        self.assertTrue(await self.needed())
