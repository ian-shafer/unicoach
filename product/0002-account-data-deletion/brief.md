# Product Brief 0002 — Account data deletion

Status: GATE 1 ANSWERED 2026-10-31 — D2, D4, D5, D6 approved on their defaults;
D1 and D3 AMENDED (a deleted account is a row in `deleted_users`; erasure is
driven by the schema); two standing rules added (D7 never an empty value, D8
classification lives in the type). `spec.md` written the same day with seven
slices under handle `deletion`; **GATE 2 ANSWERED the same day** (D9, D10, D12
on defaults; D11 amended — orphaned LLM calls are kept). READY TO START:
`deletion/01/the-classification` (and `deletion/05/the-words`, which PREFERs
`deletion/04`). Closure (in-app delete, soft delete, links severed) LANDED as
RFC 252 via brief 0014 `family/09` (`main@b99a77d3`, 2026-10-11); reset (D4) is
deferred. The original framing below is left as written (2026-08-25); its gate-1
list was never taken, so the re-presented and now answered D1–D6 supersede it
without contradicting a committed decision.

Slice IDs: `deletion/NN/name`, declared in `spec.md`. Ledger: at the end of this
file.

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

---

## Re-framed 2026-10-30 — erasure only, the App Store launch blocker

Ian, 2026-10-30: _"Run brief 0002, narrowed to account ERASURE as the App Store
launch blocker. RFC 252 already closes accounts (in-app delete, soft delete,
links severed); erasure is the part it left. Re-map the schema first — the
brief's map predates brief 0014's supporter tables, parent convos, account-keyed
subscriptions and supporter_llm_costs — then bring me D1–D6 with defaults,
marking which RFC 252 already settled."_

### What RFC 252 settled (landed `main@b99a77d3`, 2026-10-11)

Close, as built (`AccountClosureService`, one transaction): lock the user; sever
every live link from the closer's side, one link-ended email per link; cancel
the closer's pending link requests; revoke the closer's invites and the Family
Cost Report link; soft-delete `students`, `supporters`, `users`; physically
delete the `user_auth_identities` row (the one physical delete in the codebase,
admitted by a guard that checks the owner is closed); revoke every session.
Door: Settings → "Delete account", both roles, inline confirmation. A paying
user is warned that Apple keeps billing and shown the manage-subscription
control; the delete is not blocked. The login screen says "Your account has been
closed." and gives Apple's manual route for removing the app from an Apple ID.
Subscriptions and LLM cost rows are kept. Gate decisions 1–4 of RFC 252 are in
its file; the one that binds here is **RFC 252 gate 1: "Close now, erase
later"** — erasure is this brief's slice, with this brief's keep-or-erase
decisions.

**What a user gets today is Apple's "deactivation".** Apple's support article
for guideline 5.1.1(v) says, verbatim, _"only offering to temporarily deactivate
or disable an account is insufficient"_
(`research/app-store-and-legal-erasure.md` §1). So the launch blocker is real
and is exactly the gap RFC 252 named.

### The schema, re-mapped 2026-10-30

Four reports, every claim cited to a file and line at `main@00bdeb7e`:

| Report                                         | Covers                                                                                                                      |
| ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `research/schema-map.md`                       | every account-linked table (47 of 97), its FK behaviour, its delete guard, its history table, which columns hold the person |
| `research/unkeyed-data-and-copies.md`          | what a key walk cannot reach: the LLM log, queue payloads, email bodies, logs, backups, processors, the iPhone              |
| `research/closure-as-built-and-purge-infra.md` | what RFC 252's close does in code, how closed data stays invisible, the periodic-job and config machinery a purge reuses    |
| `research/app-store-and-legal-erasure.md`      | Apple 5.1.1(v) and its support article verbatim, Sign in with Apple revocation, GDPR/CCPA, LLM-provider retention, minors   |

**Corrections to the 2026-08-25 map** (`schema-map.md` §D, ten items; the ones
that change the design):

1. **A second account axis exists.** `supporters`, `supporter_student_links`
   (+versions), `supporter_invites`, `supporter_link_requests`,
   `supporter_llm_costs`, `convos.party`/`convos.supporter_id`, and the
   `account_llm_cost` view. One `users` row can be both a student and a
   supporter, so "erase this user" walks two subtrees.
