# RFC 172 — One command to reload the external data

## Problem

unicoach ingests four external data sets: the College Scorecard institution and
field CSVs, the IPEDS survey files, the IPEDS published codebooks, and the
collegedata.fyi Common Data Set corpus. Refreshing them today is four scripts
run by hand in a remembered order, ending in a single `bin/ingest-colleges`
invocation carrying **fourteen flags plus three positional paths**:

```sh
nix develop -c bin/fetch-codebooks
nix develop -c bin/fetch-ipeds
nix develop -c bin/fetch-cds-seed
nix develop -c bin/ingest-colleges \
  -k db/data/codebooks.json -s db/data/subjects.json \
  -v db/data/money-vocabulary.json \
  -m db/seed/cds/merit-aid.csv -a db/seed/cds/admission-factors.csv \
  -d db/seed/cds/deadlines.csv \
  -H db/seed/ipeds/HD2023.csv -I db/seed/ipeds/IC2023.csv \
  -A db/seed/ipeds/adm2023.csv -C db/seed/ipeds/C2023_a.csv \
  -Y db/seed/ipeds/ic2023_ay.csv -y 2023 \
  -S db/seed/ipeds/sfa2223.csv -f 2022 \
  institution.csv fields.csv db/data/college-aliases.json
```

Nothing in the tree writes that command down. `bin/ingest-colleges`' own help
warns twice about the `-a`/`-A` and `-y`/`-Y` case pairs, because getopts is
case-sensitive and a mistyped case is a different option rather than a synonym —
`-A` given the CDS admission-factors CSV is a header assertion away from a
silent wrong load. The IPEDS filenames are year-stamped, so the command changes
shape every survey year. And the flags are two all-or-nothing groups: give one
of `-m/-a/-d` or one of `-H/-I/-A/-C/-Y/-y` and you must give all of that group,
so a partially remembered command is refused rather than degraded — which is
right, and also means the operator gets no useful middle ground.

The failure this invites is not a crash. It is a green run that loaded less than
the operator believed it loaded.

RFC 67 anticipated this and deferred it: "a future refresh RFC adds a
download+cadence wrapper" (rfc/67-college-knowledge.md:197-206). This is that
RFC, minus cadence — scheduling is not proposed here.

## Summary of the decision

Three new scripts in `bin/`, each of which is exactly what its name says:

| Script                     | Does                                                       |
| -------------------------- | ---------------------------------------------------------- |
| `bin/fetch-external-data`  | runs the three fetchers                                    |
| `bin/load-external-data`   | runs `bin/ingest-colleges` once, with every path defaulted |
| `bin/reload-external-data` | runs the two above, in that order                          |

`reload-external-data` holds no logic of its own beyond the ordering rule. It is
a composition, and the two halves stay independently runnable — refetching
without reloading and reloading without refetching are both ordinary operations,
not degraded modes.

Three smaller decisions fall out and are specified below: what "all" means when
one source fails (§2), where the IPEDS filenames and survey year come from (§3),
and a precondition — two of the three existing fetchers currently breach the
`bin/` conventions in a way the wrapper cannot paper over (§5).

## What this RFC does not do

**It does not fetch the College Scorecard.** `institution.csv` and `fields.csv`
have no fetcher, and this RFC does not add one. They stay a required positional
argument of `load-external-data`, downloaded by hand from
collegescorecard.ed.gov, exactly as today. Scorecard is consequently the only
external input with no committed `PROVENANCE.json` — no recorded URL, digest or
fetch time — which is a real gap, but it is a _different_ gap: writing that
fetcher means pinning a ~143 MB artifact and deciding what is committed, and
that decision deserves its own RFC rather than a subsection of this one. The
design below leaves the socket for it: a fourth fetcher plugs into
`fetch-external-data` as a fourth selector flag and changes nothing else.

Naming the scripts "external-data" rather than "all" is deliberate for the same
reason. A name that promises _all_ would be false the moment you notice
Scorecard is not in it.

**It does not make these runnable in production.** See §6.

**It does not schedule anything.** No cron, no periodic task, no cadence.

