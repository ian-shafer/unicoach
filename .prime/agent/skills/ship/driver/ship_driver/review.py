"""One review tier as code (RFC 256 D4, D5): dispatch set, skips, lens dispatch, grades, fix plan, fix child."""
import collections
import dataclasses
import enum
import json
import re
from pathlib import Path

from . import children
from .answers import RedispatchLens
from .applicability import LENS_GROUP, classify, skip_reason
from .run import DRIVER_DIR, ScriptFailed, lens_ledger, read_jsonl

REVIEW_MD = DRIVER_DIR.parent / "references" / "review.md"
# Tiers 0..3 of .claude/skills/review-fix/tiers.md, in the order verify runs them.
TIERS = range(4)


class Grade(enum.Enum):
    CLEAN = "CLEAN"
    ADVISORY = "ADVISORY"
    SHOULD_FIX = "SHOULD-FIX"
    BLOCKING = "BLOCKING"
    MALFORMED = "malformed"  # the report breaks the output contract (Report.malformed says how): never planned


GRADES = tuple(grade.value for grade in Grade if grade is not Grade.MALFORMED)
# The tags a finding heading carries, lowest first: a report's grade is the highest of them.
TAG_GRADES = (Grade.ADVISORY, Grade.SHOULD_FIX, Grade.BLOCKING)
TAGS = tuple(grade.value for grade in TAG_GRADES)
# Planned at scrutiny full; BLOCKING alone otherwise. The lens ranking counts the same set.
FIX_GRADES = (Grade.BLOCKING, Grade.SHOULD_FIX)
# The closed outcome enum (review.md), each value with the class the report and the lens ranking count it under.
OUTCOME_CLASS = {"ACCEPTED": "accepted", "PARTIAL": "accepted", "DECLINED": "declined", "DEFERRED": "deferred",
                 "CONTRADICTED": "contradicted"}
OUTCOMES = tuple(OUTCOME_CLASS)
CONTRADICTS_KINDS = ("lens", "house", "rfc", "tier")
# The outcome record's always-present fields with their JSON types, then the fields that exist on one arm only.
OUTCOME_FIELDS = (("lens", str), ("finding_id", int), ("grade", str), ("file", str), ("line", int),
                  ("outcome", str), ("out_of_lens", bool), ("reason", str))
ARM_FIELDS = ("decline_class", "contradicts_kind", "contradicts_ref", "contradicts_tier", "amends_approved")
SCRUTINY_NONE_REASON = "scrutiny none: the suite and the hook are the review"
# Written by the run itself, never a lens report. scripts/ship-lens's RUN_WRITTEN holds the same four names (a bash
# script cannot read this tuple); a drift there only lets a run-written .md parse as a malformed report.
FIX_PLAN, FIX_REPORT, OUTCOMES_FILE, MISSING_FILE = RUN_FILES = (
    "fix-plan.md", "fix-report.md", "outcomes.jsonl", "missing.txt")
# The ship-lens missing fault kinds a tier repairs by dispatching; any other kind at tier open is refused.
REPAIRED_FAULTS = ("UNDISPATCHED", "STALLED")


def tier_file(rs, tier, name) -> Path:
    return Path(rs) / "findings" / f"tier{tier}-{name}"


# ── the texts a child reads, read from review.md so the reference and the dispatch cannot drift ──
def text_fences(markdown: str) -> list:
    return re.findall(r"^```text\n(.*?)^```", markdown, re.M | re.S)


def fence_starting(prefix: str) -> str:
    fences = text_fences(REVIEW_MD.read_text())
    for body in fences:
        if body.startswith(prefix):
            return body
    raise ValueError(f"{REVIEW_MD} has no ```text fence starting {prefix!r}; its fences start: "
                     f"{[fence[:40] for fence in fences]}")


def graded_instruction() -> str:
    return fence_starting("Output per `.claude/skills/findings-output.template.md`, PLUS:")


def file_opening_clause() -> str:
    return fence_starting("Everything you need is in this dispatch.")


def contradiction_line() -> str:
    return fence_starting("CONTRADICTION:")


