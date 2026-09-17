# db/seed/scorecard — how this manifest was made

`PROVENANCE.json` here is the committed record of a real College Scorecard
download, and the baseline `bin/fetch-scorecard` measures every later fetch
against. The two archives and the extracted CSVs themselves are **not**
committed (see the size note in `bin/fetch-scorecard` and the `.gitignore`
stanza), so this file and that manifest are the whole record.

## The fetch

`PROVENANCE.json` was written by `bin/fetch-scorecard` itself, on a real network
run on **2026-09-06** (`"fetched_at": "2026-09-06T03:49:14+00:00"`), at the
seconds precision a network run records. Nothing in it was hand edited: every
url, digest, member name and row count is what the fetcher wrote.

The run discovered release **`06102026`** from
<https://collegescorecard.ed.gov/data/> and downloaded the two archives that
page named:

- `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Institution_06102026.zip`
  — 23,559,465 bytes, sha256 `f56a181b…9ffd58`. Its one data member is
  `Most-Recent-Cohorts-Institution.csv` (100,102,932 bytes as written, 3,308
  columns, **6,273** data rows), and it also carries an
  `__MACOSX/._Most-Recent-Cohorts-Institution.csv` AppleDouble entry, which the
  fetcher drops rather than resolves.
- `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Field-of-Study_06102026.zip`
  — 17,188,590 bytes, sha256 `9c4339d6…578165`. Its one member is
  `Most-Recent-Cohorts-Field-of-Study.csv` (153,479,459 bytes, 178 columns,
  **227,980** data rows).

Both zip digests reproduce the ones an independent research download of the same
two archives recorded on **2026-09-05**, and both row counts match that
download's counts exactly.

The two URLs are the hrefs the `/data/` page served, not strings the fetcher
assembled. The **unversioned** names — the same two under `/downloads/` with the
`_06102026` stamp left off — still answer HTTP 200 and are `Last-Modified`
2024-08-22; a fetch pointed at them would ingest the August-2024 release forever
with a green exit, so `bin/fetch-scorecard` never constructs a URL and refuses
outright when the page names no download, names several for one artifact, or
names two files from different releases.

The host is `ed-public-download.scorecard.network`. The
`ed-public-download.app.cloud.gov` that older prose in this tree names now 404s
on everything, including `/`.

The 448 MB `College_Scorecard_Raw_Data_06102026.zip` is a strict superset
holding byte-identical copies of these same two members; it is deliberately not
downloaded. (Note for anyone who ever reaches for it: it unpacks to
`College_Scorecard_Raw_Data_06032026/` — an internal date that DISAGREES with
its URL stamp — so its top directory must be resolved by pattern, never rebuilt
from the URL.)

## The recorded release

`release` is the eight-digit stamp the publisher puts in both URLs, recorded at
the TOP level because it belongs to the PAIR: discovery has already refused two
artifacts whose stamps disagree, so one recorded value cannot contradict the
files beside it. It is the only version identity these files carry — there is no
survey year here — and it is what lets the next run say the release moved.

A run whose discovered stamp differs from this one **logs the change and
proceeds**: a new release is the point of the fetcher. The member-drift and
shrink guards below still run against that new release.

`release` is `null` only for an offline (`-F`) replay, exactly as `fetched_at`
is: `null` means "this was not a fetch" and is never a stand-in for "unknown".
`fetched_at` is deliberately never `null` here.

## The member figures

`member`, `member_bytes`, `member_sha256` and `rows` describe the members **as
written** — decoded `utf-8-sig` and re-encoded UTF-8, so no BOM survives into
`UNITID` — and they come from the fetcher's own extraction, not from hand
counting.

`rows` is what the shrink guard compares against (`SEED_SHRINK_FLOOR` in
`bin/fetch-scorecard`), and `member` is what the member-drift guard compares
against. `ingest_position` (1 for the institution file, 2 for the field of
study) is the fetcher's declaration of which `bin/load-external-data` positional
each file becomes, so a reader that builds that command never re-derives the
order from a filename.

A second run of the fetcher against this directory reports both guards `armed`
and both counts unchanged.

## The data dictionary, and the transcription beside it

`dictionary-variable-sources.csv` in this directory is a HAND TRANSCRIPTION, not
a fetched artifact: `variable_name,source,most_recent_cohort` for every
Scorecard column this corpus loads, each carrying the `SOURCE` value the
publisher's own dictionary states for it (RFC 179 D4) and the year that
dictionary dates it to (RFC 183). How many columns that is, is the file's own
answer — it is stated there and restated nowhere else, so a column added to the
loader moves one number in one place.