2. **`subscriptions` is keyed by `users.id`**, `NOT NULL`, no `ON DELETE`
   (`subscriptions-key-on-account.sql:54,98`); `student_id` is gone. The
   rebind-on-return RFC 252 built reads the closed `users` row, so the `users`
   row must EXIST, soft-deleted, for a returning subscriber to get their
   entitlement back. Apple's `original_transaction_id` is TOTAL-unique.
3. **Every student child cascades on paper and is refused in practice.** All
   student-owned tables carry `ON DELETE CASCADE` from `students`, but Postgres
   fires each child's own `BEFORE DELETE` guard on the cascaded rows, so the
   cascade the schema calls "the future physical-erasure path" (0006:54-56) is
   refused one level down on all 34 guarded tables. The history tables add
   `RESTRICT` edges back to every live row and hold **every prior value** of
   name, email, date of birth, income band — a scrub of the live row writes one
   more history row and leaves the old ones.
4. **The words have no account key.** `llm_requests.system`/`.content`,
   `llm_responses.content` and `llm_responses_raw.payload` hold every message
   typed and every reply, verbatim. They are reached only through five owner
   tables (`convo_requests`, `extraction_runs`, `synthesis_runs`,
   `fit_lens_runs` with TWO id columns, `supporter_llm_costs`), each pointing AT
   the log with no `ON DELETE`. Delete the owners first and the text is
   stranded. **Orphans are routine, not crash-only**: any rejected, transient or
   errored extraction, synthesis or fit-lens call writes no run row, and a chat
   turn whose `convo_requests` write fails leaves the committed call un-owned.
   No person key reaches those rows.
5. **Each guarded table carries exactly ONE delete trigger**, from six shared
   functions, never "2-3". `claims`, `commitments`, `fit_suggestions`,
   `subscriptions` are mutable entities, not append-only logs.
6. **Tables the old map did not name**: `money_profiles`, `cost_report_shares`,
   `share_events`, `sharing_permissions`, `intended_subjects` and its status,
   `admission_test_scores` and declines, `college_list_entries` (+versions),
   `sessions` (user agent, first IP), `email_sends`, `students.date_of_birth`.
7. **`user_auth_identities` is already deleted at close** (RFC 252), by a
   conditional guard that admits the delete only when the owner is closed — the
   template for D3.

**The branches an FK walk misses** (`unkeyed-data-and-copies.md`, closing list):

- **`email_sends`**: recipient address, subject and the FULL BODY of every email
  ever sent, keyed by the address string only, delete- and update-guarded. Link
  emails name the other party.
- **`jobs.payload`**: no FK. `SEND_EMAIL` carries the address, the RAW
  verification token, and the other party's name; other job kinds carry convo,
  student and transaction ids. Completed jobs are reaped after 30 days;
  dead-lettered jobs are never reaped. `job_attempts.error_message` carries
  `to [<address>]`.
- **The parent's coach talks about the student.** A supporter-party convo
  carries both `supporter_id` and `student_id`; the student's name, relationship
  and college list are in every turn's `llm_requests.system`. It keeps rendering
  after the student closes. Under physical delete it would CASCADE from
  `students` — destroying the PARENT's own conversation, which brief 0014 D9
  says survives.
- **Three copies of the raw verification token** (hash in `verification_tokens`,
  raw in the queue payload, raw in the email body).
- **Backups**: `infra/rds.tf` sets no `backup_retention_period` and
  `skip_final_snapshot = true`; the live window is not knowable from the repo
  (an operator `aws rds describe-db-instances` answers it). No dumps, no
  exports, no analytics copies.
- **Processors**: Anthropic holds every prompt and reply with no account id
  attached (nothing to key a deletion request on; its stated retention is the
  mechanism — 30 days by default, longer for flagged content). Apple holds the
  transaction and the sign-in `sub`. SES holds message metadata.
- **Logs**: stdout → journald on the instance, no retention configured; one line
  prints full tool input on dispatch failure, one prints a malformed email
  payload, failure lines print the address. CloudWatch keeps remote ops-run
  output 30 days.
- **The iPhone**: two `UserDefaults` keys and the cookie, all cleared by RFC
  252's path except on a `401`.

