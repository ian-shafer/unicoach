# RFC 223 — the fifty-one typed FAFSA deadlines

Slice `fafsa/02/fifty-one-deadlines-typed`, brief 0012
(`product/0012-fafsa-support/`). Lane A. Follows RFC 215, which landed
`fafsa_filing_deadlines` and NO rows.

## Summary

The rows. A hand-typed, per-row-cited corpus under `db/seed/fafsa-deadlines/`, a
generator that validates it and writes the seed migrations, its shell-test
harness, and a `:db` seed test that fails the build when the corpus rots or
stops covering the cycle families are filing.

This is the consolidation brief 0012 D4 asked for: the work we do once so that
fifty-one agency pages do not become a family's homework.

## Detailed Design

### 1. What ships, and what deliberately does not

The input is `product/0012-fafsa-support/discover/state-deadlines-merged.csv` —
148 harvested rows, read on 2026-09-14, already cited. It is INPUT. The
committed artifact is written from it, row by row, under these rules:

- **An unverified row does not ship** (D9). 21 harvested rows were never
  confirmed against the publisher's own page; they are dropped. Four
  jurisdictions — **Arkansas, Delaware, North Carolina, New Hampshire** — have
  no verified row at all and so appear in the corpus not at all. They are named
  in `PROVENANCE.json` as needing human eyes, the way the state-programme corpus
  names its absent jurisdictions. `fafsa/04` turns that absence into our own
  sentence: we went to the agency's page and could not get an answer.
- **`unknown` is not a kind.** The landed table has three:
  `date | wording | none`. The three harvested "we could not establish this"
  rows are unverified anyway and are dropped. A missing row IS the unknown (D9).