# ── reports ──────────────────────────────────────────────────────────────
@dataclasses.dataclass
class Finding:
    lens: str
    id: int
    title: str
    tag: Grade
    out_of_lens: bool
    body: str

    @property
    def location(self) -> str | None:
        match = re.search(r"`([^`\s]+:\d+)`", self.body)
        return match.group(1) if match else None

    @property
    def file(self) -> str | None:
        return self.location.rsplit(":", 1)[0] if self.location is not None else None


class Malformed(enum.Enum):
    """How a report breaks the output contract; the re-dispatch and the malformed-twice Ask name it."""
    NO_GRADE = "no `**Grade:**` line naming one of " + ", ".join(GRADES)
    NO_VERDICT = "no `**Verdict:**` line"
    UNTAGGED_FAIL = "a FAIL verdict with no finding heading tagged " + ", ".join(f"[{tag}]" for tag in TAGS)
    MISTAGGED = "a finding heading tagged with something other than " + ", ".join(f"[{tag}]" for tag in TAGS)
    GRADE_ABOVE_TAGS = "a `**Grade:**` above every tag its findings carry: the finding it grades is not tagged"


@dataclasses.dataclass
class Report:
    lens: str
    grade: Grade
    findings: list
    malformed: Malformed | None = None  # set exactly when grade is Grade.MALFORMED


_HEADING = re.compile(r"^(#{1,3})\s+(.*?)\s*$", re.M)
_TAG = re.compile(r"^(.*?)\s*\[(" + "|".join(TAGS) + r")\]$")
# A bracketed word that reads as a tag attempt — `[Blocking]`, `[MUST-FIX]`, `[BLOCKING] (out of lens)` — on a
# heading _TAG does not accept: a finding the parser would otherwise drop without a trace.
_TAG_ATTEMPT = re.compile(r"\[(?:[A-Z][A-Z -]*[A-Z]|(?i:advisory|should-fix|blocking))\]")
_FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,}).*?^[ \t]*\1\s*$", re.M | re.S)


def parse_report(lens: str, text: str) -> Report:
    grade = re.search(r"^\*\*Grade:\*\*\s*(" + "|".join(GRADES) + r")\s*$", text, re.M)
    verdict = re.search(r"^\*\*Verdict:\*\*(.*)$", text, re.M)
    fences = [match.span() for match in _FENCE.finditer(text)]
    headings = [h for h in _HEADING.finditer(text) if not any(start <= h.start() < end for start, end in fences)]
    findings, out_of_lens, mistagged = [], False, False
    for index, heading in enumerate(headings):
        level, title = len(heading.group(1)), heading.group(2)
        if level <= 2:
            out_of_lens = title.lower().startswith("out of lens")
            continue
        tagged = _TAG.match(title)
        if not tagged:
            mistagged = mistagged or bool(_TAG_ATTEMPT.search(title))
            continue
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        findings.append(Finding(lens, len(findings) + 1, tagged.group(1), Grade(tagged.group(2)), out_of_lens,
                                text[heading.start():end].strip()))
    # A grade BELOW a tag is harmless (planning reads the tags; lenses often grade in-lens findings only); one ABOVE
    # every tag means the finding it grades was lost.
    rank = [Grade.CLEAN, *TAG_GRADES]
    carried = max((rank.index(f.tag) for f in findings), default=0)
    malformed = (Malformed.NO_GRADE if grade is None else
                 Malformed.NO_VERDICT if verdict is None else
                 Malformed.MISTAGGED if mistagged else
                 Malformed.UNTAGGED_FAIL if "FAIL" in verdict.group(1) and not findings else
                 Malformed.GRADE_ABOVE_TAGS if rank.index(Grade(grade.group(1))) > carried else None)
    if malformed is not None:
        return Report(lens, Grade.MALFORMED, findings, malformed)
    return Report(lens, Grade(grade.group(1)), findings)


def report_path(run, tier, lens) -> Path:
    return run.rs / "findings" / f"tier{tier}-{lens}.md"


