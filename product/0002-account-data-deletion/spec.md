# Brief 0002 — spec: account erasure

Gate 2 artifact. Slice handle: **`deletion`** (IDs `deletion/NN/name`).

Gate-1 decisions D1–D8 are recorded verbatim in `brief.md` ("Gate 1 answered
2026-10-31") and are binding here. D7 and D8 are **standing** rules every future
brief inherits. This file adds the DISCOVER corrections the amendments turned
up, the slices, and gate-2 decisions D9–D12 — **answered 2026-10-31**: D9, D10
and D12 approved on their defaults; D11 amended to Option B, orphaned LLM calls
are kept (see "Gate 2 answered" at the end).

The four research reports under `research/` (schema map, unkeyed data and
copies, closure as built and purge infrastructure, App Store and legal) remain
the ground truth for the map; every claim below that is not re-cited here is
cited there at `main@00bdeb7e`.

## Preamble — what the amendments meet in the code

Ian's amended D1 and D3 were checked against the live schema on 2026-10-31.
Nothing contradicts them; eight facts shape how the slices are cut.

| #  | The amendment assumes                                                        | The code says                                                                                                                                                                                                                                                                                                                                                                                                                                    | Consequence                                                                                                                                                                                                           |
| -- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| X1 | Domains classify columns                                                     | Domains over TEXT are house precedent (`db/schema/create-answer-status-domains.sql`, `0054`), but **every object lives in `public`** — no migration creates a schema, and `0056` records that an unqualified name inside a function resolves against whatever `search_path` the caller has                                                                                                                                                       | `pii` and `owned` are the first non-public schemas. Every reference to them in a function is schema-qualified. `deletion/01`.                                                                                         |
| X2 | "Tables holding personal data gain `deleted_at` if they lack it"             | `deleted_at` exists on **four** live tables only — `users` (0001), `students` (0005), `supporters` and `supporter_student_links` (with their two history tables). The twelve `*_versions` tables, `convos`, `claims`, `observations`, `commitments`, the three LLM log tables, `email_sends`, `supporter_link_requests` (no `deleted_at` **by its own comment**, line 65) and the rest have none                                                 | Most of the ~47 account-linked tables gain `deleted_at` in `deletion/01`; the D7 CHECK (`pii.*` NULL only when `deleted_at` is set) lands with it.                                                                    |
| X3 | "The guards gain one arm"                                                    | Six **shared** guard functions (`prevent_physical_delete`, `prevent_immutable_updates`, `prevent_log_update`, `prevent_log_delete`, `prevent_delete`, `prevent_immutable_entity_*`) plus about a dozen **table-specific** ones (`prevent_convos_party_updates`, `prevent_supporter_immutable_updates`, `prevent_supporter_link_request_*`, `prevent_sharing_permission_column_updates`, the intended-subject pair, …), attached by 23 migrations | The arm is written once per **function**, not per table: one shared predicate ("inside the declared erasure") every guard calls first. `deletion/03`.                                                                 |
| X4 | "Every child key onto users/students/supporters gains a sibling key"         | Onto `users`: 8 edges (`sessions` CASCADE, `users_versions` RESTRICT, `students`, `verification_tokens`, `user_auth_identities` CASCADE, `subscriptions`, `supporters`, `supporter_invites.inviter_user_id` CASCADE). Onto `supporters`: 6 (`convos`, `supporter_invites`, `supporter_link_requests`, `supporter_llm_costs`, `supporter_student_links`, `supporters_versions`). Onto `students`: 17 migrations' worth                            | The sibling-key change is one migration per table, ~30 tables, each with a one-of CHECK. It is its own slice (`deletion/02`) because it is the one change Ian approves table by table.                                |
| X5 | "Per-account spend for any date range is identical before and after erasure" | `account_llm_cost` (`db/schema/account-llm-cost.sql`) keys spend on `students.user_id` / `supporters.user_id` through **INNER JOINs**, by design with no `deleted_at` predicate                                                                                                                                                                                                                                                                  | After erasure `students.user_id` is NULL, so the view must key on the live-or-deleted pair, or the test fails by construction. Re-keying the views is in `deletion/02`; the test is in `deletion/03`.                 |
| X6 | "History rows included"                                                      | Every `*_versions` table is written by an **AFTER INSERT OR UPDATE** trigger that copies `NEW` (`log_user_version`, 0001:78-120), references the live id `ON DELETE RESTRICT`, and carries no guard of its own                                                                                                                                                                                                                                   | The erasing UPDATE writes one more history row, already NULL — correct and expected. History rows are reached by the walk through their FK, like any child. Nothing special-cases them.                               |
| X7 | "No table holding a person's data is keyless"                                | Three keyless holders of personal text: `email_sends` (recipient, subject, body — keyed by address only, 0008), `jobs.payload` / `job_attempts.error_message` (generic JSONB and text; a SEND_EMAIL payload carries the address and a raw token), and the three LLM log tables (reached only through `owned.*` columns)                                                                                                                          | `email_sends` gains a user key in `deletion/01`. The LLM log is covered by the `owned` walk. `jobs` is generic and cannot carry a `pii` domain on a JSONB payload, so its rows are the physical-delete case: **D10**. |
| X8 | A sibling key onto `deleted_users` keeps RFC 252's rebind                    | RFC 252 rebinds a returning subscriber by Apple's `original_transaction_id` (TOTAL unique) to the new account by **updating** `subscriptions.user_id`; the live/deleted pair makes that update "clear the deleted key, set the live key"                                                                                                                                                                                                         | One line in the rebind path, stated in `deletion/02`'s acceptance so it is not forgotten.                                                                                                                             |

Two things the amendments make simpler than the presented defaults, recorded so
no slice re-litigates them: nothing in this brief physically deletes a history
row, a `convos` row, a `claims` row or an LLM log row — the RESTRICT edges and
the self-reference on `claims.superseded_by_id` that the presented D3 worried
about never fire; and the walk has no order to get right, because an UPDATE that
NULLs columns and re-points keys has no parent/child constraint to satisfy (the
FKs onto `deleted_*` are satisfied by inserting those rows first).

## Standing product decisions carried in

- **D10/brief 0001 (standing)** — Ian approves every new database table, with
  visible DDL at the /work approval stop. One slice below adds tables
  (`deletion/02`) and says so; its DDL is sketched here so gate 2 sees it too.
- **D12/brief 0001 (standing)** — value before ask. The one user-facing flow
  here (Settings → Delete account) already exists; `deletion/05` changes its
  words and must keep its shape: inline confirmation, no forced step before it,
  the subscription warning never blocks.
- **D7/this brief (standing)** — never an empty value. NULL under `deleted_at`,
  enforced by CHECK; no empty string, no placeholder, no sentinel array.
- **D8/this brief (standing)** — classification lives in the type: `pii.*` and
  `owned.*` domains, never a comment, a name pattern or a list.

## The mechanism in one paragraph (so every slice says the same thing)

An account is closed by RFC 252 (sessions revoked, identities deleted, links
severed, `users.deleted_at` set). N days later (D2, N = 7, one config key) a
periodic sweep enqueues one **erase job per closed account**; the queue worker
runs that job as **one transaction**, retried and dead-lettered like any job:
insert the `deleted_users` / `deleted_students` / `deleted_supporters` rows (D1,
same ids: **D9**); open the declared erasure (the marker every guard arm
checks); walk `pg_constraint` from the three ids, and on every reached row
re-point the live key to its sibling deleted key and call
`soft_delete_entity(table, key_column, key_value)`, which NULLs every `pii.*`
column and stamps `deleted_at`; follow every `owned.*` column forward to the LLM
log rows and erase them the same way; physically delete the short list in
**D10**; enqueue the one confirmation email (D5) as the last statement. The
drift guard — a test that walks the catalog — asserts D3's four rules, so a
table or column added later is caught at the gate, never by the walk missing it.

---

# Slices

### `deletion/01/the-classification`

**Needs:**

- — (nothing: it is the typing every other slice reads, and no slice in this
  brief has to land before it)

**What and why.** Make the schema say which columns are a person and which keys
lead to a person's words, so that erasure can be derived from the catalog
instead of written by hand (D3, "erasure is driven by the schema"; D8,
"classification lives in the type"). Nothing user-facing lands here. This is its
own slice because it touches every account-linked table once, it is the change
the drift guard is built on, and it is the one a later table author will meet
first: a new personal column that is not `pii.*` fails the gate.

**Foundations.** The domain precedent
`db/schema/create-answer-status-domains.sql` and `0054` (one `CREATE DOMAIN`,
every column retyped to it); the catalog-walk test precedent
`NoUrlColumnOutsideExternalDocumentsTest` (pg catalog walk, allowlist,
anti-vacuity probe); the research schema map (`research/schema-map.md`, sections
A, B and E) for the first pass at which columns are personal; the existing
`deleted_at` columns on `users`, `students`, `supporters` and
`supporter_student_links`; `0056`'s note on `search_path` inside functions.

**Decided here.**

- Two new schemas, `pii` and `owned`, holding domains only. A `pii.*` domain per
  base type a personal column has (`pii.text`, `pii.email`, `pii.name`,
  `pii.jsonb`, … — the set is /work's, the schema name is not). One `owned.*`
  domain per forward-owned key type; today that is `owned.llm_request_id`
  (`BIGINT`) on `convo_requests`, `extraction_runs`, `synthesis_runs`,
  `fit_lens_runs` (two columns) and `supporter_llm_costs`.
- Every column that holds a person's data — identity, contact, anything a person
  typed or that quotes them, the LLM log's `system`, `content`, `tools`,
  `params`, `llm_responses.content`, `llm_responses_raw.payload` — is retyped to
  a `pii.*` domain. `NOT NULL` columns become nullable **under D7's CHECK
  only**: `CHECK (deleted_at IS NOT NULL OR <col> IS NOT NULL)` (one CHECK per
  column, or one function over the row; /work's call), so a live row can never
  carry a NULL in a personal column.
- Every table that holds a `pii.*` column and lacks `deleted_at` gains it
  (standing D7), the twelve `*_versions` tables included.
  `supporter_link_requests`' comment that it has none is rewritten, not
  contradicted.
- `email_sends` gains a user key (nullable, because system mail and mail to an
  address with no account exist) so it is no longer keyless (D3's fourth rule).
  The receipt email of D5 is sent to a closed account, so this key is what lets
  the receipt be erased on a later pass or kept by rule — `deletion/04` decides
  which rows it writes.
- **The drift guard**, three of D3's four assertions (the fourth, sibling keys,
  lands with `deletion/02` because it cannot pass before those columns exist):
  every column on a table reachable by FK from `users`, `students` or
  `supporters`, or reached through an `owned.*` column, is `pii.*` or on a short
  **named** not-personal list (ids, timestamps, versions, statuses, money,
  counts, provider identifiers); every FK onto a `pii`-bearing table is
  `owned.*`, a key onto `users`/`students`/`supporters` or their `deleted_*`
  tables, or on a named not-owned list; no table with a `pii.*` column is
  keyless. The lists live in the test, are short, and every entry says why. An
  anti-vacuity probe (a throwaway personal column created and dropped inside the
  test) proves the walk sees columns.

**Left to /work's spec phase.** The exact domain set and names; whether the
format CHECKs on `users.email` and `users.name` move into the domain or stay on
the column; one CHECK per column versus one row function; how `pii.jsonb`
coexists with the array CHECK on `llm_requests.content`; the Kotlin side (the
column types are unchanged at the wire; `NOT NULL` reads on closed rows become
nullable reads — where that forces a model change is /work's).

**Acceptance criteria.**

- **No new table.** A migration per table it retypes, in `db/schema/ORDER`.
- The drift guard is a registered test that **fails today's schema** before the
  migration and passes after it, shown by the test's own history in the RFC. Its
  named lists are asserted non-empty and every entry is justified in a comment
  the test reads back (an entry with no reason fails).
- An INSERT or UPDATE that sets a `pii.*` column NULL on a live row is refused
  by CHECK, proven by test; the same statement on a row with `deleted_at` set
  succeeds.
- No empty string or placeholder anywhere in the change (standing D7); a test
  greps nothing — it inserts a live row with an empty string into one `pii`
  column and expects the existing format CHECK or the domain to refuse it.
- No user-visible change and no door; stated in the RFC as the reason it is not
  the first user-facing beat.

**First-session test.** None — no door.

### `deletion/02/the-deleted-tables`

**Needs:**

- BLOCKS deletion/01/the-classification — the sibling keys satisfy D3's fourth
  drift rule, which is written into the drift-guard test file `deletion/01`
  creates; and the one-of CHECK is the same `deleted_at`-gated shape. One writer
  per file.

**What and why.** Give a deleted account somewhere to be: `deleted_users`,
`deleted_students`, `deleted_supporters` (D1, "a deleted account is a row in
`deleted_users`"), and a sibling key onto them on every table keyed on `users`,
`students` or `supporters`, exactly one of each pair ever set. After this slice
nothing is erased yet, but every surviving row — the subscription, a parent's
conversation about a student, a severed link, a spend row — has a key it can
keep after the person is gone. **ADDS THREE TABLES.**

**Foundations.** The eight FK edges onto `users`, six onto `supporters` and the
`students` edges in 17 migrations (preamble X4);
`subscriptions-key-on-account.sql` (the `ADD COLUMN NULL … SET NOT NULL`
precedent); `convos-party.sql`'s `convos_party_author_check` (the one-of CHECK
shape already in the schema); `account_llm_cost.sql`, `student_llm_cost` (0041)
and `supporter_llm_cost` (the three views that key spend on an account); RFC
252's rebind path in `AccountClosureService` / the subscription verify route.

**Decided here.**

- The three tables, proposed DDL (the approval stop presents the final form):

      CREATE TABLE deleted_users (
        id         UUID        NOT NULL PRIMARY KEY,   -- the former users.id (D9)
        created_at TIMESTAMPTZ NOT NULL,               -- when the account was created
        closed_at  TIMESTAMPTZ NOT NULL,               -- users.deleted_at at close
        erased_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE deleted_students (
        id        UUID NOT NULL PRIMARY KEY,           -- the former students.id
        user_id   UUID NOT NULL REFERENCES deleted_users(id),
        erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE deleted_supporters (
        id        UUID NOT NULL PRIMARY KEY,           -- the former supporters.id
        user_id   UUID NOT NULL REFERENCES deleted_users(id),
        erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );

  No personal column on any of them, so none is `pii`-bearing and the drift
  guard treats a key onto them as not-owned. Append-only: the same
  `prevent_physical_delete` / immutable-update guards as the log tables.
- On every table with a key onto `users`, `students` or `supporters` — the three
  roots' own `user_id` columns included — a sibling column `deleted_<root>_id`
  referencing the deleted table, the live key made nullable, and
  `CHECK ((<live> IS NULL) <> (deleted_<root>_id IS NULL))`. History tables get
  the sibling column too, keyless as their copies already are
  (`subscriptions_versions` precedent).
- `sessions`, `user_auth_identities` and `verification_tokens` are exempt: they
  are the physical-delete list of D10 and never survive to be re-pointed. The
  drift guard's fourth rule names them on its not-keyed list with that reason.
- The three spend views key on `COALESCE(user_id, deleted_user_id)` (or the
  two-arm equivalent /work prefers) so a closed-then-erased account's spend
  stays under the same account id (preamble X5, D9).
- RFC 252's rebind on return clears `deleted_user_id` and sets `user_id` in one
  statement (preamble X8); the one-of CHECK makes a half-update impossible.
- Nothing writes a `deleted_*` row in this slice. The walk (`deletion/03`) is
  the only writer; a test inserts rows directly to prove the keys and CHECKs.

**Left to /work's spec phase.** Whether the three tables are one migration or
three; column naming; whether the sibling column on tables with two roots
(`supporter_student_links`, `supporter_link_requests`, parent-party `convos`) is
two columns or one per root (it is one per root: each root erases on its own
clock); index strategy on the sibling keys.

**Acceptance criteria.**

- **ADDS THREE TABLES — the RFC presents the proposed DDL explicitly at the
  approval stop** (standing D10/brief 0001), not buried in prose.
- The drift guard gains D3's fourth assertion and **passes**: every table keyed
  on a root also keys on its deleted table, the named exemptions only.
- A row with both keys set, or neither, is refused by CHECK, proven on at least
  one table per root by test.
- The three spend views return the same rows for an account before and after its
  `students.user_id` is moved to `deleted_user_id` by a direct UPDATE in the
  test — the first half of D1's "identical spend" claim, before any walk exists.
- A returning subscriber's rebind still passes RFC 252's existing test, and a
  new test rebinds from the deleted key.
- No user-visible change and no door.

**First-session test.** None — no door.

### `deletion/03/the-walk`

**Needs:**

- BLOCKS deletion/01/the-classification — the walk NULLs `pii.*` columns and
  follows `owned.*` columns; without the domains there is nothing to walk.
- BLOCKS deletion/02/the-deleted-tables — the walk inserts the `deleted_*` rows
  and re-points keys to them; the columns must exist.

**What and why.** The erasure itself, derived from the catalog (D3): one
function that erases a row, one walk that finds every row, one arm on every
guard, and the short list of physical deletes (D1, **D10**). It lands with a
door an operator can use today — an admin trigger that erases one closed account
on request (**D12**) — so a GDPR request arriving by email can be honoured
before the sweep exists. This is the slice that makes "deleted" true.

**Foundations.** `prevent_live_user_auth_identities_delete`
(`user-auth-identities-delete-on-closed-account.sql`) — the conditional-guard
precedent, including its EXISTS-not-SELECT-INTO lesson; the six shared and ~12
table-specific guard functions (preamble X3); `pg_constraint` / `pg_attribute` /
`pg_type.typnamespace` for the walk; RFC 100's admin manual triggers
(`POST /user/{id}/delete|undelete` route family) for the door; the research
report `research/closure-as-built-and-purge-infra.md` for the close's exact
state at the end of RFC 252.

**Decided here.**

- `soft_delete_entity(table, key_column, key_value)`: NULLs every column of the
  row whose type is a `pii.*` domain, stamps `deleted_at` if not already set,
  leaves everything else. No column list anywhere; it reads `pg_attribute`.
- The walk: from `users.id`, `students.id`, `supporters.id`, follow every
  inbound FK in `pg_constraint` transitively; on each reached row, if it keys on
  a root, NULL the live key and set the sibling deleted key; then
  `soft_delete_entity` it; then follow each `owned.*` column forward, using the
  constraint's referenced column as the key, and erase those rows (and their own
  FK children: `llm_responses`, `llm_responses_raw`). Rows already erased
  (deleted key set, `deleted_at` set) are skipped, which is what makes a retry
  idempotent.
- The declared erasure: a transaction-local marker only the walk sets; every
  guard function's first statement admits an UPDATE (or one of the D10 deletes)
  when the marker is set **and** the owning root is closed, else behaves exactly
  as today. Mechanism (`SET LOCAL`, a SECURITY DEFINER routine that owns the
  walk, or similar) is /work's; that the arm is one shared predicate called by
  every guard, not a copy per function, is decided here.
- **Physical deletes (D10):** `sessions`, `verification_tokens`,
  `user_auth_identities` (already deleted at close by RFC 252), the account's
  `supporter_invites` (token hashes and an invitee address), and the `jobs` /
  `job_attempts` rows whose payload names the account or its addresses
  (completed, failed and dead-lettered alike). Nothing else is physically
  deleted.
- Orphaned LLM calls (**D11**, answered: kept): a log row no `owned.*` column
  points at is **not** touched by the walk or any sweep. The RFC states this as
  a known limit, in one sentence, where the walk is described.
- The spend test (D1): a fixture account with spend on both the student and the
  supporter side; sum `account_llm_cost` over three date ranges; erase; the
  three sums are identical and the ids they are keyed on are unchanged.
- The admin door: `POST /user/{id}/erase`, admin-only, refused unless the
  account is closed, runs the walk inline for that one account. It exists for
  the operator and for the first production erasure to be watched.

**Left to /work's spec phase.** The marker mechanism; whether the walk is one
plpgsql routine or Kotlin driving per-row SQL; batching for a large account; how
the walk reports what it touched (counts per table are the natural log line);
the exact `jobs.payload` predicate.

**Acceptance criteria.**

- **The door named:** an operator with admin credentials erases one closed
  account with one request; the response reports rows touched per table.
- After the walk on a fixture account that has a row in **every** account-linked
  table (the fixture is built from the catalog, not by hand, so a new table
  joins it automatically): no `pii.*` column on any row reachable from the three
  ids is non-NULL; every row keyed on a root keys on the deleted table; every
  history row is erased; the LLM log rows reached through the owner tables have
  NULL text and unchanged tokens and cost; the D10 rows are gone; everything
  else is present, counted before and after.
- The spend test above passes.
- Running the walk twice on the same account is a no-op the second time
  (idempotency), proven by test.
- Every guard refuses the same erasing UPDATE **outside** the declared erasure,
  and refuses it inside the erasure when the owning root is **not** closed; one
  test per shared guard function, one per table-specific guard.
- A parent's conversation about an erased student still renders for the parent
  with the student's name from its own text (brief 0014 D9), and its
  `deleted_student_id` is set.
- The drift guard still passes; a table added to the test schema with a `TEXT`
  column and a key onto `students` fails it (anti-vacuity, in the test).

**First-session test.** None for a user — the operator's door only.

### `deletion/04/the-sweep`

**Needs:**

- BLOCKS deletion/03/the-walk — the job runs the walk; there is nothing to
  enqueue until it exists.

**What and why.** The promise kept without anyone acting: a periodic job finds
every account closed at least N days ago (D2, N = 7, one config key) and
enqueues one erase job per account; the queue worker runs each as one
transaction with the queue's own retry and dead-letter; the last statement of a
successful run enqueues the one confirmation email (D5, "confirm when done").
This is **the aha of the brief**: Settings → Delete account becomes erasure
end-to-end, and the person is told when it is done.

**Foundations.** `periodic_jobs` + `JobType` + `JobHandler` in `queue-worker`,
the sweep precedent `SynthesisSweepHandler`, the DB-clock-only and
idempotent-handler rules in `cron/INVARIANTS.md` (historical but still the
shape), the HOCON `${?ENV}` knob pattern, the `SEND_EMAIL` job and `email_sends`
(0008, now keyed by `deletion/01`), the LINK_ENDED email of RFC 252 as the
closest email precedent, `ASYNC_WORK.md` (queue-by-default).

**Decided here.**

- Two job types: the periodic sweep (finds and enqueues, never erases) and the
  per-account erase job (one account, one transaction, calls the walk). A
  dead-lettered erase job is an operator alert, not a retry forever; the account
  stays closed and un-erased until an operator re-runs it (the admin door from
  `deletion/03`).
- N is one config key, default 7 days, read through HOCON with an environment
  override; the user-facing copy says 30 (D2) and is not derived from N.
- The confirmation email is enqueued inside the erase transaction (required
  enqueue, `ASYNC_WORK.md`); it is addressed to the address the account had,
  which the walk has by then NULLed on `users` — so the erase job reads the
  address **before** the walk and passes it to the enqueue. The `email_sends`
  row it produces is the deletion receipt (D1) and is keyed on
  `deleted_user_id`, the one row written against the deleted table on purpose.
  The `jobs` row that carried the address is on D10's physical-delete list and
  is reaped by the existing 30-day completed-job reaper; the walk does not wait
  for it.
- The sweep erases accounts only; unowned LLM log rows are left alone (D11,
  answered: kept).

**Left to /work's spec phase.** Cadence of the sweep; batch size per sweep;
whether the erase job carries the address or re-reads it under the marker;
operator visibility (a count in the existing admin surface, or a log line).

**Acceptance criteria.**

- **The door named:** Settings → Delete account (iOS, both roles, already
  shipped by RFC 252) → N days later the account is erased with no operator
  action, and the person receives one email saying so. A test drives the
  periodic job with the DB clock advanced past N and asserts the walk ran and
  the email was enqueued.
- A failed erase job retries and dead-letters by the queue's existing rules; a
  dead-lettered one leaves the account closed, un-erased, with every guard
  intact, and the admin door erases it on re-run.
- The sweep never enqueues an account twice (idempotent on the deleted row's
  existence), and never enqueues an account closed fewer than N days.
- The confirmation email's `email_sends` row survives the walk, keyed on
  `deleted_user_id`, with its body intact — it is the receipt (D1).
- Value before ask (standing D12/brief 0001): nothing new is asked of the
  person; the only new touch is the one email.

**First-session test.** A user who deletes their account on day 1 of a test
environment with N set to 0 and the sweep run by hand receives the confirmation
email in that session.

### `deletion/05/the-words`

**Needs:**

- PREFER deletion/04/the-sweep — the copy promises erasure within 30 days and a
  confirmation email; both could be written first, but a promise with no
  mechanism behind it is exactly what the App Store review article refuses.
  Overridable by Ian if the review submission needs the copy earlier.

**What and why.** Say "deleted", say what is kept, in every place a reviewer or
a person reads it (D5): the in-app confirmation before the delete, the email
after the erasure, the privacy page. Also the two privacy-page fixes the
research found: the one sentence for minors (Cal. B&P 22581) and the stale
"essay content" line.

**Foundations.** RFC 252's iOS delete flow (Settings → Delete account, inline
confirmation, billing notice, manage-subscription control), its LINK_ENDED and
closed-account copy, the login screen's closed line and Apple's manual route
(TN3194), `/privacy` on public-web, `PRODUCT.md` for voice, the research report
`research/app-store-and-legal-erasure.md` for the exact sentences Apple's
support article asks for.

**Decided here.**

- The confirmation copy says: your account is deleted now and your data is
  erased within 30 days; what we keep: a record that an account existed and when
  it was deleted, your subscription record with Apple, and our usage ledger with
  your words removed; and the one line that our AI provider holds conversation
  text for up to 30 days with no account attached.
- The email says erasure is complete, names what was kept in the same words, and
  has no link (there is no account to link to).
- `/privacy` gains the minors sentence, loses "essay content", and points at the
  door it already points at.
- No export, no "download my data" (D5).

**Left to /work's spec phase.** Exact wording within the above; where the
provider-retention sentence sits (confirmation screen or privacy page or both).

**Acceptance criteria.**

- **The door named:** Settings → Delete account shows the new confirmation; the
  email arrives after erasure (`deletion/04`); `/privacy` carries the new
  sentences.
- Value before ask (standing D12/brief 0001): the confirmation is one screen,
  cancel is as easy as confirm, the subscription warning informs and never
  blocks, no survey, no reason required.
- Every sentence the Apple support article asks for is present, quoted in the
  RFC beside the screen it lives on.
- Copy reflects `PRODUCT.md`.

**First-session test.** A new user who opens Settings → Delete account in their
first session reads the new confirmation; nothing else about the session
changes.

### `deletion/06/the-apple-refresh-token`

**Needs:**

- — (nothing technical; it is parked by D6)

**Status:** DEFERRED

**What and why.** D6's follow-on: exchange the Sign in with Apple authorization
code at sign-in, store the refresh token (a `pii.*` column, erased with the
account), and call Apple's revoke at close for accounts created after it lands,
so the manual TN3194 route stops being the only one. Scheduled if App Store
review asks for the call, or when the backlog reaches it.

**Foundations.** RFC 111 / 113 (Apple SSO, JWKS-only validation, no token
stored), `user_auth_identities` (0018), the research report's TN3194 reading.

**Decided here / left to /work.** Everything is /work's once scheduled; D6 fixes
only the outcome.

**Acceptance criteria.** Written when scheduled.

**First-session test.** Written when scheduled.

### `deletion/07/start-over`

**Needs:**

- BLOCKS deletion/03/the-walk — reset is the walk with the identity rows as the
  cut point (D4); it reuses the walk's function and marker.

**Status:** DEFERRED

**What and why.** D4's follow-on: keep the identity, erase the coaching state,
dev-only first. Scheduled when a clean first-session test needs it more than a
throwaway account does.

**Foundations.** `deletion/03`.

**Decided here / left to /work.** Everything is /work's once scheduled; D4 fixes
only the outcome and the order (dev-only before any user door).

**Acceptance criteria.** Written when scheduled.

**First-session test.** Written when scheduled.

---

# Gate 2 — decisions D9–D12 (defaults pre-chosen)

Numbering continues from the brief (D1–D8). Options are outcomes; the default is
taken on silence.

#### D9 — the deleted id is the old id

- **Option A (recommended, default) — `deleted_users.id` IS the former
  `users.id`; likewise students and supporters.** Spend keyed on the account id
  is identical before and after by construction; RFC 252's rebind and every log
  line that ever named the id still mean the same account; no mapping exists to
  erase. The id is a random UUID that identifies nothing on its own.
- **Option B — a fresh id with a mapping column back to the old one.** The
  mapping is itself a link from a person's former identity to their records and
  would have to be erased, which puts us back at A with an extra table.
- **Option C — a fresh id and no mapping.** Spend under the old id disappears
  from the per-account view; D1's "identical spend" test cannot be written.

Recommend A. Cost of being wrong: the primary key of three tables — pick once.

#### D10 — what is physically deleted

- **Option A (recommended, default) — five kinds of row and nothing else:**
  `sessions`, `verification_tokens`, `user_auth_identities` (already at close),
  the account's `supporter_invites`, and the `jobs` / `job_attempts` rows whose
  payload names the account or its addresses (dead-lettered included). Each is a
  credential, a token or a generic payload we must never hold again and cannot
  classify with a `pii` domain. `email_sends` is **not** on the list: it gains a
  key (`deletion/01`) and is erased in place like every other row, so the
  deletion receipt can be the one row kept by rule.
- **Option B — also delete `email_sends` by address.** Erases the receipt with
  everything else (Apple's "confirmation when complete" then has no record on
  our side) and deletes by a text match rather than a key, which the drift guard
  cannot see.
- **Option C — delete nothing; make `jobs.payload` a `pii` column.** The payload
  is a generic JSONB every job type shares; NULLing it on a completed SEND_EMAIL
  job is fine, but the column cannot be `pii` for some job types and not others,
  and a dead-lettered job with a NULL payload cannot be re-run.

Recommend A. Cost of being wrong: low — the list is in one place in the walk,
and the drift guard names each entry with its reason.

#### D11 — orphaned LLM calls age out

- **Option A (recommended, default) — a log row no owner points at is erased
  (text NULL, `deleted_at` set) once older than the erasure window, by the same
  sweep.** Orphans are routine (a rejected, errored or transient call writes no
  run row) and may hold a person's words with no key to find them by; age is the
  only predicate that reaches them, and the window is the same N days.
- **Option B — leave orphans alone.** A person's words can sit in an unowned log
  row indefinitely; the drift guard cannot see them because nothing keys them.
  Honest about what it does not erase, and simpler.
- **Option C — stop writing orphans: every call gets an owner row.** The right
  long-term fix, and a change to four call sites that is not this beat.

Recommend A, with C recorded as a backlog item. Cost of being wrong: none that
is irreversible — an orphan's text has no owner to want it back.

#### D12 — an operator can erase one account on request before the sweep lands

- **Option A (recommended, default) — `deletion/03` ships an admin-only
  `POST /user/{id}/erase` that runs the walk for one closed account.** A GDPR
  request by email can be honoured the day the walk lands; the first production
  erasure is watched by a person; a dead-lettered erase job has a re-run door.
- **Option B — no admin door; the sweep (`deletion/04`) is the only caller.**
  One fewer surface; the walk is unreachable until the next slice; a
  dead-lettered job needs a `psql` session to re-run.

Recommend A. Cost of being wrong: an admin route, cheap to remove.

---

# Wave board (computed from `Needs:`; `slice-board` is the answer when they disagree)

| Wave | Slices                                                      | Why here                                                   |
| ---- | ----------------------------------------------------------- | ---------------------------------------------------------- |
| 1    | `deletion/01/the-classification`                            | Needs nothing.                                             |
| 1    | `deletion/05/the-words` (PREFER `deletion/04` — Ian's call) | Could start now; we would rather the mechanism land first. |
| 2    | `deletion/02/the-deleted-tables`                            | After 01.                                                  |
| 3    | `deletion/03/the-walk`                                      | After 01 and 02.                                           |
| 4    | `deletion/04/the-sweep` — **the aha**                       | After 03.                                                  |
| —    | `deletion/06`, `deletion/07`                                | DEFERRED (D6, D4).                                         |

Four waves of one slice each is the honest shape: this brief is a chain, and
nothing user-visible exists until the last link. The copy slice is the only
parallel work.

---

# Gate 2 answered 2026-10-31 (Ian, verbatim)

> D11: Option B, keep them

D9, D10 and D12 were not addressed and take their defaults, as each block
states.

- **D9 — the deleted id is the old id.** Default A: `deleted_users.id` is the
  former `users.id`; likewise students and supporters.
- **D10 — what is physically deleted.** Default A: `sessions`,
  `verification_tokens`, `user_auth_identities`, the account's
  `supporter_invites`, and the `jobs` / `job_attempts` rows whose payload names
  the account; `email_sends` is erased in place, never deleted.
- **D11 — orphaned LLM calls are kept.** Amended to B, as quoted: a log row no
  `owned.*` column points at is not erased by the walk or by any sweep. Option C
  (every call gets an owner row) stays a backlog item in `STATUS.md`.
- **D12 — an operator can erase one account on request.** Default A:
  `deletion/03` ships admin-only `POST /user/{id}/erase` for a closed account.

The slices above were amended in place for D11 before this file was committed
(`deletion/03` and `deletion/04` no longer mention an orphan sweep); the
decisions are immutable from here.
