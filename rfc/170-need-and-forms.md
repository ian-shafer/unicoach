# RFC 170 — Need and forms: what a school asks for, and how it treats need

Slice: `shape/07a/need-and-forms` (brief 0006, split from
`shape/07/need-and-forms` at design — D1). Lane A.

## Summary

Brief 0006 exists to give unicoach a money store shaped like what money IS. This
slice fills the last modeled-ahead corner of that store from the CDS corpus we
already pull, and opens the door: a family asks "does Amherst meet full
financial need?" or "do we have to do the CSS Profile?" and gets an answer cited
to that school's own Common Data Set, with the year on it.

The CDS gives us two things, and they are two DIFFERENT domain shapes:

- **What a school asks a family to file** — an application requirement. A
  college requires a set of aid FORMS of a stated applicant group, in a year.
- **How a school treats need** — cohort statistics. An average share of need
  met, a headcount whose need was fully met, an average need-based grant. These
  are numbers about a population, which the canonical store already models.

`aid_policy_facts` (RFC 158 D9) modeled both as one key/value table with a
`policy` slug and a boolean-or-number column. That was an honest guess made
before we had seen the data. Now that we have, the guess is wrong in the exact
way this brief exists to fix: it is a bag of source cells, not a shape. This RFC
replaces it (D2).

## Decisions

**D1. Borrowing splits out.** This slice lands need + forms. CDS H4/H5 borrowing
becomes `shape/07b/borrowing`: it is a per-loan-type statistic needing a loan
dimension the canonical store does not have, and its published percent cells are
corpus-typed text with mixed 0..1 and 0..100 values, so the honest route derives
them from counts. Its own design argument. Both first-session tests in the spec
pass without it.

**D2. `aid_policy_facts` is dropped, not extended.** It is empty, with zero
readers and zero writers. Its contents split to where the domain already puts
them: statistics to the cohort tables, requirements to a requirement relation.
Extending its `policy` CHECK with a longer list of source cells would have
carried the publisher's shape into the canonical layer one slice before
`shape/08` deletes exactly that shape from `colleges`.

**D3. Need treatment goes in the tables that already hold cohort figures.**
`cohort_money_stats` gains the average need-based grant and the average share of
need met; `cohort_population_counts` (RFC 162) gains the with-need and fully-met
headcounts, so the fully-met SHARE is derived at read time from two stored
counts and only when both exist. Nothing new is invented: the population
vocabulary, the status discipline, the `not_applicable` axis rows and the
wholesale-rebuild pattern are all already there.

**D4. Forms get their own vocabulary and relation.** `aid_forms` is a
unicoach-authored vocabulary in the `money-vocabulary.json` pattern, naming the
forms as a family knows them, not as CDS numbers them. `aid_form_requirements`
is college x form x applicant group x year. The applicant-group axis is
domain-true, not source-shaped: a form required of domestic first-years and a
form required of international applicants are different requirements, and a
family in the wrong group must not be told to file.

**D5. Forms are `required` or `unknown`; never `not required`.** The corpus
carries 9,072 true checkboxes and zero false ones on the whole forms block. We
store only required rows. The read layer names the forms the school's CDS lists,
and anything absent reads "not listed in this school's CDS" — never "not
required". This narrows the spec's first-session test ("yes/no with citation"):
a `no` does not exist in the source, and manufacturing one is precisely the lie
brief 0003's honesty rules forbid.

**D6. `meets_full_need` is not stored as a boolean.** No source publishes it. We
answer "do they meet full need?" from the average share of need met and the
fully-met headcount over its denominator, both cited, both naming their cohort.
A stored boolean would be a derived figure, which the schema conventions forbid.

**D7. A corpus `parse_error` is OUR gap.** Those cells land with status
`not_collected_by_us` (reserved by RFC 158, emitted nowhere until now). No row
keeps its RFC 158 meaning — the school did not publish it — and reads as "not
reported in this school's CDS". `bin/fetch-cds-seed` today discards
`parse_error` into a drop counter, so the seed files gain a per-cell status.

**D8. `common_data_set` joins the `MoneySource` domain.** One more publisher on
the same source axis every canonical row already carries. CDS field ids live in
`source_variable`, where source-defined codes belong, and never in a table name,
a column name or a vocabulary slug.

**D9. A decimal reader beside `get_int`.** Measured against live rows, `get_int`
rejects 80% of the need-met percentages and most dollar averages. New
`get_decimal`; percent-kind rows read from `value_num * 100`. `get_int` is
untouched, so no existing extraction moves.

