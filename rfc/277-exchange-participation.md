# RFC 277: Regional tuition exchanges, in unicoach's shape

Slice: `shape/09/exchange-participation` (brief 0006, unparked by brief 0017 D2
and D10). This RFC replaces D10's `award_basis` enumeration and `as_published`
twins with the typed facts below, on Ian's call at the approval stop,
2026-11-08.

## Executive Summary

A Washington student with Oregon State on their list is told today that no
comparable out-of-state figure exists, while the school runs a Western
Undergraduate Exchange (WUE) scholarship that caps tuition at 150% of the
resident rate. This RFC adds two small reference tables holding what the coach
needs to tell that student: who can use the exchange, what it caps, whether
getting in is enough, what it takes and by when. The cost tool and the Family
Cost Report carry it as a labelled possibility beside the out-of-state tier,
with a capped tuition figure computed at read time. It never enters a total and
is never stored.

## Detailed Design

### Tables

New migration `db/schema/create-tuition-exchanges.sql`, one line in
`db/schema/ORDER`. Reference-table shape (RFC 239 precedent): unversioned, no
soft delete, plain timestamp trigger.

Each column answers a question the coach or the report must answer. Nothing is
stored because a publisher happened to print it.

```sql
-- Who can use an exchange. One fact per exchange, so it lives once.
-- Seeded by this migration: it is keyed by nothing the import fills.
CREATE TABLE tuition_exchange_states (
    exchange   TEXT NOT NULL,
    state_code TEXT NOT NULL,
    PRIMARY KEY (exchange, state_code),
    CONSTRAINT tuition_exchange_states_exchange_check
        CHECK (exchange IN ('wue')),
    CONSTRAINT tuition_exchange_states_state_code_check
        CHECK (state_code ~ '^[A-Z]{2}$')
);

-- What one school does with an exchange.
CREATE TABLE college_tuition_exchanges (
    id                               UUID         NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id                       UUID         NOT NULL REFERENCES colleges(id),
    exchange                         TEXT         NOT NULL,
    max_percent_of_resident_tuition  SMALLINT     NOT NULL,
    selection                        TEXT         NOT NULL,
    separate_application             TEXT         NOT NULL,
    minimum_unweighted_gpa           NUMERIC(3,2) NULL,
    percent_of_eligible_offered      SMALLINT     NULL,
    apply_by                         DATE         NULL,
    source_document_id               UUID         NOT NULL REFERENCES external_documents(id),
    checked_on                       DATE         NOT NULL,
    expires_on                       DATE         NOT NULL,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT college_tuition_exchanges_one_per_exchange
        UNIQUE (college_id, exchange),
    CONSTRAINT college_tuition_exchanges_exchange_check
        CHECK (exchange IN ('wue')),
    CONSTRAINT college_tuition_exchanges_max_percent_check
        CHECK (max_percent_of_resident_tuition >= 100),
    CONSTRAINT college_tuition_exchanges_selection_check
        CHECK (selection IN ('guaranteed', 'selective', 'unstated')),
    CONSTRAINT college_tuition_exchanges_separate_application_check
        CHECK (separate_application IN ('required', 'not_required', 'unstated')),
    CONSTRAINT college_tuition_exchanges_gpa_check
        CHECK (minimum_unweighted_gpa BETWEEN 0 AND 4),
    CONSTRAINT college_tuition_exchanges_offered_check
        CHECK (percent_of_eligible_offered BETWEEN 1 AND 100),
    CONSTRAINT college_tuition_exchanges_expiry_check
        CHECK (expires_on > checked_on)
);
```

- `selection`: does meeting the criteria get the rate? `guaranteed` means yes;
  `selective` means some eligible students are turned down.
- `separate_application`: does the student have to ask for it?
- `unstated` in either column: the school's page does not say, and the coach
  sends the student to the page rather than guessing (the `score_requirement`
  precedent).
- The school is the publisher, so its name comes from `colleges`.
- `exchange` admits `wue` only. MSEP, NEBHE and SREB differ in shape (a
  percentage off, per-program eligibility), so each widens or reshapes this when
  its first row is read.

