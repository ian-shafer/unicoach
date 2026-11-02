# RFC 266 — The walk

**Slice:** `deletion/03/the-walk` (brief 0002, _account erasure_). **Status:**
proposed. **Base:** `main@fe0ed0ab`. **Lane:** rfc.

## Executive Summary

Brief 0002 D1 and D3 say a closed account is erased by the schema, not by a
column list: every `pii.*` column NULL and `deleted_at` stamped, every key onto
the account moved onto its `deleted_*` sibling, the LLM log kept with its text
NULL, a short named list physically deleted. RFC 263 typed the columns and RFC
264 gave the keys somewhere to go. This RFC writes the erasure.

It adds one function that erases a row (`soft_delete_entity`), one function that
walks the catalog from an account and erases everything it owns
(`erase_account`), one predicate every guard that would refuse the walk calls
first (`erasure_admits`), and an operator door: `POST /user/{id}/erase` in
admin-web erases one closed account and shows what it touched, per table (D12).
Every Kotlin read that can meet an erased row is made total first, so the first
erasure does not break the admin pages, a parent's conversation list or the App
Store webhook.

After it lands, "deleted" is true for any account an operator erases. Nothing
erases on its own yet: the sweep that does that seven days after a close, and
the email that confirms it, are `deletion/04`. That is the first beat a user
meets; this one has only the operator's door.

## Detailed Design

### 1. What the code says, where it differs from the spec

Read from the catalog of `main@fe0ed0ab`.