## Detailed Design

### 1. `bin/fetch-external-data`

```
fetch-external-data [-c] [-i] [-s] [-F <fixtures-dir> -o <outdir>] [-h]

  -c  fetch the published codebooks   (bin/fetch-codebooks)
  -i  fetch the IPEDS survey files    (bin/fetch-ipeds)
  -s  fetch the CDS seed              (bin/fetch-cds-seed)
  -F  offline fixture replay, forwarded to each selected fetcher
  -o  output root, REQUIRED with -F
  -h  help
```

Selecting no source runs all three. The selectors exist so that one failed or
one changed source can be re-fetched without re-downloading the other two, and
because a run of the whole set is a wide diff to review.

`-s` rather than `-d` for the CDS seed: `-d` is `deadlines` in
`ingest-colleges`, and these two scripts sit next to each other in every
runbook. Reusing a letter across them for a different meaning is the same hazard
as the `-a`/`-A` pair, one script further out.

`-F` is forwarded to each fetcher's own offline mode and **requires `-o`**,
mirroring the rule `bin/fetch-ipeds` already enforces. This is what lets
`bin/scripts-tests` exercise the orchestrator with no network and no writes
under `db/` (§7); it is not a production path.

`-o` is an output **root**, and the wrapper fans it out into `<root>/codebooks`,
`<root>/ipeds` and `<root>/cds`. It has to: all three fetchers write a file
called `PROVENANCE.json`, so one shared directory would have them silently
clobber each other's manifest. The wrapper does not create those directories:
each fetcher already creates its own, and a `mkdir` here would abort the whole
best-effort run under `set -euo pipefail` before any source was fetched, and
would hide the fetcher's own `-o` usage error — which §2 exists precisely to put
in the tally.

`bin/fetch-codebooks` also learns to honour `-o` completely: given `-o` and no
`-j`, its generated codebook goes to `<outdir>/codebooks.json` rather than the
**committed** `db/data/codebooks.json`, so an offline run cannot rewrite a
reviewed file in the working tree. An option that redirects only some of a
script's output is a half-honoured option, and that is better fixed in the
fetcher than compensated for by every caller. `-o` is accepted without `-F`; it
is `-F` that requires `-o`, not the reverse.

`-K` is forwarded to `bin/fetch-cds-seed`, and both wrappers name
`$COLLEGEDATA_ANON_KEY` in their help. A credential that can reach the fetcher
only through inherited environment, unmentioned by the tool you actually ran, is
a credential nobody can see.

**Ordering.** Codebooks, then IPEDS, then CDS. The three fetches are genuinely
independent — no fetcher reads another's output — so this order is a
presentation choice, not a constraint: it is the order the _load_ consumes them
in (§4), so a reader who learns one order has learned both.

**stdout.** Nothing. The product of this script is files on disk plus a
diagnostic summary, and per the `bin/` logging rule the summary is a diagnostic.
`-h` is the one thing it may write to stdout. This is only achievable given §5.

### 2. One failed source does not cancel the other two

`fetch-external-data` is **best-effort with explicit reporting**, the sanctioned
context from `recipes/BEST_EFFORT_BATCH_INGEST.md`. It runs every selected
fetcher even after one fails, and ends with a per-source line on stderr:

```
fetch-external-data: codebooks   ok
fetch-external-data: ipeds       ok
fetch-external-data: cds         FAILED  (exit 1)
fetch-external-data: 2 of 3 sources fetched; 1 failed
```

That claim is only worth making because it is now true of all three. It was not:
only `bin/fetch-ipeds` staged its outputs and renamed them into place;
`fetch-codebooks` and `fetch-cds-seed` wrote file by file, so a fault mid-write
left new data sitting under a stale `PROVENANCE.json` — over **committed,
reviewed** files. The staged-write helper therefore moves into
`bin/pyfunctions.py` (§5) and all three use it. Weakening the sentence would
have been the cheaper repair and the wrong one: the composition above is only
sound if each source's outputs move as one set.

