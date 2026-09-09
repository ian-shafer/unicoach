# RFC 182 — Two more ingest refusals: a freshman count checked against IPEDS, and one filing attributed to several colleges

Slice: `soft/04/two-more-ingest-refusals` (brief 0008, gates 1+2 approved
2026-09-05). Lane A. Independent of every other 0008 slice — nothing else
touches `bin/fetch-cds-seed`.

## Summary

`bin/fetch-cds-seed` already refuses a CDS filing it cannot read. Fourteen drop
reasons are counted in `db/seed/cds/PROVENANCE.json` today, and every one of
them is a check **inside a single filing**: more merit awards than freshmen, a
borrowing block that contradicts itself, an unmapped deadline template. There is
no check that compares a filing against anything outside itself, and that is
exactly where the remaining wrong numbers live.

This RFC adds the first two **cross-source** refusals, in the same
drop-and-fall-back shape as the fourteen:

1. **A freshman count that disagrees with the federal file.** Refuse the H2A
   merit block when CDS `H.201` and IPEDS `SCFA1N` differ by more than 50% in
   either direction.
2. **One filing attributed to several colleges.** Refuse a value signature that
   repeats byte-for-byte across different `unit_id`s in the same `source_year`.

Both are operator-facing. Nothing a family sees gains a warning, a flag or a
score (gate-1 D4, standing decision D7). A refusal here says "we could not read
this filing", which is unicoach's gap and is never spoken as the school's.

### What the measurements actually say now

The slice text quotes the numbers from
`product/0008-how-soft-is-this-number/research/corpus-coherence.md`. The CDS
seed was re-fetched on 2026-09-08, one day after that research, so they were
re-measured against the committed seed at `071d03b1` before this RFC was written
(`.scratch/ship/rfc-182/research/data-repro.md`, digests of `SFA2223`, `ADM2023`
and `HD2023` verified against `db/seed/ipeds/PROVENANCE.json`).

| Claim                                   | Slice text                           | Measured today                    |
| --------------------------------------- | ------------------------------------ | --------------------------------- |
| Rule 1 failures                         | 20 of 270 (7.4%)                     | **16 of 261 (6.1%)**, two-sided   |
| Rule 1, one-sided as the slice words it | —                                    | 11 of 261                         |
| Rule 1 "extractor grabbed total UG"     | 8                                    | **3**                             |
| ADM2023 replication                     | 14 of 263                            | 11 of 255, all inside the 16      |
| Rule 2 clusters                         | 7 tuples / 15 unit_ids / 3.6% of 421 | **7 / 15 / 3.58% of 419 — exact** |
| Rule 2, no IPEDS SFA row                | 2                                    | 2 (`229407`, `231970`)            |

Five of the slice's eight headline extractor defects were corrected upstream in
the 2026-09-08 fetch (UC Davis 31,464 -> 6,615; Ole Miss 17,519 -> 5,186; Puget
Sound 1,575 -> 430; Whitman 1,508 -> 390; St Olaf 3,066 -> null). **Three wrong
freshman counts ship today, not eight**: UIUC (`145637`, 33,994 vs 7,946),
University of Florida (`134130`, 32,857 vs 6,594) and Rowan (`184782`, 14,181 vs
2,574). The slice's acceptance criterion "the RFC names the 8 colleges" is met
by naming the three that remain and the five that were fixed before us. Three
corrections go back to `/chart` (see **Spec corrections**).

## Detailed Design

### Rule 1 — `merit_freshmen_vs_ipeds_implausible`

**The comparison.** CDS `H.201` ("full-time first-time degree-seeking freshmen",
`bin/fetch-cds-seed:1082`) against IPEDS `SCFA1N` from `sfa2223.csv` — the
student-financial-aid survey's own cohort count, which is the denominator
section H is filed over. Join on `unit_id = UNITID`. Refuse when

    H.201 / SCFA1N > 1.5   or   H.201 / SCFA1N < 1 / 1.5

