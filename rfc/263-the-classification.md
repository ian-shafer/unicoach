# RFC 263 — The classification

**Slice:** `deletion/01/the-classification` (brief 0002, _account erasure_).
**Status:** proposed. **Base:** `main@4689b517`. **Lane:** rfc.

## Executive Summary

Erasure (brief 0002) is to be driven by the schema, not written by hand: gate-1
decision D3 says the walk that erases an account reads the catalog, and D8 says
whether a column is personal "lives in the type". This RFC makes the schema say
it.

Two new schemas hold domains only. `pii.*` types every column that holds a
person's data. `owned.llm_request_id` types the six keys that lead from an owner
row to that person's words in the LLM log. Every personal column that was
`NOT NULL` becomes nullable under one CHECK each, so a live row still cannot
hold a NULL there (standing decision D7). Every table holding a personal column
gains `deleted_at` if it lacks one. `email_sends` gains a user key so it is no
longer keyless.

A drift-guard test walks the catalog and asserts three of D3's four rules. A
column added later to an account-linked table that is neither `pii.*` nor named
as not personal fails the suite by name. The fourth rule, sibling keys onto the
`deleted_*` tables, lands with `deletion/02`.

Nothing user-facing changes, and there is no door: no user reaches this slice.
It is not the first user-facing beat of the brief because it only classifies;
`deletion/04` (the sweep) is.

## Detailed Design

### 1. What the code says, where it differs from the spec

The spec (`product/0002-account-data-deletion/spec.md`, preamble X2) says
`deleted_at` exists on four live tables. The live schema has it on eleven
(`users`, `students`, `supporters`, `supporter_student_links`, `convos`,
`college_list_entries`, `money_profiles`, `admission_test_scores`,
`intended_subjects`, `intended_subject_status`, `sharing_permissions`) and on
every one of their `*_versions` tables. So no `*_versions` table gains a column
here: every one that will hold a `pii.*` column already has `deleted_at`.

The spec expects `supporter_link_requests`' "no `deleted_at`" comment to be
rewritten. Under §3 that table holds no personal column, so it gains no
`deleted_at` and the comment stays true; it is not touched.

The account reach (§7) is 43 tables today, plus the three LLM log tables reached
through `owned.*` and `email_sends` once it is keyed.

### 2. The schemas and domains

One migration creates the two schemas and seven domains. A domain here
classifies and constrains nothing: no `CHECK`, no `NOT NULL`, no `DEFAULT`.

```sql
CREATE SCHEMA pii;    -- domains only: a column of one of these types holds a person's data
CREATE SCHEMA owned;  -- domains only: a key of one of these types leads to a person's data

CREATE DOMAIN pii.text    AS TEXT;
CREATE DOMAIN pii.jsonb   AS JSONB;
CREATE DOMAIN pii.date    AS DATE;
CREATE DOMAIN pii.integer AS INTEGER;
CREATE DOMAIN pii.uuid    AS UUID;
CREATE DOMAIN pii.slug    AS public.slug;   -- keeps slug's own CHECK

CREATE DOMAIN owned.llm_request_id AS BIGINT;
```

One `pii` domain per base type, not per meaning (`pii.email`, `pii.name`): the
format rules on `users.email`, `users.name` and `user_auth_identities.email`
**stay on their columns**. They differ per column today (`users.email` has a
trim rule `user_auth_identities.email` lacks), so moving them into a domain
would need one domain per column and gain no guarantee. `pii.slug` is over
`public.slug`, not TEXT, so `intended_subjects.subject_slug` keeps the slug
format its foreign key to `subjects` relies on.

Every reference to `pii` or `owned` inside a function body is schema-qualified
(spec preamble X1, the `0056` `search_path` note). Neither schema needs a
`GRANT`: a role reads and writes a domain-typed column without `USAGE` on the
domain's schema, and the domains keep PostgreSQL's default `USAGE` for `PUBLIC`.
Step 2 of the plan proves this under the app role rather than asserting it.

