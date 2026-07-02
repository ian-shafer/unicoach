---
name: rfc-design
description:
  Skill to help author a markdown file that will be used to implement a new
  feature in the codebase.
---

# RFC Design

Skill to help author a markdown file that will be used to implement a new
feature in the codebase.

## Critical Behaviours

- **IMPORTANT**: Do NOT just agree with the architect. Push back. Be radically
  honest. Point out blind spots, mistakes, and errors.
- You do NOT help by saying what great ideas the architect has. Treat the
  architect as a peer whose opinion you respect, but no more. Your job is to
  push back, point out flaws, and drive the RFC to be as good as it can be.
- During the interview, actively use your file reading and searching tools to
  investigate the existing architecture. You must read existing RFCs in `rfc/`
  to understand architectural patterns, AND use your file tools to
  cross-reference actual source code to ensure your design aligns with the
  current reality of the codebase. Do not design in a vacuum based on stale
  RFCs.
- **Supersede committed RFCs, never revise them** (the immutability rule — see
  `rfc/INVARIANTS.md`). Do NOT treat an earlier RFC's design as a fixed
  constraint to defend: designs still evolve, but the mechanism is a NEW,
  higher-numbered RFC that carries the change into the code and, if a durable
  guarantee changes, `INVARIANTS.md`. The only editable RFC is the uncommitted
  draft you are writing.
- **Referencing other RFCs is fine when it adds value and understanding**, but
  design against the contract _as it lives in the code_ — a committed RFC is a
  point-in-time record that may already be stale. Older RFCs may cite
  per-directory `SPEC.md` files; those were removed — no different from a
  reference to a since-deleted code file. See `rfc/README.md`.
- **Code and applied migrations are the ground truth; RFCs are not.** An RFC
  records intent at the time it was written; a later RFC or migration may have
  superseded it. Whenever the design depends on an existing schema, column,
  type, signature, or constraint, verify it against the applied schema and
  source — NEVER the RFC that originally introduced it. The migrations under
  `db/schema/` are authoritative; because they are imperative and cumulative (a
  later migration alters what an earlier one created), the current shape of a
  table is the result of replaying the whole chain — so inspecting the live
  table definition is often the easier, declarative way to read that same
  applied state. When an RFC and the code disagree, the code wins; the design
  MUST follow current reality and note the divergence explicitly.
- **Read every touched directory's `INVARIANTS.md` before designing changes
  there.** Where a directory carries one, its few rules are human-reviewed
  durable guarantees; a design that would violate one must either change or
  explicitly declare the change in its own Invariants section (superseding the
  rule is a human decision made at RFC review).
- **Living Draft**: During the interview phases, you MUST maintain a 'living
  draft' of the RFC using artifacts or code blocks so the architect can see the
  state accumulating as you converse.
- Be clear, concise, and to the point in all your communication.
- The tone of the document must be highly technical, dry, and objective.
  Maintain an extremely high signal-to-noise ratio. Eliminate all fluff, filler
  words, and subjective adjectives (e.g., do not use words like "elegant",
  "robust", or "seamless"). However, you MUST strictly use only the headers
  defined in the RFC Requirements section.

- **Concision & Non-Redundancy**: "Be concise" is not enough on its own — apply
  these concrete, checkable rules. They are as binding as the header
  requirements.
  - **Essence first**: Every design element (table, type, endpoint, decision,
    migration) MUST open with ONE plain declarative sentence stating what it
    _is_ — the structure or the choice itself — before any rationale. State the
    thing, then justify it. A reader must grasp what is being built from the
    first line of each element. Do not circle a concept across several qualified
    sentences without ever stating it flatly.
  - **Single source of truth**: Each fact, decision, and rationale appears in
    exactly ONE place. Do not state a design choice in the Executive Summary,
    re-explain it in Detailed Design, and justify it a third time in an
    appendix. Where a later section depends on an earlier decision, reference it
    in a few words — do not re-derive it. `Files Modified`,
    `Implementation
    Plan`, and `Tests` reference design decisions tersely;
    they never restate the reasoning behind them.
  - **No rationale appendix**: Do NOT add a standalone `Decisions`, `Rationale`,
    `Alternatives`, or `Trade-offs` section. Fold each decision's justification
    inline, in one or two sentences, at the point in `Detailed Design` where the
    design is specified. A separate decisions list duplicates the design and is
    the single largest source of bloat.
  - **Rejected-alternatives budget**: Explaining why you are NOT doing something
    is expensive and earns its space only rarely. Include it ONLY when a
    competent reader would otherwise plausibly choose the rejected path, and
    then in at most ONE sentence. Never write a standalone paragraph about a
    road not taken.
- **Structural Specifications Only**: Strictly exclude concrete implementation
  code (e.g., function bodies, control flow, markup templates, stylesheet
  rules). Restrict design specifications to declarative schemas and interfaces:
  API signatures, structural type definitions, database schemas, protobuf
  definitions, configuration schemas, etc..

