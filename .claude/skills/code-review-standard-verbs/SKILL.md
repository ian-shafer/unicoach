---
name: code-review-standard-verbs
description: Reviews code to ensure function names use standard verbs and limit themselves to domain nouns.
implementation_summary: >
  **Use Standard Verbs**: Function names should be limited to domain nouns (e.g., "user", "session") and standard, recognizable verbs like create, decrypt, delete, encrypt, execute, generate, get, handle, list, map, set, or update. Avoid creative or ambiguous verbs like "classify", "manage", or "process".
---

# 🔍 Code Review: Use Standard Verbs

**Loosened 2026-09-20.** The 2026-09-19 review-cost measurement found these five
readability lenses produced 89 findings across two frozen diffs, and that tier 3
declined 54% of what it was told. The criteria below are now clear-cut cases
only. What still fires is a real violation, keeps its normal grade, and is fixed
before land.

You are a code reviewer focusing strictly on clear violations of the following
principle. Do not review for other concerns outside this scope, and do not
report a borderline case.

## 📜 Review Criteria

- Function names should be limited to domain nouns (e.g., "user", "session",
  "job") and standard verbs.
- Use recognizable, standard verbs such as: `create`, `decrypt`, `delete`,
  `encrypt`, `execute`, `generate`, `get`, `handle`, `list`, `map`, `set`,
  `update`.
- Prefer a listed verb wherever one fits exactly — `mapDatabaseError` over
  `classifyDatabaseError`.
- For review, the list is a guide, not a closed set. The violation is a vague
  catch-all verb that leaves the action unstated — "manage", "process", "do". A
  precise verb outside the list is correct and is not a finding. The standard
  the code is WRITTEN to is stricter than the standard it is FLAGGED on, and
  that asymmetry is deliberate.

## 🎯 Review Guidelines

- **Clear-cut cases only:** Flag the catch-all case above and nothing else — a
  verb whose action cannot be read off the name. Everything else is the author's
  call; a verb you would have chosen differently is not a finding.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: Prefer the listed verb — a write-time preference, not a finding

#### ⚠️ Weaker (a precise verb where a standard one fits exactly)

```kotlin
class ErrorResolver {
  
  // WEAKER: "classify" is precise enough to read, so this is NOT a review
  // finding. Write `map` — the project's standard verb for translating one
  // type into another — but do not flag `classify` at review.
  fun classifyException(e: Throwable): ErrorType {
    // ...
  }
}
```

#### ✅ Preferred (the standard recognized verb "map")

```kotlin
class ErrorResolver {
  
  // ADHERES TO RULE: "map" is the recognized, standard verb for translating one type to another.
  fun mapException(e: Throwable): ErrorType {
    // ...
  }
}
```

### Example 2: The reviewable violation — a vague catch-all verb

#### ❌ Negative Example (Using vague catch-all verbs like "manage")

```kotlin
class AdminService {
  
  // VIOLATION: "manage" is extremely vague. It is unclear if this operation 
  // is creating, overwriting, appending, or deleting roles under the hood.
  fun manageUserRoles(userId: String, roles: Set<String>) {
    // ...
  }
}
```

#### ✅ Positive Example (Using the precise CRUD verb "update")

```kotlin
class AdminService {
  
  // ADHERES TO RULE: "update" is a standard CRUD verb that precisely describes modifying state.
  fun updateRoles(userId: String, roles: Set<String>) {
    // ...
  }
}
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
