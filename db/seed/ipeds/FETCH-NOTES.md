# db/seed/ipeds — how this manifest was made

`PROVENANCE.json` here is the committed record of a real IPEDS download, and the
baseline `bin/fetch-ipeds` measures every later fetch against. The archives and
the extracted CSVs themselves are **not** committed (see the size note in
`bin/fetch-ipeds`), so this file and that manifest are the whole record.

## The fetch

`PROVENANCE.json` was written by `bin/fetch-ipeds` itself, on a real network run
on **2026-09-01** (`fetched_at` `"2026-09-01T13:44:20+00:00"`, at the seconds
precision a network run records). It downloaded the four survey data archives
from `https://nces.ed.gov/ipeds/datacenter/data/`:

- `HD2023.zip`
- `IC2023.zip`
- `ADM2023.zip`
- `C2023_A.zip`

Every archive byte count and sha256 digest in that record was independently
confirmed against an earlier operator fetch of the same four archives on
**2026-08-31**: the two fetches agree on every digest, member name and row
count. The digests are also pinned a second time in `bin/scripts-tests`, so a
hand edit of this directory fails the shell harness.

`fetched_at` is deliberately never `null` here: `null` is the script's marker
for an offline (`-F`) replay, and a manifest that records a real fetch must
never look like one.

## The member figures

`member`, `member_bytes`, `member_sha256` and `rows` come from re-extracting
those same verified archives with `bin/fetch-ipeds`, not from hand counting.
`rows` is what the shrink guard compares against, and `member` is what the
member-drift guard compares against.

## Regenerating

Re-run the fetcher against the four archives and confirm the member figures are
unchanged:

```sh
nix develop -c bin/fetch-ipeds -F <dir-holding-the-four-zips> -o <scratch-dir>
```

A run with no `-F` downloads them again and rewrites this directory in place.
