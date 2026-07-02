---
name: rfc-impl-review
description: >-
  Reviews completed RFC implementations to ensure they strictly modified the
  specified files, implemented the exact scope without feature creep, and adhered
  to core coding and design philosophies. Acts as a master orchestrator delegating
  to code-review-chain and design-review-chain.
---

# 🤖 Skill: RFC Implementation Review

This skill formalizes the process for reviewing implemented RFCs. You act as a
STRICT and ADVERSARIAL master orchestrator. Your goal is not to be polite, but
to ruthlessly hunt for errors, scope creep, and deviations from the project's
standards.

**Role — orchestrator (+ checker).** Wears the **orchestrator** hat from
`iterative-work` when fanning out to the review chains (each gets a scratch
sub-dir; reconstruct from `leaves/`), and a worker-ish **checker** hat for its
own Phase 1/2 scope and test checks. The general rules live there; below is the
review-specific workflow.

> **Important Constraint:** Explicitly IGNORE PII and security concerns at this
> point. Do not flag or evaluate code for security, privacy, or PII
> vulnerabilities during this review phase. Explicitly IGNORE minor (and some
> major) performance concerns at this stage. Do not flag them.

## Two stages, split by context-weight

The review is composed of two stages with fixed ownership, so an orchestrator
can get **both** context isolation **and** depth-1 leaves:

