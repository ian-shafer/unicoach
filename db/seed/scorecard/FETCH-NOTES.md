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

`most_recent_cohort` is the LEADING clause of the
`Cohort/Measure in "Most Recent" Datafile` value from the workbook's
`Most_Recent_Inst_Cohort_Map` sheet — the part before the `, reported in` the
sheet appends to every row. A cell reads `AcadYr 2024-25`, not
`AcadYr 2024-25, reported in IPEDS DCY2024-25`; the trailing clause is the
collection year, not the period the figure describes, and it is identical on
every row. Which variable carries which year is the CSV's own answer, stated
there and restated nowhere else, exactly like the column count above. It is
EMPTY for the two administrative records, because the publisher pools them
across cohorts (`GRAD_DEBT_MDN` is NSLDS FY2020+FY2021, `MD_EARN_WNE_P10` is a
Treasury two-cohort measure) and states no academic year for either — an empty
cell is "no academic year", never "unknown". `ScorecardDictionaryPinTest`
asserts the loader's two year constants against this column, so a snapshot bump
that re-dates a variable fails a test instead of quietly re-dating every price
we serve.

It is transcribed rather than pinned, on the `IpedsImputationFlag` precedent: a
small table that changes when NCES re-sources a column is authored vocabulary
with a citation, not a 727 KB spreadsheet to parse at runtime — and there is no
xlsx reader on this project's JVM classpath at all. RFC 173 declined to pin the
workbook for that reason and this file does not reverse that decision.

The source it was read from:

- <https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
  sheet `Institution_Data_Dictionary`, downloaded **2026-09-05**, **727,278**
  bytes.
- The SAME artifact, downloaded again **2026-09-25** for RFC 242's four rows:
  **727,278** bytes, sha256
  `2f314d1d02d4ce7e8ca29feffaaa69860b18b2812cbb407adc014c6792fa1740`. A second
  dated read, not a replacement for the line above it: the two reads are
  byte-identical, which is a FACT this file can only record by keeping both
  dates. The download is deliberately not committed and no script in `bin/`
  fetches it (RFC 173/183/226, unreversed).
- Corroborated against `InstitutionDataDocumentation.pdf` (531,031 bytes,
  "Version: September 2025"), whose cost section states that the cost elements
  are derived from data reported to the IPEDS Institutional Characteristics and
  Student Financial Aid components, and whose debt and earnings sections name
  NSLDS and Treasury.

The committed transcription's own digest, so an undocumented edit of it fails a
test rather than moving a tier quietly:

- `dictionary-variable-sources.csv` — sha256
  `4c605a01184c6d92fc248b1ebba8346f87c513a62184d6b2e00c6e96f11b6f36`

`AssuranceTier.SCORECARD_TIERS` is asserted against this file column by column,
and the digest above is typed beside that test. `PROVENANCE.json` deliberately
carries none of this: the fetcher writes that file whole and would erase a
hand-added key.

## The three outcome columns (RFC 226)

`ADM_RATE`, `C150_4` and `UGDS` joined the transcription in RFC 226. They are
the three outcome figures a search row carries that had NO stamp at all, and
brief 0011 D47 dates every figure a family can see. Their rows are the same hand
transcription as the 24 beside them, read from the same workbook and the same
two sheets: `SOURCE` from `Institution_Data_Dictionary`, and the leading
`Cohort/Measure` clause from `Most_Recent_Inst_Cohort_Map`.

**What each of the three is sourced to, and dated to, is NOT restated here.**
That is the CSV's own answer, stated there and restated nowhere else, exactly as
the column count and the `most_recent_cohort` convention above are — and for the
same reason: a re-dated snapshot moves `dictionary-variable-sources.csv`, and a
table in this file that repeated those cells would go stale with nothing able to
contradict it. `ScorecardDictionaryPinTest` reads the committed CSV for all
three, and `AssuranceTierClosureTest` pins its digest, so the transcription
cannot be re-dated or re-sourced quietly.

All three cells are entering TERMS rather than academic years, which is why the
`Cohort` type carries a `StatedTerm` arm at all. One of them contains a comma,
so it is quoted in the CSV; the reader is `commons-csv` with a default format,
and `ScorecardDictionaryPinTest` asserts the round trip against the committed
file. The leading-clause convention above applies here too: the workbook appends
`, reported in IPEDS DCY2024-25` to each of these cells.

This RFC PARTLY SUPERSEDES the RFC 195 decision below: its two reasons for
keeping non-money dates in Kotlin are now spent FOR THESE THREE, so they live in
the CSV. The six score-band variables and `SAT_AVG` stay in Kotlin exactly as
RFC 195 left them.

## The four completion columns (RFC 242)

`OMAWDP8_ALL`, `OMAWDP8_PELL_ALL`, `OMAWDP8_NOPELL_ALL` and `TRANS_4` joined the
transcription in RFC 242, on the same terms as the three above: `SOURCE` from
`Institution_Data_Dictionary`, and the leading `Cohort/Measure` clause from
`Most_Recent_Inst_Cohort_Map`, hand-transcribed from the 2026-09-25 read
recorded above. **What each is sourced to and dated to is NOT restated here**,
for the same reason the three above are not: the CSV is that answer.

One thing about them IS stated here, because it is a fact about the READER and
not about a cell. The three `OMAWDP8_*` cells are a full **entering academic
year**, not a term and not a money vintage — the publisher writes them
`AY <label> cohort`, a third shape beside `AcadYr <label>` and `Fall <yyyy>`.
`ScorecardDictionaryTranscription` therefore carries a third `Cohort` arm,
`StatedEnteringAcademicYear`, and the citation that speaks one says the academic
year rather than an autumn: a cohort that entered across a July-June year is not
an autumn cohort, and saying so would misstate the population. `TRANS_4` needs
no new arm — it carries `C150_4`'s own cell, because it counts that same cohort.

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
the reason is that file's own contract rather than an oversight. RFC 226 partly
supersedes the two reasons below FOR THE THREE OUTCOME COLUMNS (`ADM_RATE`,
`C150_4`, `UGDS`) — they now live in the CSV, with a third `Cohort` arm for a
term — while the score-band variables stay in Kotlin for exactly the reasons
stated here:

- `ScorecardDictionaryPinTest` asserts that the file's key set **equals**
  `ScorecardInstitutionColumns.DATED_VARIABLES` — the money fill's registry plus
  the three outcome columns, joined once in the registry object rather than in
  each suite that checks it. A band is neither money nor an outcome figure and
  is in neither registry, so adding a row for it fails that test, correctly.
- Its `Cohort` type once parsed **`AcadYr` strings only**. RFC 226 added a
  `StatedTerm` arm, so a Fall term CAN now be held there — but a score band is
  dated by `ScoreBandCitation.COHORT_TERM` inside its own sentence, and folding
  it into the CSV is a second decision nobody has taken.

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
**equal** `ScorecardInstitutionColumns.DATED_VARIABLES`, the INSTITUTION seam's
registries; every `VintageGroup` member in it is an institution cohort, so
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