Skip the comparison when `H.201` is null, when the unit has no SFA row, or when
`SCFA1N <= 0` — three cases with no signal in them (261 of 264 non-null `H.201`
rows are comparable).

**Two-sided, not one-sided.** The slice text says "exceeds by more than 50%",
but the 20 it quotes was measured two-sided, and the one-sided rule misses the
worst row in the corpus: Penn State (`214777`) files 185 freshmen against an
`SCFA1N` of 9,181 — 0.02x. A count 50x too small is as unreadable as one 5x too
large, and both mean the same thing about our extractor. The rule is two-sided.

**Where it runs.** Around `map_merit_rows` (`:1080`), in a
`map_merit_rows_checked` wrapper that is what `FACT_GROUPS` names: the mapper
stays a pure reading of one document and the cross-source audit is composed onto
it. It behaves exactly as the H2A contradiction test does: the whole H2A block
is refused (`return []`), so the document is unreported for the merit group and
`find_newest_reporting` (`:1566`) may fall back to an older filing. The reason
joins `STICKY_DROPS` (`:473`) for the reason its H2A sibling is sticky — it
describes the DOCUMENT, so it must stay visible in the ledger even when an older
filing covers for it.

**The same cohort has a second address, and it is checked too.** `H.201` is not
the only freshman headcount this seed writes: `H.204`
(`aid_awarded_freshmen_count`, in `aid_policy.csv`) counts the freshmen of the
same cohort, out of the same filings. Refusing a wrong number in one file while
serving it from the other is incoherent — and it is not hypothetical, since two
committed rows fail the test at colleges whose MERIT block this RFC already
refuses (`145637` UIUC, 14,731 against an `SCFA1N` of 7,946; `175272`, 2,747
against 1,157). So the H2 block gets the sibling refusal,
`aid_policy_freshmen_vs_ipeds_implausible`, recorded beside
`aid_policy_h2_contradictory` in `map_aid_policy_rows`, refusing the whole H2
block so an older filing may cover for it, and sticky for the same reason.

**That sibling is ONE-SIDED, and rule 1 proper is not.** Freshmen who were
AWARDED aid are a SUBSET of the cohort: almost every honest filing reports fewer
than `SCFA1N`, and many report far fewer. A low `H.204` is therefore normal, not
evidence, and refusing it would throw away true numbers by the hundred. Only
`H.204 > 1.5 x SCFA1N` — more freshmen awarded aid than the cohort contains — is
a reading our extractor got wrong. `H.201`, by contrast, IS the cohort, so a
value far below `SCFA1N` is as wrong as one far above it and its test stays
two-sided.

**Where the IPEDS data comes from.** `bin/fetch-cds-seed` has no IPEDS input
today; its only inputs are the corpus manifest and fields (`:658-690`). IPEDS
CSVs are deliberately not committed — `db/seed/ipeds/` holds only
`PROVENANCE.json` and `FETCH-NOTES.md` (`.gitignore:41-43`) — and
`bin/fetch-ipeds` is what writes `sfa2223.csv` there. The DB is not an option
either: the IPEDS ADM ingest stores no enrolment headcount, and the seed is
built before ingest.

So the script gains **one short option, `-I <dir>`**, the directory holding
`sfa2223.csv`, defaulting to `db/seed/ipeds` under `PROJECT_ROOT`. It reads
`UNITID` and `SCFA1N` only.

**The file may legitimately be absent** — a fresh checkout has no IPEDS CSVs,
and `bin/fetch-external-data` imposes no order between the IPEDS and CDS
fetchers (`:164-180`). An absent file therefore is not a fatal error and is
**not** a silent pass. The run proceeds with the check not run, and
`PROVENANCE.json` says so:

    "cross_source": {
      "ipeds_freshmen": {
        "ran": false,
        "reason": "no_sfa2223_csv",
        "path": "db/seed/ipeds/sfa2223.csv"
      }
    }

