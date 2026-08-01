# Review tier manifest

Ordered groups for `/manual-review-fix`. Every reviewer skill is assigned to
exactly one tier by the **blast radius of the change its findings induce** — not
by how important the rule is. Tiers are triaged in order, so a structural
finding is decided before the nits that its fix might delete.

This file is the source of ordering. `/manual-review-fix` discovers the live
skill set by glob and **asserts every discovered skill appears here** — an
unlisted skill halts the run rather than being silently dropped. Add a skill,
add a row.

Move a skill between tiers freely; the tier is a judgement about typical fix
size and the operator's is better than the author's.

## Tier 0 — RFC conformance (prep)

Runs and is triaged first. These ask "does the implementation match the RFC?",
not "is this code good?" A finding here often means the **RFC** is wrong rather
than the code, so this tier is the only one whose triage offers the _revise the
RFC_ outcome.

| Skill                           | Anchor                                        |
| ------------------------------- | --------------------------------------------- |
| `impl-review-files-modified`    | `## Files Modified` vs the actual changed set |
| `impl-review-plan-followed`     | `## Implementation Plan` steps executed       |
| `impl-review-design-adherence`  | `## Detailed Design` declarations verbatim    |
| `impl-review-feature-creep`     | traceability back to an RFC mandate           |
| `impl-review-tests-implemented` | `## Tests` cases mapped to real tests         |

## Tier 1 — multi-file / structural

Fixes that move code across file or module boundaries, change a type that
callers depend on, or delete a speculative abstraction.

| Skill                              |
| ---------------------------------- |
| `design-review-common-abstraction` |
| `design-review-generalization`     |
| `design-review-srp`                |
| `design-review-feature-isolation`  |
| `design-review-impossible-misuse`  |
| `design-review-no-remote-breakage` |
| `design-review-scale-restraints`   |
| `design-review-solve-completely`   |
| `design-review-domain-outcomes`    |
| `code-review-dry-abstractions`     |
| `code-review-constructor-di`       |
| `code-review-execution-context`    |
| `code-review-explicit-routing`     |

## Tier 2 — single-file

Fixes contained to one file: a changed signature and its local callers, an
extracted private helper, a swapped implementation.

| Skill                                         |
| --------------------------------------------- |
| `design-review-best-effort-vs-all-or-nothing` |
| `design-review-minimum-context`               |
| `design-review-actionable-errors`             |
| `code-review-single-level-abstraction`        |
| `code-review-exhaustive-eval`                 |
| `code-review-structured-payloads`             |
| `code-review-unit-encapsulation`              |
| `code-review-immutable-returns`               |
| `code-review-no-leaks`                        |
| `code-review-error-bubbling`                  |
| `code-review-lossless-domain-mapping`         |
| `code-review-allowlist`                       |
| `code-review-no-sentinels`                    |
| `code-review-reuse-hardened-primitives`       |
| `code-review-parameterize-bounds`             |

`structured-payloads` and `unit-encapsulation` are the two most likely to belong
in Tier 1 — introducing a domain type can ripple to every call site. They sit
here because in practice the type lands next to its use.

## Tier 3 — 1–4 lines

Renames, message wording, log formatting, a comment. Triaged last because a Tier
1 fix routinely deletes the line a Tier 3 finding is about.

| Skill                               |
| ----------------------------------- |
| `code-review-concrete-names`        |
| `code-review-no-smurf-naming`       |
| `code-review-standard-verbs`        |
| `code-review-bracket-serialization` |
| `code-review-semantic-output`       |
| `code-review-contextual-comments`   |
