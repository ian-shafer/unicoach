"""The phase machine: design -> approve -> implement -> verify (land and report are in land.py)."""
import dataclasses
import re
import shlex
from pathlib import Path

from . import children, review
from .answers import Rebrief
from .applicability import classify
from .dials import derive_approval, dial_line, parse_dial_line, test_depth
from .run import APPROVED_PHASE_VALUE, DRIVER_DIR, SUITE_ARGV, ScriptFailed, checkpoints, now

RFC_SECTIONS = ("## Detailed Design", "## Files Modified", "## Implementation Plan", "## Tests")
# The ship-run record names verify and land write; the report's Tests section lists exactly these.
SUITE_RECORD, SHELL_RECORD, FORMAT_RECORD, HOOK_RECORD = "suite", "shell-tests", "format-check", "hook"
GATE_RECORDS = (SUITE_RECORD, SHELL_RECORD, FORMAT_RECORD, HOOK_RECORD)


@dataclasses.dataclass(frozen=True)
class Next:
    phase: str


# ── design and approval ─────────────────────────────────────────────────
async def run_design(run, dials):
    await run.set_phase("design")
    root, number = run.root, run.get("RFC_NUM")
    line = f"write the RFC at {root}/rfc/{number}-<slug>.md and answer design with {{'path': ...}}"
    while True:
        answer = await run.ask("design", line, rfc_num=number)
        path = Path(answer.path)
        path = path if path.is_absolute() else root / path
        if not path.is_file():
            line = f"{path} does not exist (answered {answer.path!r}); write the RFC there and answer design again"
            continue
        absent = [s for s in RFC_SECTIONS if not re.search(rf"^{re.escape(s)}\s*$", path.read_text(), re.M)]
        if not absent:
            break
        line = f"{path} lacks {', '.join(absent)} (Tier 0 reads them); fix it and answer design again"
    await run.set("RFC_PATH", path)
    return Next("approve")


async def approve(run, line, **context):
    """The approval Ask, until approved, with the watchdog paused; writes <rs>/approval.md and APPROVED=yes."""
    await run.heartbeat("pause")  # a human gate outlasts the watchdog's interval
    while True:
        answer = await run.ask("approval", line, **context)
        if answer.approved:
            break
        line = "not approved: revise, present again, and answer approval"
    await run.heartbeat("resume")
    decisions = "\n".join(f"- {d}" for d in answer.decisions) or "- (none recorded)"
    (run.rs / "approval.md").write_text(f"# Approval\n\nApproved by Ian at {now()}.\n\n## Decisions\n\n{decisions}\n")
    await run.set("APPROVED", "yes")
    await run.set("PHASE", APPROVED_PHASE_VALUE)


async def run_approve(run, dials):
    await run.set_phase("approve")
    rfc = run.get("RFC_PATH")
    await approve(run, f"present {'the RFC ' + rfc if rfc else 'the plan'} for approval; answer approval",
                  rfc=rfc, brief=str(run.rs / "brief.md"))
    await run.harness.compact(compact_note(run))
    return Next("implement")


def compact_note(run) -> str:
    return f"ship run {run.run_id}: state is on disk at {run.rs}; on wake read {run.ask_path}"


# ── implement ───────────────────────────────────────────────────────────
def implementer_prompt(run, name) -> str:
    rs, base = run.rs, run.get("BASE_SHA")
    rfc = run.get("RFC_PATH")
    if rfc:
        task = (f"Implement RFC {run.get('RFC_NUM')} at {rfc}, as approved. The approval record is "
                f"{rs / 'approval.md'}:\n\n{(rs / 'approval.md').read_text()}")
    else:
        task = "Implement this change.\n\n" + (rs / "brief.md").read_text()
    return f"""You are the implementer of /ship run {run.run_id}. Your name: {name}.
Worktree (do ALL work here): {run.root}

{task}

{children.commands(run, name, "impl 1")}

The driver checks the write scope with `ship-verify-scope -s {rs} -f {base} {shlex.join(children.DENY_ARGS)}`;
a hit resets the run to its last checkpoint.

A change to the coach prompt is an edit to prompts/coach-system-prompt.md only, one sentence per line; then
`bin/prompt-seed -b {base}` (through ship-run) BEFORE the suite. Never hand-write the seed, its
db/schema/ORDER line or the systemPromptVersion pin.

A change under ios-app/** follows {DRIVER_DIR.parent / 'references' / 'visual-gate.md'}: save the screenshots it
requires under {rs / 'artifacts'}/.

A design choice the instruction did not settle: if it is reversible (no schema, no stored data shape, no prompt,
no public copy) take the default, proceed, and note it in your progress file. If it is irreversible, write the
question and its options to {children.child_dir(run, name) / 'decision.md'} and end your turn; the answer is sent
to you as a message.

{children.protocol(run, name)}
"""


def implementer_base(run) -> str:
    return f"{run.run_id}-impl"


async def spawn_implementer(run):
    return await children.spawn(run, "implementer", implementer_base(run), lambda name: implementer_prompt(run, name))