— the path included, because `-I` makes it a choice and a reader of a committed
`PROVENANCE.json` cannot otherwise tell a checkout with no IPEDS data from a run
pointed at a mistyped directory. When the check does run the block is
`{ "ran": true, "units": 3328, "compared": 310, "unreadable_rows": 0 }` — the
figures this RFC's own regeneration produced. `unreadable_rows` counts the
survey rows whose `UNITID`/`SCFA1N` pair carries TEXT we could not read — an
imputation code, a `1,234`, a mis-decoded byte — but NOT the ones that are
simply blank, which is the survey saying nothing about a college and is a third
of its rows. A federal file we could read only half of narrows this rule to
whatever survived, and a block saying only "ran" would spell that as "nothing
was wrong". A refusal count of zero, a check that never ran, and a check that
ran over a half-read file are three different facts, and the ledger has to
distinguish them or the next reader will read the wrong one.

**A file that is PRESENT and unreadable is a fault, not a skip.** A truncated
download, a renamed federal column, a `sfa2223.csv` that is a directory, a
repeated `UNITID`: each would otherwise leave the check comparing nothing (or
comparing against whichever cohort the file listed last) while the ledger said
it ran. Each is refused by name, with the header or the rejected row it read. A
`-I` directory that was named explicitly but holds no `sfa2223.csv` is a usage
error (`EXIT_INVALID_ARG_VALUE`), not a skip: the operator asked for the check.

### Rule 2 — `filing_attributed_to_multiple_units`

**The signature.** For one `(unit_id, source_year)` and one seed file, the
byte-for-byte tuple of that file's VALUE columns — everything except `unit_id`,
`source_year`, `source_url` and `archive_url`. Two different `unit_id`s carrying
an identical signature in the same `source_year` are one PDF mapped onto two
UNITIDs.

**Detected in `merit-aid.csv` and `aid_policy.csv` only.** The measured
false-positive rate on the other two files is fatal to the rule:
`admission-factors.csv` produces 19 clusters, one of 26 unit_ids, and
`deadlines.csv` 25, one of 10. Dozens of colleges legitimately rate all 18 C7
factors `very_important` or share a 1 January deadline. Byte-identical
categorical or date tuples are not evidence of a misattributed filing;
byte-identical five-figure dollar amounts are.

**But the CONSEQUENCE covers all four files.** The two categorical files are out
of scope as EVIDENCE, never as consequence. What the signature proves is a fact
about a DOCUMENT — this PDF is filed under two colleges — and that document fed
whichever groups selected it. Removing only its dollar rows leaves the same
wrong college citing the same wrong PDF for its deadlines and its C7 grid: the
defect ships half-fixed, and it is visible in the output, because the rows that
stay carry a byte-identical `source_url`. So the rule detects on the numeric
files and then removes, and records, that document's rows in **every** group.

Removal is keyed by `(unit_id, source_url)` — the filing itself — not by
`(unit_id, source_year)`. Each fact group chooses its own newest reporting
document, so a college in a cluster may hold its deadlines from a DIFFERENT
filing, which nothing has proved misattributed. Keying on the document removes
exactly what the evidence covers and leaves that filing alone.

**A minimum-cardinality guard, or the rule fires on coincidence.** Williams and
Georgia Gwinnett share a 2025 signature whose only non-null value is a merit
count of **0** — a legitimate value that two colleges may both report. A
signature must carry **at least 3 non-null values** to be refusable. The same
guard drops the Boston University / Carnegie Mellon pair, whose single shared
value is `graduating_class_count = 2025` — the class YEAR read as a headcount, a
live extractor defect landed by `shape/07b` and reported to `/chart` below, not
this rule's business.

