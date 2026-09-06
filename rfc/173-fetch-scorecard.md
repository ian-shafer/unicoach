# RFC 173: a fetcher for the College Scorecard pair

RFC 172 landed one command that reloads the external data, and it deliberately
stopped one source short: the two College Scorecard CSVs stayed "downloaded by
hand from collegescorecard.ed.gov", and the socket for a fourth fetcher was
reserved rather than filled ("a fourth fetcher plugs into `fetch-external-data`
as a fourth selector flag and changes nothing else", RFC 172 §6).

This RFC fills it. `bin/fetch-scorecard` downloads the institution-level and
field-of-study releases, extracts the two CSVs `bin/ingest-colleges` takes as
its positionals, and records `db/seed/scorecard/PROVENANCE.json`.
`bin/fetch-external-data` gains `-r` as its fourth source.

It reverses RFC 172's "no fetcher" decision on the evidence that made that
decision look safe and is in fact the reason it was not: the stable-looking
unversioned download URLs still return HTTP 200 and have not moved since August
2024. A hand download is not a pinned download; it is an unrecorded one.

## What is actually published

Verified live (see the run's research notes for the raw transcript):

- the index page `https://collegescorecard.ed.gov/data/` server-renders the
  download hrefs, currently stamped `06102026`;
- `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Institution_06102026.zip`
  — 23,559,465 bytes, one data member `Most-Recent-Cohorts-Institution.csv`
  (100,102,932 bytes, 3,308 columns, 6,273 data rows) plus an `__MACOSX/._*`
  AppleDouble entry;
- `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Field-of-Study_06102026.zip`
  — 17,188,590 bytes, one member `Most-Recent-Cohorts-Field-of-Study.csv`
  (153,479,459 bytes, 178 columns, 227,980 data rows);
- the 448 MB `College_Scorecard_Raw_Data_*.zip` is a strict superset holding
  byte-identical copies of those same two members;
- no API key and no auth on any of them. (The `api.data.gov` API does need a
  key, and would need 63 paged requests for what one zip gives.)

Three facts drive the design below.

**The host moved.** `ed-public-download.app.cloud.gov`, which older prose in
this tree still names, now 404s on everything including `/`. The live host is
`ed-public-download.scorecard.network`.

**The unversioned names are a trap.** `Most-Recent-Cohorts-Institution.zip` (no
stamp) returns 200 and is `Last-Modified: 2024-08-22`. A fetcher pointed at it
would ingest the August-2024 release forever, with a green exit and a passing
header assertion — the exact failure shape this repo's fetchers exist to refuse.
`/downloads/` is itself a 404, so there is no directory listing: the stamp has
to be read off the `/data/` page.

**The archive's internal folder date disagrees with its URL date**
(`College_Scorecard_Raw_Data_06102026.zip` unpacks to
`College_Scorecard_Raw_Data_06032026/`). Nothing here reads the big archive, but
the RFC records it so a future variant never reconstructs a member path from a
URL stamp.

## Detailed Design

`bin/fetch-scorecard` is a stdlib-only python3 script in the shape
`bin/fetch-ipeds` established, importing `bin/pyfunctions.py` for the exit band,
the `log_*` family, `parse_short_options` and `write_atomically`, and wrapping
`fatal` so every refusal names the script.

### Grammar

    fetch-scorecard [-o <outdir>] [-F <fixtures-dir>] [-h]

`GETOPT_SPEC = "o:F:h"`, identical to `bin/fetch-ipeds`, with its two
post-getopt guards: `-F` REQUIRES an explicit `-o` (exit 11), and an `-o` naming
something that exists and is not a directory is exit 22. Default `-o` is
`db/seed/scorecard` under the repo root. No `-K`: nothing here is authenticated.

### Release discovery

The date stamp is DISCOVERED, not pinned, and this is the one place this fetcher
departs from `bin/fetch-ipeds`, which pins `SURVEY_YEAR = 2023` and its archive
names as literals.

The reason is that the two publishers publish differently. IPEDS publishes a
named survey year that stays fetchable, so a year bump is a reviewed edit of the
script. The Scorecard publishes exactly one "most recent cohorts" release, whose
predecessor's stamped URL goes away; pinning the stamp would mean the fetcher
starts 404ing on the publisher's schedule, and the only stable-looking
alternative is the stale unversioned URL.

So: GET `https://collegescorecard.ed.gov/data/`, match the hrefs against

    https://ed-public-download\.scorecard\.network/downloads/(<url_name>|<url_name>)_(\d{8})\.zip(?=["'\s<])

require EXACTLY ONE match for each artifact, and require the two stamps to be
EQUAL — the pair must come from one release, because the ingest cross-reads them
by UNITID. Zero matches, several different matches, or two disagreeing stamps
are FATAL: that is a publisher change a human looks at, exactly the stance
`bin/fetch-ipeds` takes on a member it cannot resolve. The unversioned URL is
never constructed.

The artifact alternation is BUILT from the `ARTIFACTS` table
(`"|".join(re.escape(artifact.url_name) ...)`), so each published name is
spelled once and the captured group IS the key the rest of the script looks an
artifact up by. The lookahead is the href's right edge: without it the match is
a prefix of a longer token — `..._06102026.zip?ver=2` would yield
`..._06102026.zip` — and that is a URL the script ASSEMBLED by truncation, not
one the page served. A signed link, a query string or a `.zip.html` landing page
is a publisher change a human reads, exactly like a rename.

A page that offers no download for an artifact, or several, is refused ONCE with
both artifacts' verdicts (a moved host renames both hrefs), and the refusal
quotes the page: its length, what it DID offer, and a bounded one-line preview.
That is what separates "one artifact renamed beside a matched sibling" from
"this is not the /data/ page at all" — a gzipped body, a captive portal or an
empty 200 — which are otherwise byte-identical refusals. The page is decoded
`errors="replace"` and a body carrying replacement characters says so on the
warning channel.

The discovered stamp is recorded as a first-class top-level `release` field in
the manifest, and a run whose discovered stamp differs from the recorded one
LOGS the change and proceeds. A new release is the whole point of the fetcher,
so this is reported, never refused — unlike the member-drift guard, which does
refuse.

### Extraction

One `Artifact` NamedTuple row per file — `url_name`, `member_name`,
`fixture_name`, `ingest_position` (`1`/`2`, the positional it becomes), and its
identity columns — because `Most-Recent-Cohorts-Institution` appears as three
look-alike strings and a positional unpacking that swapped two of them would
still run.

The member is resolved by EXACT name after dropping `__MACOSX/` entries and
AppleDouble `._*` members. An archive missing its expected member is FATAL; the
lone surviving member is never taken as a substitute, which is the same rule
`bin/fetch-ipeds` states, and here the AppleDouble entry is precisely the
wrong-member hazard. Members are decoded `utf-8-sig` and re-encoded UTF-8, so no
BOM survives into `UNITID`.

A network body that is not a zip is FATAL (an error page served as a success). A
fixture body that is not a zip is accepted as an already-extracted CSV, which is
what lets `-F` replay a small recorded CSV.

### Guards

The `bin/fetch-ipeds` suite, unchanged in structure and in order:

1. `read_manifest` with one corruption policy — absence decided by
   `FileNotFoundError`; a file that is not UTF-8 text, a non-object parse
   (including literal `null`), and a manifest whose `source` and
   `fixture_derived` DISAGREE are all damage and are fatal. The two route fields
   are written from one flag, so a record that states its route twice and
   contradicts itself states no route at all, and the reading that leaves a
   committed record replaceable is the one it may not reach. An absent
   `artifacts` key is damage too, never an empty baseline;
2. `require_offline_outdir` — a `-F` replay against a manifest that is not
   itself a replay is refused, `unknown` protected exactly as `network` is;
3. `require_matching_route` — a network fetch against a manifest recording a
   `-F` replay is refused before a byte is fetched;
4. `require_data_rows` — an artifact that produced zero data rows is refused
   with or without a baseline, distinguishing "zero bytes" from "header, no data
   rows";
5. `armed_guards` — one `log_error` per guard that is not armed, naming the
   artifact and the recorded value that disarmed it;
6. `require_stable_baseline` — member drift and row shrink
   (`SEED_SHRINK_FLOOR = 0.5`), both run, both verdicts joined into one refusal;
7. `write_atomically` over the two CSVs AND the manifest as one set.

Plus one guard this fetcher adds: an **identity-column** check. Each artifact
declares a SMALL set of columns that say "this is the file we think it is" —
institution: `UNITID`, `INSTNM`, `CONTROL`, `PREDDEG`; field of study: `UNITID`,
`CIPCODE`, `CREDLEV` — and a header missing any of them is fatal, quoting a
bounded prefix of the header in PUBLISHED order — the member is never written,
so the refusal carries the only surviving copy of the value that failed. Each
set carries at least one column the OTHER file does not, and that is what makes
the check see a swap in EITHER direction: the published field-of-study header
carries `UNITID`, `INSTNM` and `CONTROL` too (they are 3 of the 5 columns the
two files share), so those three alone would accept a field-of-study member in
the institution slot. `PREDDEG` is institution-only, as `CIPCODE` and `CREDLEV`
are field-of-study-only.

It is deliberately NOT a copy of the loaders' `REQUIRED_INSTITUTION_COLUMNS` (39
names) and `REQUIRED_FIELDS_COLUMNS`. That list is the INGEST's contract, it is
asserted there before any write (RFC 139), and duplicating it in python would be
a second copy that drifts silently the day someone adds a column to the Kotlin
list. This check answers a different question — did the extraction produce the
institution file or the field-of-study file — which is a fact about the fetch
and belongs here. No column COUNT is asserted: 3,308 and 178 are today's numbers
and the publisher adds columns.

### The manifest

`db/seed/scorecard/PROVENANCE.json`, in the `bin/fetch-ipeds` shape:

    {
      "fetched_at": "<ISO-8601 UTC seconds>" | null,
      "source": "network" | "fixtures",
      "fixture_derived": false | true,
      "release": "06102026" | null,
      "artifacts": {
        "Most-Recent-Cohorts-Institution": {
          "url": ..., "fetched_at": ..., "bytes": ..., "sha256": ...,
          "member": ..., "member_bytes": ..., "member_sha256": ...,
          "rows": ..., "ingest_position": 1
        },
        "Most-Recent-Cohorts-Field-of-Study": { ... "ingest_position": 2 }
      }
    }

with the `fetched_at` contract this tree already states: a network run writes
ISO-8601 UTC to seconds, a `-F` replay writes `null` and `null` means exactly
"offline replay", and ONE caller-supplied timestamp is stamped at both levels.
One input decides `source`, `fixture_derived` and every `fetched_at`, so no
caller can assemble a manifest claiming a fetch that did not happen. `release`
is `null` on a replay for the same reason.

`ingest_position` is a declaration, serialised, for the same reason
`bin/fetch-ipeds` serialises `ingest_option`: a later reader that wants to build
the load command must not re-derive it from a filename.

### What lands in git

The extracted CSVs are 100 MB and 153 MB — worse than IPEDS' 86 MB against a 16
MB `.git` — so `db/seed/scorecard/` is gitignored except `PROVENANCE.json` and
`FETCH-NOTES.md`, in the same stanza shape `.gitignore` already uses for
`db/seed/ipeds/`. The manifest is both the committed record of the real fetch
and the baseline the shrink guard reads next time; `FETCH-NOTES.md` records how
it was made and the exact command that regenerates it.

### Wiring

`bin/fetch-external-data` gains `-r` (sco**r**ecard), a `FETCH_SCORECARD` flag,
a `build_scorecard_command` builder writing `<root>/scorecard`, a `run_fetcher`
case arm, and its place in the all-sources default and the ordering block. `-r`
is free in `fetch-external-data`, `reload-external-data`, `load-external-data`,
`ingest-colleges` and `read-ipeds-manifest`; `-s` is the CDS seed here and
`subjects.json` in `ingest-colleges`, and `-S` is the SFA CSV there, so both
obvious letters are refused for the reason RFC 172 §1 gives for `-s`-not-`-d`.

Scorecard runs FIRST in the ordering block, because `bin/load-external-data`
consumes it first (it is the pair of positionals).

`bin/reload-external-data` forwards `-r` the same way it forwards `-c`, `-i` and
`-s`.

### What this RFC does NOT do

It does not change how the ingest is invoked. `bin/load-external-data` and
`bin/reload-external-data` keep REQUIRING the two Scorecard positionals; only
the prose that says there is no fetcher changes, to name `bin/fetch-scorecard`
and the files it writes. Teaching those wrappers to default the positionals out
of `db/seed/scorecard/PROVENANCE.json` — the way they already default the IPEDS
paths out of the IPEDS manifest via `bin/read-ipeds-manifest` — is a real and
obvious follow-on, and it is a separate change with its own blast radius across
two wrappers, a manifest reader and the RFC 172 wrapper test block. The manifest
shape above is designed so that change needs no re-fetch.

It does not pin `CollegeScorecardDataDictionary.xlsx`. `bin/fetch-ipeds` pins
the SFA dictionary because the ingest's flag mapping is transcribed from it and
the citation has to be checkable; nothing in this tree transcribes the Scorecard
dictionary, so pinning it would be 727 KB of unused provenance.

It does not touch the API. It does not download the 448 MB raw archive.

## Files Modified

New:

- `bin/fetch-scorecard` — the fetcher (executable, python3).
- `db/seed/scorecard/PROVENANCE.json` — the committed record of the real fetch.
- `db/seed/scorecard/FETCH-NOTES.md` — how that manifest was made, and the exact
  regeneration command.
- `college/src/test/resources/scorecard-institution-zip-fixture.zip` and
  `college/src/test/resources/scorecard-field-of-study-zip-fixture.zip` — the
  recorded offline fixtures, small zips carrying a header and a handful of rows
  under the real member names.
- `college/src/test/resources/scorecard-data-page-fixture.html` — the recorded
  `/data/` page the release discovery parses offline.

Modified:

- `bin/fetch-external-data` — `-r`, the flag, the builder, the case arm, the
  all-sources default, the ordering block, the help.
- `bin/reload-external-data` — `-r` forwarded.
- `bin/load-external-data` — the help sentence that says there is no fetcher.
- `bin/pyfunctions.py` — the download stack (`format_bytes`, `content_length`,
  `DownloadProgress`, `read_body_with_progress`, `http_get` and their chunk and
  progress constants) and the guarded `decode` are lifted here from the
  fetchers, each taking the calling script's name, so there is one copy rather
  than one per fetcher. Being the one copy is also why it is hardened here: a
  body shorter than the declared `Content-Length` is raised as the transient
  fault `http_get` retries (a bounded `read(amt)` returns short rather than
  raising, so a dropped connection was returning a truncated artifact as a
  success), the transport families behind `IncompleteRead` are caught rather
  than escaping as stdlib tracebacks, the HTTP error-body preview read is
  best-effort, an unusable `Content-Length` is reported instead of discarded,
  and `write_atomically` names the files it had already renamed when a fault
  hits the rename loop — that directory is MIXED, and the old message denied it.
- `bin/fetch-ipeds` — its copies of that download stack and of `decode` deleted
  in favour of the shared ones, plus the fetcher-count prose that a fourth
  fetcher falsified. Its manifest loader gains the same three refusals as
  `bin/fetch-scorecard`'s (non-UTF-8, contradictory route fields, absent
  `artifacts`), its CSV refusal gains the line number, and its `-F` guard
  distinguishes an existing FILE from an absent path: the two fetchers keep one
  manifest policy, so a fix to one that skipped the other would be no fix.