async def run_implement(run, dials):
    await run.set_phase("implement")
    child = children.refind(run, "implementer", implementer_base(run))
    while True:
        child = child or await checkpoint_and_spawn_implementer(run)
        child, asker = await implement_with(run, child)
        if asker is not None:
            # D3: an irreversible question makes approval required for the rest of the run.
            await require_approval(run, f"{asker} asked an irreversible question")
        if await write_scope_clean(run):
            break
        if await recover_denied_write(run):
            child = None
    await run.script("ship-checkpoint", "impl", "-m", "implementation")
    await approve_diff_if_needed(run, dials)
    return Next("verify")


async def checkpoint_and_spawn_implementer(run):
    # The point a denied write is reset to: the implementer's own checkpoints may already carry it.
    await run.script("ship-checkpoint", "before-impl", "-m", "before implementation")
    return await spawn_implementer(run)


async def require_approval(run, reason):
    await run.set("DIAL_APPROVAL", "required")
    run.journal("dial", dial="approval", value="required", reason=reason)


# ship-verify-scope's line per violation; its other non-zero exits are the script refusing to judge.
SCOPE_VIOLATION = re.compile(r"^(DENIED|OUT-OF-SCOPE): ", re.M)


async def write_scope_clean(run) -> bool:
    """ship-verify-scope over the implementation, kept in findings/write-scope.txt. A non-zero exit that names no
    violating path is the script failing (a moved fork point, a usage error), never a denied write: it raises, and
    nothing is reset."""
    args = ["-f", run.get("BASE_SHA"), *children.DENY_ARGS]
    scope = await run.script("ship-verify-scope", *args, check=False, cwd=run.root)
    (run.rs / "findings").mkdir(exist_ok=True)
    (run.rs / "findings" / "write-scope.txt").write_text(f"exit={scope.code}\n{scope.out}{scope.err}")
    if scope.code == 0:
        return True
    if not SCOPE_VIOLATION.search(scope.err):
        raise ScriptFailed(["ship-verify-scope", *args], scope, f"exited {scope.code} naming no denied path")
    return False


async def recover_denied_write(run) -> bool:
    """Reset to the last checkpoint and ask the model; True when it re-briefed, so a fresh implementer is due."""
    await run.script("ship-recover", "-c", last_checkpoint(run, "before-impl"))
    answer = await run.ask("scope-deny", "the implementation wrote a denied path; the run was reset to its last "
                           "checkpoint. Answer {'brief': ...} to re-dispatch, or {} once the tree is right",
                           scope=str(run.rs / "findings" / "write-scope.txt"))
    if not isinstance(answer, Rebrief):
        return False
    (run.rs / "brief.md").write_text(answer.brief.rstrip() + "\n")
    return True


async def approve_diff_if_needed(run, dials):
    # Whether approval was GRANTED, not whether it is required: a decision makes it required without approving.
    if run.get("APPROVED") == "yes":
        return
    verdict, reason = derive_approval(dials.design, await run.changed_paths())
    if verdict == "required":
        await require_approval(run, reason)
        await approve(run, f"the diff needs approval after all ({reason}); present it and answer approval",
                      reason=reason)


def last_checkpoint(run, label) -> str:
    rows = checkpoints(run.rs)
    matching = [row.sha for row in rows if row.label == label]
    if not matching:
        raise RuntimeError(f"{run.rs / 'checkpoints.log'} has no {label!r} checkpoint to recover to; labels present: "
                           f"{sorted({row.label for row in rows})}")
    return matching[-1]


async def implement_with(run, child) -> tuple:
    """Wait on the implementer, routing an irreversible design question to the model — a respawned implementer's
    too: (the child that finished, the name of the last one that asked a question or None)."""
    asker = None

    async def settle(kid):
        nonlocal asker
        while True:
            await children.wait(run, [kid])
            question = kid.dir / "decision.md"
            if kid.done() or not question.exists():
                return
            answer = await run.ask("decision", f"{kid.name} asks an irreversible design question; answer "
                                   "{'answer': '<the decision>'}", question=str(question))
            question.rename(kid.dir / f"decision-{now()}.md")
            asker = kid.name
            await run.harness.send(kid.name, f"Decision: {answer.answer}")
            await children._until_busy(run, kid)

    return await children.finish(run, child, lambda: spawn_implementer(run), settle=settle), asker


# ── verify ──────────────────────────────────────────────────────────────
async def rebase_in_progress(run) -> bool:
    """Whether a rebase is stopped in the worktree: what ship-rebase leaves on a conflict."""
    for probe in ("rebase-merge", "rebase-apply"):
        if Path((await run.git("rev-parse", "--path-format=absolute", "--git-path", probe)).out.strip()).exists():
            return True
    return False