**D10. `bin/fetch-cds-seed` gets a conforming CLI.** Its hand-rolled
`parse_args` and long `--anon-key` break the `bin/` rule (stdlib `getopt`, short
options only). Replaced, with the reserved usage-error exit codes.

**D11. The door is the cost tool.** `college_cost_profile` gains an `aid_policy`
section — this is money, and the family asking is already in a money
conversation. No new tool, no profile required, nothing gated; prompt bumped one
version, rollback by config.

**D12. Licence.** `research/source-landscape.md` §4 asks for a re-check before
figures are shown at scale. Every rendered fact is attributed to the school's
own CDS document (`source_url`) with the corpus mirror as `archive_url`, and the
`X-CollegeData-Client` header is already sent. We proceed on that basis and
carry the re-check to Ian as an open item.

**D13. Duplicated data is a defect; this slice removes more than it adds.**
`source_url` / `archive_url` are properties of a DOCUMENT, not of a fact. One
CDS filing backs every fact we read out of it, and the three landed CDS tables
already store the same pair for the same filing (376 + 1,032 + 369 rows over
~417 documents), so today those copies can disagree and a corrected URL must be
written in four places. Adding an eleven-fact-per-school aid table would create
a fourth duplication site. Instead this RFC introduces `source_documents` and
points all four tables at it. `source_variable` stays a column for now (it
duplicates a CODE, not an entity, and normalising it means rewriting every
canonical loader) and moves to a `source_variables` reference table in the
cleanup slice, named in the report.

**D14. `academic_year` becomes a SMALLINT start year.** The tree's own
`cds_source_year` (0054) already does this; the canonical layer's `'YYYY-YY'`
TEXT columns are the outlier, and their format CHECK admits `'2025-30'` because
a regex cannot state "the second half is the first plus one". A new
`academic_year` DOMAIN over SMALLINT carries the range rule once; `'2025-26'`
becomes a label rendered at read time by a Kotlin `AcademicYear` value type, per
the derived-figures convention. `cohort_money_stats.vintage`'s literal
`'undated'` becomes NULL — an absent year rather than a magic string; its
natural key is already NULLS NOT DISTINCT, so two undated rows still collide.
FIVE columns convert, not four: `college_sfa.aid_year` joins them, because
otherwise the SFA staging loader keeps BUILDING a `'YYYY-YY'` label that the
canonical fill must PARSE back — a label parser whose only purpose is to undo a
label. Its third copy of the label rule (`SfaVariables.academicYear`) is deleted
with it. Columns OUTSIDE the money layer keep the old shape and are listed in
the report for the cleanup slice, so the "no table stores a 'YYYY-YY' string"
test is scoped to the money-layer columns.

## Detailed Design

### Vocabulary: `aid_forms`

Authored rows in `db/data/money-vocabulary.json`, loaded and fatally validated
like every other vocabulary, mirrored by `AidForm` with `fromValue` and pinned
to the table by a set-equality test both ways:

| slug                       | what a family calls it                |
| -------------------------- | ------------------------------------- |
| `fafsa`                    | the federal aid application           |
| `css_profile`              | the CSS Profile                       |
| `noncustodial_css_profile` | the noncustodial parent's CSS Profile |
| `institutional_form`       | the school's own aid form             |
| `state_aid_form`           | a state aid form                      |
| `business_farm_supplement` | the business/farm supplement          |
| `other_institutional_form` | another form the school names         |

Each row carries a description. It carries NO `is_federal` flag: the first draft
had one, on the claim that the read layer would say "the federal form everyone
files" from it, and no read layer does. Speculative until a reader exists.

### `source_documents` — one row per published document (D13)

```sql
CREATE DOMAIN academic_year AS SMALLINT
    CONSTRAINT academic_year_check CHECK (VALUE BETWEEN 2000 AND 2100);
-- 2025 IS the 2025-26 academic year. 'YYYY-YY' is a label, rendered at read
-- time (AcademicYear.kt), never stored.

CREATE TABLE source_documents (
    id            UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id    UUID          NOT NULL REFERENCES colleges(id),
    source        TEXT          NOT NULL,   -- the MoneySource domain
    academic_year academic_year NOT NULL,
    source_url    TEXT          NOT NULL,   -- the school's own publication
    archive_url   TEXT          NULL,       -- our mirror, when one exists
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT source_documents_natural_key
        UNIQUE (college_id, source, academic_year),
    CONSTRAINT source_documents_source_domain_check
        CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
                          'common_data_set')),
    CONSTRAINT source_documents_source_url_nonempty_check
        CHECK (source_url <> '')
);
```

