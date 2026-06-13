---
name: rfc-impl
description:
  Directs implementors in how to take an RFC (e.g. rfc/03-daemon-scripts.md)
  and implement it autonomously in a strict single-shot execution without making
  commits.
---

# RFC Implementation

Skill to direct implementors on how to take an RFC design file (e.g.,
`rfc/03-daemon-scripts.md`) and strictly implement it. See
`rfc-design/SKILL.md` and `rfc-design-review/SKILL.md` for context on how
these RFCs are authored and reviewed.

## Critical Behaviours

- **Architectural Decisions**: During implementation, if you encounter important
  architectural decisions that are deeply ambiguous or fundamentally missing
  from the RFC (like whether a database table should be versioned), you MUST
  pause and ask the Architect. Always provide multiple valid options along with
  a clear recommendation.

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
3. **DO NOT TOUCH SPECS**: You are strictly forbidden from modifying any `SPEC.md` 
   files during the implementation phase. Even if the RFC explicitly instructs you 
   to update a `SPEC.md`, you must ignore that instruction. Spec synchronization 
   is handled by a separate pipeline phase.
3a. **DO NOT EDIT THE RFC**: The RFC you are implementing is an immutable record —
   you MUST NOT modify it (or any other RFC under `rfc/`). Implement against it;
   never rewrite it. If the RFC is wrong or ambiguous, pause and ask the architect
   (per Critical Behaviours) rather than editing the file. Changes to a decision
   are made in a new superseding RFC, not by editing this one. See `rfc/README.md`.
4. **NO GIT COMMITS**: You **MUST NOT** make any git commits. Leave all
   modifications in the local working directory. The architect or external
   review skills will evaluate the raw uncommitted diffs.
5. **Demonstrate Success**: Upon completing the steps and verifying a green test
   suite, explicitly inform the architect that the single-shot implementation is
   complete and ready for their review or subsequent automated verification
   cycles.
