"""Spawning, waiting on, and recovering children; the checkpoint protocol text every writer is given.

A child is spawned with a SHORT prompt naming its dispatch file: a large spawn prompt can start the child before
the prompt is delivered (rfc-256's harness finding). A child is finished when its marker file says so — the lens
report's `**Grade:**` line, `## Done` in a writer's progress file — and it has stopped streaming; `rlm.collect`'s
`settled` is not used, because it reports `done` while a child is still streaming.
"""
import asyncio
import dataclasses
import datetime
import enum
import re
import shlex
from pathlib import Path

from . import run as run_module
from .answers import ChildFinished

LENS_CEILING = datetime.timedelta(minutes=30)
# A stalled or malformed lens is dispatched this many times before it is skipped or asked about.
LENS_DISPATCHES = 2
# A child that is not busy for this many polls in a row, with no ship-run of its own open, has stopped.
IDLE_POLLS = 4
NARROW_MODEL = "deepseek/deepseek-v4-pro"
PROGRESS_FILE = "progress.md"


class Settled(enum.Enum):
    """How wait() left a child."""
    DONE = "done"
    IDLE = "idle"
    OVER_CEILING = "over-ceiling"

# What a role's marker file must contain for the child to be finished; a writer's marker is its progress file.
FINISHED = {"lens": r"^\*\*Grade:\*\*", "narrowing": r"\S"}
WRITER_FINISHED = r"^## Done\s*$"

# A writer's write scope is everything EXCEPT these; ship-verify-scope checks the implementer's against them.
DENY = (".claude/*", ".prime/agent/skills/ship/*")
DENY_ARGS = tuple(arg for glob in DENY for arg in ("-d", glob))

SPAWN_PROMPT = ("You are {name}, the {role} child of /ship run {run_id}. Your complete task is in {dispatch} — "
                "read it IN FULL first (in chunks if needed), then do exactly what it says.")

CHECKPOINT_PROTOCOL = """\
Checkpoint protocol. After every `scripts/ship-checkpoint`, and whenever
`await compact.status()` reports more than 300000 tokens, (1) write
{progress} — what is done, what remains as a
numbered list, the exact next command, and the path of the last ship-run record
you read; (2) then `await compact.run("{name}: continue from progress.md")`.
Finish by appending `## Done` and one line to that file. The parent reads the
file, not your reply."""

COMMAND_PROTOCOL = """\
Write scope: anything the change needs EXCEPT {deny}.

Commands — builds, compiles, tests, format — run through ship-run, never bare:

    {scripts}/ship-run -s {rs} -n <name> -c {name} -- {prefix} <command>

Read the record and the failing excerpt it names, not the log. Start a long command in the background and end
your turn; its completion wakes you. The suite runs ONCE, at the end:

    {scripts}/ship-checkpoint -s {rs} {checkpoint} -m "<what>"
    {scripts}/ship-run -s {rs} -n suite -c {name} -g -- {suite}"""


@dataclasses.dataclass
class Child:
    name: str
    child_id: str
    role: str
    dir: Path
    started: datetime.datetime
    marker: Path

    @property
    def progress(self) -> Path:
        return self.dir / PROGRESS_FILE

    @property
    def dispatch(self) -> Path:
        return self.dir / "dispatch.md"

    def done(self) -> bool:
        text = self.marker.read_text() if self.marker.exists() else ""
        return bool(re.search(FINISHED.get(self.role, WRITER_FINISHED), text, re.M))


def child_dir(run, name) -> Path:
    return run.dir / "children" / name


def protocol(run, name) -> str:
    return CHECKPOINT_PROTOCOL.format(progress=child_dir(run, name) / PROGRESS_FILE, name=name)


def commands(run, name, checkpoint) -> str:
    """The write scope and command rules every writing child is given; the suite line is the one suite reuse reads."""
    tools = run.tools
    return COMMAND_PROTOCOL.format(deny=", ".join(DENY), scripts=tools.scripts, rs=run.rs, name=name,
                                   prefix=shlex.join(tools.prefix), checkpoint=checkpoint, suite=tools.suite_cmd)


def unique_name(run, base) -> str:
    taken = {e["name"] for e in run.events() if e["ev"] == "spawn"}
    name, n = base, 1
    while name in taken:
        n += 1
        name = f"{base}-{n}"
    return name