The forbidden third context is _partial processing_ — halting mid-way and
leaving the rest unattempted with no account of it. Stopping at the first
failure is exactly that: a collegedata.fyi outage would silently cancel an IPEDS
refresh that would have succeeded, and the operator learns this only by reading
scrollback. Each fetcher is already internally all-or-nothing over its own
outputs, so a failed source leaves _its_ files as they were; there is no
half-written source to be inconsistent about.

**Exit code.** `1` if any selected source failed — a run that faulted. The
wrapper does **not** forward a child's exit code. The reserved usage codes
(`EXIT_UNKNOWN_OPTION` 10, `EXIT_OPTION_REQUIRES_VALUE` 11,
`EXIT_UNEXPECTED_ARG` 20, `EXIT_INVALID_ARG_VALUE` 22) describe _this_ script's
own grammar; forwarding a child's `10` would tell the caller they invoked
`fetch-external-data` wrongly when they did not. The failing source is named in
the summary, which is where that information belongs.

### 3. The IPEDS filenames and the survey year are read, not typed

`load-external-data` takes no `-H/-I/-A/-C/-Y/-y` and no `-S/-f`. It reads
`db/seed/ipeds/PROVENANCE.json` — the committed manifest `bin/fetch-ipeds`
writes — and derives the six CSV paths and **both** years from it: the IPEDS
collection year (`-y`, 2023) and the SFA aid-year start (`-f`, 2022), which RFC
162 was careful to keep as two different numbers because SFA covers the aid year
before the collection that publishes it.

This is not the guessing that `bin/ingest-colleges` forbids. That rule says the
year is never inferred _from a filename_; here it comes from the manifest the
fetcher recorded, which is the same class of evidence as an operator's memory
and strictly better attested. The six CSV names likewise come from each
artifact's recorded `member` field, not from a pattern over what happens to be
on disk. The result is that the maximal command's most error-prone half — six
case-sensitive flags over year-stamped filenames — stops being something a human
types at all, and a new survey year needs no runbook edit.

Preconditions, each fatal with `EXIT_MISSING_REQUIRED_ARG` (21) and each naming
both the missing thing and the command that produces it:

- `db/seed/ipeds/PROVENANCE.json` absent → run `bin/fetch-ipeds`.
- any of the six CSVs named by the manifest absent → run `bin/fetch-ipeds`. This
  is a live condition, not a hypothetical: the IPEDS CSVs are gitignored, so a
  fresh worktree has the manifest and **none** of the six, while the original
  checkout is currently missing only `ic2023_ay.csv`. Both cases are ordinary,
  which is why every missing input is collected and reported **together** rather
  than one refusal at a time — six sequential re-runs to learn six filenames is
  not a diagnosis.
- either year absent, per §3a.
- the manifest's artifacts do not map cleanly onto the ingest flags — a role
  claimed by two artifacts, or one claimed by none. A manifest that cannot say
  unambiguously which file is `-H` is not a manifest to guess at.
- any of the three CDS CSVs or four `db/data/*.json` files absent → name it.

Checking all of these _before_ invoking the JVM matters because the load is not
transactional across its phases: a failure mid-run leaves earlier phases
committed. A missing file discovered by phase 9 is a re-run; a missing file
discovered by argument validation is a corrected command.

#### 3a. The manifest must first record the years it already knows

The committed `db/seed/ipeds/PROVENANCE.json` records `fetched_at`, `source`,
and a per-artifact block of url, digests, member and row count — and **no year
anywhere**. `bin/fetch-ipeds` has every one of them (`SURVEY_YEAR`,
`SFA_AID_YEAR`, and a declared `year` on each of its seven `ARTIFACTS` rows,
which `require_survey_year` already checks against the archive name); its
manifest writer simply never serialises them. So the reader specified above
would refuse on today's committed manifest, every time.

The years are therefore unserialised, not unknown, and the fix belongs in the
writer rather than in a cleverer reader:

- `bin/fetch-ipeds` records a top-level `survey_year`, each artifact block
  records its own declared `year`, and each records its `ingest_option` — the
  `bin/ingest-colleges` flag that artifact is loaded with, or `null` for the SFA
  data dictionary, which is deliberately never ingested. Nothing else about the
  fetch, its guards or its digests changes.
