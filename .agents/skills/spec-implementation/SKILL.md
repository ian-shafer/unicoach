---
name: spec-implementation
description:
  Directs implementors in how to take a spec (e.g. specs/03-daemon-scripts.md)
  and implement it step by step with strict architect review.
---

# Spec Implementation

Skill to direct implementors on how to take a spec design file (e.g.,
`specs/03-daemon-scripts.md`) and strictly implement it. See
`spec-design/SKILL.md` and `spec-design-review/SKILL.md` for context on how
these specs are authored and reviewed.

## The Implementation Process

If the user explicitly requests a **"single shot implementation"**, you must
execute ALL steps in the implementation plan autonomously without stopping for
human review or approval between steps. You must still implement, test, and
commit each step individually (using the commit message format in step 7) before
proceeding to the next step.

Otherwise, you MUST read through the spec's `Implementation Plan` section and
execute it step-by-step. For each step in the plan, you must strictly follow the
workflow below. **Do not move to the next step until the current step is fully
completed, approved, and committed.**

1. **Acknowledge Steps** _(Skip if single-shot)_: Before beginning
   implementation, explicitly list out all the steps in the Implementation Plan
   to the architect, and clearly state which step is currently being worked on.
2. **Implement & Test**: Implement the required code for the current step,
   including all necessary test code.
3. **Demonstrate Success**: Once the implementation is complete and the test
   code passes, show the passing tests to the architect. While you can simply
   state that the tests pass, it is highly preferable to show the actual output
   from the test programs.
4. **Architect Review** _(Skip if single-shot)_: Allow the architect to review
   the code changes you have made.
5. **Iterate** _(Skip if single-shot)_: Iterate with the architect on any
   desired code changes, refinements, or fixes.
6. **Await LGTM** _(Skip if single-shot)_: Once all code changes are implemented
   and tests pass, wait for an explicit "LGTM" (Looks Good To Me) approval from
   the architect. Do not proceed until this is received.
7. **Commit Code** _(Skip if single-shot)_: Before committing, ensure you clean up any debugging artifacts, files, or code (e.g. ad-hoc print statements or test logs) generated during the implementation. Commit the code. The commit message
   **must** start with the spec file basename and the specific implementation
   plan step wrapped in square brackets (e.g., `[07-users-dao:1]`).
8. **Next Step**: Move on to the next step in the Implementation Plan. If there
   are no more steps remaining in the spec, tell the architect that they're
   awesome and ask what they want to do next.
