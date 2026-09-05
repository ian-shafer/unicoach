# db/seed/ipeds — how this manifest was made

`PROVENANCE.json` here is the committed record of a real IPEDS download, and the
baseline `bin/fetch-ipeds` measures every later fetch against. The archives and
the extracted CSVs themselves are **not** committed (see the size note in
`bin/fetch-ipeds`), so this file and that manifest are the whole record.

## The fetch

`PROVENANCE.json` was written by `bin/fetch-ipeds` itself, on a real network run
on **2026-09-05**, at the seconds precision a network run records. It downloaded
the seven survey data archives from
`https://nces.ed.gov/ipeds/datacenter/data/`:

- `HD2023.zip`
- `IC2023.zip`
- `ADM2023.zip`
- `C2023_A.zip`
- `IC2023_AY.zip` (added by RFC 161)
- `SFA2223.zip` — the Student Financial Aid survey, **aid year 2022-23** (RFC
  162). A different year family from the five above, which are the 2023
  COLLECTION year; `bin/fetch-ipeds` checks each artifact against its own
  family's constant.
- `SFA2223_Dict.zip` — the SFA data dictionary. Pinned for its digest and its
  citation, not for ingest: its `sfa2223.xlsx` member's "imputation values"
  sheet is the published list of the thirteen `X` imputation codes that
  `IpedsImputationFlag` transcribes. It is the one artifact with no data-row
  count (it is not a CSV) and no `bin/ingest-colleges` option.

The manifest is a COMPOSITE of two real network runs the same morning, and it
says so per artifact rather than in prose: the five collection-year archives
carry `fetched_at` `"2026-09-05T04:31:58+00:00"` (the RFC 161 run) and the two
SFA archives carry `"2026-09-05T04:50:26+00:00"` (the RFC 162 run). The
top-level `fetched_at` is the later of the two. Nothing was hand-edited except
the merge of the two artifact maps; every digest, member name and row count
below is what `bin/fetch-ipeds` itself wrote.

The first four archives are unchanged from the **2026-09-01** run this file used
to record, which was itself confirmed against an operator fetch on
**2026-08-31**: all three fetches agree on every digest, member name and row
count. The `IC2023_AY.zip` figures (309,736 bytes, sha256 `42d3ee39…d69ee`,
member `ic2023_ay.csv`, 3,825 data rows) were independently confirmed against a
separate operator download during the RFC 161 design run, and the two SFA
archives' digests (`SFA2223.zip` 1,913,114 B `144f97dc…`, `SFA2223_Dict.zip`
79,497 B `466b5e12…`) reproduce the ones an earlier research download of the
same two archives recorded. The digests are also pinned a second time in
`bin/scripts-tests`, so a hand edit of this directory fails the shell harness.

`IC2023_AY_RV.zip` is an HTTP **404** today — `IC2023_AY.zip` is already the
final release. The `_RV` refusal in `bin/fetch-ipeds` therefore protects a case
that does not exist yet, which is exactly why it must stay a fatal rather than
become a fallback.

Note that `ic2023_ay.csv` covers **3,825** institutions, not the ~6,100 in
`HD2023.csv`: institutions that charge on a program-year calendar report into
`IC_PY`, a different survey file this repo does not ingest. The smaller row
count is coverage, not data loss.

`SFA2223.zip` today holds only the provisional member `sfa2223.csv`. Its
artifact declares `prefer_revised` (RFC 162 D2), so when NCES republishes the
zip with an `sfa2223_rv.csv` beside it — as `SFA2122.zip` already carries — the
next fetch takes the REVISION, because for SFA the revision changes money (3,612
restated cells in the 2021-22 file) rather than layout. That fetch will fail the
member-drift guard first, which is the point: the change is reviewed, not
silent.

`fetched_at` is deliberately never `null` here: `null` is the script's marker
for an offline (`-F`) replay, and a manifest that records a real fetch must
never look like one.

## The member figures

`member`, `member_bytes`, `member_sha256` and `rows` come from re-extracting
those same verified archives with `bin/fetch-ipeds`, not from hand counting.
`rows` is what the shrink guard compares against, and `member` is what the
member-drift guard compares against.

## Regenerating

Re-run the fetcher against the archives and confirm the member figures are
unchanged:

```sh
nix develop -c bin/fetch-ipeds -F <dir-holding-the-zips> -o <scratch-dir>
```

A run with no `-F` downloads them again and rewrites this directory in place.