- the committed `db/seed/ipeds/PROVENANCE.json` is **backfilled** with those
  same values and nothing else — every timestamp, url, byte count, digest and
  row count stays byte-identical. The values are not invented: they are what the
  same run already declared per artifact.
- `db/seed/ipeds/FETCH-NOTES.md` says so, because a field in a provenance
  manifest that had no fetch behind it must be legible as such to whoever next
  diffs the file.

**One recorded source per number, and no consensus rule.** `-y` comes from the
top-level `survey_year`; `-f` comes from the SFA artifact's own `year`; either
missing is fatal. An earlier draft of this RFC had the reader fall back to a
year that _all_ artifacts agreed on, which RFC 162 killed on both counts: the
key was never serialised, so the fallback was unreachable; and the seven
artifacts now deliberately **disagree**, five at 2023 and two at 2022, because
the SFA survey covers the prior aid year. A rule that reads "they all agree" is
exactly the rule that would have silently stamped 2023 onto `college_sfa`.

What the reader must never grow is a fallback that reads `2023` out of
`HD2023.csv`. That is the inference `bin/ingest-colleges` forbids, and it would
be indistinguishable, in a green run, from the recorded answer.

**An ingest flag with no slot in the wrapper refuses a real load.** It used to
warn and continue, which produced a green run that loaded less than the operator
believed — the failure this whole RFC exists to prevent, reintroduced in the
tool meant to prevent it. Under `-n` it stays a warning, restated immediately
above the printed command: printing a command is not loading, and refusing to
print is refusing to diagnose.

**The flag mapping is a declared fact too, not a pattern to re-derive.** An
earlier draft had `load-external-data` classify each archive with anchored
regexes over its name. That is a second typed copy of `Artifact.ingest_option`,
which `bin/fetch-ipeds` already owns and whose own comment forbids exactly that
— and the failure mode is silent: add a survey with a flag to `ARTIFACTS`, and
every load quietly omits it. So `ingest_option` is serialised beside the year,
and the reader reads it.

`SFA2223_Dict.zip` — the published data dictionary, pinned for its digest and
its citation and never ingested — then needs no special case at all: its
recorded `ingest_option` is `null`, and **a null option is the skip**, declared
by the fetcher rather than pattern-matched by the reader. The generic warning
survives for an artifact carrying a flag this wrapper does not know, which is a
different and genuinely surprising condition.

### 4. `bin/load-external-data`

```
load-external-data [-n] [-h] <institution.csv> <fields.csv> [aliases.json]

  -n  print the bin/ingest-colleges command to stdout and exit, running nothing
  -h  help
```

The two Scorecard positionals are required; `aliases.json` defaults to
`db/data/college-aliases.json`, as in `ingest-colleges`. All three may be
`s3://` URIs — the script does not resolve them, it forwards them, and
`resolve_file_arg` in `bin/functions` does the resolution as it does today.

Everything else defaults to its repo path: `db/data/codebooks.json`,
`db/data/subjects.json`, `db/data/money-vocabulary.json`, the three
`db/seed/cds/*.csv`, and the six IPEDS CSVs from §3. There is no flag to
override any of them. `bin/ingest-colleges` keeps every one of its flags and is
still the tool you reach for when a path is unusual; this wrapper's entire value
is that it has no configuration to get wrong.

After validation it does `exec` on `bin/ingest-colleges` with the assembled
argument vector, per the exec-passthrough discipline of RFC 80: exit code,
stdout and stderr pass through untouched, and there is no second process to
misreport the first.

One thing `-n` cannot show is where the data goes: the destination database is
inherited from the environment through `bin/common`, not named on the command
line. So a real run logs the resolved database and host:port on stderr before it
hands off, and the help says the target is inherited. Every input path being
explicit while the one output is implicit is the asymmetry worth closing.

