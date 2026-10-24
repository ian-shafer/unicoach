"""Phase 6 as one coroutine: the lock-bracketed sequence, and the lock released first on every non-land exit."""
import dataclasses
import re
from pathlib import Path

from . import report
from .phases import HOOK_RECORD, Next, compact_note, rebase_conflict, record_test_depth
from .run import DRIVER_DIR, ScriptFailed, archive_root, sh


@dataclasses.dataclass(frozen=True)
class Landed:
    original: str


@dataclasses.dataclass(frozen=True)
class NotLanded:
    """An expected way a land attempt ends — a conflict, a red hook, a refusal: the lock is already released and
    the model has answered `kind`, so the sequence runs again from the top."""
    kind: str


async def release(run):
    """A refused release (another run holds the lock) raises: the journal never records a release that did not
    happen."""
    await run.script("ship-lock", "release")


async def release_and_ask(run, ask_kind, line, **context) -> NotLanded:
    """Release FIRST, then ask: a run that waits on a human while holding the lock blocks every other run."""
    await release(run)
    run.journal("lock-released", reason=ask_kind)
    await run.ask(ask_kind, line, **context)
    return NotLanded(ask_kind)


def commit_subject(run, prose) -> str:
    if prose.subject is not None:
        return prose.subject
    rfc = run.get("RFC_PATH")
    if rfc:
        title = Path(rfc).read_text().splitlines()[0]
        return f"RFC {run.get('RFC_NUM')}: {re.split(r' — |: ', title, maxsplit=1)[-1].strip()}"
    return run.get("SLUG", run.run_id).replace("-", " ")


def classify_hook_failure(text) -> str:
    """A hint for the model from bin/pre-commit's failing excerpt. The hook has no structured result, so this
    matches its wording; a rewording there falls through to `tests`."""
    if re.search(r"\b[a-z-]*scripts-tests\b", text):
        return "shell-harness"
    if re.search(r"ktlint|deno fmt|bin/format", text):
        return "format"
    return "tests"


async def land_sequence(run, subject) -> Landed | NotLanded:
    """One land attempt under the lock: Landed, or NotLanded once the model has answered. A fault raises."""
    await acquire_lock(run)
    try:
        refused = await commit_under_lock(run, subject)
    except BaseException:
        await release(run)
        raise
    return refused or await ship_land(run)


# ship-lock acquire's wording for "held by another run" (its wait ran out, or it lost a race to break an expired
# hold). It exits 1 for these and for its faults alike, so the wording is what tells them apart.
LOCK_BUSY = re.compile(r"could not acquire .* within|lost a race breaking")


async def acquire_lock(run):
    """Wait out another run's hold; a fault raises with ship-lock's whole stderr. Idempotent for a lock this run
    holds."""
    while (acquired := await run.script("ship-lock", "acquire", check=False)).code != 0:
        if not LOCK_BUSY.search(acquired.err):
            raise ScriptFailed(["ship-lock", "acquire"], acquired)
        run.journal("lock-wait", err=acquired.err.strip())


async def commit_under_lock(run, subject) -> NotLanded | None:
    """Rebase, seed, order, squash, format and commit through the hook; NotLanded at the first refusal."""
    for step in (rebase_at_land, seed_prompt_at_land, check_order):
        refused = await step(run)
        if refused:
            return refused
    await run.script("ship-squash")
    record = await run.ship_run("land-format", ["bin/format"])
    if record["exit"] != "0":
        return await release_and_ask(run, "hook-failed", "bin/format failed at land", cls="format",
                                     record=str(run.rs / "runs" / "land-format.record"))
    await run.git("add", "-u")
    return await commit_through_hook(run, subject)


async def rebase_at_land(run) -> NotLanded | None:
    rebased = await run.script("ship-rebase", check=False)
    if rebased.code != 0:
        return await release_and_ask(run, "rebase-conflict", f"at land: {await rebase_conflict(run, rebased)}",
                                     root=str(run.root), stderr=rebased.err)
    await record_test_depth(run)
    return None


async def seed_prompt_at_land(run) -> NotLanded | None:
    record = await run.ship_run("land-prompt-seed", ["bin/prompt-seed", "-b", run.get("BASE_SHA")])
    if record["exit"] == "0":
        return None
    return await release_and_ask(run, "hook-failed", "bin/prompt-seed refused at land", cls="prompt-seed",
                                 record=str(run.rs / "runs" / "land-prompt-seed.record"))


async def check_order(run) -> NotLanded | None:
    order = await run.script("ship-order", check=False)
    if order.code == 0:
        return None
    return await release_and_ask(run, "hook-failed", "ship-order refused", cls="ship-order", stderr=order.err)


async def commit_through_hook(run, subject) -> NotLanded | None:
    record = await run.ship_run(HOOK_RECORD, ["git", "commit", "-q", "-m", subject])
    if record["exit"] == "0":
        return None
    failing = run.rs / "runs" / f"{HOOK_RECORD}.failing"
    text = failing.read_text() if failing.exists() else ""
    cls = classify_hook_failure(text)
    if cls == "shell-harness":
        (run.rs / "findings" / "hook-shell-failure.txt").write_text(text)
    return await release_and_ask(run, "hook-failed", f"the commit hook failed ({cls})", cls=cls,
                                 record=str(run.rs / "runs" / f"{HOOK_RECORD}.record"),
                                 procedure=str(DRIVER_DIR.parent / "references" / "driver.md"))


async def ship_land(run) -> Landed | NotLanded:
    original = run.get("ORIGINAL_CHECKOUT")
    landed = await sh([run.tools.scripts / "ship-land", "-s", run.rs], cwd=original)
    if landed.code != 0:
        return await release_and_ask(run, "hook-failed", "ship-land refused", cls="ship-land", stderr=landed.err)
    return Landed(original)


async def run_land(run, dials):
    await run.set_phase("land")
    if not run.step_done("land", "report"):
        await run.enter("land", "report")
        await run.harness.compact(compact_note(run))
        await run.script("ship-checkpoint", "before-land")
        await report.write(run)
        prose = await run.ask("report-prose", f"write the opening and the learning candidates for {run.rs / 'report.md'}",
                              report=str(run.rs / "report.md"))
        report.add_prose(run, prose)
        (run.dir / "commit-subject.txt").write_text(commit_subject(run, prose))
        run.exit("land", "report")
    state = run.state()
    original = state["ORIGINAL_CHECKOUT"]
    archive = archive_root(original) / state["RUN_ID"]
    # A run already in the archive has landed (ship-land copies it there, journal and all, before it returns): a
    # resume after that point must not take the lock and rebase a worktree that no longer exists.
    if run.rs.resolve() != archive.resolve():
        await run.enter("land", "sequence")
        subject = (run.dir / "commit-subject.txt").read_text()
        attempt = await land_sequence(run, subject)
        while isinstance(attempt, NotLanded):
            attempt = await land_sequence(run, subject)
        # ship-land copied the run scratch to the archive and removed the worktree: the run lives there now.
        run = run.move_to(archive)
        run.exit("land", "sequence")
    sha = (await sh(["git", "-C", original, "rev-parse", state["BASE_BRANCH"]], check=True)).out.strip()
    await run.set("LANDED_SHA", sha)
    await report.finish(run, sha, state)
    return Next("report")


async def run_report(run, dials):
    await run.ask("announce", f"landed {run.get('LANDED_SHA')}; tell Ian DONE from {run.rs / 'report.md'}",
                  report=str(run.rs / "report.md"))
    return Next("complete")
