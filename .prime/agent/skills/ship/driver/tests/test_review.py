import asyncio
import json
import re
import tempfile
import unittest
from pathlib import Path

import fixtures
from fixtures import FakeHarness, git, make_repo, make_rs, report_text

from ship_driver import children, review
from ship_driver.dials import Dials
from ship_driver.run import Run


class Tier(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = make_repo(self.tmp)
        self.rs = make_rs(self.tmp, self.repo)
        (self.repo / "bin" / "thing").write_text("#!/usr/bin/env bash\necho thing\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "--no-verify", "-m", "a shell change")

    async def test_the_dispatch_set_is_undispatched_minus_the_maps_removals_each_with_a_skip(self):
        harness = FakeHarness(tmp=self.tmp)
        run = Run(self.rs, harness, fixtures.TOOLS)
        await asyncio.wait_for(review.run_tier(run, 2, Dials("none", "full")), 10)
        ledger = [line.split("\t") for line in (self.rs / "findings" / "lens-ledger.tsv").read_text().splitlines()]
        started = {f[2] for f in ledger if f[0] == "start"}
        skipped = {f[2]: f[3] for f in ledger if f[0] == "skip"}
        self.assertEqual(started, {"code-review-semantic-output"})
        self.assertEqual(set(skipped), {"code-review-immutable-returns"})
        self.assertIn("shell", skipped["code-review-immutable-returns"])
        self.assertEqual((await review.missing(run, 2))[0].code, 0)
        self.assertTrue(run.step_done("verify", "tier2"))

    async def test_a_fail_with_no_tags_is_redispatched_once_with_the_instruction_quoted(self):
        def behave(name, prompt, harness):
            path = fixtures.path_in(prompt, r"Write your report to (\S+) and change")
            if "quoted" in prompt:
                path.write_text(report_text("code-review-semantic-output"))
            else:
                path.write_text("# Review Report\n\n**Verdict:** 🔴 FAIL\n\n## Findings\n\n### untagged\n")
            return 0
        harness = FakeHarness(behave, tmp=self.tmp)
        run = Run(self.rs, harness, fixtures.TOOLS)
        await asyncio.wait_for(review.run_tier(run, 2, Dials("none", "full")), 10)
        self.assertEqual(len(harness.spawned), 2)
        self.assertIn(review.graded_instruction(), harness.spawned[1]["dispatch"])
        self.assertEqual([e["reason"] for e in run.events() if e["ev"] == "redispatch"], ["malformed: NO_GRADE"])
        self.assertIn("Your previous report had no `**Grade:**` line", harness.spawned[1]["dispatch"])


    async def test_a_lens_malformed_twice_is_the_models_to_skip_never_to_grade(self):
        def behave(name, prompt, harness):
            fixtures.path_in(prompt, r"Write your report to (\S+) and change").write_text("**Verdict:** 🔴 FAIL\n")
            return 0
        run = Run(self.rs, FakeHarness(behave, tmp=self.tmp), fixtures.TOOLS)
        task = asyncio.ensure_future(review.run_tier(run, 2, Dials("none", "full")))
        await asyncio.wait_for(fixtures.until(run.open_ask), 5)
        self.assertEqual(run.open_ask()["kind"], "malformed-twice")
        self.assertIn("no `**Grade:**` line", run.open_ask()["line"])
        run.answer("malformed-twice", {"skip": "no grade twice"})
        await asyncio.wait_for(task, 10)
        ledger = (self.rs / "findings" / "lens-ledger.tsv").read_text()
        self.assertIn("skip\t2\tcode-review-semantic-output\tno grade twice", ledger)
        self.assertNotIn("done\t2\tcode-review-semantic-output", ledger)
        self.assertFalse((self.rs / "findings" / "tier2-fix-plan.md").read_text().count("semantic-output"))

    async def test_a_ledger_ship_lens_cannot_read_stops_the_tier_before_any_dispatch(self):
        ledger = self.rs / "findings" / "lens-ledger.tsv"
        ledger.write_text("")
        ledger.chmod(0)  # exists, cannot be read: ship-lens halts, exit 2
        harness = FakeHarness(tmp=self.tmp)
        run = Run(self.rs, harness, fixtures.TOOLS)
        with self.assertRaisesRegex(review.ScriptFailed, "ship-lens missing -t 2 exited 2"):
            await asyncio.wait_for(review.run_tier(run, 2, Dials("none", "full")), 10)
        self.assertEqual(harness.spawned, [])

    async def test_a_report_no_start_declares_stops_the_tier_before_any_dispatch(self):
        (self.rs / "findings" / "tier2-invented.md").write_text(report_text("invented"))
        harness = FakeHarness(tmp=self.tmp)
        run = Run(self.rs, harness, fixtures.TOOLS)
        with self.assertRaisesRegex(RuntimeError, "UNDECLARED"):
            await asyncio.wait_for(review.run_tier(run, 2, Dials("none", "full")), 10)
        self.assertEqual(harness.spawned, [])

    def test_only_the_reports_of_started_lenses_are_read(self):
        run = Run(self.rs, FakeHarness(tmp=self.tmp), fixtures.TOOLS)
        (self.rs / "findings" / "lens-ledger.tsv").write_text("start\t1\tlens-a\tc1\n")
        for lens in ("lens-a", "lens-b"):
            (self.rs / "findings" / f"tier1-{lens}.md").write_text(report_text(lens))
        self.assertEqual([r.lens for r in review.tier_reports(run, 1)], ["lens-a"])

    async def narrowed_by(self, result_text):
        def behave(name, prompt, harness):
            result = fixtures.path_in(prompt, r"Write (\S+result\.json) as JSON")
            if result is not None:
                result.write_text(result_text)
                return 0
            return fixtures.default_behaviour(name, prompt, harness)
        (self.repo / "a.kt").write_text("val a = 1\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "--no-verify", "-m", "kotlin")
        run = Run(self.rs, FakeHarness(behave, tmp=self.tmp), fixtures.TOOLS)
        await asyncio.wait_for(review.run_tier(run, 2, Dials("none", "targeted", narrow=True)), 10)
        started = {line.split("\t")[2] for line in (self.rs / "findings" / "lens-ledger.tsv").read_text().splitlines()
                   if line.startswith("start\t")}
        return run, started

    async def test_a_narrowing_result_that_does_not_parse_removes_nothing_and_says_why(self):
        run, started = await self.narrowed_by("{not json")
        self.assertEqual(started, {"code-review-semantic-output", "code-review-immutable-returns"})
        self.assertIn("does not parse", next(e["error"] for e in run.events() if e["ev"] == "narrow-failed"))

    async def test_a_narrowing_result_naming_a_lens_it_was_not_offered_removes_nothing(self):
        run, started = await self.narrowed_by(json.dumps({"remove": {"code-review-semantic-output": "no shell",
                                                                     "code-review-made-up": "x"}}))
        self.assertIn("code-review-semantic-output", started)

    async def test_a_narrowing_result_in_the_exact_shape_removes_what_it_names(self):
        run, started = await self.narrowed_by(json.dumps({"remove": {"code-review-semantic-output": "no shell"}}))
        self.assertEqual(started, {"code-review-immutable-returns"})


class FixExceptions(unittest.IsolatedAsyncioTestCase):
    async def test_an_approved_line_and_a_contradicted_blocking_each_wake_the_model(self):
        tmp = tempfile.mkdtemp()
        rs = make_rs(tmp, make_repo(tmp), RFC_NUM="1")
        (rs / "approval.md").write_text("# Approval\n\n## Decisions\n\n- D1 as written\n")
        report = review.parse_report("impl-review-design-adherence", report_text(
            "impl-review-design-adherence", "BLOCKING",
            [("D1 differs", "BLOCKING", "a.kt:1"), ("D2 differs", "BLOCKING", "a.kt:2")]))

        def behave(name, prompt, harness):
            base = {"lens": "impl-review-design-adherence", "grade": "BLOCKING", "file": "a.kt",
                    "out_of_lens": False}
            records = [{**base, "finding_id": 1, "line": 1, "outcome": "DEFERRED", "amends_approved": True,
                        "reason": "'D1 as written' is approved; the code does otherwise"},
                       {**base, "finding_id": 2, "line": 2, "outcome": "CONTRADICTED", "contradicts_kind": "lens",
                        "contradicts_ref": "code-review-no-leaks", "reason": "no change satisfies both"}]
            fixtures.path_in(prompt, r"(\S+outcomes\.jsonl)").write_text(
                "".join(json.dumps(r) + "\n" for r in records))
            fixtures.finish_progress(prompt)
            return 0
        run = Run(rs, FakeHarness(behave, tmp=tmp), fixtures.TOOLS)
        plan = review.fix_plan([report], "full", {"a.kt"})
        task = asyncio.ensure_future(review.run_fix(run, 0, plan))
        asked = []
        while not task.done():
            ask = run.open_ask()
            if ask and ask["kind"] not in asked:
                asked.append(ask["kind"])
                run.answer(ask["kind"], {})
            await asyncio.sleep(0.01)
        self.assertEqual(asked, ["approved-decision", "blocking-contradicted"])
        self.assertEqual([r["finding_id"] for r in task.result()], [2])


class Grades(unittest.TestCase):
    def test_every_archived_report_parses_to_a_grade_or_malformed(self):
        archive = fixtures.archive_dir()
        if archive is None:
            self.skipTest("no /ship archive on this machine")
        seen = 0
        for path in archive.glob("*/findings/tier*-*.md"):
            if path.name.endswith(tuple(review.RUN_FILES)):
                continue
            report = review.parse_report(path.stem, path.read_text(errors="replace"))
            self.assertIsInstance(report.grade, review.Grade, path)
            seen += 1
        self.assertGreater(seen, 100)

    def test_the_archive_re_triages_to_the_sets_the_coordinator_dispatched(self):
        """Per archived tier with outcomes and graded reports: the fix plan's dispatched (lens, id) pairs at `full`
        against the fix-grade outcome records the hand triage left. 57 of 72 matched when RFC 256 was written; the
        rest are older id schemes (rfc-244, rfc-251), promotions and second passes (rfc-230/231/232/254), and
        findings the fix child left without a record (rfc-234/238/240)."""
        archive = fixtures.archive_dir()
        if archive is None:
            self.skipTest("no /ship archive on this machine")
        triages = {}
        for outcomes in archive.glob("*/findings/tier*-outcomes.jsonl"):
            tier = int(outcomes.name[4:outcomes.name.index("-")])
            key = (outcomes.parent, tier)
            records = triages.setdefault(key, set())
            for line in outcomes.read_text(errors="replace").splitlines():
                try:
                    record = json.loads(line)
                except ValueError:
                    continue
                if record.get("grade") in ("BLOCKING", "SHOULD-FIX"):
                    records.add((record.get("lens"), record.get("finding_id")))
        matched = graded = 0
        for (findings, tier), records in triages.items():
            reports = [review.parse_report(p.name[len(f"tier{tier}-"):-3], p.read_text(errors="replace"))
                       for p in findings.glob(f"tier{tier}-*.md") if not p.name.endswith(review.RUN_FILES)]
            if all(r.grade is review.Grade.MALFORMED for r in reports):
                continue
            graded += 1
            every_file = {f.file for r in reports for f in r.findings}
            plan = review.fix_plan(reports, "full", every_file)
            matched += {(f.lens, f.id) for f in plan["dispatched"]} == records
        self.assertGreaterEqual(graded, 72)
        self.assertGreaterEqual(matched, 57, f"{matched} of {graded} archived triages re-triage to the same set")

    def test_ids_count_tagged_headings_in_file_order_including_out_of_lens(self):
        text = report_text("l", "SHOULD-FIX", [("one", "SHOULD-FIX", "a.kt:1"), ("two", "ADVISORY", "a.kt:2")])
        text += "\n## Out of lens\n\n### three [BLOCKING]\n\n`b.kt:9`\n"
        report = review.parse_report("l", text)
        self.assertEqual([(f.id, f.tag.value, f.out_of_lens) for f in report.findings],
                         [(1, "SHOULD-FIX", False), (2, "ADVISORY", False), (3, "BLOCKING", True)])
        self.assertEqual(report.findings[2].location, "b.kt:9")

    def test_a_report_that_breaks_the_contract_is_malformed_and_says_how(self):
        cases = {
            "no verdict": ("**Grade:** CLEAN\n", "NO_VERDICT"),
            "a grade no tag carries": (report_text("l", "BLOCKING"), "GRADE_ABOVE_TAGS"),
            "a near-miss tag": (report_text("l", "SHOULD-FIX", [("x", "SHOULD-FIX", "a.kt:1")])
                                + "\n### lost [Blocking]\n\n`a.kt:2`\n", "MISTAGGED"),
        }
        for case, (text, reason) in cases.items():
            report = review.parse_report("l", text)
            self.assertEqual((report.grade, report.malformed and report.malformed.name),
                             (review.Grade.MALFORMED, reason), case)

    def test_a_heading_inside_a_fence_neither_ends_a_finding_nor_leaves_out_of_lens(self):
        text = report_text("l", "BLOCKING") + (
            "\n## Out of lens\n\n### elsewhere [BLOCKING]\n\n`b.kt:2`\n\n   ```python\n## not a heading\n"
            "# a comment\n   ```\n\nmore of the finding\n\n### also elsewhere [SHOULD-FIX]\n\n`b.kt:3`\n")
        report = review.parse_report("l", text)
        self.assertEqual([(f.title, f.out_of_lens) for f in report.findings],
                         [("elsewhere", True), ("also elsewhere", True)])
        self.assertIn("more of the finding", report.findings[0].body)

    def test_an_out_of_lens_finding_that_names_no_file_is_dispatched_not_called_outside_the_diff(self):
        text = report_text("l", "BLOCKING") + "\n## Out of lens\n\n### somewhere [BLOCKING]\n\nno location\n"
        report = review.parse_report("l", text)
        self.assertIsNone(report.findings[0].location)
        plan = review.fix_plan([report], "full", {"a.kt"})
        self.assertEqual(([f.title for f in plan["dispatched"]], plan["open"]), (["somewhere"], []))

    def test_a_review_md_without_the_fence_names_the_file_and_the_prefix(self):
        saved = review.REVIEW_MD
        review.REVIEW_MD = Path(tempfile.mkdtemp()) / "review.md"
        review.REVIEW_MD.write_text("no fences here\n")
        try:
            with self.assertRaisesRegex(ValueError, "review.md has no ```text fence starting 'CONTRADICTION:'"):
                review.contradiction_line()
        finally:
            review.REVIEW_MD = saved

    def test_the_fix_plan_holds_advisory_and_adds_should_fix_only_at_full(self):
        report = review.parse_report("l", report_text("l", "BLOCKING", [
            ("b", "BLOCKING", "a.kt:1"), ("s", "SHOULD-FIX", "a.kt:2"), ("a", "ADVISORY", "a.kt:3")]))
        targeted = review.fix_plan([report], "targeted", {"a.kt"})
        full = review.fix_plan([report], "full", {"a.kt"})
        self.assertEqual([f.title for f in targeted["dispatched"]], ["b"])
        self.assertEqual([f.title for f in targeted["held"]], ["s", "a"])
        self.assertEqual([f.title for f in full["dispatched"]], ["b", "s"])
        self.assertEqual([f.title for f in full["held"]], ["a"])

    def test_an_out_of_lens_finding_outside_the_diff_is_an_open_item(self):
        text = report_text("l", "BLOCKING") + "\n## Out of lens\n\n### elsewhere [BLOCKING]\n\n`other.kt:4`\n"
        plan = review.fix_plan([review.parse_report("l", text)], "full", {"a.kt"})
        self.assertEqual(([f.title for f in plan["open"]], plan["dispatched"]), (["elsewhere"], []))


class Contract(unittest.TestCase):
    """The parser and the lens's finished marker read what review.md's graded instruction tells a lens to write."""

    def test_the_parser_and_the_marker_read_what_the_graded_instruction_asks_for(self):
        instruction = review.graded_instruction()
        grade_line = re.search(r"^\s*(\*\*Grade:\*\*.*)$", instruction, re.M).group(1)
        self.assertEqual(grade_line, "**Grade:** " + " | ".join(review.GRADES))
        self.assertRegex(grade_line, children.FINISHED["lens"])
        self.assertEqual(review.TAGS, review.GRADES[1:])
        heading = re.search(r"^\s*(### .*\[[A-Z-]+\])$", instruction, re.M).group(1)
        out_of_lens = re.search(r"^\s*(## Out of lens)$", instruction, re.M).group(1)
        report = review.parse_report("l", f"**Verdict:** 🔴 FAIL\n**Grade:** SHOULD-FIX\n\n{heading}\n\n`a.kt:1`\n"
                                          f"\n{out_of_lens}\n\n### elsewhere [BLOCKING]\n\n`b.kt:2`\n")
        self.assertEqual([(f.tag.value, f.out_of_lens) for f in report.findings],
                         [("SHOULD-FIX", False), ("BLOCKING", True)])


class FixPrompt(unittest.TestCase):
    def test_the_fix_prompt_names_the_outcome_enum_the_deny_set_and_the_suite_reuse_compares(self):
        tmp = tempfile.mkdtemp()
        run = Run(make_rs(tmp, make_repo(tmp)), FakeHarness(tmp=tmp), fixtures.tools(prefix=("nix", "develop", "-c")))
        prompt = review.fix_prompt(run, 1, "fixer", [])
        self.assertIn("|".join(review.OUTCOMES), prompt)
        self.assertIn(", ".join(children.DENY), prompt)
        self.assertIn(f"-g -- {run.tools.suite_cmd}\n", prompt)


class Outcomes(unittest.TestCase):
    def check(self, *records, expected=()):
        path = Path(tempfile.mkdtemp()) / "tier1-outcomes.jsonl"
        path.write_text("".join(r if isinstance(r, str) else json.dumps(r) + "\n" for r in records))
        return review.validate_outcomes(path, expected)

    base = {"lens": "l", "finding_id": 1, "grade": "SHOULD-FIX", "file": "a.kt", "line": 3,
            "out_of_lens": False, "reason": "r"}

    def test_a_good_record_passes(self):
        self.assertEqual(self.check({**self.base, "outcome": "ACCEPTED"}, expected={("l", 1)}), [])

    def test_a_conditional_field_on_the_wrong_arm_is_rejected(self):
        problems = self.check({**self.base, "outcome": "ACCEPTED", "decline_class": "wrong-premise"},
                              expected={("l", 1)})
        self.assertEqual(problems, ["line 1: decline_class present on a ACCEPTED record"])
        problems = self.check({**self.base, "outcome": "CONTRADICTED", "contradicts_kind": "lens"}, expected={("l", 1)})
        self.assertIn("line 1: contradicts_ref missing on a CONTRADICTED record", problems)

    def test_duplicates_strangers_unknown_fields_and_non_objects_are_refused(self):
        good = {**self.base, "outcome": "ACCEPTED"}
        problems = self.check(good, good, {**good, "finding_id": 9}, {**good, "contradict_tier": 1}, "[1]\n",
                              {**good, "grade": "NIT"}, expected={("l", 1)})
        for expected in ("line 2: a second record for l#1", "line 3: l#9 is not a finding of this batch",
                         "line 4: unknown field(s) contradict_tier", "line 5 is not a JSON object",
                         "line 6: grade 'NIT' is not one of ADVISORY, SHOULD-FIX, BLOCKING"):
            self.assertIn(expected, problems)

    def test_a_deferral_says_whether_it_would_amend_an_approved_decision(self):
        deferred = {**self.base, "outcome": "DEFERRED"}
        self.assertIn("line 1: amends_approved missing on a DEFERRED record",
                      self.check(deferred, expected={("l", 1)}))
        self.assertEqual(self.check({**deferred, "amends_approved": False}, expected={("l", 1)}), [])
        self.assertIn("line 1: amends_approved present on a ACCEPTED record",
                      self.check({**self.base, "outcome": "ACCEPTED", "amends_approved": False},
                                 expected={("l", 1)}))

    def test_a_line_that_does_not_parse_and_a_missing_record_are_named(self):
        problems = self.check("{not json\n", expected={("l", 1)})
        self.assertTrue(problems[0].startswith("line 1 does not parse"))
        self.assertIn("no record for l#1", problems)
