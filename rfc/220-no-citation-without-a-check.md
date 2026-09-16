# RFC 220 — No citation without a check

**Slice:** `test/05.4/no-citation-without-a-check` (brief 0010, D28) **Lane:** A
(rfc) — a schema change. **Base:** `main@37f6a0d8`

## Summary

`external_documents` (RFC 213) is the one home for a url we cite and for whether
it still resolves. It landed with `url_status ... NOT NULL DEFAULT
'unknown'`
and `url_checked_on DATE NULL`, so

```sql
INSERT INTO external_documents (url) VALUES ('https://example.com/x');
```

is legal and stores a citation with no evidence at all. Two paths already did
it: the adopt migration's own fill, and `ExternalDocumentsDao.idOf`, which mints
a row for any url a caller hands it.

This RFC makes that row unrepresentable, in the schema, with one migration and
one signature change. Nothing a family sees changes, and no prompt version is
cut.

## Detailed Design

### 1. The migration refuses, then tightens

One migration, appended after the existing seed. It has two parts, in order.

**A guard that refuses rather than backfills.** If any row holds a NULL
`url_checked_on`, it raises `P0001` naming the count and `bin/check-link`.
Backfilling would invent the evidence this slice exists to require, and a
made-up date is RFC 182's check-that-never-ran-looking-like-one-that-passed,
written into the table by us.

Measured, not assumed: the state-programme corpus cites **43 distinct urls**
(`seed-admission-test-state-programmes-2026.sql`) and the committed sweep
(`seed-external-document-link-status-2026.sql`) covers **all 43** — 34 `found`
and 9 `unknown` with a date. So zero rows fail the guard today. The run
re-confirms this against a migrated database before landing.

**Then the tightening.** `DROP DEFAULT` on `url_status`, `SET NOT NULL` on
`url_checked_on`, and `DROP CONSTRAINT external_documents_checked_when_known` —
the paired CHECK is trivially true once the date is non-null.

`url_status` is **already** `NOT NULL`; the hole in it was the DEFAULT, which
filled the column in for a caller that said nothing. So there is no redundant
`SET NOT NULL` beside it. With the default gone, an INSERT naming only `url`
fails on `url_status`'s existing NOT NULL (SQLSTATE 23502), and one naming a
status but no date fails on `url_checked_on`'s new one.

Both column comments are rewritten in the same migration, because both currently
describe the nullability that is being removed.

The three-state vocabulary is untouched. `unknown` WITH a date — we asked and
got no usable answer — stays exactly as storable as it is today, and nine of our
rows say it. What becomes unrepresentable is the fourth state: a url nobody
checked.

### The proposed DDL, in full

```sql
-- A citation carries evidence, or it cannot be stored (RFC 220, slice
-- test/05.4/no-citation-without-a-check, brief 0010 D28).
--
-- external_documents landed (RFC 213) with `url_status link_status NOT NULL
-- DEFAULT 'unknown'` and `url_checked_on DATE NULL`, so
--
--     INSERT INTO external_documents (url) VALUES ('https://example.com/x');
--
-- is legal and stores a citation with NO evidence at all -- no status anybody
-- chose, and no day anybody looked. Two paths did exactly that: the adopt
-- migration's own fill, and ExternalDocumentsDao.idOf, which minted a row for
-- any url handed to it. The guarantee belongs in the SCHEMA because that is the
-- only layer that can hold it for every writer -- a seed, a migration, a DAO, a
-- human at psql.
--
-- IT REFUSES; IT DOES NOT BACKFILL. A backfill would invent the evidence this
-- migration exists to require, and a made-up date is the check-that-never-ran
-- looking like one that passed (RFC 182) written into the table by us. So the
-- guard below raises, names the count, and names the tool that produces the
-- missing answer. Today it finds none: the corpus cites 43 distinct urls and
-- the committed sweep covers all 43 (34 `found`, 9 `unknown` WITH a date).
--
-- WHAT IS STILL SAYABLE. The three-state vocabulary is untouched. `unknown`
-- WITH a date -- we asked and got no usable answer (a timeout, a DNS fault, a
-- TLS fault, a host refusing us) -- is exactly as storable as it was, and it is
-- what nine of our rows say. What becomes unrepresentable is the fourth state:
-- a url nobody ever checked.
--
-- WHAT IT COSTS EVERY FUTURE CALLER, and this is deliberate: a url must be
-- swept with bin/check-link (or otherwise answered for) BEFORE it can be
-- stored. Brief 0012 (FAFSA) is the first to inherit that cost.
--
-- url_status is ALREADY NOT NULL. The hole in it was the DEFAULT, which filled
-- the column in for a caller who said nothing; so only the DEFAULT is dropped
-- here, and there is no redundant SET NOT NULL beside it. With no default, an
-- INSERT that names only `url` now fails on url_status's existing NOT NULL, and
-- one that names a status but no date fails on url_checked_on's new one.
--
-- THE PAIRED CHECK GOES. `CHECK (url_status = 'unknown' OR url_checked_on IS
-- NOT NULL)` is trivially true once the date is NOT NULL, and a constraint that
-- cannot fail is a constraint a reader has to reason about for nothing.

DO $$
DECLARE
    dateless BIGINT;
BEGIN
    SELECT COUNT(*) INTO dateless
    FROM external_documents
    WHERE url_checked_on IS NULL;

    IF dateless > 0 THEN
        -- Deferred from any statement context, so this text is the whole
        -- payload the operator gets: it names the count, what will NOT happen,
        -- and the command that produces the missing answer.
        RAISE EXCEPTION
            'external_documents holds [%] row(s) with no url_checked_on. This migration will not invent a date: sweep those urls with bin/check-link, record the result, and re-run.',
            dateless
            USING ERRCODE = 'P0001';
    END IF;
END;
$$;

ALTER TABLE external_documents
    ALTER COLUMN url_status DROP DEFAULT,
    ALTER COLUMN url_checked_on SET NOT NULL,
    DROP CONSTRAINT external_documents_checked_when_known;

COMMENT ON COLUMN external_documents.url_status IS
    'Whether the document was still served when we last looked (RFC 200): '
    'found, not_found, or unknown. No default (RFC 220): a caller states what '
    'it knows, because a defaulted status is how a check that never ran comes '
    'to look like one that passed. Recorded by an operator sweep at seed time '
    'and never at render -- a family must never wait on somebody else''s web '
    'server. Only not_found justifies dropping an anchor.';

COMMENT ON COLUMN external_documents.url_checked_on IS
    'The day url_status was established. NOT NULL (RFC 220): a url nobody '
    'checked cannot be stored. An unknown WITH a date is a check that ran and '
    'got no usable answer, which must never look like one that passed '
    '(RFC 182) -- that row is still perfectly storable, and nine of ours say '
    'exactly it.';
```