- **Stage A — Scope & Context Prep (delegatable).** Phases 1, 2, 2b
  (files-modified isolation, scope/feature-creep check, test-completeness +
  guard-branch exercise) **plus** construction of the shared review-context file
  `<scratch>/review-context.md` (the `<base>...HEAD` diff plus each changed
  file's contents, write-once / skip-if-present). Stage A reads every changed
  file anyway for the scope checks, so it builds the context file with no second
  read. **Stage A spawns no subagents.** It may therefore be delegated to a
  depth-1 background agent (e.g. the pipeline's `[rfc-impl-review-prep]` agent),
  keeping its heavy reads off the orchestrator.
- **Stage B — Lens Fan-out & Aggregation (top-level-owned).** Phase 3 (the
  `design-review-chain` and `code-review-chain` leaf fan-outs) plus Phase 4 (the
  master verdict). Phase 3 spawns leaves, so it **MUST run in the top-level
  session** (see the Depth-1 Fan-out Invariant below). Stage B is context-light:
  the leaves `Read` the prebuilt `review-context.md` and write verdicts to
  scratch, so the session holds only lens names and scratch paths.

Run **standalone** (a developer invokes `/rfc-impl-review` inline at the top
level), one session runs both stages back-to-back and the leaves are already
depth-1 — no regression; Stage A simply builds `review-context.md` in its own
Phase 3 step (no separate prep agent) and Phase 3 passes that file to the
chains. The split matters only when an orchestrator wants Stage A's context
isolation **and** depth-1 leaves; the `rfc-pipeline` composes the two stages to
get both.

### Depth-1 Fan-out Invariant (normative)

The Phase 3 chains (and the `design-review-chain` / `code-review-chain` they
drive) spawn one leaf reviewer per micro-skill. That fan-out **MUST execute in
the top-level session**, so each leaf is a **depth-1** child. It **MUST NOT** be
invoked from inside a background subagent (an `Agent`-tool task), because that
makes the leaves **grandchildren** of the top-level session, which the Claude
Code harness task layer reaps unreliably (a finished leaf can stay `running`
indefinitely). When this review runs under the `rfc-pipeline`, **only Stage A is
delegated to a background agent; Stage B's fan-out is run inline by the pipeline
orchestrator** so the leaves stay depth-1. Standalone, both stages run inline at
depth-0 and the leaves are depth-1 automatically.

## Execution Workflow

You MUST execute the review by following these exact phases sequentially:

### Phase 1. Files Modified Isolation Check

Validate that the code changes did not spill over into unrelated files.

- Establish the **changed-file set** to review: the files that differ between
  the base revision and the implementation tip, via
  `git diff --name-only <base>...HEAD`. `<base>` is the base revision the caller
  supplies; absent one, default to `main` (the `...` merge-base form, so the set
  is unaffected by `<base>` advancing after the branch point). If the
  implementation is uncommitted in the working tree (so `<base>...HEAD` is
  empty), fall back to `git status --porcelain`. Carry this set forward as the
  review target for Phase 3.
- Compare that changed-file set against the `Files Modified` section of the RFC:
  any file in one but not the other is a discrepancy to report.
- If an extraneous file was modified, clearly identify it as an isolation
  failure.
- **Invariants Landing Check:** If the RFC carries an `Invariants` section, each
  declared invariant must appear (Rule + Why; wording may be lightly edited to
  match the file's style, meaning unchanged) in its target directory's
  `INVARIANTS.md` — a missing landing is a scope failure. The converse is
  enforced too: any `INVARIANTS.md` change with no matching RFC declaration, and
  any created `SPEC.md` (this codebase does not use them), is an automatic
  isolation failure the implementor must revert.

### Phase 2. Implementation Scope Check

Validate the scope boundaries of the implementation:

- **Missing Features:** Verify that _everything_ explicitly requested in the RFC
  was actually implemented.
- **Verbatim Detailed Design Adherence:** Verify that every declaration defined
  in `## Detailed Design` is found verbatim in the implementation. Declarations
  include files, classes, interfaces, modules, functions, methods, schemas, code
  snippets, and variable types. Any missing, altered, or omitted declaration is
  an automatic scope failure and a critical issue that must trigger a revision
  required verdict.
- **Feature Creep:** Verify that _no extra functionality_ was added. The
  implementor must not add speculative abstractions or features not mentioned in
  the RFC.
  - **Bidirectional Traceability:** Every new or modified class, struct,
    interface, database schema column, helper function, or public/private API
    method in the implementation files MUST map directly to a mandate,
    declaration, or requirement defined in the target RFC. If any structural
    code change cannot be traced back to the RFC, it is an automatic feature
    creep violation and a scope failure.

### Phase 2b. Test Verification Completeness Check

Validate the test coverage of the implementation against the RFC's verification
requirements: - **RFC Test Map:** Read the `## Tests` section of the target RFC.
Extract all explicitly defined test cases, requirements, and assert scenarios. -
**Test-Completeness Analysis:** Read the target test suites, unit test,
integration test, or verification files modified or added in the working
repository workspace. Map them directly against the RFC's test list. - **Failure
Trigger:** If _any_ test case or requirement described in the RFC's test section
is not implemented in the target test files, this is an automatic check failure.
You MUST mark the final verdict as `🔴 REVISION REQUIRED` and specify the
missing test implementations as critical action items. Do not approve the
implementation until the tests are implemented and passing.

**Guard Branch Exercise:** For every "must fatal / must reject / must refuse on
precondition X" behaviour in the RFC (port guards, auth gates, malformed-input
rejections, defensive fatals), you MUST independently **trigger precondition X**
yourself (bind the port, send the malformed body, supply the bad credential) and
confirm the refusal fires — do NOT trust a happy-path-only verification, since
such a guard is silent on every run where its precondition is absent. Treat any
guard that was only happy-path-tested as an unverified finding and mark the
verdict `🔴 REVISION REQUIRED`.

### Phase 2c. Build the shared review-context file (end of Stage A)

This is the final Stage A step. You have already read every changed file for the
scope checks above, so materialize the shared review context **once** to
`<scratch>/review-context.md` (write-once, skip-if-present): the `<base>...HEAD`
diff (the `...` merge-base form; fall back to `git diff <base>` when the
implementation is uncommitted) plus the full contents of each changed
**non-test** file in the Phase 1 set, inlined whole and labelled by path. **Do
NOT inline test-file bodies** — their changes are already in the diff and their
bodies are the bulk of the context; instead **name** each changed test file (and
any non-test file too large to inline) in a "named — `Read` on demand" list for
leaves to `Read` directly. (A test file is one under a `test/` / `tests/`
directory or whose name matches `*Test` / `*Tests` / `*Spec` / `*_test` /
`*.test.*` — e.g. `src/test/**` or `*Test.kt`.) Phase 3 passes this file to each
chain as its **Review Context File** so the chains skip rebuilding it. When
Stage A is delegated (the pipeline's `[rfc-impl-review-prep]` agent), this file
is the prep's durable handoff to the top-level fan-out.

### Phase 3. Chain Delegation (Stage B — top-level-owned fan-out)

This phase spawns leaves and so **MUST run inline in the top-level session** per
the Depth-1 Fan-out Invariant above — never from inside a background subagent.
You MUST delegate the deep structural and code reviews to the dedicated macro
chains. Pass the **changed-file set established in Phase 1** to each chain as
its **Target** — the explicit file set, not a directory, component, or single
artifact. Also pass the **`<base>` from Phase 1** as each chain's **Base
Revision**, and pass the **`<scratch>/review-context.md` built in Phase 2c** as
each chain's **Review Context File** so the chains skip rebuilding the context
and point their leaves at that file (which each leaf `Read`s):

1. `design-review-chain`
2. `code-review-chain`

_(Note: When the `rfc-pipeline` orchestrator supplied a run-scratch sub-path for
this review pass, hand each chain its own sub-directory under it — e.g.
`<scratch>/design/` and `<scratch>/code/` — as its **Scratch Dir**. Each chain's
leaf reviewers `Read` the prebuilt `review-context.md` and write one verdict
file per rule under `<scratch>/<chain>/leaves/` the instant they finish, and the
chain compiles `report.md` by reading that directory. Ingest the chains'
findings from those files. If a chain's compile is interrupted, reconstruct its
verdict from the `leaves/` directory directly rather than re-running the leaves
— completed leaf work is never lost or repeated.)_

**RFC Scope Boundary Enforcement:** Before summarizing or incorporating findings
from these chains, you MUST compare them with the target RFC. If a
recommendation from a review chain proposes changes that deviate from or exceed
the scope defined in the RFC, you MUST NOT recommend it. Keep the finding as
informational only, for future consideration, and do not include it as an
actionable item.

**Correctness carve-out:** Data-integrity and lossy-error findings — dropped or
flattened failure context, swallowed exceptions, a structured error discarded at
an emission site (throw, return, or log) — are **correctness defects, not scope
creep**. They remain actionable even when the RFC did not explicitly enumerate
error handling, and MUST NOT be demoted to informational on scope-boundary
grounds. The prohibition above targets speculative abstractions and feature
additions, not the loss of root-cause data.

### Phase 4. Aggregation and Final Verdict

Once Phase 1-3 are complete, aggregate all findings into a final master report.

## 📋 Required Review Output Format

You MUST output your final master evaluation precisely using the markdown format
provided below.

```markdown
# 🔍 Master Implementation Review: [RFC Name / Step]

## 📝 1. Scope & Boundary Checks

**Files Modified Isolation Check:**

- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list the extraneous or missing files. If PASS,
  state "None")_

**Implementation Scope Check:**

- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list missing features or feature creep. If PASS,
  state "None")_

**Test Verification Completeness Check:**

- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list the specific tests defined in the RFC that
  were missing from the test suites. If PASS, state "None")_

## 🏗️ 2. Design Review Summary

_(Summarize the critical failures and observations from the
`design-review-report.md`. Do not list passing checks, only actionable
violations.)_

## 🛡️ 3. Code Review Summary

_(Summarize the critical failures, missing abstractions, and coding rule
violations from the `code-review-report.md`. Do not list passing checks, only
actionable violations.)_

## 💡 4. Informational Findings & Out-of-Scope Suggestions

_(List any elegant design suggestions, refactoring ideas, or clean-up proposed
by the review chains that deviate from or exceed the explicit scope of the RFC.
State clearly that these are NOT action items for this implementation loop, but
are recorded for future consideration.)_

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable
code changes they must make based on all phases. For each action item, you MUST
provide at least 2 distinct options for how to resolve it, and explicitly
recommend one of the options. Do NOT include any out-of-scope,
informational-only suggestions listed in Section 4 as action items.)_
```
