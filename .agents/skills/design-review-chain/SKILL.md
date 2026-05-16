---
name: design-review-chain
description: >-
  A pre-configured macro skill that initiates a comprehensive, adversarial 
  design review chain for a specific target. It delegates execution to the 
  `skill-loop` primitive, chaining all individual `design-review-*` micro-skills 
  for a targeted, single-pass evaluation.
---

# Design Review Chain

This skill acts as a specific execution macro. 
It ensures that when a user asks to "do a full design review" or "run design-review-chain",
the underlying generic state machine is invoked with all the micro-review personas sequentially for a single pass.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target**: The file, artifact, or component under review.

If the user does not provide a Target in their prompt, you MUST pause and ask them to provide it before continuing.

## Execution

Once the Target is known, you MUST immediately delegate execution to `skill-loop/SKILL.md` using the following parameters:

- **Target**: <The Target provided by the user>
- **Iterations**: 1
- **Skills Chain**: All skills located in `.agents/skills/` that match the pattern `design-review-*` (excluding `design-review-chain` itself). You MUST use your directory listing tools to discover all matching skills at runtime before beginning the chain.

Follow the initialization and execution state machine instructions defined in `skill-loop/SKILL.md` exactly.

## Output Format

As the orchestrator delegates to each persona, you MUST accumulate the verdicts in your context.
Upon completion of the chain, you MUST compile a unified evaluation report using the markdown format provided below.

> **CRITICAL CONSTRAINT:** Do NOT output the raw report to the chat buffer. You MUST use your file editing tools to write the report to a persistent markdown file in your local scratch directory (e.g. `<appDataDir>/brain/<conversation-id>/scratch/design-review-report.md`). Only output a summary and the file path to the chat buffer so the master orchestrator can read it later.

```markdown
# 🔍 Comprehensive Design Review: [Target]

## 🏗️ 1. Design Review Check

### [Insert Skill Name (e.g., design-review-srp)]
**Evaluation:** [Provide observation] **Status:** ✅ PASS / ❌ FAIL

## General Review and Feedback

[This section is for general feedback and observations that do not fit into the above categories. It is not required to provide feedback in this section.]

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable code changes they must make. For each action item, you MUST provide at least 2 distinct options for how to resolve it, and explicitly recommend one of the options.)_
```