- **A parent's conversation is reached from the student.** `convos.student_id`
  is set on both parties' conversations; on a supporter's conversation it names
  the student it is about. A walk that erased every row reached from
  `students.id` would erase the parent's words, which brief 0014 D9
  ("author-partitioned deletion: each party's own conversations, inputs, and
  account survive the other's deletion") forbids. §3 adds the one rule that
  partitions them (decision D-A, approved 2026-11-01).
- **Most guards are never tripped.** The walk writes `pii.*` columns,
  `deleted_at`, `version` and the key pairs. Of the shared guards only
  `prevent_log_update` refuses those; `prevent_immutable_updates` and
  `prevent_physical_timestamp_update` guard `id`, `created_at` and
  `row_created_at`, which the walk never writes. No D10 table carries a blanket
  delete guard (`user_auth_identities`' guard already admits a closed account).
  The arm goes into the eight guard functions the walk trips, not into every
  guard (§5, decision D-B).
- **A versioned row cannot be updated without a version bump.**
  `enforce_versioning` refuses an UPDATE whose `version` is not
  `OLD.version +
  1`, so every walk UPDATE on a table carrying that trigger
  bumps it, and the history trigger writes one more row (preamble X6).
- **The inherited "partial indexes on the deleted keys" would not restore the
  meter's index scans.** The spend views filter on
  `COALESCE(user_id, deleted_user_id)`; no index on either column alone serves
  that expression. Expression indexes on the same `COALESCE` do (§7, decision
  D-G).
- **The drift guard already proves the slice's anti-vacuity case in part.** Its
  probe adds a TEXT column to `claims` and a keyed table with no TEXT column; §9
  adds the slice's exact probe, a new table with a TEXT column and a key onto
  `students`.

### 2. `soft_delete_entity`

One function erases the rows of one table that match one key:

```sql
CREATE FUNCTION soft_delete_entity(
  target     regclass,  -- the table
  key_column name,      -- the column to match
  key_value  text       -- the value, compared as text to key_column::text
) RETURNS bigint        -- rows changed
```

On every row of `target` whose `key_column` equals `key_value` and which is not
already erased, in one UPDATE: every column whose type lives in the `pii` schema
is set NULL; `deleted_at` is set to `now()` where the table has one and it is
NULL; `version` is set to `version + 1` where the table carries the
`enforce_versioning` trigger. The column set is read from `pg_attribute` /
`pg_type.typnamespace` on every call; there is no column list. A row is already
erased when every `pii.*` column is NULL and `deleted_at` (if the table has one)
is set; skipping those rows is what makes a second run write nothing (no new
history row, no version bump). A table with neither a `pii.*` column nor
`deleted_at` (`subscriptions`, `cost_report_shares`, the support tables) is
never changed: the subscription outlives the person (D6). Every identifier is
quoted with `format('%I')`; every reference to another object is
schema-qualified (preamble X1).

The key is `text` so one signature serves `uuid` and `bigint` keys; the value is
cast to the key column's own type, never the column to text, so the key's index
serves the match. That matters because the walk calls it once per erased row.

### 3. The walk

One plpgsql function erases one account, in the caller's transaction:

```sql
CREATE FUNCTION erase_account(account uuid)  -- a users.id
RETURNS TABLE (
  table_name text,   -- a table the walk touched
  moved      bigint, -- rows whose live account key moved to its deleted sibling
  erased     bigint, -- rows soft_delete_entity changed
  deleted    bigint  -- rows physically deleted (D10)
)
```

Plpgsql rather than Kotlin driving per-row SQL (decision D-D): the walk is
catalog reads and set-based UPDATEs, one round trip, in one transaction, and the
same function serves the admin door here and the sweep's job in `deletion/04`.

**Refusal.** It locks the `users` row `FOR UPDATE` and raises (SQLSTATE `P0001`,
`CONSTRAINT = 'erase_account_closed'`) unless `deleted_at` is set. An unknown id
raises the same way with `CONSTRAINT = 'erase_account_exists'`.

**The account's roots** are `users.id = account` and, if present, the one
`students` row and the one `supporters` row whose `user_id` or `deleted_user_id`
is `account`.

**Order of work**, all inside one call:

1. **Read what the deletes need** before anything is NULL: the account's
   addresses (`users.email` and every `users_versions.email`) and its session
   token hashes, two of the identifier kinds the `jobs` predicate (§4) matches.
2. **Declare the erasure** (§5):
   `set_config('unicoach.erasure_account',
   account::text, true)`.
3. **Insert the deleted rows** (D1, D9):
   `deleted_users (id, created_at,
   closed_at)` from
   `users (id, created_at, deleted_at)`; `deleted_students` and
   `deleted_supporters` for the roots present, each
   `ON CONFLICT (id) DO NOTHING` so a re-run inserts nothing.
4. **Move the keys.** For every foreign key onto `users`, `students` or
   `supporters` that a pair CHECK couples with a sibling onto the matching
   `deleted_*` table (read from `pg_constraint.conkey`, never from a name — RFC
   264): one UPDATE per key, `SET <sibling> = <live>, <live> = NULL` (plus the
   version bump of §2) on every row whose `<live>` is one of the roots. This is
   every row keyed on the account, the other party's rows included: D1's "every
   row keyed on a root keys on the deleted table".
5. **Erase what the account owns.** The erased set is built from the roots
   outward over the edges of §6:
   - the three root rows;
   - every row keyed (by its live key or its sibling) on one of the roots **and
     on no other account** — the ownership rule (decision D-A, approved
     2026-11-01): a row that carries a key onto any other account is never
     erased, whatever path reaches it; at most its key onto this account moves
     (step 4). Neither party's erasure crosses into the other's rows;
   - with one reading of "other account" for `convos`: a conversation belongs to
     its `party`. On a supporter's conversation the student key names whom it is
     about, not who wrote it, so it is not counted; on a student's conversation
     there is no supporter key. Erasing the parent erases the parent's
     conversations about a student; erasing the student only moves their key on
     them (brief 0014 D9). `party` is read here, once, beside D10's named list;
     `convos_party_author_check` already ties it to the supporter key;
   - every row whose child-ward foreign key (not `owned.*`) points at a row in
     the set, under the same rule: a student's observation, commitment or fit
     suggestion that points at an erased parent's conversation is not erased
     (none exists today: extraction skips supporter conversations and the
     parent's coach only reads);
   - every row an `owned.*` column of a row in the set points at, using the
     constraint's referenced column as the key, and that row's own child-ward
     children (`llm_responses`, `llm_responses_raw`). An `owned.*` key is never
     followed backwards, so the walk never climbs from a log row into another
     owner.

   Each row in the set is passed to `soft_delete_entity` by its primary key (or,
   for a history table, by the entity `id` it copies, which erases every version
   at once). Tables are visited breadth-first from the roots, so a live table is
   erased before its `_versions` table and the history rows the key move and the
   erasure just wrote are erased with the rest.
6. **Delete D10's rows**: `sessions`, `verification_tokens`,
   `user_auth_identities` keyed on the account; `supporter_invites` keyed on any
   root (`inviter_user_id`, `student_id`, `supporter_id`); and `jobs` matching
   §4, with their `job_attempts` (the foreign key cascades). These tables are
   named once, in the walk; the drift guard's `NOT_KEYED` list names the first
   four with the same reason (RFC 264).
7. **Close the declaration**:
   `set_config('unicoach.erasure_account', '',
   true)`, so the rest of the
   caller's transaction is guarded again.
8. Return one row per table with a non-zero count.

**What the walk does not touch.** The `deleted_*` tables' own children (the
sibling keys point there, not along the walk). History copies of a live key
(`students_versions.user_id` and the ten like it) are not moved: they carry no
foreign key and record what was true then; D9 makes the id they name the deleted
row's id anyway. An LLM log row no `owned.*` column points at is not touched
(D11, answered: kept). An `email_sends` row sent to the account's address before
RFC 263 whose backfill matched no account has no key and is not reached either.

**Known limit: the student's data in a parent's conversation.** The parent's
coach writes the student's name and shared college list into every system prompt
it sends (`SubjectBlock`), its read-only tools return the student's shared data,
and the parent may write about the student. All of it lives in the parent's
conversation and LLM log rows, which D-A keeps when the student is erased; it is
erased when the parent is. The copy cannot be separated from the parent's words
inside one column, and erasing the parent's conversation would erase the
parent's data. Whether `deletion/05`'s copy says so is /chart's.

**Idempotency.** Every step skips what is already done: the deleted rows
conflict, a moved key has a NULL live value, an erased row is skipped by §2, a
deleted row is gone. A second call returns no rows and writes nothing.

**Failure.** Any error rolls back the caller's transaction whole; nothing is
half-erased. A foreign key with more than one column in the walk's edges raises
rather than being guessed at; none exists today.

### 4. Which `jobs` rows are deleted

A `jobs` row is deleted when its `payload` holds, at any depth, a JSON string
equal to one of the account's identifiers: the three root ids, the ids of its
erased conversations, its addresses (lower-cased; current and historical), or
its session token hashes (decision D-F). Read with
`jsonb_path_query(payload, 'strict $.**')` over string scalars, so a job type
added later that carries any of these is covered without a change here. Today
that is `SEND_EMAIL` (`userId`, `to`), `SYNTHESIZE_STUDENT` and `FIT_LENS`
(`studentId`), `EXTRACT_CONVERSATION` (`convoId`) and `SESSION_EXTEND_EXPIRY`
(`tokenHash`), in any status. `REFRESH_SUBSCRIPTION` carries Apple's identifiers
only and survives with the subscription (D6). The scan reads every `jobs` row
once per erasure; the 30-day reaper keeps the table small.

### 5. The declared erasure and the guard arm

One predicate, called first by every guard the walk trips:

```sql
CREATE FUNCTION erasure_admits() RETURNS boolean
-- true iff unicoach.erasure_account (transaction-local) names a users row
-- whose deleted_at is set
```

The marker is a transaction-local custom setting the walk sets and clears
(decision D-C). It is an integrity guard against application bugs, not a
security boundary: any session of the application's role could set it, as it
could run any other SQL; the condition that the named account is closed keeps a
stray marker from opening a live account's rows. A SECURITY DEFINER walk owned
by a separate role would make it a boundary, at the cost of a role per checkout
(RFC 174) that nothing else needs.

The eight guard functions that refuse a write the walk makes each gain, as their
first statement, `IF public.erasure_admits() THEN RETURN NEW; END IF;`:

| Guard function                                      | Kind           | Walk write it refuses                           |
| --------------------------------------------------- | -------------- | ----------------------------------------------- |
| `prevent_log_update`                                | shared         | any UPDATE on the 18 append-only tables         |
| `prevent_convos_party_updates`                      | table-specific | `student_id`, `supporter_id` moved              |
| `prevent_supporter_immutable_updates`               | table-specific | `supporters.user_id` moved                      |
| `prevent_supporter_student_link_immutable_updates`  | table-specific | `student_id`, `supporter_id` moved              |
| `prevent_supporter_link_request_column_updates`     | table-specific | `student_id`, `supporter_id` moved              |
| `prevent_sharing_permission_column_updates`         | table-specific | `student_id` moved                              |
| `prevent_intended_subject_immutable_updates`        | table-specific | `student_id` moved, `subject_slug` (`pii`) NULL |
| `prevent_intended_subject_status_immutable_updates` | table-specific | `student_id` moved                              |

Every other guard is unchanged, so none admits a write erasure does not need
(decision D-B). Each restated function is its latest definition by `ORDER`
position with the one line added. `enforce_versioning` is not a guard here; the
walk satisfies it (§2).

### 6. One definition of the reach

The drift guard (`ErasureClassificationTest`) and the walk must agree on what is
reachable, or the guard proves a reach the walk does not take. Today the guard
holds its own recursive CTE. This RFC moves the edge set into the schema, once,
and both read it:

```sql
CREATE VIEW erasure_edge AS            -- one row per single-column foreign key in public
  child          regclass,  -- the table holding the key
  child_column   name,
  parent         regclass,  -- the table it references
  parent_column  name,
  owned          boolean    -- the key's type lives in the owned schema
```

A child-ward step runs `parent → child` on every edge; a forward step runs
`child → parent` on an `owned` edge only. The guard's reach (seeds, child-ward
flag) becomes a query over the view; its four rules and its named lists are
unchanged. The walk additionally skips edges whose parent is a `deleted_*` table
(the sibling keys) and edges into D10's tables (§3, step 6). A multi-column
foreign key appears in the view with `child_column` NULL, so the walk can refuse
it (§3, Failure).

### 7. The spend views' index scans

`account_llm_cost` filters on `COALESCE(st.user_id, st.deleted_user_id)` and
`COALESCE(sp.user_id, sp.deleted_user_id)`; `supporter_llm_cost` joins on
`COALESCE(slc.supporter_id, slc.deleted_supporter_id)` (RFC 264 §5). Three
expression indexes, matching those expressions exactly, give the per-account
meter its index scans back (decision D-G). **This is the DDL presented at the
approval stop:**

```sql
CREATE INDEX students_account_idx            ON students            ((COALESCE(user_id, deleted_user_id)));
CREATE INDEX supporters_account_idx          ON supporters          ((COALESCE(user_id, deleted_user_id)));
CREATE INDEX supporter_llm_costs_account_idx ON supporter_llm_costs ((COALESCE(supporter_id, deleted_supporter_id)));
```

Not partial, and not unique: every row has exactly one of the pair set, so the
expression is never NULL and the index covers every row; uniqueness is already
carried by the live unique index and `deleted_students.user_id` /
`deleted_supporters.user_id`. If a view's expression is rewritten, the planner
stops using its index; test 15 fails when that happens.

No new table.

### 8. Kotlin: reads that can meet an erased row

Every row mapper reads its account key and its personal columns as non-null, and
`queryOne` / `queryList` turn the resulting exception into a `DatabaseException`
(`SqlSessionQueries.kt:42,143`), so one erased row fails a whole read. Several
DAOs (`UsersDao`, `StudentsDao`, `ConvosDao`, `CollegeListEntriesDao`,
`MoneyProfilesDao`) apply `SoftDeleteScope` in Kotlin after mapping, so even an
`ACTIVE` read by id maps the erased row first. Before the walk writes anything,
every read that can return an erased row excludes it in SQL (decision D-I).
Models are unchanged except `Convo` (below): an erased row holds nothing a
product surface can show, and D7 forbids inventing a value for it.

**Which rows are erased.** A row is erased when the key of the account that owns
it has moved — §3's ownership rule (D-A) read back:

| Rows                                                                                                                                                                                   | Erased when                                                                                  |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `users`                                                                                                                                                                                | a `deleted_users` row has its id                                                             |
| `students`, `supporters`                                                                                                                                                               | `user_id IS NULL`                                                                            |
| `convos`                                                                                                                                                                               | `party = 'student' AND student_id IS NULL` or `party = 'supporter' AND supporter_id IS NULL` |
| a table keyed on a student only (`claims`, `observations`, `commitments`, `college_list_entries`, `fit_suggestions`, `money_profiles`, the three run tables, `sharing_permissions`, …) | `student_id IS NULL`                                                                         |
| the LLM log (`llm_requests`, `llm_responses`, `llm_responses_raw`)                                                                                                                     | `deleted_at IS NOT NULL` (nothing but the walk sets it; RFC 263 added it)                    |
| a support table or `_versions` table                                                                                                                                                   | its parent is (the read joins or is reached through the parent)                              |

Each DAO states its table's predicate once, as a SQL fragment beside its mapper,
and every read below applies it. Reads filtered by a live account key
(`WHERE student_id = ?`) cannot meet an erased row and are unchanged.

| Read                                                                                                                                                                                                                                  | Today on an erased row                           |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `UsersDao.findById`/`findByIdOrNull`/`findByIdForUpdate`/`list`/`findVersion`/`listVersions`                                                                                                                                          | admin user list and detail fail                  |
| `StudentsDao.findById`/`findByIdOrNull`/`findByIdForUpdate`/`list`/`listVersions`; `SupportersDao.findById`                                                                                                                           | admin fails; synthesis and fit-lens jobs retry   |
| `ConvosDao.findById`/`listWithActivity`/`findByIdWithActivity`/`listTurns`/`findTurnByRequestId`                                                                                                                                      | admin fails; extraction jobs retry               |
| `ClaimsDao`, `ObservationsDao`, `CommitmentsDao`, `CollegeListEntriesDao`, `FitSuggestionsDao`, `MoneyProfilesDao`, `ExtractionRunsDao`, `SynthesisRunsDao`, `FitLensRunsDao`: `findById`, `list`, `listVersions`, `listByConvoRange` | admin fails                                      |
| `ClaimSupportDao`, `CommitmentSupportDao`, `CollegeListEntrySupportDao` reads by the other row's id                                                                                                                                   | admin panels fail                                |
| `SharingPermissionsDao.listVersions`                                                                                                                                                                                                  | (no production caller)                           |
| `LlmCallsDao.findCallByRequestId`/`listCalls`/`listUnlinkedCalls`                                                                                                                                                                     | admin LLM pages fail                             |
| `SubscriptionsDao.findByOriginalTransactionId`                                                                                                                                                                                        | the App Store webhook job retries to dead-letter |

A read that now finds nothing answers as it does for an absent row: the admin
pages 404, the synthesis and fit-lens jobs take their existing not-found no-op
(`SynthesisService.kt:131`), the extraction job's `readPhase` gains the same
not-found no-op (it rethrows today, `ExtractionService.kt:139`), and the
webhook's `refresh` answers `RefreshResult.NotBound` (RFC 264 §8). That last one
is filtered, not mapped: a mapper that fell back to `deleted_user_id` would hand
`refresh` the erased account to upsert under, and the upsert's erased-row arm
(RFC 264 §7) would bind the subscription back to it. The rebind of a returning
subscriber still goes through the upsert, which reads no row.

**The one model change: a parent's conversation outlives its subject.**
`ConvosDao.mapConvo` reads `Convo.studentId` as
`COALESCE(student_id, deleted_student_id)`. By D9 that is the same id the
conversation always named, so `listBySupporterWithActivity` keeps returning the
parent's conversation about an erased student, the wire DTO's `studentId` is
unchanged, and the title (`convos.name`, the parent's own words) renders. A
caller that loads the student by that id now finds none, which every such path
already handles as not found. The student's name in the parent's coach comes
from the live roster (`SupporterService.rosterEntryInSession`), which closure
already emptied; that is RFC 252's behaviour and unchanged here.

**The service.** `AccountErasureService` (`service/.../account/`, beside
`AccountClosureService`):

```kotlin
class AccountErasureService(private val database: Database) {
  suspend fun erase(userId: UserId): Result<ErasureOutcome>
}

sealed interface ErasureOutcome {
  data class Erased(val tables: List<TableErasure>) : ErasureOutcome // empty on a re-run
  data object NotClosed : ErasureOutcome
  data object NotFound : ErasureOutcome
}

data class TableErasure(val table: String, val moved: Long, val erased: Long, val deleted: Long)
```

It runs `AccountErasureDao.erase(session, userId)` in one `withConnection`
transaction. The DAO calls `erase_account(?)` and maps the two refusals by
constraint name (`erase_account_closed` → `NotClosed`, `erase_account_exists` →
`NotFound`); anything else is a failure carrying the exception unaltered. The
service logs the per-table counts at INFO. `deletion/04`'s job calls the same
service.

### 9. The drift guard

Three changes to `ErasureClassificationTest`:

1. Its reach reads `erasure_edge` (§6) instead of its own CTE.
2. The probe test gains the slice's case:
   `CREATE TABLE probe_child
   (student_id UUID REFERENCES students(id), note TEXT)`,
   and rule 1 must name `probe_child.note`.
3. Every column added here goes on `NOT_PERSONAL`; none is expected (§7 adds
   indexes only).

### 10. The operator door

`POST /user/{id}/erase` in admin-web, behind the existing admin auth, registered
by `UsersResource.registerExtraRoutes` beside the RFC 100 triggers. It calls
`AccountErasureService.erase(userId)` (§8) and renders an admin page:

| Outcome     | Response                                                                                    |
| ----------- | ------------------------------------------------------------------------------------------- |
| `Erased`    | 200, a page titled with the user id listing `table_name`, `moved`, `erased`, `deleted` rows |
| `NotClosed` | 409, the shared admin error page: "Close the account before erasing it."                    |
| `NotFound`  | 404, the shared not-found page                                                              |
| failure     | the shared DAO-error page, as every admin action                                            |

An already-erased account answers `Erased` with an empty table: a re-run is the
documented way to finish a dead-lettered erase job (`deletion/04`), and it is
harmless. The user detail page gains an **Erase** `CustomAction`, disabled with
the reason "Close the account first." while `deletedAt` is null, and help text
saying the erasure cannot be undone. There is no second confirmation step: the
close itself was the person's (or the operator's) deliberate act, and the walk
refuses a live account (decision D-H). An erased account's detail page and
`POST /user/{id}/undelete` answer 404: §8 excludes the erased `users` row from
every read, so there is no page to carry a disabled Undelete (amended at review,
Ian 2026-11-01).

The walk's per-table counts are also logged at INFO, one line per erasure, with
the user id and no personal value, so the first production erasure can be read
back from the log as well as the page.

### Reachability

The operator's door only (§10): an admin erases one closed account at
`POST /user/{id}/erase`. No user, screen, chat tool or public endpoint changes;
a parent's conversation about an erased student keeps listing and rendering as
it did after the close.

### Schema

No new table. New: functions `soft_delete_entity`, `erase_account`,
`erasure_admits`, and the `erase_account_*` step functions `erase_account` runs
(one per step of §3, plus the per-table tally; split at review); view
`erasure_edge`; three expression indexes (§7). Restated: the eight guard
functions of §5. Migrations, each appended to `db/schema/ORDER`:
`erasure-edge.sql` (§6), `erasure-functions.sql` (§2, §3, the predicate of §5),
`erasure-guard-arm.sql` (§5), `spend-views-account-indexes.sql` (§7).

## Tests

All against the real database. `:db` unless named.

**The fixture.** `ErasureFixture` (db test fixtures) builds one account that
holds both a student and a supporter, the supporter linked to a second student's
account, through the DAOs' own create paths where they exist and a raw INSERT
where none does, so that the account's roots reach a row in every table of the
reach. It also writes, for the same account: a session, a verification token, an
auth identity, an invite, and `jobs` rows naming each of its identifiers (§4) in
each status. It is hand-built, and **asserted against the catalog**: test 1
fails naming any table in `erasure_edge`'s reach (less the `deleted_*` tables)
with no fixture row, so a table added later fails the gate until the fixture
covers it (decision D-E).

**`AccountErasureTest`** (§3, §4):

1. `the fixture covers the reach` — the coverage assertion above.
2. `erasure leaves no personal value on anything the account owns` — after
   `erase_account`: every `pii.*` column is NULL on every row the fixture wrote
   for the account (its own rows, both roles, every history row and every LLM
   log row reached through an owner); `deleted_at` is set on each where the
   table has it; every key onto the account is NULL and its sibling holds the
   same id; the three `deleted_*` rows exist with
   `closed_at = users.deleted_at`.
3. `the LLM log keeps its tokens and cost` — for every reached `llm_responses`
   row: content NULL, `input_tokens`, `output_tokens`, the cache counts and
   `cost_nanodollars` unchanged.
4. `only D10's rows are deleted` — row counts per table before and after are
   equal except: the D10 tables lose exactly the account's rows; `deleted_*`
   gain one each; `_versions` tables gain the rows the walk's UPDATEs wrote. The
   second student's rows are unchanged but for the link keys onto the erased
   supporter.
5. `the jobs naming the account are deleted, in every status` — each fixture job
   whose payload carries an account identifier at any depth is gone with its
   attempts; a job naming the other account survives; a `REFRESH_SUBSCRIPTION`
   job for the account's subscription survives.
6. `a second erasure writes nothing` — the second call returns no rows; every
   table's row count and every row's `version` are unchanged.
7. `the walk refuses a live account and an unknown id` — SQLSTATE `P0001` with
   `erase_account_closed` and `erase_account_exists`; nothing written.
8. `spend is the same before and after erasure` — the account's spend on both
   sides; `AccountLlmCostDao` totals over three date ranges before and after;
   identical, keyed on the same id (D1).
9. `a parent's conversation survives the student's erasure` — a separate student
   account S and parent account P, with P's conversation about S and its LLM
   rows. Erase S: the conversation's `deleted_student_id` is S, `student_id`
   NULL, `name` and `deleted_at` unchanged, its LLM text intact, and
   `ConvosDao.listBySupporterWithActivity(P)` returns it with `studentId == S`.
   Then erase P: the conversation is erased.
10. `the declaration is closed when the walk returns` — `erasure_admits()` is
    false after `erase_account` returns inside the same transaction.

**`SoftDeleteEntityTest`** (§2):

11. A `claims` row: `statement` NULL, `deleted_at` set, `student_id` and
    `created_at` untouched; returns 1. A `students` row: `version` bumped and
    one history row written. A history table by entity id: every version erased.
    `subscriptions`: returns 0 and writes nothing. A second call on each returns
    0 and writes no history row.

**`ErasureGuardArmTest`** (§5), one test per guard function in §5's table (one
shared, seven table-specific), each with the same UPDATE the walk makes on a row
of that guard's table:

12. refused with no declaration; refused with the declaration naming a live
    account; admitted with the declaration naming a closed account.
13. `a guard without the arm still refuses inside the erasure` — an `id` change
    on `claims` (`prevent_immutable_updates`) and a DELETE on `claims`
    (`prevent_physical_delete`) are refused with the declaration naming a closed
    account.

**`ErasedRowReadsTest`** (§8):

14. After erasing the fixture account, every read in §8's table, called with an
    erased row's id or over the whole table, succeeds and omits the erased rows;
    `SubscriptionsDao.findByOriginalTransactionId` returns null for the erased
    account's subscription.

**`SpendViewIndexTest`** (§7):

15. With `enable_seqscan = off`, `EXPLAIN` of the meter's query on
    `account_llm_cost` names `students_account_idx`, `supporters_account_idx`
    and `supporter_llm_costs_account_idx`.

**`ErasureClassificationTest`** (§9):

16. The four rules pass reading `erasure_edge`; the probe test names
    `probe_child.note` under rule 1 and not after rollback.

**`:service`:**

17. `AccountErasureServiceTest` — `Erased` with per-table counts; `NotClosed`
    for a live account; `NotFound` for an unknown id; `Erased` with an empty
    list on a re-run.
18. `SubscriptionServiceTest` — `refresh` of an erased account's subscription
    answers `NotBound` and makes no App Store call.
