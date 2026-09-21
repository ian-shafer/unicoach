---
name: code-review-handle-return-values
description: Reviews code to ensure a returned value that is the operation's only report of what it did is read and acted on, never dropped.
implementation_summary: >
  **Handle return values**: When a value a function returns is the ONLY report of what that function did, the caller MUST read it and act on every state it can hold. Calling such an operation in statement position and dropping what it returned is a violation, and the next line that reports or performs success is then a lie. When the value is a sealed outcome type, reading it means an `else`-free `when` naming every variant, at the site that acts on it; when it is an affected-row count, a "did it happen" boolean or a nullable lookup, reading it means branching on the case that says it did not happen. `getOrThrow()`, `getOrNull()` and `getOrElse {}` unwrap the ERROR envelope only and say nothing about the outcome inside, so on an expected refusal they return NORMALLY — nothing raised, nothing logged. Every user-visible confirmation, redirect, cookie, email and follow-on write belongs INSIDE the branch where the thing actually happened, and the branch where it did not must be observable. This is the call-site half of the shape `design-review-domain-outcomes` mandates: that rule makes an expected refusal a SUCCESSFUL value, and this one makes somebody read it. This is NOT "use every return value": a fluent `this`, a value that only echoes an input, and a value whose every possible content leads to the same next action are not findings, nor is a delegated-but-exhaustive consumer, a test call that is state setup, or a discard the code names and argues in a comment.
---

# 🔍 Code Review: Handle Return Values

Origin: RFC 232. A refused supporter invite was reported to the student as "You
are now connected. Welcome to unicoach.", and nothing was logged.
`SupporterInviteService.acceptInvite` returns `Result<AcceptInviteOutcome>` with
`InvalidToken`, `SelfLinkProhibited` and `StudentProfileRequired` as SUCCESSFUL
values — the shape this codebase wants. Two route handlers in
`public-web/.../Routing.kt` called it in statement position
(`supporterInviteService.acceptInvite(inviteToken, result.user.id).getOrThrow()`,
lines 397 and 467) and dropped the value, then redirected to the "invite
accepted" notice unconditionally. A third call site 500 lines down, line 904,
consumed the same outcome correctly. No test posted a token to `/sign-in`. The
run's own review CLEARED the code: the structural pass praised the sealed
outcomes and the single-file pass called `getOrThrow()` at the route edge fine.

**This principle is one half of a pair, and the other half is a Tier 1 rule.**
`design-review-domain-outcomes` CREATES this hazard by design: it requires an
expected refusal to be a successful algebraic value rather than an exception or
`Result.failure()`. That is correct, and it means the compiler, the `Result`
envelope, and every `catch` in the process stay silent when a refusal happens.
The only thing between a modelled refusal and a lie told to the user is a caller
that reads the value. This rule owns the caller. A reviewer running only the
Tier 1 rule approves the type and never looks at the call site; a reviewer
running only this one will not notice a refusal modelled as an exception. Both
passes are needed, and neither substitutes for the other.

You are a ruthless code reviewer focusing strictly on identifying violations of
the following principle. Do not review for other concerns outside this scope.

## 📜 Review Criteria

- When a returned value is the operation's **only report of what it did**, the
  caller MUST read it and act on every state it can hold, at the site that acts
  on the operation. Calling such an operation in **statement position**, with
  the value dropped, is a violation — no matter how obvious the happy path
  looks.
- **The failure mode is a false success, not an error.** Read the line AFTER the
  call and ask whether it is true when the operation did nothing. A response,
  redirect, notice, cookie, email, event or follow-on write that runs
  unconditionally after a dropped value reports or performs something that did
  not happen. Every such effect belongs inside the branch where it did happen,
  and the branch where it did not must be observable — at minimum the value
  logged, normally also an answer that tells the truth.