### 3. Which columns are personal

**The rule.** A column is personal when, on a row whose owner is erased, its
value would still say something the person told us, or something we wrote about
them, beyond the fact that the row existed and when. That is the line gate-1
decision D5 already promised a user: what stays is "a record that the account
existed and when it was deleted, your subscription record with Apple, and our
usage ledger with your words removed".

So a structured answer the person gave is personal (their income band, their
test score, which college is on their list and how it went). A value from our
own fixed vocabulary that classifies a row is not (a claim's `topic`, a
commitment's `lens`, an answer's `answered`/`declined` state), and neither are
keys, lifecycle timestamps, versions, counts, money, token hashes and provider
identifiers.

**REQUIRES ARCHITECT DECISION — D-A, how wide "personal" is.** Recommended (A):
the rule above. (B) narrow: free text, identity, contact, credentials, network
identifiers and birth date only; structured answers (bands, scores, college ids,
statuses) stay on the erased row. B touches about twenty fewer columns but makes
D5's "what stays" sentence untrue.

| Table (and its `_versions`) | Retyped to `pii.*`                                                         | Gains `deleted_at` |
| --------------------------- | -------------------------------------------------------------------------- | ------------------ |
| `users`                     | `email`, `name`, `display_name`, `password_hash` → `pii.text`              | —                  |
| `sessions`                  | `user_agent`, `initial_ip` → `pii.text`; `metadata` → `pii.jsonb`          | — (hard-deleted)   |
| `user_auth_identities`      | `subject` (the provider's id for the person), `email` → `pii.text`         | — (hard-deleted)   |
| `email_sends`               | `recipient_email`, `subject`, `body`, `error_message` → `pii.text`         | yes                |
| `students`                  | `date_of_birth`, `expected_high_school_graduation_on` → `pii.date`         | —                  |
| `convos`                    | `name` (set from the first turn) → `pii.text`                              | —                  |
| `observations`              | `quote` → `pii.text`                                                       | yes                |
| `claims`                    | `statement` → `pii.text`                                                   | yes                |
| `commitments`               | `statement`, `drop_reason` → `pii.text`                                    | yes                |
| `fit_suggestions`           | `rationale` → `pii.text`; `college_id` → `pii.uuid`                        | yes                |
| `extraction_runs`           | `failure_reason` (may quote model output) → `pii.text`                     | yes                |
| `synthesis_runs`            | `failure_reason` → `pii.text`                                              | yes                |
| `fit_lens_runs`             | `failure_reason` → `pii.text`                                              | yes                |
| `llm_requests`              | `system` → `pii.text`; `content`, `tools`, `params` → `pii.jsonb`          | yes                |
| `llm_responses`             | `content` → `pii.jsonb`; `reason` → `pii.text`                             | yes                |
| `llm_responses_raw`         | `payload` → `pii.jsonb`                                                    | yes                |
| `college_list_entries`      | `college_id` → `pii.uuid`; `status`, `reasons`, `living_plan` → `pii.text` | —                  |
| `money_profiles`            | `income_band`, `residency_state`, `living_plan`, `dependency` → `pii.text` | —                  |
| `admission_test_scores`     | `score` → `pii.integer`; `taken_on` → `pii.date`                           | —                  |
| `intended_subjects`         | `subject_slug` → `pii.slug`                                                | —                  |

Not personal, by the rule, and named in the drift guard's list (§5) with a
reason: `llm_requests.tool_choice` (names one of our tools),
`llm_responses.model_resolved`/`stop_reason`/`provider_request_id`,
`email_sends.sender_email` (our address), `user_auth_identities.email_verified`,
`users.is_admin`, the `*_status`/`*_precision` columns beside a personal value,
`admission_test_scores.test`/`component`, `supporter_student_links.relationship`
and `supporter_invites.relationship` (the link's kind, kept for the other party
under brief 0014 D9), the subscription's Apple identifiers and periods (kept
under gate-1 decision D6), and every token hash.