async def rebase_conflict(run, rebased) -> str:
    """ship-rebase exits 1 for a conflict and for every fault; a conflict leaves a rebase in progress. The Ask line
    for the conflict; any other failure raises with the script's whole stderr."""
    if not await rebase_in_progress(run):
        raise ScriptFailed(["ship-rebase"], rebased, f"exited {rebased.code} with no rebase in progress")
    return f"ship-rebase stopped on a conflict in {run.root}; resolve it, then answer"


async def rebase(run):
    while (rebased := await run.script("ship-rebase", check=False)).code != 0:
        await run.ask("rebase-conflict", await rebase_conflict(run, rebased), root=str(run.root), stderr=rebased.err)


async def record_test_depth(run):
    """DIAL_TESTS for the tree as rebased: bin/gate-scope over BASE_SHA..HEAD."""
    await run.set("DIAL_TESTS", dial_line(await test_depth(run.root, run.get("BASE_SHA"))))


async def prompt_seed(run, name):
    """bin/prompt-seed's outputs are a function of the prompt source and BASE_SHA; regenerate when either moves."""
    if "prompt" not in classify(await run.changed_paths(), run.root):
        return
    await gate(run, name, ["bin/prompt-seed", "-b", run.get("BASE_SHA")])
    await run.script("ship-checkpoint", "prompt-seed", "-m", "regenerate the coach prompt seed")


async def gate(run, name, argv, suite=False) -> dict:
    """Run a verify command until it is green; a red one is the model's to fix (Ask suite-red)."""
    while True:
        record = await run.ship_run(name, argv, gate=suite)
        if record["exit"] == "0":
            return record
        await run.ask("suite-red", f"{' '.join(argv)} exited {record['exit']}; fix it and answer",
                      record=str(run.rs / "runs" / f"{name}.record"), failing=record.get("failing"))
        await run.script("ship-checkpoint", "verify-fix", "-m", f"fix {name}")


def suite_records(rs) -> list:
    path = Path(rs) / "suite-green.txt"
    records = []
    for line in path.read_text().splitlines() if path.exists() else []:
        if line.startswith("exit="):
            records.append({})
        if records and "=" in line:
            key, value = line.split("=", 1)
            records[-1][key] = value
    return records


async def suite_needed(run):
    """RFC 211's four conditions on the LAST suite record; returns (needed, reason). Whether the tree's move since
    the green run could change the suite's answer is bin/gate-scope's `gradle=`, the hook's own map."""
    records = suite_records(run.rs)
    if not records:
        return True, "no suite record"
    last = records[-1]
    if (await run.git("status", "--porcelain")).out.strip():
        return True, "the tree is dirty"
    if last.get("exit") != "0":
        return True, f"the last suite exited {last.get('exit')}"
    suite = run.tools.suite_cmd
    if last.get("cmd") != suite:
        return True, f"the last suite ran {last.get('cmd')!r}, not {suite!r}"
    head = (await run.git("rev-parse", "HEAD^{tree}")).out.strip()
    tree = last.get("tree")
    if tree is None:
        return True, "the last suite record names no tree"
    if tree == head:
        return False, f"tree {head} already passed"
    try:
        moved = await test_depth(run.root, tree)
    except ScriptFailed as error:
        return True, f"the recorded tree {tree} cannot be compared: {error}"
    if moved.gradle:
        return True, f"bin/gate-scope says the move since tree {tree} needs Gradle (scope={moved.scope})"
    return False, f"bin/gate-scope says the move since tree {tree} needs no Gradle (scope={moved.scope})"


async def run_verify(run, dials):
    await run.set_phase("verify")
    await rebase_once(run)
    for tier in review.TIERS:
        await review.run_tier(run, tier, dials)
    await prompt_seed(run, "prompt-seed-final")
    await run.script("ship-checkpoint", "after-review", "-m", "review fixes")
    tests = parse_dial_line(run.tests)
    await suite_gate(run, tests)
    await shell_gate(run, tests)
    await format_gate(run)
    return Next("land")


async def rebase_once(run):
    if run.step_done("verify", "rebase"):
        return
    await run.enter("verify", "rebase")
    await run.harness.compact(compact_note(run))
    await run.script("ship-checkpoint", "before-verify")
    await rebase(run)
    await record_test_depth(run)
    await prompt_seed(run, "prompt-seed")
    run.exit("verify", "rebase")


async def suite_gate(run, tests):
    if not tests.gradle:
        return
    needed, reason = await suite_needed(run)
    run.journal("suite-decision", needed=needed, reason=reason)
    if needed:
        await gate(run, SUITE_RECORD, list(SUITE_ARGV), suite=True)


async def shell_gate(run, tests):
    if tests.every_harness:
        await gate(run, SHELL_RECORD, ["bin/shell-tests"])  # whatever bin/shell-tests runs
    elif tests.harnesses:
        await gate(run, SHELL_RECORD, ["bin/shell-tests", *[arg for name in tests.harnesses for arg in ("-n", name)]])


async def format_gate(run):
    if "kotlin" in classify(await run.changed_paths(), run.root):
        await gate(run, FORMAT_RECORD, ["bin/format", "-c"])
