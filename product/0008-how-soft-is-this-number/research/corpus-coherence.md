# 0008 research — our own corpus: is the softness measurable in the data we hold?

Topic owner: `corpus-coherence` research subagent, product brief 0008. Written
2026-09-07. **Read-only run** — no repo file outside this one was changed,
nothing was committed, no `bin/fetch-*` script was executed (they overwrite
reviewed seeds).

---

## METHOD

Three measurement surfaces, kept separate because they answer different
questions.

**A. The seed we ship.** `db/seed/cds/aid_policy.csv` (1,807 rows, 338
`unit_id`, 336 (unit,year) filings), `merit-aid.csv` (371 rows/units),
`deadlines.csv` (1,033 rows, 320 filings), `admission-factors.csv` (377 rows).
Read with pandas in the agent kernel. Pure CSV arithmetic — no project code.

**B. The upstream corpus the seed is built from.** collegedata.fyi PostgREST
API, `https://api.collegedata.fyi/rest/v1`, using the same tables, the same
filters and the same anon-key discovery `bin/fetch-cds-seed:114-115,471-547`
uses, re-implemented in my own throw-away script (I did **not** run the
fetcher). Fetched 2026-09-07: `cds_manifest` with
`extraction_status=eq.extracted` and `removed_at=is.null` → **4,809 rows**
(committed `PROVENANCE.json` records 4,810 — the corpus moved by one row since
2026-09-06); `cds_fields` for `field_id like H.4*` → 446 rows and `like H.5*` →
4,070 rows, plus the H.201/H.204/H.208/H.209/H.211 block → 2,111 rows. I
re-implemented `document_unusable_reason` and `select_documents`
(`bin/fetch-cds-seed:567-602`) and `get_int` (`bin/fetch-cds-seed:690-716`)
exactly.

**C. Federal cross-checks.** IPEDS `ADM2023` and `C2023_a` from `db/seed/ipeds/`
(committed digests in `db/seed/ipeds/PROVENANCE.json`), read locally. IPEDS
**SFA2223** was fetched and analysed by a delegated child agent; its full
working is `.scratch/0008-xsource-cds.md` and is cited here as **[XCDS]**. The
Scorecard institution file, IPEDS `IC2023_AY` and the Scorecard data dictionary
were fetched and analysed by a second delegated child; its working is
`.scratch/0008-xsource-price.md`, cited as **[XPRICE]**. Both children verified
every download digest against the repo's committed `PROVENANCE.json` pins before
measuring.

**Tools.** `nix develop -c python3 --version` was not needed: every step above
is data analysis over downloaded CSV/JSON, run in the agent's own kernel. No
project import, no Gradle, no `psql`. I did **not** stand up the test database —
`bin/test` recreates it, and the CDS facts at issue are all in the seed CSVs, so
the DB adds a load step and no evidence.

---

## 1. Ian's claim: "14 of 249 filings report more borrowers than graduates"

### 1.1 Verdict: **exactly true**, and I reproduced both numbers.

