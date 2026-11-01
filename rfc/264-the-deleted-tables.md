# RFC 264 — The deleted tables

**Slice:** `deletion/02/the-deleted-tables` (brief 0002, _account erasure_).
**Status:** proposed. **Base:** `main@02204093`. **Lane:** rfc.

## Executive Summary

Gate-1 decision D1 says a deleted account is a row in `deleted_users` (and
`deleted_students`, `deleted_supporters`), and that every key onto a person
gains a sibling key onto the deleted table, exactly one of the pair ever set.
This RFC adds the three tables and the sibling keys. It erases nothing: the walk
(`deletion/03`) is the only writer of a `deleted_*` row or a sibling key.

After it lands, every row that is to outlive the person — the subscription, a
parent's conversation about a student, a severed link, a spend row — has a key
it can keep once the person is gone. The three spend views read the
live-or-deleted pair, so an account's spend stays under the same id across
erasure (D9). The returning-subscriber rebind of RFC 252 also rebinds from the
deleted key. The drift guard gains D3's fourth rule: every key onto an account
has its sibling, the named exemptions only.

Nothing user-facing changes and there is no door. It is not the first
user-facing beat of the brief because it only gives erasure somewhere to put
keys; `deletion/04` (the sweep) is.

## Detailed Design

### 1. What the code says, where it differs from the spec

Read from the catalog of `main@02204093` (36 foreign keys onto the three roots).

- **The roots' own history edges cannot take a sibling.** `users_versions.id`,
  `students_versions.id` and `supporters_versions.id` reference their root and
  are the first column of the history table's primary key `(id, version)`. They
  cannot be NULL. They also never need re-pointing: D1 keeps the `users`,
  `students` and `supporters` rows (personal columns NULL, `deleted_at` set), so
  the id they name always exists. They are exempt (§6, decision D-B).
- **`supporter_invites` is on D10's physical-delete list but not on the slice's
  exemption list.** Its three keys (`inviter_user_id`, `student_id`,
  `supporter_id`) never survive an erasure to be re-pointed, for the same reason
  as the three named tables. It is exempt with them (decision D-A).