`college_merit_aid`, `college_admission_factors` and `college_deadlines` drop
their `source_url` / `archive_url` pairs and gain
`source_document_id UUID NOT NULL REFERENCES source_documents(id)`. All three
are rebuilt wholesale from the committed seed by the `cds` phase, so the
conversion is a loader change, not a data migration.

### `aid_form_requirements`

```sql
CREATE TABLE aid_form_requirements (
    id                 UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id         UUID          NOT NULL REFERENCES colleges(id),
    aid_form           TEXT          NOT NULL REFERENCES aid_forms(slug),
    applicant_group    TEXT          NOT NULL,
    academic_year      academic_year NOT NULL,
    is_required        BOOLEAN       NULL,
    status             TEXT          NOT NULL REFERENCES figure_statuses(slug),
    source             TEXT          NOT NULL,
    source_variable    TEXT          NOT NULL,   -- the CDS field id
    source_document_id UUID          NOT NULL REFERENCES source_documents(id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT aid_form_requirements_natural_key
        UNIQUE (college_id, aid_form, applicant_group, academic_year),
    CONSTRAINT aid_form_requirements_applicant_group_check
        CHECK (applicant_group IN ('domestic_first_year_aid_applicants',
                                   'nonresident_first_year_aid_applicants')),
    CONSTRAINT aid_form_requirements_value_iff_status_check
        CHECK ((is_required IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    CONSTRAINT aid_form_requirements_source_domain_check
        CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
                          'common_data_set')),
    CONSTRAINT aid_form_requirements_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

CREATE INDEX aid_form_requirements_college_idx ON aid_form_requirements (college_id);

DROP TABLE aid_policy_facts;
```

`is_required` is nullable and not defaulted for one reason: a row with status
`not_collected_by_us` (D7) states OUR gap and carries no value. It is never
`FALSE` today; D5 is enforced by test rather than by CHECK, so a future source
that genuinely publishes a negative can write one without a migration.

### The `academic_year` conversion (D14)

In the same migration: `price_figures.academic_year`,
`college_ipeds_charges.academic_year`, `cohort_population_counts.vintage` and
`cohort_money_stats.vintage` change to the domain, their format CHECKs are
dropped, `'undated'` becomes NULL, and `AcademicYear.kt` replaces the string in
the Kotlin row types. Every one of these tables is rebuilt wholesale by the
ingest. Columns outside the money layer that still follow the old pattern are
listed in the report for the cleanup slice.

### Cohort figures

The CDS reports these four cells against THREE different denominators, and the
schema says which is which. Its own line letters, quoted:

    c = H.203  determined to have financial need            (not fetched)
    d = H.204  students in line c who were awarded any financial aid
    e = H.205  students in line d awarded any need-based scholarship or grant
    h = H.208  students IN LINE D whose need was fully met
    i = H.209  average % of need met OF STUDENTS AWARDED ANY NEED-BASED AID
    k = H.211  average need-based scholarship and grant award OF THOSE IN LINE E

So `cohort_population_counts.population` gains
`first_time_full_time_freshmen_awarded_any_aid` (line d, the denominator of the
fully-met share and of nothing else) and
`first_time_full_time_freshmen_need_fully_met` (line h), written with the
explicit `not_applicable` residency and arrangement rows the vocabulary already
provides. `cohort_money_stats.measure` gains `avg_need_based_grant` and
`avg_need_met_share`, both written against
`first_time_full_time_freshmen_awarded_need_based_grant` (line e) — NOT line d,
and not "students with need", which is line c and which we do not store.
`MoneySource` gains `COMMON_DATA_SET`; the three source-domain CHECKs gain
`'common_data_set'`.

We do NOT fetch line c and use it as the denominator instead, tempting as it is:
the CDS reports line h against line d, so pairing line h with line c would
manufacture a share no school ever published. The honest move is to keep the
source's own pairing and say the cohort out loud in every sentence — "of the
freshmen who received any financial aid" — because a family hears "need fully
met" and thinks of line c.

Naming the denominator wrongly is the defect this brief keeps finding (RFC 148,
then RFC 162): a slug reading `..._with_need` over line-d data would have made
every emitted sentence describe a cohort the school never reported. A test pins
each measure to its population so a future edit cannot re-point a figure at a
different denominator.

The share of freshmen whose need was fully met is computed at read time by
`Share.ofOrNull` over the two counts, emitted only when both exist, with its
population inside the sentence — the RFC 148 rule, the way `MeritAidWire`
already does it. User-facing copy says "received", never "awarded" (RFC 141);
the CDS's own word stays in slugs and comments.

### Seed, extraction, ingest