**Machinery that exists for a purge** (`closure-as-built-and-purge-infra.md`,
"Reusable"): the close as phase one; the periodic-job machinery end to end
(`periodic_jobs` row, `JobType`, `JobHandler`, sweep precedent, DB-clock and
idempotency rules in `cron/INVARIANTS.md`); the HOCON-plus-env knob pattern for
"N days"; `SoftDeleteScope.DELETED` with no caller yet (the sweep's read); the
conditional guard; catalog-walk tests (`NoUrlColumnOutsideExternalDocumentsTest`
and siblings) as the drift-guard precedent; admin `undelete` for the `users`
half. **Missing**: the traversal itself; a way past the guards and `RESTRICT`
history edges that still refuses every non-purge delete; a schema shape that can
hold a tombstone (`users.email`/`name` are `NOT NULL` with format checks;
`convos.student_id` is immutable by trigger; the cost views are inner joins);
any reset; any export; the copy.

**What the law and Apple actually require** (`app-store-and-legal-erasure.md`,
closing section):

- Apple: delete "the entire account record, along with associated personal
  data"; legally-required retention is allowed **if the flow says what is
  kept**; a deferred purge is explicitly allowed — _"Inform the person how long
  it will take [...] and provide a confirmation when the deletion is complete"_;
  subscriptions: warn, show manage, never block (done, RFC 252).
- GDPR: without undue delay, one month to complete (Art. 12(3)); a usable
  multi-week "undo" window is endorsed by no source; anonymisation counts only
  when re-identification is not reasonably likely — **a row that keeps a join
  key to the person is still personal data** (ICO 2025); backups may age out on
  a stated schedule the notice mentions (ICO). CCPA: 45 days; de-identification
  explicitly allowed; backups carved out.
- Sign in with Apple: Apple's 2022 notice says tokens "must" be revoked at
  deletion; the current support article and TN3194 say "should"/"expected". The
  backend validates identity tokens by JWKS only, never exchanges the
  authorization code, **holds no refresh token** — programmatic revocation is
  impossible for existing users. TN3194's own fallback is the manual route RFC
  252 shipped. No public evidence that review tests the call.
- Minors 13–17: no extra erasure mechanics; California B&P 22581 wants a NOTICE
  that a minor can remove content they posted — one sentence.

### Gate 1, re-presented — D1–D6 (erasure scope)

Each decision keeps its 2026-08-25 number. "RFC 252 settled" is binding already;
"Open" is what Ian decides now. Options are outcomes; the default is taken on
silence.

#### D1 — erase the person, keep the skeleton

**RFC 252 settled:** the `user_auth_identities` row is deleted at close; the
subscription row and the LLM cost rows are kept while the account is closed.

**Open:** what the purge erases, what it keeps, per class of data.

- **Option A (recommended, default) — "erase the person, keep the skeleton."**
  After the purge, nothing that identifies or quotes the person exists in our
  database. Kept: (1) one tombstone row each for `users`, `students`,
  `supporters` holding only the id and the three dates (created, closed, purged)
  — the skeleton the subscription record, the other party's severed link history
  and the parent's own conversation still point at; (2) the subscription record,
  bound to the tombstone, so a returning subscriber on the same Apple ID gets
  their entitlement back (RFC 252's rebind) and Apple's transaction id is never
  re-minted; (3) our spend ledger with the words removed — every LLM call keeps
  model, tokens, cost and time, and loses `system`, `content`, tool input and
  the raw provider body, so a past month's cost never changes after a purge; (4)
  the deletion receipt — the one confirmation email's send record (D5) — so we
  can show we told the person it was done. Erased: every other row the person
  owns, **including every history (`*_versions`) row**, every `email_sends` row
  addressed to any address the account ever had (the receipt excepted), every
  queue job and attempt that names the account or its addresses (dead-lettered
  included), tokens, sessions. **Orphaned LLM calls** (no owner row, so no
  person key) have their words blanked by age, not by person: any orphan older
  than the purge window loses its text. **The other party's records stay**: a
  parent's own conversation that names the student, and the link-ended email in
  the other party's send log, are the other party's data (brief 0014 D9, "each
  party's own conversations, inputs, and account survive the other's deletion");
  the student's name inside them is the parent's record, like a name in a
  notebook.
- **Option B — "erase everything, including the skeleton."** No tombstone: the
  `users`/`students`/`supporters` rows go too. Consequences: the subscription
  row must go with them (its key is `NOT NULL`), so a returning subscriber on
  the same Apple ID cannot subscribe on a new account (TOTAL-unique transaction
  id) until Apple's record expires; the parent's conversation about the student
  is destroyed by the cascade, breaking D9; the cost ledger loses the owner
  rows, so a past month's spend changes after a purge.
- **Option C — "scrub in place, delete nothing."** Blank the personal columns on
  every row and keep the rows. Fails the test the law sets: rows that keep their
  join keys and their shape are pseudonymised, not erased, and the history
  tables would still hold the prior values unless deleted anyway.

Recommend A: it is the only option that is erasure in the ICO's sense AND keeps
D9 and the rebind RFC 252 built. Cost of being wrong: a purge is irreversible by
construction; the window in D2 is the only undo.

#### D2 — close now, purge after a short fixed window

**RFC 252 settled:** access ends at once — sessions revoked, identity released,
links severed, the email already says "closed" (not "deleted").

**Open:** when the irreversible purge runs, and what we promise.

- **Option A (recommended, default) — "closed at once, erased by a periodic job
  once the close is N days old; N = 7, one config key; the app says 'within 30
  days'; one email when it is done."** The window is operator safety for a
  brand-new irreversible path and takeover recovery (admin `undelete` exists for
  the `users` half), not a user-facing undo: the identity is already released,
  so the person cannot log back in to change their mind. Saying 30 while doing 7
  keeps the promise true through a week of job failures; the one-month GDPR
  clock is met either way.
- **Option B — "erased inside the close transaction, immediately."** Cleanest
  legal posture and no job, no second email: the confirmation is in-app.
  Consequences: an irreversible traversal of ~47 tables runs on the request path
  on day one with no operator window; an account-takeover deletion is
  unrecoverable; the person's address is gone before any confirmation could be
  sent.
- **Option C — "a 30-day usable undo: the person can sign back in and cancel."**
  Endorsed by no legal source; contradicts RFC 252, which already releases the
  sign-in at close; would need the identity row kept for 30 days.

Recommend A. Cost of being wrong: small — N is a config key, and B is a later
narrowing (set N to 0 and run the purge inline) once the path has run in
production.

#### D3 — only the purge can delete a person's rows, and a test proves it

**RFC 252 settled:** the precedent. `user_auth_identities`' guard now admits a
DELETE **only when the owning `users` row is soft-deleted** and refuses every
other delete (`user-auth-identities-delete-on-closed-account.sql`).

**Open:** how the purge gets past 34 delete guards, the update guards on the
logs, and the `RESTRICT` history edges, without weakening them for anything
else.

- **Option A (recommended, default) — "each guard gains one purge arm: the owner
  is closed AND the statement runs inside a declared purge; everything else is
  refused as today."** The declared purge is a transaction-local marker the
  purge sets and nothing else does (mechanism — a `SET LOCAL` setting, or one
  SQL routine that owns the traversal — is /work's spec). A **drift guard** test
  walks `pg_constraint`/`pg_trigger` and fails when any table reachable from an
  account key is not covered by the purge, or carries a guard with no purge arm
  (the catalog-walk pattern of `NoUrlColumnOutsideExternalDocumentsTest`, with
  its anti-vacuity probe). The log tables' UPDATE guards gain the same arm so
  the words can be blanked (D1).
- **Option B — "disable triggers inside the purge transaction."** Precedent
  exists in two migrations (`ALTER TABLE … DISABLE TRIGGER`). Consequences: a
  blanket switch rather than a named exception; needs table-owner privilege on
  the app role; nothing in the schema says WHICH deletes are legitimate, so the
  drift guard can only check coverage, not intent.
- **Option C — "drop the guards on account-owned tables."** Trades the audit
  guarantee for simplicity; refused by the brief's own framing.

Recommend A: it generalises what RFC 252 already shipped and makes the guarantee
testable. Cost of being wrong: one migration per guarded table either way; the
arm's shape is the only thing that would change.

#### D4 — reset is not this beat

**RFC 252 settled:** nothing (no reset exists at any level; tests use
`TRUNCATE … CASCADE`).

**Open:** whether a "start over" (keep the identity, erase the coaching state)
rides along, and for whom.

- **Option A (recommended, default) — "deferred: a follow-on slice, dev-only
  first, reusing the purge's traversal minus the identity rows."** The launch
  blocker stays as small as it can be; the traversal is written once with the
  cut point (identity or everything) as a parameter so the follow-on is cheap.
- **Option B — "build reset in the same slice."** Same traversal, one more door;
  widens the launch-blocking change by an admin or user surface and a second set
  of acceptance criteria.
- **Option C — "user-facing 'start over' now."** A product feature (value before
  ask, confirmation, what survives) that deserves its own gate.

Recommend A. Reversible: the slice board can promote the follow-on any time.

#### D5 — say "deleted", say what is kept, confirm when done

**RFC 252 settled:** the door (Settings → Delete account, both roles, inline
confirmation), the billing notice and manage-subscription control, the login
screen's closed line and the Apple manual route, `/privacy` pointing at the
door, "contact us" for access requests.

**Open:** the words, the completion confirmation, what the operator sees, export
first, the minors notice.

- **Option A (recommended, default) — "the confirmation says deleted and erased
  within 30 days, and what stays: a record that the account existed and when it
  was deleted, your subscription record with Apple, and our spend ledger with
  your words removed, plus the provider's retention; one email confirms when
  erasure is complete; the operator sees only the tombstone afterwards; no
  export in this beat; `/privacy` gains the one minors sentence and loses its
  stale 'essay content'."** Apple's article asks for exactly the first three;
  the receipt email is Apple's "confirmation when complete" and the only channel
  that survives the account. No export: Apple does not require it and GDPR Art.
  15/20 are separate rights that "contact us" already covers for now.
- **Option B — "export first."** A "download my data" step before deletion.
  Nothing exists; it is its own slice with its own format questions; it would
  delay the launch blocker.
- **Option C — "no confirmation email; the in-app notice states the window."**
  Weaker reading of Apple's text; saves the receipt row in D1; a reviewer who
  reads the article literally may ask for it.

Recommend A. Cost of being wrong: copy — cheap to change, but it is the copy a
reviewer reads.

#### D6 — Apple's manual route stays; the subscription outlives the person

**RFC 252 settled:** the subscription is kept, warned-not-blocked, and rebinds
to a new account on the same Apple ID; the Apple identity row is deleted at
close; the login screen shows Apple's manual route (TN3194); Google stores no
token, so there is nothing to revoke.

**Open:** programmatic Sign in with Apple token revocation; what the
subscription row keeps after the purge.

- **Option A (recommended, default) — "ship erasure without the revoke call; the
  manual route stays; a follow-on slice starts exchanging the authorization code
  at sign-in and storing the refresh token so revocation becomes possible for
  accounts created after it. The subscription row stays bound to the tombstone
  indefinitely, holding only Apple's identifiers, product, status and
  periods."** The backend holds no refresh token today, so for every existing
  account the only compliant routes are the manual one or a re-authentication
  inside the delete flow; review enforcement of the call is undocumented; TN3194
  itself offers the manual route. The subscription row keeps no personal data
  beyond Apple's transaction id, and keeping it is what lets a returning
  subscriber keep what they paid for.
- **Option B — "re-authenticate inside Delete account: the Apple sheet appears,
  the server exchanges the fresh code, revokes, then closes."** Compliant for
  everyone today; adds an Apple sheet to the deletion flow for Apple users only,
  a client-secret signing key in config, and an Apple round-trip the close must
  survive failing. Build it if we want zero review risk on this point.
- **Option C — "drop the subscription row at purge."** The transaction id is
  arguably personal data once severed. Consequence: a returning subscriber
  cannot subscribe on a new account until Apple's record expires (TOTAL-unique
  id), and RFC 252's rebind is undone.

Recommend A, stated honestly: it is the one open review risk this beat accepts.
Cost of being wrong: a rejection on this point costs the follow-on slice built
sooner, nothing irreversible.

### Carried into every slice (not decisions — standing facts)

- **The drift guard is the acceptance test** of the whole brief: a test that
  walks the catalog and fails when a new account-linked table or guard is not
  covered by the purge. The code wins over the research maps above; the test
  derives the set from `pg_constraint`, never from this document.
- **Order matters**: collect `llm_request_id`s from the five owner tables before
  any owner row is removed; delete history rows before live rows; newest-first
  through `claims.superseded_by_id`.
- **The parent's conversation about a purged student** keeps rendering with the
  student's name from its own text, and the student's tombstone id. The list the
  parent's coach READS is gone with the student (the subject seam already
  returns nothing for a closed student).
- **Two operator facts outside the slice**: the live RDS backup window (not in
  the repo; the notice says "backups age out within N days", so N must be read
  from AWS), and journald retention on the instance.

### Gate 1 answered 2026-10-31 (Ian, verbatim)

> Approve D2, D4, D5, D6 on defaults. Amend D1 and D3, and add two standing
> rules.
>
> D1 amended — "no tombstone row; a deleted account is a row in deleted_users
> (and deleted_students, deleted_supporters)." Every child key onto
> users/students/supporters gains a sibling key onto the deleted table; at
> erasure the live key goes NULL and the deleted key is set, exactly one of the
> pair ever set. The account's rows are not deleted: every personal column goes
> NULL and deleted_at is stamped, history rows included, so row counts and the
> per-account cost spine survive. The LLM log keeps date, model, tokens and
> cost; its text and llm_responses_raw.payload go NULL. Per-account spend for
> any date range is identical before and after erasure, and a test asserts it.
> Physical deletes are limited to the rows we must never hold again (list them
> in the spec).
>
> D3 amended — "erasure is driven by the schema." Personal columns are declared
> with domains in a pii schema; forward-owned keys (the owner tables'
> llm_request_id columns) with domains in an owned schema. One function,
> soft_delete_entity(table, key_column, key_value), NULLs every pii.* column on
> a row and stamps deleted_at. The walk follows pg_constraint from the deleted
> account's ids, re-points keys, erases each reached row, and follows owned.*
> columns forward using the constraint's referenced column as the key. No
> per-table code, no column list. The guards gain one arm admitting these
> updates inside the declared erasure. The drift guard asserts: every column on
> a reachable table is pii.* or on a short named not-personal list; every FK
> onto a pii-bearing table is owned.* or on a named not-owned list; every table
> keyed on an account also keys on its deleted table; no table holding a
> person's data is keyless.
>
> Standing — "never an empty value." A column is never set to an empty string or
> placeholder. A personal column may be NULL only when the row's deleted_at is
> set, enforced by CHECK; tables holding personal data gain deleted_at if they
> lack it.
>
> Standing — "classification lives in the type." A column's personal or owned
> status is a domain, never a comment, a name pattern or a list.

#### The committed decisions, labelled

The verbatim text above is binding; the labels are the gloss later messages use.

- **D1 — a deleted account is a row in `deleted_users`.** Amended as quoted.
  Supersedes the presented Option A on four points, so no slice inherits the
  stale text: there is no skeleton-only tombstone (the `users`, `students` and
  `supporters` rows stay, personal columns NULL, and a `deleted_*` row is added
  beside them); history (`*_versions`) rows are erased in place, not deleted;
  the LLM log is kept with its text NULL, never deleted; `email_sends` rows are
  not deleted by address (they get a key and are erased like any other row, per
  D3's fourth drift rule). What the presented text and the amendment agree on
  still holds: the subscription outlives the person (now bound through its
  sibling key to `deleted_users`), the other party's records stay (brief 0014
  D9), and the deletion receipt is kept.
- **D2 — close now, purge after a short fixed window.** Approved on the default:
  closed at once, erased by a periodic job once the close is N days old, N = 7
  as one config key, the app says "within 30 days", one email when it is done.
- **D3 — erasure is driven by the schema.** Amended as quoted. Supersedes the
  presented Option A's "one purge arm per delete guard": the arm admits the
  erasing UPDATEs (and the few physical deletes D1 allows) inside the declared
  erasure, and the drift guard asserts the four schema rules quoted above rather
  than coverage of a hand-written traversal.
- **D4 — reset is not this beat.** Approved on the default: a deferred
  follow-on, dev-only first, reusing the walk with the cut point as a parameter.
- **D5 — say deleted, say what is kept, confirm when done.** Approved on the
  default. The "what stays" sentence changes with D1: a record that the account
  existed and when it was deleted, your subscription record with Apple, and our
  usage ledger with your words removed.
- **D6 — Apple's manual route stays; the subscription outlives the person.**
  Approved on the default: no revoke call now; a follow-on slice stores refresh
  tokens; the subscription row stays, holding only Apple's identifiers, product,
  status and periods.
- **D7 (standing) — never an empty value.** As quoted. Inherited by every future
  brief without re-asking.
- **D8 (standing) — classification lives in the type.** As quoted. Inherited by
  every future brief without re-asking.

Gate 2 (the slices, D9–D12) is in `spec.md`, answered 2026-10-31.

## Ledger

_(one line per landed slice: ID, RFC, code SHA, date, one-line what.)_

    deletion/01/the-classification LANDED as RFC 263 (main@99921e86,
      2026-10-31) — personal columns typed `pii.*` (60, across 20 table
      families), LLM-log keys typed `owned.llm_request_id`, one presence CHECK
      per formerly NOT NULL personal column, `deleted_at` on 11 erased-in-place
      tables, `email_sends.user_id` (backfilled), and the drift-guard test
      `ErasureClassificationTest` (rules 1–3). Approval: sessions and
      user_auth_identities keep their shape (hard-deleted, D10); the
      not-personal list is keyed by `table.column`.
    deletion/02/the-deleted-tables LANDED as RFC 264 (main@c1476182,
      2026-11-01) — `deleted_users` / `deleted_students` / `deleted_supporters`
      (append-only, id REFERENCES the live root, UNIQUE user_id), a
      `deleted_<root>_id` sibling on 27 keys across 24 tables and 11 history
      copies, each pair one CHECK; spend views read either key; the
      subscription rebind also rebinds from an erased account; drift rule 4.
      Approval (Ian, 2026-11-01): `supporter_invites` exempt (D10 deletes it);
      the roots' own history ids exempt; nullable keys
      (`email_sends.user_id`, `convos.supporter_id`) pair at-most-one; names
      kept (`deleted_*`, `erased_at`, `closed_at`); no sibling indexes.
    deletion/03/the-walk LANDED as RFC 266 (main@81f30c5a, 2026-11-01) —
      `erase_account` walks `pg_constraint` from a closed account's roots, moves
      every key onto its `deleted_*` sibling, NULLs every `pii.*` column through
      `soft_delete_entity`, follows `owned.*` keys into the LLM log (text NULL,
      tokens and cost kept) and deletes D10's rows, jobs matched by payload; one
      predicate (`erasure_admits`) opens eight guards; erased rows are excluded
      from every Kotlin read; admin-web `POST /user/{id}/erase` (D12) with
      per-table counts; expression indexes restore the meter's index scans.
      Approval (Ian, 2026-11-01): D-A revised — a conversation belongs to its
      party, and a row keyed on another account is never erased by any path
      (the student's data copied into a parent's conversation is a known
      limit); D-B..D-I on defaults; at review §10/§2 amended to match the code.
    deletion/04/the-sweep LANDED as RFC 267 (main@85a9c676, 2026-11-02) —
      closing an account (the app's Settings → Delete account, and admin-web's
      Delete) enqueues one ERASE_ACCOUNT job delayed by accountErasure.graceDays
      (7) in the close transaction; the job runs the walk and, in the same
      transaction, queues one ACCOUNT_ERASED email to the verified address,
      whose email_sends row is the receipt, keyed on deleted_user_id; a
      migration schedules every account already closed; D13 landed (the walk
      leaves jobs and returns at once for an erased account; the reaper deletes
      every finished job after 30 days; a dead-letter logs one ERROR line).
      Approval (Ian, 2026-11-01): D-H — "Could we delay a job for a week
      instead of using a cron?" / "Do not worry about a max": a job delayed at
      the close replaces the spec's periodic sweep and its batch size; D-A..D-G
      on defaults (verified address only; both doors confirm; the backfill runs
      on the old schedule, so accounts closed more than 7 days before the deploy
      are erased right after it). Email copy interim (deletion/05).
    deletion/05/the-words LANDED as RFC 269 (main@2fce18f7, 2026-11-04) —
      the iOS delete confirmation (both roles), the login line after a delete,
      the ACCOUNT_ERASED email and /privacy say the account is deleted now,
      erased within 30 days, what is kept (the existence-and-date record, any
      Apple subscription record, usage records with the words removed) and that
      the AI provider keeps conversation text up to 30 days with no account
      attached; the confirmation promises the email only to a verified address;
      /privacy drops "essay content" and gains the Cal. B&P 22581 minors
      notice. Kotlin copy has one home (ErasureDisclosure); graceDays above 30
      fails boot. Approval (Ian, 2026-11-04): "Approve defaults" — provider
      line on all three surfaces, unverified users told no email will come,
      "closed" → "deleted" on the login line, the minors caveat names its two
      reasons. PREFER deletion/04 was already met.