**Every member of the cluster is dropped, not all-but-one.** We know one of the
filings is misattributed; we do not know which, and there is no evidence inside
the seed that picks a winner. Keeping the largest campus would be a guess
rendered as a fact. So every `unit_id` in the cluster loses the rows its
document fed, and one drop is recorded per `(unit_id, file)` with the cluster's
members as the sample detail — so the ledger says which file each removal came
out of, including the two the rule does not detect in.

**This one cannot fall back.** The check needs every unit at once, so it runs in
`build_seed` (`:1610`) after the per-document mapping is complete —
`find_newest_reporting` has already chosen. A refusal here therefore REMOVES
rows rather than making a document unreported, and no older filing covers for
it. That is a real difference from all fourteen existing reasons and from rule
1, and it is deliberate: a fallback would need a second mapping pass to be
honest, which is more machinery than a 15-unit problem earns.

**Effect on the committed seed, as the run produced it:** 5 clusters, 10
unit_ids, and 35 refusals — one per (unit_id, file): 10 in `aid_policy.csv`, 9
in `merit-aid.csv`, 8 in `admission-factors.csv` and 8 in `deadlines.csv`. The
clusters are Northeastern x2 (`167358`, `482705`), Springfield x2 (`167899`,
`475273`), WPI + Whitman (`168421`, `237057`), Houston x2 (`225511`, `229407`)
and EVMS + Old Dominion (`231970`, `232982`).

Five of the 7 measured clusters, not seven, because the two rules OVERLAP and
rule 1 runs first: the Fairleigh Dickinson pair and two of the three UW campuses
lose their merit AND their H2 blocks to rule 1 and its `H.204` sibling before
rule 2 sees them, so no signature is left to repeat. The third UW campus is then
alone and keeps its rows. Those refusals are counted under rule 1's reasons, and
a reader of `PROVENANCE.json` must not add the reasons together to get "filings
we refused".

The USC Aiken / USC Columbia pair is NOT refused, contrary to the expectation
above: the guard counts the non-null values of ONE file's signature, and that
pair carries two in `merit-aid.csv` (the merit count and its average) and one in
`aid_policy.csv` (the graduating class). Neither file's signature is evidence on
its own, which is what the guard says.

**One correction to the slice's story.** The slice calls this "a main campus's
PDF mapped onto branch UNITIDs". That holds for six clusters. It does not hold
for WPI + Whitman — unrelated colleges in different states that share a 2024
signature. The rule is right; the explanation is not, and the RFC does not
repeat it.

### The two rules overlap, and the counts are not independent

WPI's `H.201` of 390 is Whitman's number, so cluster 3 also produces one of the
16 rule-1 failures. Whichever rule runs first takes the credit. Rule 1 runs
per-document and rule 2 at build time, so rule 1 counts it. `PROVENANCE.json`
readers must not add the two reasons to get "filings we refused".

### Regenerating the seed

Code alone changes nothing a family sees: `db/seed/cds/*.csv` is committed
output. This run therefore re-runs `bin/fetch-ipeds` (for `sfa2223.csv`) and
`bin/fetch-cds-seed`, and commits the regenerated CDS seed together with the
code, with the before/after row counts stated here. Upstream drift unrelated to
the two refusals is reported rather than absorbed silently.

**What the run produced** (2026-09-09; `sfa2223.csv` re-fetched the same day,
every archive digest unchanged from the committed
`db/seed/ipeds/PROVENANCE.json`):

| seed file               | rows before | rows after |
| ----------------------- | ----------- | ---------- |
| `merit-aid.csv`         | 368         | 352        |
| `aid_policy.csv`        | 3,568       | 3,496      |
| `admission-factors.csv` | 373         | 369        |
| `deadlines.csv`         | 846         | 831        |

Every college that left a file is accounted for by one of the two rules; the
only additions are four colleges the corpus newly carries (`128106`, `129215`,
`180179`, `221740`) and the older filings rule 1's fallback reached.

