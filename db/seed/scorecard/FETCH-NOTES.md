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