### 2. `idOf` and `upsert` take the evidence

`idOf` is **not** deleted. Once the columns are non-null its bare insert simply
cannot succeed, so the hole is closed by the schema; and its one-statement
`INSERT ... ON CONFLICT ... RETURNING id` shape avoids a read-then-write race
that a `DO NOTHING` + `SELECT` pair would lose. What changes is its signature: a
citing caller must now say what it knows.

Both entry points take a `NewExternalDocument`, and the difference between them
stays exactly what it was — **who wins on conflict**:

- `upsert` overwrites the status pair. It is for a caller that just ASKED.
- `idOf` leaves an existing row entirely alone
  (`DO UPDATE SET url =
  EXCLUDED.url`). The evidence it carries is used only
  when the row is new, so it still cannot record "we never asked" over an answer
  somebody got (RFC 182).

`NewExternalDocument.urlCheckedOn` becomes a non-nullable `LocalDate`, mirroring
the column. Its `require(!urlStatus.requiresCheckedOn || urlCheckedOn != null)`
guard becomes unreachable and is removed; `SourceDocument`'s identical guard is
untouched, because `source_documents` is not changed by this slice.

`ExternalDocumentsDao.citationIds` is the one home for the citing policy, shared
by both cited corpora — `admission_test_state_programmes` (RFC 208) and
`fafsa_filing_deadlines` (RFC 215, landed from a sibling run while this one was
open). It gains a `readOn: LocalDate` and passes `SourceUrlStatus.FOUND` with
it: the day a person opened those pages and wrote the row from what they served.
That is evidence, stated by the party that has it, and it is only ever written
when the document is new to us. Both callers hand it their row's own
`checked_on`, so neither DAO carries a copy of the rule.

## Considered and NOT done

Recorded so nobody re-proposes them.

- **A lint grepping SQL files for a `GENERATED FILE` header.** A string match
  cannot tell a file that MEANS the header from one that merely SAYS it, and the
  byte-identical seed test already catches the drift it would look for. Ian:
  "this seems junky to me."
- **A content-equality test between the table and the committed
  `link-status.csv`.** The same duplication, in the other direction: the seed is
  generated from that file and pinned byte-identical to it already.
- **Deleting `idOf`.** Nothing is wrong with it once the column is non-null —
  its bare insert cannot succeed — and its single-statement shape is worth
  keeping for the race it avoids.
- **Revoking `INSERT` from the application role.** This is the only mechanism
  that would make an unchecked citation _impossible_ rather than _refused_, and
  it is the real end state. It cannot be done cheaply: `DATABASE_USER` is
  `POSTGRES_USER`, so the application connects as the table owner, and there are
  no per-table grants anywhere in the schema. Building them touches local
  development, the test harness and RDS. **Its own future slice**, not this one.

## What this costs every future caller

A url must be swept with `bin/check-link` — or otherwise answered for — BEFORE
it can be stored. Brief 0012 (FAFSA) is the first to inherit that cost, and this
sentence exists so that brief does not discover it at its own gate.

## Files Modified

