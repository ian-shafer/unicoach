# RFC 143: No bare source code reaches a tool result

Status: Draft

Brief 0003, follow-on to RFC 142 (the deferred item it recorded).

## Summary

RFC 142 removed Scorecard bucket jargon from the income-band path after the
coach said "Q5 net price" to a parent. It left one instance of the same class
standing, and said so: `college_search` still emits the raw IPEDS control
integer — `"control": 2` — with no phrase attached, while the sibling cost tool
already emits `"public"`.

This RFC gives the control vocabulary one home both modules can reach, has
search emit the label, and — the part that outlasts this fix — replaces the
string-specific jargon guard with one that asserts the general property.

## Motivation

A full sweep of every model-facing payload in the repo (not the partial grep
that produced RFC 142's wrong first diagnosis) finds exactly one remaining bare
source code:

| Surface                       | Emits                          | Verdict                                   |
| ----------------------------- | ------------------------------ | ----------------------------------------- |
| `college_search` result       | `"control": 2`                 | the gap                                   |
| `college_cost_profile` result | `"control": "public"`          | already right                             |
| `region`, `locale`, CIP codes | input-schema descriptions only | correct — the filter contract IS the code |

The risk is lower than Q5's, and the RFC should say so rather than overclaim:
nothing instructs the model to cite `control` in results, whereas the search
description explicitly told it to cite the matching income band, which is what
made that jargon reach a user's screen. This lands because the class is worth
closing while it is one line, not because families are being harmed today.

**The durable half is the guard.** Today's test greps for `q[1-5]` and `NPT4` —
string-specific, so it stringifies the search payload and certifies it clean
while `"control": 2` sits inside it. That is precisely the failure RFC 142's own
Motivation criticises: a guard that covers the surface it was written for and
sleeps through the next instance. A property-level assertion is what stops this
recurring a third time.

## Detailed Design

### One home for the control vocabulary

`CollegeControl` (the sealed interface in `service`) carries `TuitionApplicable`
on its `Public` case — cost-domain logic that `college` has no business
importing, and `college` cannot see `service` anyway. So the _vocabulary_ splits
from the _cost semantics_:

- **`InstitutionControl`** — a plain enum in `db/models` beside `IncomeBand`,
  mapping the IPEDS code to its label (`1 -> public`, `2 -> private_nonprofit`,
  `3 -> private_for_profit`). A code the source does not define cannot be an
  enum member, so it is handled at the lookup: `fromCode` is nullable and
  `labelFor` renders `unknown (control [N])`, keeping the raw code observable
  rather than swallowing it into a defined label or dropping it.
- **`CollegeControl`** keeps its shape and its `Public(tuitionApplicable)` case,
  but derives its `label` from `InstitutionControl` instead of hand-writing the
  four strings. One vocabulary, two consumers — the `putIncomeBand` move again.
- `college_search` emits the label.

### The guard becomes a property

The jargon assertion moves from "this payload contains no `q1`..`q5` and no
`NPT4`" to the general form: **no value in a tool result is a bare source
code.** Concretely, over the rendered payload: no `qN` bucket token, no `NPT4`,
and no field named for a known coded dimension (`control`) carrying a bare
integer. Field names that are _documented codes by contract_ — the input schema
descriptions, and `income_band`, which `update_money_profile` accepts back — are
exempt by name, and the exemption list is short enough to read.

## Files Modified

| File                                                                       | Change                                                   |
| -------------------------------------------------------------------------- | -------------------------------------------------------- |
| `db/src/main/kotlin/ed/unicoach/db/models/InstitutionControl.kt`           | NEW — the code -> label vocabulary, one home             |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` | `CollegeControl.label` derives from `InstitutionControl` |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`         | emit the control label, not the integer                  |
| `college/src/test/.../CollegeSearchToolTest.kt`                            | the label assertion; the generalised guard               |
| `service/src/test/.../costs/CollegeCostChatToolTest.kt`                    | the generalised guard                                    |
| `db/src/test/.../InstitutionControlTest.kt`                                | NEW — pins the mapping                                   |
| `rfc/143-no-bare-source-codes.md`                                          | this document                                            |

No schema change. No prompt version — v6's rule already covers this; nothing new
to teach the coach. No API, no iOS.

## Implementation Plan

1. Add `InstitutionControl` in `db/models`; pin its mapping.
2. Derive `CollegeControl.label` from it; existing cost tests must pass
   unchanged (the emitted strings do not move).
3. Emit the label from `college_search`; update its tests.
4. Generalise the jargon guard in both tool tests.
5. `nix develop -c bin/test` (full suite, no scoping).

## Tests

- **`the control vocabulary has one home`** — `InstitutionControl` maps each
  IPEDS code to its label, and an unrecognised code keeps the raw value visible.
- **`search results name the control in words`** — the payload carries
  `"public"` / `"private_nonprofit"`, never a bare integer.
- **`the cost tool's labels are unchanged`** — existing assertions pass with no
  edit; this refactor moves no user-visible string.
- **`no bare source code reaches a tool result`** — the generalised guard, over
  both the `college_search` and `college_cost_profile` payloads, with a positive
  control so it cannot pass vacuously.

## Deliberately not fixed here

- **The guard is one shape written twice**, once per test file, and its
  allowlists have already diverged (the cost copy derives from
  `CostField.entries`; the search copy names its eight fields). `college` and
  `service` share no test source set, so unifying it means a `testFixtures`
  module — the `testFixtures(project(":appstore"))` precedent makes that cheap,
  and it is the obvious next move if a third tool needs the guard. Left out
  because a test-infrastructure refactor inside a fix run trades review quality
  for tidiness.
- **`CollegeSearchTool`'s input-schema description still spells
  `1 public,
  2 private nonprofit, 3 private for-profit` by hand.** That is the
  filter contract — codes there are correct — but the phrases could be built
  from `InstitutionControl.entries` so they cannot drift from the labels the
  results carry.
- **The guard inspects numeric values only**, so a code shipped as a JSON string
  (`put("control", code.toString())`) would still be certified clean. The
  property as stated in this RFC is unqualified; the implementation is not.
