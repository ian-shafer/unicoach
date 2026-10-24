"""The answer each judgement kind accepts. A payload that fits none of its kind's shapes is refused when the model
answers, while the ask is still open — never accepted to fail later inside the driver's task."""
import dataclasses
import re


@dataclasses.dataclass(frozen=True)
class Ack:
    """Done; the driver re-checks or re-runs what it asked about."""


@dataclasses.dataclass(frozen=True)
class Design:
    path: str


@dataclasses.dataclass(frozen=True)
class Approval:
    approved: bool
    decisions: list = dataclasses.field(default_factory=list)


@dataclasses.dataclass(frozen=True)
class Decision:
    answer: str


@dataclasses.dataclass(frozen=True)
class Rebrief:
    brief: str


@dataclasses.dataclass(frozen=True)
class SkipLens:
    skip: str


@dataclasses.dataclass(frozen=True)
class RedispatchLens:
    redispatch: str  # why a third report should differ


@dataclasses.dataclass(frozen=True)
class ChildFinished:
    finished: str  # what the model did that completes the child's work; the driver checks it again


@dataclasses.dataclass(frozen=True)
class RespawnChild:
    respawn: str  # why a fresh child should redo the work


@dataclasses.dataclass(frozen=True)
class ReportProse:
    opening: str
    learning_candidates: str
    subject: str | None = None  # None: commit_subject derives it from the RFC title or the slug


SHAPES = {
    "design": (Design,), "approval": (Approval,), "decision": (Decision,),
    "scope-deny": (Rebrief, Ack), "malformed-twice": (SkipLens, RedispatchLens),
    "child-failed": (ChildFinished, RespawnChild), "report-prose": (ReportProse,),
    "suite-red": (Ack,), "rebase-conflict": (Ack,), "hook-failed": (Ack,), "approved-decision": (Ack,),
    "blocking-contradicted": (Ack,), "announce": (Ack,),
}


# The str fields that carry prose; every other str a model answers with is one non-blank line (a path, a reason
# ship-lens records in a TSV column, a decision sent as one message line).
_PROSE = {(Rebrief, "brief"), (ReportProse, "opening"), (ReportProse, "learning_candidates")}


def _type_name(annotation) -> str:
    return annotation.__name__ if isinstance(annotation, type) else str(annotation)


def _describe(shape) -> str:
    return "{" + ", ".join(f"{field.name!r}: {_type_name(field.type)}" for field in dataclasses.fields(shape)) + "}"


def shapes(kind) -> list:
    """The payloads a kind accepts, written as the model sends them; an unknown kind is a driver defect."""
    if kind not in SHAPES:
        raise ValueError(f"no answer shape for judgement kind {kind!r}")
    return [_describe(shape) for shape in SHAPES[kind]]


def _fits(shape, payload) -> bool:
    fields = {field.name: field for field in dataclasses.fields(shape)}
    required = {name for name, field in fields.items()
                if field.default is dataclasses.MISSING and field.default_factory is dataclasses.MISSING}
    if set(payload) - set(fields) or required - set(payload):
        return False
    for name, value in payload.items():
        if not isinstance(value, fields[name].type):
            return False
        if isinstance(value, str) and (shape, name) not in _PROSE and (not value.strip() or re.search(r"[\n\t]", value)):
            return False
    return True


def parse(kind, payload):
    """The payload as the one answer type of `kind` it fits; ValueError naming the accepted shapes otherwise."""
    accepted = shapes(kind)
    if isinstance(payload, dict):
        for shape in SHAPES[kind]:
            if _fits(shape, payload):
                return shape(**payload)
    raise ValueError(f"a {kind} answer is {' or '.join(accepted)}, not {payload!r}")
