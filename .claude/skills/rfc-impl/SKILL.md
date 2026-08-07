---
name: rfc-impl
description:
  Directs implementors in how to take an RFC (e.g. rfc/03-daemon-scripts.md)
  and implement it autonomously in a strict single-shot execution without making
  commits.
---

# RFC Implementation

Skill to direct implementors on how to take an RFC design file (e.g.,
`rfc/03-daemon-scripts.md`) and strictly implement it.

## Critical Behaviours

- **Architectural Decisions**: During implementation, if you encounter important
  architectural decisions that are deeply ambiguous or fundamentally missing
  from the RFC (like whether a database table should be versioned), you MUST
  pause and ask the Architect. Always provide multiple valid options along with
  a clear recommendation.
- **Never create a `SPEC.md`** — this codebase does not use them.

## The Implementation Process

This skill operates **STRICTLY** in a single-shot, fully autonomous mode.

You MUST read through the RFC's `Implementation Plan` section and execute ALL
steps in the implementation plan autonomously without stopping for human review
or approval between steps.

For the implementation, you must strictly follow this workflow:

1. **Implement All Steps**: Implement the required code for every step in the
   `Implementation Plan` in a single exhaustive run. Ensure you clean up any
   debugging artifacts or ad-hoc print statements.
2. **Execute Tests**: You must verify the implementation code compiles and all
   related test suites pass (e.g., by running `bin/test`). If tests fail,
   systematically debug and fix them.
3. **Trigger Every Guard**: When the RFC's acceptance criteria include any "must
   fatal / must reject / must refuse on precondition X" behaviour (port guards,
   auth gates, malformed-input rejections, defensive fatals), a green happy-path
   run is necessary but **NOT sufficient** — such a guard is silent whenever its
   precondition is absent, so a broken one passes every normal run. You MUST
   verify each guard by actually **triggering precondition X** (bind the port,
   send the malformed body, supply the bad credential) and confirming the
   refusal actually fires.
4. **Do Not Edit the RFC**: Never edit the RFC being implemented (or any other
   RFC). If you have a question about the content of the RFC, pause and ask the
   architect.
5. **No Git Commits**: You **MUST NOT** make any git commits. Leave all
   modifications in the local working directory. The architect or external
   review skills will evaluate the raw uncommitted diffs.
6. **Demonstrate Success**: Upon completing the steps and verifying a green test
   suite, explicitly inform the architect that the single-shot implementation is
   complete and ready for their review or subsequent automated verification
   cycles.
