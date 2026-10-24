"""The four dials of RFC 256 D3: two chosen (design, scrutiny), two derived (approval, tests)."""
import dataclasses
import fnmatch
import os
import re
import tempfile
import typing
from pathlib import Path
from typing import Literal

from .run import ScriptFailed, parse_assignments, sh

Design = Literal["none", "rfc"]
Scrutiny = Literal["none", "targeted", "full"]


@dataclasses.dataclass(frozen=True)
class Dials:
    design: Design
    scrutiny: Scrutiny
    narrow: bool = False

    def __post_init__(self):
        for dial, allowed in (("design", typing.get_args(Design)), ("scrutiny", typing.get_args(Scrutiny))):
            if getattr(self, dial) not in allowed:
                raise ValueError(f"{dial} must be one of {allowed}, not {getattr(self, dial)!r}")
        if self.narrow and self.scrutiny != "targeted":
            raise ValueError(f"narrow applies only to scrutiny='targeted', not {self.scrutiny!r}")


# Paths whose change cannot be cheaply reversed; any one of them makes approval required. Each glob names paths
# that exist (test_dials checks every one against `git ls-files`); a subject with no code yet — payments, data
# deletion — is caught by the instruction's words below until its code lands.
IRREVERSIBLE_GLOBS = (
    "db/schema/*", "db/seed/*", "prompts/*",
    "service/*/budget/*", "service/*/subscriptions/*", "appstore/*",
    "*StoreKit*", "*storekit*", "*Paywall*", "*Subscription*",
    "*/auth/*", "*Auth*.kt",
    "PRODUCT.md", "public-web/*HomePage*",
)
_IRREVERSIBLE_WORDS = re.compile(
    r"\b(migration|schema|seed|system prompt|billing|budget|storekit|payment|auth|authentication|authorization|"
    r"delete (an |the )?account|data deletion|home page|tagline|PRODUCT\.md)\b", re.IGNORECASE)
_PATHLIKE = re.compile(r"[\w.-]+(?:/[\w.*-]+)+|[\w-]+\.(?:md|kt|kts|sql|swift|ts|py|conf)\b")


def paths_named(instruction: str) -> list:
    return _PATHLIKE.findall(instruction)


def derive_approval(design: str, paths, instruction: str = "") -> tuple:
    """('required' | 'skipped', reason). fnmatch's * crosses '/', so 'db/schema/*' is the whole tree."""
    if design == "rfc":
        return "required", "design=rfc"
    for path in paths:
        for glob in IRREVERSIBLE_GLOBS:
            if fnmatch.fnmatchcase(path, glob):
                return "required", f"{path} matches {glob}"
    word = _IRREVERSIBLE_WORDS.search(instruction)
    if word:
        return "required", f"the instruction names {word.group(0)!r}"
    return "skipped", "no irreversible path or subject"


@dataclasses.dataclass(frozen=True)
class TestDepth:
    """bin/gate-scope's answer, typed where it is read: its `harnesses=ALL` is every_harness, never a harness name."""
    scope: str
    gradle: bool
    every_harness: bool
    harnesses: tuple = ()


def _test_depth(fields: dict, separator: str) -> TestDepth:
    """gate-scope's three keys, exactly; ValueError naming what is wrong otherwise."""
    absent = [key for key in ("scope", "gradle", "harnesses") if key not in fields]
    if absent:
        raise ValueError(f"no {', '.join(absent)}")
    if fields["gradle"] not in ("yes", "no"):
        raise ValueError(f"gradle={fields['gradle']!r} is neither yes nor no")
    names = tuple(name for name in fields["harnesses"].split(separator) if name)
    every = names == ("ALL",)
    return TestDepth(fields["scope"], fields["gradle"] == "yes", every, () if every else names)


async def test_depth(root, base: str, gate_scope=None) -> TestDepth:
    """bin/gate-scope over the base-to-HEAD name set, through a temporary index; the real index is untouched.

    `base` is any tree-ish: a commit for the tests dial, a recorded tree for RFC 211's suite reuse."""
    root = Path(root)
    argv = [gate_scope or root / "bin" / "gate-scope", "-r", root]
    with tempfile.TemporaryDirectory() as scratch:
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(scratch) / "index")}
        await sh(["git", "-C", root, "read-tree", base], env=env, check=True)
        proc = await sh(argv, env=env, check=True)
    try:
        return _test_depth(parse_assignments(proc.out.splitlines()), " ")
    except ValueError as bad:
        raise ScriptFailed(argv, proc, f"over {base} printed an answer the driver cannot read ({bad})") from None


def dial_line(depth: TestDepth) -> str:
    """DIAL_TESTS as one shell word: the scripts `source` the state file, so a value must not contain a space."""
    harnesses = "ALL" if depth.every_harness else "+".join(depth.harnesses)
    return f"scope={depth.scope},gradle={'yes' if depth.gradle else 'no'},harnesses={harnesses}"


def parse_dial_line(line: str) -> TestDepth:
    try:
        return _test_depth(parse_assignments(line.split(",")), "+")
    except ValueError as bad:
        raise ValueError(f"DIAL_TESTS {line!r} is not scope=<s>,gradle=yes|no,harnesses=<names|ALL>: {bad}") from None