A fourth seed file `db/seed/cds/aid_policy.csv` carries one row per (college,
year, fact) with a per-cell `status`, the CDS field id, and the two urls.
`FIELD_IDS` gains H.204, H.208, H.209, H.211 (the first-time full-time freshmen
block — the corpus repeats the block three times for three cohorts and labels
none of them; block 1 matches the existing merit-aid seed) and H.801-H.807.
`get_decimal` handles the percents and dollar averages. A cross-field sanity
rule drops a school's whole aid-policy block when the fully-met count exceeds
the awarded count (`aid_policy_h2_contradictory`, a sticky drop): the two cells
contradict each other, so neither can be trusted. `require_plausible_seed` gains
the group with a floor under the measured coverage (need figures ~312-318
schools, FAFSA 278, CSS Profile 74, noncustodial 47 — of 417 seeded colleges).

`CdsSeedLoader` writes the three canonical tables inside the existing `cds`
phase transaction; row counts land in `college_index_build`
(`aid_form_requirement_rows`, plus the existing canonical counters).

### Read and prompt

An aid-policy read model assembles, per college: the forms this school's CDS
names for domestic first-year aid applicants — the only group the CDS H8 block
covers and the only one the seed carries; the nonresident group is representable
in the schema and stays unread until a source fills it — the average share of
need met, the fully-met share with its denominator named, and the average
need-based grant. `CollegeCostChatTool` renders it as `aid_policy` with a
`CdsCitation` section and its own `aid_policy_availability` key for a school
with no filing. NOT `data_availability`: that list speaks the Scorecard's
`CostField` vocabulary, so a CDS silence reported there is attributed to the
wrong publisher — the reason merit aid was already kept out of it. No bare CDS
field id reaches a tool result; `BareSourceCodeGuard`'s allowlist covers the new
keys. New system-prompt seed migration: previous body verbatim plus one
paragraph.

## Landed against two slices that moved under it

This RFC was written on `main@9b817622` and lands on `6f8f60e2`. Three slices
landed while it was in review, and two of them changed the same code:

- **RFC 162** (`shape/03/ipeds-sfa`) added `cohort_population_counts`. D3 builds
  directly on it; the run rebased onto it before implementation started.
- **RFC 166** (`shape/04/cost-answers-from-canonical`) cut the cost answers over
  to the canonical store, and did it on `'YYYY-YY'` TEXT with a sentinel,
  `VINTAGE_UNDATED = "undated"`. D14 replaces exactly that. One had to go and
  the sentinel went: `CollegeFigures` / `ServedFigures` /
  `CanonicalMoneyReadDao` now speak `AcademicYear`, and their `VINTAGE_ORDER`
  comparator loses the normalisation step that existed because
  `"undated" > "2023-24"` sorts by character code — the bug it guarded is now
  unrepresentable rather than guarded. Their `FigureGroup` supersedes this
  branch's `ScorecardVintage` entirely. The typed year stops at the spoken
  boundary: copy a family reads still carries `AcademicYear.label`, applied at
  the six places the stored year becomes words.
- **RFC 172** (one command to reload the external data) converted
  `bin/fetch-cds-seed` to stdlib `getopt` and moved its logging to stderr —
  independently, and while this branch was doing the same. Main's version wins
  wholesale: its `bin/pyfunctions.py` helpers, its `GETOPT_SPEC`, its `-K` (this
  branch had written `-k`), its atomic seed write, and its stderr assertions.
  This slice deletes its own duplicates of all of them and keeps one genuine
  increment, a located refusal when `-F` names something that is not a
  directory. The integration runs the other way too: `bin/load-external-data`
  built the ingest command with `-m -a -d`, and this slice makes the CDS group
  FOUR, so their one-command reload would have been refused with exit 21. It now
  passes `-p`, names a missing `aid_policy.csv` as its own input, and their
  flag-count wording moves from fourteen to fifteen.

Numbers, claimed at the commit rather than at kickoff: migrations **0087** and
**0088**, system prompt **v20** — whose body is the landed v19 read
byte-for-byte plus one appended paragraph, asserted as an equality rather than a
list of `contains` checks.

## Files Modified

- `db/schema/0087.create-aid-form-requirements.sql` (new; the `academic_year`
  domain and the five-column conversion, the `money_source` domain,
  `source_documents` and the three CDS tables' cutover onto it, the nullable
  document key on the two cohort tables with its CDS-must-cite CHECK,
  `aid_forms`, `aid_form_requirements`, the `aid_policy_facts` drop, and the
  cohort measure/population/aid-scope additions)
