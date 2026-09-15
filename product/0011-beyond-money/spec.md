# 0011 — Beyond money: spec and slices

**Handle:** `beyond` (brief 0011). Gate 1 APPROVED 2026-09-10, 23 decisions.
**Gate 2 (spec):** PRESENTED 2026-09-10 — nine slices, decisions **D24-D32**
below.

---

## Preamble — what DISCOVER found, and where the brief was wrong

Four parallel DISCOVER agents verified the brief against the code
(`discover/prompt-and-wire.md`, `discover/field-of-study.md`,
`discover/student-profile.md`, `discover/surfaces.md`; 879 lines, every claim
citing a path and line range). **Forty-seven corrections.** The load-bearing
ones, because slices written against the brief's version of reality would burn a
/ship design phase:

| #   | The brief said                                         | The code says                                                                                                                                                                                                                                                                                                                                                                                                                               |
| --- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1  | The field-of-study file is "not ingested"              | The `fields` phase **already runs** and writes `college_programs`. What is missing is every FIGURE: the loader reads 4 of 178 columns (`CollegeScorecardLoader.kt:1838`)                                                                                                                                                                                                                                                                    |
| C2  | We would need a CIP vocabulary                         | It exists. `college_programs.cip_title` **is** `CIPDESC`, and `CIPCODE` is **4-digit on all 227,980 rows** — the grain D15 asked for is already stored. **429 code/title pairs, 391 at bachelor's**                                                                                                                                                                                                                                         |
| C3  | 311 covered families                                   | **391** bachelor's families exist; **308** carry an unsuppressed earnings figure. 311 was a coverage count mistaken for a vocabulary                                                                                                                                                                                                                                                                                                        |
| C4  | The source files are "committed in-tree"               | `db/seed/ipeds/*` and `db/seed/scorecard/*` are **gitignored** (`.gitignore:41-43,54-56`). Correct phrasing: **already fetchable by `bin/fetch-*`**                                                                                                                                                                                                                                                                                         |
| C5  | (nothing)                                              | **A LIVE money defect.** `CsvIngestSupport.PRIVACY_SUPPRESSED` is the literal `"PrivacySuppressed"`, which appears **zero times** in either committed CSV — the real sentinel is `PS`. Today **1,228 of 6,273** `GRAD_DEBT_MDN` rows are stamped `not_reported_by_institution` when the publisher **suppressed** them. A slice copying that seam would convert **44,047** publisher suppressions into "the school reported nothing"         |
| C6  | The figures go in the canonical money store            | **Neither existing table fits.** `cohort_money_stats`' uniqueness key is institution-level with closed CHECK lists — adding nullable `cip_code` would make every existing reader's `WHERE` silently wrong; `price_figures` cannot hold a pooled cohort in `academic_year`                                                                                                                                                                   |
| C7  | Debt-to-earnings by field is the story                 | The two columns are **different cohorts, different publishers, different dollar bases**: `EARN_MDN_4YR` = Treasury, AY2017-18+18-19 pooled, 2024 dollars; `DEBT_ALL_STGP_EVAL_MDN` = NSLDS, AY2018-19+19-20 pooled, **no inflation adjustment**. A ratio we compute would be a false figure                                                                                                                                                 |
| C8  | Two silence arms (figure / `PS`)                       | **Three.** Bachelor's rows: earnings 27,617 numeric / 44,047 `PS` / 0 blank; debt 26,005 / 40,870 `PS` / **4,789 blank**                                                                                                                                                                                                                                                                                                                    |
| C9  | D6: five income bands + `dependency`                   | Scorecard publishes **three FAFSA terciles** ($0-30k / $30,001-75k / $75,001+). Our five `IncomeBand` values are the **net-price** brackets — a different series; pairing them would attach a false label to a true number. And `dependency` is near-noise: **+2.7pp** median gap, positive at only 60.4% of schools, 70.9% coverage, against **+12.3pp** and 94.9% for income                                                              |
| C10 | D8: three unguarded figures                            | **Six** ride the wire unconditionally (`CollegeMatchRow.kt:155,156,177-180`), and they ride `similar_colleges` and the fit-lens prose too. They also split in two classes: `pell_share` and `median_earnings` come **out of** `cohort_money_stats` and `AssuranceTier` already names their variables — the DAO simply drops source and vintage. `completion_rate` is `colleges.C150_4` with **no source, vintage, status or tier anywhere** |
| C11 | D12 weaves into the report page                        | `CostReportPage.kt:74-79` **forbids the page authoring money copy**, and the page renders **no earnings at all** today. D12 is a `coaching/costs` change, not a `public-web` one                                                                                                                                                                                                                                                            |
| C12 | D18: tell the student before the first share           | **There is no per-share snapshot** — the report is always live (`ServiceCostReportSource.kt:20-33`). A stored field appears instantly on **every already-minted link**. The permission must gate the RENDER, not the mint                                                                                                                                                                                                                   |
| C13 | D15: serve the taxonomy through `/api/v1/vocabularies` | RFC 165 (`:102-112`) **excludes** the subjects taxonomy by rule, and the endpoint has no paging, filter, cache or compression; iOS refetches the whole document per screen appearance. 391 entries is ~25-30KB per screen open                                                                                                                                                                                                              |
| C14 | D21: `cip_codes` is tri-state                          | `cip_codes` has **no `{}` state** — `array_agg` over zero census rows returns NULL. The judged-no lives only on `subject_slugs`                                                                                                                                                                                                                                                                                                             |
| C15 | D22: add the field as a distance dimension (don't)     | A weighted **SUBJECTS axis already exists** (`SimilarityQuery.kt:36-37`, Jaccard at `CollegesDao.kt:1379-1394`), deliberately kept out of `DEFAULTS`. D22 therefore reads: never feed the student's stored field into that axis, and keep it out of `DEFAULTS`                                                                                                                                                                              |
| C16 | (nothing)                                              | Brief 0009's slices assert **rendered word-count ceilings** (<=1,480 / <=1,230 / <=1,170). A new woven sentence **fails those tests**, it does not warn                                                                                                                                                                                                                                                                                     |

Two further facts the spec is built on: the prompt is
`prompts/coach-system-prompt.md`, live pin **v27**, rollback `v26`, and since
RFC 185 a version mints only when the authored body differs; and there is **no
`SystemPromptCatalog` class in `src/main`** — the runtime seam is
`CoachingService.composeSystem` (`:845-905`), which returns the body verbatim.

---

## Gate 2 — the spec decisions (defaults pre-chosen)

| #                                    | Decision                                                                                                                                                                                 | Default                                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D24 — rank on it, do not quote it    | **D3 is unfollowable today for completion, admission rate and enrolment** — no vintage travels with them. So slice 1 must take one half of D8's "attribute them, or stop emitting them". | **Forbid the coach from speaking them as FACTS; do not delete the keys.** The sorts and filters read those keys; deleting them breaks search. The prompt says the coach may rank and filter on them and may not quote them as figures until they carry a stamp. The stamp arrives in `beyond/06`.                                                                       |
| D25 — sentinel fix lands first       | **The `PS` sentinel defect (C5) is a LIVE defect in money we already serve.**                                                                                                            | **Its own slice, landing BEFORE the field-of-study ingest.** Not folded into it: a money-store correction that re-labels 1,228 live rows deserves its own RFC, its own review and its own rollback, and the ingest slice must not be able to hide it.                                                                                                                   |
| D26 — a sibling table for majors     | **Where the field figures live (C6).**                                                                                                                                                   | **A sibling table, `program_money_stats`, keyed `(college_id, cip_code, credential_level, measure, vintage)`**, reusing the canonical store's own furniture: `figure_statuses`, `source` + `source_variable`, the publisher flag and the value-iff-status CHECK. **New table => brief 0001 D10: the DDL is presented at /ship's approval gate, not buried in the RFC.** |
| D27 — never compute the ratio        | **Debt-to-earnings by field (C7).**                                                                                                                                                      | **We never compute the ratio.** We serve the publisher's own `DEBT_ALL_STGP_EVAL_MDN10YRPAY` — the monthly payment on a 10-year plan — beside the earnings figure, each with its own cohort stamp. A ratio across two cohorts, two publishers and two dollar bases is a figure nobody published.                                                                        |
| D28 — terciles plus Pell             | **D6 restated (C9).**                                                                                                                                                                    | **Income terciles as the publisher defines them, plus Pell. Drop `dependency`.** The tercile boundaries are SPOKEN, never mapped onto our five net-price bands. Pillar 2 still needs no new asking.                                                                                                                                                                     |
| D29 — its own fields endpoint        | **How the 391 fields reach a picker (C13).**                                                                                                                                             | **A new `GET /api/v1/fields-of-study?q=`**, on the `CollegeRoutes` q/limit precedent — not `/api/v1/vocabularies`, which RFC 165 rules out and which would ship 25-30KB per screen open.                                                                                                                                                                                |
| D30 — undecided must fail to compile | **`undecided` as a fourth `AnswerStatus`.**                                                                                                                                              | **Yes, explicitly — and the slice must make all four exhaustive `when`s fail to compile until they handle it.** The alternative (a separate column) splits one answer across two places. `FieldUpdate.Clear` must NOT be exposed on this field: D17 says removing the last value returns the student to `undecided`, never to `unanswered`.                             |
| D31 — permission gates the render    | **D18's consent mechanism (C12).**                                                                                                                                                       | **A reversible permission on the student's profile that gates the RENDER, defaulting to OFF.** Not a `share_events` kind — those are append-only and permanent, and a consent a student cannot withdraw is not consent. Withheld-by-default also lets D18 and D20 share ONE render path.                                                                                |
| D32 — raise the ceiling in-run       | **The woven sentence breaks brief 0009's asserted word ceilings (C16).**                                                                                                                 | **The weaving slice raises the ceiling in the same run, and its RFC states the new number and why.** A ceiling silently raised is a ceiling that stops meaning anything; a ceiling left to fail makes the other brief's tests our blocker.                                                                                                                              |

---

## Wave board

Same wave = safe to run in parallel (a CONFLICTS pair may still share a wave; it
rebases).

- **Wave 1:** `beyond/01`, `beyond/02`, `beyond/09`
- **Wave 2:** `beyond/03`, `beyond/04`, `beyond/06`
- **Wave 3:** `beyond/05`, `beyond/08`
- **Wave 4:** `beyond/07`

Standing rules every slice inherits without restating: brief 0001 **D12** (value
before ask) and **D10** (DDL at the gate); brief 0009 **D8** (say it once); this
brief's **D3**, **D4**, **D23**.

---

## `beyond/01/what-the-coach-may-not-say`

**Needs:** —

**What:** The guardrail block the prompt has never had. Today 158 prompt lines
defend against invented non-money facts with ONE line, while six outcome figures
reach the model on every search row. One sentence of intent: before we teach the
coach to talk about majors and completion, stop it inventing the things it
already could.

**Foundations:** `prompts/coach-system-prompt.md` (live pin v27, rollback v26,
RFC 185 mints a version only when the authored body differs);
`CoachingService.composeSystem` (`:845-905`);
`CollegeMatchRow.kt:155,156,177-180` for the six figures;
`FitLensService.kt:961-966` for the prose path a prompt row does not reach.

**Decided here:** the block forbids (a) estimating this student's admission odds
or naming a school a reach/match/safety as a prediction; (b) describing a
school's culture, programmes, requirements or deadlines from model memory; (c)
quoting completion rate, admission rate or undergraduate enrolment **as
figures** while they carry no stamp — they may still rank and filter (D24).
**Left to /ship:** the wording, the block's position, and whether the fit-lens
prose needs its own rule or inherits this one.

**Acceptance:** a test proves the new paragraph exists and is reachable in the
composed system prompt; a test proves the old body still rolls back by
`COACHING_SYSTEM_PROMPT_VERSION=v26`; **no figure key is deleted from any tool
payload**; no migration, no new table, no new data. Reachability: every family
talking to the coach, from the next request after deploy.

**First session:** a student asks "what are my chances at Michigan?" and gets an
honest refusal that offers what we CAN say, instead of a number.

---

## `beyond/02/the-suppression-sentinel`

**Needs:** —

**What:** Fix a live defect in money we already serve. `CsvIngestSupport` looks
for the literal `"PrivacySuppressed"`; the Scorecard writes **`PS`**. One
sentence of intent: a figure the publisher suppressed must not be reported to a
family as a figure the school failed to report — they are different silences and
we say them differently.

**Foundations:** `CsvIngestSupport.PRIVACY_SUPPRESSED`; the `figure_statuses`
vocabulary (`suppressed_by_publisher` vs `not_reported_by_institution`); RFC
177/179/183's status seam. Measured blast radius: **1,228 of 6,273**
`GRAD_DEBT_MDN` rows today; **44,047 + 40,870** field-of-study cells the moment
`beyond/03` lands.

**Decided here:** the sentinel is recognised at the ingest seam, once, for every
consumer — not patched per column. **Left to /ship:** whether the fixture
literal stays as a second accepted spelling, and how the operator summary
reports the reclassification count.

**Acceptance:** a test that fails on today's code; the ingest summary states how
many rows changed status; a family-visible sentence changes from "the school did
not report this" to "the publisher suppressed this" for the affected rows, and
**nothing else moves**. Requires a re-ingest to reach a database, and the RFC
says so.

**First session:** a parent reading the report sees an honest reason, not a
blamed school.

---

## `beyond/03/what-this-major-returns`

**Needs:**

- BLOCKS beyond/02/the-suppression-sentinel — this slice writes 84,917
  suppressed cells through the same seam; landing first would convert every one
  of them into "the school reported nothing".

**What:** Load the field-of-study FIGURES. One sentence of intent: the
school-level earnings number is the one number that hides the most, and the
publisher already broke it down by major.

**Foundations:** `CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS` (4 of 178
columns today); `college_programs` (already carries `cip_code` 4-digit +
`cip_title` = `CIPDESC`); RFC 183's per-column vintage pinning and
`ScorecardDictionaryPinTest`.

**Decided here:** three columns — `EARN_MDN_4YR`, `DEBT_ALL_STGP_EVAL_MDN`,
`DEBT_ALL_STGP_EVAL_MDN10YRPAY`; the new `program_money_stats` table (D26, **DDL
at the gate**); three silence arms, not two (D28/C8: numeric / `PS` / blank);
per-column vintages pinned as RFC 183 pinned them, noting the pin test asserts a
key set **equal to** `ScorecardInstitutionColumns.LOADED_VARIABLES`, so the
transcription needs a `source_file` scope and its own vintage groups. **We never
compute a debt-to-earnings ratio** (D27). **Left to /ship:** the table's exact
columns and indexes, and the staging shape.

**Acceptance:** every figure carries its own publisher, variable and cohort
vintage; the two debt columns and the earnings column are never averaged,
combined or ratio'd; a blank and a `PS` produce different stored statuses and
different sentences; counters for all three arms reach the operator summary.
**The third counter is this slice's own work (D45)** — `beyond/02` landed two
counters (coerced out of range, suppressed by publisher) plus a LOG LINE for a
token that is neither a known blank nor a sentinel and does not parse; that arm
becomes a counter here, because a log line is invisible until somebody already
suspects a problem. **No family-visible change lands in this slice** — it fills
a store. Reachability is `beyond/05`'s job, and this slice says so.

---

## `beyond/04/your-intended-fields`

**Needs:** —

**What:** Let a student say what they want to study, and store it. One sentence
of intent: one answer, given once, that changes what search shows, which peers
we suggest, and what the report can say about return.

**Foundations:** `money_profiles` (the tri-state pattern, versioning, triggers);
`college_list_entries` as the per-student child-table precedent;
`StudentScopedChatTool`; RFC 171's "Your details" screen; `college_programs` as
the vocabulary source (429 pairs, 391 at bachelor's).

**Decided here:** ~~stored as 4-digit CIP (D15)~~ — **SUPERSEDED by D38: stored
as a `subjects` slug from our own 181-name taxonomy**, each name carrying CIP
prefixes, because one name can cover several codes and a family recognises
"nursing" where nobody recognises `51.38`. LANDED that way as RFC 205. An
unordered **set capped at 3**, no ranking (D16); a fourth status
**`undecided`**, exhaustively handled (D30); `Clear` not exposed (D17); a new
**`GET /api/v1/fields-of-study?q=`** (D29); both doors — chat and the screen
(D17). **New table => DDL at the gate** (brief 0001 D10); the proposed direction
is a child table keyed `(student_id, cip_code)` with the status on the parent.
**Left to /ship:** the iOS multi-select control (none exists — `DSPickerRow` is
single selection), and whether the REST grammar extends or gains a sibling route
(today it 400s on more than one selection).

**Acceptance:** value before ask — the coach offers the question only when the
answer would change what it says, names what it buys, and accepts "I don't know
yet" as a real answer that is stored, not as silence; a decline is durable and
undoable from the screen; no synonym is ever stored; a term that is not a field
of study is refused at the picker with a reason. Reachability: the coach, and
the "Your details" screen.

**First session:** a student says "I think I want to do nursing", the coach
stores `51.38` from the published list, and says what it will now be able to
tell them.

---

## `beyond/05/the-major-answer`

**Needs:**

- BLOCKS beyond/03/what-this-major-returns — there are no figures to speak
  without it.
- BLOCKS beyond/04/your-intended-fields — there is no field to speak about
  without it.

**What:** The coach answers "what do graduates of MY major at THIS school earn,
and what do they owe?" One sentence of intent: this is the aha — the moment the
cost conversation becomes a return conversation, in the family's own major.

**Foundations:** `CollegeCostChatTool` / `CollegeCostService`; the RFC
177/179/183 seam (`MoneySourceCopy`, `FigureStatusCopy`, `AssuranceTierCopy`);
`program_money_stats`.

**Decided here:** the answer names the cohort years in the publisher's words;
the monthly payment is the publisher's own figure (D27); suppression and absence
are different sentences (C8); the coverage limit is stated **once** per answer,
not per figure (D19/D23 and brief 0009's standing D8). **Left to /ship:**
whether this is a new tool or a widening of `college_cost_profile`, and the
prompt version arithmetic.

**Acceptance:** with no stored field, the answer degrades to the school-level
figure with its limits stated and does not nag; with a stored field the school
does not run, the coach says so plainly; with a suppressed figure the coach says
the publisher suppressed it and does not substitute the school-level number in
its place.

---

## `beyond/06/finish-like-me`

**Needs:**

- PREFER beyond/01/what-the-coach-may-not-say — product judgement: this slice
  puts a completion figure in front of families, and the guardrail that stops
  the coach quoting unstamped completion numbers should exist first.
  Overridable.

**What:** Completion odds for students like this one, and an honest headline.
One sentence of intent: the published graduation rate systematically overstates
a low-income student's odds, and the publisher already breaks it down.

**Foundations:** the committed Scorecard institution file;
`colleges.graduation_rate` (`C150_4`);
`college_search_index.completion_rate_150pct_4yr_share`; `money_profiles` income
band.

**Decided here:** **income terciles as the publisher defines them, plus Pell;
`dependency` is dropped** (D28) and the tercile boundaries are spoken, never
mapped onto our five net-price bands; the headline moves to `OMAWDP8_ALL` and
transfer-out becomes sayable (D9); every new figure carries a source and a
vintage stamp from the first commit (D7) — `C150_4` and `TRANS_4` are Fall 2018
while `RET_FT4` is Fall 2023, and four vintages beside each other with no stamps
is precisely the RFC 183 failure. **Left to /ship:** the columns and their
stamps, and whether the search index carries the subgroup figures or only the
headline.

**Acceptance:** a subgroup figure is never served without saying which
population it describes; with no money profile the all-student figure is served
and labelled as such; the headline change is explained as a different measure of
a different population, never as improvement; `completion_rate_150pct_4yr_share`
stops being an unstamped figure (D24's debt is paid here).

---

## `beyond/07/the-report-weave`

**Needs:**

- BLOCKS beyond/05/the-major-answer — the report renders copy authored in
  `coaching/costs` (the page may not author money copy), so the sentences must
  exist there first.
- BLOCKS beyond/10/one-place-for-permissions — D35/D36: the permission that
  gates this render is modelled there, and this slice must not grow a fourth
  bespoke shape of its own.
- CONFLICTS concise/01/the-page-says-it-once and concise/04/cite-once-per-block
  — both briefs rewrite the same report paragraphs (`debtBlock`,
  `borrowingParagraphs`, `meritBlock`); rebase risk only, not an order.

**What:** Weave what a major returns into the Family Cost Report's existing
money paragraphs. One sentence of intent: the parent asked what this will cost;
the same page should answer what it returns, in the student's own major.

**Foundations:** `CostReportPage.kt` (`debtBlock:981-1000`, the section order);
`ServiceCostReportSource.kt:20-33` (the report is always LIVE — no snapshot);
`ShareEventsDao`; RFC 155 D-F/D-G (no upgrade cues to a logged-out parent).

**Decided here:** no new section — the figures ride the existing money
paragraphs (D12); **the permission gates the RENDER and defaults to OFF,
reversible** (D31), so D18 and D20 share one withheld-by-default path; the
coverage limit is stated once, per-college gaps are short marks (D19); with
nothing stored the page is **byte-identical to today** (D20); the word ceilings
brief 0009 asserts are raised **in this run**, with the new number justified in
the RFC (D32). **Left to /ship, with a binding constraint:** how many of up to
three fields show per college — **never pick a favourite on the family's
behalf** (Ian, 2026-09-10).

**Acceptance:** a student who has not granted the permission has no majors on
any share, including links already minted; granting and revoking both take
effect on the next page load; the page still authors no money copy of its own.

---

## `beyond/08/your-field-in-search`

**Needs:**

- BLOCKS beyond/04/your-intended-fields — there is no stored field to search
  with.

**What:** The stored field improves search and similar-colleges **without ever
removing a school**. One sentence of intent: context orders and labels; only an
instruction cuts.

**Foundations:** `college_search_index.cip_codes` / `subject_slugs`
(`0064...sql:178-179`; 6-digit codes throughout, prefix matching already exists
at both rebuild and query time); `CollegeMatchRow`; `SimilarityQuery.kt:36-37`
and the Jaccard subjects axis at `CollegesDao.kt:1379-1394`; RFC 169's residency
offer (`CollegeChatToolTest.kt:119-150` pins that it never gates).

**Decided here:** a **stored** field never cuts — it orders and labels (D21); an
**explicit typed filter** may cut and must say how many schools it removed
(D21); the similarity **distance is untouched** and the SUBJECTS axis stays out
of `DEFAULTS` (D22, C15); each result states teaches-your-field **yes / no /
unknown**, and `NULL` is unknown — only `subject_slugs` carries a judged `{}`
(C14); the offer reuses RFC 169's mechanism verbatim. **Left to /ship:** whether
results group or annotate. ~~Which side widens 4-digit to 6-digit~~ is **MOOT**
since RFC 205 landed: the answer is stored as a `subjects` slug, and
`college_search_index.subject_slugs` already speaks that same vocabulary.

**Acceptance:** a test proves no school is removed by a stored field; a test
proves the similarity ranking is byte-identical with and without a stored field;
the census false-negative (a new programme with no graduates yet) is a
**footnote at most** (D23).

---

## `beyond/09/who-pays-for-college-advice`

**Needs:** —

**What:** The first `public-web` explainer page: how the college-guidance market
is paid for, and what that means for the advice a family gets. One sentence of
intent: the research we did for ourselves is more useful to families than it is
to us.

**Foundations:** `public-web/src/main/kotlin/ed/unicoach/web/render/`
(`PrivacyPage.kt`, `TermsPage.kt`, `Layout.kt`), `Routing.kt`,
`LegalPagesTest.kt`; `research/landscape.md` as the sourced material.

**Decided here:** D14's content rules are the acceptance criteria, not advice —
every load-bearing claim carries a public URL and a fetch date; a claim about
another company quotes its OWN published methodology, pricing or product page; a
visible "as of" date; no scores, ranks or grades; no lead capture, no email
wall, no ad. **A claim the research marked unverified may not appear.** **Left
to /ship:** whether the prose is authored in Markdown or kotlinx.html, and
whether an index page is built yet (a second page is guaranteed — brief 0010
inherits this pattern).

**Acceptance:** every external claim on the page resolves to a live URL recorded
in the page's own source; the page needs no account and sets no cookie it did
not already set; it names unicoach's own incentive too, because a page about who
pays that omits its author is the failure it is describing.

**First session:** a parent with no account reads it, and trusts the product
more for having read it.

---

## Gate 2 — APPROVED by Ian, 2026-09-11, verbatim: "approve"

All nine slices and decisions **D24-D32** stand as written, at their defaults.
The board is startable: `beyond/01`, `beyond/02`, `beyond/04`, `beyond/06` and
`beyond/09` are READY (`beyond/06` carries an overridable PREFER on
`beyond/01`).

Two slices add a table and therefore hit brief 0001 **D10** at their own /ship
gates, with the DDL presented explicitly and not buried in the RFC:

- `beyond/03/what-this-major-returns` — `program_money_stats` (D26).
- `beyond/04/your-intended-fields` — the student's intended fields child table
  (D15/D16/D30).

Ledger (one line per landed slice, appended as each lands):

_(empty — nothing landed yet)_

---

## Parallel-work protocol (adopted 2026-09-11 from brief 0010, not re-derived)

Brief 0010's spec records four parallel-work hazards, learned the expensive way
while briefs 0009 and 0010 ran beside each other. This brief adopts them rather
than rediscovering them:

1. **Start from the CHANGE, not the file.** Enumerate every file your pass
   touches, then protect that set. Two sessions independently scoped a freeze to
   one file and both were wrong the same way — the collision arrived through
   `CollegeCostChatTool.DESCRIPTION`, which nobody protected because no other
   SESSION was editing it. A sibling slice was.
2. **A hold is scoped to a FILE and a KIND of edit, never to a brief or a
   wave.** Holding a slice that never opens the contended file is cargo-culting
   the agreement past its reason.
3. **A deletion pass contends with EVERY file it deletes from**, whoever is
   editing them, including your own sibling slices. `Needs: CONFLICTS` is one
   word for both hazards, so where a slice here deletes from a file another live
   slice adds to, the reason must say so — the board cannot.
4. **`git add` only the paths you enumerated.** In a shared checkout, adding
   another session's path is silent, passes every gate, and lands under your
   commit message; neither RFC 193 nor RFC 194 catches it, because a docs-only
   commit is exactly what it looks like.
5. **A guard inside the artifact under rewrite is not a guard.** Any slice here
   that ratchets a budget, pins a sentence or asserts a count must put that
   guard somewhere its own work does not open. This binds `beyond/07` directly
   (D32 raises a word ceiling asserted by brief 0009's tests) and `beyond/03`
   (the vintage pin).

Concretely for this brief: `beyond/01` and `beyond/05` both mint a coach prompt
version, and brief 0010's and 0009's slices do too — **only one prompt-editing
slice runs at a time**, and that is a scheduling rule, not a `Needs:` edge,
because the file is shared across briefs and the board cannot see across
sessions.

---

## Spec corrections, 2026-09-11 — what landed while this brief sat at gate 2

Two drift-check agents re-verified all nine slices against `main`
(`discover/drift-coach.md`, `discover/drift-surfaces.md`). Brief 0009 landed
EIGHT slices (RFCs 188-192, 196, 198, 199) and brief 0010 landed its wave 1
(RFCs 186, 187). **The code wins over this spec.** These corrections are binding
on the slice text above.

### A standing rule this brief now follows

**No slice text quotes a line number, a prompt line count or a version pin.**
Every one of them was stale within a day. A slice's RFC reads the live pin from
`service/src/main/resources/service.conf` and the seams from the code, at design
time. The spec names FILES and SYMBOLS; the code supplies coordinates.

### Corrections

| #   | The spec said                                       | Reality on `main`, 2026-09-11                                                                                                                                                                                                                                                                                                                                                                                                   |
| --- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| X1  | prompt 158 lines, pin **v27**, rollback v26         | **207 lines, pin v32, rollback v31** — and `rfc-202` (`concise/11`) is landing v33 as this is written. `beyond/01`'s acceptance criterion is rewritten to "rolls back to the pin that was live at design time, read from `service.conf`".                                                                                                                                                                                       |
| X2  | `beyond/01` adds a guardrail paragraph              | The prompt ALREADY forbids invented college facts (line 5) and admission odds inside the testing block (line 65), and RFC 192/199 established a duplicate-deletion convention. **The paragraph must state only what is NOT already there** — principally the unstamped-figure rule (D24) — or review will cut it.                                                                                                               |
| X3  | (nothing)                                           | A new prompt paragraph must be registered in the **`CoachParagraph` enum**, and needs a seed SQL file plus a `db/schema/ORDER` line. An RFC that moves a rule between homes declares a **HALF rollback** ("revert the code commit too").                                                                                                                                                                                        |
| X4  | `beyond/05` widens `college_cost_profile`           | RFC 192 turned `CollegeCostChatTool.DESCRIPTION` into a **pure schema legend** with two guards: `LEGEND_BYTE_CEILING = 6845`, set at the measurement with **zero headroom**, and a `legend, not a rulebook` test banning rule phrasings. So every SAY rule goes in the PROMPT, and widening the tool costs an explicit, justified ceiling raise. A NEW tool ships its own measured ceiling (the siblings are 1359 / 621 / 472). |
| X5  | D27: serve the payment beside the earnings figure   | RFC 198 (prompt line 105) **forbids earnings and the 10-year payment sharing one table**, because they are two publishers; lines 106-108 add that "once per answer" is never licence to move a source or year away from its figure. D27's content stands; its PRESENTATION is decided for us, and D19's coverage-limit wording must respect the same rule.                                                                      |
| X6  | `beyond/06` reads `colleges.graduation_rate`        | The column was renamed to `completion_rate_150pct_4yr_share` (`0059.sql:59`).                                                                                                                                                                                                                                                                                                                                                   |
| X7  | `beyond/07` CONFLICTS `concise/01` and `concise/04` | **The edge is SPENT** — all four page rewrites (RFCs 188/190/191/196) have LANDED. `CostReportPage.kt` grew 1,127 -> 1,556 lines; the slice reads its own coordinates.                                                                                                                                                                                                                                                          |
| X8  | D32: ceilings "<=1,480 / <=1,230 / <=1,170"         | **Those numbers never became assertions.** Each slice missed its spec number and ratcheted the MEASURED one. There is exactly ONE live word ceiling: `WORD_CEILING = 1404`, on the chain 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404.                                                                                                                                                                                             |
| X9  | (nothing)                                           | **Four byte ceilings** (6845 / 1359 / 621 / 472) plus a **1,992-byte per-college payload budget**, all set at the exact measured size with **zero headroom**. Six budgets now stand between this brief and a new sentence. See D33.                                                                                                                                                                                             |
| X10 | (nothing)                                           | **RFC 196 added a source sweep** over `CostReportPage` asserting `countOf("href") == 1` and `countOf(".url") == 0`. A woven sentence that LINKS the Scorecard **fails outright**. RFC 196's link promise is scoped to one byte-frozen CDS sentence via a `CdsCitation` helper, so it creates **no pattern** for citing the Scorecard. See D34.                                                                                  |
| X11 | (nothing)                                           | **RFC 191 asserts** that every figure the page speaks a kind for is covered by exactly one sentence, and that the `MANDATORY_SURVEY` / `VOLUNTARY_SELF_REPORT` sentences render **zero** times; `proseFiguresOf` hardcodes `MEDIAN_DEBT` as the only prose figure. **A field figure carrying a tier breaks both tests.**                                                                                                        |
| X12 | `beyond/07` needs no DDL                            | The render permission (D31) **has no column to live on**. `beyond/07` is therefore a **THIRD** brief-0001-D10 DDL slice, which the gate-2 close-out did not list. See D35.                                                                                                                                                                                                                                                      |

### Still true — do not re-verify

The `PS` sentinel defect is live and unmoved (`CsvIngestSupport.kt:218`); the
field loader still reads 4 of 178 columns; `college_programs` grain unchanged;
the search-index arrays unchanged; the SUBJECTS axis is still out of `DEFAULTS`
and the Jaccard unchanged; the six unguarded wire figures are untouched (RFC 189
compressed the COST tool, a different payload);
`completion_rate_150pct_4yr_share` is still completely unstamped, so **D24's
debt is still owed**; the report is still always live with no snapshot; there is
still no `SystemPromptCatalog` in `src/main`. **`beyond/09` needs no correction
at all.**

### Correction to the parallel-work protocol above (Ian, 2026-09-11)

The clause "only one prompt-editing slice runs at a time" is **RETRACTED**. It
was hazard 2 — a hold kept past its reason. RFC 185 made the prompt body a
line-based Markdown file that **git merges like any other source**, and
`bin/prompt-seed` generates the seed **at land, after the final rebase**, with
one writer owning both the label and the pin. So parallel prompt work is fine.
What remains: same-region edits are an ordinary rebase; **a deletion pass
contends with every file it deletes from**; and git merges text, not meaning —
the second lander reads the first's paragraph before adding a sibling rule.

---

## `beyond/10/one-place-for-permissions`

**Needs:** —

**What:** One modelled place for what a student allows a parent to see, built
before the first thing that needs it. One sentence of intent: a permission is a
thing in its own right, not a flag bolted to whatever feature happened to need
it first.

**Foundations:** the three existing shapes this does NOT converge but must not
imitate — `cost_report_shares.revoked_at` (revoked once, forever),
`share_events` kind `opted_out` (permanent, no undo), and `money_profiles`'
`unanswered | answered | declined` status columns (undoable, versioned,
trigger-guarded). The `money_profiles` versioning and trigger stack is the shape
worth reusing.

**Decided here:** D36 governs — the data is modelled correctly first, then a
structure to control it, composed rather than compromised. Every permission is
**named and explicit**, never an arbitrary key-value pair, because a generic key
space invites keys nobody reviews. Every permission is **reversible**, and its
default is **withheld**. Its first occupant is "show my intended majors on the
Family Cost Report" (D18/D31). **New table => brief 0001 D10: the DDL is
presented at /ship's approval gate.** **Left to /ship:** the table shape, and
whether the three existing shapes get a stated migration path or only a recorded
note.

**Acceptance:** the permission exists, defaults to withheld, and is reversible
from the student's own surface; no existing shape is changed; nothing renders
differently, because the consumer does not exist yet — this slice is
deliberately invisible, and its RFC says so. Reachability is `beyond/07`'s job.