**Hard-deleted tables keep their shape.** Gate-2 decision D10 physically deletes
the account's `sessions` and `user_auth_identities` rows, so they are never
erased in place: their personal columns are typed `pii.*` (they are personal)
but keep `NOT NULL`, gain no `deleted_at` and no §4 CHECK. This narrows standing
D7's "tables holding personal data gain `deleted_at`" to the tables erased in
place (Ian, approval stop, 2026-10-31). It also makes the catalog say which way
a table goes: a table with a `pii.*` column and no `deleted_at` is deleted, one
with `deleted_at` is erased. `deletion/03` may read that instead of a list.

`*_versions` tables are retyped in the same migration as their live table, so a
history row is never typed differently from the row it copies (the
`create-partial-date-storage.sql` precedent: "a half-migrated pair has no valid
reader").

### 4. Presence while live (D7)

**Every `pii.*` column is nullable.** A `pii.*` column that was `NOT NULL` gets
one CHECK per column, on the live table and on its `_versions` table:

```sql
CONSTRAINT <table>_<column>_present_while_live_check
    CHECK (deleted_at IS NOT NULL OR <column> IS NOT NULL)
```

One CHECK per column, not one row function: a row function would have to name
its columns, which is the column list D3 forbids, and a per-column CHECK names
the column in its refusal.

`<table>` is the live table's name on both tables of a family: constraint names
are per table, and `<table>_versions_<column>_…` overruns PostgreSQL's 63-byte
identifier limit on several families. For the same reason the graduation date's
CHECK is `students_graduation_on_present_while_live_check`.

**An existing CHECK that would refuse the erased state gains one arm.** Nine
rules tie a personal value's presence to another column. Each becomes
`(deleted_at IS NOT NULL AND <column> IS NULL) OR <rule as today>`: the rule
with only the personal column's presence freed, so a deleted row may lose the
value and nothing else changes:

| Constraint                                                                                      | Personal column  |
| ----------------------------------------------------------------------------------------------- | ---------------- |
| `money_profiles_{income_band,residency,living_plan,dependency}_value_iff_answered_check` (four) | the four answers |
| `llm_responses_completed_content_check`                                                         | `content`        |
| `llm_responses_reason_presence_check`                                                           | `reason`         |
| `{extraction,synthesis,fit_lens}_runs_failure_pairing_check` (three)                            | `failure_reason` |

`fit_lens_runs_failure_consistency_check` tied the outcome to the category and
the reason together, so an arm on it would also free the outcome/category tie.
It is restated as `(outcome = 'failed') = (failure_category IS NOT NULL)`, the
form `extraction_runs` and `synthesis_runs` already use. It names no personal
column, so it needs no arm and keeps the outcome/category tie on a deleted row;
on a live row it admits what the old rule did, since the pairing rule ties the
reason to the category.

Every other CHECK on a personal column already admits NULL (a length, format or
`IN` test on NULL is not false). The constraint names are kept, so any code that
matches a constraint name still matches.

No empty string, placeholder or sentinel is written anywhere (standing D7). The
existing not-empty CHECKs stay and keep refusing `''` on a live row.

### 5. Owned keys

The six columns that lead from an owner row to an LLM call are retyped to
`owned.llm_request_id`:

| Table                 | Column(s)                                       |
| --------------------- | ----------------------------------------------- |
| `convo_requests`      | `llm_request_id`                                |
| `extraction_runs`     | `llm_request_id`                                |
| `synthesis_runs`      | `llm_request_id`                                |
| `fit_lens_runs`       | `query_llm_request_id`, `reason_llm_request_id` |
| `supporter_llm_costs` | `llm_request_id`                                |

`student_llm_cost`, `supporter_llm_cost` and `account_llm_cost` read these
columns, and PostgreSQL refuses `ALTER COLUMN TYPE` under a view. So this is one
migration across five tables, not five: it drops the three views, retypes, and
recreates each from its current definition (`convos-party.sql`,
`supporter-llm-cost.sql`, `account-llm-cost.sql`), unchanged. The stored
definition now shows the domain coerced to `bigint` in each join
(`pg_get_viewdef`); the rows are the same.

### 6. `email_sends` gets an account key

```sql
ALTER TABLE email_sends
    ADD COLUMN user_id UUID NULL REFERENCES users(id);
```

NULL means the mail was not sent to an account. No template does that today, but
the job payload is documented as serving "recipients who are not users"
(`EmailJobPayload.kt:11-14`), so the key stays nullable: a send to an address
with no account is recorded with a NULL `user_id`. Account erasure never reaches
such a row, because no account owns it; erasing it for someone who never had an
account is a by-address request outside this brief. `deletion/04` decides
whether the erasure receipt is itself erased; this slice only makes it possible.

**Writers.** All three templates go to an account's address, and the account's
id is in hand where each is enqueued: `EMAIL_VERIFICATION`
(`EmailVerificationService.enqueue`, called from `AuthService.register`,
`AuthService.changeEmail` and `EmailVerificationService.resend`), `LINK_ENDED`
(`LinkEndedNotice.tell`, the recipient `User`) and `LINK_REQUESTED`
(`LinkRequestedNotice`, the student's `User`). The id travels:

```kotlin
data class EmailJobPayload(
    val to: String,
    val template: EmailTemplate,
    val context: JsonObject,
    val userId: String?,   // the account the mail is sent to; null for a non-account recipient
)
```

`EmailService.send` gains a `userId: UserId?` parameter and `EmailSendsDao`'s
insert takes it on `NewEmailSend`, and the worker passes the payload's value
through without a lookup (the worker does no domain lookups,
`EmailJobPayload.kt`). A `userId` that is not a UUID is a poison message: the
worker returns a permanent failure (`InvalidRecipientAccountException`) rather
than log mail erasure could not reach. `userId` has no default, so every
producer states it and omitting it does not compile. A job already queued before
the deploy has no `userId` key; `EmailSendHandler` decodes with its own
`Json { explicitNulls = false }`, which reads the missing key as null, so the
job is sent and logged with a NULL key, which the backfill below cannot reach, a
window of the queue's depth at deploy time.

So a NULL `user_id` means "no account is known to own this send", reached three
ways the row does not tell apart: a non-account recipient, a job queued before
the deploy, or a pre-existing send the backfill below matched to no account or
to more than one. The migration states all three on the column, and reports with
`RAISE NOTICE` how many sends the backfill keyed and how many it left NULL, and
why, so the deploy log carries the count.

**REQUIRES ARCHITECT DECISION — D-B, existing rows.** Recommended (A): the
migration backfills `user_id` where `recipient_email` matches the email of
exactly one account across `users` and `users_versions` (so mail sent before an
email change is found), and leaves it NULL where no account or more than one
matches. The table's update guard is disabled for that one statement, as `0035`
and `create-partial-date-storage.sql` already do. (B) no backfill: every mail
sent before this lands is out of reach of erasure for good.

### 7. The drift guard

`ErasureClassificationTest` in `:db` walks the live catalog. It holds three
rules, each asserted by name with the offending columns listed.

**The reach.** Seeds `users`, `students`, `supporters`. A table is in the reach
when it has a foreign key onto a table in the reach (child-ward), or an
`owned.*` column of a table in the reach references it (forward). One recursive
query, in the test.

1. **Every column is classified.** Every column of every table in the reach is
   of a `pii.*` type, or is on `NOT_PERSONAL` as `table.column`.
2. **Every key to a person's words is owned.** Every foreign key from a table
   reached child-ward onto a table that has a `pii.*` column and is **not**
   reached child-ward is of an `owned.*` type, or is on `NOT_OWNED` as
   `table.column`. "Reached child-ward" means from a seed by child-ward steps
   alone. A key inside the forward-reached log (`llm_responses.request_id`)
   points back up the path the walk came down, at a row already walked, so it is
   not checked.
3. **No personal column is out of reach.** Every table with a `pii.*` column is
   in the reach. This is D3's "no table holding a person's data is keyless",
   stated as what erasure needs: not "has a key" but "the walk gets there".

**REQUIRES ARCHITECT DECISION — D-C, which keys rule 2 checks.** Recommended
(A): as written, only keys onto personal tables the walk does not already reach
child-ward. A key the walk already follows (`claim_support.claim_id`,
`observations.convo_id`, every `_versions.id`) needs no annotation, so
`NOT_OWNED` starts **empty** and the acceptance criterion "its named lists are
asserted non-empty" applies to `NOT_PERSONAL` only; the probe in test 6 proves
rule 2 can fail. (B) the slice's literal rule: every foreign key onto a personal
table that is not `owned.*` or an account key goes on `NOT_OWNED`. About twenty
entries, each with the same reason ("the walk reaches it child-ward"), copied by
every future table author.

**The lists live in the test file**, as `Map<String, String>` constants from
`table.column` to its reason (the `permitted` map of
`NoUrlColumnOutsideExternalDocumentsTest`). Nothing in production reads them:
erasure reads only the types. `NOT_PERSONAL` is keyed by `table.column` (Ian,
approval stop, 2026-10-31), so every column a new table adds needs its own
entry, and a personal column that reuses an exempt name cannot pass. It is not
short — about 400 entries across 47 tables — so it is grouped by table with one
reason per kind of column (keys, lifecycle timestamps, versions, our own
vocabularies, counts and money, provider and model identifiers, token hashes,
answer states, values kept for another party).

A stale entry fails too: a `table.column` no longer in the reach, or a
`NOT_OWNED` entry that is no longer a key onto a personal table, must be deleted
(the `NoUrlColumnOutsideExternalDocumentsTest` discipline).

### 8. Kotlin

No model changes. The wire types are unchanged (a domain over TEXT reads and
binds as TEXT; over JSONB as JSONB). A personal column read as non-null stays
non-null in Kotlin, because §4 makes a NULL impossible on a live row and no code
in this slice writes one. `deletion/03` is the first change that writes a NULL,
so it owns every read path that can meet an erased row.

Two shell assertions match PostgreSQL's NOT NULL wording on `convos.name`
(`bin/db-convos-tests`: `test_convos_minimal_insert_no_name_rejected`,
`test_convos_name_null_rejected`); they move to the new CHECK's name.
`EmailSendsDaoTest`'s "omitting a NOT NULL column is rejected" asserts only that
the insert fails, which the CHECK still makes true; its name is corrected. No
main code maps SQLState 23502.

### 9. Cost

Retyping a column to a domain rewrites its table (measured in
`create-answer-status-domains.sql`). Every table in §3 is rewritten once, inside
its migration's transaction, holding an `ACCESS EXCLUSIVE` lock for the rewrite.
The largest are the LLM log tables. The deploy runs migrations before the new
code starts, so the lock is held while the old code is still serving.

### Reachability

None. No user, screen, chat tool or endpoint changes.

### Schema

**No new table.** New: two schemas, seven domains. Changed: 60 personal columns
retyped across 20 table families (live and `_versions`); 31 of them are
`NOT NULL` today, and 29 of those are replaced by a CHECK each (the two on
hard-deleted `user_auth_identities` keep `NOT NULL`); six owned keys retyped; 11
tables gain `deleted_at`; `email_sends` gains `user_id`; nine CHECKs gain an
erased arm and one is restated; three views recreated unchanged. Migrations: one
for the schemas, one per table family in §3 (`classify-<table>.sql`;
`email_sends`' is `classify-email-sends.sql` and also carries the key and
backfill), one for the owned keys; each listed in `db/schema/ORDER` after
`convos-party.sql`'s successors.

## Tests

All in `:db`, against the real database.

**`ErasureClassificationTest`** (§7):

1. `every column in the erasure reach is personal or named not personal` — rule
   1; the message lists `table.column` for each offender.
2. `every key onto a person's words is owned or named not owned` — rule 2.
3. `every table with a personal column is in the erasure reach` — rule 3.
4. `the named lists give a reason for every entry` — `NOT_PERSONAL` is
   non-empty; every reason in both lists is non-blank.
5. `no named entry is stale` — every `NOT_PERSONAL` entry is a column in the
   reach; every `NOT_OWNED` entry is a key onto a personal table.
6. `each rule can fail, proven inside a rolled-back transaction` — in one
   transaction that always rolls back:
   `ALTER TABLE claims ADD COLUMN
   probe_note TEXT` makes rule 1 name
   `claims.probe_note`;
   `CREATE TABLE
   probe_owner (student_id UUID REFERENCES students(id), call_id BIGINT
   REFERENCES llm_requests(id))`
   makes rule 2 name `probe_owner.call_id`;
   `CREATE TABLE probe_keyless (note pii.text)` makes rule 3 name
   `probe_keyless`. After rollback none of the three is reported.
7. `the reach follows owned keys` — anti-vacuity by identity: the reach contains
   `users`, `claims`, `llm_requests` and `llm_responses_raw` (the last two only
   through `owned.*`).

**History.** Tests 1–3 ran against `main@4689b517` before any migration
(`bin/test db -t "ed.unicoach.db.ErasureClassificationTest"`) and failed on
classification, not on a SQL error:

- `every column in the erasure reach is personal or named not personal` — 49
  offenders: every column §3 retypes on the 43 tables reached child-ward
  (`email_sends` and the three LLM log tables are not in the reach yet).
- `every key onto a person's words is owned or named not owned` — inspected 0
  keys, because no table is personal yet, so its anti-vacuity check failed:
  `convo_requests.llm_request_id` was not seen.
- `every table with a personal column is in the erasure reach` — saw 0 personal
  tables, so its anti-vacuity check failed: `users` and `llm_responses_raw` were
  not seen.

Of the rest:

- `no named entry is stale` failed with 41 `NOT_PERSONAL` entries on columns not
  yet in the reach (`email_sends`, the LLM log, and seven new `deleted_at`
  columns).
- `each rule can fail` failed with a SQL error: schema `pii` does not exist.
- `the reach follows owned keys` failed: `llm_requests` was not in the reach.
- `the named lists give a reason for every entry` passed.

**`PersonalColumnPresenceTest`** (§4):

8. `a live user cannot lose their name` — `UPDATE users SET name = NULL` on a
   live row is refused by `users_name_present_while_live_check` (SQLState
   23514).
9. `a deleted user can lose every personal column` — set `deleted_at`, then NULL
   `email`, `name`, `display_name`, `password_hash`: succeeds, and the
   `users_versions` row the update wrote has the same NULLs and `deleted_at`
   (the history table's CHECK admits it).
10. `a live user cannot be given an empty email` — an INSERT with `email = ''`
    is refused by a CHECK (PostgreSQL names `users_email_format_check`, the
    first of the email rules it evaluates), and one with `name = ''` by
    `users_name_not_empty_check`.
11. `a completed response keeps its content while live, and loses it when
    deleted`
    — INSERT `llm_responses` with `outcome = 'completed'`, `content = NULL`:
    refused while `deleted_at IS NULL`, accepted with `deleted_at` set. INSERT,
    not UPDATE, so the log's update guard is not under test.
12. `an answered money question keeps its answer while live, and loses it when
    deleted`
    — the same pair on `money_profiles` with `income_band_status = 'answered'`,
    `income_band = NULL`.
13. `a failed run keeps its reason while live, and loses it when deleted` — the
    same pair on `fit_lens_runs` with `outcome = 'failed'`,
    `failure_reason = NULL`.

    `a deleted run that lost its reason still ties its outcome to its category`
    — a deleted `fit_lens_runs` row with no reason is refused by
    `fit_lens_runs_failure_consistency_check` both as `failed` with no category
    and as `applied` with one.

**`EmailSendsDaoTest`** (`:email`) and **`EmailSendHandlerTest`**:

14. `a send recorded for an account carries its user id` — the DAO stores the
    given `userId`; a send with none stores NULL.
15. `a queued job without a user id is still sent` — a payload JSON with no
    `userId` field deserializes and is logged with a NULL key.

    A job whose `userId` is not a UUID returns `JobResult.PermanentFailure`
    naming the id; nothing is sent and no `email_sends` row is written.

**Enqueue sites** (`:service`, beside each service's existing tests):

16. `the verification, link-ended and link-requested jobs name the recipient's
    account`
    — each enqueued payload's `userId` is the recipient's `users.id`.

## Implementation Plan

1. **Test first.** Write `ErasureClassificationTest` (tests 1–7) against the
   base schema. Run it and record the failures under Tests → History. The
   `pii`/`owned` names it queries do not exist yet; tests 1–3 must fail on
   classification, not on a SQL error. Verify:
   `nix develop -c bin/test db -t "ed.unicoach.db.ErasureClassificationTest"`.
2. **Schemas and domains.** `create-pii-and-owned-domains.sql` and its `ORDER`
   line. Verify: `nix develop -c bin/test` (the suite runs as the app role, so a
   missing grant shows here).
3. **One migration per table family**, in §3's order, each: retype the personal
   columns on the table and its `_versions`, drop their `NOT NULL`, add the §4
   CHECKs, add `deleted_at` where §3 says, add the erased arm to that table's
   constraints in §4. Run `nix develop -c bin/test` after every few migrations;
   fix any test that asserted a NOT NULL violation (§8).
4. **Owned keys.** `classify-owned-llm-request-keys.sql` (§5). Verify the three
   views return the same rows: `account_llm_cost` before and after on a seeded
   database, by `bin/db-query`.
5. **`email_sends`.** The key, `deleted_at` and the backfill (if D-B is A) in
   `classify-email-sends.sql`; then the payload field, `EmailService`, the DAO
   and the three enqueue sites (§6), with tests 14–16. Verify:
   `nix develop -c bin/test`.
6. **Presence tests** 8–13. Fix the two `bin/db-convos-tests` assertions (§8).
   Verify: `nix develop -c bin/test` (its db stage runs `bin/db-convos-tests`).
7. **Full suite.** `nix develop -c bin/test`, unscoped. The drift guard passes.

## Files Modified

- `db/schema/` — `create-pii-and-owned-domains.sql`, one `classify-<table>.sql`
  per table family in §3, `classify-owned-llm-request-keys.sql`,
  `classify-email-sends.sql` (all new); `ORDER`
- `db/src/test/kotlin/ed/unicoach/db/` — `ErasureClassificationTest.kt`,
  `PersonalColumnPresenceTest.kt` (new)
- `email/src/main/kotlin/ed/unicoach/email/` — `EmailJobPayload.kt`,
  `EmailService.kt`, `EmailSendHandler.kt`, `EmailExceptions.kt`,
  `dao/EmailSendsDao.kt`, `dao/NewEmailSend.kt`
- `service/src/main/kotlin/ed/unicoach/auth/` — `EmailVerificationService.kt`,
  `AuthService.kt`
- `service/src/main/kotlin/ed/unicoach/family/` — `LinkEndedNotice.kt`,
  `LinkRequestedNotice.kt`
- `bin/db-convos-tests` (two assertions, §8)
- Tests beside each module, as listed under Tests.
