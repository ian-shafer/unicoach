# RFC 176 — Drop the publisher shape

Product slice `shape/08/drop-the-publisher-shape` (brief
`product/0006-money-in-unicoach-shape`, gate 2 approved 2026-09-02). The last
slice of the money-shape brief: `colleges` ends as identity, location and codes,
and every money number the product speaks comes from the canonical layer.

## Summary

Brief 0006 D10 set the cutover contract — _beside, backfill, cut over, drop
last_ — and named the condition for the drop: **reader count zero**. RFC 158
built the canonical store beside `colleges`, RFC 161/162 filled it, RFC 166
moved every cost answer onto it, and RFC 169 put search's published-price ruler
on it. This RFC does the last two steps: it cuts the readers that are still on
the publisher-shaped columns, then drops those columns.

A current-tree audit (`.scratch/ship/rfc-176/findings/research-readers.md`)
found that **reader count is not zero today**, so this run is not the pure
`DROP` the slice text anticipated. Of the 18 money columns on `colleges`:

- **9 are already at reader-count zero** — `cost_of_attendance_per_year_usd`,
  `tuition_and_fees_in_state_per_year_usd`,
  `tuition_and_fees_out_of_state_per_year_usd`, and the six RFC 149 cost
  components (`housing_and_food_{on,off}_campus_per_year_usd`,
  `books_and_supplies_per_year_usd`,
  `other_expenses_{on,off}_campus_per_year_usd`,
  `other_expenses_with_family_per_year_usd`). Ingest writes them; nothing but
  `admin-web` reads them, and the six components are not even on the admin page.
- **9 still reach a user** — `net_price_per_year_usd` (indirectly, via the
  search-index copy at `CollegesDao.kt:2646`), the five band columns
  `net_price_per_year_income_q1..q5_usd`, `median_earnings_10y_after_entry_usd`,
  `median_debt_at_completion_usd`, and `pell_share`. All nine arrive through the
  `search` / `similar_colleges` match payload (`CollegeMatchRow`), plus fit lens
  for earnings.

Every one of those nine has a canonical home already filled from the same
Scorecard CSV: `cohort_money_stats` carries `avg_net_price` (including the
`q1..q5` band series), `median_earnings_10y`, `median_debt_at_completion` and
`pell_share`. Nothing new is stored. This RFC re-points the readers, proves the
count is zero, and drops the columns.

**Zero behaviour change is the acceptance test**, with one named exception
(D12): the same numbers reach the same surfaces under the same wire keys, except
where the canonical store already holds the publisher's own upstream figure in
place of the Scorecard's copy of it — which is the figure every other money
surface has served since RFC 166. The canonical layer can say more than the
columns could — a suppression named as the publisher's rather than a bare `null`
— and this RFC deliberately does **not** take that up. Saying more is a product
slice with a door; this one is invisible when done right.

## Decisions

### D1 — Cut the nine live readers, then drop all eighteen, in one run

The alternative was two runs: drop the nine dead columns now, cut readers later.
Rejected. A half-dropped publisher shape is the worst of both states — the
mirror invariant between `colleges` and `colleges_versions` is broken, the
loader writes two shapes, and `log_college_version()` is restated twice for one
end state. The cut is small (one SQL lateral and one rebuild source), so the run
does it once.

### D2 — Payload money is read live from canonical in the search query, not materialised into the index

The five band prices, earnings, debt and Pell share are **display values for the
returned page**, not rulers. Nothing filters, sorts or ranks the corpus on them
— confirmed against `CollegeQuery`, `PriceRuler` and `PriceRulerSql`. They are
selected today by joining `colleges c` for at most 25 rows, alongside `c.city`
and `c.website`.

They move to a `LEFT JOIN LATERAL` over `cohort_money_stats`, added beside
`PUBLISHED_TUITION_TIERS_LATERAL` and shaped exactly like it — RFC 169
established that pattern for precisely this case, live canonical money for the
returned page. Copying them into `college_search_index` instead would put eight
more columns into a table that exists to rank a corpus, and would make every
value one rebuild stale.

`DISTINCT ON (measure, income_band) … ORDER BY vintage DESC` serves the newest
vintage per cell, the same rule the cost path uses.

