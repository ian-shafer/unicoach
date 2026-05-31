---
name: design-review-chain
description: >-
  A pre-configured macro skill that initiates a comprehensive, adversarial 
  design review chain for a specific target. It concurrently invokes background 
  subagents to execute all individual `design-review-*` micro-skills, compiling 
  their results into a single evaluation report.
---

# Design Review Chain

This skill acts as a specific execution macro. It ensures that when a user asks to "do a full design review" or "run design-review-chain", the orchestrator concurrently spawns background subagents to evaluate all design-review rules in parallel.

## Invocation Parameters

Invocation MUST define the following parameter:

- **Target**: The file, artifact, or component under review.

If the user does not provide a Target in their prompt, you MUST pause and ask
them to provide it before continuing.

## Execution

1. **Discover Review Skills**: Scan the list of all active skills available in your execution context (defined in your system prompt or `<skills>` block) that match the pattern `design-review-*` (excluding `design-review-chain` itself).
2.  **Concurrently Spawn Subagents**: Launch a background subagent for each
    discovered design-review skill using `invoke_subagent`. Specify the
    following for each subagent entry in the list:
    - **TypeName**: `self`
    -   **Role**: `[Skill Name] Reviewer` (e.g. `design-review-srp Reviewer`)
    -   **Prompt**: `"Run the [Skill Name] skill on target '[Target]' and return
        a detailed verdict. Your response must clearly state the Status (PASS or
        FAIL) and the detailed Evaluation."`
    - **Workspace**: `inherit`
3. **Await Reports**: Pause and wait for all spawned subagents to finish and report back. If some subagents respond earlier, keep track of their reports in your conversation memory until every subagent has returned a verdict.
4. **Compile Report**: Once all subagents have finished, compile their verdicts into a unified report using the markdown format below.

## Output Format

Accumulate all subagent verdicts in your context and compile a unified
evaluation report.

> **CRITICAL CONSTRAINT:** Do NOT output the raw report to the chat buffer. You
> MUST use your file editing tools to write the report to a persistent markdown
> file in your local scratch directory (e.g.
> `<appDataDir>/brain/<conversation-id>/scratch/design-review-report.md`). Only
> output a summary and the file path to the chat buffer so the master
> orchestrator can read it later.

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