- `db/schema/0088.seed-coach-system-prompt-v20.sql` (new)
- `db/data/money-vocabulary.json`; `db/seed/cds/aid_policy.csv`,
  `db/seed/cds/PROVENANCE.json`
- `bin/fetch-cds-seed`, `bin/ingest-colleges`, `bin/load-external-data`,
  `bin/read-ipeds-manifest`, `bin/scripts-tests`
- `db/.../models/AidForm.kt`, `AidFormApplicantGroup.kt`, `CollegeAidPolicy.kt`,
  `FactTable.kt`, `SourceDocument.kt`, and the `AidPolicyDao.kt` /
  `SourceDocumentsDao.kt` / `SourceDocumentJoin.kt` set (new); `MoneySource.kt`,
  `CohortScopes.kt`, `MoneyMeasure.kt`, `CanonicalMoneyRows.kt`,
  `CanonicalMoneyDao.kt`, `CanonicalMoneyReadDao.kt`, `CdsAdmissionsDao.kt`,
  `CollegeIpedsChargesDao.kt`, `CollegeSfaDao.kt`, `CollegesDao.kt`,
  `FigureReading.kt`
- `common/.../common/util/AcademicYear.kt` — the year value type MOVES here from
  `:service`, because `:db`, `:college` and `:service` all read it and a second
  copy in `:db` is the duplication D13 refuses. It sits beside `Share.kt`, and
  it carries the 2000..2100 range its SQL domain states.
- `college/.../CdsSeedLoader.kt`, `CanonicalMoneyLoader.kt`,
  `CollegeScorecardLoader.kt`, `CollegeSfaLoader.kt`, `IngestApplication.kt`,
  `IpedsChargesLoader.kt`, `MoneyVocabularyLoader.kt`, `CdsCoverage.kt`
- service: `AidPolicyPractice.kt` + `AidPolicyWire.kt` (new),
  `CollegeCostService.kt`, `CollegeCostChatTool.kt`, `CollegeFigures.kt`,
  `CdsCitation.kt`, `FederalAidPolicy*`; `ScorecardVintage.kt` is DELETED (RFC
  166's `FigureGroup` supersedes it)
- `service.conf`, `CoachingConfigTest`, `SystemPromptCatalogTest`
- `.gitignore` (`__pycache__/`, `*.pyc` — a committed `.pyc` was found in review
  and no rule existed)

## Implementation Plan

1. Migration part 1: the `academic_year` domain and the conversion of the four
   money columns, plus `AcademicYear.kt` and its readers. Green before anything
   else starts.
2. Migration part 2: `source_documents`, the three CDS tables' cutover onto it,
   `aid_forms` vocabulary, `aid_form_requirements`, the `aid_policy_facts` drop,
   the cohort measure/population additions; Kotlin enums, DAO, constraint tests.
3. `bin/fetch-cds-seed`: CLI conversion, `get_decimal`, the new field ids and
   fact group, per-cell status, provenance; `bin/scripts-tests` fixtures.
4. Regenerate the seed against the live corpus; commit the new file and explain
   any digest movement in the existing three.
5. Loader, ingest phase, provenance counts.
6. Read model, wire, tool section, citations, guard allowlist.
7. Prompt migration + config + catalog test.

## Tests

- Schema: natural key, applicant-group CHECK, value-iff-status, source domain,
  FK onto `aid_forms`, the `source_documents` natural key, the FK from all four
  fact tables onto it, and that `aid_policy_facts` is gone.
- The `academic_year` domain rejects 2199 and accepts 2025; no table stores a
  `'YYYY-YY'` string; the label is rendered from the start year.
- No URL is stored twice: a test asserts the CDS tables carry no `source_url`
  column and that one document row backs all of a school's CDS facts.
- Vocabulary set-equality both ways: `aid_forms` rows vs `AidForm`.
- D5 pinned: the loader never writes `is_required = FALSE`.
- Seed: offline-fixture tests for each new drop reason; committed sha256 and row
  counts; a fractional need-met value that today's `get_int` would silently
  drop.
- Loader: `not_collected_by_us` rows land; wholesale replace is idempotent;
  header assertion fatals.
- Each measure is pinned to its population, so no figure can be silently
  re-pointed at another denominator.
- A form required only of nonresident applicants does NOT appear in the domestic
  read: the applicant-group predicate is given a row it must exclude (D4).
- Read: fully-met share only when both counts exist and naming its cohort; an
  absent form renders "not listed in this school's CDS"; a school with no filing
  renders the coverage-honest line; no bare field id in any result.
- Tool + prompt end to end for both first-session questions.
- Gate: `nix develop -c bin/test`.