# ── the ledger ──────────────────────────────────────────────────────────
async def missing(run, tier):
    """ship-lens missing as (Proc, faults): exit 0 is closed, 1 lists the faults, one tab-split tuple per line. Any
    other exit is ship-lens unable to answer, and raises with its whole stderr."""
    proc = await run.script("ship-lens", "missing", "-t", tier, check=False)
    if proc.code not in (0, 1):
        raise ScriptFailed(["ship-lens", "missing", "-t", tier], proc)
    return proc, [tuple(line.split("\t")) for line in proc.out.splitlines() if line.strip()]


async def skip(run, tier, lens, reason):
    await run.script("ship-lens", "skip", "-t", tier, "-l", lens, "-r", reason)


# ── lens dispatch ────────────────────────────────────────────────────────
def lens_prompt(run, tier, lens, diff, rfc_text=None, quoted=None) -> str:
    """`quoted` is the Malformed reason of the report being replaced, for a re-dispatch."""
    corpus = run.root / ".claude" / "skills"
    parts = [
        f"# Lens: {lens} (tier {tier} of /ship run {run.run_id})\n\n" + (corpus / lens / "SKILL.md").read_text(),
        "# Output template\n\n" + (corpus / "findings-output.template.md").read_text(),
        (f"Your previous report had {quoted.value}. This is the instruction, quoted; follow it exactly.\n\n"
         if quoted else "") + graded_instruction() + "\n" + file_opening_clause(),
    ]
    if rfc_text is not None:
        parts.append("# The RFC this implementation is measured against\n\n" + rfc_text)
    parts += [
        "# The diff under review\n\n```diff\n" + diff + "\n```",
        f"Write your report to {report_path(run, tier, lens)} and change no other file.",
        "Reply with ONE tab-separated line: lens, grade, findings, files opened.",
    ]
    return "\n\n".join(parts)


async def dispatch_lens(run, tier, lens, diff, rfc_text, quoted=None):
    report = report_path(run, tier, lens)
    child = await children.spawn(run, "lens", f"{run.run_id}-t{tier}-{lens}",
                                 lambda name: lens_prompt(run, tier, lens, diff, rfc_text, quoted),
                                 marker_for=lambda name: report, tier=tier)
    await run.script("ship-lens", "start", "-t", tier, "-l", lens, "-c", child.child_id)
    return child


class _LensSettling:
    """One tier's lens children, until each has a graded report, a re-dispatch, or a skip. A stalled child is deleted
    whatever happens next: one past its ceiling streams on otherwise."""

    def __init__(self, run, tier, diff, rfc_text):
        self.run, self.tier, self.diff, self.rfc_text = run, tier, diff, rfc_text
        self.dispatches = collections.Counter()
        self.nudged = set()

    async def settle(self, dispatched):
        pending = dict(dispatched)  # lens -> Child
        self.dispatches.update(pending.keys())
        while pending:
            status = await children.wait(self.run, list(pending.values()), ceiling=children.LENS_CEILING)
            again = {}
            for lens, child in pending.items():
                successor = await self.settle_one(lens, child, status[child.name])
                if successor is not None:
                    again[lens] = successor
            pending = again

    async def settle_one(self, lens, child, status):
        """The child to wait on next for this lens, or None once the lens is settled (done or skipped)."""
        path = report_path(self.run, self.tier, lens)
        if status is children.Settled.IDLE and child.name not in self.nudged:
            self.nudged.add(child.name)
            await children.nudge(self.run, child, f"write your report to {path} with its `**Grade:**` line")
            return child
        if not path.exists() or path.stat().st_size == 0:
            return await self.on_stalled(lens, child, status)
        report = parse_report(lens, path.read_text())
        if report.grade is Grade.MALFORMED:
            return await self.on_malformed(lens, child, path, report.malformed)
        await self.run.script("ship-lens", "done", "-t", self.tier, "-l", lens)
        return None

    async def on_stalled(self, lens, child, status):
        await self.run.harness.delete(child.child_id)
        if self.dispatches[lens] < children.LENS_DISPATCHES:
            return await self.redispatch(lens, child, "stalled")
        await skip(self.run, self.tier, lens, f"child stalled twice ({status.value})")
        return None

    async def on_malformed(self, lens, child, path, malformed):
        if self.dispatches[lens] < children.LENS_DISPATCHES:
            return await self.redispatch(lens, child, f"malformed: {malformed.name}", malformed,
                                         keep_as="report.malformed.md")
        answer = await self.run.ask("malformed-twice", f"tier {self.tier} {lens} is still malformed after a "
                                    f"re-dispatch ({malformed.value}); answer {{'skip': '<reason>'}} or "
                                    "{'redispatch': '<why a third differs>'}",
                                    tier=self.tier, lens=lens, report=str(path), malformed=malformed.name)
        if isinstance(answer, RedispatchLens):
            return await self.redispatch(lens, child, f"model: {answer.redispatch}", malformed,
                                         keep_as="report.malformed-2.md")
        await skip(self.run, self.tier, lens, answer.skip)
        return None

    async def redispatch(self, lens, child, reason, quoted=None, keep_as=None):
        if keep_as:
            report_path(self.run, self.tier, lens).rename(child.dir / keep_as)
        self.dispatches[lens] += 1
        self.run.journal("redispatch", tier=self.tier, lens=lens, reason=reason)
        return await dispatch_lens(self.run, self.tier, lens, self.diff, self.rfc_text, quoted)


