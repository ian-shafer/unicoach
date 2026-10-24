import json
import tempfile
import unittest
from pathlib import Path

import fixtures
from ship_driver.ranking import COLUMNS, rank_lenses, to_tsv


def write_run(archive, run, ledger, outcomes):
    findings = archive / run / "findings"
    findings.mkdir(parents=True)
    (findings / "lens-ledger.tsv").write_text("".join("\t".join(row) + "\n" for row in ledger))
    (findings / "tier1-outcomes.jsonl").write_text(
        "".join(o if isinstance(o, str) else json.dumps(o) + "\n" for o in outcomes))


class Ranking(unittest.TestCase):
    def test_three_runs_produce_the_yield_columns_and_count_an_unparsed_line(self):
        archive = Path(tempfile.mkdtemp())
        rec = lambda grade, outcome: {"lens": "lens-a", "grade": grade, "outcome": outcome}
        write_run(archive, "r1", [("start", "1", "lens-a", "c1"), ("start", "1", "lens-b", "c2")],
                  [rec("SHOULD-FIX", "ACCEPTED"), rec("ADVISORY", "ACCEPTED"), "not json\n"])
        write_run(archive, "r2", [("start", "1", "lens-a", "c3"), ("skip", "1", "lens-b", "why")],
                  [rec("BLOCKING", "DECLINED"), rec("SHOULD-FIX", "PARTIAL")])
        write_run(archive, "r3", [("start", "1", "lens-a", "c4")], [rec("SHOULD-FIX", "CONTRADICTED")])
        result = rank_lenses(archive)
        self.assertEqual(len(result["unparsed"]), 1)
        self.assertRegex(result["unparsed"][0], r"r1/findings/tier1-outcomes\.jsonl:3: JSONDecodeError")
        rows = {row["lens"]: row for row in result["rows"]}
        a = rows["lens-a"]
        self.assertEqual((a["dispatched"], a["runs_dispatched"], a["n"]), (3, 3, 5))
        self.assertEqual((a["fix_findings"], a["fix_accepted"], a["fix_declined"], a["contradicted"]), (4, 2, 1, 1))
        self.assertEqual(a["acc_per_dispatch"], 0.67)
        self.assertEqual(a["fix_decline_rate"], 0.25)
        self.assertEqual((rows["lens-b"]["dispatched"], rows["lens-b"]["skipped"]), (1, 1))
        self.assertIsNone(rows["lens-b"]["fix_decline_rate"])

    def test_a_fixture_archive_gives_exactly_the_checked_table(self):
        """Every column, both outcome spellings (RFC 234's enum and the archive's older words), and the sort."""
        archive = Path(tempfile.mkdtemp())
        rec = lambda lens, grade, outcome: {"lens": lens, "grade": grade, "outcome": outcome}  # noqa: E731
        write_run(archive, "r1", [("start", "1", "lens-a", "c1"), ("start", "1", "lens-b", "c2"),
                                  ("skip", "2", "lens-c", "why")],
                  [rec("lens-a", "SHOULD-FIX", "applied"), rec("lens-a", "ADVISORY", "HELD"),
                   rec("lens-b", "BLOCKING", "not_applied"), rec("lens-b", "SHOULD-FIX", "fixed")])
        write_run(archive, "r2", [("start", "1", "lens-a", "c3"), ("start", "2", "lens-c", "c4")],
                  [rec("lens-a", "BLOCKING", "ACCEPTED"), rec("lens-a", "SHOULD-FIX", "DEFERRED"),
                   rec("lens-c", "SHOULD-FIX", "dissolved"), rec("lens-c", "SHOULD-FIX", "CONTRADICTED"),
                   "garbage\n"])
        result = rank_lenses(archive)
        self.assertEqual(len(result["unparsed"]), 1)
        self.assertEqual(to_tsv(result["rows"]), "\t".join(COLUMNS) + "\n" + (
            "lens-a\t2\t2\t0\t4\t2\t0\t2\t0\t1\t2\t1\t3\t2\t0\t1.0\t0.0\n"
            "lens-b\t1\t1\t0\t2\t1\t1\t0\t0\t1\t1\t0\t2\t1\t1\t1.0\t0.5\n"
            "lens-c\t1\t1\t1\t2\t0\t1\t0\t1\t0\t2\t0\t2\t0\t1\t0.0\t0.5\n"))

    def test_the_archive_reproduces_the_rfc_table(self):
        archive = fixtures.archive_dir()
        if archive is None:
            self.skipTest("no /ship archive on this machine")
        rows = {row["lens"]: row for row in rank_lenses(archive)["rows"]}
        concrete = rows["code-review-concrete-names"]
        self.assertGreaterEqual(concrete["dispatched"], 24)
        self.assertEqual(concrete["fix_accepted"], 0)
        self.assertGreater(rows["code-review-dry-abstractions"]["acc_per_dispatch"], 2)
