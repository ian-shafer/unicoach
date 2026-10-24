"""The run report from files alone: phase 7's sections, and the Spend section of RFC 256 D8."""
import collections
import dataclasses
import datetime
import json
import statistics
from pathlib import Path

from . import review
from .phases import GATE_RECORDS
from .run import archive_root, lens_ledger, read_jsonl, read_record, sh, ship_run_ledger

PROSE_MARK = "<!-- the model's prose: opening -->"
LEARNING_MARK = "<!-- the model's prose: learning candidates -->"


def _ts(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def _span(span: datetime.timedelta) -> str:
    """A span to the whole second, as H:MM:SS."""
    return str(datetime.timedelta(seconds=round(span.total_seconds())))


def _bullets(lines):
    return "\n".join(f"- {line}" for line in lines) if lines else "None"


def tier_section(run, tier) -> str:
    """Markdown for one tier: what was dispatched, skipped, graded and applied."""
    rs = run.rs
    ledger = [row for row in lens_ledger(rs) if row.tier == tier]
    if not ledger:
        return ""
    started = [row.lens for row in ledger if row.event == "start"]
    skips = collections.defaultdict(list)
    for row in ledger:
        if row.event == "skip":
            skips[row.detail].append(row.lens)
    reports = review.tier_reports(run, tier)
    grades = collections.Counter(r.grade.value for r in reports)
    records = review.outcome_records(run, tier)
    by_value = collections.Counter(r.get("outcome") for r in records)
    by_class = collections.Counter(review.OUTCOME_CLASS.get(value) for value in by_value.elements())
    by_lens = collections.defaultdict(collections.Counter)
    for record in records:
        by_lens[record.get("lens")][record.get("outcome")] += 1
    declines = collections.Counter(r["decline_class"] for r in records if "decline_class" in r)
    events = run.events()
    redispatch = [f"{e['lens']} ({e['reason']})" for e in events if e["ev"] == "redispatch" and e["tier"] == tier]
    narrow_failed = [e["error"] for e in events if e["ev"] == "narrow-failed" and e["tier"] == tier]
    fixers = [e["name"] for e in events if e["ev"] == "spawn" and e["role"] == "fix" and e["tier"] == tier]
    missing_file = review.tier_file(rs, tier, review.MISSING_FILE)
    verdict = missing_file.read_text().strip() if missing_file.exists() else "(not run)"
    applied = by_class["accepted"]
    discarded = by_class["declined"] + by_class["deferred"] + by_class["contradicted"]
    out_of_lens = sum(f.out_of_lens for r in reports for f in r.findings)
    narrowing = "; ".join(narrow_failed) + " — every lens dispatched" if narrow_failed else "no failure"
    return f"""### Tier {tier}

- Lenses dispatched: {len(set(started))}; skipped: {sum(len(v) for v in skips.values())}
{"".join(f"  - skipped ({len(v)}): {reason} — {', '.join(sorted(v))}{chr(10)}" for reason, v in skips.items())}- Grades: {', '.join(f"{g} {n}" for g, n in sorted(grades.items())) or 'none'}
- Re-dispatched: {', '.join(redispatch) or 'did not fire'}
- Narrowing: {narrowing}
- Fix children: {len(fixers)}
- Applied {applied}, discarded {discarded}; outcomes by value: {dict(by_value) or 'none'}
- Outcomes by lens: {', '.join(f"{lens} {dict(c)}" for lens, c in sorted(by_lens.items())) or 'none'}
- Decline classes: {dict(declines) or 'none'}
- Out of lens: {out_of_lens}
- `ship-lens missing` at close: {verdict or 'nothing'}
"""


def tier_findings(run, tier, scrutiny, changed) -> dict:
    """The tier's held, open and out-of-lens findings, for the report's sections after the tiers."""
    reports = review.tier_reports(run, tier)
    plan = review.fix_plan(reports, scrutiny, changed)
    return {"held": plan["held"], "open": plan["open"],
            "out_of_lens": [f for r in reports if r.grade is not review.Grade.MALFORMED
                            for f in r.findings if f.out_of_lens]}


def outcome_lines(run) -> tuple:
    """Deferred and contradicted outcome records across the tiers as report lines, and contradictions by kind: both
    taken from the records."""
    records = [record for tier in review.TIERS for record in review.outcome_records(run, tier)]
    deferred = [f"{_where(r)} — {r.get('reason')} (owner: Ian)" for r in records if r.get("outcome") == "DEFERRED"]
    contra = [r for r in records if r.get("outcome") == "CONTRADICTED"]
    contradicted = [f"{_where(r)} vs {r.get('contradicts_kind')}:{r.get('contradicts_ref')} — {r.get('reason')}"
                    for r in contra]
    return deferred, contradicted, collections.Counter(r.get("contradicts_kind") for r in contra)


def _where(record) -> str:
    return f"{record.get('lens')}#{record.get('finding_id')} `{record.get('file')}:{record.get('line')}`"


def suite_section(rs) -> str:
    lines = []
    for name in GATE_RECORDS:
        record = read_record(Path(rs) / "runs" / f"{name}.record")
        if record:
            counts = ", ".join(f"{k}={record[k]}" for k in ("tests", "failures", "errors", "skipped") if k in record)
            lines.append(f"`{record.get('cmd')}` exit={record.get('exit')} tree={record.get('tree')}"
                         + (f" — executed: {counts}" if counts else " — no JUnit counts"))
    return _bullets(lines)


def ship_run_counts(rs) -> dict:
    return dict(collections.Counter(row.fields.get("caller") for row in ship_run_ledger(rs) if row.event == "start"))


def machine_time(rs) -> datetime.timedelta:
    open_runs, total = {}, datetime.timedelta()
    for row in ship_run_ledger(rs):
        name = row.fields.get("name")
        if row.event == "start":
            open_runs[name] = _ts(row.at)
        elif row.event == "end" and name in open_runs:
            total += _ts(row.at) - open_runs.pop(name)
    return total


# ── session logs ─────────────────────────────────────────────────────────
@dataclasses.dataclass(frozen=True)
class SessionCount:
    turns: int
    messages: int
    tokens: int
    unread: tuple = ()  # "<log>:<line>: <error>" for each line that did not parse


def assistant_messages(log: Path, start=None, end=None) -> SessionCount:
    """Turns, assistant messages and totalTokens in a session jsonl; a turn starts at an assistant message that
    follows anything but an assistant message or a tool result. Spend is best effort: a line that does not parse (a
    session deleted mid-write leaves a torn one) is named in `unread` and the rest are counted."""
    turns = messages = tokens = 0
    unread, previous = [], None
    for number, line in enumerate(log.read_text(errors="replace").splitlines(), 1):
        try:
            entry = json.loads(line)
        except ValueError as bad:
            unread.append(f"{log}:{number}: {bad}")
            continue
        message = entry.get("message") if isinstance(entry.get("message"), dict) else {}
        role = message.get("role") if entry.get("type") == "message" else entry.get("type")
        at = entry.get("timestamp")
        inside = at is None or ((start is None or _ts(at) >= start) and (end is None or _ts(at) <= end))
        if role == "assistant" and inside:
            messages += 1
            usage = message.get("usage") if isinstance(message.get("usage"), dict) else {}
            tokens += usage.get("totalTokens", 0)
            if previous not in ("assistant", "toolResult"):
                turns += 1
        previous = role
    return SessionCount(turns, messages, tokens, tuple(unread))


def child_session_files(agent_dir) -> tuple:
    """(childId -> the child's session file from the rlm-ledger's spawn rows, the ledger lines that did not parse)."""
    files, unread = {}, []
    for ledger in Path(agent_dir).glob("rlm-ledger/*.jsonl"):
        for number, line in enumerate(ledger.read_text(errors="replace").splitlines(), 1):
            try:
                row = json.loads(line)
            except ValueError as bad:
                unread.append(f"{ledger}:{number}: {bad}")
                continue
            if row.get("op") == "spawn":
                files[row["childId"]] = Path(row["child"])
    return files, unread


def spend(run) -> str:
    """From files the run recorded: the coordinator's log and Prime Agent's directory were bound at start()."""
    events = run.events()
    if not events:
        return "No journal."
    session = run.session()
    coordinator, unread = coordinator_line(run, session, events)
    children, child_unread = children_lines(session, events)
    per_phase = ", ".join(f"{p} {_span(s)}" for p, s in phase_spans(events).items()) or "none"
    return _bullets([
        coordinator,
        *children,
        f"Wall-clock per phase: {per_phase}",
        f"Machine time (ship-run spans): {_span(machine_time(run.rs))}",
        f"ship-run invocations per caller: {ship_run_counts(run.rs) or 'none'}",
        f"Unread log lines: {'; '.join(unread + child_unread) or 'none'}",
    ])


def coordinator_line(run, session, events) -> tuple:
    """The coordinator's turns split judgement / absorbed, and the coordinator log lines that did not parse."""
    start, end = _ts(events[0]["at"]), _ts(events[-1]["at"])
    answers = len(list((run.dir / "answers").glob("*.json")))
    wakes = len(read_jsonl(run.dir / "wakes.jsonl"))
    log = Path(session["coord_log"]) if session.get("coord_log") else None
    if not (log and log.is_file()):
        return (f"Coordinator model turns: unmeasured (no session log at {log}); {answers} judgement answers, "
                f"{wakes} wakes issued"), []
    count = assistant_messages(log, start, end)
    return (f"Coordinator model turns: {count.turns} ({answers} judgement, {max(count.turns - answers, 0)} absorbed), "
            f"{count.tokens} tokens; {wakes} wakes issued"), list(count.unread)


def children_lines(session, events) -> tuple:
    """Children by role with their tokens, the unmeasured ones, the median lens calls; and the unread lines."""
    files, unread = child_session_files(session["agent_dir"]) if session.get("agent_dir") else ({}, [])
    roles, tokens_by_role, unmeasured, lens_calls = collections.Counter(), collections.Counter(), [], []
    for event in (e for e in events if e["ev"] == "spawn"):
        roles[event["role"]] += 1
        path = files.get(event["child_id"])
        count = assistant_messages(path) if path and path.exists() else SessionCount(0, 0, 0)
        unread += count.unread
        if count.tokens == 0:
            unmeasured.append(event["name"])
        tokens_by_role[event["role"]] += count.tokens
        if event["role"] == "lens" and count.messages:
            lens_calls.append(count.messages)
    return [
        "Children: " + (", ".join(f"{role} {n} ({tokens_by_role[role]} tokens)"
                                  for role, n in sorted(roles.items())) or "none"),
        f"Unmeasured children: {', '.join(unmeasured) or 'none'}",
        f"Median lens model calls: {statistics.median(lens_calls) if lens_calls else 'unmeasured'}",
    ], unread


def phase_spans(events) -> dict:
    """Wall-clock per phase from its enter/exit events (a sub-step's carry a `step`); a phase still open — the one
    this report is written from — runs to the last event."""
    end = _ts(events[-1]["at"])
    phases, opened = collections.defaultdict(datetime.timedelta), {}
    for event in events:
        if event["ev"] not in ("enter", "exit") or "step" in event:
            continue
        if event["ev"] == "enter":
            opened[event["phase"]] = _ts(event["at"])
        elif event["phase"] in opened:
            phases[event["phase"]] += _ts(event["at"]) - opened.pop(event["phase"])
    for phase, began in opened.items():
        phases[phase] += end - began
    return phases


# ── the report ───────────────────────────────────────────────────────────
async def write(run):
    state = run.state()
    base = state["BASE_SHA"]
    scrutiny = state.get("DIAL_SCRUTINY", "full")
    changed = set(await run.changed_paths())
    stat = (await run.git("diff", "--stat", base, "HEAD")).out.rstrip()
    tiers = [tier_section(run, tier) for tier in review.TIERS]
    found = [tier_findings(run, tier, scrutiny, changed) for tier in review.TIERS]
    held = [f for t in found for f in t["held"]]
    open_items = [f for t in found for f in t["open"]]
    out_of_lens = [f for t in found for f in t["out_of_lens"]]
    deferred, contradicted, kinds = outcome_lines(run)
    identity = f"RFC {state['RFC_NUM']} ({state.get('RFC_PATH', '')})" if state.get("RFC_NUM") else f"slug {state.get('SLUG')}"
    text = f"""# {state['RUN_ID']}

{PROSE_MARK}

## Run

- {identity}, branch `{state['BRANCH']}`, base `{state['BASE_BRANCH']}@{base[:8]}`
- Dials: design={state.get('DIAL_DESIGN')}, scrutiny={scrutiny}, approval={state.get('DIAL_APPROVAL')}, tests: {state.get('DIAL_TESTS')}

## What was built

```text
{stat}
```

## Review

{chr(10).join(t for t in tiers if t) or 'No lens ran (scrutiny none, or nothing to review).'}

## Tests

{suite_section(run.rs)}

## Open items

{_bullets(deferred + [f"{f.lens}#{f.id} `{f.location}` {f.title} — outside this run's diff (owner: Ian)" for f in open_items])}

## Advisory findings (not applied)

{_bullets([f"{f.lens} `{f.location or '?'}` {f.title}" for f in held])}

## Contradictions

{_bullets(contradicted)}

By kind: {dict(kinds) or 'none'}

## Seen but not owned

{_bullets([f"{f.lens} `{f.location or '?'}` [{f.tag.value}] {f.title}" for f in out_of_lens])}

## Learning candidates

{LEARNING_MARK}

Archive: `{archive_root(state['ORIGINAL_CHECKOUT']) / state['RUN_ID']}/`
"""
    (run.rs / "report.md").write_text(text)


def add_prose(run, prose):
    path = run.rs / "report.md"
    text = path.read_text()
    text = text.replace(PROSE_MARK, prose.opening.strip() or PROSE_MARK)
    text = text.replace(LEARNING_MARK, prose.learning_candidates.strip() or "None")
    path.write_text(text)


async def finish(run, sha, state):
    """After ship-land: the landed SHA with --stat, and the Spend section, appended to the archived report."""
    original = state["ORIGINAL_CHECKOUT"]
    stat = (await sh(["git", "-C", original, "show", "--stat", "--format=%h %s", sha], check=True)).out.rstrip()
    with open(run.rs / "report.md", "a") as file:
        file.write(f"\n## Landed\n\n```text\n{stat}\n```\n\n## Spend\n\n{spend(run)}\n")