| File                                                                          | Change                                                                                                                              |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/require-a-check-on-every-external-document.sql`                    | NEW — the guard, the tightening, the two comments                                                                                   |
| `db/schema/ORDER`                                                             | one line, placed at land time by `ship-order`                                                                                       |
| `db/src/main/kotlin/ed/unicoach/db/models/ExternalDocument.kt`                | `urlCheckedOn` becomes non-nullable; dead guard removed; KDoc                                                                       |
| `db/src/main/kotlin/ed/unicoach/db/dao/ExternalDocumentsDao.kt`               | `idOf` takes a `NewExternalDocument` and writes the evidence on insert; `citationIds` gains `readOn` and states `FOUND` on that day |
| `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeDao.kt`     | hands `citationIds` the row's own `checked_on`                                                                                      |
| `db/src/main/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineDao.kt`             | the same, for the corpus RFC 215 landed mid-run                                                                                     |
| `db/src/test/kotlin/ed/unicoach/db/dao/ExternalDocumentsDaoTest.kt`           | four call sites; two rewritten tests; two new tests                                                                                 |
| `db/src/test/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeDaoTest.kt` | its fixture document states its evidence                                                                                            |
| `db/src/test/kotlin/ed/unicoach/db/FafsaFilingDeadlineSchemaTest.kt`          | the same, for RFC 215's fixture documents                                                                                           |

Two of those files are a sibling run's, landed while this one was open: RFC 215
put the citing policy in one shared place (`citationIds`) the day a second cited
corpus needed it. That is where this change belongs, so the rebase conflict was
read and resolved INTO the shared seam rather than around it — neither DAO
carries its own copy of the rule.

No `service/` file is touched. No `prompts/` file is touched, so
`bin/prompt-seed` cuts no version.

## Implementation Plan

1. Write the migration; run `nix develop -c bin/test` to apply it to the local
   test database and confirm the guard finds zero dateless rows in the real
   corpus.
2. `NewExternalDocument.urlCheckedOn` -> non-nullable; drop the dead `require`.
3. `ExternalDocumentsDao.idOf(session, document)`; update its KDoc so the "who
   wins on conflict" distinction still reads correctly.
4. `AdmissionTestStateProgrammeDao.upsert`: build the two `NewExternalDocument`
   values from `row.sourceUrl` / `row.archiveUrl` and `row.checkedOn`.
5. Update and add the tests below.
6. Full suite, tiered review, hook, land.

## Tests

All in `db/src/test/kotlin/ed/unicoach/db/dao/ExternalDocumentsDaoTest.kt`,
against the real database, cleaning up by url prefix as that suite already does.

**New — the acceptance criteria, each driven. Two tests.**

1. _a citation with no evidence at all is refused by the columns themselves_:
   `INSERT INTO external_documents (url) VALUES (...)` fails on `url_status`
   (SQLSTATE `23502` — the default is gone, so the NOT NULL the table always had
   is what refuses it); an INSERT naming a status but no date fails on
   `url_checked_on`; and `unknown` WITH a date still inserts, so the dropped
   paired CHECK cannot be re-added silently.
2. _the migration refuses a database holding a dateless row, and invents
   nothing_. Inside ONE transaction that is always rolled back: restore the
   pre-migration shape (`SET DEFAULT`, `DROP NOT NULL`, re-add the paired
   CHECK), insert a row with a NULL date, then execute the committed migration
   file **verbatim, read from disk**, and assert it raises `P0001` with a
   message naming the count. Rolled back, so the tightened schema is intact for
   every later test; asserted after the rollback, not assumed.

**Rewritten — they encode the behaviour being removed.**

3. `citing a url we do not hold creates it as never asked` becomes _creates it
   with the evidence the citer supplied_: `idOf` with `FOUND` + a date stores
   both.
4. `a status we could not establish keeps its date, and one we never asked for
   has none`
   loses its never-asked half and keeps the other: `unknown` WITH a date
   round-trips, and is still `unknown`.
5. `a claim to know with no day it was looked at is refused` no longer names
   `external_documents_checked_when_known` — that constraint is dropped — and
   asserts the NOT NULL refusal instead. The Kotlin half is now a compile-time
   type, so the runtime `assertFailsWith` for it goes.

**Two fixtures now state their evidence**, in
`AdmissionTestStateProgrammeDaoTest` and `FafsaFilingDeadlineSchemaTest`. Both
inserted a bare url in `@BeforeEach` and the new columns refused them — the rule
working, caught by the suite, on the first run.

**Unchanged and load-bearing:**
`citing a document does not overwrite what a
sweep found` — now with the citer
carrying different evidence, which is the stronger version of the same claim.

Per brief 0010 D22, no test here is fitted to one student or to a date being
near: the cases are a swept url, an unswept url, an unknown-with-a-date url, an
`http://` url, and a database in a state the product can no longer produce.

No date is rendered for a human anywhere in this change, so brief 0010 D21 (one
date seam, US format) has nothing to bind here.
