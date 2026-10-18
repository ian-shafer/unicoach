# RFC 255 — Owner budget exemption

**Status:** proposed. **Base:** `main@55f55ebc`.

The owner's own account ran out of the $5 lifetime free allowance, and there is
no purchase path for the operator of the product to use it. An account whose
`users.is_admin` is true is now exempt from the coaching meter: the gate never
exhausts it, and its usage bar shows 0%. Every non-admin account is metered
exactly as before.

## Context

Four facts from the code decide this design.

1. **One choke point.** All four turn gates (`CoachingService`,
   `SynthesisService`, `FitLensService`, `ExtractionService`) and both usage
   routes read `BudgetService.verdict` / `.entitlement`, and every one funnels
   into `entitlementForAccount(session, userId)`
   (`service/.../coaching/budget/BudgetService.kt`). There is no second gate.
2. **The subject is the ACCOUNT, not the role** (RFC 244).
   `entitlementForAccount` already holds the `UserId`; the role rows are one
   indexed hop away and already resolved by the callers that hold only a role
   id.
3. **`users.is_admin` already exists** (RFC 60, migration
   `0012.add-users-is-admin.sql`) and gates the admin panel
   (`admin-web/.../auth/AdminAuth.kt`). It is a grantable role that marks
   whoever operates the product — there is no separate "owner" flag.
4. **The meter's ceiling is a named constant.** `Nanodollars.MAX_FOR_PERCENTAGE`
   (about $92.2M) is the largest allowance `Entitlement.usedPercent` can compute
   a percentage for, and every percentage-checked config reader already bounds
   against this one value. Using it as the exempt account's allowance reuses
   that bound instead of inventing an infinity.

## Detailed Design

`BudgetService.entitlementForAccount` gains one branch, checked before the
subscription/free decision:

```kotlin
private fun entitlementForAccount(
  session: SqlSession,
  userId: UserId,
): Entitlement =
  if (UsersDao.findByIdOrNull(session, userId).getOrThrow()?.isAdmin == true) {
    adminEntitlement(session, userId)
  } else {
    when (val subscription = SubscriptionsDao.findCurrent(session, userId).getOrThrow()) {
      null -> freeBranch(session, userId)
      else -> subscriptionBranch(session, userId, subscription)
    }
  }
```

`adminEntitlement` reads the same lifetime ledger the free branch reads, but
pairs it with the ceiling instead of the configured free allowance:

```kotlin
/**
 * An admin account is the operator of the product and is exempt from the
 * student-facing meter (RFC 255): the lifetime ledger against
 * [Nanodollars.MAX_FOR_PERCENTAGE], the ceiling every percentage-checked
 * allowance already shares. The gate therefore never exhausts it, and
 * [Entitlement.usedPercent] floors to 0 — "0% of an effectively unlimited
 * allowance". The real spend is still reported, so the meter never lies.
 */
private fun adminEntitlement(
  session: SqlSession,
  userId: UserId,
): Entitlement =
  AccountLlmCostDao
    .lifetimeCost(session, userId)
    .map {
      Entitlement(
        spent = it.costNanodollars,
        allowance = Nanodollars.MAX_FOR_PERCENTAGE,
        basis = EntitlementBasis.AdminExemption,
      )
    }.getOrThrow()
```

Five consequences, each deliberate:

- **The check is on the account, before the subscription branch.** An admin who
  also holds a subscription is still exempt — the owner is never blocked,
  whether or not they happened to pay Apple. The exemption is unconditional, not
  a fallback for "no subscription".
- **A distinct basis, not an aliased one.** The exemption gets its own
  `EntitlementBasis.AdminExemption` arm, so the sealed union has one member per
  meter state and every exhaustive `when` is forced to handle it. At the wire
  boundary `CoachingCoverageKind.from` maps it to the EXISTING `free_allowance`
  member, so the wire vocabulary stays two (RFC 244's "no account rides
  another's subscription" is untouched) and no client changes.
- **The exemption follows `is_admin`, not a separate owner flag.** Admin is a
  grantable, revocable role (RFC 60), so every admin account is exempt, not only
  the first: granting a second admin also grants them this unconditional,
  non-kill-switchable exemption.
- **The ceiling is the allowance, not an invented infinity.** `exhausted` is
  false for any real spend because `spent < MAX_FOR_PERCENTAGE` always holds,
  and the existing `multiplyExact` guard (pinned by `EntitlementTest`) keeps the
  `spent × 100` inside a `Long` on the not-exhausted branch.
- **The polarity is "only a positive signal exempts".**
  `.getOrThrow()?.isAdmin
  == true` falls through to ordinary metering on a
  missing users row, and a failed read propagates (fails closed) rather than
  exempting.

The exemption is not configurable. There is no kill switch that reaches it: the
operator cannot be blocked by the student-facing meter they run. The existing
`budget.freeAllowanceUsd = 0` kill switch still blocks every non-admin account;
it simply does not block the operator.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/budget/Entitlement.kt` — the
  `EntitlementBasis.AdminExemption` arm and the `resetsAt` `when`.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt` — the
  admin branch, the `adminEntitlement` method, and the `UsersDao` import.
- `service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetSkipLog.kt` — the
  new arm in its exhaustive `when`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SupporterCoachingUsageResponse.kt`
  — the `AdminExemption` arm in `CoachingCoverageKind.from`.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/BudgetServiceTest.kt` —
  the admin tests below.
- `service/src/test/kotlin/ed/unicoach/coaching/budget/EntitlementTest.kt` — the
  admin-exemption reset-point test.

## Implementation Plan

1. Add the `is_admin` branch to `entitlementForAccount` and the
   `adminEntitlement` method.
2. Give the test's `createStudent` and `createSupporter` helpers an `isAdmin`
   parameter (default `false`) that writes `is_admin` on the `users` insert, so
   an admin fixture is an ordinary seeded account with `is_admin = true`.
3. Tight loop: `nix develop -c bin/test service`. Full suite at verify:
   `nix develop -c bin/test`.

## Tests

In `BudgetServiceTest`:

- `an admin account past the free allowance is still entitled` — spend over $5,
  assert `exhausted == false`, `usedPercent == 0`, allowance equals
  `Nanodollars.MAX_FOR_PERCENTAGE`, basis is `AdminExemption`.
- `an admin account's real spend is still reported` — assert `spent` equals the
  lifetime ledger, so the meter never lies even when it never blocks.
- `an admin holding a current subscription is still exempt` — subscribe the
  admin, spend past the plan budget, assert still entitled; pins the
  admin-before-subscription ordering.
- `an admin is exempt through the supporter surface too` — a supporter whose
  account is admin, spend past the allowance, assert not exhausted; pins that
  the one account-keyed choke point covers both surfaces.
- `a non-admin account with the same spend still exhausts` — the contrast: same
  spend with `is_admin = false`, assert exhausted, so the exemption does not
  leak.
- `a soft-deleted admin account is not exempt` — create the admin, soft-delete
  its `users` row, spend past the allowance, assert exhausted; pins that a
  missing users row falls through to ordinary metering.

In `EntitlementTest`:

- `an admin-exemption entitlement reports no reset point` — the exemption's
  lifetime meter never resets (`resetsAt == null`) and is not exhausted.
