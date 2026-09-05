# RFC 162 — IPEDS SFA: honestly-labeled aid statistics in the canonical layer

Slice: `shape/03/ipeds-sfa` (brief 0006, gate 2 approved 2026-09-02). Needs:
BLOCKS `shape/01/canonical-store` (landed, RFC 158) — met. CONFLICTS
`shape/02/ipeds-ic-ay` (`pipeline/rfc-161`, live, no code yet).

## Summary

Pin IPEDS SFA (student financial aid) and fill `cohort_money_stats` from it: net
price with an honest population label, income-band net price, Pell
number/share/average, aid mix by source, loan share/average — plus the
population counts (residency-rate-paying and living-arrangement) that make a
basis concrete. No consumer changes; the door is `shape/04`.

Every mapping claim below is measured, not quoted: the research parsed the real
NCES artifacts for four aid years and ~450 live Scorecard calls
(`.scratch/ship/rfc-162/findings/research-sfa-source.md`), and the repo facts
are `.scratch/ship/rfc-162/findings/research-repo-ingest.md`.

## Detailed Design

### 1. The source, and three corrections to the spec

`SFA2223.zip` (aid year 2022-23, 1,913,114 B) + `SFA2223_Dict.zip` (the
imputation-code list) from `nces.ed.gov/ipeds/datacenter/data/`. Data member
`sfa2223.csv`: 5,653 rows x 691 columns. Every variable except `UNITID` has an
`X` imputation twin (345/346 — a mechanical rule).

**Correction 1 — SFA splits public and private with different names for the same
concept, and the spec names only the public half.** Net price is `NPIST*`
(public) / `NPGRN*` (private); income bands are `NPIS41-45` (public) /
`NPT41-45` (private, literally that name inside the SFA file); counts are
`GIST*`/`GIS4*` (public) / `GRNT*`/`GRN4*` (private). They are mutually
exclusive — zero institutions carry both. Loading only `NPIS41-45` as the spec
says covers 1,798 of 5,653 rows and silently drops ~65% of colleges. **We load
both families.**

**Correction 2 — the flag mapping in the spec is wrong on `Z`.** `Z` is "implied
zero": a real published zero (17,357 cells in SFA2223, value always exactly 0).
Mapping `Z -> imputed_by_publisher`, as the spec's shape/02 line does, would
label 17k honest zeros as publisher guesses. Measured over four aid years, only
`R, A, Z, P, C, N` ever occur, with zero exceptions over 1.95M (value, flag)
pairs: `A` => value always NULL; `R/C/P/N` => value always present; `Z` => value
always 0. The mapping this RFC implements is:

| Flag               | Meaning                                  | Status                        |
| ------------------ | ---------------------------------------- | ----------------------------- |
| `R`, `C`           | reported / analyst-corrected             | `reported`                    |
| `Z`                | implied zero (a real published 0)        | `reported`                    |
| `A`                | not applicable                           | `not_applicable`              |
| `P`, `N`           | imputed (prior year / nearest neighbour) | `imputed_by_publisher`        |
| `G`, `J`, `K`, `L` | other publisher imputations              | `imputed_by_publisher`        |
| `B`, `D`, `H`      | do-not-know / left blank                 | `not_reported_by_institution` |

The last two rows are defensive: those codes never occur in the four years
measured. `not_reported_by_institution` is therefore effectively unfillable from
SFA — NCES imputes instead of blanking — so no test asserts its presence.

**Correction 3 — `_RV` is a second member of the SAME zip, and it matters.**
`SFA2122.zip` contains `sfa2122.csv` AND `sfa2122_rv.csv`; they differ in 411
columns / 3,612 cells, including `npist2` (32 rows) and `pgrnt_a` (31). The
existing `bin/fetch-ipeds` rule drops `_rv` members and would knowingly ingest
superseded money. See decision D2.

### 2. The variable -> measure mapping

Year suffixes are RELATIVE to the file: in `SFAyyzz`, `2` = the file's own aid
year, `1` = one earlier, `0` = two earlier (verified across two files). We load
all three, so one `SFA2223` pin yields vintages `2020-21`, `2021-22`, `2022-23`
(D15: store history, serve latest).