async def settle_lenses(run, tier, dispatched, diff, rfc_text):
    """Wait on the tier's lens children; wake a stopped lens once, re-dispatch a stalled or malformed lens up to
    LENS_DISPATCHES, then skip or ask."""
    await _LensSettling(run, tier, diff, rfc_text).settle(dispatched)


def narrowed(path: Path, lenses) -> dict:
    """The narrowing child's removals if its result is exactly {"remove": {<offered lens>: <reason>}}; ValueError
    naming the file and what it holds otherwise."""
    text = path.read_text() if path.exists() else ""
    try:
        answer = json.loads(text)
    except ValueError as bad:
        raise ValueError(f"{path} does not parse as JSON ({bad}): [{text[:500]}]") from None
    remove = answer.get("remove") if isinstance(answer, dict) and set(answer) == {"remove"} else None
    if (not isinstance(remove, dict) or not set(remove) <= set(lenses)
            or not all(isinstance(why, str) and why.strip() for why in remove.values())):
        raise ValueError(f"{path} is not {{\"remove\": {{\"<offered lens>\": \"<why>\"}}}}: [{text[:500]}]")
    return remove


async def narrow(run, tier, lenses, diff) -> list:
    """Dials(narrow=True): one cheap child may only REMOVE lenses; each removal is a `narrowed:` skip. Narrowing is
    optional, so a result that cannot be used removes nothing: the journal and the report say why."""
    corpus = run.root / ".claude" / "skills"
    descriptions = "\n".join(f"- {lens}: {_description(corpus / lens / 'SKILL.md')}" for lens in lenses)

    def prompt(name):
        return (f"Decide which of these review lenses CANNOT fire on the diff below.\n\n{descriptions}\n\n"
                f"```diff\n{diff}\n```\n\nWrite {narrowing_result(run, name)} as JSON: "
                "{\"remove\": {\"<lens>\": \"<one sentence why>\"}}. "
                "Remove only a lens that certainly cannot fire. Do not message; the file is the answer.")
    child = await children.spawn(run, "narrowing", f"{run.run_id}-t{tier}-narrow", prompt, model=children.NARROW_MODEL,
                                 marker_for=lambda name: narrowing_result(run, name), tier=tier)
    status = await children.wait(run, [child], ceiling=children.LENS_CEILING)
    if status[child.name] is not children.Settled.DONE:
        await run.harness.delete(child.child_id)
    try:
        if status[child.name] is not children.Settled.DONE:
            raise ValueError(f"the narrowing child settled {status[child.name].value}")
        removed = narrowed(child.marker, lenses)
    except ValueError as bad:
        run.journal("narrow-failed", tier=tier, error=str(bad))
        return lenses
    for lens, why in removed.items():
        await skip(run, tier, lens, f"narrowed: {why}")
    return [lens for lens in lenses if lens not in removed]