### D3 — The search index keeps its net-price ruler; only its SOURCE moves

`college_search_index.net_price_per_year_usd` and `net_price_percentile_share`
ARE rulers: `PriceRuler.NetPrice` is the default ruler and the
no-residency-on-file fallback, and fit lens hard-codes it. They stay. The
rebuild stops copying `colleges.net_price_per_year_usd` and reads the canonical
cell instead.

Retiring the net ruler outright would leave a family with no residency on file
with no price filter, no price sort and no price axis at all. That is a product
decision for /chart, not a consequence of a schema cleanup. RFC 169 left "the
column-shape end state" to this slice (`0091:35-37`); the end state is that the
index carries rulers, sourced from canonical.

### D4 — Every canonical address is pinned in full, from the house enums, and cross-checked against the loader

`cohort_money_stats` holds **two** `avg_net_price` series — the Scorecard NPT4
family and SFA's grant-aided cohort — which differ by population and aid scope,
never by vintage. RFC 166 shipped a reader that keyed on
`(measure,
income_band)` and took the newest vintage; it would have served the
SFA figure as the overall average net price, and a green suite proved nothing
because the fixtures wrote at the reader's own addresses.

So: the new lateral pins the **full** address, the SQL is generated from
`MoneyMeasure` / `CohortPopulation` / `CohortAidScope` / `IncomeBand` rather
than typed as literals, and a **cross-module contract test asserts the search
reader's address grid matches `CanonicalMoneyLoader`'s in both directions** —
the closing test RFC 166 added for the same hazard.

The four addresses this RFC reads:

| payload                               | measure                     | population                              | aid scope                | band     |
| ------------------------------------- | --------------------------- | --------------------------------------- | ------------------------ | -------- |
| `net_price_by_income_band`            | `avg_net_price`             | `title_iv_aided_undergraduates`         | `federal_aid_receiving`  | `q1..q5` |
| index net-price ruler                 | `avg_net_price`             | `title_iv_aided_undergraduates`         | `federal_aid_receiving`  | NULL     |
| `median_earnings_10y_after_entry_usd` | `median_earnings_10y`       | `employed_not_enrolled_10y_after_entry` | `all`                    | NULL     |
| `median_debt_at_completion_usd`       | `median_debt_at_completion` | `federal_loan_borrowing_completers`     | `federal_loan_borrowing` | NULL     |
| `pell_share`                          | `pell_share`                | `undergraduates`                        | `all`                    | NULL     |

Two refinements the implementation forced, recorded here because both are the
kind of detail that looks like a typo and is actually the decision:

- **`ORDER BY vintage DESC NULLS LAST`, not `DESC`.** Since 0087 `vintage` is a
  nullable `academic_year` and `undated` is SQL NULL; Postgres sorts NULLS FIRST
  on `DESC`, so a plain `DESC` would let an undated row beat every dated one.
  `NULLS LAST` states the policy `:service` already holds
  (`CollegeFigures.VINTAGE_ORDER` — undated sorts oldest), and an undated row is
  still served when it is the only one, which is the case for median debt and
  median earnings.
- **`residency_scope` is deliberately NOT pinned** — the address is measure +
  population + aid scope + band. The Scorecard's net-price scope is
  control-keyed (`in_state_rate_paying` at a public, `all` elsewhere, RFC 157),
  so pinning one scope would silently drop every public school's net price.
  `:service`'s `CohortSeries` pins the same three, for the same reason.

### D5 — History: drop from `colleges` AND `colleges_versions`, against a measured no-op

The slice requires that pre-canonical money history is preserved, never silently
lost. The first question is therefore what history exists. Measured on the dev
database: 6,338 version rows over 6,273 colleges, max version 2, and a v1-vs-v2
self-join over all 16 price columns with `IS DISTINCT FROM` returns **zero
rows** — not one second version changes any money value. There is no money
history in the versions table; there is a snapshot stored twice, and the live
half of that snapshot is superseded by `price_figures` / `cohort_money_stats`,
filled from the same publisher CSV.

Three options were weighed:

