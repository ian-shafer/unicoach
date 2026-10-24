import json
import tempfile
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, git, make_repo, make_rs

from ship_driver import report
from ship_driver.run import Run, ScriptFailed


def message(role, at, tokens=0):
    entry = {"type": "message", "timestamp": at, "message": {"role": role}}
    if role == "assistant":
        entry["message"]["usage"] = {"totalTokens": tokens}
    return json.dumps(entry) + "\n"


class Spend(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_turns_are_counted_between_two_timestamps(self):
        log = self.tmp / "coord.jsonl"
        log.write_text(
            message("assistant", "2026-10-24T09:00:00Z", 5)  # before the window
            + json.dumps({"type": "custom_message", "timestamp": "2026-10-24T10:00:00Z"}) + "\n"
            + message("assistant", "2026-10-24T10:00:01Z", 10) + message("toolResult", "2026-10-24T10:00:02Z")
            + message("assistant", "2026-10-24T10:00:03Z", 20)
            + json.dumps({"type": "custom_message", "timestamp": "2026-10-24T10:05:00Z"}) + "\n"
            + message("assistant", "2026-10-24T10:05:01Z", 30))
        start, end = report._ts("2026-10-24T09:30:00Z"), report._ts("2026-10-24T11:00:00Z")
        count = report.assistant_messages(log, start, end)
        self.assertEqual((count.turns, count.messages, count.tokens, count.unread), (2, 3, 60, ()))

    def test_turns_split_by_answers_with_wakes_reported_and_a_child_without_usage_is_unmeasured(self):
        repo = make_repo(self.tmp)
        run = Run(make_rs(self.tmp, repo), FakeHarness(tmp=self.tmp), fixtures.TOOLS)
        run.journal("enter", phase="verify")
        run.journal("spawn", role="lens", name="lens-1", child_id="sub-1", started="2026-10-24T10:00:00+00:00")
        run.journal("spawn", role="fix", name="fix-1", child_id="sub-2", started="2026-10-24T10:00:00+00:00")
        (run.dir / "answers").mkdir()
        (run.dir / "answers" / "1.json").write_text("{}")
        sessions, agent = self.tmp / "sessions", self.tmp / "agent"
        (agent / "rlm-ledger").mkdir(parents=True)
        sessions.mkdir()
        at = run.events()[0]["at"]
        (sessions / "coord-1.jsonl").write_text(
            "".join(json.dumps({"type": "custom_message", "timestamp": at}) + "\n" + message("assistant", at, 7)
                    for _ in range(3)))
        lens_log, fix_log = self.tmp / "lens.jsonl", self.tmp / "fix.jsonl"
        lens_log.write_text(message("assistant", at, 100) + message("assistant", at, 50))
        fix_log.write_text(json.dumps({"type": "session"}) + "\n")
        (agent / "rlm-ledger" / "x.jsonl").write_text(
            json.dumps({"op": "spawn", "childId": "sub-1", "child": str(lens_log)}) + "\n"
            + json.dumps({"op": "spawn", "childId": "sub-2", "child": str(fix_log)}) + "\n")
        run.wake("design", "x")  # one ask, woken twice
        run.wake("design", "x\nwith a second line")
        run.bind_session(coord_log=sessions / "coord-1.jsonl", agent_dir=agent)
        text = report.spend(run)
        self.assertIn("Coordinator model turns: 3 (1 judgement, 2 absorbed), 21 tokens; 2 wakes issued", text)
        self.assertIn("fix 1 (0 tokens), lens 1 (150 tokens)", text)
        self.assertIn("Unmeasured children: fix-1", text)
        self.assertIn("Median lens model calls: 2", text)
        self.assertIn("Unread log lines: none", text)

    def run_with_logs(self):
        repo = make_repo(self.tmp)
        run = Run(make_rs(self.tmp, repo), FakeHarness(tmp=self.tmp), fixtures.TOOLS)
        run.journal("enter", phase="verify")
        run.journal("spawn", role="lens", name="lens-1", child_id="sub-1", started="2026-10-24T10:00:00+00:00")
        agent = self.tmp / "agent"
        (agent / "rlm-ledger").mkdir(parents=True)
        at = run.events()[0]["at"]
        coord, lens_log = self.tmp / "coord.jsonl", self.tmp / "lens.jsonl"
        coord.write_text(message("assistant", at, 7) + '{"type": "mess')  # torn: written as the session died
        lens_log.write_text(message("assistant", at, 100) + "not json\n")
        (agent / "rlm-ledger" / "x.jsonl").write_text(
            json.dumps({"op": "spawn", "childId": "sub-1", "child": str(lens_log)}) + "\n{torn\n")
        run.bind_session(coord_log=coord, agent_dir=agent)
        return run, coord, lens_log, agent / "rlm-ledger" / "x.jsonl"

    def test_lines_that_do_not_parse_are_named_and_the_rest_still_measured(self):
        run, coord, lens_log, ledger = self.run_with_logs()
        text = report.spend(run)
        self.assertIn("Coordinator model turns: 1 (0 judgement, 1 absorbed), 7 tokens", text)
        self.assertIn("lens 1 (100 tokens)", text)
        for where in (f"{coord}:2", f"{lens_log}:2", f"{ledger}:2"):
            self.assertIn(where, text)

    def test_wall_clock_is_reported_to_the_second(self):
        repo = make_repo(self.tmp)
        run = Run(make_rs(self.tmp, repo), FakeHarness(tmp=self.tmp), fixtures.TOOLS)
        run.dir.mkdir(parents=True)
        with open(run.dir / "journal.jsonl", "w") as file:
            for at, ev in (("2026-10-24T10:00:00+00:00", "enter"), ("2026-10-24T10:00:20+00:00", "exit")):
                file.write(json.dumps({"at": at, "ev": ev, "phase": "design"}) + "\n")
        self.assertIn("Wall-clock per phase: design 0:00:20", report.spend(run))


class Write(unittest.IsolatedAsyncioTestCase):
    async def test_a_base_git_cannot_read_fails_the_report_instead_of_writing_an_empty_one(self):
        tmp = Path(tempfile.mkdtemp())
        run = Run(make_rs(tmp, make_repo(tmp), BASE_SHA="0" * 40, RFC_NUM=""), FakeHarness(tmp=tmp), fixtures.TOOLS)
        with self.assertRaises(ScriptFailed):
            await report.write(run)
        self.assertFalse((run.rs / "report.md").exists())

    async def test_a_skipped_malformed_report_puts_no_finding_in_the_report(self):
        tmp = Path(tempfile.mkdtemp())
        repo = make_repo(tmp)
        run = Run(make_rs(tmp, repo), FakeHarness(tmp=tmp), fixtures.TOOLS)
        (repo / "a.kt").write_text("x\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "--no-verify", "-m", "a.kt")
        (run.rs / "findings" / "lens-ledger.tsv").write_text("start\t1\tlens-a\tc1\nskip\t1\tlens-a\tno grade twice\n")
        (run.rs / "findings" / "tier1-lens-a.md").write_text(
            "**Verdict:** 🔴 FAIL\n\n### never planned [ADVISORY]\n\n`a.kt:1`\n\n## Out of lens\n\n"
            "### nor this [BLOCKING]\n\n`a.kt:2`\n")  # tagged, but no **Grade:** line
        await report.write(run)
        text = (run.rs / "report.md").read_text()
        self.assertNotIn("never planned", text)
        self.assertNotIn("nor this", text)
        self.assertIn("Grades: malformed 1", text)

    async def test_contradictions_are_counted_by_the_records_kind_whatever_their_ref_says(self):
        tmp = Path(tempfile.mkdtemp())
        run = Run(make_rs(tmp, make_repo(tmp)), FakeHarness(tmp=tmp), fixtures.TOOLS)
        record = {"lens": "l", "finding_id": 1, "grade": "SHOULD-FIX", "file": "a.kt", "line": 1,
                  "outcome": "CONTRADICTED", "contradicts_kind": "house", "contradicts_ref": "a vs rfc:256",
                  "out_of_lens": False, "reason": "r"}
        (run.rs / "findings" / "tier1-outcomes.jsonl").write_text(json.dumps(record) + "\n")
        await report.write(run)
        self.assertIn("By kind: {'house': 1}", (run.rs / "report.md").read_text())

    async def test_a_failed_narrowing_is_in_its_tiers_section(self):
        tmp = Path(tempfile.mkdtemp())
        run = Run(make_rs(tmp, make_repo(tmp)), FakeHarness(tmp=tmp), fixtures.TOOLS)
        (run.rs / "findings" / "lens-ledger.tsv").write_text("skip\t1\tlens-a\tno lens can fire\n")
        run.journal("narrow-failed", tier=1, error="result.json does not parse")
        self.assertIn("- Narrowing: result.json does not parse — every lens dispatched", report.tier_section(run, 1))
