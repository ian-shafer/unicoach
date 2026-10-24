import asyncio
import json
import tempfile
import unittest

import fixtures
from fixtures import FakeHarness, make_repo, make_rs

import ship_driver
from ship_driver.answers import Decision, Design
from ship_driver.run import Run, ScriptFailed, read_jsonl, sh


class Journal(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        tmp = tempfile.mkdtemp()
        self.harness = FakeHarness(tmp=tmp)
        self.run = Run(make_rs(tmp, make_repo(tmp)), self.harness, fixtures.TOOLS)

    async def test_park_writes_the_ask_and_a_journal_line_and_wakes(self):
        task = asyncio.ensure_future(self.run.ask("design", "write the RFC"))
        await asyncio.sleep(0.05)
        ask = json.loads(self.run.ask_path.read_text())
        self.assertEqual((ask["kind"], ask["n"], ask["answers"]), ("design", 1, ["{'path': str}"]))
        self.assertIn({"ev": "ask", "kind": "design", "n": 1}, [{k: e[k] for k in ("ev", "kind", "n") if k in e}
                                                               for e in self.run.events()])
        self.assertTrue(self.harness.wakes[0].startswith("ship-wake run-a design: write the RFC; read "))
        self.assertEqual([w["kind"] for w in read_jsonl(self.run.dir / "wakes.jsonl")], ["design"])
        self.assertFalse(task.done())
        self.run.answer("design", {"path": "rfc/1-x.md"})
        self.assertEqual(await asyncio.wait_for(task, 2), Design("rfc/1-x.md"))
        self.assertFalse(self.run.ask_path.exists())

    async def test_an_answer_of_the_wrong_kind_is_refused(self):
        task = asyncio.ensure_future(self.run.ask("approval", "present it"))
        await asyncio.sleep(0.05)
        with self.assertRaises(ValueError):
            self.run.answer("design", {})
        self.run.answer("approval", {"approved": True})
        await asyncio.wait_for(task, 2)

    async def test_an_answer_of_the_wrong_shape_is_refused_while_the_ask_stays_open(self):
        task = asyncio.ensure_future(self.run.ask("malformed-twice", "skip or redispatch"))
        await asyncio.sleep(0.05)
        for wrong in ({}, {"redispatch": True}, {"skip": "x", "redispatch": "y"}, {"skip": 3}, ["skip"]):
            with self.assertRaisesRegex(ValueError, "'skip': str.* or .*'redispatch': str", msg=repr(wrong)):
                self.run.answer("malformed-twice", wrong)
        self.assertIsNotNone(self.run.open_ask())
        self.assertFalse(task.done())
        self.run.answer("malformed-twice", {"skip": "no grade twice"})
        self.assertEqual((await asyncio.wait_for(task, 2)).skip, "no grade twice")

    def test_a_blank_or_multi_line_one_line_answer_is_refused_and_prose_may_span_lines(self):
        from ship_driver import answers
        for kind, payload in (("malformed-twice", {"skip": ""}), ("malformed-twice", {"skip": "a\nb"}),
                              ("design", {"path": "  "}), ("decision", {"answer": "x\ty"}),
                              ("report-prose", {"opening": "o", "learning_candidates": "l", "subject": " "})):
            with self.assertRaises(ValueError, msg=repr(payload)):
                answers.parse(kind, payload)
        prose = answers.parse("report-prose", {"opening": "one\ntwo", "learning_candidates": "None"})
        self.assertIsNone(prose.subject)
        self.assertEqual(answers.parse("scope-deny", {"brief": "line one\nline two"}).brief, "line one\nline two")

    async def test_an_answer_written_while_the_task_was_dead_is_taken_on_reentry(self):
        first = asyncio.ensure_future(self.run.ask("decision", "which"))
        await asyncio.sleep(0.05)
        first.cancel()
        self.run.answer("decision", {"answer": "option 2"})
        self.assertEqual(await asyncio.wait_for(self.run.ask("decision", "which"), 2), Decision("option 2"))

    async def test_resume_reenters_the_phase_whose_enter_has_no_exit(self):
        await self.run.enter("implement")
        self.run.exit("implement")
        await self.run.enter("verify")
        await self.run.enter("verify", "tier0")
        self.run.exit("verify", "tier0")
        await self.run.enter("verify", "rebase")  # a sub-step is never a phase, whatever its name
        await self.run.set("PHASE", "verifying")
        self.assertEqual(ship_driver.open_phase(self.run), "verify")
        self.assertTrue(self.run.step_done("verify", "tier0"))
        self.assertFalse(self.run.step_done("verify", "tier1"))
        self.assertEqual(self.run.get("DRIVER_STEP"), "verify/rebase")

    async def test_a_phase_that_disagrees_with_the_journal_is_reported_not_acted_on(self):
        self.run.journal("enter", phase="verify")
        await self.run.set("PHASE", "landing")
        with self.assertRaisesRegex(RuntimeError, "PHASE=landing disagrees with the journal"):
            ship_driver.open_phase(self.run)


class Commands(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        tmp = tempfile.mkdtemp()
        self.run = Run(make_rs(tmp, make_repo(tmp)), FakeHarness(tmp=tmp), fixtures.TOOLS)

    async def test_a_failed_command_carries_its_whole_stderr_and_its_stdout(self):
        script = "echo the-stdout; for i in $(seq 1 400); do echo line$i >&2; done; exit 3"
        with self.assertRaises(ScriptFailed) as raised:
            await sh(["bash", "-c", script], check=True)
        self.assertEqual(raised.exception.proc.code, 3)
        self.assertIn("line1\n", str(raised.exception))
        self.assertIn("line400", str(raised.exception))
        self.assertIn("the-stdout", str(raised.exception))

    async def test_a_ship_run_that_refuses_raises_with_its_stderr_and_never_reads_an_older_record(self):
        stale = self.run.rs / "runs" / "a" / "b.record"
        stale.parent.mkdir(parents=True)
        stale.write_text("cmd=bin/test\nexit=0\n")
        with self.assertRaisesRegex(ScriptFailed, "wrote no record(.|\n)*record name"):
            await self.run.ship_run("a/b", ["true"])

    async def test_a_ship_run_returns_the_record_this_invocation_wrote(self):
        record = await self.run.ship_run("probe", ["bash", "-c", "exit 4"])
        self.assertEqual(record["exit"], "4")
        self.assertTrue(record["cmd"].startswith("bash -c "))

    def test_waiting_on_nothing_is_none(self):
        self.assertIsNone(self.run.waiting_on)

    def test_a_multi_line_wake_is_one_record(self):
        self.run.wake("hook-failed", "first line\nsecond\tline")
        self.assertEqual([w["line"] for w in read_jsonl(self.run.dir / "wakes.jsonl")], ["first line\nsecond\tline"])

    def test_an_unrecorded_tests_dial_is_a_fault_not_no_tests(self):
        with self.assertRaisesRegex(RuntimeError, "no DIAL_TESTS"):
            self.run.tests

    def test_a_missing_checkpoint_names_the_log_and_the_labels_present(self):
        from ship_driver.phases import last_checkpoint
        (self.run.rs / "checkpoints.log").write_text("abc123\tpre-rebase\n")
        with self.assertRaisesRegex(RuntimeError, r"checkpoints\.log has no 'before-impl' checkpoint.*pre-rebase"):
            last_checkpoint(self.run, "before-impl")
