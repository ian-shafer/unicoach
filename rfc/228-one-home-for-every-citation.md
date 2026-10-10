# RFC 228 — One home for every citation

> **Reconstruction note.** The document originally committed here was lost. The
> commit that landed it — `646525d2`, "rfc: 228 — one home for every citation",
> 2026-09-16 — recorded a `deno fmt`-mangled copy of
> `college/src/test/kotlin/ed/unicoach/college/CdsSeedLoaderTest.kt` instead of
> the RFC text; its first line was `package ed.unicoach.college`. No other copy
> of the original exists anywhere in git, so there was nothing to restore. This
> text was **reconstructed on 2026-09-25** from the code that landed as RFC 228
> (`main@adc49582`) and the brief 0010 ledger line for the slice. It is
> therefore a faithful description of **what shipped**, not the document the
> author wrote at the time. Where the author's reasoning is not recoverable from
> the committed tree, this document says nothing rather than guessing: it
> records no alternatives considered, no rejected options, and no decision
> attributed to anyone that the tree does not itself state.

**Slice:** `test/05.2/one-home-for-every-citation` (brief 0010, D26) **Lane:** A
(rfc) — three migrations, one script renamed and refolded, one new schema guard.
**Base:** `main@a63e3f9a`. **Landed:** `main@adc49582`, 2026-09-16.

## Problem

Brief 0010 D26 settled where a cited url lives: _"We have the need to store
links to external documents... Let's model this correctly in a separate table:
external_documents... We'll need to do a cleanup slice to move all schema that
points to external documents to use this new table."_ `test/05.1` built the
table (RFC 213, `db/schema/create-external-documents.sql`) and RFC 220 made it
strict — `url_checked_on` is NOT NULL, so a citation carries its evidence or it
cannot be stored. This slice is the cleanup D26 named.

Three owners still declared their own url columns at the base:

- `source_documents` — `source_url`, `archive_url`, plus RFC 200's link-rot pair
  `source_url_status` / `source_url_checked_on`. It was itself the first attempt
  to de-duplicate a url (RFC 170 D13): three CDS fact tables carried their own
  `(source_url, archive_url)` pair and `0087` moved them onto one college-keyed
  row.
- `policy_parameters` — a bare `source_url TEXT NOT NULL` from `0077`, with **no
  reading date at all**.
- `admission_test_administrations` — `dates_source_url TEXT NOT NULL` and
  `score_release_source_url TEXT NULL` (RFC 197), with two https-only CHECKs.

And the sweep that establishes whether a link still resolves had a defect that
the column names made invisible: `bin/cds-link-check` (RFC 200) harvested a
column **literally named `source_url`**, so the CDS corpus's **446 mirror
addresses had never been asked about once**. It also carried its own copy of the
fetching code beside `bin/check-link`, which exists to do exactly that fetching.

## Detailed Design

### 1. `source_documents` adopts `external_documents`

`db/schema/adopt-external-documents-for-source-documents.sql`. Two nullable
foreign keys are added, the rows are linked, the keys are tightened, and the old
columns go:

- `external_document_id UUID NOT NULL REFERENCES external_documents(id)` — the
  college's own Common Data Set filing.
- `archive_external_document_id UUID NULL REFERENCES external_documents(id)` —
  the tidy third-party mirror (`www.collegedata.fyi`) where one exists.
- `source_documents_archive_is_not_the_source_check` — a mirror may not be the
  page.
- `source_url`, `archive_url`, `source_url_status`, `source_url_checked_on` and
  the `source_documents_checked_when_known` CHECK are dropped. There is one home
  for "is this url still served", and it is not here.

Dropping `source_url` takes `source_documents_source_url_nonempty_check` with
it. The empty-url refusal does not disappear, it moves earlier: it is now
`NewExternalDocument`'s own row type, which says "external document url is
empty" before a statement is ever sent — `CanonicalMoneyDaoTest` follows the
message to its new wording.

**It refuses; it does not invent.** The migration's own words, and the rule RFC
220 set: every document it creates carries the evidence the row it came from
already holds.

- A page with no recorded check cannot become a document, because
  `url_checked_on` is NOT NULL. The migration counts
  `source_url_checked_on IS NULL` first and raises `P0001` naming the count and
  the tool that produces the missing answer, rather than writing a date nobody
  established. The committed corpus has none — the committed sweep covers all
  441 pages.