Kotlin side, in `db/.../models`: `TuitionExchange`, `ExchangeSelection` and
`SeparateApplication`, each an `enum class` carrying its `value` with
`fromValue`, and `CollegeTuitionExchange`. `TuitionExchange` also carries the
exchange's full name, short name and percentage ceiling, which the tool, the
report and the loader read. Both tables hold no personal data and sit outside
the erasure reach.

### Seed

`tuition_exchange_states` is seeded by the migration from WICHE's published list
of member states and territories. This is the compact's own statement of who may
use it, not its Savings Finder dataset, which is never an input.

`db/seed/tuition-exchanges/{exchanges.csv,link-status.csv,PROVENANCE.json}` is
loaded by a new `TuitionExchangeSeedLoader` from `bin/ingest-colleges` behind
`bin/load-external-data`, because the rows key on `colleges(id)` (RFC 239 D-C).
A unit id missing from `colleges`, or a name that disagrees, is fatal and names
the file and line. So is a percentage above the ceiling the `TuitionExchange`
enum holds for that exchange (WUE's is 150), because the table's CHECK bounds
only the low end. The loader also refuses what RFC 243's refuses: a renamed
header, a short row, a bad cell, a repeated key, a cited url with no committed
answer, and a missing or malformed sidecar. A re-load drops every row the file
no longer names, so a school withdrawn from the corpus stops being answered for.
A file with no row is refused as truncated, never read as an instruction to drop
them all. `PROVENANCE.json` holds the reading behind each row, with the
sentences it came from, for review in the diff. It is never loaded.

First rows, each read from the school's own page:

| School             | Max % | Selection | Separate application | Min GPA | % offered | Apply by     |
| ------------------ | ----- | --------- | -------------------- | ------- | --------- | ------------ |
| Oregon State       | 150   | selective | not required         | —       | 30        | February 3   |
| Western Washington | 150   | selective | not required         | 3.25    | —         | none we hold |

A deadline the school states as a month and day is stored as its next occurrence
after `checked_on` (RFC 239's reading-day rule for a page that names no cycle).
`expires_on` is `HandTypedReferenceData.CYCLE_EXPIRY` following that deadline,
or following `checked_on` when no deadline is held. A guard test fails the build
`HandTypedReferenceData.REFRESH_LEAD` before a row's `expires_on`.

### Read

`CollegeTuitionExchangesDao.listByColleges` runs inside
`CollegeCostService.readInSession`, joined to the exchange's states. A pure
function
`exchangePossibilitiesOf(rows, residencyState, collegeState, figures,
today)`
decides who is told. A row yields an `ExchangePossibility` only when
`residencyState` is non-null, is one of the exchange's states, differs from
`collegeState`, and `today <= expires_on`. Otherwise it yields nothing, the same
result as no row. `CollegeCost` gains
`exchangePossibilities: List<ExchangePossibility>`, not defaulted, on the
`automaticAwards` reasoning.

**The capped figure is tuition only and is computed at read time.** WUE's
ceiling is a percentage of resident _tuition_, and the store holds tuition _and
fees_ plus a fees-only figure, so:

    resident tuition = in-state tuition and fees - in-state fees only
    capped tuition   = resident tuition x max_percent_of_resident_tuition / 100

`cappedTuitionPerYearUsd` is null when either input is unpublished or the
fees-only figure is not below the combined one, and null when the out-of-state
tuition (same subtraction) is known and is not higher than the cap, because the
ceiling then does not bind. Both inputs come from the served year, whose label
travels with the figure. The figure rounds to the nearest dollar. Null degrades
to stating the percentage.

### The coach's door

`CollegeCostChatTool.collegeObject` emits per-college `regional_exchanges` only
when the list is non-empty, so no key means nothing is said. Each entry:
`exchange_name`, `exchange_short_name`, `max_percent_of_resident_tuition`,
`selection`, `separate_application`, `minimum_unweighted_gpa`,
`percent_of_eligible_offered`, `capped_tuition_per_year_usd_derived`,
`capped_tuition_academic_year`, `apply_by`, `apply_by_passed`, `source_url`,
`checked_on`. It emits no `CostField`, so it takes no part in any total or
vintage label. `family_cost_picture` inherits the key through `payloadOf`.

Lines added to `prompts/coach-system-prompt.md` after the in-district lines, and
to `prompts/parent-coach-system-prompt.md`, each reseeded as a new version
through `bin/prompt-seed`:

> When a college's result carries regional_exchanges, say each one beside the
> out-of-state tier as "if admitted under" and the exchange's short name: a
> labelled possibility, never as the price and never inside a total, always with
> how it is decided and its deadline. Selective means some eligible students do
> not get it, so say that, with the share offered and the minimum GPA where the
> result gives them. Name a tuition exchange only for a school whose result
> carries one; who is eligible is the result's to decide and never your memory.
> The capped figure is tuition only, before fees; where there is none, give the
> percentage. Where selection or separate_application is unstated, say the
> school's page does not settle it and point to the page. Where there is no
> apply_by, say we do not hold a deadline and point to the page. Where
> apply_by_passed is true, say that date has passed for this year's applicants.

### The report's door

`CostReportPage.schoolSection` adds `exchangeLines(cost)` after
`tuitionTierLine` and before `priceTable`, one `report-school-exchange` block
per possibility. It says "If admitted under the Western Undergraduate Exchange
(WUE)", the capped tuition labelled as our arithmetic with its year and "before
fees", states that this is not the published price and is not in the totals
below, then how it is decided, the minimum GPA and share offered where held, the
deadline, and a link to the school's page. The words obey the page's vocabulary
(no "award").

## Files Modified

- `db/schema/create-tuition-exchanges.sql`, `db/schema/ORDER`, two generated
  prompt seed migrations
- `db/seed/tuition-exchanges/*`
- `db/src/main/kotlin/ed/unicoach/db/models/` (four new types),
  `db/.../dao/CollegeTuitionExchangesDao.kt`
- `college/.../TuitionExchangeSeedLoader.kt`, `IngestApplication.kt`,
  `bin/ingest-colleges`, `bin/load-external-data`
- `service/.../coaching/costs/` (`ExchangePossibility.kt`,
  `CollegeCostService.kt`, `CollegeCostChatTool.kt`)
- `public-web/.../render/CostReportPage.kt`
- `prompts/coach-system-prompt.md`, `prompts/parent-coach-system-prompt.md`
- Tests beside each, and `public-web`'s report fixtures

## Implementation Plan

1. Migration with the member-state seed, enums, model, DAO.
2. Seed files and loader, wired through `bin/ingest-colleges` and
   `bin/load-external-data`. Verify with the DAO and loader tests.
3. `exchangePossibilitiesOf` and `CollegeCost.exchangePossibilities`.
4. Tool key, then the report block.
5. Both prompts, reseeded with `bin/prompt-seed`; update the catalog pins.
6. `nix develop -c bin/test`.

## Tests

- DAO against the real database: round trip; each CHECK refuses its bad row.
- Loader: loads the committed corpus; refuses an unknown unit id, a name
  mismatch and a percentage above the exchange's ceiling.
- Seed guard: no row within `REFRESH_LEAD` of `expires_on`.
- `exchangePossibilitiesOf`: a Washington student at Oregon State gets the
  possibility; a student from a non-member state, a resident of the school's own
  state, a student with no state on file, an expired row and no row all get
  nothing; the cap subtracts fees; null when fees are unpublished; null when the
  cap does not bind; `apply_by_passed` on each side of the deadline.
- Tool: first-session test, a Washington student with Oregon State on their list
  sees `regional_exchanges` with selection, deadline and the derived figure, and
  no total in the payload changes with or without the row; an ineligible
  student's college has no key.
- Report: the block renders before the price table with the not-in-totals
  sentence; the table's totals are identical with and without the row; a school
  with no possibility renders nothing.
- `SystemPromptCatalogTest`: the served bodies carry the new lines.