- `merit-aid.csv`: 20 filings removed — 11 by rule 1 outright (the other 4 of
  its 15 refusals fell back to an older filing that passes) and 9 by rule 2.
- `aid_policy.csv`: 15 filings, 142 rows — 5 by the `H.204` sibling and 10 by
  rule 2.
- `admission-factors.csv`: 8 filings, and `deadlines.csv`: 8 filings / 28 rows —
  all of them rule 2's consequence, none of them its evidence.

Drop counts: `merit_freshmen_vs_ipeds_implausible` 15,
`aid_policy_freshmen_vs_ipeds_implausible` 7 (at 6 colleges — `377555` is
refused in its newest filing and again in the older one the fallback tried),
`filing_attributed_to_multiple_units` 35.

Two pieces of upstream drift are worth naming. Penn State (`214777`) now files a
2025-26 University Park filing that reports 9,142 freshmen against an `SCFA1N`
of 9,181, so the 0.02x row this RFC was measured on is gone before us and rule 1
fires 15 times rather than the 16 measured. Tulane (`160755`) likewise moved to
a 2025-26 filing. Five other drop counts also moved without any help from this
change (`aid_policy_h5_average_over_no_borrowers` 169 -> 173,
`aid_policy_value_junk` 12 -> 13, `deadline_flag_junk` 25 -> 26,
`merit_int_junk` 22 -> 21, and `merit_h2a_contradictory` 1 -> 0), which is the
corpus re-extracting, not us. `aid_policy_flag_junk` (32 -> 31) and
`aid_policy_h5_contradictory` (16 -> 15) moved because the filings they counted
are now refused earlier, by the `H.204` test.

## Files Modified

- `bin/fetch-cds-seed` — `-I` option and `GETOPT_SPEC`; an `sfa2223.csv` reader;
  the `H.201`/`SCFA1N` test around `map_merit_rows` and the `H.204` sibling in
  `map_aid_policy_rows`; the duplicate-signature pass in `build_seed`; three
  reasons, all three in `STICKY_DROPS` bar the run-level one; the
  `cross_source.ipeds_freshmen` block in `PROVENANCE.json`; `-h` text.
- `bin/scripts-tests` — new fixtures and assertions for both rules, the skip
  path, the unusable-file faults, and the two guards; existing drop-count
  assertions re-checked, since a new fixture document shifts them.
- `db/seed/cds/*.csv` and `db/seed/cds/PROVENANCE.json` — regenerated. All four
  CSVs, not two: rule 2 removes the refused document's rows from every group.
- `rfc/182-two-more-ingest-refusals.md`.

No migration, no schema change, no Kotlin, no prompt version, nothing
family-visible (gate-2 G6).

## Implementation Plan

1. `-I <dir>` through `parse_short_options` (`:1780`), short options only,
   default `db/seed/ipeds`; `-h` text; explicit `-I` with no `sfa2223.csv` is
   `EXIT_INVALID_ARG_VALUE`.
2. `load_ipeds_freshmen(ipeds_dir)` reads `<dir>/sfa2223.csv` into an
   `IpedsFreshmen` lookup, `SCFA1N > 0` only. The lookup, not a bare dict: it
   also tallies the colleges it was actually asked about, which is the
   `compared` figure the `cross_source` ledger reports. An ABSENT file yields
   the `IpedsFreshmenUnavailable` variant — same questions, "did not run"
   answers — rather than a `None` every caller re-interprets. A file that is
   PRESENT but carries neither column, or no usable cohort row, is a failed read
   and `fatal`: it would otherwise compare nothing while the ledger said the
   check ran.
3. Thread the lookup to `map_merit_rows` the way `drops` is threaded — every
   builder takes it, the three groups with no freshman count ignore it — and add
   the two-sided test in a `map_merit_rows_checked` wrapper so the mapper stays
   pure; register the reason in `STICKY_DROPS`.