`-n` writes the command it _would_ run to **stdout** — that is data the caller
explicitly asked for, the one sanctioned use of stdout here. It is what makes
the script testable with no database (§7), the body an operator **adapts** into
a `bin/remote` invocation (§6), and what makes the wrapper auditable rather than
opaque: you can always see the fourteen-flag command it built.

Adapts, not pastes. §6 is the reason: `db/data/` and `db/seed/` are never
bundled to the host, so every repo path in that line has to become a `-f N:` /
`@N` upload. The help says so rather than promising a paste that cannot work.

**The restart reminder.** `ingest-colleges` already warns that the served
vocabularies are read once at process start, so a load changes nothing a running
service says until `rest-server` and `queue-worker` restart.
`load-external-data` prints the two concrete commands (`bin/rest-server-bounce`,
`bin/queue-worker-bounce`) on stderr immediately **before** it hands off with
`exec` — after the handoff there is no wrapper left to print anything, which is
the price of exec-passthrough and worth paying. It does not run them. A
data-loading tool that restarts services on its own is a tool you cannot run
during business hours.

### 5. Precondition: two fetchers must first obey the `bin/` rules

`bin/fetch-codebooks` and `bin/fetch-cds-seed` currently breach both `bin/`
conventions:

- both parse arguments with a hand-rolled `while`/`case` loop
  (bin/fetch-codebooks:913-937, bin/fetch-cds-seed:1062-1088) rather than the
  stdlib `getopt` module that `bin/fetch-ipeds` uses correctly;
- `fetch-cds-seed` has a **long option**, `--anon-key`, which rule 1 forbids
  outright;
- both emit only `1`/`10`/`11`, so a stray positional exits `10`
  (unknown-option) instead of `20` (unexpected-arg);
- and both **print their closing summary to stdout** (bin/fetch-codebooks:959,
  bin/fetch-cds-seed:1103).

The last one is not cosmetic here. `fetch-external-data`'s stdout contract in §1
is unachievable while two of its three children write summaries to stdout, and
the alternative — the wrapper redirecting its children's stdout to stderr — is a
workaround that conceals the breach and makes the children's stdout permanently
unusable for real data. So this RFC fixes them at the source:

1. replace both hand-rolled loops with stdlib `getopt` plus the post-parse
   value-shape checks (empty value, value beginning with `-`, repeated flag)
   that `bin/fetch-ipeds` already models;
2. adopt the full `EXIT_*` set, including `20` for an unexpected positional;
3. move every summary line to stderr, leaving stdout empty on a normal run;
4. replace `--anon-key JWT` with **`-K JWT`**. The `$COLLEGEDATA_ANON_KEY`
   environment fallback and the scrape-the-API-page fallback are unchanged, and
   `PROVENANCE.json` keeps recording which route a run used. This is a breaking
   change to a hand-run operator tool with no callers in the tree; a long option
   that the conventions forbid is not worth an alias.

Doing that to two scripts leaves the third copy of the same guard suite sitting
in `bin/fetch-ipeds`, so the shared half moves to **`bin/pyfunctions.py`** — the
python counterpart to `bin/functions`, named for that precedent — holding the
`EXIT_*` band, the `log_*`/`fatal` helpers, `bracketed`, and
`parse_short_options`. Each fetcher keeps only its own `GETOPT_SPEC` and the
post-parse rules that are genuinely its own (`fetch-ipeds` keeps
`-F`-requires-`-o` and `-o`-must-be-a-directory). It is a module, not a command:
no `getopts`, no executable bit. Three hand-maintained copies of one parser is
not a style question — the copies here had already drifted apart, losing
`log_error` and the `-o` directory check on the way.

One deliberate exception to "no behaviour change": the staged-write helper
described in §2 moves here from `bin/fetch-ipeds` and is adopted by the other
two, so all three now write their outputs as one set. That is a change to _how_
they write, made because §2's best-effort argument depends on it.

Nothing else about what these scripts _fetch, parse, guard or write_ changes.
Their shrink floors, route refusals, member-drift detection and
fatal-on-unparseable rules are untouched.

### 6. These are laptop tools, not ops tools