- `bin/fetch-codebooks` — its own unguarded `decode`, which could raise out of
  its cp1252 recovery path, replaced by the shared guarded one, plus the same
  count prose and the same `-F` file-versus-absent wording.
- `bin/fetch-cds-seed` — the same count prose.
- `bin/scripts-tests` — a new RFC 173 section, plus the RFC 172 wrapper tests
  that count sources (three becomes four) and iterate the selectors.
- `.gitignore` — the `db/seed/scorecard/` stanza.
- `README.md` — the "download them by hand" sentence.
- `rfc/173-fetch-scorecard.md` — this file.

## Implementation Plan

1. `bin/fetch-scorecard`, written against `bin/fetch-ipeds` as the template:
   docstring/help, pyfunctions bootstrap, `fatal` wrapper, `ARTIFACTS`, release
   discovery, HTTP layer with chunked progress, member resolution, row counting,
   the guard suite, the manifest builder, the summary, and the paste-ready
   `bin/load-external-data` command at the end.
2. The fixtures: two small zips built from the real headers plus a few rows, and
   the recorded `/data/` page trimmed to the hrefs it must parse.
3. `.gitignore` stanza and the `db/seed/scorecard/` directory.
4. One REAL network run to produce the committed `PROVENANCE.json`, and
   `FETCH-NOTES.md` written from it.
