import asyncio
import datetime
import os
import tempfile
import types
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, make_repo, make_rs, until

from ship_driver import children, review
from ship_driver.harness import LiveHarness
from ship_driver.run import Run


class Children(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = make_repo(self.tmp)

    def make(self, behave):
        self.harness = FakeHarness(behave, tmp=self.tmp)
        return Run(make_rs(self.tmp, self.repo), self.harness, fixtures.TOOLS)

    async def test_wait_returns_done_once_the_marker_is_written_and_the_child_is_quiet(self):
        run = self.make(lambda name, prompt, h: 3 if name == "slow" else 0)
        fast = await children.spawn(run, "fix", "fast", lambda name: "x")
        slow = await children.spawn(run, "fix", "slow", lambda name: "x")
        for child in (fast, slow):
            child.progress.write_text("## Done\nok\n")
        status = await asyncio.wait_for(children.wait(run, [fast, slow]), 2)
        self.assertEqual(status, {"fast": children.Settled.DONE, "slow": children.Settled.DONE})
        self.assertEqual(self.harness.pending_polls["slow"], 0)

    async def test_a_lens_settled_with_no_report_is_redispatched_once_then_skipped(self):
        run = self.make(lambda name, prompt, h: 0)  # never writes its report
        lens = "code-review-dry-abstractions"
        child = await review.dispatch_lens(run, 1, lens, "diff", None)
        await asyncio.wait_for(review.settle_lenses(run, 1, {lens: child}, "diff", None), 5)
        ledger = (run.rs / "findings" / "lens-ledger.tsv").read_text().splitlines()
        self.assertEqual([line.split("\t")[0] for line in ledger], ["start", "start", "skip"])
        self.assertEqual(ledger[-1].split("\t")[3], "child stalled twice (idle)")
        self.assertEqual(len(self.harness.spawned), 2)
        self.assertEqual(self.harness.deleted, [s["id"] for s in self.harness.spawned])
        self.assertEqual([name for name, _ in self.harness.sent], [s["name"] for s in self.harness.spawned])

    async def never_respawned(self):
        raise AssertionError("respawned")

    async def test_a_fix_child_without_its_marker_gets_one_wake_then_child_failed(self):
        run = self.make(lambda name, prompt, h: 0)
        child = await children.spawn(run, "fix", "fixer", lambda name: "x")
        task = asyncio.ensure_future(children.finish(run, child, self.never_respawned))
        await asyncio.wait_for(until(lambda: run.open_ask()), 2)
        self.assertEqual(run.open_ask()["kind"], "child-failed")
        self.assertEqual(len(self.harness.sent), 1)
        self.assertIn("progress", self.harness.sent[0][1])
        child.progress.write_text("## Done\nthe model finished it\n")
        run.answer("child-failed", {"finished": "wrote the last record and ## Done"})
        self.assertIs(await asyncio.wait_for(task, 2), child)

    async def test_a_finished_answer_is_checked_and_asked_again_while_the_work_is_still_undone(self):
        run = self.make(lambda name, prompt, h: 0)
        child = await children.spawn(run, "fix", "fixer", lambda name: "x")
        task = asyncio.ensure_future(children.finish(run, child, self.never_respawned))
        await asyncio.wait_for(until(lambda: (run.open_ask() or {}).get("n") == 1), 2)
        run.answer("child-failed", {"finished": "done, honestly"})
        await asyncio.wait_for(until(lambda: (run.open_ask() or {}).get("n") == 2), 2)
        self.assertIn("still unfinished", run.open_ask()["line"])
        child.progress.write_text("## Done\nok\n")
        run.answer("child-failed", {"finished": "now it is"})
        self.assertIs(await asyncio.wait_for(task, 2), child)

    async def test_a_respawn_answer_deletes_the_child_and_waits_on_a_fresh_one(self):
        run = self.make(lambda name, prompt, h: 0)
        child = await children.spawn(run, "fix", "fixer", lambda name: "x")

        async def respawn():
            fresh = await children.spawn(run, "fix", "fixer", lambda name: "x")
            fresh.progress.write_text("## Done\nredone\n")
            return fresh
        task = asyncio.ensure_future(children.finish(run, child, respawn))
        await asyncio.wait_for(until(lambda: run.open_ask()), 2)
        run.answer("child-failed", {"respawn": "its context is gone"})
        fresh = await asyncio.wait_for(task, 2)
        self.assertEqual((fresh.name, self.harness.deleted), ("fixer-2", [child.child_id]))
        self.assertIn({"ev": "respawn", "name": "fixer", "reason": "its context is gone"},
                      [{k: e[k] for k in ("ev", "name", "reason")} for e in run.events() if e["ev"] == "respawn"])

    async def test_a_woken_child_that_finishes_is_not_failed(self):
        run = self.make(lambda name, prompt, h: 0)
        child = await children.spawn(run, "fix", "fixer", lambda name: "x")

        async def finish_late():
            while not self.harness.sent:
                await asyncio.sleep(0.01)
            child.progress.write_text("## Done\nok\n")
        await asyncio.wait_for(asyncio.gather(children.finish(run, child, self.never_respawned), finish_late()), 2)
        self.assertIsNone(run.open_ask())

    async def test_refind_matches_the_journaled_base_never_a_name_that_merely_looks_like_it(self):
        run = self.make(lambda name, prompt, h: 0)
        await children.spawn(run, "fix", "run-a-t1-fix-2", lambda name: "an unrelated family", tier=1)
        self.assertIsNone(children.refind(run, "fix", "run-a-t1-fix"))
        second = await children.spawn(run, "fix", "run-a-t1-fix", lambda name: f"I am {name}", tier=1)
        found = children.refind(run, "fix", "run-a-t1-fix")
        self.assertEqual((found.name, found.started, second.dispatch.read_text()),
                         ("run-a-t1-fix", second.started, "I am run-a-t1-fix"))
        self.assertEqual([e["tier"] for e in run.events() if e["ev"] == "spawn"], [1, 1])

    async def test_a_child_is_spawned_with_a_short_prompt_naming_its_dispatch_file(self):
        run = self.make(lambda name, prompt, h: 0)
        prompt = "# Lens: x\n\n" + "the diff\n" * 40_000
        child = await children.spawn(run, "lens", "big", lambda name: prompt)
        spawned = self.harness.spawned[0]["prompt"]
        self.assertLess(len(spawned), 400)
        self.assertIn(str(child.dispatch), spawned)
        self.assertEqual(child.dispatch.read_text(), prompt)

    async def test_a_lens_still_streaming_is_waited_on_not_redispatched(self):
        lens = "code-review-dry-abstractions"

        def behave(name, prompt, harness):
            report = fixtures.path_in(prompt, r"Write your report to (\S+) and change")
            return 8, lambda: report.write_text(fixtures.report_text(lens))  # written as it stops streaming
        run = self.make(behave)
        child = await review.dispatch_lens(run, 1, lens, "diff", None)
        await asyncio.wait_for(review.settle_lenses(run, 1, {lens: child}, "diff", None), 5)
        ledger = (run.rs / "findings" / "lens-ledger.tsv").read_text().splitlines()
        self.assertEqual([line.split("\t")[0] for line in ledger], ["start", "done"])
        self.assertEqual((len(self.harness.spawned), self.harness.sent), (1, []))

    async def test_a_stopped_lens_gets_one_wake_message_and_is_not_redispatched_when_it_then_reports(self):
        lens = "code-review-dry-abstractions"
        run = self.make(lambda name, prompt, h: 0)  # stops at once, as an errored child does
        child = await review.dispatch_lens(run, 1, lens, "diff", None)

        async def send(name, message):
            self.harness.sent.append((name, message))
            review.report_path(run, 1, lens).write_text(fixtures.report_text(lens))
        self.harness.send = send
        await asyncio.wait_for(review.settle_lenses(run, 1, {lens: child}, "diff", None), 5)
        self.assertEqual(len(self.harness.sent), 1)
        self.assertIn(str(child.dispatch), self.harness.sent[0][1])
        self.assertEqual(len(self.harness.spawned), 1)
        self.assertEqual([e["ev"] for e in run.events() if e["ev"] in ("nudge", "redispatch")], ["nudge"])

    async def test_a_lens_over_its_ceiling_is_redispatched_once_then_skipped(self):
        saved = children.LENS_CEILING
        children.LENS_CEILING = datetime.timedelta(milliseconds=50)
        try:
            run = self.make(lambda name, prompt, h: 10 ** 6)  # streams forever, never reports
            lens = "code-review-dry-abstractions"
            child = await review.dispatch_lens(run, 1, lens, "diff", None)
            await asyncio.wait_for(review.settle_lenses(run, 1, {lens: child}, "diff", None), 5)
        finally:
            children.LENS_CEILING = saved
        ledger = (run.rs / "findings" / "lens-ledger.tsv").read_text().splitlines()
        self.assertEqual([line.split("\t")[0] for line in ledger], ["start", "start", "skip"])
        self.assertEqual([e["status"] for e in run.events() if e["ev"] == "settle"], ["over-ceiling"] * 2)
        self.assertEqual(self.harness.deleted, [s["id"] for s in self.harness.spawned])

    async def test_a_child_waiting_on_its_own_ship_run_is_not_idle(self):
        run = self.make(lambda name, prompt, h: 0)
        child = await children.spawn(run, "fix", "fixer", lambda name: "x")
        ledger = run.rs / "runs" / "ledger.tsv"
        ledger.parent.mkdir()
        ledger.write_text("2026-10-24T10:00:00Z\tstart\tname=suite\tcaller=fixer\tcmd=bin/test\n"
                          "2026-10-24T10:00:00Z\tstart\tname=other\tcaller=someone-else\tcmd=x\n")
        waiting = asyncio.ensure_future(children.wait(run, [child]))
        await asyncio.sleep(children.IDLE_POLLS * 5 * fixtures.TOOLS.poll_seconds)
        self.assertFalse(waiting.done())
        with open(ledger, "a") as file:
            file.write("2026-10-24T10:05:00Z\tend\tname=suite\tcaller=fixer\texit=0\n")
        self.assertEqual(await asyncio.wait_for(waiting, 2), {"fixer": children.Settled.IDLE})


class LiveBusy(unittest.IsolatedAsyncioTestCase):
    def harness(self, list_agents):
        return LiveHarness({"agent_observe": types.SimpleNamespace(list_agents=list_agents)})

    async def test_a_child_absent_from_the_roster_is_not_busy_and_a_streaming_one_is(self):
        async def roster():
            return {"agents": [{"sessionName": "lens-a", "relationship": "child", "isStreaming": True},
                               {"sessionName": "lens-b", "relationship": "sibling", "isStreaming": True}]}
        harness = self.harness(roster)
        self.assertEqual([await harness.busy(n) for n in ("lens-a", "lens-b", "gone")], [True, False, False])

    async def test_session_id_requires_exactly_one_current_agent_and_names_the_roster(self):
        for agents in ([], [{"sessionId": "a", "isCurrent": True}, {"sessionId": "b", "isCurrent": True}]):
            async def roster(agents=agents):
                return {"agents": agents}
            with self.assertRaisesRegex(RuntimeError, f"marks {len(agents)} agents isCurrent"):
                await self.harness(roster).session_id()

    async def test_without_a_session_dir_the_log_is_never_looked_for_in_the_kernels_cwd(self):
        async def roster():
            return {"agents": [{"sessionId": "s-1", "isCurrent": True}]}
        tmp = Path(tempfile.mkdtemp())
        saved = dict(os.environ)
        cwd = os.getcwd()
        try:
            os.environ["PRIME_AGENT_CODING_AGENT_DIR"] = str(tmp / "agent")
            os.environ.pop("PRIME_AGENT_SESSION_DIR", None)
            os.chdir(tmp)
            (tmp / "s-1.jsonl").write_text("{}\n")
            self.assertEqual(await self.harness(roster).session_log(), tmp / "agent" / "sessions" / "s-1.jsonl")
        finally:
            os.chdir(cwd)
            os.environ.clear()
            os.environ.update(saved)

    async def test_spawn_hands_back_the_child_id_alone(self):
        async def spawn(prompt, name, model=None):
            return types.SimpleNamespace(rlm_child_id="sub-9", name=name, session_dir="/x")
        harness = LiveHarness({"rlm": types.SimpleNamespace(spawn=spawn)})
        self.assertEqual(await harness.spawn("p", "n"), "sub-9")

    async def test_a_heartbeat_reply_without_an_id_names_the_reply(self):
        async def create(*args, **kwargs):
            return {"error": "quota"}
        harness = LiveHarness({"rlm_heartbeat": types.SimpleNamespace(create=create)})
        with self.assertRaisesRegex(RuntimeError, "no heartbeat id.*quota"):
            await harness.heartbeat_create("i", "*/30 * * * *", "label")

    async def test_a_roster_that_cannot_be_read_is_not_read_as_not_busy(self):
        async def broken():
            raise RuntimeError("host error: daemon unavailable")
        with self.assertRaisesRegex(RuntimeError, "daemon unavailable"):
            await self.harness(broken).busy("lens-a")