- **Two live keys are nullable today.** `email_sends.user_id` (system mail, an
  address with no account; RFC 263) and `convos.supporter_id` (NULL on a
  student's own conversation; `convos_party_author_check`). The slice's
  `(<live> IS NULL) <> (<deleted> IS NULL)` would refuse both legitimate NULLs.
  Their pair is "at most one" and the party CHECK reads either key (§3, decision
  D-D).
- **History copies name every column.** All eleven `log_*_version` functions for
  the tables below use an explicit column list, and the copied keys are
  `NOT NULL` with no foreign key. Each copy is restated to carry the sibling
  (§4).
- **The guards are not touched.** `prevent_supporter_immutable_updates`,
  `prevent_convos_party_updates`, the link, link-request, sharing-permission and
  intended-subject guards refuse any change to their keys, and
  `prevent_log_update` refuses every UPDATE on the seven append-only tables
  here. That is the guarantee `deletion/03` opens one arm in, under its declared
  erasure. Nothing in this slice re-points a key in production, so no guard
  changes.

### 2. The three tables

One migration, `create-deleted-account-tables.sql`. **This is the DDL presented
at the approval stop.**

```sql
CREATE TABLE deleted_users (
  id         UUID        NOT NULL PRIMARY KEY REFERENCES users(id),  -- the former users.id (D9)
  created_at TIMESTAMPTZ NOT NULL,               -- users.created_at: when the account was created
  closed_at  TIMESTAMPTZ NOT NULL,               -- users.deleted_at: when it was closed
  erased_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()  -- when the walk erased it
);

CREATE TABLE deleted_students (
  id        UUID        NOT NULL PRIMARY KEY REFERENCES students(id),  -- the former students.id (D9)
  user_id   UUID        NOT NULL UNIQUE REFERENCES deleted_users(id),
  erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE deleted_supporters (
  id        UUID        NOT NULL PRIMARY KEY REFERENCES supporters(id),  -- the former supporters.id (D9)
  user_id   UUID        NOT NULL UNIQUE REFERENCES deleted_users(id),
  erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Two changes from the spec's sketch, each an enforcement of something the spec
already says:

- **`id` references the live root** (decision D-C). D9 says the deleted id IS
  the former id; the foreign key makes a `deleted_users` row for an id that was
  never an account impossible. It is satisfiable because D1 keeps the root row.
- **`user_id` is `UNIQUE`** on `deleted_students` and `deleted_supporters`,
  mirroring `students_user_id_unique_idx` and `supporters_user_id_unique_idx`:
  one student and one supporter per account, live or deleted.

No column on any of them is personal: D5 keeps "a record that an account existed
and when it was deleted". None is `pii`-bearing, so drift rule 2 treats a key
onto them as not owned.

**Append-only.** `trigger_00_prevent_<t>_update` (`prevent_log_update`) and
`trigger_01_prevent_<t>_delete` (`prevent_log_delete`) on each, the pair the log
tables carry. Nothing ever updates or deletes a `deleted_*` row.

### 3. The sibling keys

On each table below, per live key `<live>` onto root `<root>`:

```sql
ALTER TABLE <t>
  ADD COLUMN deleted_<root>_id UUID NULL REFERENCES deleted_<root>s(id),
  ALTER COLUMN <live> DROP NOT NULL,
  ADD CONSTRAINT <t>_<live>_pair_check CHECK (num_nonnulls(<live>, deleted_<root>_id) = 1);
```

`num_nonnulls(a, b) = 1` is the spec's `(a IS NULL) <> (b IS NULL)` written so
the nullable case (`<= 1`) reads as the same rule. The column is named for the
root, not the live column: `supporter_invites.inviter_user_id` would be the only
exception and it is exempt. On a table with two roots, there is one sibling per
root, because each root erases on its own clock.

| Root         | Table                           | Live key       | Pair   |
| ------------ | ------------------------------- | -------------- | ------ |
| `users`      | `students`                      | `user_id`      | `= 1`  |
|              | `supporters`                    | `user_id`      | `= 1`  |
|              | `subscriptions`                 | `user_id`      | `= 1`  |
|              | `email_sends`                   | `user_id`      | `<= 1` |
| `students`   | `convos`                        | `student_id`   | `= 1`  |
|              | `observations`                  | `student_id`   | `= 1`  |
|              | `claims`                        | `student_id`   | `= 1`  |
|              | `extraction_runs`               | `student_id`   | `= 1`  |
|              | `college_list_entries`          | `student_id`   | `= 1`  |
|              | `commitments`                   | `student_id`   | `= 1`  |
|              | `synthesis_runs`                | `student_id`   | `= 1`  |
|              | `fit_suggestions`               | `student_id`   | `= 1`  |
|              | `fit_lens_runs`                 | `student_id`   | `= 1`  |
|              | `money_profiles`                | `student_id`   | `= 1`  |
|              | `cost_report_shares`            | `student_id`   | `= 1`  |
|              | `share_events`                  | `student_id`   | `= 1`  |
|              | `intended_subject_status`       | `student_id`   | `= 1`  |
|              | `intended_subjects`             | `student_id`   | `= 1`  |
|              | `supporter_student_links`       | `student_id`   | `= 1`  |
|              | `admission_test_score_declines` | `student_id`   | `= 1`  |
|              | `admission_test_scores`         | `student_id`   | `= 1`  |
|              | `sharing_permissions`           | `student_id`   | `= 1`  |
|              | `supporter_link_requests`       | `student_id`   | `= 1`  |
| `supporters` | `convos`                        | `supporter_id` | `<= 1` |
|              | `supporter_student_links`       | `supporter_id` | `= 1`  |
|              | `supporter_link_requests`       | `supporter_id` | `= 1`  |
|              | `supporter_llm_costs`           | `supporter_id` | `= 1`  |

27 keys on 24 tables. `email_sends.user_id` and `convos.supporter_id` are
nullable today and keep that meaning; their pair is `<= 1` (decision D-D).
`convos_party_author_check` is restated to read either key:

```sql
CHECK ((party = 'supporter') = (num_nonnulls(supporter_id, deleted_supporter_id) = 1))
```

so an erased parent's conversation is still a supporter conversation.

The sibling FKs take the default `NO ACTION`: a `deleted_*` row is never
deleted. **Indexes:** none on the sibling keys. Every sibling is NULL on every
row until `deletion/03`; no `deleted_*` row is ever deleted, so the referencing
side needs no index for FK enforcement; the walk adds what it reads by. The
uniqueness of `students.user_id` and `supporters.user_id` is carried by the
`UNIQUE` on `deleted_students.user_id` and `deleted_supporters.user_id` (§2).

Partial indexes and the self-link guards that read live keys
(`supporter_student_links_supporter_student_active_idx`,
`prevent_supporter_student_link_self_link`, …) are unchanged: on a re-pointed
row the live key is NULL and they do not apply, which is the intent — an erased
person has no active link and cannot link to themselves.

### 4. History copies

The ten `*_versions` tables that copy a root key get the sibling as a copy:
nullable, no foreign key (the `subscriptions_versions` precedent), the live copy
made nullable, and the same pair CHECK. Their logging function is restated in
the same migration with the sibling in its column list.

| History table                      | Copied keys                  | Function restated                     |
| ---------------------------------- | ---------------------------- | ------------------------------------- |
| `students_versions`                | `user_id`                    | `log_student_version`                 |
| `supporters_versions`              | `user_id`                    | `log_supporter_version`               |
| `subscriptions_versions`           | `user_id`                    | `log_subscription_version`            |
| `supporter_student_links_versions` | `student_id`, `supporter_id` | `log_supporter_student_link_version`  |
| `college_list_entries_versions`    | `student_id`                 | `log_college_list_entry_version`      |
| `money_profiles_versions`          | `student_id`                 | `log_money_profile_version`           |
| `intended_subjects_versions`       | `student_id`                 | `log_intended_subject_version`        |
| `intended_subject_status_versions` | `student_id`                 | `log_intended_subject_status_version` |
| `admission_test_scores_versions`   | `student_id`                 | `log_admission_test_score_version`    |
| `sharing_permissions_versions`     | `student_id`                 | `log_sharing_permission_version`      |

Each restated function is the latest definition (by `ORDER` position) with the
sibling appended to both lists, nothing else changed. Existing history rows are
untouched: their live copy stays set and their sibling NULL, which the pair
CHECK admits.

### 5. The spend views

`student_llm_cost`, `supporter_llm_cost` and `account_llm_cost` are recreated
(currently `classify-owned-llm-request-keys.sql`) with each owner's key read as
the pair:

- `student_llm_cost`: every arm of `per_call` selects
  `COALESCE(<owner>.student_id, <owner>.deleted_student_id) AS student_id`
  (`convos`, `extraction_runs`, `synthesis_runs`, `fit_lens_runs` twice).
- `supporter_llm_cost`:
  `COALESCE(slc.supporter_id, slc.deleted_supporter_id) AS supporter_id`.
- `account_llm_cost`: `COALESCE(st.user_id, st.deleted_user_id) AS user_id` and
  `COALESCE(sp.user_id, sp.deleted_user_id) AS user_id`. The joins onto
  `students.id` and `supporters.id` are unchanged: D1 keeps those rows.

Output columns, names and types are unchanged, so `AccountLlmCostDao` is
untouched. By D9 the deleted id is the live id, so an account's spend is
reported under the same id before and after its keys move.

### 6. The drift guard's fourth rule

`ErasureClassificationTest` gains rule 4:

4. **Every key onto an account has its sibling.** For every foreign key column
   `<t>.<live>` onto `users`, `students` or `supporters`, unless it is on
   `NOT_KEYED`: `<t>` has a column with a foreign key onto the matching
   `deleted_*` table, and a CHECK constraint whose columns are exactly those
   two. The pairing is read from `pg_constraint.conkey`, not from a column name
   (standing D8).

`NOT_KEYED`, a `Map<String, String>` keyed by `table.column` like the other two
lists:

| Entry                                                                             | Reason                                                                      |
| --------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `sessions.user_id`, `verification_tokens.user_id`, `user_auth_identities.user_id` | physically deleted at erasure (D10); never survives to be re-pointed        |
| `supporter_invites.inviter_user_id`, `.student_id`, `.supporter_id`               | physically deleted at erasure (D10); never survives to be re-pointed        |
| `users_versions.id`, `students_versions.id`, `supporters_versions.id`             | the root's own history; part of the primary key; the root row is kept by D1 |
| `deleted_users.id`, `deleted_students.id`, `deleted_supporters.id`                | the deleted row's own id is the root's id (D9); the root row is kept by D1  |

A `NOT_KEYED` entry that is no longer a foreign key onto a root is stale and
fails, like the other lists. Every new column (the 27 siblings, the 11 history
copies, and the `deleted_*` tables' columns now in the reach through their `id`
key) goes on `NOT_PERSONAL` under the existing `KEY` and `LIFECYCLE` reasons.

### 7. The rebind (RFC 252)

`SubscriptionsDao.upsert`'s `ON CONFLICT (original_transaction_id) DO UPDATE`
gains the deleted key in its `SET` and one arm in its `WHERE`:

```sql
SET ..., user_id = EXCLUDED.user_id, deleted_user_id = NULL, ...
WHERE ( <unchanged: same account, terms changed> )
   OR ( <unchanged: other account, closed> )
   OR subscriptions.deleted_user_id IS NOT NULL
```

An erased account's subscription always rebinds to the account that presents its
`original_transaction_id`, as a closed one already does. Clearing the deleted
key and setting the live one in one statement is the only legal move: the pair
CHECK refuses a half-update.

### 8. Kotlin

No model changes. Every key stays non-null on a live row: the pair CHECK makes a
live row with neither key impossible, and nothing in this slice writes a
sibling. `deletion/03` is the first change that moves a key, so it owns every
read path that can meet a re-pointed row. The one already known:
`SubscriptionService.refresh` (the App Store webhook) reads the row by
`original_transaction_id` and upserts under `existing.userId`; on an erased
account's subscription that read meets a NULL `user_id`, and the refresh must
answer "not bound" rather than throw or bind. More broadly, every DAO row mapper
that reads a key made nullable here (`StudentsDao`, `ConvosDao`,
`SubscriptionsDao`, `ClaimsDao` and the rest) assumes it is set, so
`deletion/03` must make those mappers total or filter erased rows out of their
queries before it writes a sibling; the `COUPLING.` paragraph in
`create-deleted-account-tables.sql` records the same. The upsert in §7 never
maps a re-pointed row: the new arm always writes, and `RETURNING *` returns the
rebound row.

Any test or shell assertion that matched PostgreSQL's NOT NULL wording on a key
made nullable here moves to the pair CHECK's name.

### 9. Cost

`ADD COLUMN … NULL` is catalog-only. `DROP NOT NULL` is catalog-only. Each
`ADD CONSTRAINT … CHECK` and `… FOREIGN KEY` scans its table once to validate,
under the migration's lock; no table is rewritten. The LLM log tables are not
altered.

### Reachability

None. No user, screen, chat tool or endpoint changes.

### Schema

**ADDS THREE TABLES** (§2): `deleted_users`, `deleted_students`,
`deleted_supporters`. Changed: 27 sibling keys on 24 tables (§3), 11 history
copies on 10 tables (§4), 27 pair CHECKs plus 11 on history, one CHECK restated
(`convos_party_author_check`), ten logging functions restated, three views
recreated with the same columns. Migrations:
`create-deleted-account-tables.sql`; one `deleted-key-<table>.sql` per table
family in §3 (the live table, its `_versions` and its logging function
together); `spend-views-read-deleted-keys.sql`; each appended to
`db/schema/ORDER`.

## Tests

All in `:db` against the real database unless named.

**`ErasureClassificationTest`** (§6):

1. `every key onto an account has its sibling` — rule 4; the message lists each
   offending `table.column`. Anti-vacuity: the inspected set contains
   `students.user_id`, `convos.student_id` and `convos.supporter_id`.
2. The existing `the named lists give a reason for every entry` and
   `no named entry is stale` cover `NOT_KEYED`.
3. The existing probe test gains
   `CREATE TABLE probe_keyed (student_id UUID REFERENCES students(id))` and
   asserts rule 4 names `probe_keyed.student_id`, and not after rollback.

**History.** Rule 4 was run against `main@02204093` before any migration. It
failed naming exactly the 27 keys of §3 and no other; the probe test passed
(rule 4 named `probe_keyed.student_id`). `no named entry is stale` also failed,
on the three `deleted_*.id` entries of `NOT_KEYED`, whose tables did not exist
yet. The other six tests passed.

**`DeletedKeyPairTest`** (§2–§4):

4. `a row keyed on both the live and the deleted account is refused` — one table
   per root: `students` (`users`), `money_profiles` (`students`),
   `supporter_llm_costs` (`supporters`); SQLState 23514 naming the pair CHECK.
5. `a row keyed on neither is refused` — the same three tables.
6. `a row keyed only on the deleted account is accepted` — the same three, with
   `deleted_users` / `deleted_students` / `deleted_supporters` rows inserted
   first.
7. `a nullable key may be absent but never doubled` — `email_sends` with neither
   key is accepted, with both refused; a student `convos` row with neither
   supporter key is accepted; a supporter `convos` row keyed only on
   `deleted_supporter_id` is accepted, and with neither is refused by
   `convos_party_author_check`.
8. `a deleted row names a real account` — a `deleted_users` row whose id is not
   a `users.id` is refused (23503); a second `deleted_students` row for the same
   deleted user is refused (23505).
9. `a deleted row is never changed or removed` — UPDATE and DELETE on each
   `deleted_*` table are refused by the log guards.
10. `the history copy carries the deleted key` — a `students` row moved from
    `user_id` to `deleted_user_id` (version bumped) writes a `students_versions`
    row with `user_id` NULL and `deleted_user_id` set.

**`AccountLlmCostDaoTest`** / the view tests (§5):

11. `an account's spend is the same before and after its student moves to the
    deleted key`
    — fixture spend on the student side and the supporter side;
    `AccountLlmCostDao` totals over three date ranges; move `students.user_id`
    to `deleted_user_id` by direct UPDATE; the three totals and the id they are
    keyed on are unchanged (the slice's acceptance criterion).
12. `spend keyed on a deleted student or supporter reports under the same id` —
    owner rows inserted keyed only on `deleted_student_id` (one per
    `student_llm_cost` arm) and a `supporter_llm_costs` row keyed only on
    `deleted_supporter_id` appear in `student_llm_cost` / `supporter_llm_cost`
    under the deleted id, and in `account_llm_cost` under the account id.

**`SubscriptionsDaoTest`** (§7):

13. The existing
    `upsert rebinds a closed account's subscription to the new
    account` and
    `upsert refuses to rebind another account's subscription` pass unchanged, as
    does `SubscriptionRoutingTest`'s 409 test.
14. `upsert rebinds an erased account's subscription to the new account` — the
    row is moved to `deleted_user_id` (a `deleted_users` row inserted first);
    upsert from a new account returns `Applied`; the row has `user_id` set,
    `deleted_user_id` NULL, and a new `subscriptions_versions` row says so.

## Implementation Plan

1. **Test first.** Add rule 4, `NOT_KEYED` and the probe to
   `ErasureClassificationTest`. Run it against the base schema and record its
   failure under Tests → History. Verify:
   `nix develop -c bin/test db -t "ed.unicoach.db.ErasureClassificationTest"`.
2. **The three tables.** `create-deleted-account-tables.sql` and its `ORDER`
   line; tests 8–9.
3. **Sibling keys**, one `deleted-key-<table>.sql` per table family in §3, each:
   the sibling, `DROP NOT NULL`, the pair CHECK; for a versioned table, the
   history copy, its CHECK and the restated logging function (§4); for `convos`,
   the restated party CHECK. Add every new column to `NOT_PERSONAL`. Run
   `nix develop -c bin/test` after every few; move any assertion on a NOT NULL
   key violation to the pair CHECK (§8).
4. **Spend views.** `spend-views-read-deleted-keys.sql`; tests 11–12.
5. **Rebind.** `SubscriptionsDao.upsert` (§7); tests 13–14.
6. **Pair tests** 4–7 and 10.
7. **Full suite.** `nix develop -c bin/test`, unscoped. Rule 4 passes.

## Files Modified

- `db/schema/` — `create-deleted-account-tables.sql`, one
  `deleted-key-<table>.sql` per table family in §3,
  `spend-views-read-deleted-keys.sql` (all new); `ORDER`
- `db/src/main/kotlin/ed/unicoach/db/dao/SubscriptionsDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/` — `ErasureClassificationTest.kt`,
  `DeletedKeyPairTest.kt` (new), `dao/AccountLlmCostDaoTest.kt`,
  `dao/SubscriptionsDaoTest.kt`, and the view tests beside them
- Any test or `bin/db-*-tests` assertion that matched a NOT NULL violation on a
  key made nullable here (§8)