- The page insert is
  `SELECT DISTINCT ON (source_url) ... ORDER BY source_url,
  source_url_checked_on DESC`,
  because one filing backs several colleges (one University of Washington filing
  backs three campuses) and the newest check of a url is the one that is true
  about it.
- **The mirror is re-linked only where somebody has already asked about it.**
  Mirrors are linked to a document that already EXISTS; for the rest the
  nullable column is left NULL and a `RAISE NOTICE` says how many, naming
  `db/seed/cds/link-status.csv` as what returns them at the next ingest.

### 2. `policy_parameters` adopts it, on five hand-swept verdicts

`db/schema/adopt-external-documents-for-policy-parameters.sql`.
`source_document_id UUID NOT NULL REFERENCES external_documents(id)` replaces
`source_url`.

`0077` stored no reading date, so the evidence had to be **established** rather
than carried across. Five literal `external_documents` rows are inserted,
`found` on `DATE '2026-09-17'` — one sweep run by hand with
`bin/check-link -f <the five urls>` and the same again with `-g`, both passes
answering 200 for all five. The migration records the commands in its own
header. They are literals rather than a generated seed because five hand-typed
rows in a migration are reviewable in exactly the diff a CSV would be, and a
generator for five rows is a mechanism for its own sake (D28).

Any row the five literals do not cover is **refused**, by count, with `P0001`
and the instruction to sweep it and re-run: a date is established by a sweep,
never by this migration.

### 3. The testing calendar adopts it

`db/schema/adopt-external-documents-for-test-calendar.sql`. `dates_document_id`
(NOT NULL) and `score_release_document_id` (NULL) replace the two url columns.

**The evidence is the row's own reading date**, on the rule RFC 220 settled for
every cited corpus: a person opened that page on `checked_on` and typed the row
from what it served, so the page was FOUND on that day. The migration says, in
as many words, that this is the same statement
`ExternalDocumentsDao.citationIds` already makes for
`admission_test_state_programmes` (RFC 213) and `fafsa_filing_deadlines` (RFC
215), and that a second rule here would be a second answer to a settled
question. No network, no sweep, no invention.

The paired rule RFC 197 wrote is carried over unchanged, as
`admission_test_administrations_score_release_document_check`: a score-release
date and the page it was read from arrive together or not at all.

The two https-only CHECKs are **not** replaced. `external_documents` refuses a
url with no scheme and accepts `http://` as well as `https://`, on Ian's call at
the RFC 208 gate ("prefer https, but not block insecure"). So the calendar stops
being the one corpus that refuses an insecure publisher page; the general rule
arrives and no guarantee is lost.

`db/schema/ORDER` gains the three files in dependency order: source documents,
policy parameters, test calendar.

### 4. `bin/cds-link-check` becomes `bin/cds-link-sweep`, and fetches through `bin/check-link`

The old 524-line script is deleted and replaced by 349 lines that **judge and do
not fetch**. `bin/check-link` prints one HTTP status per url and refuses to read
the codes; this script is the reader.

- It harvests **both** `source_url` and `archive_url` from every seed CSV in the
  directory — the defect this slice exists to fix — over the DISTINCT urls: the
  four committed seed files hold 5,048 rows but 887 documents.
- RFC 200's two-pass judgement is kept, and the two passes now differ in the two
  dimensions that actually moved a verdict when RFC 200 measured them (Michigan
  State 404 → 206, Saint Joseph 403 → 404): pass one is HEAD with a generic
  agent, pass two is GET with a browser agent.
- The vocabulary is unchanged: `found` is 2xx in either pass, `not_found` is 404
  or 410 in BOTH, everything else is `unknown` **with the date we asked**. A url
  the file does not mention is "we never asked".
- The plausibility bar stays here rather than in the fetcher, because
  `check-link` exits 0 on a machine with no egress having printed `000` for
  everything. Nothing found at all, or more than `DEAD_SHARE_CEILING` (20%)
  gone, and **nothing is written** — writing nothing keeps every link.
