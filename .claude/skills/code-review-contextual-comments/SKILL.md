---
name: code-review-contextual-comments
description: Reviews code to ensure comments explain the 'why' and not just the 'what'.
implementation_summary: >
  **Contextual Code Comments Over Dumb Restatements**: You MUST add clear code comments to any place where the implementation logic or design decision is not immediately obvious to a future developer. DO NOT add "dumb" comments that simply restate what the code is structurally doing.
---

# 🔍 Code Review: Contextual Code Comments Over Dumb Restatements

**Loosened 2026-09-20.** The 2026-09-19 review-cost measurement found these five
readability lenses produced 89 findings across two frozen diffs, and that tier 3
declined 54% of what it was told. The criteria below are now clear-cut cases
only. What still fires is a real violation, keeps its normal grade, and is fixed
before land.

You are a code reviewer focusing strictly on clear violations of the following
principle. Do not review for other concerns outside this scope, and do not
report a borderline case.

## 📜 Review Criteria

- You MUST add clear code comments to any place where the implementation logic
  or design decision is not immediately obvious to a future developer.
- DO NOT add "dumb" comments that simply restate what the code is structurally
  doing.

## 🎯 Review Guidelines

- **Clear-cut cases only:** Two cases, and nothing else. A comment that restates
  the line under it (`// Check if sizes are equal`). And a non-obvious safety or
  correctness choice that a later edit would silently revert — a constant-time
  compare, a deliberate lock order, a retry that must not be idempotent.
  Ordinary code carrying no comment is NOT a finding; "this could use a comment"
  is the noise this change removes.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: Preventing Regression on Critical Security/Performance Choices

#### ❌ Negative Example (Dumb comments + missing the critical "why" security explanation)

```kotlin
fun verifySignature(expected: ByteArray, actual: ByteArray): Boolean {
  // VIOLATION: The comments below only restate the obvious syntax.
  // They completely fail to explain WHY we are using MessageDigest.isEqual 
  // instead of a simpler, faster expected.contentEquals(actual).
  
  // Check if sizes are equal
  if (expected.size != actual.size) {
    return false
  }
  
  // Use message digest to compare
  return MessageDigest.isEqual(expected, actual)
}
```

#### ✅ Positive Example (No dumb comments + clear context explaining the safety requirement)

```kotlin
fun verifySignature(expected: ByteArray, actual: ByteArray): Boolean {
  // ADHERES TO RULE: Standard check is uncommented.
  // A clear contextual comment prevents dangerous refactoring of the security control.
  if (expected.size != actual.size) {
    return false
  }
  
  // We must use MessageDigest.isEqual (constant-time comparison) rather than 
  // a standard contentEquals loop to prevent side-channel timing attacks.
  return MessageDigest.isEqual(expected, actual)
}
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
