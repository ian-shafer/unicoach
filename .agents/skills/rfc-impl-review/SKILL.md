---
name: rfc-impl-review
description: >-
  Reviews completed RFC implementations to ensure they strictly modified the
  specified files, implemented the exact scope without feature creep, and adhered 
  to core coding and design philosophies. Acts as a master orchestrator delegating
  to code-review-chain and design-review-chain.
---

# 🤖 Skill: RFC Implementation Review

This skill formalizes the process for reviewing implemented RFCs. 
You act as a STRICT and ADVERSARIAL master orchestrator. Your goal is not to be polite, but to ruthlessly hunt for errors, scope creep, and deviations from the project's standards.

> **Important Constraint:** Explicitly IGNORE PII and security concerns at this point. Do not flag or evaluate code for security, privacy, or PII vulnerabilities during this review phase. Explicitly IGNORE minor (and some major) performance concerns at this stage. Do not flag them.

## Execution Workflow

You MUST execute the review by following these exact phases sequentially:

### Phase 1. Files Modified Isolation Check

Validate that the code changes did not spill over into unrelated files.
- Compare exactly what was expected in the `Files Modified` section of the RFC against what files were *actually* modified.
- If an extraneous file was modified, clearly identify it as an isolation failure.

### Phase 2. Implementation Scope Check

Validate the scope boundaries of the implementation:
- **Missing Features:** Verify that *everything* explicitly requested in the RFC was actually implemented.
- **Feature Creep:** Verify that *no extra functionality* was added. The implementor must not add speculative abstractions or features not mentioned in the RFC.

### Phase 3. Chain Delegation

You MUST delegate the deep structural and code reviews to the dedicated macro chains. 
Execute the following chains on the target implementation files:
1. `design-review-chain`
2. `code-review-chain`

*(Note: Instruct these chains to write their full reports to persistent markdown files in your scratch directory. You must use your file reading tools to ingest these reports once the chains complete.)*

### Phase 4. Aggregation and Final Verdict

Once Phase 1-3 are complete, aggregate all findings into a final master report.

## 📋 Required Review Output Format

You MUST output your final master evaluation precisely using the markdown format provided below.

```markdown
# 🔍 Master Implementation Review: [RFC Name / Step]

## 📝 1. Scope & Boundary Checks

**Files Modified Isolation Check:**
- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list the extraneous or missing files. If PASS, state "None")_

**Implementation Scope Check:**
- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list missing features or feature creep. If PASS, state "None")_

## 🏗️ 2. Design Review Summary

_(Summarize the critical failures and observations from the `design-review-report.md`. Do not list passing checks, only actionable violations.)_

## 🛡️ 3. Code Review Summary

_(Summarize the critical failures, missing abstractions, and coding rule violations from the `code-review-report.md`. Do not list passing checks, only actionable violations.)_

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable code changes they must make based on all phases. For each action item, you MUST provide at least 2 distinct options for how to resolve it, and explicitly recommend one of the options.)_
```