- `checked_on` is today in `America/New_York`, so a sweep run at 21:00 Pacific
  is not dated tomorrow. (This is why §2's verdicts are dated 2026-09-17 while
  the commit's own timestamp is 2026-09-16 23:43 Pacific.)
- It is stdlib-only python3, parses options with stdlib `getopt` in the
  short-only `bin/` grammar, and writes **nothing to stdout** on a normal run:
  its product is a file, so even the closing summary is a diagnostic on stderr.

`pass_one` and `pass_two` stay in the output and no verdict is derived from
them. They are the evidence for the verdict beside them, for a human re-reading
the committed file without re-fetching 887 addresses.

### 5. `bin/check-link -g`: GET and never HEAD

One new flag, and the only change to the fetcher's own judgement-free contract:
with `-g` it sends GET outright instead of HEAD-with-fallback, because a second
pass that changes the METHOD is the difference between a host refusing us and a
document that is gone. `GETOPT_SPEC` becomes `hgf:u:c:t:`; the help documents
it; `force_get` is threaded through `check` and `statuses_for`.

### 6. The sidecar is renamed, and the loader sweeps both urls

`db/seed/cds/source-url-status.csv` becomes `db/seed/cds/link-status.csv`, and
its header becomes `url,url_status,checked_on,pass_one,pass_two` — the file is
no longer about one column of one table.

`CdsSeedLoader` follows, name for name: `SOURCE_URL_STATUS_FILE` →
`LINK_STATUS_FILE`, `SOURCE_URL_STATUS_COLUMNS` → `LINK_STATUS_COLUMNS`,
`sourceUrlStatusCsv` → `linkStatusCsv`, and the sidecar is still defaulted from
the aid-policy file's own directory rather than made a fifth required argument.
The defect vocabulary moves with it (`Defect.SidecarUnknownStatus` now reports
`[url_status]`).

The join is still **by url**, and now for both addresses: `readFiling` looks the
mirror up in the same sidecar and passes its verdict down. A url the sidecar
does not mention is `unknown` with no date — and under RFC 220's strict table is
simply **not stored**: the fact row is refused rather than a citation being
invented.

`NewSourceDocument` therefore gains `archiveUrlStatus` and
`archiveUrlCheckedOn`, stated rather than defaulted for the same reason
`sourceUrlStatus` is (a verdict nobody reached must never look like one that
passed, RFC 182), with the paired `require` the page's pair already had.

### 7. The write paths resolve the citation first

- `SourceDocumentsDao.upsert` upserts the page and, where there is one, the
  mirror through `ExternalDocumentsDao`, inside the caller's transaction, then
  writes the two ids. It is wrapped in `runCatching` so an unevidenced citation
  is a `Result` failure and the loader's row-location wrapper still sees it. A
  private `checkedDate` refuses the one case RFC 220 made unrepresentable — a
  verdict with no date — with `IllegalStateException` naming the url: a defect
  surfaced, never a date backfilled.
- `AdmissionTestCalendarDao.upsert` resolves through
  `ExternalDocumentsDao.citationIds`, which **cites and never sweeps**, so this
  path cannot overwrite a known link status with "we never asked" (RFC 182).

### 8. The read paths keep their shape, so nothing above the DAOs learns that storage moved

Every reader re-projects the urls under the names its mapper already used:

- `SourceDocumentJoin.CITATION_COLUMNS` becomes
  `src.url AS source_url, arc.url AS archive_url, src.url_status AS
  source_url_status`,
  and `withDocumentById` adds the inner join on the page and a LEFT join on the
  mirror, aliased `src` and `arc`.
- `BorrowingDao` adds the same two joins to its own CTE query.
- `AdmissionTestCalendarDao.list` joins `external_documents` twice — inner for
  the NOT NULL dates page, LEFT for the score-release page — and aliases
  `dates_source_url` / `score_release_source_url`; the `RETURNING` clause of the
  upsert does the same with two scalar subqueries.
- `PolicyParametersDao.listAll` / `listForAwardYear` select
  `(SELECT e.url FROM external_documents e WHERE e.id = p.source_document_id)
  AS source_url`.
- `CdsRead.decodeSourceUrlStatus`'s corrupt-value message now names
  `external_documents.url_status`, and `SourceUrlStatus`'s own doc comment says
  it backs that column.

### 9. `bin/test-calendar-seed` writes the shape the cycle belongs to

The committed seed for cycle `2026-27` applies BEFORE
`adopt-external-documents-for-test-calendar.sql` drops the two url columns, so
it must go on being generated exactly as it landed. The generator names the
boundary once, as `LEGACY_URL_COLUMN_SEED_THROUGH_CYCLE='2026-27'`, and emits
the old two-url INSERT for a cycle at or below it and the post-RFC 228 shape for
every later cycle: an `external_documents` upsert of the cited urls (`found`, on
the row's own `checked_on`) followed by a fact INSERT citing them by id. Both
shapes are built in the same single awk pass and chosen in the `END` block, so
the boundary is a decision and not a fork in the reading. Moving the constant
would rewrite a landed migration.

### 10. The guard: only `external_documents` declares a url column

`NoUrlColumnOutsideExternalDocumentsTest` asks `information_schema.columns` for
every public column whose name matches `%url%` or `%link%` and compares the set
against a three-entry allowlist, each entry carrying its reason
(`external_documents.url`, `.url_status`, `.url_checked_on`). A table that
declares its own url column fails the build by name, with the instruction to
replace it with a foreign key or justify it in the list.

It is held in both directions, and its ability to fail is **proven, not
asserted**:

- the allowlist is checked against the schema too, so a permit for a column that
  went away must be deleted rather than left behind;
- `external_documents.url` must be in the set, so a typo in the pattern cannot
  pass by seeing nothing;
- and a probe table with a `source_url` column is created and shown to be named
  inside an always-rolled-back transaction.

## Files Modified

| Path                                                                | Change                                                                                            |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `db/schema/adopt-external-documents-for-source-documents.sql`       | NEW. Two keys, the refusal, the DISTINCT ON page insert, the mirror notice, four dropped columns. |
| `db/schema/adopt-external-documents-for-policy-parameters.sql`      | NEW. One key, five hand-swept literals, the unlinked-row refusal.                                 |
| `db/schema/adopt-external-documents-for-test-calendar.sql`          | NEW. Two keys, the carried-over paired CHECK, three dropped CHECKs.                               |
| `db/schema/ORDER`                                                   | Three appended lines, in dependency order.                                                        |
| `bin/cds-link-check`                                                | DELETED (524 lines).                                                                              |
| `bin/cds-link-sweep`                                                | NEW (349 lines). Sweeps both url columns, fetches through `bin/check-link`, keeps the two passes. |
| `bin/check-link`                                                    | `-g`: GET and never HEAD.                                                                         |
| `bin/test-calendar-seed`                                            | `LEGACY_URL_COLUMN_SEED_THROUGH_CYCLE`; the post-RFC 228 seed shape for later cycles.             |
| `db/seed/cds/source-url-status.csv`                                 | DELETED (441 pages).                                                                              |
| `db/seed/cds/link-status.csv`                                       | NEW, COMMITTED SWEEP. 887 documents: 861 found, 13 not_found, 13 unknown, every row dated.        |
| `college/src/main/kotlin/ed/unicoach/college/CdsSeedLoader.kt`      | The sidecar's new name and header; the mirror's verdict read and passed down.                     |
| `db/src/main/kotlin/ed/unicoach/db/models/SourceDocument.kt`        | `archiveUrlStatus`, `archiveUrlCheckedOn`, and the paired `require`.                              |
| `db/src/main/kotlin/ed/unicoach/db/models/SourceUrlStatus.kt`       | Doc: it backs `external_documents.url_status`.                                                    |
| `db/src/main/kotlin/ed/unicoach/db/dao/SourceDocumentsDao.kt`       | Upserts page and mirror through `ExternalDocumentsDao`; `checkedDate` refuses a dateless verdict. |
| `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestCalendarDao.kt` | `citationIds` on the write path; the two joins and aliases on the read path.                      |
| `db/src/main/kotlin/ed/unicoach/db/dao/PolicyParametersDao.kt`      | `source_url` re-projected from `external_documents`.                                              |
| `db/src/main/kotlin/ed/unicoach/db/dao/SourceDocumentJoin.kt`       | `CITATION_COLUMNS` aliases `src`/`arc`; `withDocumentById` adds both joins.                       |
| `db/src/main/kotlin/ed/unicoach/db/dao/BorrowingDao.kt`             | The same two joins.                                                                               |
| `db/src/main/kotlin/ed/unicoach/db/dao/CdsRead.kt`                  | The corrupt-value message names the new column.                                                   |
| `bin/scripts-tests`                                                 | The sweep section renamed; the archive-column assertion; the `-g` method counters.                |
| tests and fixtures                                                  | below                                                                                             |

No coach copy changed, so no prompt version was cut.

## Tests

- **`SourceDocumentsAdoptionTest`** (NEW, 3 tests) — the adoption, run against
  the real database. **The refusals are staged backwards**, inside one
  always-rolled-back transaction: the migration cannot be reached forwards any
  more, so the table is returned to its pre-migration shape, given the row the
  migration exists to refuse, and then the **committed migration file is
  executed verbatim** — a copy of the SQL in the test would prove only that the
  copy refuses. The three: a page with no recorded check refuses the migration
  _by name and count_; a mirror nobody asked about is left unlinked and never
  dated, and is not stored at all; the migrated table enforces its new shape
  (NOT NULL on `external_document_id`, and
  `source_documents_archive_is_not_the_source_check`).
- **`CitationAdoptionSchemaTest`** (NEW, 3 tests) — the other two adoptions, as
  the database now sees them: the calendar refuses a score release with no page
  and a page with no release; a calendar row citing no dates page is refused by
  NOT NULL (`23502`); a federal parameter with no `source_document_id` cannot be
  written, and one with a real citation can.
- **`NoUrlColumnOutsideExternalDocumentsTest`** (NEW, 2 tests) — §10: the guard,
  and the proof that the guard can fail.
- **`CdsLinkCheckSidecarPinTest`** — the script and the loader are one fact in
  two languages, so the pin follows the rename: `OUTPUT_NAME` against
  `CdsSeedLoader.LINK_STATUS_FILE`, `OUTPUT_HEADER` against
  `LINK_STATUS_COLUMNS` _in order_, and the sidecar's absolute resolution beside
  the seed.
- **`CdsSeedLoaderTest`** — two names change to say what RFC 220 made true: a
  `not_found` url still lands on its document with the day we looked, and a run
  with no sidecar at all now _refuses to store a citation_ rather than asking
  nobody and carrying on. Both resource fixtures move to the new header and gain
  the mirror row; `college/src/test/resources/link-status.csv` is added.
- **`bin/scripts-tests`** — the sweep's section is renamed and its fixture seeds
  now carry an `archive_url` column; the new assertion is that **the sweep
  harvests `archive_url` too, not only `source_url`**. The three refusals (dead
  share, all-404, nothing listening), the empty stdout, the exact header, the
  url-ordered rows and the `-h` text are all kept against the local fixture
  server. For `-g`, the fixture server counts methods: a `-g` run sends one GET
  and no HEAD, and without `-g` HEAD is still tried first.
- **Fixture updates** — `MoneyVocabularyLoaderTest`, `PolicyParametersDaoTest`,
  `FederalAidPolicyServiceTest`, `CoachingTestDb`, `BorrowingDaoTest`,
  `CanonicalMoneyDaoTest`, `CdsAdmissionsDaoTest`: every fixture that wrote a
  url now writes an `external_documents` row and cites it. `CoachingTestDb`
  states `FOUND` with a date for both urls, because a fixture is not exempt from
  RFC 220.

Executed on the landing run, as recorded in the brief 0010 ledger: **3,591
tests, 0 failures; 1,142 shell assertions; 45 calendar-seed assertions.**

## The operator step

One, and it is committed rather than described: a sweep of all 887 cited urls,
dated 2026-09-17 — **861 found, 13 not_found, 13 unknown**, every row carrying
its date and its two raw pass codes, in `db/seed/cds/link-status.csv`. Of those
887, **446 are mirror addresses that had never been checked once** before this
slice.

## What this slice does not do

- It does not touch the other tables D26 counted. The three CDS fact tables
  (`0054`) and the canonical money tables (`0083`) already cite
  `source_documents` by `source_document_id` rather than carrying urls, and
  `admission_test_state_programmes` landed on `external_documents` with RFC 213
  (`db/schema/adopt-external-documents-for-state-programmes.sql`). After this
  slice the guard in §10 proves the set is closed.
- It adds no `document_captures` table and no per-owner join table. D26 named
  both; this slice is the url migration only.
- It changes no coach copy and cuts no prompt version.

## Where the code has moved since

`CdsSeedLoader.kt` was substantially reworked by RFC 239 (`main@a53b7be3`, "a
score is money"), which also edited `CdsSeedLoaderTest` and
`AdmissionTestCalendarDaoTest`. The sidecar contract this RFC landed survived
that rework unchanged — `LINK_STATUS_FILE`, `LINK_STATUS_COLUMNS` and the
five-column header are still what the loader asserts and what
`CdsLinkCheckSidecarPinTest` pins. Nothing else this RFC touched has been
changed since.

## What this document cannot say

Recoverable from the tree: every shape above, every refusal, every test, and the
reasoning the migrations and doc comments state in their own words. **Not**
recoverable, and therefore absent: which alternatives the author weighed (a
polymorphic owner column, a generated seed for §2's five rows, and keeping the
mirror unlinked permanently are all _mentioned_ in committed comments, but
whether they were ever live options is not something the code can tell us); what
the approval gate asked for beyond the RFC 208 https call the test-calendar
migration cites; and the implementation plan the original document would have
carried.
