---
name: spec-implementation
description: Directs implementors in how to take a spec (e.g. specs/03-daemon-scripts.md) and implement it step by step with strict architect review.
---

# Spec Implementation

Skill to direct implementors on how to take a spec design file (e.g., `specs/03-daemon-scripts.md`) and strictly implement it. See `spec-design/SKILL.md` and `spec-design-review/SKILL.md` for context on how these specs are authored and reviewed.

## The Implementation Process

You MUST read through the spec's `Implementation Plan` section and execute it step-by-step. For each step in the plan, you must strictly follow the workflow below. **Do not move to the next step until the current step is fully completed, approved, and committed.**

1. **Implement & Test**: Implement the required code for the current step, including all necessary test code.
2. **Demonstrate Success**: Once the implementation is complete and the test code passes, show the passing tests to the architect. While you can simply state that the tests pass, it is highly preferable to show the actual output from the test programs.
3. **Architect Review**: Allow the architect to review the code changes you have made.
4. **Iterate**: Iterate with the architect on any desired code changes, refinements, or fixes.
5. **Await LGTM**: Once all code changes are implemented and tests pass, wait for an explicit "LGTM" (Looks Good To Me) approval from the architect. Do not proceed until this is received.
6. **Propose Commit**: Once the LGTM is received for the code, propose a commit message by writing it to a temporary file (e.g., in `/tmp/`) and allow the architect to review and edit it if desired. The commit message **must** include the spec file name and the specific implementation plan step being completed.
7. **Commit Code**: Once the architect gives the LGTM on the commit message, commit the code using the approved message.
8. **Next Step**: Move on to the next step in the Implementation Plan. If there are no more steps remaining in the spec, tell the architect that they're awesome and ask what they want to do next.