1. **`colleges_versions_money_archive`** — copy 5,852 rows into a new table. It
   moves provably-identical values into a table with no reader, and buys the
   forensics 0006 D15 already deferred. No `*_archive` precedent exists anywhere
   in `db/schema`.
2. **Drop both sides against a documented cutoff** — the `0017` precedent, which
   dropped columns from `users` _and_ `users_versions` and restated
   `log_user_version()`, after proving the live table held nothing that
   mattered.
3. **Drop from `colleges` only, freezing the money tail on `colleges_versions`**
   — zero rows moved, but the versions table then carries 18 permanently-NULL
   columns and the `colleges` / `colleges_versions` mirror that every migration
   since 0023 maintained is broken on purpose.

**Chosen: 2 (Ian's call at the /ship gate, 2026-09-05; the draft defaulted to
3).** A frozen tail is a half-drop: the publisher shape survives in the schema,
unreadable by any code, and every future reader of `colleges_versions` has to be
told why 18 of its columns are always NULL. Since the measurement shows the
columns hold no history — only a duplicate of a snapshot the canonical layer now
owns — keeping them buys nothing that justifies that permanent confusion. The
publisher shape leaves the database entirely, which is the end state D10 named.

What "never silently lost" means under this option is that the loss is
**recorded, and the values are dumped before they go**:

- the run takes a `bin/db-dump` of the pre-migration database and names its path
  in the report, so the raw values exist outside the schema at a known point;
- `COMMENT ON TABLE colleges_versions` states the cutoff — which columns this
  table carried, until which migration and date, that their values were the
  Scorecard figures for the version's ingest, and where the live figures live
  now;
- this decision, with its measurement, is the record. A future question about a
  pre-2026-09 net price is answered by the dump plus this paragraph, not by a
  column nobody reads.

The reversal is not free and is named here: if the measurement is ever shown
wrong — a third version somewhere in production that did change a money value —
that delta is recoverable only from the dump. The measurement was taken over
every version row in the database, not a sample.

### D6 — `log_college_version()` is restated in the same migration

plpgsql bodies are TEXT and are not dependency-tracked, so `DROP COLUMN`
succeeds and then kills the next `colleges` write. `CREATE OR REPLACE` in the
same migration, restating the INSERT without the 18 columns — the versions table
still HAS them, so the INSERT simply names fewer columns and the rest default to
NULL. The trigger itself is untouched (`0062:71-73`).

### D7 — `change_summary` is not touched by the migration; the loader drops the keys and bumps its method version

`college_index_build.change_summary` is append-only provenance, and its keys
stay readable precisely because **the migration does nothing to it** — no
backfill, no key-strip UPDATE, no JSON CHECK. Old rows keep their 28 keys.

The loader must drop the 18 money names from **both** lists in the same commit —
`CollegeScorecardLoader`'s summary allowlist and
`CollegesDao.NON_NULL_COUNTABLE_COLUMNS` — because they are two copies of one
list and the DAO one becomes SQL: leaving either behind fails the ingest with
`42703 column does not exist` _after_ the load. `METHOD_VERSION` goes 8 → 9 so a
10-key row is distinguishable from a 28-key one — all 18 dropped columns were in
the 28-key `non_null` axis, so 28 − 18 = 10, not the 12 an earlier draft of this
decision claimed from a stale 16-column drop list.

### D8 — `admin-web` loses its money fields rather than gaining a canonical read

`CollegesResource` shows money in three places: the list columns, the detail
map, and a "Net Price" cell in the version-history panel. All three go.

The version panel cannot be honestly re-pointed at all: `price_figures` is not
versioned per `colleges.version`, so there is no canonical value to put in a
per-version row. For the list and detail, a canonical admin surface is real work
with its own shape questions (which address? which vintage? shown how?) and no
user waiting for it. Admin is internal, and every family-facing money surface
already reads canonical. Building an admin view of the canonical layer is a
slice, not a footnote in a removal.

### D9 — Reader-count zero is proved by a test, in two halves

The acceptance criterion asks for a grep-based test naming each dropped column.
One test is not enough, because `colleges_versions` legitimately keeps the names
and the historical migrations legitimately mention them:

- a **schema assertion** — `information_schema.columns` for table `colleges`
  contains none of the 18 names;
- a **source scan** over the main source sets (`db`, `college`, `service`,
  `rest-server`, `queue-worker`, `admin-web`), with `db/schema/*.sql` excluded
  by construction.

The scan cannot simply assert "the name appears nowhere", and that is not a
weakening — 11 of the 18 names remain _correct_ speech about a different
namespace. `net_price_per_year_usd` is the `college_search_index` ruler column
D3 deliberately keeps. `pell_share` is a `money_measures` value and a wire key.
The two tuitions, the six components, median debt and median earnings are
`CostField.wireName`s — canonical concept names since RFC 166, which is exactly
why this RFC leaves `CostField` alone (D-analysis in the research reports).

So the scan is a **named, reasoned, per-file allowlist with exact counts, held
in both directions**: an unlisted occurrence fails, and a listed count that
changes fails too. A reintroduced column cannot hide in an existing allowance.
All 18 names are spelled out in the test, so the next person to bring one back
has to delete a test that says why it went.

Both halves name all 18 columns explicitly.

### D10 — Reachability: nothing new appears, and that is the point

The slice's own first-session test is "none — invisible when done right". The
door for this work is every door already open: a search result still carries
`net_price_by_income_band`, the earnings, debt and Pell lines, and its price
axis; a cost answer, the parent report and the fit-lens digest are untouched
because RFC 166 already moved them. The user-visible claim of this RFC is that
**nothing changes**, and the full `bin/test check` suite is what proves it.

