"""rank_lenses: the per-lens yield table of RFC 256 D4, recomputed from an archive of /ship runs."""
import collections
import json
from pathlib import Path

from .review import FIX_GRADES, OUTCOME_CLASS, OUTCOMES_FILE, Grade
from .run import lens_ledger

# The enum's classes, plus the archive's spellings from before RFC 234 closed it: the readings the D4 table used.
_OUTCOME_CLASS = {**OUTCOME_CLASS, "applied": "accepted", "fixed": "accepted", "not_applied": "declined",
                  "dissolved": "declined", "HELD": "deferred"}
_FIX_TAGS = tuple(grade.value for grade in FIX_GRADES)
COLUMNS = ("lens", "dispatched", "runs_dispatched", "skipped", "n", "accepted", "declined", "deferred",
           "contradicted", "blocking", "shouldfix", "advisory", "fix_findings", "fix_accepted",
           "fix_declined", "acc_per_dispatch", "fix_decline_rate")


def rank_lenses(archive_dir) -> dict:
    """Return {"rows": [...], "unparsed": ["<path>:<line>: <error>", ...]}; rows sorted by accepted fix-grade
    findings per dispatch."""
    archive = Path(archive_dir)
    dispatched, runs, skipped = _ledger_counts(archive)
    records, unparsed = _outcome_records(archive)
    rows = [_row(lens, dispatched[lens], len(runs[lens]), skipped[lens], records[lens])
            for lens in sorted(set(dispatched) | set(records))]
    rows.sort(key=lambda row: -(row["acc_per_dispatch"] or 0))
    return {"rows": rows, "unparsed": unparsed}


def _ledger_counts(archive: Path) -> tuple:
    dispatched, runs, skipped = collections.Counter(), collections.defaultdict(set), collections.Counter()
    for ledger in archive.glob("*/findings/lens-ledger.tsv"):
        rs = ledger.parent.parent
        for row in lens_ledger(rs):
            if row.event == "start":
                dispatched[row.lens] += 1
                runs[row.lens].add(rs.name)
            elif row.event == "skip":
                skipped[row.lens] += 1
    return dispatched, runs, skipped


def _outcome_records(archive: Path) -> tuple:
    records, unparsed = collections.defaultdict(list), []
    for outcomes in archive.glob(f"*/findings/tier*-{OUTCOMES_FILE}"):
        for number, line in enumerate(outcomes.read_text(errors="replace").splitlines(), 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                records[record["lens"]].append(record)
            except (ValueError, KeyError, TypeError) as error:
                unparsed.append(f"{outcomes}:{number}: {error!r}")
    return records, unparsed


def _row(lens, dispatched, runs_dispatched, skipped, mine) -> dict:
    every = collections.Counter(_OUTCOME_CLASS.get(r.get("outcome")) for r in mine)
    fix = [r for r in mine if r.get("grade") in _FIX_TAGS]
    fixed = collections.Counter(_OUTCOME_CLASS.get(r.get("outcome")) for r in fix)
    grades = collections.Counter(r.get("grade") for r in mine)
    return {
        "lens": lens, "dispatched": dispatched, "runs_dispatched": runs_dispatched, "skipped": skipped,
        "n": len(mine), "accepted": every["accepted"], "declined": every["declined"],
        "deferred": every["deferred"], "contradicted": every["contradicted"],
        "blocking": grades[Grade.BLOCKING.value], "shouldfix": grades[Grade.SHOULD_FIX.value],
        "advisory": grades[Grade.ADVISORY.value],
        "fix_findings": len(fix), "fix_accepted": fixed["accepted"], "fix_declined": fixed["declined"],
        "acc_per_dispatch": round(fixed["accepted"] / dispatched, 2) if dispatched else None,
        "fix_decline_rate": round(fixed["declined"] / len(fix), 2) if fix else None,
    }


def to_tsv(rows) -> str:
    def cell(value):
        return "" if value is None else str(value)
    lines = ["\t".join(COLUMNS)]
    lines += ["\t".join(cell(row[column]) for column in COLUMNS) for row in rows]
    return "\n".join(lines) + "\n"
