"""Which lenses can fire on a diff: path classes, the lens map, and the retired set (RFC 256 D4)."""
from pathlib import Path

CODE_CLASSES = frozenset({"kotlin", "swift", "ts", "python", "sql", "shell", "config"})
TYPED = frozenset({"kotlin", "swift", "ts", "python"})

_EXTENSIONS = {
    ".kt": "kotlin", ".kts": "kotlin",
    ".swift": "swift",
    ".ts": "ts", ".tsx": "ts", ".js": "ts", ".mjs": "ts",
    ".py": "python",
    ".sql": "sql",
    ".sh": "shell", ".bash": "shell",
    ".md": "markdown",
}

# One row per lens in .claude/skills/review-fix/tiers.md. review.py dispatches a lens missing here rather than
# guess its group, and the applicability test fails until the row is added.
_GROUP_CLASSES = {"typed": TYPED, "shell": frozenset({"shell"}), "code": CODE_CLASSES, "impl": CODE_CLASSES}
LENS_GROUP = {
    **{lens: "typed" for lens in (
        "code-review-constructor-di", "code-review-execution-context", "code-review-immutable-returns",
        "code-review-structured-payloads", "code-review-unit-encapsulation",
        "code-review-lossless-domain-mapping", "code-review-no-sentinels",
        "design-review-impossible-misuse", "design-review-domain-outcomes",
        "design-review-feature-isolation", "code-review-no-leaks")},
    "code-review-semantic-output": "shell",
    **{lens: "code" for lens in (
        "code-review-dry-abstractions", "code-review-exhaustive-eval", "code-review-single-source-of-truth",
        "code-review-allowlist", "code-review-error-bubbling", "code-review-parameterize-bounds",
        "code-review-single-level-abstraction", "code-review-explicit-routing",
        "code-review-reuse-hardened-primitives", "code-review-bracket-serialization",
        "code-review-handle-return-values", "code-review-concrete-names", "code-review-no-smurf-naming",
        "code-review-standard-verbs", "code-review-contextual-comments",
        "design-review-common-abstraction", "design-review-generalization", "design-review-srp",
        "design-review-no-remote-breakage", "design-review-scale-restraints",
        "design-review-solve-completely", "design-review-best-effort-vs-all-or-nothing",
        "design-review-minimum-context", "design-review-actionable-errors")},
    **{lens: "impl" for lens in (
        "impl-review-files-modified", "impl-review-plan-followed", "impl-review-design-adherence",
        "impl-review-feature-creep", "impl-review-tests-implemented")},
}

_RETIRED_WHY = {
    "code-review-concrete-names": "24 dispatches, no fix-grade finding",
    "code-review-standard-verbs": "2 accepted in 23 dispatches",
    "code-review-bracket-serialization": "4 accepted in 23 dispatches",
    "code-review-constructor-di": "5 accepted in 21 dispatches, 4 of the archive's 16 contradictions",
}
RETIRED = frozenset(_RETIRED_WHY)


def retired_reason(lens: str) -> str:
    return f"retired by RFC 256: {_RETIRED_WHY[lens]}"


def classify_path(path: str, root: Path) -> str:
    if path.startswith("prompts/"):
        return "prompt"
    suffix = Path(path).suffix
    if suffix in _EXTENSIONS:
        return _EXTENSIONS[suffix]
    if suffix:
        return "config"
    try:
        with open(root / path, "rb") as file:
            first = file.readline(200)
    except FileNotFoundError:  # deleted by this diff: no shebang to read
        return "config"
    if first.startswith(b"#!"):
        if b"python" in first:
            return "python"
        if b"sh" in first:
            return "shell"
    return "config"


def classify(paths, root: Path) -> frozenset:
    return frozenset(classify_path(path, Path(root)) for path in paths)


def skip_reason(lens: str, classes: frozenset, design: str):
    """The reason ship-lens records for a lens the map removes (one text per group per tier), or None when the lens
    can fire."""
    if lens in RETIRED:
        return retired_reason(lens)
    group = LENS_GROUP[lens]
    if group == "impl" and design != "rfc":
        return "no RFC in this run"
    if not _GROUP_CLASSES[group] & classes:
        present = ", ".join(sorted(classes)) or "no paths"
        return f"no lens of the {group} group can fire on the changed classes: {present}"
    return None


def allowed(lens: str, classes: frozenset, design: str) -> bool:
    return skip_reason(lens, classes, design) is None