`most_recent_cohort` is the `Cohort/Measure in "Most Recent" Datafile` value
from the workbook's `Most_Recent_Inst_Cohort_Map` sheet, as the publisher writes
it — which variable carries which year is the CSV's own answer, stated there and
restated nowhere else, exactly like the column count above. It is EMPTY for the
two administrative records, because the publisher pools them across cohorts
(`GRAD_DEBT_MDN` is NSLDS FY2020+FY2021, `MD_EARN_WNE_P10` is a Treasury
two-cohort measure) and states no academic year for either — an empty cell is
"no academic year", never "unknown". `ScorecardDictionaryPinTest` asserts the
loader's two year constants against this column, so a snapshot bump that
re-dates a variable fails a test instead of quietly re-dating every price we
serve.

It is transcribed rather than pinned, on the `IpedsImputationFlag` precedent: a
small table that changes when NCES re-sources a column is authored vocabulary
with a citation, not a 727 KB spreadsheet to parse at runtime — and there is no
xlsx reader on this project's JVM classpath at all. RFC 173 declined to pin the
workbook for that reason and this file does not reverse that decision.

The source it was read from:

- <https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
  sheet `Institution_Data_Dictionary`, downloaded **2026-09-05**, **727,278**
  bytes.
- Corroborated against `InstitutionDataDocumentation.pdf` (531,031 bytes,
  "Version: September 2025"), whose cost section states that the cost elements
  are derived from data reported to the IPEDS Institutional Characteristics and
  Student Financial Aid components, and whose debt and earnings sections name
  NSLDS and Treasury.

The committed transcription's own digest, so an undocumented edit of it fails a
test rather than moving a tier quietly:

- `dictionary-variable-sources.csv` — sha256
  `329f4ec4cc9cb2ff4d941519d50aba46949a46bbb0f6d29f52c862673dbf778b`

`AssuranceTier.SCORECARD_TIERS` is asserted against this file column by column,
and the digest above is typed beside that test. `PROVENANCE.json` deliberately
carries none of this: the fetcher writes that file whole and would erase a
hand-added key.

## The six percentile score-band variables (RFC 195)

`SATVR25`, `SATVR75`, `SATMT25`, `SATMT75`, `ACTCM25` and `ACTCM75` are read
from the institution file into `colleges` by `CollegeScorecardLoader`. Their
date is the publisher's own, read from the same workbook as the table above —
`CollegeScorecardDataDictionary.xlsx` (727,278 bytes), sheet
`Most_Recent_Inst_Cohort_Map`, which dates all six, and `SAT_AVG` with them, to:

> **Fall 2024, reported in IPEDS DCY2024-25**

and sheet `Institution_Data_Dictionary`, which gives `SOURCE = IPEDS` for all
six, labels `SATVR*` _critical reading_, `SATMT*` _math_, and `ACTCM*` the ACT
_cumulative_ score.

So the honest date of a band is an **entering-cohort term** — autumn 2024 — and
not an academic year. It lives in ONE constant, `ScoreBandCitation.COHORT_TERM`
(`service/.../admissions/SchoolScoreBands.kt`), which quotes the cell above in
its KDoc; every sentence the coach says about a band takes the term from there.
Beside it, `COHORT_SNAPSHOT_RELEASE` records the release that cell was read from
— `06102026`, the `release` in `PROVENANCE.json` — and `ScoreBandCopyTest`
asserts the two agree, so a re-fetch that moves the data fails a test instead of
leaving the sentence dated to a cohort nobody checked.

**They are deliberately NOT added to `dictionary-variable-sources.csv`**, and
the reason is that file's own contract rather than an oversight:

- `ScorecardDictionaryPinTest` asserts that the file's key set **equals**
  `ScorecardInstitutionColumns.LOADED_VARIABLES` — the canonical-money fill's
  registry. A band is not money and is not in that registry, so adding a row for
  it fails that test, correctly: the file is the money seam's pin, not a general
  dictionary.
- Its `Cohort` type parses **`AcadYr` strings only**. "Fall 2024" is a term, not
  an academic year, so the one thing a row there would have to state is the one
  thing that column cannot hold — and the nearest legal value would silently
  re-date the band by a year.

A later snapshot that moves the cohort therefore moves `COHORT_TERM` and this
section, in one edit, with the cell above quoted from the new workbook.

## The three field-of-study money variables (RFC 218)

