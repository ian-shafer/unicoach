"""The /ship driver (RFC 256): the phase machine of a run as one kernel task, waking the model only to judge.

    import sys; sys.path.insert(0, "<original checkout>/.prime/agent/skills/ship/driver")
    import ship_driver
    from ship_driver.dials import Dials

    drv = await ship_driver.start(instruction, Dials(design="rfc", scrutiny="full"), slug=None, brief=None)
    drv = await ship_driver.resume()        # this session's live run, after a restart or compaction
    await drv.answer(kind, payload)         # the model's judgement, by the kind named in the ship-wake line
    drv.status()                            # one line
    await ship_driver.watchdog()            # the heartbeat's one cell

The agent primitives are read from the kernel's own globals (harness.LiveHarness); tests pass `harness=` and a
`tools=` Toolchain of their own.
"""
import asyncio
import traceback
from pathlib import Path

from . import land, phases
from .dials import Dials, derive_approval, paths_named
from .harness import LiveHarness
from .ranking import rank_lenses
from .run import (APPROVED_PHASE_VALUE, DRIVER_DIR, PHASE_VALUE, Run, ScriptFailed, Toolchain, archive_root,
                  parse_assignments, read_record, sh)

__all__ = ["start", "resume", "watchdog", "rank_lenses", "Driver", "Dials", "Toolchain"]

HEARTBEAT_INTERVAL = "*/30 * * * *"
WATCHDOG_CELL = (f"import sys; sys.path.insert(0, {str(DRIVER_DIR)!r}); import ship_driver; "
                 "await ship_driver.watchdog()")

PHASES = {
    "design": phases.run_design, "approve": phases.run_approve, "implement": phases.run_implement,
    "verify": phases.run_verify, "land": land.run_land, "report": land.run_report,
}

# The phases that can hand over to each one: start() launches design, approve or implement straight after claim.
PREVIOUS = {"design": ("claim",), "approve": ("claim", "design"), "implement": ("claim", "approve"),
            "verify": ("implement",), "land": ("verify",), "report": ("land",)}


def _leaves(phase) -> set:
    """The PHASE values a phase can leave in the state file: its own, and approve()'s from inside approve/implement."""
    return {PHASE_VALUE[phase]} | ({APPROVED_PHASE_VALUE} if phase in ("approve", "implement") else set())


# The PHASE values the state file may hold while the journal has this phase open: a phase is entered (journal) just
# before it writes its own PHASE, so the file can still hold what a predecessor left.
CONSISTENT_PHASE = {phase: _leaves(phase).union(*(_leaves(p) for p in previous)) for phase, previous in PREVIOUS.items()}

_DRIVERS = {}  # run id -> Driver, for this kernel


class Driver:
    def __init__(self, run: Run, dials: Dials):
        self.run = run
        self.dials = dials
        self.task = None

    async def answer(self, kind, payload):
        self.run.answer(kind, payload)
        return self.status()

    def status(self) -> str:
        run = self.run
        state = run.state()
        ask = run.open_ask()
        live = "running" if self.task and not self.task.done() else "stopped"
        waiting = f"ask {ask['kind']}" if ask else (run.waiting_on if run.waiting_on is not None else "-")
        return (f"{state.get('RUN_ID')} phase={state.get('PHASE')} step={state.get('DRIVER_STEP', '-')} "
                f"task={live} waiting-on={waiting}")

    def launch(self, phase):
        self.task = asyncio.ensure_future(self._loop(phase))
        _DRIVERS[self.run.run_id] = self

    async def _loop(self, phase):
        try:
            while phase != "complete":
                await self.run.enter(phase)
                outcome = await PHASES[phase](self.run, self.dials)
                self.run.exit(phase)
                phase = outcome.phase
            await self.run.set_phase("complete")
            self.run.journal("complete")
            heartbeat = self.run.session().get("heartbeat_id")
            if heartbeat:
                await self.run.harness.heartbeat_delete(heartbeat)
        except asyncio.CancelledError:
            raise
        except BaseException as error:
            run = self.run
            (run.dir / "error.txt").write_text(traceback.format_exc())
            run.journal("error", phase=phase, step=run.get("DRIVER_STEP"), type=type(error).__name__, error=str(error))
            headline = str(error).splitlines()[0] if str(error) else ""
            run.harness.wake(f"ship-wake {run.run_id} driver-error in {phase}: {type(error).__name__}: {headline:.200}; "
                             f"read {run.dir / 'error.txt'}, fix the cause, then await ship_driver.resume()")


def _dials_from_state(run) -> Dials:
    state = run.state()
    absent = [key for key in ("DIAL_DESIGN", "DIAL_SCRUTINY") if key not in state]
    if absent:
        raise RuntimeError(f"{run.rs / 'state'} lacks {', '.join(absent)}: {state}")
    return Dials(state["DIAL_DESIGN"], state["DIAL_SCRUTINY"], state.get("DIAL_NARROW") == "yes")


# ship-status -p's own exit-1 answer when no worktree has a run; any other failure is ship-status failing.
NO_LIVE_RUNS = "no live ship runs found"


async def _live_scratches(tools) -> list:
    """Run scratches with a driver journal and a PHASE that is not complete: live worktrees, then landed runs not
    yet announced."""
    argv = [tools.scripts / "ship-status", "-p"]
    proc = await sh(argv, cwd=tools.original)
    if proc.code != 0 and NO_LIVE_RUNS not in proc.err:
        raise ScriptFailed(argv, proc)
    scratches = [Path(line) for line in proc.out.splitlines() if line.strip()]
    scratches += [p.parent.parent for p in archive_root(tools.original).glob("*/driver/journal.jsonl")]
    return [rs for rs in scratches
            if (rs / "driver" / "journal.jsonl").exists() and read_record(rs / "state").get("PHASE") != "complete"]