19. `ExtractionServiceTest`, `SynthesisServiceTest`, `FitLensServiceTest` — a
    job for an erased conversation or student is a no-op success.

**`:admin-web`** (`UsersResourceTest`):

20. POST `/user/{id}/erase` on a closed user renders the counts page; on a live
    user 409; on an unknown id 404; without an admin session the auth redirect.
21. After an erasure, `/user`, `/student`, `/convo`, `/claim` and the LLM pages
    still render; the Erase action is disabled for a live user; an erased user's
    detail page and `POST /user/{id}/undelete` answer 404.

## Implementation Plan

1. **Reads first** (§8), before anything can write an erased row. For each DAO
   in §8's table, the erased predicate and its application to every listed read;
   `Convo.studentId` through the pair; the extraction not-found no-op. Verify:
   `nix develop -c bin/test`.
2. **The edge view** (§6) and the drift guard reading it (§9). Verify:
   `nix develop -c bin/test db -t "ed.unicoach.db.ErasureClassificationTest"`;
   all six existing tests and the new probe pass.
3. **`soft_delete_entity` and `erasure_admits`** (§2, §5) in
   `erasure-functions.sql`; test 11.
4. **The guard arm** (§5), `erasure-guard-arm.sql`; tests 12–13.
5. **The walk** (§3, §4) in `erasure-functions.sql`; the fixture; tests 1–10.
   Fix what the fixture surfaces (a guard or CHECK the walk trips that §5 does
   not name is added to §5 and to test 12, and reported at review).