`EARN_MDN_4YR`, `DEBT_ALL_STGP_EVAL_MDN` and `DEBT_ALL_STGP_EVAL_MDN10YRPAY` are
read from the **field-of-study** file into `program_money_stats` by
`ProgramMoneyLoader`. Their cohorts are the publisher's own, read from the same
workbook as the two sections above — `CollegeScorecardDataDictionary.xlsx`
(727,278 bytes,
<https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
read **2026-09-15**), sheet `FieldOfStudy_Cohort_Map`, column
`FieldOfStudyMostRecent datafile` — the column for the file we ingest.
Descriptions and publishers come from sheet `FieldOfStudy_Data_Dictionary`.

| Variable                        | Publisher | Publisher's cohort cell, verbatim                                                                                         | Stored years |
| ------------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------- | ------------ |
| `EARN_MDN_4YR`                  | Treasury  | `Treasury AY2017-2018, AY2018-2019 pooled earnings cohort measured in CY2022, CY2023, inflation adjusted to 2024 dollars` | 2017, 2018   |
| `DEBT_ALL_STGP_EVAL_MDN`        | NSLDS     | `NSLDS pooled AY2018-19, AY2019-20 cohort`                                                                                | 2018, 2019   |
| `DEBT_ALL_STGP_EVAL_MDN10YRPAY` | NSLDS     | `NSLDS pooled AY2018-19, AY2019-20 cohort`                                                                                | 2018, 2019   |

The workbook's `README` says "Released June 10, 2026" and its `ChangeLog` dates
the latest `FieldOfStudy_Cohort_Map` refresh to the same day, which is the
`release` in `PROVENANCE.json` (`06102026`). That is a **date match, not a
stamped filename**, and this section says so rather than claiming more.

The row stores the two years; this file and `ScorecardFieldOfStudyColumns` store
the sentence. The two academic years are a fact about the ROW, so they are typed
`academic_year` columns on `program_money_stats`. Everything else in the cell —
the calendar years earnings were measured in, and the 2024 dollar base — is a
fact about the VARIABLE, identical on all ~684,000 rows, so it lives once, in
`college/.../ScorecardFieldOfStudyColumns.kt`. That object also carries
`COHORT_SNAPSHOT_RELEASE = "06102026"`, and `ScorecardFieldOfStudyPinTest`
asserts it equals `PROVENANCE.json`'s `release` — so a re-fetch that moves the
data fails a test instead of leaving three cohorts dated to a release nobody
re-read.

**An earnings figure is in 2024 dollars and nothing on its row says so.** The
inflation base is a named field,
`ScorecardFieldOfStudyColumns.EARNINGS.inflationBaseYear`, reached from any row
through its `source_variable`; the
`COMMENT ON COLUMN
program_money_stats.source_variable` names that object so a
reader of the schema alone is sent to it. A sentence that speaks an earnings
figure without its dollar base is a defect.

Three things the publisher does **not** state, recorded so no later slice
invents them:

- **No dollar year for either debt column.** The earnings cell names its
  adjustment; the debt cells name none. The word "inflation" occurs twice in the
  whole of `FieldOfStudyDataDocumentation.pdf` (891,554 bytes, read 2026-09-15),
  both inside the earnings paragraph. Missing, not "nominal 2020".
- **No interest rate tied to this release.** The 10-year payment's method is
  described only in that PDF, whose own version line reads "Version: June 2024"
  — two years older than our snapshot. Copy may say "the publisher's own
  estimate of the monthly payment on a standard 10-year plan"; it may **not**
  name a rate.
- **The earnings column and the debt columns are different cohorts from
  different publishers** — Treasury AY2017-18+AY2018-19 against NSLDS
  AY2018-19+AY2019-20. That is why the two are never averaged, combined or
  ratio'd: a debt-to-earnings ratio over them is a figure nobody published.

**They are deliberately NOT added to `dictionary-variable-sources.csv`**, for
the reason the six score bands above are not — that file's key set must
**equal** `ScorecardInstitutionColumns.LOADED_VARIABLES`, the INSTITUTION money
seam's registry; every `VintageGroup` member in it is an institution cohort, so
joining one would stamp `EARN_MDN_4YR` with a tuition year; and its `Cohort`
type parses `AcadYr <label>` or empty, while these cohorts are pooled two-year
spans.

## Regenerating

Re-run the fetcher; it discovers the current release, downloads it, and rewrites
this directory in place:

```sh
nix develop -c bin/fetch-scorecard
```

To replay the recorded fixtures instead, with no network at all, give the replay
its own output directory (`-F` requires an explicit `-o`, and a replay is
refused against this one):

```sh
nix develop -c bin/fetch-scorecard -F college/src/test/resources -o <scratch-dir>
```
