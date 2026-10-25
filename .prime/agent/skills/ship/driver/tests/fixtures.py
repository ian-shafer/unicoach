"""Shared fixtures: a throwaway repo with a lens corpus and stub tools, a run scratch, and a FakeHarness."""
import asyncio
import dataclasses
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DRIVER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DRIVER))
REPO_ROOT = DRIVER.parents[4]

from ship_driver.run import Toolchain, read_record  # noqa: E402,F401

REAL_SCRIPTS = Toolchain().scripts
# The real scripts, stub tools run directly (no dev shell), and a fast poll; a test that needs its own checkout or
# scripts directory replaces those fields.
TOOLS = Toolchain(original=REPO_ROOT, scripts=REAL_SCRIPTS, prefix=(), poll_seconds=0.01)


def tools(**fields) -> Toolchain:
    return dataclasses.replace(TOOLS, **fields)


def archive_dir():
    """The /ship archive of this machine, if any: gitignored, so tests that read it skip without it."""
    path = os.environ.get("SHIP_ARCHIVE")
    if not path:
        common = subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "--path-format=absolute",
                                 "--git-common-dir"], capture_output=True, text=True).stdout.strip()
        path = Path(common).parent / ".scratch" / "ship-archive" if common else ""
    return Path(path) if path and Path(path).is_dir() else None


def git(cwd, *args):
    return subprocess.run(["git", "-C", str(cwd), *args], check=True, capture_output=True, text=True).stdout


TIER_LENSES = {
    0: ["impl-review-plan-followed"],
    1: ["code-review-dry-abstractions"],
    2: ["code-review-semantic-output", "code-review-immutable-returns"],
    3: ["code-review-contextual-comments"],
}

STUBS = {
    "bin/test": 'echo "stub suite"; exit "${STUB_TEST_EXIT:-0}"',
    "bin/format": 'exit "${STUB_FORMAT_EXIT:-0}"',
    "bin/prompt-seed": "exit 0",
    "bin/shell-tests": "exit 0",
    "bin/gate-scope": 'echo "scope=full"; echo "gradle=${STUB_GRADLE:-yes}"; echo "harnesses=${STUB_HARNESSES:-}"',
    "hooks/pre-commit": f'[ "${{STUB_HOOK_EXIT:-0}}" = 0 ] || {{ echo "FAILED stub test"; exit 1; }}\n'
                        f'"{REAL_SCRIPTS}/ship-verified" record -s full',
}