None of the three is added to `bin/remote`'s `ops_tools` allowlist
(bin/remote:121) or to `bin/deploy`'s `REPO_PATHS` bundle (bin/deploy:124-138).

Two reasons, either sufficient. First, `fetch-external-data` egresses to
nces.ed.gov and collegedata.fyi and writes the _working tree_ — including
`db/data/codebooks.json` and the three `db/seed/cds/*.csv`, which are committed,
reviewed files. Its output is a diff someone reads, and a production host has no
working tree to write and no reviewer to read it. Second, `db/data/` and
`db/seed/` are not bundled into the deployed artifact at all, so every default
path `load-external-data` exists to supply is absent in production by
construction.

The production procedure is unchanged and stays the one RFC 92 defines:

```sh
bin/remote prod -f 1:institution.csv -f 2:fields.csv -- ingest-colleges @1 @2 …
```

`load-external-data -n` prints the command body to paste into it, so the
laptop-only tool still serves the production path without being deployed into
it.

### 7. `bin/reload-external-data`

```
reload-external-data [-n] [-h] <institution.csv> <fields.csv> [aliases.json]
```

It runs `fetch-external-data` with no selectors, and then, **only if that exited
0**, `load-external-data` with the arguments it was given. The `-c`/`-i`/`-s`
selectors are forwarded to the fetch half, so "refetch IPEDS only, then reload"
has a path through the composed tool rather than forcing the operator back to
the two halves.

It does validate its own positionals up front, before the fetch — the one thing
it holds beyond the ordering rule, and the reason _is_ the ordering rule:
discovering a bad positional _after_ a multi-minute download of three sources is
a usage error reported at the worst possible moment. It checks arity, emptiness
and (for non-`s3://` paths) existence, and it shares the grammar itself with
`load-external-data` through one helper in `bin/functions`.

What it must **not** do is call the load half's full validation as a preflight.
Those checks include the IPEDS CSVs, which legitimately do not exist yet on a
fresh checkout — that is what the fetch is for — so a preflight that ran them
would refuse every reload it was meant to help. The code carries a comment
saying so, because it reads like an omission and is not. `-n` is forwarded to
the load half, so `-n` fetches and then prints the command rather than running
it.

The asymmetry with §2 is the point and is worth stating plainly:

- _within_ the fetch, best-effort — three independent sources, and one outage
  should not cancel two good refreshes;
- _between_ fetch and load, all-or-nothing — the load consumes what the fetch
  produced, so loading after a partly failed fetch is precisely the partial mode
  the recipe forbids.

On that failure it exits **1 of its own**, not the fetch half's code — the same
rule §2 applies one layer down, and for the same reason: a republished `127`, or
a republished usage code from the grammar of a script the operator did not
invoke, is a diagnosis about the wrong program.

And it must not claim more than it knows. "The data on disk is unchanged" is
false after a partly successful best-effort fetch, because the sources that
succeeded have already rewritten committed files. The message names which
sources were written, which failed, that **no load ran**, and what to run next.

## Files Modified

| Path                            | Change                                                           |
| ------------------------------- | ---------------------------------------------------------------- |
| `bin/fetch-external-data`       | new — §1, §2                                                     |
| `bin/load-external-data`        | new — §3, §4                                                     |
| `bin/reload-external-data`      | new — composition, §7 ordering rule                              |
| `bin/fetch-codebooks`           | `getopt`, `EXIT_*`, summary to stderr — §5                       |
| `bin/fetch-cds-seed`            | `getopt`, `EXIT_*`, summary to stderr, `--anon-key` → `-K` — §5  |
| `bin/fetch-ipeds`               | record a top-level `survey_year` and a per-artifact `year` — §3a |
| `db/seed/ipeds/PROVENANCE.json` | backfill both years, nothing else — §3a                          |
| `db/seed/ipeds/FETCH-NOTES.md`  | why those fields have no fetch behind them — §3a                 |
| `bin/pyfunctions.py`            | new — the python counterpart to `bin/functions` — §5             |
| `bin/read-ipeds-manifest`       | new — the manifest reader, its own script — §3a                  |
| `bin/functions`                 | one shared positional-grammar helper — §7                        |
| `bin/scripts-tests`             | new section per script — §7                                      |
| `README.md`                     | the refresh runbook becomes three lines                          |

