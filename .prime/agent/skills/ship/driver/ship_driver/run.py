"""One run on disk: its state (through ship-state), its journal, its open judgement and the answers to it."""
import asyncio
import dataclasses
import datetime
import json
import shlex
import subprocess
import typing
from pathlib import Path

from . import answers

DRIVER_DIR = Path(__file__).resolve().parents[1]
SUITE_ARGV = ("bin/test",)

# The machine's phase -> the PHASE value ship-status shows. "claimed" is ship-claim's own (it writes PHASE=claimed).
PHASE_VALUE = {"claim": "claimed", "design": "designing", "approve": "designing", "implement": "implementing",
               "verify": "verifying", "land": "landing", "report": "landing", "complete": "complete"}
# What approve() writes once Ian approves, from inside approve or implement, on top of the phase's own value.
APPROVED_PHASE_VALUE = "approved"


@dataclasses.dataclass(frozen=True)
class Toolchain:
    """Where the driver finds its tools; start() and resume() take one, and every Run holds it."""
    # The checkout the driver is imported from: the landed skill drives the run (RFC 256 D1).
    original: Path = DRIVER_DIR.parents[4]
    scripts: Path = DRIVER_DIR.parent / "scripts"
    # The toolchain lives in the dev shell (CLAUDE.md); tests run their stubs with ().
    prefix: tuple = ("nix", "develop", "-c")
    poll_seconds: float = 15

    @property
    def suite_cmd(self) -> str:
        """The suite as ship-run writes it in `cmd=` (each word %q-quoted, space-joined); RFC 211 reuse compares it."""
        return shlex.join((*self.prefix, *SUITE_ARGV))


def now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def parse_assignments(parts) -> dict:
    """KEY=VALUE pairs from an iterable of strings, the format of ship-state, ship-claim, ship-run records and
    bin/gate-scope; a string without '=' is not a pair."""
    return dict(part.split("=", 1) for part in parts if "=" in part)


def read_record(path) -> dict:
    path = Path(path)
    return parse_assignments(path.read_text().splitlines()) if path.exists() else {}


def read_jsonl(path) -> list:
    path = Path(path)
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def archive_root(original) -> Path:
    """Where ship-land copies a landed run scratch (scripts/ship-land's `archive=`). A bash script cannot read this,
    so the location is stated there and here: a drift makes resume miss a landed run and fail by name."""
    return Path(original) / ".scratch" / "ship-archive"


def _tsv(path) -> list:
    path = Path(path)
    return [line.split("\t") for line in path.read_text(errors="replace").splitlines()] if path.exists() else []


class ShipRunRow(typing.NamedTuple):
    at: str
    event: str  # "start" | "end"
    fields: dict


def ship_run_ledger(rs) -> list:
    """<rs>/runs/ledger.tsv as ship-run writes it: one row per command start and end, then k=v fields."""
    return [ShipRunRow(row[0], row[1], parse_assignments(row[2:])) for row in _tsv(Path(rs) / "runs" / "ledger.tsv")
            if len(row) >= 3]


class LensLedgerRow(typing.NamedTuple):
    event: str  # "start" | "skip" | "done"
    tier: int
    lens: str
    detail: str  # the child id on start, the reason on skip; "" when the row carries none


def lens_ledger(rs) -> list:
    """<rs>/findings/lens-ledger.tsv as ship-lens writes it: event, tier, lens, then the event's own field."""
    return [LensLedgerRow(row[0], int(row[1]), row[2], row[3] if len(row) > 3 else "")
            for row in _tsv(Path(rs) / "findings" / "lens-ledger.tsv") if len(row) >= 3]


class CheckpointRow(typing.NamedTuple):
    sha: str
    label: str


def checkpoints(rs) -> list:
    """<rs>/checkpoints.log as ship-checkpoint writes it: the commit, then its label."""
    return [CheckpointRow(row[0], row[1]) for row in _tsv(Path(rs) / "checkpoints.log") if len(row) > 1]


@dataclasses.dataclass
class Proc:
    code: int
    out: str
    err: str


class ScriptFailed(RuntimeError):
    """A command that failed, or answered in a shape the driver cannot read: its argv and its whole Proc."""

    def __init__(self, argv, proc: Proc, problem=None):
        super().__init__(argv, proc, problem)
        self.argv = tuple(map(str, argv))
        self.proc = proc
        self.problem = problem or f"exited {proc.code}"

    def __str__(self):
        out = f"\n--- stdout ---\n{self.proc.out.rstrip()}" if self.proc.out.strip() else ""
        return f"{shlex.join(self.argv)} {self.problem}\n--- stderr ---\n{self.proc.err.rstrip()}{out}"