def narrowing_result(run, name) -> Path:
    return children.child_dir(run, name) / "result.json"


def _description(skill_md: Path) -> str:
    match = re.search(r"^description:\s*(.*)$", skill_md.read_text(), re.M)
    return match.group(1).strip() if match else ""


# ── triage ───────────────────────────────────────────────────────────────
def fix_plan(reports, scrutiny, changed) -> dict:
    """BLOCKING always, SHOULD-FIX at full; ADVISORY held; out-of-lens only inside this run's diff. A malformed
    report was skipped, so none of its findings is planned. An out-of-lens finding that names no file cannot be shown
    to be outside the diff, so the fix child judges it."""
    plan = {"dispatched": [], "held": [], "open": []}
    fixable = FIX_GRADES if scrutiny == "full" else (Grade.BLOCKING,)
    for report in reports:
        if report.grade is Grade.MALFORMED:
            continue
        for finding in report.findings:
            if finding.tag not in fixable:
                plan["held"].append(finding)
            elif finding.out_of_lens and finding.file is not None and finding.file not in changed:
                plan["open"].append(finding)
            else:
                plan["dispatched"].append(finding)
    return plan


def finding_line(finding) -> str:
    return f"{finding.lens}#{finding.id} [{finding.tag.value}] `{finding.location or '?'}` {finding.title}"


def write_fix_plan(run, tier, plan, contradicted=()):
    def lines(findings):
        return "\n".join(f"- {finding_line(f)}" for f in findings) or "None"
    contra = "\n".join(f"- {r['lens']}#{r['finding_id']} vs {r['contradicts_kind']}:{r['contradicts_ref']} "
                        f"(tier{tier}-{OUTCOMES_FILE})" for r in contradicted) or "None"
    tier_file(run.rs, tier, FIX_PLAN).write_text(
        f"# Tier {tier} fix plan\n\n## Dispatched\n\n{lines(plan['dispatched'])}\n\n"
        f"## Held as advisory\n\n{lines(plan['held'])}\n\n"
        f"## Open items (outside this run's diff)\n\n{lines(plan['open'])}\n\n"
        f"## Promoted or demoted\n\nNone — tags are applied as graded (RFC 256 D5).\n\n"
        f"## Contradicted\n\n{contra}\n")


def validate_outcomes(path: Path, expected) -> list:
    """Exactly one record per dispatched finding, every line a JSON object of known fields, each field on its own
    arm."""
    problems, seen = [], set()
    known = {field for field, _ in OUTCOME_FIELDS} | set(ARM_FIELDS)
    lines = path.read_text().splitlines() if path.exists() else []
    for number, line in enumerate(lines, 1):
        try:
            record = json.loads(line)
        except ValueError as bad:
            problems.append(f"line {number} does not parse: {bad}")
            continue
        if not isinstance(record, dict):
            problems.append(f"line {number} is not a JSON object")
            continue
        surplus = sorted(set(record) - known)
        if surplus:
            problems.append(f"line {number}: unknown field(s) {', '.join(surplus)}")
        for field, kind in OUTCOME_FIELDS:
            if not isinstance(record.get(field), kind) or (kind is int and isinstance(record.get(field), bool)):
                problems.append(f"line {number}: {field} missing or not {kind.__name__}")
        if record.get("grade") not in TAGS:
            problems.append(f"line {number}: grade {record.get('grade')!r} is not one of {', '.join(TAGS)}")
        outcome = record.get("outcome")
        if outcome not in OUTCOMES:
            problems.append(f"line {number}: outcome {outcome!r} is not one of {', '.join(OUTCOMES)}")
        arms = {"decline_class": outcome == "DECLINED", "contradicts_kind": outcome == "CONTRADICTED",
                "contradicts_ref": outcome == "CONTRADICTED",
                "contradicts_tier": record.get("contradicts_kind") == "tier",
                "amends_approved": outcome == "DEFERRED"}
        for field, applies in arms.items():
            if (field in record) != applies:
                problems.append(f"line {number}: {field} {'missing' if applies else 'present'} on a {outcome} record")
        if "contradicts_kind" in record and record["contradicts_kind"] not in CONTRADICTS_KINDS:
            problems.append(f"line {number}: contradicts_kind {record['contradicts_kind']!r} is not a known kind")
        if "amends_approved" in record and not isinstance(record["amends_approved"], bool):
            problems.append(f"line {number}: amends_approved is not bool")
        key = (record.get("lens"), record.get("finding_id"))
        if key not in expected:
            problems.append(f"line {number}: {key[0]}#{key[1]} is not a finding of this batch")
        elif key in seen:
            problems.append(f"line {number}: a second record for {key[0]}#{key[1]}")
        seen.add(key)
    for lens, finding_id in sorted(set(expected) - seen):
        problems.append(f"no record for {lens}#{finding_id}")
    return problems