def write_exec(path: Path, body: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/usr/bin/env bash\n" + body + "\n")
    path.chmod(0o755)


def make_repo(tmp) -> Path:
    repo = Path(tmp) / "repo"
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.email", "t@example.com")
    git(repo, "config", "user.name", "t")
    git(repo, "config", "core.hooksPath", "hooks")
    (repo / ".gitignore").write_text(".scratch/\n")
    skills = repo / ".claude" / "skills"
    rows = []
    for tier, lenses in TIER_LENSES.items():
        rows.append(f"\n## Tier {tier} — fixture\n\n| Skill |\n| ----- |\n" + "".join(f"| `{l}` |\n" for l in lenses))
        for lens in lenses:
            (skills / lens).mkdir(parents=True)
            (skills / lens / "SKILL.md").write_text(f"---\nname: {lens}\ndescription: the {lens} rule\n---\n# {lens}\n")
    (skills / "review-fix").mkdir()
    (skills / "review-fix" / "tiers.md").write_text("# Review tier manifest\n" + "".join(rows))
    (skills / "findings-output.template.md").write_text("# Findings output template\n")
    for name, body in STUBS.items():
        write_exec(repo / name, body)
    (repo / "db" / "schema").mkdir(parents=True)
    (repo / "db" / "schema" / "ORDER").write_text("")
    (repo / "README.md").write_text("fixture\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "--no-verify", "-m", "fixture")
    return repo


def make_rs(tmp, repo, run_id="run-a", **extra) -> Path:
    rs = Path(tmp) / "scratch" / run_id
    (rs / "findings").mkdir(parents=True)
    base = git(repo, "rev-parse", "HEAD").strip()
    state = {"RUN_ID": run_id, "LANE": "quick", "RFC_NUM": "", "SLUG": run_id, "BRANCH": f"ship/{run_id}",
             "CODEBASE_ROOT": repo, "ORIGINAL_CHECKOUT": repo, "BASE_BRANCH": "main", "BASE_SHA": base,
             "RUN_SCRATCH": rs, "PHASE": "verifying", "DIAL_DESIGN": "none", "DIAL_SCRUTINY": "full", **extra}
    (rs / "state").write_text("".join(f"{k}={v}\n" for k, v in state.items()))
    return rs


def install_scripts(repo) -> Path:
    """Drive the fixture from a scripts directory inside it: ship-status lists the runs of the worktrees of its
    OWN checkout, so it is copied in and sees the fixture's runs and none of this machine's. Every other script is a
    wrapper that execs the real one, which keeps its own checkout (ship-lock's bin/wait-for)."""
    scripts = Path(repo) / ".prime" / "agent" / "skills" / "ship" / "scripts"
    scripts.mkdir(parents=True)
    for real in REAL_SCRIPTS.glob("ship-*"):
        if real.name == "ship-status":
            shutil.copy2(real, scripts / real.name)
        else:
            write_exec(scripts / real.name, f'exec "{real}" "$@"')
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "--no-verify", "-m", "ship scripts")
    return scripts


def report_text(lens, grade="CLEAN", findings=()):
    verdict = "🟢 PASS" if not findings else "🔴 FAIL"
    body = "".join(f"\n### {title} [{tag}]\n\n**Code** — `{where}`\n\nwhat is wrong\n" for title, tag, where in findings)
    return f"# Review Report: {lens}\n\n**Verdict:** {verdict}\n**Grade:** {grade}\n\n## Findings\n{body}"


class FakeHarness:
    """Children 'run' by a callback on their dispatch file at spawn and stay busy for the polls it returns.

    `behave(name, dispatch, harness)` returns a poll count, or (polls, then) where `then()` runs when the child
    stops being busy — a child that writes its marker late."""

    def __init__(self, behave=None, tmp=None):
        self.behave = behave or default_behaviour
        self.spawned, self.wakes, self.sent, self.deleted, self.compactions = [], [], [], [], []
        self.heartbeats = {}
        self.pending_polls, self.then = {}, {}
        self.tmp = Path(tmp or tempfile.mkdtemp())

    async def spawn(self, prompt, name, model=None):
        child_id = f"sub-{len(self.spawned) + 1}"
        dispatch = path_in(prompt, r"Your complete task is in (\S+) ").read_text()
        self.spawned.append({"name": name, "id": child_id, "prompt": prompt, "dispatch": dispatch, "model": model})
        behaviour = self.behave(name, dispatch, self) or 0
        polls, then = behaviour if isinstance(behaviour, tuple) else (behaviour, None)
        self.pending_polls[name] = polls
        if then is not None:
            self.then[name] = then
        return child_id

    async def busy(self, name):
        left = self.pending_polls.get(name, 0)
        self.pending_polls[name] = max(left - 1, 0)
        if left <= 1 and name in self.then:
            self.then.pop(name)()
        return left > 0

    async def list_subagents(self):
        return []

    async def delete(self, child_id):
        self.deleted.append(child_id)

    async def send(self, name, message):
        self.sent.append((name, message))

    def wake(self, text):
        self.wakes.append(text)

    async def session_id(self):
        return "coord-1"

    async def session_log(self):
        return self.tmp / "sessions" / f"{await self.session_id()}.jsonl"

    def agent_dir(self):
        return self.tmp / "agent"

    async def heartbeat_create(self, instruction, interval, label):
        self.heartbeats["hb-1"] = {"instruction": instruction, "interval": interval, "status": "active"}
        return "hb-1"

    async def heartbeat_update(self, heartbeat_id, status):
        self.heartbeats[heartbeat_id]["status"] = status

    async def heartbeat_delete(self, heartbeat_id):
        self.heartbeats[heartbeat_id]["status"] = "deleted"

    async def compact(self, instructions):
        self.compactions.append(instructions)


def path_in(prompt, pattern):
    match = re.search(pattern, prompt)
    return Path(match.group(1)) if match else None


def finish_progress(prompt, line="finished"):
    progress = path_in(prompt, r"(\S+/progress\.md)")
    progress.parent.mkdir(parents=True, exist_ok=True)
    progress.write_text(f"# progress\n\n## Done\n{line}\n")


def default_behaviour(name, prompt, harness):
    """A clean lens report; a fix child that accepts every finding; an implementer that writes one file."""
    if prompt.startswith("# Lens:"):
        path_in(prompt, r"Write your report to (\S+) and change").write_text(report_text("lens"))
    elif " fix child of " in prompt:
        outcomes = path_in(prompt, r"(\S+outcomes\.jsonl)")
        with open(outcomes, "a") as file:
            for match in re.finditer(r"^## (\S+) #(\d+) \[([A-Z-]+)\]", prompt, re.M):
                file.write(json.dumps({"lens": match.group(1), "finding_id": int(match.group(2)),
                                       "grade": match.group(3), "file": "src/a.sh", "line": 1,
                                       "outcome": "ACCEPTED", "out_of_lens": False, "reason": "applied"}) + "\n")
        path_in(prompt, r"Write what you did to (\S+fix-report\.md)").write_text("fixed\n")
        finish_progress(prompt)
    elif prompt.startswith("You are the implementer"):
        root = path_in(prompt, r"Worktree \(do ALL work here\): (\S+)")
        (root / "src").mkdir(exist_ok=True)
        (root / "src" / "a.sh").write_text("#!/bin/sh\necho hi\n")
        finish_progress(prompt)
    return 0


async def until(predicate):
    """Poll a condition the driver's task makes true; the caller bounds it with asyncio.wait_for."""
    while not predicate():
        await asyncio.sleep(0.01)


async def autopilot(driver_box, answers, log, stop):
    """Answer every ask by kind from `answers` (a payload or a callable on the ask) until `stop` is set."""
    while not stop.is_set():
        driver = driver_box.get("driver")
        if driver is not None:
            ask = driver.run.open_ask()
            answer_file = driver.run.dir / "answers" / f"{ask['n']}.json" if ask else None
            if ask and not answer_file.exists():
                payload = answers[ask["kind"]]
                payload = payload(ask, driver) if callable(payload) else payload
                log.append(ask["kind"])
                await driver.answer(ask["kind"], payload)
        await asyncio.sleep(0.01)