5. `bin/fetch-external-data` and `bin/reload-external-data` wiring; the
   `bin/load-external-data` and `README.md` prose.
6. `bin/scripts-tests`: the new section and the RFC 172 wrapper edits.

## Tests

All in `bin/scripts-tests` (there is no JVM code in this change), following the
category list the IPEDS section already covers:

- the script parses as python3;
- an offline `-F` run succeeds and writes both CSVs under their declared names
  plus `PROVENANCE.json`, and nothing else;
- the manifest is asserted as JSON, not grepped: route `fixtures`,
  `fixture_derived` true, `release` null, both-level `fetched_at` identity,
  per-artifact row counts and `ingest_position`;
- release discovery: a page yielding no match is fatal; a page yielding two
  different stamps for the pair is fatal; a page yielding several matches for
  one artifact is fatal;
- the unversioned URL is never constructed (asserted against the script source;
  against the stamped URLs the COMMITTED manifest records -- a replay records
  `url: null`, so it carries no URL to check; and positively against the
  recorded `/data/` page, where every URL discovery yields carries an 8-digit
  stamp);
- member resolution: an archive whose only remaining member is the `__MACOSX/`
  AppleDouble entry is refused, not substituted;
- a network body that is not a zip is fatal and names the URL, while the same
  body on the `-F` route is still read as a recorded CSV;