def outcome_records(run, tier) -> list:
    return read_jsonl(tier_file(run.rs, tier, OUTCOMES_FILE))


def fix_base(run, tier) -> str:
    return f"{run.run_id}-t{tier}-fix"


def fix_prompt(run, tier, name, findings) -> str:
    rs = run.rs
    batch = "\n\n".join(f"## {f.lens} #{f.id} [{f.tag.value}]{' (out of lens)' if f.out_of_lens else ''}\n\n{f.body}"
                         for f in findings)
    tier0 = ""
    if tier == 0:
        tier0 = (f"\n\nThis is Tier 0: a finding may conclude the RFC is wrong; then amend the RFC to match the code. "
                 f"EXCEPT a line Ian approved — {rs / 'approval.md'}:\n\n{(rs / 'approval.md').read_text()}\n\n"
                 "An amendment that would change an approved decision is NOT made: record it DEFERRED with "
                 "amends_approved true and a reason quoting the approved text.")
    fields = ", ".join(f"{field} ({kind.__name__})" for field, kind in OUTCOME_FIELDS)
    return f"""You are the tier {tier} fix child of /ship run {run.run_id}. Your name: {name}.
Worktree (all work here): {run.root}

Apply the WHOLE batch below. Per-item compiles are fine.

{children.commands(run, name, f"fix{tier}")}

Outcome records: append ONE JSON line per finding below (never per merged item) to
{tier_file(rs, tier, OUTCOMES_FILE)}, built with json.dumps, never typed:
{fields}. finding_id is the number below, outcome is one of {'|'.join(OUTCOMES)}, reason is one line.
decline_class ONLY on DECLINED (spelled as the rejection-class table in .claude/skills/review-fix/SKILL.md spells
it); contradicts_kind ({'|'.join(CONTRADICTS_KINDS)}) and contradicts_ref ONLY on CONTRADICTED; contradicts_tier
ONLY when contradicts_kind is tier; amends_approved (bool) ONLY on DEFERRED. A field that does not apply
is absent.

If one change satisfies both rules, make it (ACCEPTED, synthesis named in the reason). Otherwise halt THAT item and
write in your report

{contradiction_line()}
then both rules quoted verbatim, each with the path it was read from, and one line on why no change satisfies both.
Do not pick a side.{tier0}

Write what you did to {tier_file(rs, tier, FIX_REPORT)} (one file for the tier).

{children.protocol(run, name)}

# The batch

{batch}
"""