6. **Indexes** (§7); test 15.
7. **Service and door** (§8, §10): `AccountErasureDao`, `AccountErasureService`,
   the admin route, action and page; tests 14, 17–21.
8. **Full suite.** `nix develop -c bin/test`, unscoped.

## Files Modified

- `db/schema/` — `erasure-edge.sql`, `erasure-functions.sql`,
  `erasure-guard-arm.sql`, `spend-views-account-indexes.sql` (new); `ORDER`
- `db/src/main/kotlin/ed/unicoach/db/dao/` — `AccountErasureDao.kt` (new); the
  DAOs in §8's table (`UsersDao`, `StudentsDao`, `SupportersDao`, `ConvosDao`,
  `ClaimsDao`, `ObservationsDao`, `CommitmentsDao`, `CollegeListEntriesDao`,
  `FitSuggestionsDao`, `MoneyProfilesDao`, the three run DAOs, the three support
  DAOs, `SharingPermissionsDao`, `LlmCallsDao`, `SubscriptionsDao`)
- `db/src/main/kotlin/ed/unicoach/db/models/` — `TableErasure` (new)
- `db/src/testFixtures/kotlin/ed/unicoach/db/` — `ErasureFixture` (new)
- `db/src/test/kotlin/ed/unicoach/db/` — `ErasureClassificationTest.kt`,
  `AccountErasureTest.kt`, `SoftDeleteEntityTest.kt`, `ErasureGuardArmTest.kt`,
  `ErasedRowReadsTest.kt`, `SpendViewIndexTest.kt` (new)
- `service/src/main/kotlin/ed/unicoach/account/` — `AccountErasureService.kt`,
  `ErasureOutcome.kt` (new); `coaching/extraction/ExtractionService.kt`; and
  their tests, with `SubscriptionServiceTest`, `SynthesisServiceTest`,
  `FitLensServiceTest`
- `admin-web/src/main/kotlin/ed/unicoach/admin/` — `resources/UsersResource.kt`,
  `Application.kt` (wiring), a render helper for the counts page;
  `UsersResourceTest.kt`