Out of scope, deliberately: speaking the canonical figure statuses on the search
payload (a suppression named as the publisher's), retiring the net ruler, and
any admin view of the canonical layer. Each is a product slice.

### D11 — `CollegeCostProfile.ingestYear` is deleted, not re-sourced

Raised at the gate: the upsert's `IS DISTINCT FROM` content-change predicate
(`CollegesDao.kt:386-413`) names 18 money columns among its 32, so today a
snapshot whose ONLY change is money bumps `version`, fires history, and advances
`colleges.updated_at`. After this RFC it cannot — the compared columns are gone.
`CollegeCostService.ingestYearOf` (`:1598`) derives a year from exactly that
timestamp, so the mechanism of the worry is real and confirmed.

It lands on a dead field. `ingestYear` is computed at
`CollegeCostService.kt:1090` and **read by nothing**:
`CollegeCostChatTool.profileObject` does not emit it, `CostReportPage` does not
render it, and no wire key carries it. The whole tree's occurrences are the
field, its assignment, two `:service` tests, one `public-web` test fake, and
`AcademicYear`'s KDoc — which records that `label(profile.ingestYear)` was "the
one mistake the work was done to prevent". Brief 0003's M5 complaint ("the tool
prints _data ingested YYYY_") was answered by RFC 149/151: the spoken vintage is
now a real per-figure academic year, typed `AcademicYear` and stored as
`price_figures.academic_year`.

So the answer is NOT to re-source the field from the canonical layer. Reading a
vintage from the figures an answer actually used is the right rule, and RFC 149
already implemented it for every figure the product speaks; adding a second,
profile-wide vintage beside it would re-create the ambiguity that RFC's type was
built to remove.

The field is **deleted**. Keeping it is the strictly worse option after this
migration: its meaning was already weak, and the drop makes it definitely wrong
— a money answer's "as of" year sourced from a timestamp that money can no
longer move. A dead field with a plausible name is an invitation, and the
invitation is to print a wrong vintage on a family's cost answer. Removing the
last place the publisher shape's timestamp could be mistaken for a money vintage
is exactly this slice's job.

This is a behaviour-preserving deletion — nothing reads it — so it does not
breach the RFC's zero-behaviour-change contract. `colleges.updated_at` itself
stays: it is still the honest wall-clock time of the last change to the
identity, location and codes that remain on the row.

**The consequence, stated in full rather than left implicit.** The predicate
loses 18 of its 32 columns, so after this RFC a snapshot whose ONLY change is
money no longer bumps `colleges.version`, no longer writes a `colleges_versions`
row, and no longer advances `colleges.updated_at`. That is three observable
effects, not one, and none of them has a reader that cares: `version` and the
version rows are admin-facing history of the identity/location/codes shape,
which is now exactly what the row holds; and the timestamp's only money consumer
was `ingestYear`, deleted above. Money's own change history lives in
`price_figures` / `cohort_money_stats`, which the canonical fill writes on its
own schedule and which carries an academic year per figure — a better record
than a row-level version counter ever was. Recorded here so the next reader of
`colleges.version` knows what it stopped counting, and when.

### D12 — "Zero behaviour change" is true of the mechanism, not of every number, and the exception is named

The slice's acceptance criterion is zero behaviour change. That holds for the
wire (no key, type or shape moves), for the ruler, and for median debt and
median earnings, which only the Scorecard fill writes.

It does **not** hold for two payload values, and pretending otherwise would be
the dishonesty this brief exists to remove. `cohort_money_stats` is written by
two fills, and at two addresses they collide on purpose:

- **the five band prices.** IPEDS SFA writes `avg_net_price` per income band at
  exactly the Scorecard's NPT4x address, because they are the same measured fact
  — `CanonicalMoneyLoader` records that 198 of 200 publics matched to the dollar
  — and the SFA aid years are newer. Newest-vintage-wins therefore serves the
  **publisher's own number in place of the Scorecard's copy of it**. The loader
  calls this "upstream-wins bite" and it is the intended behaviour of the store.
- **`pell_share`.** The Scorecard's `PCTPELL` is undated; SFA's `UPGRNTP` is
  dated. Same measure, population, aid scope and residency scope, so the dated
  row wins.

So a search result may serve a slightly different band price or Pell share than
the dropped column held. This is not a regression to be undone: it is the search
payload joining the cost answer, the parent report and the fit-lens digest,
which have read canonical since RFC 166 and have therefore ALREADY been serving
the upstream figure. Today the same school can quote one net price by band in a
cost answer and another in a search result. After this RFC it cannot.

The alternative — pinning the search payload to the Scorecard series to preserve
the old bytes — would freeze a known-superseded copy into one surface and
re-create exactly the disagreement RFC 166 closed. Rejected.

A test pins the direction, at the shared address, in the positive: an SFA band
row at a newer vintage **displaces** the Scorecard's row in the payload. It sits
beside the trap test for the address that does NOT collide (SFA's overall
grant-aided net price, a different population and aid scope, which must never
reach the ruler).

### D13 — One home for the cohort address, and the fourth column of its key (review)

Three review lenses independently found `CohortStatAddress` to be a third copy
of a triple `:college` and `:service` already declared, and the RFC's own D4
shipped a cross-module contract test whose only job was to reconcile two of
them. That is the wrong shape, and the argument D4 made for the copy — "`:db`
does not depend on `:college`" — argues against importing UPWARD only. The type
belongs DOWN, in `:db`, beside the three enums it is made of.

So the cohort address now has one home,
`ed.unicoach.db.models.CohortStatAddress`, with the four addresses more than one
module names collected in `CohortAddresses`. `:college`'s `CohortCoordinate` and
`:service`'s `CohortSeries` are deleted. `:service`'s `CohortAddress` survives
as a distinct type, built ON the shared triple: it also carries a `FigureGroup`
and `bandSelected`, which are facts about how a `CostField` READS, not about
where the cell IS.

`SearchCohortAddressContractTest` is deleted along with the second copy it
existed to reconcile. That is a consequence, not a loss: with one declaration
the test could only prove that declaration agrees with itself. The protection
against a WRONG address is `CollegeScorecardRealDataTest` — the falsifier, which
reads the real CSV — and the two two-series tests in `SearchCanonicalMoneyTest`,
all of which read rows back out of the database.

**The fourth column, which is a correctness fix rather than a cleanup.** D4
pinned three of the four columns that separate two rows of one cell.
`residency_scope` is the fourth; it is in `cohort_money_stats_natural_key`, it
is CONTROL-dependent, and one college can legitimately hold both scopes at one
address. Left out of the sort, the row served for a band price — and for the
corpus-wide net-price ruler — was whichever the planner returned first.

The reader therefore orders by the scope that MATCHES THE COLLEGE'S CONTROL
first (public → `in_state_rate_paying`, otherwise → `all`, which is the rule
both fills write by), and only then by vintage. Nothing is pinned in the
`WHERE`: that would drop every public school's net price, which is why D4 left
the scope free in the first place. With scope and vintage both in the order, the
natural key leaves exactly one row, so the read is total.

`:service` refuses the same case with a `require` instead. The two layers differ
on purpose: there, one college's cost answer IS the answer, and refusing is the
honest outcome; here the same expression ranks a corpus, and failing a whole
search page over one school's data is the worse answer. A test now writes both
scopes at one address, for a public and for a private, which no test did before.

**D9 widening.** The zero-reader sweep derives its roots from
`settings.gradle.kts`, so it covers all seventeen module source sets rather than
the six an earlier draft named by hand, and a module added later joins it with
no edit. Widening it produced no new offender. Its two claims — no offender, and
no allowlist drift — are two tests, because as one method the first failure hid
the second.

**D2 shape.** The five band prices reach `CollegeMatch` as
`netPriceUsdByBand: Map<IncomeBand, Int>` rather than five parallel `Int?`
fields, so band identity lives in the key instead of in a field-name suffix that
no longer matches its column. The wire is unchanged: `net_price_by_income_band`
is still built by iterating `IncomeBand.entries` and omitting the bands with no
figure.

### D14 — What review hardened in the read path (tier 2)

Four claims this RFC makes were true only by convention until review; each is
now evaluated by the code that depends on it.

**One address per projected measure, checked at construction.** The payload
lateral keys `DISTINCT ON (measure, income_band)` and filters on the measure
alone, so the served address set may hold exactly one address per measure — a
second (SFA's grant-aided net price is the live candidate) would be admitted by
the `WHERE` and merged into one cell, which is RFC 166's blocker returning by a
different door. The precondition was a comment; it is now a `require` on the
address set.

**One school may not quote two integers for one figure.** Every dollar cell the
search path reads out of `cohort_money_stats` is rounded HALF UP,
`floor(x + 0.5)`, rather than with SQL `round()`, which breaks ties away from
zero. The cost surface's `toWholeDollars` rounds half up, and the negative net
prices this data admits by design — aid exceeding cost — are exactly where the
two rules disagree. A test pins both surfaces to one integer on a negative
half-dollar.

**The positional convention is not reintroduced in SQL.** The payload lateral's
band aliases are named by the band's own code (`net_price_income_under_30k`),
not by the publisher's `q1..q5` digit. Deleting `netPriceIncomeQ1Usd` from
Kotlin and then re-encoding the same convention in a column alias would have
moved the hazard rather than removed it.

**The zero-reader proof names modules and files, not counts and basenames.** It
asserts the set of module names it must sweep, so a module LEAVING the build
fails the guard instead of quietly shrinking it; and it exempts other-namespace
occurrences by repo-relative PATH, so a permit belongs to a file rather than to
a filename that another module could share.

One shape is deliberately left alone. `SearchCanonicalMoneyTest`'s
`PublisherFigures` still names its five fields `netPricePerYearIncomeQ1..Q5Usd`,
because that type models the SCORECARD's own variables (`NPT41..45`), where
`q1..q5` is the publisher's vocabulary and not ours — the same rule the schema
conventions state for source-defined codes. The convention is wrong in OUR
domain types and right in a type that names the source's.

## Detailed Design

### Migration `0094.drop-publisher-money-columns.sql`

Numbered 0094, not 0093: RFC 177 landed `0093.seed-coach-system-prompt-v23.sql`
while this run was open, and `schema_migrations.version_id` is a four-character
primary key, so two files sharing a prefix cannot both be recorded. The number
was re-claimed from the rebased tree, not copied from this RFC's first draft.

1. `CREATE OR REPLACE FUNCTION log_college_version()` **first** — the
   `0062:74-110` body with the 18 columns removed from both the column list and
   the `NEW.*` list, taking it from 37 columns to 19. `bin/db-migrate` wraps the
   file in one transaction, so the order inside it is a readability choice
   rather than a correctness one; replacing first is the order to copy, because
   it puts the trigger's new body beside the reason for it.
2. `DROP INDEX IF EXISTS colleges_net_price_per_year_usd_idx` (`0059:114`),
   explicitly rather than as a side effect of the column drop.
3. `ALTER TABLE colleges DROP COLUMN` × 18. The 12 CHECK constraints on those
   columns — eleven `_nonneg_check` plus `colleges_pell_share_range_check` — and
   the 16 `COMMENT ON COLUMN` texts go with them automatically; No FK, no view,
   and no other trigger names a money column.
4. `ALTER TABLE colleges_versions DROP COLUMN` × 18 (D5). The versions table
   carries no CHECKs on them by design (`0062:59-60`), so this is the columns
   alone.
5. `COMMENT ON TABLE colleges_versions` — the cutoff record (D5): which money
   columns this table carried, until which migration and date, what they held,
   and that the live figures now live in `price_figures` / `cohort_money_stats`.

The drop is preceded, outside the migration, by a `bin/db-dump` whose path the
run's report names.

### The search / similar payload

`CollegesDao` gains `CANONICAL_COHORT_LATERAL`, built beside
`PUBLISHED_TUITION_TIERS_LATERAL` and generated from the house enums:

```
LEFT JOIN LATERAL (
  SELECT
    max(s.value) FILTER (WHERE s.measure = <avg_net_price>
                           AND s.income_band = <q1>) AS net_price_income_q1,
    …                                                  -- q2..q5
    max(s.value) FILTER (WHERE s.measure = <median_earnings_10y>)      AS median_earnings_10y,
    max(s.value) FILTER (WHERE s.measure = <median_debt_at_completion>) AS median_debt,
    max(s.value) FILTER (WHERE s.measure = <pell_share>)               AS pell_share
  FROM (
    SELECT DISTINCT ON (cs.measure, cs.income_band)
           cs.measure, cs.income_band, cs.value
    FROM cohort_money_stats cs
    WHERE cs.college_id = i.college_id
      AND ( (cs.measure, cs.population, cs.aid_scope) = <one full address> OR … )
    ORDER BY cs.measure, cs.income_band, cs.vintage DESC
  ) s
) money ON TRUE
```

Each disjunct is a **full** address triple (D4); the outer aggregates see at
most one row per cell by construction, so `max(...)` is a projection, not a
comparison. `mapMatch` reads the lateral's aliases instead of the `c.*` columns;
`CollegeMatch`'s field names, `IncomeBand.getNetPrice(CollegeMatch)`, and every
`CollegeMatchRow` wire key are **unchanged** — this is a source move, not a
contract change.

Shares are stored 0–1 and dollars whole, exactly as the columns held them, so
`pellShare: Double?` and the three `Int?` fields keep their types. The dollar
cells are rounded **in SQL**, `round(...)::INTEGER` inside the lateral, not at
the Kotlin row boundary: `cohort_money_stats.value` is `NUMERIC`, and reading a
NUMERIC through `getInt` truncates rather than rounds, which would shave a
dollar off a figure at random.

### The index rebuild

`CollegesDao`'s rebuild INSERT-SELECT stops reading `c.net_price_per_year_usd`
and reads the canonical overall `avg_net_price` cell at the full address. The
rebuild already runs after `canonical-money` (RFC 169), so ordering is
unchanged. `net_price_percentile_share` is computed from the new source with the
same `percent_rank()` over the same default universe. The index DDL, its indexes
and its CHECKs are untouched.

### Ingest

`CollegeScorecardLoader` stops parsing and writing the 18 columns into
`NewCollege`; the canonical fill is independent (it re-parses the same CSV and
never selects from `colleges`), so it is untouched. `NewCollege`, `College` and
`CollegeMatch`'s dead fields go with them. `IncomeBand`'s `College` overload
(zero production readers) is deleted; the `CollegeMatch` one stays, renamed
`getNetPrice` and reduced to a map lookup.

`CollegeScorecardRealDataTest`'s RFC 157 falsifier reads the publisher columns
directly; it is **ported to the canonical cells**, not deleted — it is the test
that proves the ingest saw real data.

## Files Modified

**Schema**

- `db/schema/0094.drop-publisher-money-columns.sql` (new)

**db**

- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — row mappers, upsert,
  search + similar SQL, `CANONICAL_COHORT_LATERAL` (new), index rebuild,
  `NON_NULL_COUNTABLE_COLUMNS`
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt`, `NewCollege.kt` — the
  18 money fields removed. `CollegeMatch.kt` is **unchanged**: after the cut
  every money field on it has a live reader fed from the canonical lateral,
  which is the point of D2 — the payload's shape never moved, only its source.
- `db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt` — the dead `College`
  overload removed; the `CollegeMatch` one becomes `getNetPrice`, a map lookup

**college**

- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  parse/write removal, summary allowlist, `METHOD_VERSION` 8 → 9

**service / admin-web**

- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` —
  `ingestYear` and `ingestYearOf` deleted (D11)

`FitLensService.kt` is deliberately absent: its earnings read is
`match.medianEarnings10yAfterEntryUsd` and its price read is the index ruler, so
re-pointing the payload and the rebuild moved both without touching the file.

- `admin-web/src/main/kotlin/ed/unicoach/admin/resources/CollegesResource.kt` —
  money fields, detail map, version-panel "Net Price" cell

**Tests** — `CollegeSearchIndexRebuildTest`, `CollegesDaoTest`,
`CollegeScorecardIngestTest`, `CollegeScorecardRealDataTest`, `IncomeBandTest`,
`CostsTestDb`, `CollegeFixtures`, the ~15 fixture files carrying full
`NewCollege(...)` literals, plus two new tests (D9) and the cross-module address
contract test (D4).

## Implementation Plan

1. **Cut the readers first, with the columns still present.** Add the lateral,
   re-point `mapMatch`, re-point the rebuild. Fit lens needs no production edit:
   its earnings read is `match.medianEarnings10yAfterEntryUsd` and its price
   read is the index ruler, so both move with the payload and the rebuild. Run
   the suite: it must be green _before_ anything is dropped, which is what
   proves the new source returns the old numbers.
2. **Add the address contract test** (D4) and watch it fail if an address is
   wrong.
3. **Cut ingest and the models** — stop writing, delete the fields, shrink both
   summary lists, bump `METHOD_VERSION`.
4. **Cut admin-web.**
5. **Prove zero** — add the two D9 tests against the still-present columns; the
   source scan must already pass, the schema assertion must still fail.
6. **Drop** — migration 0094, `log_college_version()` restated, comments
   written. The schema assertion now passes.
7. **Fixtures and stale prose** — the `NewCollege(...)` literals, the RFC 157
   leftovers, the migration-comment references.
8. Full `nix develop -c bin/test check`.

## Tests

- **Zero-reader schema assertion** — `colleges` has none of the 18 names.
- **Zero-reader source scan** — no main source set names any of the 18.
- **Address contract test** (cross-module, both directions) — the search
  reader's address grid equals `CanonicalMoneyLoader`'s.
- **Payload equivalence** — a search over a fixture whose canonical rows and
  (still present, then removed) columns agree returns identical
  `net_price_by_income_band`, earnings, debt and Pell values before and after.
- **Two-series trap, both directions** (D12). At the address that does NOT
  collide — SFA's overall grant-aided net price, a different population and aid
  scope — the ruler must serve the Scorecard cell and never SFA's. At the
  address that DOES collide — the five bands — an SFA row at a newer vintage
  must **displace** the Scorecard row. One test each; the pair is what stops a
  future reader from "fixing" either direction into the other.
- **Index rebuild** — `CollegeSearchIndexRebuildTest` with the canonical source;
  an empty `cohort_money_stats` leaves the ruler NULL and search says so rather
  than ranking wrongly.
- **Version write after the drop** — a `colleges` UPDATE writes a version row
  through the restated `log_college_version()` and the trigger still fires; the
  version row carries the full identity/location/codes shape and no money column
  exists on either table.
- **Ingest summary** — `change_summary` writes 10 keys at method version 9, and
  a pre-existing 28-key row is still readable, key for key.
- Full `bin/test check`, including `bin/shell-tests`.