No schema change, no migration, no Kotlin change, no `bin/deploy` or
`bin/remote` change.

## Implementation Plan

1. §5 first — the two fetchers' option parsing, exit codes and stdout. It is a
   precondition of §1's stdout contract and is independently correct, so it
   lands and is verified before any wrapper exists.
2. §3a — the `bin/fetch-ipeds` writer line, the backfill and the note. It is a
   precondition of step 3 in the same way §5 is of step 4: without it the reader
   refuses on every real checkout.
3. `bin/load-external-data`, including the §3 manifest reader and the
   preconditions. `-n` makes it fully testable before step 4.
4. `bin/fetch-external-data`, including `-F`/`-o` forwarding.
5. `bin/reload-external-data`.
6. `bin/scripts-tests` sections and the `README.md` runbook.

## Tests

All in `bin/scripts-tests`, following the pattern the existing fetcher sections
already use: a `python3 -c ast.parse`/`bash -n` parse assertion, one shared
offline run into a `mktemp -d`, `assert_exit_code` against the `EXIT_*`
constants, `rm -rf` at section end and no section-level `EXIT` trap (the daemon
trap must survive). **No test touches the network** — `-F` and `-n` are what
make that true.

- usage: unknown option → 10; option missing its value → 11; unexpected
  positional → 20; `-F` without `-o` → 21 or 22 as `fetch-ipeds` already does.
- `fetch-external-data -F … -o …` with the committed fixtures produces all three
  sources and **writes nothing to stdout** (the existing
  `test_ipeds_logs_to_stderr_and_stdout_stays_empty` is the model).
- selectors: `-c` alone runs only the codebooks fetcher.
- best-effort: with one fetcher stubbed to fail, the other two still run, the
  summary reports `2 of 3`, and the exit code is `1` and not the child's code.
- `load-external-data -n` prints exactly one command line to stdout, and that
  line contains all fourteen flags with the six IPEDS paths and both years and
  the three positionals last, every value taken from a fixture
  `PROVENANCE.json`.
- each precondition of §3 fails with 21 and names the missing file _and_
  `bin/fetch-ipeds` — one test per missing-input class, including the
  currently-real missing `ic2023_ay.csv` case, and one asserting that several
  missing files are reported in a single refusal.
- the §3a year reader: `-y` from the top-level `survey_year` and `-f` from the
  SFA artifact's `year`, each absent → 21. Plus one test that the real committed
  `db/seed/ipeds/PROVENANCE.json` yields 2023 **and** 2022, so the backfill is
  asserted rather than assumed, and one that `SFA2223_Dict.zip` is skipped with
  no warning while an unrecognised artifact still warns.
- `reload-external-data` with a failing fetch half runs no load and exits with
  the fetch code, and — the other branch — with a succeeding fetch half it does
  run the load, forwarding `-n` and all three positionals.
- the `-n` command is checked against `bin/ingest-colleges`'s **own getopts
  spec**, deriving the value-taking letters from it rather than re-typing them,
  so a fifteenth ingest option fails this test instead of shipping as a silently
  partial load.
- the manifest reader refuses a record with no recognised `ingest_option`, a
  role claimed twice, a non-integer year, and a top-level `survey_year` that
  contradicts a collection artifact's own year — each naming what it read.
- the two rewritten fetchers keep their guards: re-run the existing
  codebooks/CDS section assertions unchanged, plus `-K` replacing `--anon-key`
  and `--anon-key` now exiting 10.

## Open items

- **The Scorecard fetcher.** Out of scope above, and the one thing standing
  between `reload-external-data` and its name. It needs its own RFC: a ~143 MB
  artifact, a pinned version, a `PROVENANCE.json`, and a decision about what is
  committed.
- **Cadence.** Nothing here schedules a refresh. Whether these data sets should
  be refreshed on a periodic task (RFC 97) rather than by hand is a real
  question and is not answered here.
