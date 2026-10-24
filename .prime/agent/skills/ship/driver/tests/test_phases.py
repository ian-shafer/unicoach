import asyncio
import tempfile
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, make_repo, make_rs

from ship_driver import phases
from ship_driver.dials import Dials
from ship_driver.run import Run, ScriptFailed


async def until_ask(run, n):
    while (run.open_ask() or {}).get("n") != n:
        await asyncio.sleep(0.01)


class Design(unittest.IsolatedAsyncioTestCase):
    async def test_an_rfc_missing_a_tier0_section_is_asked_for_again(self):
        tmp = tempfile.mkdtemp()
        repo = make_repo(tmp)
        run = Run(make_rs(tmp, repo, RFC_NUM="1"), FakeHarness(tmp=tmp), fixtures.TOOLS)
        rfc = repo / "rfc" / "1-x.md"
        rfc.parent.mkdir()
        rfc.write_text("# RFC 1\n\n## Detailed Design\n\n## Files Modified\n\n## Implementation Plan\n")
        task = asyncio.ensure_future(phases.run_design(run, Dials("rfc", "full")))
        await asyncio.wait_for(until_ask(run, 1), 2)
        run.answer("design", {"path": str(rfc)})
        await asyncio.wait_for(until_ask(run, 2), 2)
        self.assertIn("lacks ## Tests", run.open_ask()["line"])
        rfc.write_text(rfc.read_text() + "\n## Tests\n")
        run.answer("design", {"path": str(rfc)})
        self.assertEqual(await asyncio.wait_for(task, 2), phases.Next("approve"))
        self.assertIn(f"RFC_PATH={rfc}", (run.rs / "state").read_text())

    async def test_an_rfc_path_with_no_file_is_asked_for_as_missing_not_as_lacking_sections(self):
        tmp = tempfile.mkdtemp()
        run = Run(make_rs(tmp, make_repo(tmp), RFC_NUM="1"), FakeHarness(tmp=tmp), fixtures.TOOLS)
        task = asyncio.ensure_future(phases.run_design(run, Dials("rfc", "full")))
        await asyncio.wait_for(until_ask(run, 1), 2)
        run.answer("design", {"path": "rfc/1-typo.md"})
        await asyncio.wait_for(until_ask(run, 2), 2)
        self.assertIn("rfc/1-typo.md does not exist", run.open_ask()["line"])
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


class Scripts(unittest.IsolatedAsyncioTestCase):
    """Phase steps against one stubbed ship-* script, the real ones for the rest."""

    def make(self, **stubs):
        tmp = tempfile.mkdtemp()
        scripts = Path(tmp) / "scripts"
        for real in fixtures.REAL_SCRIPTS.glob("ship-*"):
            fixtures.write_exec(scripts / real.name, stubs.get(real.name, f'exec "{real}" "$@"'))
        self.repo = make_repo(tmp)
        rs = make_rs(tmp, self.repo)
        (rs / "brief.md").write_text("add a greeting script\n")
        return Run(rs, FakeHarness(tmp=tmp), fixtures.tools(scripts=scripts))

    async def test_a_scope_check_that_faults_raises_and_resets_nothing(self):
        run = self.make(**{"ship-verify-scope": 'echo "[FATAL] [abc] is not an ancestor of HEAD" >&2; exit 1'})
        with self.assertRaisesRegex(ScriptFailed, "(?s)naming no denied path.*not an ancestor"):
            await asyncio.wait_for(phases.run_implement(run, Dials("none", "none")), 20)
        self.assertFalse([e for e in run.events() if e.get("cmd", "").startswith("ship-recover")])
        self.assertIsNone(run.open_ask())

    async def test_a_rebase_fault_with_no_rebase_in_progress_raises_with_its_stderr(self):
        run = self.make(**{"ship-rebase": 'echo "[FATAL] cannot resolve [main]" >&2; exit 1'})
        with self.assertRaisesRegex(ScriptFailed, "(?s)no rebase in progress.*cannot resolve"):
            await asyncio.wait_for(phases.rebase(run), 5)

    async def test_a_rebase_conflict_is_asked_with_the_scripts_stderr(self):
        run = self.make(**{"ship-rebase": 'echo "[FATAL] rebase hit a conflict and is LEFT IN PROGRESS" >&2; exit 1'})
        (self.repo / ".git" / "rebase-merge").mkdir()
        task = asyncio.ensure_future(phases.rebase(run))
        await asyncio.wait_for(until_ask(run, 1), 5)
        self.assertEqual(run.open_ask()["kind"], "rebase-conflict")
        self.assertIn("LEFT IN PROGRESS", run.open_ask()["context"]["stderr"])
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