async def spawn(run, role, base_name, prompt_for, model=None, marker_for=None, tier=None) -> Child:
    """Name the child from its base, write prompt_for(name) to <child>/dispatch.md, and spawn with a short prompt
    naming it. The journal records the base and the tier, so nothing reads them back out of the name."""
    name = unique_name(run, base_name)
    directory = child_dir(run, name)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "dispatch.md").write_text(prompt_for(name))
    marker = Path(marker_for(name)) if marker_for else directory / PROGRESS_FILE
    short = SPAWN_PROMPT.format(name=name, role=role, run_id=run.run_id, dispatch=directory / "dispatch.md")
    child_id = await run.harness.spawn(short, name=name, model=model)
    started = datetime.datetime.now(datetime.timezone.utc)
    run.journal("spawn", role=role, base=base_name, tier=tier, name=name, child_id=child_id, model=model,
                started=started.isoformat(), marker=str(marker))
    return Child(name, child_id, role, directory, started, marker)


def refind(run, role, base_name):
    """The latest child of this role and base name the journal recorded, as a Child, or None."""
    for event in reversed(run.events()):
        if event["ev"] == "spawn" and event["role"] == role and event["base"] == base_name:
            return Child(event["name"], event["child_id"], role, child_dir(run, event["name"]),
                         datetime.datetime.fromisoformat(event["started"]), Path(event["marker"]))
    return None


def ship_run_open(run, caller) -> bool:
    """Whether a ship-run this caller started has no end line yet: a child waiting on its own command is working."""
    running = set()
    for row in run_module.ship_run_ledger(run.rs):
        if row.fields.get("caller") != caller:
            continue
        if row.event == "start":
            running.add(row.fields.get("name"))
        elif row.event == "end":
            running.discard(row.fields.get("name"))
    return bool(running)


async def wait(run, kids, ceiling: datetime.timedelta | None = None) -> dict:
    """Poll until each child is finished, idle, or over the ceiling: {name: Settled}."""
    status, quiet = {}, {child.name: 0 for child in kids}
    pending = {child.name: child for child in kids}
    run.waiting_on = "children: " + ", ".join(pending)
    while pending:
        for name, child in list(pending.items()):
            busy = await run.harness.busy(name)
            verdict = None
            if not busy and child.done():
                verdict = Settled.DONE
            elif ceiling is not None and datetime.datetime.now(datetime.timezone.utc) - child.started > ceiling:
                verdict = Settled.OVER_CEILING
            else:
                quiet[name] = 0 if busy or ship_run_open(run, name) else quiet[name] + 1
                verdict = Settled.IDLE if quiet[name] >= IDLE_POLLS else None
            if verdict:
                pending.pop(name)
                status[name] = verdict
                run.journal("settle", name=name, status=verdict.value)
        if pending:
            await asyncio.sleep(run.tools.poll_seconds)
    run.waiting_on = None
    return status


async def nudge(run, child, problem):
    """The one wake message a stopped child gets: an errored or idle child resumes with its context intact."""
    await run.harness.send(child.name, f"You stopped before finishing: {problem}. Re-read {child.dispatch} "
                                       f"(and {child.progress} if you wrote one), then finish.")
    run.journal("nudge", name=child.name, problem=problem)
    await _until_busy(run, child)


def unfinished(child, check=None):
    """What keeps a writing child from being finished, or None: its `## Done` marker, then `check`'s condition."""
    if not child.done():
        return f"{child.progress} does not end with `## Done`"
    return check() if check is not None else None


async def finish(run, child, respawn, check=None, settle=None) -> Child:
    """Wait for a writing child to finish: one wake message on a missing marker, then Ask(child-failed), and act on
    the answer — the model finished the work itself (checked again), or `respawn()` starts a fresh child.

    `check` returns a problem string or None, for a condition beyond the `## Done` marker. `settle(child)` replaces
    the wait for a role the driver answers mid-run, so a respawned child is answered too. Returns the child that
    finished."""
    settle = settle or (lambda kid: wait(run, [kid]))
    nudged = False
    while True:
        await settle(child)
        problem = unfinished(child, check)
        if problem is None:
            return child
        if not nudged:
            nudged = True
            await nudge(run, child, problem)
            continue
        answer = await run.ask("child-failed", f"{child.name} exited twice unfinished: {problem}",
                               child=child.name, problem=problem, progress=str(child.progress))
        while isinstance(answer, ChildFinished) and (problem := unfinished(child, check)) is not None:
            answer = await run.ask("child-failed", f"{child.name} is still unfinished: {problem}",
                                   child=child.name, problem=problem, progress=str(child.progress))
        if isinstance(answer, ChildFinished):
            return child
        await run.harness.delete(child.child_id)
        run.journal("respawn", name=child.name, reason=answer.respawn)
        child, nudged = await respawn(), False


async def _until_busy(run, child, polls=IDLE_POLLS):
    """After a wake message, give the child a few polls to pick it up before waiting on it again."""
    for _ in range(polls):
        await asyncio.sleep(run.tools.poll_seconds)
        if child.done() or await run.harness.busy(child.name):
            return
