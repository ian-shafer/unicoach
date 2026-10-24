"""The machine end to end against a throwaway repo: real ship-* scripts, stub tools, a FakeHarness."""
import asyncio
import datetime
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, autopilot, git, make_repo, report_text

import ship_driver
from ship_driver import report as report_module
from ship_driver.dials import Dials


async def until_waiting_on_children(driver):
    """Cancel a task only once it waits on its implementer: a cancelled subprocess call leaves its process behind."""
    while driver.run.waiting_on is None or not driver.run.waiting_on.startswith("children"):
        await asyncio.sleep(0.01)


class EndToEnd(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = make_repo(self.tmp)
        self.tools = fixtures.tools(original=self.repo, scripts=fixtures.install_scripts(self.repo))
        self.saved = dict(os.environ)
        os.environ["VAR_DIR"] = str(Path(self.tmp) / "var")
        for key in ("GIT_DIR", "GIT_INDEX_FILE", "GIT_WORK_TREE"):
            os.environ.pop(key, None)

    def tearDown(self):
        ship_driver._DRIVERS.clear()
        os.environ.clear()
        os.environ.update(self.saved)

    async def drive(self, instruction, dials, answers, harness=None, slug="demo", timeout=60, brief=None):
        harness = harness or FakeHarness(tmp=self.tmp)
        box, log, stop = {}, [], asyncio.Event()
        pilot = asyncio.ensure_future(autopilot(box, answers, log, stop))
        driver = await ship_driver.start(instruction, dials, slug=slug, brief=brief, harness=harness, tools=self.tools)
        box["driver"] = driver
        try:
            await asyncio.wait_for(asyncio.shield(driver.task), timeout)
        finally:
            stop.set()
            await pilot
        return driver, harness, log

    async def test_a_mechanical_run_lands_within_its_budget_and_reports_spend_from_files(self):
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"report-prose": {"opening": "DONE — a greeting script exists.", "learning_candidates": "None"},
             "announce": {}})
        error = driver.run.dir / "error.txt"
        self.assertFalse(error.exists(), error.read_text() if error.exists() else "")
        self.assertEqual(log, ["report-prose", "announce"])
        self.assertLessEqual(len(log), 3, "the mechanical budget is 3 judgement turns")
        archive = self.repo / ".scratch" / "ship-archive" / "ship-demo"
        self.assertEqual(driver.run.rs.resolve(), archive.resolve())
        self.assertIn("src/a.sh", git(self.repo, "show", "--stat", "main"))
        report = (archive / "report.md").read_text()
        self.assertIn("DONE — a greeting script exists.", report)
        self.assertIn("## Spend", report)
        self.assertIn("Children: implementer 1", report)
        self.assertIn("ship-run invocations per caller: {'driver':", report)
        self.assertIn("PHASE=complete", (archive / "state").read_text())
        self.assertEqual(harness.heartbeats["hb-1"]["status"], "deleted")
        self.assertEqual(harness.heartbeats["hb-1"]["interval"], "*/30 * * * *")
        state = (archive / "state").read_text()
        self.assertNotRegex(state, r"(?m)^(HEARTBEAT_ID|DRIVER_SESSION|COORD_SESSION)=")
        self.assertEqual(driver.run.session()["driver_session"], "coord-1")
        tests = report.split("## Tests\n")[1].split("\n## ")[0]
        self.assertIn("`bin/test` exit=0", tests)

    async def test_a_feature_run_reviews_fixes_and_lands_within_its_budget(self):
        def behave(name, prompt, harness):
            if prompt.startswith("# Lens: code-review-dry-abstractions"):
                fixtures.path_in(prompt, r"Write your report to (\S+) and change").write_text(report_text(
                    "code-review-dry-abstractions", "SHOULD-FIX",
                    [("The greeting is spelled twice", "SHOULD-FIX", "src/a.sh:2"),
                     ("Consider a name", "ADVISORY", "src/a.sh:1")]))
                return 2
            return fixtures.default_behaviour(name, prompt, harness)

        def design(ask, driver):
            rfc = driver.run.root / "rfc" / f"{driver.run.get('RFC_NUM')}-greeting.md"
            rfc.parent.mkdir(exist_ok=True)
            rfc.write_text("# RFC 1 — Greeting\n\n## Detailed Design\n\n## Files Modified\n\n"
                           "## Implementation Plan\n\n## Tests\n")
            return {"path": str(rfc)}

        driver, harness, log = await self.drive(
            "build the greeting feature", Dials("rfc", "full"),
            {"design": design, "approval": {"approved": True, "decisions": ["D1 as written"]},
             "report-prose": {"opening": "DONE — greeting.", "learning_candidates": "None"}, "announce": {}},
            harness=FakeHarness(behave, tmp=self.tmp), slug=None)
        error = driver.run.dir / "error.txt"
        self.assertFalse(error.exists(), error.read_text() if error.exists() else "")
        self.assertEqual(log, ["design", "approval", "report-prose", "announce"])
        self.assertLessEqual(len(log), 12, "the feature budget is 12 judgement turns")
        rs = driver.run.rs
        ledger = (rs / "findings" / "lens-ledger.tsv").read_text()
        self.assertIn("skip\t3\tcode-review-concrete-names\tretired by RFC 256", ledger)
        self.assertIn("skip\t2\tcode-review-immutable-returns\tno lens of the typed group", ledger)
        fix = [s for s in harness.spawned if " fix child of " in s["dispatch"]]
        self.assertEqual(len(fix), 1)
        self.assertIn("The greeting is spelled twice", fix[0]["dispatch"])
        self.assertNotIn("Consider a name", fix[0]["dispatch"])
        report = (rs / "report.md").read_text()
        self.assertIn("code-review-dry-abstractions `src/a.sh:1` Consider a name", report)
        self.assertIn("RFC 1: Greeting", git(self.repo, "log", "-1", "--format=%s", "main"))

    async def test_a_failed_commit_releases_the_lock_before_it_asks(self):
        lock_states = []

        def hook_failed(ask, driver):
            status = subprocess.run([self.tools.scripts / "ship-lock", "status"], capture_output=True,
                                    text=True).stdout
            lock_states.append(status)
            os.environ["STUB_HOOK_EXIT"] = "0"
            return {}

        os.environ["STUB_HOOK_EXIT"] = "1"
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"hook-failed": hook_failed, "report-prose": {"opening": "DONE", "learning_candidates": "None"},
             "announce": {}})
        self.assertEqual(log, ["report-prose", "hook-failed", "announce"])
        self.assertIn("free", lock_states[0])
        events = driver.run.events()
        released = next(i for i, e in enumerate(events) if e["ev"] == "lock-released")
        asked = next(i for i, e in enumerate(events) if e["ev"] == "ask" and e["kind"] == "hook-failed")
        self.assertLess(released, asked)

    async def test_a_format_that_fails_at_land_releases_the_lock_and_asks_instead_of_committing(self):
        def hook_failed(ask, driver):
            os.environ["STUB_FORMAT_EXIT"] = "0"
            return {}

        os.environ["STUB_FORMAT_EXIT"] = "1"
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"hook-failed": hook_failed, "report-prose": {"opening": "DONE", "learning_candidates": "None"},
             "announce": {}})
        self.assertEqual(log, ["report-prose", "hook-failed", "announce"])
        ask = next(e for e in driver.run.events() if e["ev"] == "ask" and e["kind"] == "hook-failed")
        self.assertEqual(driver.run.events().index(ask) - 1,
                         next(i for i, e in enumerate(driver.run.events()) if e["ev"] == "lock-released"))

    async def test_ship_land_waits_out_a_lock_another_run_holds(self):
        lock = Path(os.environ["VAR_DIR"]) / "run" / "ship-land.lock"
        expires = []

        def prose(ask, driver):
            """Another run takes the lock just before this one's land sequence starts."""
            lock.mkdir(parents=True)
            (lock / "op").write_text("run-b\n")
            expires.append(int(time.time()) + 2)
            (lock / "expires-at").write_text(f"{expires[0]}\n")
            return {"opening": "DONE", "learning_candidates": "None"}

        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"), {"report-prose": prose, "announce": {}})
        self.assertIn("src/a.sh", git(self.repo, "show", "--stat", "main"))
        events = [e for e in driver.run.events() if e["ev"] == "subprocess"]
        acquired = next(e for e in events if e["cmd"] == "ship-lock acquire" and e["exit"] == 0)
        self.assertGreaterEqual(datetime.datetime.fromisoformat(acquired["at"]).timestamp(), expires[0])
        cmds = [e["cmd"] for e in events]
        self.assertLess(cmds.index("ship-lock acquire"), cmds.index("ship-squash"))

    async def test_the_implementer_reads_the_instruction_and_the_plan(self):
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}},
            brief="Plan: one file, src/a.sh.")
        implementer = next(s for s in harness.spawned if s["dispatch"].startswith("You are the implementer"))
        self.assertIn("add a greeting script", implementer["dispatch"])
        self.assertIn("Plan: one file, src/a.sh.", implementer["dispatch"])

    async def test_land_rewrites_the_tests_dial_after_its_rebase(self):
        def prose(ask, driver):
            os.environ["STUB_GRADLE"] = "no"  # what gate-scope says about the base as landed
            return {"opening": "DONE", "learning_candidates": "None"}

        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"), {"report-prose": prose, "announce": {}})
        self.assertIn("DIAL_TESTS=scope=full,gradle=no,", (driver.run.rs / "state").read_text())

    async def test_a_diff_that_trips_an_irreversible_path_stops_for_approval_before_verify(self):
        def behave(name, prompt, harness):
            if prompt.startswith("You are the implementer"):
                root = fixtures.path_in(prompt, r"Worktree \(do ALL work here\): (\S+)")
                (root / "PRODUCT.md").write_text("# The product\n")
            return fixtures.default_behaviour(name, prompt, harness)

        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"approval": {"approved": True, "decisions": []},
             "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}},
            harness=FakeHarness(behave, tmp=self.tmp))
        self.assertEqual(log, ["approval", "report-prose", "announce"])
        self.assertIn("DIAL_APPROVAL=required", (driver.run.rs / "state").read_text())
        events = driver.run.events()
        asked = next(i for i, e in enumerate(events) if e["ev"] == "ask" and e["kind"] == "approval")
        verified = next(i for i, e in enumerate(events) if e["ev"] == "enter" and e.get("phase") == "verify")
        self.assertLess(asked, verified)
        self.assertIn("PRODUCT.md matches PRODUCT.md", (driver.run.dir / "journal.jsonl").read_text())

    async def test_a_run_whose_task_died_parked_on_approval_is_resumed_by_the_watchdog(self):
        harness = FakeHarness(tmp=self.tmp)
        dead = await ship_driver.start("add a migration for greetings", Dials("none", "none"), slug="demo",
                                       harness=harness, tools=self.tools)
        while not dead.run.open_ask():
            await asyncio.sleep(0.01)
        self.assertEqual(dead.run.open_ask()["kind"], "approval")
        dead.task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await dead.task
        ship_driver._DRIVERS.clear()
        box, log, stop = {}, [], asyncio.Event()
        pilot = asyncio.ensure_future(autopilot(box, {
            "approval": {"approved": True, "decisions": []},
            "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}}, log, stop))
        await ship_driver.watchdog(harness, self.tools)
        driver = ship_driver._DRIVERS[dead.run.run_id]
        box["driver"] = driver
        try:
            await asyncio.wait_for(asyncio.shield(driver.task), 60)
        finally:
            stop.set()
            await pilot
        error = driver.run.dir / "error.txt"
        self.assertFalse(error.exists(), error.read_text() if error.exists() else "")
        self.assertIsNot(driver, dead)
        self.assertEqual(log, ["approval", "report-prose", "announce"])
        self.assertIn({"ev": "resume", "phase": "approve"},
                      [{k: e[k] for k in ("ev", "phase") if k in e} for e in driver.run.events()])
        self.assertIn("src/a.sh", git(self.repo, "show", "--stat", "main"))

    async def test_a_second_start_from_the_same_session_is_refused(self):
        harness = FakeHarness(lambda name, prompt, h: 10 ** 6, tmp=self.tmp)  # the implementer never finishes
        first = await ship_driver.start("add a greeting script", Dials("none", "none"), slug="demo", harness=harness,
                                        tools=self.tools)
        await until_waiting_on_children(first)
        try:
            with self.assertRaisesRegex(RuntimeError, "this session already drives"):
                await ship_driver.start("another", Dials("none", "none"), slug="other", harness=harness,
                                        tools=self.tools)
        finally:
            first.task.cancel()
            await asyncio.gather(first.task, return_exceptions=True)

    async def test_resume_and_the_watchdog_take_this_sessions_run_and_never_another_sessions(self):
        tmp = self.tmp

        class Session(FakeHarness):
            def __init__(self, session, behave=None):
                super().__init__(behave, tmp=tmp)
                self.session = session

            async def session_id(self):
                return self.session

        busy = lambda name, prompt, h: 10 ** 6  # noqa: E731
        first = await ship_driver.start("add a greeting script", Dials("none", "none"), slug="demo",
                                        harness=FakeHarness(busy, tmp=self.tmp), tools=self.tools)
        second = await ship_driver.start("add a farewell script", Dials("none", "none"), slug="other",
                                         harness=Session("coord-2", busy), tools=self.tools)
        for driver in (first, second):
            await until_waiting_on_children(driver)
            driver.task.cancel()
        await asyncio.gather(first.task, second.task, return_exceptions=True)
        ship_driver._DRIVERS.clear()
        third = Session("coord-3")
        await ship_driver.watchdog(third, self.tools)
        self.assertEqual(ship_driver._DRIVERS, {})
        with self.assertRaisesRegex(RuntimeError, "this session drives no live /ship run"):
            await ship_driver.resume(third, self.tools)
        resumed = await ship_driver.resume(FakeHarness(busy, tmp=self.tmp), self.tools)
        try:
            self.assertEqual(resumed.run.run_id, "ship-demo")
        finally:
            resumed.task.cancel()
            await asyncio.gather(resumed.task, return_exceptions=True)

    async def test_a_start_that_fails_before_its_journal_leaves_no_run_to_resume_and_can_be_retried(self):
        class NoHeartbeat(FakeHarness):
            async def heartbeat_create(self, instruction, interval, label):
                raise RuntimeError("the heartbeat service is down")

        with self.assertRaisesRegex(RuntimeError, "heartbeat service is down"):
            await ship_driver.start("add a greeting script", Dials("none", "none"), slug="demo",
                                    harness=NoHeartbeat(tmp=self.tmp), tools=self.tools)
        with self.assertRaisesRegex(RuntimeError, "this session drives no live /ship run"):
            await ship_driver.resume(FakeHarness(tmp=self.tmp), self.tools)
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}}, slug="again")
        self.assertIn("src/a.sh", git(self.repo, "show", "--stat", "main"))

    async def test_a_driver_that_dies_after_landing_resumes_without_landing_again(self):
        real_finish, deaths = report_module.finish, []

        async def dies_once(run, sha, state):
            if not deaths:
                deaths.append(sha)
                raise RuntimeError("the kernel died after ship-land")
            await real_finish(run, sha, state)
        report_module.finish = dies_once
        try:
            answers = {"report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}}
            dead, harness, log = await self.drive("add a greeting script", Dials("none", "none"), answers)
            self.assertIn("driver-error in land: RuntimeError: the kernel died after ship-land", harness.wakes[-1])
            error = next(e for e in dead.run.events() if e["ev"] == "error")
            self.assertEqual((error["phase"], error["type"], error["error"]),
                             ("land", "RuntimeError", "the kernel died after ship-land"))
            ship_driver._DRIVERS.clear()
            box, log, stop = {}, [], asyncio.Event()
            pilot = asyncio.ensure_future(autopilot(box, answers, log, stop))
            driver = box["driver"] = await ship_driver.resume(harness, self.tools)
            try:
                await asyncio.wait_for(asyncio.shield(driver.task), 30)
            finally:
                stop.set()
                await pilot
        finally:
            report_module.finish = real_finish
        self.assertEqual(log, ["announce"])
        cmds = [e["cmd"] for e in driver.run.events() if e["ev"] == "subprocess"]
        self.assertEqual(cmds.count("ship-squash"), 1)
        self.assertIn("PHASE=complete", (driver.run.rs / "state").read_text())
        self.assertIn("## Landed", (driver.run.rs / "report.md").read_text())

    async def test_a_decision_does_not_switch_off_the_approval_check_on_the_diff(self):
        def behave(name, prompt, harness):
            if prompt.startswith("You are the implementer"):
                question = fixtures.path_in(prompt, r"options to (\S+decision\.md)")
                question.parent.mkdir(parents=True, exist_ok=True)
                question.write_text("store greetings in a new table?\n")
            return 0

        harness = FakeHarness(behave, tmp=self.tmp)

        async def send(name, message):
            harness.sent.append((name, message))
            if message.startswith("Decision:"):
                implementer = next(s for s in harness.spawned if s["name"] == name)
                root = fixtures.path_in(implementer["dispatch"], r"Worktree \(do ALL work here\): (\S+)")
                (root / "PRODUCT.md").write_text("# The product, greeting\n")
                fixtures.finish_progress(implementer["dispatch"])
        harness.send = send
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"decision": {"answer": "yes, a table"}, "approval": {"approved": True, "decisions": ["a table"]},
             "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}}, harness=harness)
        self.assertEqual(log, ["decision", "approval", "report-prose", "announce"])
        self.assertIn("Decision: yes, a table", [m for _, m in harness.sent])
        self.assertTrue((driver.run.rs / "approval.md").exists())

    def respawn_once(self):
        """child-failed: respawn the first time; any later ask finishes the asked child's work itself, so a run that
        asks again still lands and the ask log names the extra ask instead of the run timing out."""
        def answer(ask, driver):
            if ask["n"] == 1:
                return {"respawn": "its context is gone"}
            (driver.run.root / "src").mkdir(exist_ok=True)
            (driver.run.root / "src" / "a.sh").write_text("#!/bin/sh\necho hi\n")
            Path(ask["context"]["progress"]).write_text("## Done\nthe model finished it\n")
            return {"finished": "wrote it myself"}
        return answer

    async def test_a_respawned_implementer_is_kept_so_a_later_denied_write_does_not_wait_on_the_deleted_one(self):
        def behave(name, prompt, harness):
            if prompt.startswith("You are the implementer") and name.endswith("-impl"):
                return 0  # stops without its marker: nudged, then child-failed
            if prompt.startswith("You are the implementer"):
                root = fixtures.path_in(prompt, r"Worktree \(do ALL work here\): (\S+)")
                (root / ".claude" / "skills" / "rogue.md").write_text("not ours\n")
            return fixtures.default_behaviour(name, prompt, harness)

        def tree_fixed(ask, driver):
            (driver.run.root / "src").mkdir(exist_ok=True)
            (driver.run.root / "src" / "a.sh").write_text("#!/bin/sh\necho hi\n")
            return {}

        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"child-failed": self.respawn_once(), "scope-deny": tree_fixed,
             "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}},
            harness=FakeHarness(behave, tmp=self.tmp))
        self.assertEqual(log, ["child-failed", "scope-deny", "report-prose", "announce"])
        self.assertEqual([s["name"] for s in harness.spawned if s["dispatch"].startswith("You are the implementer")],
                         ["ship-demo-impl", "ship-demo-impl-2"])
        landed = git(self.repo, "show", "--stat", "main")
        self.assertIn("src/a.sh", landed)
        self.assertNotIn("rogue.md", landed)

    async def test_a_respawned_implementers_irreversible_question_is_routed_and_makes_approval_required(self):
        def behave(name, prompt, harness):
            if prompt.startswith("You are the implementer") and not name.endswith("-impl"):
                question = fixtures.path_in(prompt, r"options to (\S+decision\.md)")
                question.parent.mkdir(parents=True, exist_ok=True)
                question.write_text("store greetings in a new table?\n")
            return 0

        harness = FakeHarness(behave, tmp=self.tmp)

        async def send(name, message):
            harness.sent.append((name, message))
            if message.startswith("Decision:"):
                implementer = next(s for s in harness.spawned if s["name"] == name)
                fixtures.default_behaviour(name, implementer["dispatch"], harness)
        harness.send = send
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"child-failed": self.respawn_once(), "decision": {"answer": "yes, a table"},
             "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}}, harness=harness)
        self.assertEqual(log, ["child-failed", "decision", "report-prose", "announce"])
        self.assertIn(("ship-demo-impl-2", "Decision: yes, a table"), harness.sent)
        dials = [(e["value"], e["reason"]) for e in driver.run.events() if e["ev"] == "dial" and e["value"] == "required"]
        self.assertEqual(dials, [("required", "ship-demo-impl-2 asked an irreversible question")])

    async def test_harnesses_all_runs_every_shell_harness_whatever_the_diff_touches(self):
        os.environ["STUB_HARNESSES"] = "ALL"
        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}})
        record = fixtures.read_record(driver.run.rs / "runs" / "shell-tests.record")
        self.assertEqual((record.get("cmd"), record.get("exit")), ("bin/shell-tests", "0"))

    async def test_a_denied_write_is_recovered_then_asked_and_a_new_implementer_lands(self):
        def behave(name, prompt, harness):
            if prompt.startswith("You are the implementer") and name.endswith("-impl"):
                root = fixtures.path_in(prompt, r"Worktree \(do ALL work here\): (\S+)")
                (root / ".claude" / "skills" / "rogue.md").write_text("not ours\n")
            return fixtures.default_behaviour(name, prompt, harness)

        driver, harness, log = await self.drive(
            "add a greeting script", Dials("none", "none"),
            {"scope-deny": {"brief": "add src/a.sh and nothing under .claude/"},
             "report-prose": {"opening": "DONE", "learning_candidates": "None"}, "announce": {}},
            harness=FakeHarness(behave, tmp=self.tmp))
        self.assertEqual(log, ["scope-deny", "report-prose", "announce"])
        recovered = next(i for i, e in enumerate(driver.run.events()) if e.get("cmd", "").startswith("ship-recover -c "))
        asked = next(i for i, e in enumerate(driver.run.events()) if e["ev"] == "ask" and e["kind"] == "scope-deny")
        self.assertLess(recovered, asked)
        implementers = [s for s in harness.spawned if s["dispatch"].startswith("You are the implementer")]
        self.assertEqual(len(implementers), 2)
        self.assertIn("nothing under .claude/", implementers[1]["dispatch"])
        landed = git(self.repo, "show", "--stat", "main")
        self.assertIn("src/a.sh", landed)
        self.assertNotIn("rogue.md", landed)