- **A secondary aggregator is not a publisher.** One shippable harvested row
  (South Dakota's per-institution list, sourced to _Mapping Your Future_) cites
  an aggregator rather than the agency. `publisher` is rendered by the page as
  the link text, so such a row would print an aggregator's name as the state's
  answer. It is dropped and recorded in `PROVENANCE.json`; South Dakota still
  ships its verified `none` row from the state's own site.
- **A Wayback row cites the AGENCY, through the archive.** Six shippable rows
  were read through a Wayback copy because the agency blocks every client we
  have. The landed table has two keys for exactly this (D16): `source_url` is
  the agency's own address, `archive_url` is the archived address it was read
  through. The original address is recoverable from the Wayback url, and is
  typed into the CSV by hand — the generator does not unwrap urls.

That leaves **124 state rows across 47 jurisdictions** — 104 of the 2026-27
cycle in all 47, and 20 of 2027-28 in 11 — plus the four federal rows of section
2, so **128 rows** in the committed file. (Two harvested rows fell out during
transcription and are named in `PROVENANCE.json`: Arizona's list of UNIVERSITY
priority dates, whose own research note says "do not present as a state
deadline", and a Maine row belonging to the closed 2025-26 cycle.)

### 2. The federal rows are typed here, from scratch

The harvest has **zero federal rows** (D17). Four are typed in this run, read
live off the publisher's page on the day of the run:

| award year | event                  | publisher                    | source                           |
| ---------- | ---------------------- | ---------------------------- | -------------------------------- |
| 2026       | `form_opens`           | U.S. Department of Education | ED press release, 24 Sept 2025   |
| 2026       | `final_deadline`       | U.S. Department of Education | Federal Register, 91 FR 37096    |
| 2026       | `corrections_deadline` | U.S. Department of Education | Federal Register, 91 FR 37096    |
| 2027       | `form_opens`           | U.S. Department of Education | the statute / the fsapartners EA |

`program` is `Federal student aid`, the name the DDL's own comment uses.

The 2027-28 federal `form_opens` row is a **`wording` row, not a date**: what is
published today is "not later than October 1" (Pub. L. 118-145) and "public
release by Oct. 1, 2026" (the beta announcement). Neither names the day the form
actually opened, and the 2026-27 form opened a week EARLY. A `date` row here
would be our guess wearing the Department's name.

The 2027-28 federal deadline and corrections dates are **not typed**: the
Federal Register notice for that award year is not published yet and the only
figures we have are unverified snippets. D9 governs.

**Three corrections that came out of reading those pages live, on 2026-09-15,
rather than trusting the research report:**

- The Federal Register notice does **not** attach "11:59 p.m. Central" to the 30
  June 2027 submission rows. Footnote 1 carries that cut-off and its marker
  appears only on the electronic CORRECTIONS rows. So the submission row is the
  day and nothing else, and the corrections row is the day plus the footnote's
  words. Printing "11:59 p.m. Central" beside 30 June and citing 91 FR 37096
  would attribute to the publisher something it does not say.
- The 2026-27 open-date press release names no date IN its sentence — it says
  "Today ... is online and available" under a dateline of September 24, 2025. So
  `deadline_as_published` carries the dateline and the sentence together, and
  the reader can see which half is the publisher's date.
- The 2027-28 `form_opens` row is confirmed as `wording`, and for a sharper
  reason than the design first had: both publishers state an upper BOUND. The
  announcement says "ahead of the public release by Oct. 1, 2026" and the
  statute says "not later than October 1 prior to the applicant's planned year
  of enrollment". Neither says the form opens ON that day, and the 2026-27 form
  opened a week early.

### 3. The one-day disagreement, and why it is one row

D17 records that the Federal Register prints **11 September 2027** for the
2026-27 corrections deadline while studentaid.gov is reported to print the
**12th**, and says the disagreement is stored with its sources rather than
resolved by us.

It is stored — in `PROVENANCE.json`, under `known_limits`, with both urls, both
figures and what each publisher was actually observed to say. It is **not two
rows**, for two reasons that both come from landed artifacts:

- The natural key is `(award_year, state, program, event)` with
  `NULLS NOT DISTINCT`, precisely so that "two disagreeing federal rows" cannot
  both land (`create-fafsa-filing-deadlines.sql`). Making them land would mean
  bending `program` — a source-defined program NAME — into a publisher axis.
- studentaid.gov is a JavaScript application that returns no deadline text to
  any client we have, and this run PROVED that rather than assuming it on
  2026-09-15: the page serves an Angular shell; its own content endpoint
  (`/content/en/fafsa-deadlines.json`) still publishes the 2023-24 federal
  figures; none of the twenty-four application bundles contains any 2027 date; a
  Googlebot user agent is blocked and a rendering proxy answers 403; and the
  Internet Archive was returning 429 and 503 and produced no copy. The only
  support for the 12th is a Google search-index snippet. A figure read from a
  search snippet is exactly the unverified fact D9 refuses to ship.

So the committed row is the Federal Register's, which is the authoritative
publisher of the deadline, and the second reading is recorded as a limit of this
corpus rather than as a fact about the world.

### 4. Both open cycles ship, because D23 needs them

D11 says one cycle at launch — the cycle now being filed. D23, decided after RFC
215 landed, says each jurisdiction shows the newest cycle we hold a **verified**
row for, and names the measurement: all 51 were attempted and 47 hold a verified
2026-27 row, while only 11 hold a verified 2027-28 one. `fafsa/03` cannot
implement D23 against a corpus that holds one cycle.

There is a second, harder reason. `FafsaFilingCycle.currentFilingYear` turns
over on **1 October**, which is a fortnight away. From that day the cycle being
filed is 2027-28, and a corpus holding only 2026-27 rows would fail the D11
build guard the moment the month changed — correctly, and with nothing anyone
could do about it except type rows we already hold.

So the corpus ships the verified rows of both open cycles. Every row names its
own cycle in `award_year`; nothing anywhere renders a cycle-less date.

### 5. The authored file

`db/seed/fafsa-deadlines/deadlines.csv`, one row per deadline, header exactly:

    state,award_year,program,event,deadline_kind,deadline_date,deadline_as_published,publisher,source_url,archive_url,checked_on,expires_on

- `state` — the USPS code, or **empty for the federal rows**, mirroring the
  nullable column whose NULL means federal.
- `publisher` — D24(a). `external_documents` deliberately carries no publisher,
  and without this column the public page would print bare urls.
- `deadline_as_published` — the landed column name (D18b), on every row whatever
  its kind. Verbatim publisher text is written **inside double quotes**; an
  unquoted cell is our own factual description of what the page does not say,
  which is the only honest thing a `none` row can carry when the page's absence
  has no sentence of its own.
- `deadline_date` — present exactly when `deadline_kind` is `date`.
- `archive_url` — empty unless the agency's page had to be read through an
  archive.
- `checked_on` / `expires_on` — **per row, not per session.** The
  state-programme sibling requires every row of a reading session to share one
  pair, because one agent re-read all fifty pages in one sitting. Here the state
  rows were read on 2026-09-14 and the federal rows on the day of this run, and
  a rule that forced them to agree would force a false date onto one of them.
  The staleness guard is per jurisdiction, so it does not need them to agree.

**This CSV is RFC 4180, with quoted fields.** The cells carry the publishers'
own sentences, and those sentences carry commas ("March 2, 2027, 11:59 p.m.
Pacific") and quotation marks. The sibling generator's naive comma split, and
its refusal of any cell containing a double quote, cannot read this corpus at
all.

### 6. The generator is `bin/fafsa-deadline-seed`, in python3

A sibling script, not a generalisation of `bin/test-state-programme-seed` —
`discover/repo-grounding.md` §3 argues that with evidence, and §5 above adds the
decisive one: the two files are not the same format.

It is **python3**, which is a departure from the sibling and is the reason to
say so plainly: quoted-CSV parsing and SQL literal escaping over publisher prose
is exactly what `csv` and a careful escaper do correctly and what awk field
splitting does not. `bin/` already has nine python scripts and
`bin/pyfunctions.py` is the declared python counterpart of `bin/functions`,
carrying the reserved usage exit codes, the `log_*` family that writes to
stderr, and `parse_short_options` (stdlib `getopt`, short options only). Every
`bin/` convention holds: short options, all logging on stderr, nothing on stdout
on a normal run, reserved exit codes.

    bin/fafsa-deadline-seed [-c csv] [-d schema-dir] [-n] [-h]

      -c  the authored CSV (default db/seed/fafsa-deadlines/deadlines.csv)
      -d  the schema directory to generate into (default db/schema)
      -n  dry run: writes nothing; exit 3 if the tree differs from what a
          real run would generate
      -h  help

      0   generated, or already correct
      1   refused: the CSV is not usable, or the corpus cannot be written
      3   -n only: the tree differs from what would be generated
      9   refused: db/schema/ORDER cannot be appended to safely
      10, 11, 20, 22  usage errors

**It validates before it writes, and reports every fault in one pass with line
numbers.** Each predicate that mirrors a landed CHECK carries a
`MIRRORS <constraint-name>` comment naming it, and the harness reads the
constraint names out of `db/schema/create-fafsa-filing-deadlines.sql` and
asserts that this script mirrors exactly those — so a constraint added to the
table later cannot go unmirrored in silence. That is the sibling's mechanism,
kept verbatim, and it is what D24(c) asks for: the LANDED names, including
`deadline_as_published` and the trimmed-text rules, not the names in the
research report.

The mirrored rules, by landed constraint name:

| landed constraint                          | the CSV rule                                                                              |
| ------------------------------------------ | ----------------------------------------------------------------------------------------- |
| `..._state_format_check`                   | `^[A-Z]{2}$` or empty (federal)                                                           |
| `..._event_check`                          | one of the four `AidDeadlineEvent` values                                                 |
| `..._kind_check`                           | one of `date`, `wording`, `none`                                                          |
| `..._date_iff_kind_check`                  | a day exactly when the kind is `date`                                                     |
| `..._opening_is_dated_check`               | a `form_opens` row is never `none`                                                        |
| `..._date_in_cycle_check`                  | `[award_year-1-07-01, award_year+1-12-31]` — **D24(d)**                                   |
| `..._program_nonempty_check`               | trimmed and non-empty                                                                     |
| `..._deadline_as_published_nonempty_check` | trimmed and non-empty                                                                     |
| `..._publisher_nonempty_check`             | trimmed and non-empty                                                                     |
| `..._archive_is_not_the_source_check`      | an archive url is never the source url                                                    |
| `..._expiry_after_checked_check`           | `expires_on > checked_on`                                                                 |
| `..._checked_in_or_after_cycle_check`      | `checked_on >= award_year-1-07-01`                                                        |
| `fafsa_filing_deadlines_natural_key`       | no duplicate `(award_year, state, program, event)` within one reading day (see section 7) |

Three more mirror a declaration that lives OUTSIDE this table, and carry the
same `MIRRORS` tag because the harness resolves them against `db/schema` too:
the `academic_year` DOMAIN's `2000..2100` range on `award_year`, the
`external_documents` url shape on `source_url` and `archive_url`, and the
`link_status` domain on the swept file's `url_status`.

Then two rules no database declaration can state:

- **membership, not shape** — a state cell must be one of the 59 published USPS
  codes (`db/data/codebooks.json`, the same file
  `AdmissionTestStateProgrammeSeedTest` asserts against), because `^[A-Z]{2}$`
  admits 676 strings and 617 of them name nothing;
- every cell is printable ASCII — a smart quote pasted off a state page and a CR
  from a CRLF checkout are named by line number rather than escaped into a SQL
  literal whose meaning depends on a server setting.

### 7. The generated seeds, and what makes one immutable

One seed per **reading day**:
`db/schema/seed-fafsa-filing-deadlines-<yyyy-mm-dd>.sql`, holding every row
whose `checked_on` is that day, and appended to `db/schema/ORDER`. This run
produces two: the 2026-09-14 harvest reading, and the federal reading of the day
the run lands.

Naming by reading day, and not by cycle, is what makes the corpus's own
correction rule work. `create-fafsa-filing-deadlines.sql` states it: never
hand-edit a generated seed, and a corrected row lands as a NEW generated seed
upserting on the natural key rather than as an edit to a landed one. You cannot
correct a row here without re-reading its page, and re-reading its page gives it
a new `checked_on`, which puts it in a new file. A migration corpus is replayed
from the top on every `db-reset`, so a landed file must never change; the
generator therefore **refuses** a CSV row that rewrites a tuple an existing
generated seed already carries under the same reading day, naming the file, and
refuses a tuple that a landed seed has and the CSV no longer does. That is the
sibling's append-only guard with reading-day granularity.

That refusal carries its own escape, once per offending FILE and not once per
row: if the seed has NOT landed yet — you are still authoring this reading —
delete it and its `ORDER` line and re-run. It is written down because it bit
this run: changing `expires_on` mid-run made the generator refuse all 128 rows
against seeds it had itself written ten minutes earlier.

**The in-file key is per READING DAY, and this corrects the table above.** A
correction is a re-read: the landed row STAYS in the CSV, because deleting it
would change a landed seed, and the corrected row joins it with a new
`checked_on`. So one natural key legitimately appears twice in the file. What
may never happen is two rows with one key on ONE reading day -- they would land
in a single `INSERT ... ON CONFLICT DO UPDATE`, which PostgreSQL refuses
outright ("cannot affect row a second time"). The generator's uniqueness rule is
therefore `(award_year, state, program, event, checked_on)`, and the harness
proves both halves: refused within a day, filed separately across two.

Each seed writes its `external_documents` rows FIRST and references them by id —
**D24(b)**, and the shape `adopt-external-documents-for-state-programmes.sql`
already put on this table's closest sibling:

    INSERT INTO external_documents (url, url_status, url_checked_on)
    VALUES ('...', 'found', DATE '2026-09-16'), ...
    ON CONFLICT (url) DO NOTHING;

    INSERT INTO fafsa_filing_deadlines
        (award_year, state, program, event, deadline_kind, deadline_date,
         deadline_as_published, publisher, source_document_id,
         archive_document_id, checked_on, expires_on)
    VALUES
      (2026, 'CA', 'Cal Grant', 'priority_deadline', 'date', DATE '2027-03-02',
       '...', 'California Student Aid Commission',
       (SELECT id FROM external_documents WHERE url = '...'), NULL,
       DATE '2026-09-14', DATE '2027-06-30')
    ON CONFLICT (award_year, state, program, event) DO UPDATE SET ...;

`DO NOTHING` on the documents and never `DO UPDATE`: a url this seed cites may
already have been swept by `bin/check-link`, and re-stating it must not reset
`url_status` to "we never asked" (RFC 182, RFC 213). The id is subselected by
url because it is a `uuidv7()` the database mints and a generated file cannot
know it.

**The sweep is not optional, because the schema no longer allows it to be.**
`test/05.4/no-citation-without-a-check` LANDED as RFC 220
(`db/schema/require-a-check-on-every-external-document.sql`): it dropped
`external_documents.url_status`'s DEFAULT and made `url_checked_on` NOT NULL, so
`INSERT INTO external_documents (url) VALUES (...)` — the shape this design
first carried — cannot be applied to a migrated database at all. It refuses
rather than backfills, because a made-up date is a check that never ran looking
like one that passed (RFC 182), and it says in as many words that a future
caller sweeps its urls with `bin/check-link` BEFORE storing them.

So this corpus carries its own sweep, as the state-programme sibling does:
**`db/seed/fafsa-deadlines/link-status.csv`**, header
`url,url_status,url_checked_on` — the column order `external_documents` names
them in — one row per distinct cited url, source and archive alike, because an
archived address is a document like any other. It is a SOURCE file a human
reviews in a diff, exactly like `deadlines.csv`, and it sits beside it so that
`-c` names both. The mapping from an HTTP code to RFC 200's three words is the
CALLER's and is the sibling's verbatim: `2xx` is `found`, `404` and `410` are
`not_found`, and everything else — a 403, a 5xx, a `000` — is `unknown` WITH the
day we asked, because that keeps the link. Several agency sites block every
client we have, and recording their live pages as dead is a worse answer than
the one a family gets. `bin/fafsa-deadline-seed` itself touches no network: it
reads that committed answer, and **a cited url with no row in it is a fault
named by its line in `deadlines.csv`**, which is the same guarantee RFC 220 put
in the schema, moved to where a human can fix it.

`DO NOTHING` on the documents and never `DO UPDATE` is unchanged and matters
more now, not less: a url this seed cites may already have been swept — by the
state-programme corpus, or on a later day than this file records — and
re-stating it must not reset `url_status` to an older answer (RFC 182, RFC 213,
RFC 220).

### 8. `expires_on`, and the two build guards

`expires_on` is **`checked_on` plus 365 days** — one year from the day a human
read the page. It is a fact about the READING, not about the cycle.

It was first written as "30 June of `award_year + 1`", the end of the award year
the row describes, and the seed test's author showed that rule makes the second
guard below UNFIREABLE: that day is some nine months after the cycle stops being
the one being filed, so every live row is always at least 273 days from expiry
and a 90-day bar can never bite inside a filing window. A guard that cannot fire
is decoration. Under the reading-based rule the build starts failing in mid-June
2027, which is the yearly reminder D11 and D7 ask for.

`db/src/test/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineSeedTest.kt` reads the
committed CSV from disk, before any migration loads it, on the
`AdmissionTestStateProgrammeSeedTest` precedent, and fails the build on:

1. **No rows for the cycle now being filed** (D11) —
   `FafsaFilingCycle.currentFilingYear(today)`. This is the yearly reminder and
   it is deliberate.
2. **A live row within `HandTypedReferenceData.REFRESH_LEAD` (90 days) of its
   `expires_on`**, per jurisdiction, over the rows of the cycle now being filed
   — the sibling's rule, and its reason: a stale state must not be able to hide
   behind a fresh neighbour. The federal rows are a jurisdiction like any other.

Both failure messages carry the refresh instruction in full — re-read the page,
append rows with a new `checked_on`, update `PROVENANCE.json`, re-run the
generator — because whoever hits this is being told to do a thing, and a message
that names neither the file nor the command gets ignored for a week.

The same test asserts the `PROVENANCE.json` manifest against the file it
describes (sha256 and row count), that every state cell parses through
`UsStateCodes.parse`, that no committed row's `deadline_date` falls outside its
own cycle window, and that the four unsourced jurisdictions are named in the
manifest's coverage block.

### 9. `PROVENANCE.json`

The sibling's shape, and wider where this corpus knows more. `sha256` and
`rows_by_file` cover the authored CSV and nothing else, because that is the file
the seed test recomputes. Beside them:

- `cycles` — how many rows and jurisdictions each open cycle holds, which is the
  measurement D23 turns into a per-jurisdiction display rule;
- `coverage` — `jurisdictions_attempted`, `jurisdictions_with_rows`, `rows`,
  `federal_rows`, **`absent`** (AR, DE, NC, NH, with the note that all four
  block machine reading) and `out_of_scope` (PR, GU, VI, per D13);
- `known_limits` — the federal one-day disagreement and the proof studentaid.gov
  cannot be read; the submission row that carries no time of day; the unwritten
  2027-28 federal deadlines; the dropped aggregator row; Maine's closed cycle;
  and the 21 unverified rows;
- `judgement_calls` — the rows where a human had to choose, including the states
  whose own page is stale (Oregon, North Dakota), the yearless recurring dates
  kept as wording, and the splits that keep two facts as two rows;
- `link_sweep` — the swept file, the day, the tool, the counts, and every
  `not_found` and `unknown` url by name and reason (section 7).

Three of those blocks are asserted by the seed test, so the manifest cannot
quietly shrink while the corpus grows.

### 10. The harness, and the gate map

`bin/fafsa-deadline-seed-scripts-tests`, the sibling's shape: fixtures built
from the script's own declared header, a refusal case per validator predicate,
the `-n` drift case, the ORDER append, the "nothing on stdout" assertion, and
the sweep that reads the landed constraint names out of the DDL and asserts the
script mirrors exactly those.

It is registered in **`bin/shell-tests`** and in **`bin/gate-scope`'s
`KNOWN_HARNESSES`**, in the same scopes as
`test-state-programme-seed-scripts-tests`. That is not optional:
`bin/gate-scope` fails the commit gate BY NAME for a `bin/*scripts-tests` its
table does not know (CLAUDE.md, RFC 214).

## Files Modified

New:

- `db/seed/fafsa-deadlines/deadlines.csv` — the authored corpus (128 rows).
- `db/seed/fafsa-deadlines/link-status.csv` — the committed sweep of the 84
  distinct urls it cites (section 7).
- `db/seed/fafsa-deadlines/PROVENANCE.json`
- `bin/fafsa-deadline-seed` — the generator (python3).
- `bin/fafsa-deadline-seed-scripts-tests` — its harness.
- `db/schema/seed-fafsa-filing-deadlines-2026-09-14.sql` — GENERATED.
- `db/schema/seed-fafsa-filing-deadlines-2026-09-15.sql` — GENERATED, the
  federal reading.
- `db/src/test/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineSeedTest.kt`
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineFixture.kt`
  — the committed-corpus reader, in `testFixtures` where the
  `AdmissionTestStateProgrammeFixture` precedent and the `commons-csv`
  dependency already live, so `db/build.gradle.kts` is not touched.

Changed:

- `db/schema/ORDER` — two appended lines (`ship-order` places them at land).
- `bin/shell-tests` — the new harness in its list and its help.
- `bin/gate-scope` — the new harness in `KNOWN_HARNESSES` and its scopes.

Not touched: the table, the models, the DAO — all landed in RFC 215 and correct.

## Implementation Plan

1. `bin/fafsa-deadline-seed` and its harness, against a small fixture CSV.
2. The federal rows, read live off the publishers' pages on the day of the run.
3. The 124 state rows, transcribed from the harvest in four blocks by
   jurisdiction, each row keeping its publisher's own words, its agency url, its
   `checked_on`, and — where a state also demands its own application — the
   SEPARATE ROW that demands it (D14: Tennessee's Promise application, New
   Jersey's NJFAMS record, Pennsylvania's two school-type rows, Virginia's,
   Oklahoma's and Utah's own programmes are all rows of their own, because a
   single date per state would make a family miss half the work).
4. `PROVENANCE.json`, and the generator run that writes the seeds.
5. The seed test and its fixture.
6. `bin/shell-tests` and `bin/gate-scope` registration.
7. `nix develop -c bin/test`, then the harness, then the gate.

## Tests

**`bin/fafsa-deadline-seed-scripts-tests`** — one case per refusal, each
asserting the message names the LINE: a fake date under a `wording` rule; a
`date` row with no day; a `form_opens` row of kind `none`; a state code that is
two capitals but not one of the 59; a lower-case state code; a date outside its
own cycle window (probed with LAST cycle's date, not one two cycles back — the
defect RFC 215's review caught, and the reason this is written down here); a
citation with no url; an archive url equal to its source; an untrimmed
`program`; an empty `deadline_as_published`; a duplicated natural key; an
`expires_on` before `checked_on`; a `checked_on` before its cycle opened; a
non-ASCII byte; a header that is not the expected header. Plus: every fault
reported in ONE pass; nothing on stdout on a normal run; `-n` exit 3 on a
hand-edited seed and exit 0 on a correct tree; the ORDER line appended once and
only once; the DDL-constraint-name sweep.

**`FafsaFilingDeadlineSeedTest`** — the committed corpus has rows for the cycle
now being filed; a corpus that does not fails, by name; a jurisdiction within 90
days of expiry fails and is NAMED; a stale jurisdiction cannot hide behind a
fresh neighbour; appending a re-read clears the guard; the manifest matches the
file; every state cell is a published USPS code; the federal rows carry an empty
state cell and are not mistaken for a jurisdiction; AR, DE, NC and NH are named
in the manifest as absent.

**The database itself** — `bin/test` recreates and migrates the test database on
every run, so the generated seeds APPLY there, against the real landed CHECK
constraints, on every run of the suite. A row the validator wrongly admits fails
the migration, which is the backstop for every mirrored predicate above.
