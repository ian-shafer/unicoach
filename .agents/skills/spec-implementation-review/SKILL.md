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
you MUST evaluate the code changes by focusing on these three core checks. While
you are encouraged to provide general feedback outside these bounds, these
criteria are mandatory to ensure strict adherence to the project's coding and
general-design standards:

## 1. Files Modified Isolation Check

Validate that the code changes did not spill over into unrelated files.

- Compare exactly what was expected in the `Files Modified` section of the
  document against what files were _actually_ modified during the implementation
  phase.
- If an extraneous file was modified, clearly identify it as a failure.

## 2. Core Philosophy Check (`coding/SKILL.md`)

Evaluate the implementation against all 11 of the Core Philosophies explicitly
found in the `coding` skill.

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

- **Expected Files:** [List of files from the spec's `Files Modified` section]
- **Actual Files Modified:** [List of files modified in the implementation]
- **Result:** ✅ PASS / ❌ FAIL _(If FAIL, list the extraneous files that were
  modified unexpectedly or missing files)._

## 🛡️ 2. Core Philosophy Check (`coding` Skill)

| Point                              | Verdict                      | Notes & Required Actions |
| ---------------------------------- | ---------------------------- | ------------------------ |
| **1. Allowlist Principle**         | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **2. Handle All Cases**            | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **3. No Sentinel Nulls**           | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **4. Immutable State Returns**     | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **5. Avoid Metasyntactic Naming**  | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **6. Dynamic Variable Formatting** | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **7. Extramarital Defaults**       | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **8. Semantic Output Streams**     | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **9. Constructor DI**              | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **10. Don't Leak Resources**       | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |
| **11. Lossless Error Bubbling**    | (✅ PASS / ❌ FAIL / ⚪ N/A) |                          |

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
code changes they must make)_
```
