# db/seed/ipeds — how this manifest was made

`PROVENANCE.json` here is the committed record of a real IPEDS download, and the
baseline `bin/fetch-ipeds` measures every later fetch against. The archives and
the extracted CSVs themselves are **not** committed (see the size note in
`bin/fetch-ipeds`), so this file and that manifest are the whole record.

## The fetch

`PROVENANCE.json` was written by `bin/fetch-ipeds` itself, on a real network run
on **2026-09-05** (`fetched_at` `"2026-09-05T04:31:58+00:00"`, at the seconds
precision a network run records). It downloaded the five survey data archives
from `https://nces.ed.gov/ipeds/datacenter/data/`:

- `HD2023.zip`
- `IC2023.zip`
- `ADM2023.zip`
- `C2023_A.zip`
- `IC2023_AY.zip` (added by RFC 161)

The first four are unchanged from the **2026-09-01** run this file used to
record, which was itself confirmed against an operator fetch on **2026-08-31**:
all three fetches agree on every digest, member name and row count. The
`IC2023_AY.zip` figures (309,736 bytes, sha256 `42d3ee39…d69ee`, member
`ic2023_ay.csv`, 3,825 data rows) were independently confirmed against a
separate operator download during the RFC 161 design run. The digests are also
pinned a second time in `bin/scripts-tests`, so a hand edit of this directory
fails the shell harness.

`IC2023_AY_RV.zip` is an HTTP **404** today — `IC2023_AY.zip` is already the
final release. The `_RV` refusal in `bin/fetch-ipeds` therefore protects a case
that does not exist yet, which is exactly why it must stay a fatal rather than
become a fallback.

Note that `ic2023_ay.csv` covers **3,825** institutions, not the ~6,100 in
`HD2023.csv`: institutions that charge on a program-year calendar report into
`IC_PY`, a different survey file this repo does not ingest. The smaller row
count is coverage, not data loss.

`fetched_at` is deliberately never `null` here: `null` is the script's marker
for an offline (`-F`) replay, and a manifest that records a real fetch must
never look like one.

## The member figures

`member`, `member_bytes`, `member_sha256` and `rows` come from re-extracting
those same verified archives with `bin/fetch-ipeds`, not from hand counting.
`rows` is what the shrink guard compares against, and `member` is what the
member-drift guard compares against.

## Regenerating

Re-run the fetcher against the five archives and confirm the member figures are
unchanged:

```sh
nix develop -c bin/fetch-ipeds -F <dir-holding-the-five-zips> -o <scratch-dir>
```

A run with no `-F` downloads them again and rewrites this directory in place.
