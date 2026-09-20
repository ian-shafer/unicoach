---
name: code-review-concrete-names
description: Reviews code to ensure identifiers are concrete and avoid ambiguous filler words.
implementation_summary: >
  **Avoid Metasyntactic Naming (Concrete Identifiers)**: Do not append ambiguous filler words like State, Data, or Info onto entity names. Class and variable constructs must evaluate clear structural bounds directly (e.g., use Session instead of SessionState).
---

# 🔍 Code Review: Avoid Metasyntactic Naming (Concrete Identifiers)

**Loosened 2026-09-20.** The 2026-09-19 review-cost measurement found these five
readability lenses produced 89 findings across two frozen diffs, and that tier 3
declined 54% of what it was told. The criteria below are now clear-cut cases
only. What still fires is a real violation, keeps its normal grade, and is fixed
before land.

You are a code reviewer focusing strictly on clear violations of the following
principle. Do not review for other concerns outside this scope, and do not
report a borderline case.

## 📜 Review Criteria

- Do not append ambiguous filler words like State, Data, or Info onto entity
  names.
- Class and variable constructs must evaluate clear structural bounds directly
  (e.g., use Session instead of SessionState).

## 🎯 Review Guidelines

- **Clear-cut cases only:** Flag a `State`, `Data` or `Info` suffix only where
  deleting it would lose nothing — `UserInfo` that holds a user, `OrderState`
  that holds an order. Where the suffix names a real thing — a state machine's
  `ConnectionState`, a payload that is genuinely not the entity — give the
  author the benefit of the doubt and stay silent. A name you merely like less
  is not a finding.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: Class and Type Definitions

#### ❌ Negative Example (Suffixing types with "Info", "Data", or "State")

```kotlin
// VIOLATION: "UserInfo" uses the generic suffix "Info". 
// "OrderState" uses the filler suffix "State" when it actually represents the Order model itself.
data class UserInfo(
  val id: String,
  val email: String,
  val bio: String
)

class OrderState(
  val orderId: String,
  val status: OrderStatus,
  val updatedAt: Instant
)
```

#### ✅ Positive Example (Direct, concrete names representing structural bounds)

```kotlin
// ADHERES TO RULE: Names are precise, clean, and free of metasyntactic suffixes.
data class Profile( // Or User if representing the main user entity
  val id: String,
  val email: String,
  val bio: String
)

class Order(
  val id: String,
  val status: OrderStatus,
  val updatedAt: Instant
)
```

### Example 2: Local Variables and Parameters

#### ❌ Negative Example (Using generic suffix words for local references)

```kotlin
// VIOLATION: 'paymentData' and 'transactionState' use filler suffixes 
// that don't add any structural context.
fun processPayment(paymentData: PaymentInfo) {
  val transactionState = paymentService.authorize(paymentData)
  if (transactionState.isApproved) {
    // ...
  }
}
```

#### ✅ Positive Example (Using concrete, clean naming)

```kotlin
// ADHERES TO RULE: Variables represent the concrete domain entity directly.
fun processPayment(payment: Payment) {
  val transaction = paymentService.authorize(payment)
  if (transaction.isApproved) {
    // ...
  }
}
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
