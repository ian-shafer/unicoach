---
name: spec-implementation
description:
  Directs implementors in how to take a spec (e.g. specs/03-daemon-scripts.md)
  and implement it autonomously in a strict single-shot execution without making
  commits.
---

# Spec Implementation

Skill to direct implementors on how to take a spec design file (e.g.,
`specs/03-daemon-scripts.md`) and strictly implement it. See
`spec-design/SKILL.md` and `spec-design-review/SKILL.md` for context on how
these specs are authored and reviewed.

## Critical Behaviours

- **Architectural Decisions**: During implementation, if you encounter important
  architectural decisions that are deeply ambiguous or fundamentally missing
  from the spec (like whether a database table should be versioned), you MUST
  pause and ask the Architect. Always provide multiple valid options along with
  a clear recommendation.

## The Implementation Process

This skill operates **STRICTLY** in a single-shot, fully autonomous mode.

You MUST read through the spec's `Implementation Plan` section and execute ALL
steps in the implementation plan autonomously without stopping for human review
or approval between steps.

For the implementation, you must strictly follow this workflow:

1. **Implement All Steps**: Implement the required code for every step in the
   `Implementation Plan` in a single exhaustive run. Ensure you clean up any
   debugging artifacts or ad-hoc print statements.
2. **Execute Tests**: You must verify the implementation code compiles and all
   related test suites pass (e.g., by running `bin/test`). If tests fail,
   systematically debug and fix them.
3. **NO GIT COMMITS**: You **MUST NOT** make any git commits. Leave all
   modifications in the local working directory. The architect or external
   review skills will evaluate the raw uncommitted diffs.
4. **Demonstrate Success**: Upon completing the steps and verifying a green test
   suite, explicitly inform the architect that the single-shot implementation is
   complete and ready for their review or subsequent automated verification
   cycles.