async def _own_live_scratches(harness, tools) -> list:
    """The live runs this session drives; another session's run is never adopted."""
    session = await harness.session_id()
    return [rs for rs in await _live_scratches(tools) if Run(rs, harness, tools).session().get("driver_session") == session]


async def start(instruction, dials, slug=None, brief=None, harness=None, tools=None) -> Driver:
    """Claim a run, record the dials, create the watchdog, and start the task. One driver per session.

    The journal is written last: a start() that fails before it leaves no run the driver would resume."""
    harness = harness or LiveHarness()
    tools = tools or Toolchain()
    if (dials.design == "none") != bool(slug):
        raise ValueError(f"design={dials.design!r} takes {'a slug' if dials.design == 'none' else 'no slug'}, "
                         f"not slug={slug!r}")
    for rs in await _own_live_scratches(harness, tools):
        raise RuntimeError(f"this session already drives {rs}; use `await ship_driver.resume()`")
    run = await _claim(harness, tools, dials, slug)
    approval, reason = await _record_dials(run, dials, instruction)
    _write_brief(run, instruction, brief)
    await _bind_watchdog(run, harness)
    await run.enter("claim")
    run.journal("dial", dial="approval", value=approval, reason=reason)
    run.exit("claim")
    driver = Driver(run, dials)
    driver.launch(_first_phase(dials, approval))
    return driver


async def _claim(harness, tools, dials, slug) -> Run:
    lane = ["-l", "rfc"] if dials.design == "rfc" else ["-l", "quick", "-s", slug]
    argv = [tools.scripts / "ship-claim", *lane]
    claim = await sh(argv, cwd=tools.original, check=True)
    assigned = parse_assignments(claim.out.splitlines())
    if "RUN_SCRATCH" not in assigned:
        raise ScriptFailed(argv, claim, "printed no RUN_SCRATCH=")
    return Run(assigned["RUN_SCRATCH"], harness, tools)


async def _record_dials(run, dials, instruction) -> tuple:
    approval, reason = derive_approval(dials.design, paths_named(instruction), instruction)
    for key, value in (("DIAL_DESIGN", dials.design), ("DIAL_SCRUTINY", dials.scrutiny),
                       ("DIAL_NARROW", "yes" if dials.narrow else "no"), ("DIAL_APPROVAL", approval)):
        await run.set(key, value)
    return approval, reason


def _write_brief(run, instruction, brief):
    (run.rs / "instruction.md").write_text(instruction.rstrip() + "\n")
    plan = f"\n\n## Plan\n\n{brief.rstrip()}" if brief else ""
    (run.rs / "brief.md").write_text(instruction.rstrip() + plan + "\n")


async def _bind_watchdog(run, harness):
    heartbeat = await harness.heartbeat_create(f"ship watchdog: run `{WATCHDOG_CELL}` in one cell and nothing else",
                                               HEARTBEAT_INTERVAL, f"ship watchdog {run.run_id}")
    run.bind_session(driver_session=await harness.session_id(), heartbeat_id=heartbeat,
                     coord_log=await harness.session_log(), agent_dir=harness.agent_dir())


def _first_phase(dials, approval) -> str:
    if dials.design == "rfc":
        return "design"
    return "approve" if approval == "required" else "implement"


async def resume(harness=None, tools=None) -> Driver:
    """Re-enter this session's live run at the phase its journal left open."""
    harness = harness or LiveHarness()
    tools = tools or Toolchain()
    live = await _own_live_scratches(harness, tools)
    if not live:
        raise RuntimeError("this session drives no live /ship run with a driver journal")
    if len(live) > 1:
        raise RuntimeError(f"this session drives more than one live run; which? {', '.join(map(str, live))}")
    run = Run(live[0], harness, tools)
    phase = open_phase(run)
    existing = _DRIVERS.get(run.run_id)
    if existing and existing.task and not existing.task.done():
        return existing
    run.journal("resume", phase=phase)
    driver = Driver(run, _dials_from_state(run))
    driver.launch(phase)
    return driver


def open_phase(run) -> str:
    """The phase the journal left open, refusing when the state file's PHASE disagrees with it."""
    phase = run.open_step()
    if phase is None:
        raise RuntimeError(f"{run.rs}: the journal has no open phase")
    recorded = run.get("PHASE")
    if recorded not in CONSISTENT_PHASE[phase]:
        raise RuntimeError(f"{run.rs}: PHASE={recorded} disagrees with the journal's open phase {phase!r} "
                           f"(expected one of {sorted(CONSISTENT_PHASE[phase])}); fix the state file, do not guess")
    return phase


async def watchdog(harness=None, tools=None):
    """The heartbeat's cell: a live task prints one line; a dead one of this session's is resumed."""
    for driver in _DRIVERS.values():
        if driver.task and not driver.task.done():
            print(driver.status())
            return
    harness = harness or LiveHarness()
    tools = tools or Toolchain()
    if not await _own_live_scratches(harness, tools):
        print("ship watchdog: no live run of this session's")
        return
    driver = await resume(harness, tools)
    print(f"ship watchdog: resumed — {driver.status()}")