**The aid scope follows the DENOMINATOR of the measure.** A `*_share` is a share
OF THE COHORT, so its aid scope is `all`; an average is an average OVER
RECIPIENTS, so it carries its own receiving scope. Getting this wrong is not a
labelling nicety — it stores a number about one population under the name of
another.

| Measure (new unless noted)                     | Public var            | Private var       | Population / scopes                                                                                         |
| ---------------------------------------------- | --------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------- |
| `avg_net_price` (exists)                       | `NPIST0-2`            | `NPGRN0-2`        | `first_time_full_time_aid_cohort`, residency `in_state_rate_paying` (pub) / `all` (priv), aid `grant_aided` |
| `avg_net_price` by band                        | `NPIS41-45{0,1,2}`    | `NPT41-45{0,1,2}` | `title_iv_aided_undergraduates` — the Scorecard NPT4 key, so upstream-wins can collide honestly             |
| `pell_share` (exists)                          | `UPGRNTP`             | same              | `undergraduates`, aid `all`                                                                                 |
| `pell_average_award`                           | `UPGRNTA`             | same              | `undergraduates`, aid `pell_receiving`                                                                      |
| `federal_grant_share` / `_average_award`       | `FGRNT_P` / `FGRNT_A` | same              | `first_time_full_time_aid_cohort`; share `all`, average `federal_grant_receiving`                           |
| `state_local_grant_share` / `_average_award`   | `SGRNT_P` / `SGRNT_A` | same              | same; average `state_local_grant_receiving`                                                                 |
| `institutional_grant_share` / `_average_award` | `IGRNT_P` / `IGRNT_A` | same              | same; average `institutional_grant_receiving`                                                               |
| `student_loan_share` / `_average_amount`       | `LOAN_P` / `LOAN_A`   | same              | same; share `all`, average `loan_receiving`                                                                 |

`LOAN_P`/`LOAN_A` cover ANY loan — federal, institutional and private — so
neither may be labelled `federal_loan_borrowing`.

The Pell RECIPIENT COUNT (`UPGRNTN`) is a headcount, so it is not a money
measure at all: it lands in `cohort_population_counts` (§3) as its own
population. `MeasureUnit` therefore stays USD-or-share, and the unit now DRIVES
the percent-to-share conversion instead of a hand-passed scale factor.

`NPIST/NPGRN` describe the **grant-aided** population; `NPIS4x/NPT4x` describe
the **Title IV-aided** population. Different cohorts — the bands do not roll up
to the overall figure, and `CohortAidScope` had no grant-aided value, so one is
added. This distinction is the whole point of the slice's criterion that a basis
be queryable structure.

### 3. Population counts go in a sibling table

`SCFA11-14N` (fall cohort paying in-district / in-state / out-of-state rates)
and `GIS4ON/WF/OF/UN` + `GRN4*` (students living on campus / with family / off
campus not with family / unknown) are **headcounts, not money**. They do not
belong in `cohort_money_stats`, whose own table comment says "a number about a
population, never a price": a headcount has no `MeasureUnit`, needs a residency
value the money table's two-value `residency_scope` cannot express, and needs an
arrangement dimension the money table does not have at all.

So: a new `cohort_population_counts` table keyed by college x cohort x
`residency_bases` x `arrangements` x vintage, reusing RFC 158's authored
vocabulary tables (`residency_bases`, `arrangements`, `figure_statuses`) by FK.
A row may name neither dimension — that is a plain cohort headcount, which is
where the Pell recipient count lives. It answers "what share of this public's
students does the in-state price actually apply to" (College of DuPage: 1,488 of
2,099 = 71% in-district) and "how many live with family" — the weights the
Scorecard blends away.

### 4. Upstream-wins, precisely scoped

`CanonicalMoneyLoader.ORDERED_SOURCES` is first-write-wins per natural key (RFC
158, P8). SFA is prepended, so SFA wins where the fact is the SAME fact.
Measured identities: `NPT41-44_PUB == NPIS41-44` (198/197/196/187 of 200 exact);
`NPT4x_PRIV == NPT4x`; `PCTPELL == UPGRNTN/SCUGRAD` (199/200 within 1e-4).