async def sh(argv, cwd=None, env=None, check=False) -> Proc:
    """The driver's one way to run a process: on the event loop, never blocking it; `check` raises on a non-zero exit."""
    proc = await asyncio.create_subprocess_exec(*map(str, argv), cwd=cwd, env=env,
                                                stdin=subprocess.DEVNULL,
                                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out, err = await proc.communicate()
    result = Proc(proc.returncode, out.decode(errors="replace"), err.decode(errors="replace"))
    if check and result.code != 0:
        raise ScriptFailed(argv, result)
    return result


class Run:
    def __init__(self, rs, harness, tools: Toolchain):
        self.rs = Path(rs)
        self.harness = harness
        self.tools = tools
        self.dir = self.rs / "driver"
        self.waiting_on: str | None = None

    def move_to(self, rs) -> "Run":
        """Point this run, in place, at where ship-land moved its scratch; every holder of it (the Driver's loop and
        its error handler included) then reads and writes there."""
        self.rs = Path(rs)
        self.dir = self.rs / "driver"
        return self

    # ── state ────────────────────────────────────────────────────────────
    def state(self) -> dict:
        """The state file, read in-process: `ship-state show` is `cat`. Writes go through ship-state."""
        return read_record(self.rs / "state")

    def get(self, key, default=None):
        return self.state().get(key, default)

    async def set(self, key, value):
        await sh([self.tools.scripts / "ship-state", "-s", self.rs, "set", key, str(value)], check=True)

    @property
    def run_id(self) -> str:
        return self.get("RUN_ID")

    @property
    def root(self) -> Path:
        return Path(self.get("CODEBASE_ROOT"))

    @property
    def approval(self) -> str:
        return self.get("DIAL_APPROVAL", "skipped")

    @property
    def tests(self) -> str:
        """DIAL_TESTS as recorded; never recorded is a fault, not "no tests"."""
        tests = self.get("DIAL_TESTS")
        if tests is None:
            raise RuntimeError(f"{self.rs / 'state'} has no DIAL_TESTS; verify/rebase records it before a gate reads it")
        return tests

    # ── the agent session driving this run: driver-only, never in the shared state file ──
    @property
    def session_path(self) -> Path:
        return self.dir / "session.json"

    def session(self) -> dict:
        return json.loads(self.session_path.read_text()) if self.session_path.exists() else {}

    def bind_session(self, **fields):
        self.dir.mkdir(parents=True, exist_ok=True)
        staged = self.dir / ".session.json"
        staged.write_text(json.dumps({**self.session(), **{k: str(v) for k, v in fields.items()}}, indent=2) + "\n")
        staged.rename(self.session_path)

    async def heartbeat(self, status):
        heartbeat_id = self.session().get("heartbeat_id")
        if heartbeat_id:
            await self.harness.heartbeat_update(heartbeat_id, status)

    # ── commands ─────────────────────────────────────────────────────────
    async def script(self, name, *args, check=True, cwd=None) -> Proc:
        """A ship-* script with -s <rs>, run from the original checkout unless told otherwise."""
        cwd = cwd or self.get("ORIGINAL_CHECKOUT")
        proc = await sh([self.tools.scripts / name, "-s", self.rs, *args], cwd=cwd)
        self.journal("subprocess", cmd=f"{name} {' '.join(map(str, args))}".strip(), exit=proc.code)
        if check and proc.code != 0:
            raise ScriptFailed([name, *args], proc)
        return proc

    async def ship_run(self, name, argv, gate=False, tool=True) -> dict:
        """A long command through ship-run -c driver; returns the record THIS invocation printed. ship-run's stdout
        is its record and nothing else, and a ship-run that refused prints none."""
        command = [*self.tools.prefix, *argv] if tool else list(argv)
        self.waiting_on = f"ship-run {name}"
        args = ["-n", name, "-c", "driver", *(["-g"] if gate else []), "--", *command]
        proc = await self.script("ship-run", *args, check=False, cwd=self.root)
        self.waiting_on = None
        record = parse_assignments(proc.out.splitlines())
        if "exit" not in record:
            raise ScriptFailed(["ship-run", *args], proc, f"exited {proc.code} and wrote no record")
        return record

    async def git(self, *args, check=True) -> Proc:
        return await sh(["git", "-C", self.root, *args], check=check)

    async def changed_paths(self) -> list:
        base = self.get("BASE_SHA")
        out = (await self.git("diff", "--name-only", base, "HEAD")).out
        return [line for line in out.splitlines() if line]

    # ── journal ──────────────────────────────────────────────────────────
    def journal(self, ev, **fields):
        self.dir.mkdir(parents=True, exist_ok=True)
        with open(self.dir / "journal.jsonl", "a") as file:
            file.write(json.dumps({"at": now(), "ev": ev, **fields}) + "\n")

    def events(self) -> list:
        return read_jsonl(self.dir / "journal.jsonl")

    async def enter(self, phase, step=None):
        """A phase, or a sub-step inside one: a journal event with no `step` is a phase's."""
        await self.set("DRIVER_STEP", f"{phase}/{step}" if step else phase)
        self.journal("enter", phase=phase, **({"step": step} if step else {}))

    def exit(self, phase, step=None):
        self.journal("exit", phase=phase, **({"step": step} if step else {}))

    def step_done(self, phase, step) -> bool:
        return any(e["ev"] == "exit" and e.get("phase") == phase and e.get("step") == step for e in self.events())

    def open_step(self):
        """The last phase entered and not exited, from the journal."""
        open_phases = []
        for event in self.events():
            if event["ev"] not in ("enter", "exit") or "step" in event:
                continue
            if event["ev"] == "enter":
                open_phases.append(event["phase"])
            elif event["phase"] in open_phases:
                open_phases.remove(event["phase"])
        return open_phases[-1] if open_phases else None

    async def set_phase(self, phase):
        await self.set("PHASE", PHASE_VALUE[phase])

    # ── judgement ────────────────────────────────────────────────────────
    @property
    def ask_path(self) -> Path:
        return self.dir / "ask.json"

    def open_ask(self):
        return json.loads(self.ask_path.read_text()) if self.ask_path.exists() else None

    def _next_answer_number(self) -> int:
        taken = [int(p.stem) for p in (self.dir / "answers").glob("*.json")]
        return max(taken, default=0) + 1

    async def ask(self, kind, line, **context):
        """Park on a judgement, wake the model, and return its answer as the answer type of `kind` it fits."""
        shapes = answers.shapes(kind)
        ask = self.open_ask()
        if ask is None or ask["kind"] != kind:
            ask = {"n": self._next_answer_number(), "kind": kind, "line": line, "answers": shapes,
                   "context": context, "at": now()}
            self.dir.mkdir(parents=True, exist_ok=True)
            self.ask_path.write_text(json.dumps(ask, indent=2) + "\n")
            self.journal("ask", kind=kind, n=ask["n"])
        self.wake(kind, line)
        answer_path = self.dir / "answers" / f"{ask['n']}.json"
        self.waiting_on = f"answer: {kind}"
        while not answer_path.exists():
            await asyncio.sleep(self.tools.poll_seconds)
        self.waiting_on = None
        payload = json.loads(answer_path.read_text())["payload"]
        self.ask_path.unlink(missing_ok=True)
        self.journal("answer", kind=kind, n=ask["n"])
        return answers.parse(kind, payload)

    def answer(self, kind, payload):
        """Stage the model's answer; one that fits none of the kind's shapes is refused here, in the model's cell."""
        ask = self.open_ask()
        if ask is None:
            raise ValueError("the driver is not waiting on a judgement")
        if ask["kind"] != kind:
            raise ValueError(f"the open judgement is {ask['kind']!r}, not {kind!r}")
        answers.parse(kind, payload)
        directory = self.dir / "answers"
        directory.mkdir(parents=True, exist_ok=True)
        staged = directory / f".{ask['n']}.json"
        staged.write_text(json.dumps({"n": ask["n"], "kind": kind, "payload": payload, "at": now()}) + "\n")
        staged.rename(directory / f"{ask['n']}.json")

    def wake(self, kind, line):
        text = f"ship-wake {self.run_id} {kind}: {line}; read {self.ask_path}"
        self.harness.wake(text)
        self.dir.mkdir(parents=True, exist_ok=True)
        with open(self.dir / "wakes.jsonl", "a") as file:
            file.write(json.dumps({"at": now(), "kind": kind, "line": line}) + "\n")
