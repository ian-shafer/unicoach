---
name: spec-auto-iteration
description: >-
  Automates the back-and-forth process of drafting and reviewing a new feature
  specification. The agent plays both the 'Designer' and the 'Reviewer',
  enforcing an adversarial critique to prevent confirmation bias, and using file
  artifacts to keep context overhead low.
---

# Spec Auto-Iteration Workflow

This skill instructs the agent to autonomously generate (if not already
provided), review, and iterate a specification by alternating between an
'Architect/Designer' persona and a 'Reviewer' persona.

## Critical Constraints

- **Artifacts Only**: To prevent context window bloat, NEVER print the contents
  of the spec in the chat. You must ONLY write the spec directly to a markdown
  file artifact in the `specs/` directory (e.g., `specs/draft-spec.md`). Chat
  output must only contain the critique and a summary of updates.
- **Strict Adversarial Rules**: The Reviewer persona MUST act as a skeptical,
  highly critical Principal Engineer. You are strictly forbidden from approving
  the spec passively or saying "looks good" on the first two iterations.
- **Quota Enforcement**: The Reviewer MUST find at least three (3) distinct and
  technical flaws, architectural blind spots, or missing edge cases during each
  internal review phase.

## The Iteration Loop

When the user invokes this skill with a feature request, execute the following
state machine for exactly 3 rounds (unless the user specifies otherwise):

### Round 1: Initial Draft

1. **Act as Designer**: Adopt the mindset from `spec-design/SKILL.md`.
2. Based on the user's prompt (or a rough draft markdown file provided by the
   user), draft the initial V1 spec. Ensure it has the required sections:
   Executive Summary, Detailed Design, Tests, Implementation Plan, Files
   Modified.
3. Silently save the draft to an artifact (`specs/<feature-name>.md`). Do not
   output the draft to the chat.

### Round 2 & 3: The Adversarial Loop

1. **Switch to Reviewer**: Adopting the mindset from
   `spec-design-review/SKILL.md`, read the newly saved artifact.
2. In the chat, output your brutal critique as the Reviewer. You must list at
   least 3 concrete, technical problems (e.g., missing API pagination, missing
   database indexes, unhandled race conditions, vague test descriptions). For each problem, you MUST provide at least 2 distinct options for how to resolve it, and explicitly recommend one.
3. **Switch back to Designer**: In the chat, respond to the critique. State
   explicitly which points you accept and how you will fix them, and reject any
   feedback you think is incorrect (stating why in the spec artifact).
4. Update the markdown artifact with the fixes. Let the user know the artifact
   has been updated.

### Conclusion

Once the 3 rounds are complete, summarize the changes made to the spec (if an
initial rough draft was provided) or the final state of the spec and ask the
user if they want to review the artifact manually, or if they want to force
another adversarial round.
