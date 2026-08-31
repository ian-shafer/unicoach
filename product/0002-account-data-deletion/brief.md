# Product Brief 0002 — Account data deletion

Status: FRAMED, awaiting gate 1. Not started. Independent of brief 0001
(compliance, not differentiation) — but it unblocks repeatable clean-slate
testing, which 0001's slices need.

Slice IDs: this brief is not sliced yet. Its slices get permanent IDs of the
form `deletion/NN/name` at SPEC & SLICE.

Raised by Ian 2026-08-25 while looking for a clean account to test S1-S3 on: "we
should just build this. It's needed to comply with law (delete user data on
request)."

## Why

1. **Legal.** GDPR Art. 17 (right to erasure), CCPA/CPRA deletion rights.
2. **App Store.** Guideline 5.1.1(v): an app that supports account creation must
   offer account deletion IN-APP. Unicoach has account creation and paid
   subscriptions; a support-email workflow does not satisfy this. This is a
   review-blocking requirement for the v1 public launch.
3. **Testing.** A repeatable reset is how every future slice gets an honest
   first-session test. Today the only clean slate is a fresh throwaway account.

## Two operations, one engine

- **Reset** — keep the identity, erase the coaching state (claims, observations,
  commitments, college list, money profile, convos). "Start over" for a user;
  the dev/test affordance.
- **Delete** — erase the identity too. The legal obligation.

Delete is the superset; build it and reset falls out of the same traversal.

## What the schema does today (investigated 2026-08-25)

The repo is built to REFUSE deletion. This is the design work, not a script:

- `users` carries `trigger_00_prevent_physical_delete` — a physical DELETE is
  refused outright. Soft-delete (`deleted_at`) is the house pattern.
- `students.user_id` and `verification_tokens.user_id` reference `users(id)`
  with NO `ON DELETE` clause (NO ACTION) — they block a delete.
- `users_versions.id ... ON DELETE RESTRICT` — version history pins the row.
- Every append-only table (`claims`, `observations`, `commitments`,
  `claim_support`, `commitment_support`, `college_list_entry_support`,
  `extraction_runs`, `synthesis_runs`, `fit_lens_runs`, `fit_suggestions`,
  `llm_requests`, `llm_responses`, `llm_responses_raw`, `subscriptions`) carries
  2-3 `prevent_delete` / `prevent_physical_delete` triggers.
- Most student-owned tables DO cascade from `students(id)` — the traversal from
  a student is largely solved; the traversal from a USER is not.
- **The LLM call log is deliberately not student-linked.** Attribution flows
  through `convo_requests -> convos.student_id`, `extraction_runs.student_id`,
  `synthesis_runs.student_id`, `fit_lens_runs` (two ids, unioned). Finding
  "everything belonging to this user" is a graph traversal, and missing a branch
  is the silent failure mode.

## Gate-1 decisions (Ian's, not the LLM's)

- D1. **Delete vs anonymize, per table.** Payment/subscription records are
  normally RETAINED for tax/audit (often 7 years) and scrubbed of PII rather
  than deleted. Same question for the LLM cost ledger (a financial record) and
  the raw response bodies (contain student text). Proposed default: erase
  student-authored content and PII; retain non-identifying financial/aggregate
  rows with identifiers severed.
- D2. **Immediate vs grace period.** A 30-day soft-delete-then-purge window
  protects against rage-quits and account takeover; immediate erasure is the
  cleaner legal posture. Proposed default: soft-delete immediately (access ends
  at once), purge on a periodic job (RFC 97) after N days, N configurable.
- D3. **How the append-only guards are breached.** They protect audit integrity
  and must not simply be dropped. Proposed default: a narrow, explicitly-named
  purge path (SECURITY DEFINER routine or a guard that admits a declared purge
  context), logged, covered by tests that prove it works ONLY for purge.
- D4. **Scope of "reset"** — exactly which tables it clears, and whether it is
  user-facing ("start over") or dev-only.
- D5. **Surfaces.** In-app deletion entry point (App Store requirement) +
  admin/ops visibility + what the user is shown/told (and any export-first
  obligation).
- D6. Whether an Apple/Google SSO identity needs revocation handling
  (`user_auth_identities`) and what happens to an active paid subscription at
  deletion time.

## Success criteria

- A deletion request provably removes or anonymizes every row reachable from the
  user, with a TEST that walks the schema and fails when a new user-linked table
  is added without being covered (the drift guard — the same lesson as RFC 135's
  enum guards).
- The App Store in-app requirement is satisfied.
- `reset` gives a repeatable, one-command clean slate for testing.

## Notes

- Not sliced yet; sizing happens after gate 1.
- Related open item from brief 0001's runs: ingest observability (a silent no-op
  ingest and a stale jar are indistinguishable today). Separate, small, and
  worth doing before S4's data build.