The facts are CDS **H4/H5** — `H.401` "number in the class" and `H.501` "number
who borrowed from any loan program". They are **not in the seed we ship**: RFC
170 D1 explicitly split them out (`rfc/170-need-and-forms.md:30-31` — "CDS H4/H5
borrowing becomes `shape/07b/borrowing`"), which has not landed. So the claim is
measured on the **upstream corpus**, surface B, not on `db/seed/cds/`.

Counting every `cds_fields` document that reports both `H.401` and `H.501` with
`value_status = reported`, and reading each cell through the fetcher's own
`get_int` rule:

| Denominator                                                                                    | N       | borrowers > graduates | rate     |
| ---------------------------------------------------------------------------------------------- | ------- | --------------------- | -------- |
| **Every document reporting both H.401 and H.501**                                              | **249** | **14**                | **5.6%** |
| ...restricted to manifest-usable documents (`document_unusable_reason` = None)                 | 226     | 13                    | 5.8%     |
| ...one filing per school (newest document reporting the group — the seed's own selection rule) | 192     | 12                    | 6.3%     |

**249 / 14 is the first row.** Ian's number is the raw document count, not the
seed's per-school selection. Reproduced to the digit.

### 1.2 But the finding is about **our extraction**, not the school's honesty.

Here are all 14, with the corpus's own `producer` column:

| school                                    | year    | producer       | H.401 "graduates" | H.501 "borrowers" |
| ----------------------------------------- | ------- | -------------- | ----------------- | ----------------- |
| The University of Alabama                 | 2024-25 | tier4_docling  | **6**             | 2,280             |
| Princeton University                      | 2025-26 | tier4_docling  | **24**            | 144               |
| Georgia Institute of Technology           | 2025-26 | tier4_docling  | **25**            | 1,186             |
| Georgia Institute of Technology (2nd doc) | 2025-26 | tier4_docling  | **25**            | 1,186             |
| Messiah University                        | 2025-26 | tier4_docling  | **27**            | 321               |
| Manhattan College                         | 2025-26 | tier4_docling  | **27**            | 312               |
| Johns Hopkins University                  | 2025-26 | tier4_docling  | **28**            | 263               |
| University of Florida                     | 2025-26 | tier4_docling  | **29**            | 1,056             |
| University of Massachusetts-Amherst       | 2025-26 | tier4_docling  | **39**            | 2,588             |
| St. John Fisher University                | 2025-26 | tier4_docling  | **87**            | 349               |
| Quincy University                         | 2025-26 | tier2_acroform | **111**           | 121               |
| University of Illinois Urbana-Champaign   | 2024-25 | tier1_xlsx     | **356**           | 2,923             |
| University of Pittsburgh                  | 2024-25 | tier4_docling  | **2024**          | 2,058             |
| The University of Alabama                 | 2025-26 | tier4_docling  | **2025**          | 2,334             |

Two tells, both decisive:

- **12 of 14 come from `tier4_docling`/`tier2_acroform` — PDF layout
  extraction.** The one `tier1_xlsx` case (UIUC) is a spreadsheet read.
- **Two rows literally contain the class year**: Pittsburgh's "graduating class"
  is `2024` in a 2024-25 filing; Alabama's is `2025` in a 2025-26 filing. The
  extractor took "Class of 2025" as the headcount.
- Princeton graduates ~1,284 bachelor's a year (IPEDS `C2023_a`, `AWLEVEL=5`,
  `CIPCODE=99`). A reported class of **24** is not a school overstating
  borrowing; it is a cell read from the wrong place on the page.

Confirmed independently against IPEDS completions (`db/seed/ipeds/C2023_a.csv`,
bachelor's = `AWLEVEL=5 & CIPCODE=99 & MAJORNUM=1`, 2,445 institutions). Of 231
corpus filings I could join, **12 report a class under 15% of the school's total
bachelor's completions** — and **11 of those 12 are among the 14**.

> **The honest reading of "14 of 249": it is a real, reproducible, per-filing
> coherence violation, and its cause is our own PDF extraction pipeline, not a
> school misrepresenting itself.** That does not weaken the case for measuring
> coherence. It changes what the flag would _mean_, and therefore what a family
> should be told — "we could not read this filing reliably", not "this school's
> claim looks doubtful". Getting that sentence backwards is exactly the RFC 170
> failure mode the brief names.

### 1.3 A caution on the number 249

The seed also happens to contain **249 unit_ids present in all four fact
groups**. That is a coincidence and the two 249s are unrelated. Anyone quoting
"249" downstream should say which one they mean.

---

## 2. What else is checkable on the corpus we hold, today

### 2.1 The purely internal checks are **already implemented — and already clean**

`bin/fetch-cds-seed` runs two per-filing coherence rules at ingest, and its
policy is **drop the block and fall back to an older document**, not store a
flag:

- `merit_h2a_contradictory` — `no_need_merit_count > freshmen_ft_total`
  (`bin/fetch-cds-seed:949-960`): "the whole H2A block is untrustworthy,
  INCLUDING the average award it reports".
- `aid_policy_h2_contradictory` —
  `need_fully_met_count >
  aid_awarded_freshmen_count`
  (`bin/fetch-cds-seed:1267-1286`, applied at `:1288-1299`): "A fully-met
  headcount (line h) cannot exceed the line-d denominator it is reported
  against".
- Both are `STICKY_DROPS` (`:346-358`) so the count survives the fallback.
- Percent cells are read scale-aware and range-bounded (`get_percent`,
  `:749-790`).
- The whole seed is refused if a group collapses below `SEED_SHRINK_FLOOR = 0.5`
  of the committed count (`:133`, `:1421-1455`).

Committed `PROVENANCE.json` records what those rules caught on the 2026-09-06
run: `aid_policy_h2_contradictory` **2**, `merit_h2a_contradictory` **1**,
`aid_policy_value_junk` **3**, `aid_policy_flag_junk` **31**,
`factor_rating_junk` **16**, `merit_int_junk` **22**,
`deadline_date_unparseable` **36**, `deadline_flag_junk` **26**,
`field_status_parse_error` **37**.

So every internal check I can define runs to **zero violations on the shipped
seed**, because the offending filings were dropped before they got there:

| Check (defined precisely)                                                           | N filings tested | violations                   |
| ----------------------------------------------------------------------------------- | ---------------- | ---------------------------- |
| `avg_need_met_percent` outside 0..100                                               | 309              | **0**                        |
| `need_fully_met_count > aid_awarded_freshmen_count` (H.208 > H.204)                 | 309              | **0**                        |
| `aid_awarded_freshmen_count > freshmen_ft_total` (H.204 > H.201, cross-file join)   | 265              | **0**                        |
| `need_fully_met_count > freshmen_ft_total` (H.208 > H.201)                          | 257              | **0**                        |
| `no_need_merit_count > freshmen_ft_total` (H.2A01 > H.201)                          | 244              | **0**                        |
| any count negative, any dollar average negative                                     | all              | **0** (refused by `get_int`) |
| `offered = false` but a date present (deadlines)                                    | 1,033 rows       | **2**                        |
| notification date before closing date, same round, naive same-cycle rule            | 165 rounds       | **3**                        |
| all 18 C7 admission factors = `not_considered` (a filing that says nothing matters) | 377              | **1**                        |
| ≤ 2 of 18 C7 factors non-`not_considered`                                           | 377              | **8**                        |

Corroborated by [XCDS] independently: 0/309, 0/309, 0/244, 0/265 on the four
numeric ones.

**This is the single most important result in this report.** _A stored coherence
table over the facts we hold today would be an empty table._

### 2.2 Two checks that are NOT clean, and neither is internal

**(a) Filing-year divergence within one school.** The seed picks a document
**per fact group** (`select_documents` docstring, `:591-596`), so one school's
merit row and aid row can come from different CDS cycles. **33 of 421 units
(7.8%)** have fact groups drawn from different `source_year`s. That is a
citation-honesty issue (`CdsCitation` renders one year), not a data defect.

**(b) A single CDS filing attributed to several `unit_id`s.** [XCDS] found **7
value-tuples byte-identical across 2–3 different `unit_id`s in the same
`source_year`, touching 15 unit_ids (3.6% of 421)** — a main campus's PDF mapped
onto its branch/online UNITIDs. Two of them (229407, 231970) have no IPEDS SFA
row at all. This is invisible to any within-filing check and visible instantly
to a duplicate-value scan.

### 2.3 Coverage: the CDS touches a minority of colleges

- Units with any CDS row in the seed: **421**.
- Units present in all four fact groups: **249**.
- IPEDS `HD2023` four-year, degree-granting, currently-active institutions
  (`ICLEVEL=1 & DEGGRANT=1 & CYACTIVE=1`): **2,821**; the repo's four-year
  universe is `INST_LEVEL_FOUR_OR_MORE_YEARS = 1`
  (`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:2238`).
- **CDS coverage: 421 / 2,821 = 14.9%.**

---

## 3. Cross-source disagreement — the strongest evidence

### 3.1 CDS vs the federal freshman count: 5–7% of filings cannot be reconciled

I ran this against IPEDS **ADM2023** `ENRLFT` (enrolled full-time first-time);
[XCDS] ran it against IPEDS **SFA2223** `SCFA1N`. Two different federal files,
two independent agents, near-identical answers.

| measure                                                 | mine (ADM2023 `ENRLFT`) | [XCDS] (SFA2223 `SCFA1N`) |
| ------------------------------------------------------- | ----------------------- | ------------------------- |
| N compared                                              | 263                     | 270                       |
| within 10%                                              | 67.7%                   | 57.8%                     |
| within 25%                                              | 91.6%                   | — (82.2% within 20%)      |
| ratio > 2x or < 0.5x                                    | **14 (5.3%)**           | —                         |
| \|diff\| > 50% ("hard fail", 1.5x slack for year drift) | —                       | **20 (7.4%)**             |

My 14 outlier unit_ids:
`110644, 127556, 134130, 145637, 174844, 175272, 176017,
184694, 184782, 206604, 236328, 237057, 377555, 377564`
— **all 14 are inside [XCDS]'s 20**. That is a replication, not a coincidence.

**Root cause identified for 8 of them** [XCDS §3a]: `freshmen_ft_total` matches
SFA `SCFA2DG` (**total** degree-seeking undergraduates) within 10% while being ≥
2× `SCFA1N`. The extractor read the total-enrolment row where the CDS asks for
first-time full-time freshmen. Examples: UIUC 33,994 vs 7,946 freshmen but
34,031 total UG; Florida 32,857 vs 6,594 / 33,673.

**These 8 filings are in the seed we ship today.** They pass every internal
check in §2.1. Only a federal file exposes them.

### 3.2 Most cross-source "disagreement" is definitional, and must not be quoted as softness

[XCDS §1–2] measured all five available CDS↔SFA pairs and found only **one**
(H.201 vs SCFA1N) is like-for-like. H.204 vs `ANYAIDN` (median −27.6%) and
H.2A01 vs `IGRNT_N` (median −74.3%) are **strict-subset relations**: the CDS
item counts a need-restricted / no-need subset of the IPEDS population, so a
large signed gap is _expected_. H.211 vs `IGRNT_A` (median +52.3%) compares
all-sources need-based grant against institutional-only grant — not a coherence
test at all.

Two hard limits [XCDS METHOD], stated honestly:

- **SFA2324 and SFA2425 are HTTP 404 at NCES as of 2026-09-07.** SFA2223 is the
  newest federal aid file. Every CDS filing we hold (2024/2025) is 1–2 cohort
  years ahead of it.
- Measured year drift on the same 373 schools, SFA2122→SFA2223: median 6–9%, p90
  20–30%. **Any gap under ~25% is inside the noise floor.**

That is why the defensible headline is **24 of 377 unit_ids (6.4%)** failing a
hard test at 1.5× slack, not the 70–98% raw disagreement rates.

Also worth recording: **H.208 (need fully met) and H.209 (average % of need met)
are unverifiable against any federal source** — IPEDS does not collect met-need
[XCDS §6]. Those two figures, which the coach quotes, have no external check at
all. Ever.

### 3.3 Scorecard vs IPEDS on price: the disagreement is a VINTAGE gap

Delegated and now returned; full working in `.scratch/0008-xsource-price.md`,
cited here as **[XPRICE]**. All three zip digests it measured match the repo's
own pins (`db/seed/scorecard/PROVENANCE.json` release `06102026`,
`db/seed/ipeds/PROVENANCE.json`), so this measures exactly what unicoach serves.

| Pair                                  | N     | exact     | within 1%   | within 5% | median \|diff\| | p90 \|diff\| |
| ------------------------------------- | ----- | --------- | ----------- | --------- | --------------- | ------------ |
| `TUITIONFEE_IN` vs IPEDS `CHG2AY3`    | 3,325 | **20.3%** | 28.2%       | 75.6%     | $394            | $2,352       |
| `TUITIONFEE_OUT` vs `CHG3AY3`         | 3,325 | 20.2%     | —           | —         | $544            | $2,242       |
| `NPT4*` bands vs SFA `NPIS4x`/`NPT4x` | —     | ~0.1%     | —           | —         | ~$1,600         | —            |
| `PCTPELL` vs `UPGRNTP/100`            | 5,392 | 1.3%      | 24.9% (1pp) | —         | 2.59pp          | —            |

**RFC 161's 92.1% is refuted for the pinned artifacts** (`rfc/161-*.md:16-17`):
today it is **20.3%**, with 2,649 mismatches rather than 269. RFC 162's
identities fail the same way (`NPT41_PUB == NPIS412`: 0.1%). But the cause is
**not** two publishers disagreeing about a fact:

- The Scorecard dictionary's cohort map puts `TUITIONFEE_IN/OUT` at
  **AY2024-25** and `NPT4*`/`PCTPELL` at **AY2023-24**. The newest IPEDS files
  that exist are `IC2023_AY` (2023-24) and `SFA2223` (2022-23). `IC2024_AY`,
  `SFA2324` and every `_rv` variant are **404** [XPRICE METHOD].
- Median Scorecard/IPEDS tuition ratio **1.0262** vs IPEDS's own year-over-year
  ratio **1.0265**, and **83%** of mismatches have the Scorecard higher. That is
  one year of tuition inflation, not a data dispute.
- The RFC's own Austin Community College example still reproduces exactly (2550
  / 2550 / 8580).

**[XPRICE] also read a live code defect out of this** (read from code, not run):
Scorecard prices are stamped `AcademicYear(2022)`
(`CanonicalMoneyLoader.kt:908,1152`) but are AY2024-25, and `NPT4` is stamped
2021 (`:986,1003,1155`) but is AY2023-24. Because `PriceKey`/`StatKey` include
the year, (a) IC_AY never collides with the Scorecard tuition key, so RFC 161's
designed displacement **never fires**; (b) the Scorecard `NPT4` rows **do**
collide with SFA's 2021-22 rows, so a 2021-22 net price can displace a 2023-24
one under a 2021-22 label. That is out of scope for this brief but should be
carried to the money brief as a defect, not lost here.

### 3.4 The assurance tier cannot be keyed on the source

From the Scorecard data dictionary's own `SOURCE` column
(`https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx`,
linked from `https://collegescorecard.ed.gov/data/data-documentation/`) [XPRICE
§5]:

| Scorecard variable                                              | dictionary `SOURCE` | kind                                  |
| --------------------------------------------------------------- | ------------------- | ------------------------------------- |
| `TUITIONFEE_IN/OUT`, `COSTT4_A`, `NPT4*`, `PCTPELL`, `PCTFLOAN` | IPEDS               | **re-published IPEDS**                |
| `DEBT_MDN`, `GRAD_DEBT_MDN`                                     | **NSLDS**           | administrative (federal loan records) |
| `MD_EARN_WNE_P10`                                               | **Treasury**        | administrative (tax records)          |

**Every money field unicoach cross-checks is an IPEDS re-publication.** The only
administrative Scorecard money facts are the NSLDS debt medians and the Treasury
earnings — exactly the two unicoach maps to `MEDIAN_DEBT_AT_COMPLETION` and
`MEDIAN_EARNINGS_10Y`
(`db/src/main/kotlin/ed/unicoach/db/models/MoneyMeasure.kt:55-56`) — and those
have **no IPEDS twin at all**.

This settles the brief's second success criterion directly. "Scorecard" is not
one kind of fact. A tier keyed on `MoneySource` would call a re-published IPEDS
tuition figure _administrative_ and would have nothing left to say that is true
of both it and the NSLDS debt median. **The defensible key is (measure,
vintage), not (source).**

---

## 4. Conclusion: is a stored per-filing coherence flag (C4) worth its table?

### Verdict: **no**

Not as a table over the facts we hold. Yes as a check we **run** — whose output
belongs in the ingest ledger, not in a family-facing store.

The numbers behind that:

1. **The internal checks are already implemented and already clean.** Every
   within-filing test I could define runs to **0 violations on the shipped
   seed** (§2.1, 10 checks, 6 numeric ones at exactly zero, corroborated by a
   second agent). The two rules that fire (`aid_policy_h2_contradictory` = 2,
   `merit_h2a_contradictory` = 1 on the last run) already fire **at ingest**,
   and their designed remedy — drop the block, fall back to an older document —
   is _better_ than storing a flag, because it means the family never sees the
   bad number in the first place. A `coherence_findings` table over today's
   corpus would have **three rows referencing figures that are not in the
   store**.

2. **Everything that actually fires needs a SECOND SOURCE, not a second
   column.** The defects that survive into the shipped seed are: 8 filings where
   the wrong row was extracted (visible only against IPEDS `SCFA1N`/`SCFA2DG`),
   15 unit_ids sharing one filing (visible only to a duplicate scan across
   rows), 33 units whose fact groups are from different years (visible only
   across groups). **None of these is a per-filing flag.** C4 as written —
   "written as rows against a figure or filing" — models the wrong shape for
   every defect we can actually measure.

3. **The flag would mislabel its own cause.** 12 of Ian's 14 are `tier4_docling`
   PDF-layout failures and two literally contain the class year. A stored
   per-filing flag surfaced to a family says "this school's number looks wrong".
   The true sentence is "we could not read this school's filing reliably".
   Storing the finding without storing that distinction reproduces the RFC 170
   failure the brief was opened over — **our gap rendered as the school's
   fault**.

4. **Blast radius on families is tiny and lopsided.**
   - CDS covers **421 of 2,821** four-year colleges = **14.9%**.
   - Hard cross-source failures: **24 of 377 CDS unit_ids = 6.4%**.
   - **24 / 2,821 = 0.85% of the four-year universe.** Fewer than 1 college in
     100 would ever carry a flag, and a family only sees it if that college is
     on their list _and_ the surfaced figure is one of the flagged ones.
   - By contrast a **static** softness axis applies to **100%** of figures on
     **100%** of colleges — a two-orders-of-magnitude larger surface for the
     same sentence-writing effort.

5. **And the static axis has to be keyed on the MEASURE, not the source**
   (§3.4). C2 as the brief words it — "an `assurance` axis on the source" — is
   refuted by the Scorecard's own dictionary: `TUITIONFEE_IN` and
   `GRAD_DEBT_MDN` share a `MoneySource` and are a re-published survey and an
   NSLDS administrative record respectively. C4 and C2 are therefore **both**
   wrong-keyed as written, and for the same reason: the unit that carries
   assurance is the (measure, vintage) pair, not the filing and not the
   publisher.

### What I recommend instead

- **Take C5, plus a re-keyed C2.** One derived-hedge renderer (C5) is
  unconditionally right: it kills the duplication the brief opened over and
  decides no ontology. Pair it with an assurance axis keyed on the **measure**
  and its **vintage** —
  `MEASURE -> {administrative record, mandatory survey,
  voluntary self-report}`
  — not on `MoneySource`. That stays code-side (no migration, same shape as
  `MoneySource`'s existing per-member metadata), covers every figure on every
  college, and is the only version that survives §3.4. Do **not** ship C1 or
  C2-as-written.

- **Keep the coherence checking, move it nowhere.** Extend
  `bin/fetch-cds-seed`'s existing drop-and-fall-back rules with the two
  cross-source ones this research found — reject a filing whose `H.201` exceeds
  IPEDS `SCFA1N` by >50%, and reject a value-tuple that repeats across unit_ids
  — and count them in `PROVENANCE.json` beside the nine reasons already there.
  That fixes 8 wrong numbers currently shipping, costs no DDL, and keeps the
  finding where an operator reads it rather than where a family does.
- **Revisit C4 only when `shape/07b/borrowing` lands.** H4/H5 is the one fact
  group where a within-filing check has real violations (14/249, 5.6%). If that
  slice ships the borrowing figures, it should ship the `H.501 ≤ H.401` refusal
  in the same fetcher rule that already refuses `H.208 > H.204` — again a drop,
  not a stored flag.
- **Say out loud that H.208 and H.209 have no external check.** If the hedge is
  going to be derived, "average percent of need met" deserves the softest
  sentence in the vocabulary: self-reported, unaudited, and unverifiable against
  any federal file that exists.

---

## Reproducing this

The upstream-corpus half needs the collegedata.fyi anon key, discovered from
`https://www.collegedata.fyi/api` exactly as `bin/fetch-cds-seed:471-491` does.
The seed half needs only `db/seed/cds/*.csv` and `db/seed/ipeds/*.csv`, both in
the checkout (IPEDS CSVs are gitignored — `.gitignore:41-43` — but present in a
checkout that has run `bin/fetch-external-data`). The scripts I ran were
throw-away kernel cells, not committed; every count above is stated with its
denominator and its filter so it can be re-derived.

**Unavailable to anyone, stated honestly:** any IPEDS SFA file newer than
2022-23 and any IPEDS IC_AY newer than 2023-24 — `SFA2324`, `SFA2425`,
`IC2024_AY` and every `_rv` revised variant return 404 at NCES as of 2026-09-08.
That means (a) every CDS-vs-federal comparison in §3.1 spans 1-2 cohort years,
(b) the ~92% same-vintage tuition agreement of §3.3 cannot be _proved_, only
inferred from the dictionary cohort map plus the 1.026 inflation ratio, and (c)
RFC 162 D2's "prefer revised" currently has nothing to prefer.

**Also out of scope but not to be lost:** the vintage-stamping defect in
`CanonicalMoneyLoader` (§3.3) — Scorecard AY2024-25 charges stamped
`AcademicYear(2022)`, NPT4 AY2023-24 stamped 2021, with a real key collision
against SFA's 2021-22 rows. It was read from the code, not observed in a live
table. It belongs in the money brief as a defect, not in this one.

**Not attempted:** I did not stand up the test database. `bin/test` recreates
it, and every fact at issue is in a seed CSV or an upstream API, so the DB would
have added a load step and no evidence.