- an archive that will not open (a damaged member, the shape a truncated
  download leaves) is refused by name on the `[FATAL]` channel rather than as a
  traceback, and nothing is written;
- the identity-column guard: a header missing `CIPCODE` is refused and nothing
  is written;
- absolute refusal: a header-only member is refused, distinctly from a zero-byte
  one, and neither writes;
- shrink guard fires and leaves the manifest untouched;
- member-drift guard fires at an unchanged row count;
- route refusals both ways, with `source: unknown` protected as `network` is;
- a first-generation run says it is UNGUARDED; an unarmed guard names the
  offending recorded value;
- a discovered release that differs from the recorded one is LOGGED and the run
  proceeds -- never refused -- and an unchanged one says so instead;
- corrupt-manifest policy: `null`, a list, and a bad `artifacts` value;
- the COMMITTED manifest is checked: real network route, ISO date, the published
  member names, the recorded digests;
- the gitignore assertion: the CSVs are ignored, the manifest and notes are not;
- usage errors 10/11/20/22 including a repeated option and a dropped operand;
- a successful run writes NOTHING to stdout; `-h` prints the help to stdout and
  exits 0 with an empty stderr;
- `bin/fetch-external-data`: `-r` runs only the scorecard fetcher; the
  no-selector run now runs four; the best-effort tally reads
  `[3] of [4] sources fetched; [1] failed`; `-o` puts scorecard output under
  `<root>/scorecard`; stdout stays empty;
- `bin/reload-external-data`: `-r` is accepted as a selector and forwarded to
  the fetch half alongside the other selectors and `-K`.

The full `nix develop -c bin/test` run is the gate, and `bin/shell-tests` is run
directly while iterating, since `bin/scripts-tests` is where every assertion in
this change lives.