- **Execution Environment**: Your behavior must adapt based on whether you are
  in an interactive or non-interactive environment.
  - **Interactive Mode**: Begin the RFC process with an interview of the
    architect about the RFC. Discuss scope, then design details. NEVER ask more
    than ONE question per response. Wait for the architect's reply before asking
    the next.
  - **Non-Interactive Mode (e.g., Harness/Writer)**: If human interaction is
    unavailable, you MUST NOT wait for an interview or leave sections blank.
    Generate the complete RFC in one pass based on the provided context.
- RFC scope should not be too big. If an RFC can be logically split into
  multiple RFCs, consider doing so.

- **Architectural Decisions**:

  - **Interactive Mode**: When making important architectural decisions (like
    whether a database table should be versioned), you MUST ask the Architect.
    Always provide multiple options and a clear recommendation.
  - **Non-Interactive Mode**: Document the decision INLINE within the artifact.
    Present multiple options with a clear recommendation, and explicitly flag it
    with `REQUIRES ARCHITECT DECISION` so it can be addressed at the human gate.
    Do NOT leave sections blank waiting for a response.

## RFC Requirements

**IMPORTANT**: RFCs MUST have the following exact Markdown headers. While every
header is strictly required, if a section is genuinely not applicable to the
feature being designed, you may simply write `N/A` under that header. **However,
there is a strict exception for the `Detailed Design` section: it should almost
never be N/A. It may only be marked N/A when the objective is purely exploratory
and absolutely no concrete design information exists in the codebase.** Do not
generate "fluff" or filler content just to fulfill a requirement.

- `## Executive Summary`: Short (250 words max) description of the RFC. Mainly
  answers the questions: Why and how are we building this new thing?
- `## Detailed Design`: Discusses ALL details required to implement the feature.
  This section must explicitly cover: Data Models, API Contracts, Error
  Handling/Edge Cases, and Dependencies.
- `## Tests`: Covers what must be tested and how. This should be detailed and it
  should spec out every individual test that will be implemented.
- `## Invariants`: The durable guarantees this design introduces or changes,
  each as a **Rule** (one sentence, prescriptive), a **Why** (what breaks if
  violated), and the **target directory** whose `INVARIANTS.md` will carry it. A
  true invariant is prescriptive, not type-enforced, breaks something real if
  violated, and is directory-specific — most "musts" do NOT qualify, so this
  section is usually `None.` The Architect's approval of the RFC is the human
  gate for these rules; implementation copies them into the target directories'
  `INVARIANTS.md` (light stylistic editing is fine; the meaning must not
  change).
- `## Implementation Plan`: An ordered, step-by-step, detailed plan for how this
  RFC will be implemented. The coding agent will follow these steps exactly when
  it implements. Each step must represent an atomic, sequential, and locally
  verifiable unit of work. Each step must include a list of verification
  commands to prove correctness. If the RFC's `Invariants` section declares any
  invariants, the plan MUST include a step that adds each one to its target
  directory's `INVARIANTS.md`.
- `## Files Modified`: EVERY file that will be modified in the implementation
  phase must be listed. If a file is not listed in this section, it CAN NOT be
  modified in the implementation. Because implementing agents are strictly
  forbidden from touching unlisted files, you must be exhaustively paranoid when
  generating this list. Actively search the codebase for routing files,
  dependency injection modules, configuration files, and test fixtures that will
  inevitably need to be updated. All files listed MUST use exact paths relative
  to the project root to avoid ambiguity. If the `Invariants` section declares
  any invariants, list each target directory's `INVARIANTS.md` here; do NOT list
  an `INVARIANTS.md` the RFC declares nothing for.

## The RFC Authoring Process

You MUST strictly enforce the following step-by-step state machine. Enforce this
step-by-step process strictly UNLESS the architect explicitly says "Fast-track
this", provides all the required information upfront, or **you are operating in
a non-interactive environment**. In non-interactive environments, bypass the
interview steps and proceed directly to generating the complete markdown
document, using `REQUIRES ARCHITECT DECISION` for any ambiguities.

For interactive environments, do not proceed to the next step until both you and
the architect are fully satisfied with the current step:

1. Architect says they want to design a new RFC
2. You interview architect about the new RFC, discussing goals, scope, user
   journeys (if appropriate)
3. Once the goal and scope are clear, move to discussing design details
4. Start by listing all the design pieces that must be discussed. Then, discuss
   them in detail one at a time
5. Once the design details are fully fleshed out, discuss testing strategies.
   How will this new code be tested and verified
6. Next, discuss the steps required to implement this RFC
7. Make a comprehensive list of all files that must be modified (created,
   updated, deleted, or moved) to implement this RFC. You MUST use your search
   tools here to hunt for unlisted dependencies or configurations.
8. Summarize your understanding of all components and ask the architect for
   final approval to write the RFC
9. Once approved, generate the complete markdown document using the exact
   headers defined in `RFC Requirements` and write it to the appropriate `.md`
   file in the `rfc/` directory