async def run_fix(run, tier, plan):
    expected = {(f.lens, f.id) for f in plan["dispatched"]}

    async def spawn_fix():
        return await children.spawn(run, "fix", fix_base(run, tier),
                                    lambda name: fix_prompt(run, tier, name, plan["dispatched"]), tier=tier)
    child = children.refind(run, "fix", fix_base(run, tier)) or await spawn_fix()
    outcomes = tier_file(run.rs, tier, OUTCOMES_FILE)

    def check():
        problems = validate_outcomes(outcomes, expected)
        return "; ".join(problems[:5]) if problems else None
    await children.finish(run, child, spawn_fix, check)
    records = outcome_records(run, tier)
    approved = [r for r in records if r["outcome"] == "DEFERRED" and r["amends_approved"]]
    if approved:
        await run.ask("approved-decision", f"tier {tier}: {len(approved)} fix(es) would amend an approved RFC line",
                      tier=tier, records=approved)
    blocked = [r for r in records if r["outcome"] == "CONTRADICTED" and r["grade"] == Grade.BLOCKING.value]
    if blocked:
        await run.ask("blocking-contradicted", f"tier {tier}: {len(blocked)} BLOCKING finding(s) contradicted",
                      tier=tier, records=blocked, report=str(tier_file(run.rs, tier, FIX_REPORT)))
    return [r for r in records if r["outcome"] == "CONTRADICTED"]


# ── the tier ─────────────────────────────────────────────────────────────
async def run_tier(run, tier, dials):
    step = f"tier{tier}"
    if run.step_done("verify", step):
        return
    await run.enter("verify", step)
    changed = await run.changed_paths()
    diff = (await run.git("diff", run.get("BASE_SHA"), "HEAD")).out
    rfc_text = tier0_rfc_text(run, tier)
    lenses = await lenses_to_dispatch(run, tier, dials, changed, diff)
    dispatched = {lens: await dispatch_lens(run, tier, lens, diff, rfc_text) for lens in lenses}
    await settle_lenses(run, tier, dispatched, diff, rfc_text)
    await fix_tier(run, tier, dials, set(changed))
    await close_tier(run, tier)
    run.exit("verify", step)


async def lenses_to_dispatch(run, tier, dials, changed, diff) -> list:
    """The tier's undispatched lenses that can fire (narrowed when the dial says so), then its stalled ones. A fault
    dispatching cannot repair is refused here, before any child is paid for."""
    _, faults = await missing(run, tier)
    unrepaired = [fault for fault in faults if fault[0] not in REPAIRED_FAULTS]
    if unrepaired:
        raise RuntimeError(f"tier {tier} of {run.rs} opens with ledger faults dispatching cannot repair: {unrepaired}")
    undispatched = [fault[1] for fault in faults if fault[0] == "UNDISPATCHED"]
    stalled = [fault[1] for fault in faults if fault[0] == "STALLED"]
    keep = await skip_inapplicable(run, tier, dials, undispatched, classify(changed, run.root))
    if keep and dials.narrow:
        keep = await narrow(run, tier, keep, diff)
    return keep + stalled


async def skip_inapplicable(run, tier, dials, lenses, classes) -> list:
    """Record a skip for each lens that cannot fire; return the rest. A lens the map lacks is dispatched."""
    keep = []
    for lens in lenses:
        reason = SCRUTINY_NONE_REASON if dials.scrutiny == "none" else (
            skip_reason(lens, classes, dials.design) if lens in LENS_GROUP else None)
        if reason:
            await skip(run, tier, lens, reason)
        else:
            keep.append(lens)
    return keep


def tier0_rfc_text(run, tier):
    rfc = run.get("RFC_PATH")
    return Path(rfc).read_text() if tier == 0 and rfc else None


async def fix_tier(run, tier, dials, changed):
    plan = fix_plan(tier_reports(run, tier), dials.scrutiny, changed)
    write_fix_plan(run, tier, plan)
    if plan["dispatched"]:
        write_fix_plan(run, tier, plan, await run_fix(run, tier, plan))


async def close_tier(run, tier):
    proc, _ = await missing(run, tier)
    if proc.code != 0:
        raise ScriptFailed(["ship-lens", "missing", "-t", tier], proc, f"exited {proc.code}: tier {tier} does not close")


def tier_reports(run, tier) -> list:
    """The reports of the lenses this tier's ledger started; a file no `start` names is not one of them."""
    started = sorted({row.lens for row in lens_ledger(run.rs) if row.event == "start" and row.tier == tier})
    return [parse_report(lens, path.read_text()) for lens in started if (path := report_path(run, tier, lens)).exists()]