4. The duplicate-signature pass in `build_seed`, detected in the two numeric
   files with the >= 3 non-null guard; drop every member, in every group the
   refused DOCUMENT fed; one `drops.record` per `(unit_id, file)`.
5. `cross_source` block in the provenance writer.
6. Tests in `bin/scripts-tests` (below), then `nix develop -c bin/shell-tests`.
7. Regenerate the seed; record before/after row counts and the units dropped.

## Tests

`bin/scripts-tests` only — the sole harness for this script (`:2179-2994`), run
offline via `bin/fetch-cds-seed -F "$CDS_FIXTURES" -o "$CDS_OUT"` and run in
`bin/test check` through `bin/shell-tests:74`. Following the existing precedent
(`:2551`, `seed_lacks '^555555,' merit-aid.csv` plus `cds_drop_count`):

1. A filing whose `H.201` is 5x its `SCFA1N` emits **no merit row**, and
   `cds_drop_count merit_freshmen_vs_ipeds_implausible 1`.
2. The low side: `H.201` at 0.02x of `SCFA1N` is refused by the same reason —
   the test that fails if anyone makes the rule one-sided.
3. A filing within the band emits its merit row unchanged.
4. A unit with no SFA row, and one with `SCFA1N = 0`, are compared against
   nothing and keep their rows; drop count 0.
5. No `sfa2223.csv`: the run succeeds, no merit row is refused, and
   `PROVENANCE.json` records `cross_source.ipeds_freshmen.ran = false`. An
   explicit `-I` at a directory without the file exits 22.
6. Two unit_ids sharing a 3-value merit signature in one `source_year`: **both**
   lose their rows, `cds_drop_count filing_attributed_to_multiple_units 2`.
7. The same values in DIFFERENT `source_year`s: both rows kept.
8. A 1-value shared signature (the Williams/Gwinnett shape): both rows kept,
   drop count 0.
9. `admission-factors.csv` and `deadlines.csv` rows that duplicate byte-for-byte
   are kept — the scope, asserted rather than assumed.
10. Sticky behaviour for rule 1: an older filing covers for the refused
    document, and the drop is still counted.
11. Rule 2's consequence: a refused cluster loses its `admission-factors.csv`
    and `deadlines.csv` rows too, counted per file — and a group whose own
    selection reached a DIFFERENT filing keeps its rows, which is what proves
    the removal is keyed on the document rather than on the college.
12. The `H.204` sibling, both sides: a count above `1.5 x SCFA1N` refuses the
    whole H2 block, and a count far BELOW the cohort — the normal case for aid
    recipients — is kept. The second is the test that fails if anyone makes this
    rule two-sided.
13. A present-but-unusable `sfa2223.csv` is a FAULT, not a skip: one file whose
    header lacks `SCFA1N`, one that carries no usable cohort row.
14. The file rule 1 reads is the one `bin/fetch-ipeds` writes (an aid-year bump
    there would otherwise retire the rule in silence), and the COMMITTED seed's
    `cross_source.ipeds_freshmen.ran` is true (a regeneration without the
    federal file would otherwise restore every refused row and still match its
    own manifest).

Existing `cds_drop_count` and row-count assertions are re-run and corrected
where a new fixture document shifts them.

## Spec corrections returned to `/chart`

1. **"8 colleges" is now 3.** Five were fixed upstream on 2026-09-08. The
   corpus-coherence numbers are one seed-fetch stale: 16 of 261 two-sided, 11 of
   255 on ADM2023.
2. **The slice text words rule 1 one-sided; its own measurement is two-sided.**
   Built two-sided, per the design reasoning above.
3. **"A main campus's PDF mapped onto branch UNITIDs" is wrong for one of the
   seven clusters** (WPI + Whitman are unrelated institutions).
4. **A live extractor defect found while measuring, owned by no slice**: Boston
   University and Carnegie Mellon both report `graduating_class_count = 2025` —
   the class year read as a headcount, landed by `shape/07b`.