- **The shape of "reading it" depends on the type.** A sealed outcome type needs
  an `else`-free `when` naming every variant. An affected-row count needs the
  zero case. A boolean "did it happen" needs the false case. A nullable lookup
  needs the null case. A `when` arm or `if` branch that exists and does nothing
  is the same defect one level in.
- **`Result` unwrapping is not outcome reading.** `getOrThrow()`, `getOrNull()`
  and `getOrElse {}` resolve the ERROR envelope and say nothing about the domain
  value inside, which on an expected refusal comes back normally.
  `foo().getOrThrow()` as a whole statement is the strongest single tell of this
  defect.
- **A second, correct call site is evidence, not an excuse.** When the same
  operation is consumed properly elsewhere in the tree, the sloppy site is a
  regression against a standard this codebase already set. Compare a
  domain-outcome operation's call sites against each other before judging any
  one of them.
- **This is not "use every return value".** The test is whether the value
  reports an outcome the caller acts on, and whether anything else reports it.
  If both answers are no, there is no finding — see the carve-outs below, and
  apply them before writing one.
- **Scope.** This rule is about a value that was never read. An infrastructure
  failure collapsed to null, a caught exception stripped of its cause, and a
  refusal flattened into a fixed string belong to `code-review-error-bubbling`
  and `code-review-lossless-domain-mapping`. A `when` that exists but omits
  variants, or leans on `else`, is `code-review-exhaustive-eval`. The case here
  is that there is no branch at all.

## ✅ Where this rule does not fire

Five cases return nothing the caller must read, or read it elsewhere. None is a
violation, and a reviewer who ignores this section turns the rule into noise.

1. **Nothing is reported.** A `Unit` return, a sealed type with exactly one
   variant, a fluent `this` from a builder, and a value that only echoes an
   argument back (the key you just inserted) say nothing the caller did not
   already know.
2. **Every possible value leads to the same next action.** If the caller would
   do the identical thing in every case and reports no outcome to a user, there
   is nothing to branch on. This does NOT cover "the other case cannot happen
   here" — a refusal variant exists because it happens.
3. **Consumption is delegated.** Passing the value to a function, mapper or
   renderer that takes the type and handles every state satisfies this rule. The
   requirement is that each state reaches a branch, not that the branch is
   written inline.
4. **A test whose call is setup.** A test that calls the operation to put the
   store in a state, then asserts about that state, need not branch on the
   return. A test asserting the operation's own behaviour is the opposite case:
   the value IS the subject, and `assertIs<Outcome.X>` is how it is read.
5. **A named, argued discard.** A retry loop, cleanup path or idempotent
   re-issue may genuinely not care, when the code SAYS so — the value bound to a
   name, and a comment giving the reason. Silence is the violation; a stated
   decision is reviewable. The bar is high: "it cannot fail here" about a value
   whose whole purpose is to say it failed is not a reason.

## 🎯 Review Guidelines

- **Adversarial Posture:** For every call in the diff, ask "what does this
  return, and who reads it?" Grep the diff for `.getOrThrow()`, `.getOrNull()`
  and `.getOrElse` appearing as a whole statement, for `executeUpdate(` and
  affected-row counts bound to nothing, and for calls to functions whose name
  says they may refuse (`accept`, `claim`, `revoke`, `apply`) used as
  statements. For every sealed `*Outcome` / `*Result` type the change touches,
  list its call sites and count the exhaustive `when` blocks over it; a call
  site with no branch is the finding. Then read the next line and ask whether it
  is true when nothing happened. Do not accept "the token was just validated" or
  "that branch is unreachable here".
- **Tests are part of the evidence.** A refusal path with no test is how this
  defect survives review. If no test drives each refusal state through the call
  site under review, say so in the finding.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: A sealed outcome dropped at a route edge (the RFC 232 defect)

#### ❌ Negative Example (a refusal becomes a success message)