**`NPT4_PUB` is NOT an identity and must not be overwritten.** It has no SFA
variable; it is the band-count-weighted mean `sum(GIS4Nk*NPIS4k)/sum(GIS4Nk)`
(reproduced to +/-$1). It describes the Title IV-aided population; `NPIST2`
describes the grant-aided one. They land as two rows with different aid scopes,
not one row overwriting the other. `GRAD_DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10`
(Treasury) have no SFA twin and stay Scorecard's.

Restatement across files is real (SFA2223 disagrees with SFA2122 about AY2021-22
for 133 publics), but this pin loads one file, so within a fill the newest
statement is the only statement.

### 5. Fetch and ingest plumbing

- `bin/fetch-ipeds`: two rows in the `ARTIFACTS` table. Its
  `require_survey_year()` fatalled unless every artifact's year equalled a
  single global `SURVEY_YEAR`; SFA is keyed by aid year 2022-23, so the expected
  year becomes a field on each artifact row (`expected_year`) rather than a
  global. Atomic write set, member-drift guard, PROVENANCE digests: unchanged.
- `bin/ingest-colleges`: one new all-or-nothing option group — `-S` (the SFA
  CSV) plus `-f` (its aid year) — following the CDS precedent. It is NOT "data +
  dict": no code opens the dictionary, so a `-D` flag would be a lie in the
  grammar. The aid-year flag is `-f` because RFC 161 landed `-Y` for the IC_AY
  file first.
- `CollegeScorecardLoader.ingest()`: a `sfa` ROW phase beside `ipeds`, before
  the derived phases; the canonical fill then reads staging **for the run's own
  aid year**, passed in — never whatever a previous run left staged.
