---
name: spec-implementation-review
description:
  Reviews completed spec implementations to ensure they strictly modified the
  specified files, adhered to core defensive coding philosophies, and upheld
  general design principles.
---

# 🤖 Skill: Spec Implementation Review

This skill formalizes the process for reviewing implemented specifications. The
purpose is to enforce coding standards as specified in the `coding` and
`general-design` skills.

When a user asks you to act as a reviewer for a recently implemented spec step,
you MUST assume a STRICT and ADVERSARIAL posture. Your goal is not to be polite,
but to ruthlessly hunt for errors, edge-cases, and any deviations from the
project's coding and general design standards.

You MUST evaluate the code changes by focusing on these three core checks. While
you are encouraged to provide general feedback outside these bounds, these
criteria are mandatory to ensure strict adherence to the project's standards:

> **Important Constraint:** Explicitly IGNORE PII and security concerns at this
> point. Do not flag or evaluate code for security, privacy, or PII
> vulnerabilities during this review phase. Explicitly IGNORE minor (and some
> major) performance concerns at this stage. Do not flag them.

## 1. Files Modified Isolation Check

Validate that the code changes did not spill over into unrelated files.

- Compare exactly what was expected in the `Files Modified` section of the
  document against what files were _actually_ modified during the implementation
  phase.
- If an extraneous file was modified, clearly identify it as a failure.

## 2. Core Philosophy Check (`coding/SKILL.md`)

Evaluate the implementation against all 12 of the Core Philosophies explicitly
found in the `coding` skill.

**Important Rule for Reasoning:** You MUST include relevant code examples in
your reasoning to justify your verdict. However, the length of your code
examples MUST NOT exceed 20% of the total lines of code changed in the
implementation (e.g., if 10 lines were changed overall, your code snippet should
be 2 lines max).

1. **Accept Known, Reject All Else** (The Allowlist Principle)
2. **Handle All Cases** (Exhaustive Evaluation)
3. **No Sentinel Nulls or Empty Strings** (Explicit Initialization)
4. **Immutable State Returns** (Functional Patterns)
5. **Avoid Metasyntactic Naming** (Concrete Identifiers)
6. **Dynamic Variable Formatting** (Bracket Serialization)
7. **Extramarital Routing Defaults** (Explicit Interfaces Over Implicit Magic)
8. **Semantic Output Streams** (Error and Fatal Routing)
9. **Constructor Dependency Injection over Singletons** (Test Mockability)
10. **Do Not Leak Implementation Resources**
11. **Lossless Error Bubbling**
12. **DRY Structural Abstractions** (Avoid Boilerplate Duplication)

## 3. General Code and Design Check (`general-design/SKILL.md`)

Deliver a brief evaluation based on the core points of the general code design
skill:

- **Solve problems as generally as possible:** Evaluate if the solution takes an
  excessively specific approach to a problem that could be generalized via
  abstraction (e.g., standardizing on an Entity rather than specifically
  CRUD'ing a table).
- **Abstract Frameworks to Common Infrastructure:** Check if generic or
  logic-independent code was erroneously isolated in a specific module instead
  of placed into a common, sharable folder.

---

## 📋 Required Review Output Format

You MUST output your evaluation precisely using the markdown format provided
below. Use it as a templated report card.

```markdown
# 🔍 Implementation Review: [Spec Name / Step]

## 📝 1. Files Modified Isolation Check

- **Result:** ✅ PASS / ❌ FAIL
- **Discrepancies:** _(If FAIL, list the extraneous files that were modified
  unexpectedly or missing expected files. If PASS, state "None")._

## 🛡️ 2. Core Philosophy Check (`coding` Skill)

### 1. Allowlist Principle

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 2. Handle All Cases

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 3. No Sentinel Nulls

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 4. Immutable State Returns

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 5. Avoid Metasyntactic Naming

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 6. Dynamic Variable Formatting

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 7. Extramarital Defaults

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 8. Semantic Output Streams

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 9. Constructor DI

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 10. Don't Leak Resources

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 11. Lossless Error Bubbling

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

### 12. DRY Structural Abstractions

**Verdict:** (✅ PASS / ❌ FAIL / ⚪ N/A) **Reasoning:** [Provide detailed
explanation. MUST include code examples (max 20% of total LOC changed)]

## 🏗️ 3. General Code and Design Check (`general-design` Skill)

### Solve problems as generally as possible

**Evaluation:** [Provide observation] **Status:** ✅ PASS / ❌ FAIL

### Abstract Frameworks to Common Infrastructure

**Evaluation:** [Provide observation] **Status:** ✅ PASS / ❌ FAIL

## General Review and Feedback

[This section is for general feedback and observations that do not fit into the
above categories. It is not required to provide feedback in this section.]

## 🏁 Final Verdict

**Status:** 🟢 APPROVED / 🔴 REVISION REQUIRED

**Action Items for Implementor:** _(If REVISION REQUIRED, list clear, actionable
code changes they must make. For each action item, you MUST provide at least 2
distinct options for how to resolve it, and explicitly recommend one of the
options.)_
```