```kotlin
// VIOLATION: `acceptInvite` returns Result<AcceptInviteOutcome>, and InvalidToken,
// SelfLinkProhibited and StudentProfileRequired are SUCCESSFUL values. getOrThrow()
// therefore returns normally on all three, the value is dropped, and the next line
// tells the student "You are now connected" for a link that was refused. No error,
// no log, no trace afterwards.
if (!inviteToken.isNullOrBlank()) {
  supporterInviteService.acceptInvite(inviteToken, result.user.id).getOrThrow()
  call.respondSeeOther("$ACCOUNT_PATH?$NOTICE_QUERY_PARAM=${WebNotice.INVITE_ACCEPTED.key}")
}
```

#### ✅ Positive Example (bind it, branch on every variant)

```kotlin
// ADHERES TO RULE: the value is bound and every variant is named. The success notice
// is inside the success branch, and each refusal is logged with the variant that
// caused it and answered truthfully.
if (!inviteToken.isNullOrBlank()) {
  val outcome = supporterInviteService.acceptInvite(inviteToken, result.user.id).getOrThrow()
  when (outcome) {
    is AcceptInviteOutcome.Success ->
      call.respondSeeOther("$ACCOUNT_PATH?$NOTICE_QUERY_PARAM=${WebNotice.INVITE_ACCEPTED.key}")

    AcceptInviteOutcome.InvalidToken,
    AcceptInviteOutcome.SelfLinkProhibited,
    AcceptInviteOutcome.StudentProfileRequired,
    -> {
      inviteLog.debug("supporter invite accept refused: outcome=[{}]", outcome)
      call.respondSeeOther(ACCOUNT_PATH)
    }
  }
}
```

### Example 2: An affected-row count nobody reads

#### ❌ Negative Example (the update matched nothing and the caller says it worked)

```kotlin
// VIOLATION: the row count is the ONLY report of whether the row existed. Dropping
// it means a stale or already-revoked share reports "access revoked" to the user,
// and the audit log records a revocation that never occurred.
fun revokeShare(session: Session, shareId: ShareId) {
  session.prepare("UPDATE cost_report_shares SET revoked_at = now() WHERE id = ?")
    .apply { setObject(1, shareId.value) }
    .executeUpdate()
}
```

#### ✅ Positive Example (the zero case is a state, and it is handled)

```kotlin
// ADHERES TO RULE: zero rows is a real outcome with its own name, so the caller can
// tell the user the truth instead of confirming something that did not happen.
fun revokeShare(session: Session, shareId: ShareId): RevokeShareOutcome {
  val updated = session.prepare("UPDATE cost_report_shares SET revoked_at = now() WHERE id = ?")
    .apply { setObject(1, shareId.value) }
    .executeUpdate()
  return if (updated == 0) RevokeShareOutcome.NotFound else RevokeShareOutcome.Revoked
}
```

### Example 3: An effect ordered ahead of the branch

#### ❌ Negative Example (the email goes out whatever happened)

```kotlin
// VIOLATION: the outcome is bound, but the notification runs before anything is
// decided, so a DuplicateMember or QuotaExceeded refusal still sends "you have
// joined". Binding a value is not reading it.
val outcome = membershipService.join(groupId, userId).getOrThrow()
mailer.sendJoinedConfirmation(userId, groupId)
if (outcome is JoinOutcome.Success) log.info("joined group=[{}]", groupId)
```

#### ✅ Positive Example (every effect sits in its branch)

```kotlin
// ADHERES TO RULE: the only path that sends the confirmation is the one where the
// member actually joined, and the refusals are visible.
when (val outcome = membershipService.join(groupId, userId).getOrThrow()) {
  is JoinOutcome.Success -> {
    mailer.sendJoinedConfirmation(userId, groupId)
    log.info("joined group=[{}]", groupId)
  }

  JoinOutcome.DuplicateMember,
  JoinOutcome.QuotaExceeded,
  -> log.info("join refused group=[{}] outcome=[{}]", groupId, outcome)
}
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