- `CanonicalMoneyLoader`: `MoneySource.IPEDS_SFA` first in `ORDERED_SOURCES`
  (ahead of RFC 161's `IPEDS_IC_AY`, then `SCORECARD`) with its mapping branch;
  `METHOD_VERSION` bumped; `canonical_money_summary` gains SFA row and
  per-status counts, plus the named loss classes (§7).

### 6. The X-flag mapper is shared with shape/02, and this RFC owns it

When this RFC was written no X-flag handling existed anywhere in the repo, and
no code path could produce `imputed_by_publisher` or `not_applicable`.
`shape/02` needed the identical mapper and the identical year fix. RFC 161
landed first with its own nested copy; the rebase deleted that copy, and
`IpedsImputationFlag` (in `db/.../models`, carrying a sealed `FlagMeaning` so a
reading is an exhaustive `when` with no fatal path) is now the ONE flag
vocabulary for both surveys.

**This corrects landed behaviour.** RFC 161 mapped `Z` to
`imputed_by_publisher`; one enum cannot hold two meanings for one letter, and
the measured evidence is decisive — `Z` is an implied zero NCES writes itself,
value always exactly 0 (§1). Both surveys now read `Z` as `reported`, and RFC
161's prose is superseded on this point, per `rfc/README.md`: the code wins.

### 7. Honest losses are counted, never inferred

Every way a cell can fail to become a row is its own named counter, surfaced in
the ingest summary and the provenance JSON: a value the publisher filled that
will not parse (`valuesUnreadable` — never folded into "absent", or the fill
would report OUR parse loss as the publisher's disagreement), a value present
under a not-applicable flag, a variable the fill looks up that the loader never
staged (`sfaCellsNotStaged`), and an institution matching neither the public nor
the private variable family. An unrecognised imputation letter is fatal and
names every offending column, unit and line — not just the first.

## Files Modified

- `db/schema/0085.create-sfa-staging-and-population-counts.sql` (new) — the
  `college_sfa` staging table (keyed by `aid_year_start`, not a survey year: the
  same run stages IPEDS survey year 2023 and SFA aid year 2022), the
  `cohort_population_counts` table, and the widened `cohort_money_stats` CHECK
  lists (measure, population, aid_scope), composed with RFC 161's landed schema
  rather than doubling it. Price keys are guarded by an ALLOWLIST of
  price-keyable slugs, and share-valued rows carry a range CHECK.
- `db/src/main/kotlin/ed/unicoach/db/models/MoneyMeasure.kt` — new measures.
- `db/src/main/kotlin/ed/unicoach/db/models/CohortScopes.kt` — the new
  populations and aid scopes (§2).
- `db/src/main/kotlin/ed/unicoach/db/models/IpedsImputationFlag.kt` (new, with
  its sealed `FlagMeaning`), `MoneySource.kt` (RFC 161's enum gains
  `IPEDS_SFA`), `IncomeBand.kt` (one `bandDigit`, shared with the NPT4 mapping).
- `db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyRows.kt`,
  `CollegeSfaRows.kt` (new),
  `db/src/main/kotlin/ed/unicoach/db/dao/CanonicalMoneyDao.kt`,
  `CollegeSfaDao.kt` (new), `BatchWrites.kt` (new — RFC 158's private batch
  writer lifted to a shared, tested helper rather than copied a third time).
- `college/src/main/kotlin/ed/unicoach/college/CollegeSfaLoader.kt` (new),
  `SfaVariables` (the one owner of the staged variable names, arrangement and
  residency code sets, and the public/private `SfaFamily`),
  `CanonicalMoneyLoader.kt`, `CollegeScorecardLoader.kt`, `IngestApplication.kt`
  (one generic flag-group parser serving both the IPEDS and SFA groups).
- `bin/fetch-ipeds`, `bin/ingest-colleges`, `bin/scripts-tests`.
- `db/seed/ipeds/PROVENANCE.json` and `db/seed/ipeds/FETCH-NOTES.md` (the
  manifest and its notes), `db/data/money-vocabulary.json`.

## Implementation Plan

1. Migration (number claimed immediately before commit — it landed as 0085 after
   RFC 161 took 0084 mid-run).
2. Enums + `IpedsImputationFlag` + the flag->status mapper, with its table test.
3. `bin/fetch-ipeds` per-artifact expected year + the revised-member policy
   (D2); real network fetch to pin true digests.
4. `college_sfa` staging loader + the `bin/ingest-colleges` group.
5. Canonical fill: SFA branch, both public and private variable families, three
   vintages, population counts.
6. `ORDERED_SOURCES` prepend + the NPT4_PUB carve-out.
7. Ingest summary and `college_index_build` counts; `METHOD_VERSION` bump.

## Tests

- **Flag mapping**: every one of the 13 codes maps to the stated status; `Z`
  maps to `reported` and carries value 0 (the correction, pinned), for BOTH
  IPEDS surveys.
- **Value/flag invariants**: `A` => NULL, `R/C/P/N` => present, `Z` => 0.
- **Public/private coverage**: a private-control fixture lands `NPGRN`/`NPT4x`
  rows; a public fixture lands `NPIST`/`NPIS4x`; neither carries both, and an
  institution matching NEITHER family is counted, not dropped.
- **Upstream-wins**: Portland State (UNITID 209807) AY2021-22 band 1 — Scorecard
  `NPT41_PUB` 16,348 vs SFA `NPIS41` 10,311; SFA wins, one row, source
  `ipeds_sfa`, never averaged (D5).
- **No overwrite of a different population**: `NPT4_PUB` (Title IV-aided) and
  `NPIST2` (grant-aided) coexist as two rows with different aid scopes.
- **Aid scope is asserted with the measure**, never the value alone — the
  denominator rule of §2 is what the test pins.
- **Pell average exists** where the Scorecard has none: University of Alabama
  (100751) `UPGRNTA` = $5,202, flag `R`.
- **Population counts**: College of DuPage (144865) `SCFA11-14N` = 1,488 / 553 /
  58 / 0 summing to `SCFA1N` 2,099; its `xgis4on2 = A` lands as `not_applicable`
  (no dorms) beside a reported `gis4wf2 = 501`.
- **Three vintages** from one pin; suffix mapping is relative, not hardcoded.
- **The fill reads the run's own staging, not the tree's**: an ingest without
  the SFA group emits zero `ipeds_sfa` rows even when a previous run's cells are
  still staged.
- **Every loss class is counted** (§7): an unparseable published value is
  `valuesUnreadable`, never an absence; a looked-up variable that was never
  staged moves `sfaCellsNotStaged`, and a test asserts it is empty, i.e. the
  loader and the fill agree on every variable name.
- **Idempotence / phase-transactionality** of `bin/ingest-colleges`, the argv
  refusals of the new group, and the provenance row naming the SFA fill.
- Set-equality both ways between every widened CHECK list and its Kotlin enum,
  including the imputation-flag domain.
