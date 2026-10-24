import asyncio
import tempfile
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, make_repo, make_rs

import ship_driver
from ship_driver import land
from ship_driver.dials import Dials
from ship_driver.run import Run, ScriptFailed


def stub_scripts(tmp, **stubs) -> Path:
    """The real ship-* scripts, each replaced by its stub when one is given."""
    scripts = Path(tmp) / "scripts"
    for real in fixtures.REAL_SCRIPTS.glob("ship-*"):
        fixtures.write_exec(scripts / real.name, stubs.get(real.name, f'exec "{real}" "$@"'))
    return scripts


class Lock(unittest.IsolatedAsyncioTestCase):
    def make(self, **stubs):
        tmp = tempfile.mkdtemp()
        return Run(make_rs(tmp, make_repo(tmp)), FakeHarness(tmp=tmp),
                   fixtures.tools(scripts=stub_scripts(tmp, **stubs)))

    async def test_a_lock_fault_raises_with_its_stderr_instead_of_waiting_forever(self):
        run = self.make(**{"ship-lock": 'echo "[FATAL] VAR_DIR is not set in [.env.dev]" >&2; exit 1'})
        with self.assertRaisesRegex(ScriptFailed, "VAR_DIR is not set"):
            await asyncio.wait_for(land.land_sequence(run, "subject"), 5)

    async def test_a_refused_release_raises_and_is_never_journaled_as_released(self):
        run = self.make(**{"ship-lock": 'echo "[FATAL] land lock is held by [run-b], not [run-a]" >&2; exit 1'})
        with self.assertRaisesRegex(ScriptFailed, "held by \\[run-b\\]"):
            await asyncio.wait_for(land.release_and_ask(run, "hook-failed", "x"), 5)
        self.assertFalse([e for e in run.events() if e["ev"] == "lock-released"])


class Entry(unittest.IsolatedAsyncioTestCase):
    def tools(self, **stubs):
        self.tmp = tempfile.mkdtemp()
        return fixtures.tools(original=make_repo(self.tmp), scripts=stub_scripts(self.tmp, **stubs))

    async def test_a_ship_status_that_faults_is_not_read_as_no_live_run(self):
        tools = self.tools(**{"ship-status": 'echo "[FATAL] not inside a git repository" >&2; exit 1'})
        with self.assertRaisesRegex(ScriptFailed, "not inside a git repository"):
            await ship_driver.resume(FakeHarness(tmp=self.tmp), tools)

    async def test_a_claim_that_prints_no_run_scratch_names_what_it_printed(self):
        tools = self.tools(**{"ship-status": 'echo "[FATAL] no live ship runs found under this repo" >&2; exit 1',
                              "ship-claim": 'echo "CLAIMED=nothing"'})
        with self.assertRaisesRegex(ScriptFailed, "(?s)printed no RUN_SCRATCH=.*CLAIMED=nothing"):
            await ship_driver.start("x", Dials("none", "none"), slug="demo", harness=FakeHarness(tmp=self.tmp),
                                    tools=tools)

    async def test_the_slug_goes_with_design_none_and_only_with_it(self):
        tools = self.tools(**{"ship-claim": 'echo "claimed" >&2; exit 9'})
        for dials, slug in ((Dials("none", "none"), None), (Dials("rfc", "full"), "demo")):
            with self.assertRaisesRegex(ValueError, "takes", msg=str(dials)):
                await ship_driver.start("x", dials, slug=slug, harness=FakeHarness(tmp=self.tmp), tools=tools)

    def test_dials_missing_from_the_state_file_are_named(self):
        tmp = tempfile.mkdtemp()
        rs = make_rs(tmp, make_repo(tmp))
        (rs / "state").write_text((rs / "state").read_text().replace("DIAL_DESIGN=none\n", ""))
        with self.assertRaisesRegex(RuntimeError, "state lacks DIAL_DESIGN"):
            ship_driver._dials_from_state(Run(rs, FakeHarness(tmp=tmp), fixtures.TOOLS))
