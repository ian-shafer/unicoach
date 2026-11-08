# db/seed/scorecard — how this directory was made

Everything committed here was written by `bin/fetch-scorecard` on a real network
run; nothing in it is hand edited. `PROVENANCE.json` is the record of that run
and the baseline the fetcher measures every later fetch against.

| File                                     | What it is                                                                                          | Committed |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------- | --------- |
| `Most-Recent-Cohorts-Institution.csv`    | the institution-level data file, `bin/load-external-data`'s first positional                        | no        |
| `Most-Recent-Cohorts-Field-of-Study.csv` | the field-of-study data file, its second positional                                                 | no        |
| `CollegeScorecardDataDictionary.xlsx`    | the publisher's data dictionary workbook, as downloaded                                             | yes       |
| `dictionary-institution-cohorts.csv`     | the workbook's `Most_Recent_Inst_Cohort_Map` sheet, every column, cells verbatim                    | yes       |
| `dictionary-institution-sources.csv`     | its `Institution_Data_Dictionary` sheet: `VARIABLE NAME`, `SOURCE`, `NAME OF DATA ELEMENT`, `NOTES` | yes       |
| `dictionary-field-of-study-cohorts.csv`  | its `FieldOfStudy_Cohort_Map` sheet: `Variable Name`, `FieldOfStudyMostRecent datafile`             | yes       |
| `PROVENANCE.json`                        | the run's record: per artifact its URL, release stamp, digests and row counts                       | yes       |

The two data CSVs are too large to commit (their sizes are `member_bytes` in
`PROVENANCE.json`), so they stay out of git (the `.gitignore` stanza); their
digests and row counts are in `PROVENANCE.json` too.

## The data dictionary is the home of every Scorecard year (RFC 274)

The publisher dates every variable itself, in the dictionary's cohort-map
sheets. The ingest reads the three CSVs above (`bin/ingest-colleges -D`) and
stamps every Scorecard money and field-of-study row with the period the
dictionary states for its column, so moving to a newer release is a fetch and an
ingest, never a Kotlin edit. A release that drops, renames or back-dates a
column the ingest serves is refused at ingest, naming the variable.

Which year each variable describes is the CSVs' own answer and is not restated
here, because a restatement would go stale with nothing able to contradict it.
The CSVs are written cells verbatim, so a release that re-dates a variable shows
in the diff of `dictionary-institution-cohorts.csv` or
`dictionary-field-of-study-cohorts.csv`, and the fetcher names the changed
variables on stderr when it overwrites a predecessor.

The digest of each CSV, and of the workbook, is recorded under
`artifacts.CollegeScorecardDataDictionary` in `PROVENANCE.json`;
`ScorecardDictionaryTest` holds the committed CSVs to those digests. The
workbook is committed because its URL is unversioned and the publisher
overwrites it, so without it nobody could check the extraction against its
source. Its `README` sheet's "Released ..." line is recorded as `released`.

The few periods still typed in Kotlin -- the outcome citations
(`ScorecardOutcomeCitations`) and the score-band term (`ScoreBandCitation`) --
are pinned by tests against these committed CSVs, so a release that moves one
fails the suite naming the variable and the publisher's cell.

## Release stamps

The `/data/` page names each data file with an eight-digit `MMDDYYYY` stamp, and
the publisher re-issues one file without the other, so the two may carry
different stamps. Each artifact records its own `release`; a disagreement is a
warning naming both, never a refusal. A run whose stamp differs from the one
recorded logs the move and proceeds -- a new release is the point of the
fetcher. `release` and `fetched_at` are `null` only on an offline (`-F`) replay.

## Traps the fetcher is built around

- **The unversioned data URLs.** The same two names under `/downloads/` with the
  stamp left off still answer HTTP 200 with a 2024 release. The fetcher never
  constructs a URL: every data URL is the href the `/data/` page served, and the
  workbook URL is the href `/data/data-documentation/` served.
- **The AppleDouble entry.** The institution archive carries an
  `__MACOSX/._Most-Recent-Cohorts-Institution.csv` resource fork whose name ends
  `.csv`; the fetcher drops it and resolves the member by exact name.
- **The raw-data archive** (`College_Scorecard_Raw_Data_<stamp>.zip`, ~450 MB)
  is a superset holding byte-identical copies of the two members and is not
  downloaded. It has unpacked to a top directory stamped with a different date
  from its URL, so anything that reaches for it must resolve that directory by
  pattern.
- **The host moved.** Downloads are on `ed-public-download.scorecard.network`;
  the `ed-public-download.app.cloud.gov` older prose in this tree names 404s.

## Regenerating

```sh
nix develop -c bin/fetch-scorecard
```

To replay the recorded fixtures instead, with no network at all, give the replay
its own output directory (`-F` requires an explicit `-o`, and a replay is
refused against this one):

```sh
nix develop -c bin/fetch-scorecard -F college/src/test/resources -o <scratch-dir>
```
