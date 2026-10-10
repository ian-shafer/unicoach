# 0010 — When do I need to take the SATs?

**Handle:** `test` (brief 0010)

## Status

- **Phase:** EXECUTE — **waves 1, 2 and 3 COMPLETE, and wave 4's first slice
  landed** (RFCs 186, 187, 195, 197, 207, 208, 209, 213, 220, 227, 228, 229,
  239). Only `test/08/the-psat-is-money` remains, and its PREFER edge on
  `test/07` is now met: the curated award corpus and its `checked_on` discipline
  exist, so the PSAT slice extends a seam rather than inventing a second one.
  Ask `slice-board`, not this line.
- **Gate 1 (prioritisation):** **APPROVED by Ian 2026-09-10, verbatim:
  "approve".** All fourteen decisions taken at their defaults. D4, D10 and D13
  already carried Ian's pre-approval amendments (explain the stakes once; infer
  school-day testing from the stored residency state instead of asking; scope is
  the test FAMILY, never "the SAT"); D14 was added in the same round. **D9 is
  standing** (never test prep, never score prediction, never chancing) and is
  inherited by future briefs without re-asking.
- **Gate 2 (spec):** **APPROVED by Ian 2026-09-10** — D15-D20 at their defaults,
  confirmed by his dispatch instruction ("both") with the decision list in hand.
  One pre-dispatch correction: a missing CONFLICTS edge between `test/01` and
  `test/02` on `prompts/coach-system-prompt.md`. Eight slices in `spec.md`.
- **Research:** three parallel subagents, all reported 2026-09-10 —
  `research/testing-landscape.md`, `research/data-feasibility.md`,
  `research/user-value.md`; DISCOVER is `discover.md`. A fourth,
  `research/psat-national-merit.md`, verified the National Merit claims D13
  rests on — see **Corrections to the record** below.
- **Ledger:** below, one line per landed slice.

## Ledger

_(one line per landed slice: ID, RFC, SHAs, one-line what.)_

    test/01/the-coach-knows-today LANDED as RFC 186 (main@88a8f1da + 57c668ba,
       2026-09-11) — every system text now carries today's date in US Eastern,
       the student's expected graduation date at its stored precision, and the
       year of school derived from the two; the composeSystem early return is
       deleted, so the block renders for a brand-new student with nothing on
       file. Coach prompt v28 (rollback COACHING_SYSTEM_PROMPT_VERSION=v27).
       Executed: 3,073 tests, 0 failures, whole suite; CoachingServiceTest 56,
       HighSchoolYearTest 10.

    test/02/does-this-school-want-a-score LANDED as RFC 187 (main@1b352e39 +
       493dd34f, 2026-09-11) — college_admissions_profile gains a fourth
       section, test_policy: the per-school IPEDS ADMCON7 policy that was
       already stored and already a search filter but never readable for one
       named school. Zero DDL. Every claim carries its collection year inside
       the citation sentence, the "a school can change this, confirm on their
       page" caveat rides on EVERY rendered section, and an absent policy says
       we do not have it, never that the school requires no score. Coach prompt
       v29 (rollback COACHING_SYSTEM_PROMPT_VERSION=v28). Executed: 1,734 tests,
       0 failures on the landed tree; 18 added; 19 files, +1503/-96.

    test/04/the-testing-calendar LANDED as RFC 197 (main@131fb5ef + c5f52d78,
       2026-09-13) — admission_test_administrations, 15 hand-typed SAT/ACT rows
       for 2026-27 committed as a CSV with a PROVENANCE.json and generated into a
       seed migration by bin/test-calendar-seed; the admission_test_timeline tool
       works back from the earliest deadline on the student's list through score
       release to the day registration closes, and shows the working. Past the
       calendar's expiry it refuses to name a date and points at College Board
       and ACT; a test fails when any live row is within 90 days of expiry. Coach
       prompt v34 (rollback COACHING_SYSTEM_PROMPT_VERSION=v33, PARTIAL — the pin
       drops the paragraph but leaves the tool registered, which is why the
       refusal and the four guardrails also live in the tool's description).
       Executed: 3,064 tests, 0 failures; 1,050 shell assertions.

    test/03/the-range-not-the-average LANDED as RFC 195 (main@d9e60e33 +
       8a6edd18, 2026-09-14) — six 25th/75th percentile score columns on colleges
       and a fifth score_bands section on college_admissions_profile: the coach
       speaks the range a school's admitted class actually scored in, with a
       quarter above and a quarter below beside it, instead of an average that
       reads like a bar. Bands for 1,039 of 6,273 colleges; where only an SAT
       average exists it is spoken and labelled as an average (D16/D-A); where a
       stored band violates the migration's own CHECKs the section is refused,
       logged, and the school named in data_availability. Coach prompt v35
       (rollback COACHING_SYSTEM_PROMPT_VERSION=v34) — the "score ranges are not
       things we hold" sentence is corrected, the correction RFC 197 declined for
       the right reason. Executed: 3,217 tests, 0 failures.

    test/06.1/one-answer-one-date LANDED as RFC 209 (main@0a7c027f + 92c214f4,
       2026-09-14) — the two shapes this schema kept copying by hand now have one
       definition each. A partly-known date is a real DATE at the start of the
       period plus its precision, and Kotlin sees only PartialDate: the (date,
       precision) pair never escapes the DAO, so no caller can hold a date whose
       value and precision disagree, proven by a guard that sweeps every module
       and permits exactly one file. The answer words are written ONCE:
       answer_status_or_undecided is the base and answer_status is derived from
       it as that list minus 'undecided', so the money spelling and the
       subject-field spelling cannot drift. students migrated in the same run;
       nothing above the DAO changed. Executed: 3,282 tests, 0 failures. Open:
       intended_subject_status is not retyped — RFC 205 had not landed at the
       final rebase, and the two ALTERs are recorded in the migration's own
       closing comment.

    test/05/your-state-already-pays LANDED as RFC 208 (main@75f5fc17 + bba0927a,
       2026-09-14) — the coach can tell a family that their state already runs,
       and often pays for, an admission test inside the school day, inferred from
       the residency state the money profile already holds; it never asks for the
       state again. admission_test_state_programmes is append-only history keyed
       (state, school_year), so a re-read ADDS a row and last year's answer stays
       true about last year; the read and the staleness guard both take the
       newest row per state, which is what makes the guard both firing and
       clearable. 36 of 51 jurisdictions, each from a state agency's own page: 27
       require, 9 offer; 16 ACT, 13 SAT, 7 where the state chose no single test;
       22 say who pays; 7 carry an archive link. 15 jurisdictions have no row,
       and absence means we did not read a source, never that the state runs
       nothing. Five funding claims were found and REJECTED because the page did
       not say the state pays. The state column carries a format rule rather than
       a key into us_states — that codebook is created by a migration and filled
       by the import run, so a migration-time seed points at an empty table
       (proven: 112 of 113 migrations applied, then the seed was refused), and
       test/05.3/own-our-codebooks carries the key (D27). Coach prompt v36.
       Executed: 3,302 tests, 1,616 shell assertions, 0 failures.

    test/05.1/external-documents LANDED as RFC 213 (main@2f9344e3 + 063b8569,
       2026-09-14) — every url we cite now lives in one table with a date beside
       it. external_documents holds a url, whether it still resolves, and the day
       we looked: no type, no publisher, no capture table, all three cut by Ian
       during design. admission_test_state_programmes lost its two link columns
       and points at it — the page required, the archived address optional, the
       two never equal, an archive with no page unrepresentable — filled inside
       the migration from the columns it dropped. NEW TOOL bin/check-link: hand
       it urls, it prints HTTP status codes; it fetches and does not judge, so
       the found/not_found/unknown reading stays with the caller. A real sweep of
       all 43 cited addresses is committed: 34 alive, 0 dead, 9 "asked, no usable
       answer" with the date asked (Arizona, Delaware and New Hampshire among
       them, recorded as blocked-not-gone). NOT ONE WORD THE COACH SAYS CHANGED —
       no service file, no prompt line, no payload key, prompt still v36.
       Executed: 3,351 tests, 0 failures; 1,184 shell assertions including 21 new
       for check-link, none of which resolves a public name.

    test/07/a-score-is-money LANDED as RFC 239 (main@a53b7be3 +
       483ca64b, 2026-09-25) — at eighteen curated public universities a
       test score band IS a price, and the coach now says so: the band,
       the dollars a year, whether a score must be submitted to qualify
       at all, that school's superscore rule FOR SCHOLARSHIP MONEY, and
       the last sitting that still makes its money deadline. Two tables
       (college_automatic_awards + college_automatic_award_bands, one
       row per published CELL so a score x GPA matrix survives) and a
       hand-typed corpus: 60 award rows over 40 schools, 571 cells, 53
       pages read 2026-09-24 and re-answered by bin/check-link. Five
       honest answers, not two: a score moves the money here / this
       school publishes a table a score does not affect / we read the
       page and there is no automatic award / we have not read this
       school / its own rule does not permit a combination. Loaded by
       the INGEST run, not a migration, because colleges arrive from the
       import (D27's failure); the loader REFUSES an unknown unit id, a
       name that disagrees with ours, a GPA the column cannot store
       exactly, a score off the published scale, a band under a
       publishes-nothing row, and a claim with no publisher's words —
       each by file, line and cell. Ian reversed the draft's refusal to
       compute a combination ("Math is easy, right?"): we combine best
       SECTIONS where the school's page accepts it, never totals, never
       across ACT's 2025 three-vs-four-subject change, and never called
       an ACT Superscore. Review's three blocking findings were all
       wrong money that no test caught: a top award priced in WORDS
       ranked as $0, a student below every band treated as earning
       nothing, and a four-decimal GPA rounded by Postgres into another
       tier's row. Coach prompt v45 (rollback v44). Executed: 3,837
       tests 0 failures; 1,154 shell assertions; the committed corpus
       loads into a real database with zero refusals, now a test. Door:
       the chat, plus a report-award-grid block on the family cost
       report that names the school's grid and NOTHING about the
       student's score. Owed re-reads (PROVENANCE.json): Ole Miss
       in-state, Oklahoma, Wyoming, Montana State and Utah still publish
       only their Fall 2026 grid; UVA refuses every client we have.

**Waves 1, 2 and 3 are complete: six slices landed — RFCs 186, 187, 195, 197,
208 and 209.** The question the brief was opened for is answered by a test: for
a student with one school closing Early Action on 1 November, the coach names
SAT 3 October (scores 16 October) as the last usable sitting and **18
September** as the day registration closes — and names the ACT beside it, 17
October with registration closing 11 September, because naming only the SAT
would make the SAT the default noun (D13).

Wave 2 was held, by agreement with the sibling `/chart say-it-once` session,
until its `concise/03/one-rule-one-home` — a pure DELETION pass on the same
prompt file — had landed. A deletion rebased across additions can silently drop
a sentence that landed minutes earlier and no test catches it; an add/add rebase
cannot. Only the deletion needed a window.

    test/08/the-psat-is-money LANDED as RFC 243 (main@f084bbda + feb1ba3a,
       2026-10-10) — the PSAT/NMSQT is a MONEY event and the coach now treats it
       as one: no college ever sees the score, only the junior-year sitting
       qualifies, and Finalist standing is what a named school pays for. Two
       corpora by the two routes the key rule picks — national_merit_cycles
       seeded BY MIGRATION (keyed by competition year, which is the class's
       graduation year, so "what this test is and when it is" is in every
       database by construction) and college_national_merit_awards loaded by the
       INGEST (keyed by colleges(id)). 2 cycle rows, each with TWO citations
       because no page publishes a whole cycle: NMSC sponsors the competition,
       College Board administers it and is the only publisher of the window
       DATES, the Selection Index scale and the sentence that no college receives
       the score. 26 award rows over 17 colleges, 19 automatic, 6 competitive, 1
       verified absence (UNC in its own words); 16 schools publish a real award
       and EIGHT of those publish only words, so the words are the required cell
       and nothing ranks or totals. NO score column and NO cutoff column
       anywhere — NMSC publishes no qualifying score in advance, so what is
       stored is HOW the bar is set plus its own sentence saying it is not
       published. NEW TOOL psat_national_merit: seven timing arms off her own
       stored graduation year, five per-school answers, and a base rate that
       cannot be omitted beside a figure. The ONE proactive moment of this brief
       is a psat_window commitment the sweep writes in code for a junior inside
       her 30-day lead, once in her life, with nine named suppression reasons the
       model cannot propose; a share opt-out does NOT silence it (a dated
       qualification is not an offer). Coach prompt v48 (rollback = the version
       below the pin, PARTIAL — the tool stays registered and the corpora stay in
       the database, so every reactive answer survives). **THE CURATION PASS
       REWROTE THE HEADLINE:** Oklahoma's $153,450 — the number in this brief, in
       the spec and in RFC 243's first draft — is published against FALL 2026
       entry, as are Texas Tech's (2022 deadline still on the page), UT Dallas's,
       Maine's and Ole Miss's. Four of the five schools that made this slice look
       impressive in September could not be seeded on 2026-09-25, which is what
       checked_on exists for. What the coach says instead, each read that day:
       Oklahoma State $154,500 non-resident for 2027-28, ASU $17,500/yr
       out-of-state, USC $20,000/yr, Mississippi State $48,000, Auburn $11,000/yr
       Alabama residents ONLY. Review earned its cost twice on money the tests
       did not catch: the base rate was guaranteed inside each school's price but
       DERIVED at the head, so a junior with an EMPTY COLLEGE LIST — exactly who
       the nudge fires for — got none while the payload still stated the
       sponsor's own $2,500; and a FAILED money-profile read was rendered as "we
       hold no state of residency for this family" in a payload whose own top
       line said "do not tell them it is not on file — we do not know whether it
       is". Executed: 4,199 tests, 0 failures (whole tree on disk 4,298); 83 new
       shell assertions for bin/national-merit-seed. 41 review lenses over four
       tiers, every ledger check exit 0. Door: the chat, both asked and raised.
       **BRIEF 0010 IS COMPLETE** — test/09/the-report-says-it-too (D30) is the
       report half and is a new slice, not part of this one.

## The question

Ian's daughter asked him, in plain words, **"when do I need to take the SATs?"**
— and unicoach could not have answered her. That one sentence is a whole class
of question the product does not serve: _what do I have to do, and by when?_
unicoach today is excellent at **what will this cost me** and knows almost
nothing about **when**. It stores the student's expected high-school graduation
date at signup and never tells the coach; it never tells the coach what today's
date is; and it holds a school's application deadlines and whether that school
weighs test scores, but has no notion of a testing calendar, a test policy, or a
score. The brief asks: **is "when do I test" the right next bet, and if so, what
is the smallest honest version of it?**

A note on the seed question, which is itself a finding: Ian described her as a
**junior in college**. If that is literal, the SAT is the wrong exam entirely
(transfer applications rarely need it; graduate entry means GRE/GMAT/LSAT), and
unicoach — whose every surface assumes a high-school student — would answer
confidently and wrongly. If she is a high-school junior, she is in exactly the
window the SAT question belongs to. **A coach that does not know which one it is
talking to should not be answering timing questions at all.** Gate 1 has to
decide whether unicoach owns that ambiguity or refuses it.

## What the repo actually has today (grepped, not guessed)

| Foundation                                            | Where                                                                                                               | State                                                                                                                                                          |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Expected high-school graduation date                  | `students.expected_high_school_graduation_year/_month/_day` (schema `0005`), variable precision, `NOT NULL` on year | Collected at signup, exposed over REST and admin — **never reaches the coach's context**                                                                       |
| System-prompt assembly                                | `CoachingService.composeSystem`                                                                                     | Injects pending commitments, fit suggestions and the money profile. **No current date. No graduation year. No grade level.**                                   |
| Coach prompt body                                     | `prompts/coach-system-prompt.md` (Markdown since RFC 185, seed v26)                                                 | Says "plan applications and deadlines" in its opening line and then says **nothing** about testing: zero mentions of SAT, ACT, test-optional, or a grade level |
| Application deadlines                                 | `college_deadlines` (RFC 140): round, `offered`, closing/notification month+day, per CDS cycle                      | Live, read by `college_admissions_profile`. Dates are cycle-relative MM/DD, sparse, never fabricated                                                           |
| Does this school weigh test scores                    | `college_admission_factors.test_scores` (CDS **C.704**), 4-value rating                                             | Live, already fetched by `bin/fetch-cds-seed`                                                                                                                  |
| A school's SAT average                                | `colleges.sat_average_equivalent_score` (Scorecard `SAT_AVG`)                                                       | Live, in the search index; ACT is converted in, and it is an **admitted-student average**, not a percentile range                                              |
| Test **policy** (required / optional / blind)         | —                                                                                                                   | **Absent.** CDS section C8 is the natural home; the fetcher does not request it                                                                                |
| Score **ranges** (25th/75th percentile, % submitting) | —                                                                                                                   | **Absent.** CDS section C9                                                                                                                                     |
| SAT/ACT test dates + registration deadlines           | —                                                                                                                   | **Absent**, and no repo precedent for a hand-maintained calendar                                                                                               |
| Merit aid                                             | `college_merit_aid` (CDS H2A)                                                                                       | Live — and merit money is frequently **score-gated**, which is the bridge from testing to unicoach's money core                                                |

## Candidate bets

Ranked after research; listed here as framed.

| # | Bet                                                         | One line                                                                                                                 | Existing foundation                                                         | Cost shape                                                             |
| - | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| A | **The coach knows when "now" is, and who it is talking to** | Inject today's date and the student's graduation date (and therefore their year of school) into the system prompt        | `students` row exists; `composeSystem` is one function                      | Smallest possible — no DDL, no new data source                         |
| B | **A testing timeline the coach can speak**                  | The SAT/ACT calendar and the standard sequence, personalised to the student's graduation year and their list's deadlines | Bet A + `college_deadlines`                                                 | Needs a calendar source; staleness is the risk                         |
| C | **Does this school even want a score?**                     | Per-college test policy (CDS C8) and score range (C9) in `college_admissions_profile`                                    | `bin/fetch-cds-seed` already parses C7/C14/C16/C21/C22 from the same corpus | Medium — new fetch fields, new table or columns (**DDL → Ian's gate**) |
| D | **Scores as money**                                         | Where a score crosses a published merit threshold, say what it is worth in dollars                                       | `college_merit_aid`, the whole money layer                                  | Highest value, weakest public data                                     |
| E | **A deadline/reminder engine**                              | Dated, personalised nudges: register by X, test on Y, apply by Z                                                         | Queue + synthesis nudge machinery exists (`SynthesisService`)               | Largest; a promise the product must keep                               |
| F | **Refuse well**                                             | Detect that the student is not a US high-school applicant and say so plainly instead of coaching them wrongly            | Bet A gives the signal                                                      | Small, and it is the honesty rule this repo already lives by           |

## Success criteria for the decision

1. The answer we ship must be **something Google and ChatGPT cannot give her** —
   personalised to her graduation date, her list, and her money picture — or it
   is not worth building (see brief 0001's differentiator test).
2. It must be **reachable**: name the door (the chat tool, the screen).
3. It must not require unicoach to **assert a fact it cannot source**. A test
   date we hand-typed and a test policy we inferred are both the failure mode
   briefs 0008 and 0009 exist to prevent.
4. It must **degrade honestly** when the student is not who we assume.

## What research found

Three subagents reported; the full cited reports are in `research/`. Four facts
moved the brief.

**1. The generic answer is a commodity; the arithmetic is not.** ACT's own
guidance is fall junior → spring junior → fall senior, and every competitor says
some version of that. What none of them does is the subtraction for one student:
for a **Nov 1** ED/EA deadline the last comfortable sittings are **SAT Oct 3**
(scores Oct 16) and **ACT Oct 17** (scores Oct 27), so the real action date is
**mid-September**, when registration closes — not October. The competitor
weakness is structural staleness: CollegeVine's top-ranked "when should I take
the SAT" article is dated **30 Sep 2016** and still quotes 2016-17 dates. The US
counselor ratio is **372:1** (ASCA 2024-25).

**2. A global "take the SAT" rule would be wrong for most students.** FairTest's
2026-27 survey of 2,247 policies: **7% require** (161 colleges), **86%
test-optional**, **4% test-free**, and the net change this cycle was **one
college**. MIT, Harvard, Yale, Dartmouth, Brown, Caltech, Stanford and UT Austin
require; the UC system stays test-free. So the answer must be anchored to **this
student's list**, never asserted globally.

**3. The unmatchable answer is a MONEY answer.** Alabama publishes an automatic
merit grid where the score band _is_ dollars: SAT 1250 → \$6,000/yr, 1260 →
\$8,000/yr, 1420+ → \$28,000/yr. Same page: scores **must** be submitted to be
eligible, and UA **does not superscore for scholarships** — so "just superscore
it" is wrong there. For Fall 2027 entry scores are due **Dec 4**, which makes
the **Dec 5** SAT too late and **Nov 7** the last usable sitting, with
registration closed **Oct 23**. A ten-point miss is \$8,000 over four years, on
a date the family misses silently. ASU works the same way. That is unicoach's
promise — price truth — not a test-prep pivot.

**4. The data verdicts are better than expected in two places and worse in
one.** Today's date + graduation year in the prompt: **EASY**, no DDL. Score
percentile bands (`SATVR25/75`, `SATMT25/75`, `ACTCM25/75`): **EASY and free** —
already sitting **unused in the committed Scorecard seed CSV** (949 rows). Test
required/optional/blind: **MEDIUM** — not from CDS C8, which is thin and ~13%
junk with only 149 colleges, but from **IPEDS `ADMCON7`**, populated for all
1,972 institutions with an official codebook covering 360 of our 369 CDS
schools. The national test calendar: **MEDIUM** — no machine-readable feed
exists anywhere, so ~30 rows a year hand-maintained, and silent staleness is the
risk. Per-college "last date scores may arrive" (CDS C.8E): **HARD**, 46 rows of
unusable values — do not attempt.

## The bets, ranked

| Rank | Bet                                                            | Verdict across the three reports                                                                                                                                                                                                                                                                                                                                              |
| ---- | -------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | **A — the coach knows when "now" is and who it is talking to** | All three name it first and call it the unblocker. Cheapest thing in the brief: no DDL, no new source, one function and a prompt edit. Today the coach cannot say "you are a junior; the next SAT is in six weeks" because it knows neither half of that sentence. It also disambiguates a high-school junior from a college junior **for free**, from a field already stored |
| 2    | **B — the date arithmetic, anchored to the student's list**    | This is the product. Test date → registration deadline → score release → the school's own deadline, subtracted for this student. Needs a maintained ~30-row calendar with an explicit expiry, because the one thing worse than no date is a 2016 date                                                                                                                         |
| 3    | **D — scores as money**                                        | The differentiator nothing else has, and the reason to build this at all. Weakest public data: automatic merit grids are published per school in HTML and exist in no dataset, so a first version is narrow and hand-curated, or it is dishonest                                                                                                                              |
| 4    | **C — does this school even want a score?**                    | Cheap half is free (percentile bands, already committed); the policy half is a new IPEDS ADM ingest. Required for B and D to be truthful, since 86% of schools do not require a score at all                                                                                                                                                                                  |
| 5    | **F — refuse well**                                            | Not a bet of its own after A: the stored graduation date is the signal, and the refusal is a prompt rule                                                                                                                                                                                                                                                                      |
| 6    | **E — a dated reminder engine**                                | Real value, largest cost, and it makes a promise the product must then keep. A later brief                                                                                                                                                                                                                                                                                    |

## Gate 1 — prioritisation

Defaults are pre-chosen. "Approve" takes all twelve; amend any line.

**D1 — take the bet.** "When do I test" becomes brief 0010's beat, scoped as a
**deadline-and-money engine**, not a testing feature and never test prep.
_Default: YES._

**D2 — date in the prompt first. Ship the cheap half first and on its own.**
Inject today's date and the student's stored graduation date into the coach's
system prompt as slice 1, landing independently of everything below, because
every other slice is blocked on it and it is valuable alone. _Default: YES._

**D3 — refuse a non-high-schooler. The coach must know who it is talking to, and
refuse when it does not.** Derive the year of school from the stored graduation
date; where the student's own words contradict it (a **college** junior asking
about the SAT), say plainly that unicoach coaches US high-school applicants and
does not advise on GRE/GMAT/LSAT, rather than answering confidently. No new
field. _Default: YES — refuse honestly._

**D4 — list-anchored, explain once. Advice is anchored to the student's college
list — but the coach explains the stakes once, unprompted.** Two arms, and both
are required (amended by Ian, 2026-09-10).

_The claim_ is list-anchored: with 86% of colleges test-optional, "you need to
take a test" is false for most students, so a per-school statement is only ever
made from that school's recorded policy.

_The reasoning_ is not. The first time testing comes up, the coach explains — in
its own plain words, not a list of facts — **what a college entrance test is
for, when a student would need one, when they would want one even though no
school demands it, and when they are better off not sitting one at all.** What
is at stake in each direction:

- **Need**: a school on the list requires a score; a scholarship requires a
  score even where admission does not; an NCAA or state-programme rule requires
  one.
- **Want**: a strong score can only help at a test-optional school; it can move
  real money at schools with automatic merit grids; it can offset a weaker
  transcript; the PSAT route to National Merit is money, not admission.
- **Not**: every school on the list is test-optional or test-free and the
  student's time is better spent elsewhere; a score below a school's own
  reported range is usually better withheld where withholding is allowed; the
  cost, the Saturdays, and the stress are real and are not nothing.

The explanation is given **once**, is not repeated at every mention, and never
becomes a lecture that delays the actual answer to the actual question. With an
empty college list the coach may still give the standard sequence, labelled as
general rather than as advice about their schools. _Default: YES, both arms._

**D5 — policy from IPEDS, not CDS. Test policy comes from IPEDS `ADMCON7`, not
CDS C8.** 1,972 institutions with an official required/optional/blind codebook
beats 149 colleges of ~13% junk. _Default: ADMCON7._

**D6 — range, not average. Adopt the score percentile bands already in the
committed seed** — and prefer the 25th/75th **range** over
`sat_average_equivalent_score` wherever we speak to a student, because an
admitted-student average invites a student to read it as a bar. _Default: YES._

**D7 — calendar expires, coach refuses. The test calendar is hand-maintained
data with an explicit expiry, and the coach refuses past it.** ~30 rows a year
(SAT and ACT: test date, registration deadline, score release). Past the expiry
date the coach says it does not know the current dates and points at the
publisher, rather than naming a stale one. This adds a table — **DDL presented
at /ship's gate** (standing D10). _Default: YES, with expiry-refusal._

**D8 — merit grids: narrow and sourced. Score-gated merit money ships narrow and
honest, not broad and guessed.** A hand-curated set of schools that publish an
automatic merit grid, each row carrying its source URL and cycle; the coach
speaks it only for those schools and says plainly that it does not know for the
rest. _Default: YES, narrow — and if the curated set cannot reach a useful size,
this slice becomes a later brief rather than a vague one._

**D9 — no prep, no prediction, no odds. Never test prep, never score prediction,
never chancing.** Khan Academy is free and better; chancing engines are a
crowded, low-trust market. _Default: YES — standing._

**D10 — infer school-day testing from state. Infer school-day testing from the
state we already hold; ask only what the state cannot answer.** (Amended by Ian,
2026-09-10 — "can the coach infer this from residence?" Yes, and it should.)

The money profile already records the family's **state of residency**, and
`composeSystem` already puts it in the coach's context for the in-state price.
School-day testing is a **state-level** fact, so the same field answers it: ~12
states require every junior to sit the ACT, ~14 plus DC contract SAT School Day,
and three (OH, SC, TN) let the district choose. That is a ~50-row table keyed by
state, with the same expiry discipline as D7, because these programmes change
yearly and the best public list we found is secondary (PrepScholar, Oct 2025) —
each row wants a state-DOE source URL before we assert it.

So: **never ask for the state again** (D4's ethos and the money profile's rule),
and where the state is unknown, the residency question already exists and
already pays for itself twice. The one thing the state cannot settle is whether
**her particular school** participates and on which date — that, and only that,
is the single optional question, never gating, "I don't know" accepted.
_Default: YES — infer from state, ask only the school-level detail._

**D11 — no reminders this beat. No reminders or notifications in this beat.** A
dated nudge is a promise the product must keep; it needs its own brief.
_Default: DEFER._

**D12 — store her own scores. Store the student's own test scores.** Without a
stored score the coach cannot say "you are 10 points from the next money band"
in a later session. New table, so **DDL at /ship's gate**; invited, declinable,
never gating, and removable — the money-profile pattern. _Default: YES, store._

**D13 — the whole test family, not the SAT. The scope is college entrance tests,
not "the SAT".** (Added by Ian, 2026-09-10.) Every slice, every table and every
sentence of copy is written for the family of admissions tests, with the SAT as
one member and never the default noun.

- **SAT and ACT are first-class and symmetric** — both calendars, both
  registration deadlines, both score-release lags, both fee-waiver programmes
  (they are separate programmes with different rules and different fees; ACT's
  base fee is \$70). The coach never assumes the SAT; in ~12 states the ACT is
  the test the student will actually sit.
- **The CLT (Classic Learning Test)** is named where a school on the list
  accepts it and nowhere else. Our research did not cover it, so its acceptance
  list is an open question the spec must answer before any slice claims it.
- **English-proficiency tests** (TOEFL, IELTS, Duolingo English Test) are a
  different requirement with different triggers, and belong here as a named,
  separate arm rather than being folded into "entrance tests".
- **PSAT/NMSQT** is in scope as a **money** event, not an admissions one: it is
  school-administered in an October window, the student does not register, and
  **only the junior-year sitting qualifies** for National Merit.
- **AP and IB exams are explicitly out of scope** as entrance tests. They are
  credit and placement, a different question that deserves its own brief.
- **Graduate and professional tests (GRE, GMAT, LSAT) stay out of scope**, and
  D3 is how we say so honestly rather than by silence.

_Default: YES — test-family scope, SAT never the default noun._

**D14 — four score-mechanic guardrails. The four score mechanics are encoded as
explicit guardrails.** (Added 2026-09-10, from the research's "most likely to be
got subtly wrong" list.) These are where a fluent model produces confident,
costly errors, so they are written as rules rather than left to the model's
judgement:

1. **Score Choice is per test date, and does not apply to the free score sends
   chosen at registration.** Advising "just send your best" while four free
   sends are already queued is a real, irreversible error.
2. **Superscoring is the college's policy, not the testing agency's** — and not
   ours to assume. Dartmouth superscores automatically; Yale forbids recomputing
   an ACT composite across the old and new Science formats; Alabama does not
   superscore **for scholarship purposes** at all, which is the exact case where
   the advice is worth thousands of dollars. The coach states a superscore rule
   only from a recorded per-school policy, and otherwise says it does not know.
3. **Self-reported scores are widely accepted, with revocation risk** — Stanford
   reserves the right to withdraw an offer where official scores disagree.
4. **A College Board fee waiver is one per lifetime and carries unlimited free
   score sends**; ACT runs a separate programme with its own rules. Waivers are
   requested ideally 1-2 weeks before the registration deadline, which makes the
   waiver its own earlier deadline.

_Default: YES — guardrails, not judgement._

**D23 — one partial-date storage pattern. Variable-precision dates are stored as
a real `DATE` plus a precision, and spoken in Kotlin only as `PartialDate`.**
(Ian, 2026-09-14: _"Option C is fine. But we must use a custom type in kotlin,
never a data clump of the data and the precision. I think we already have this
type in kotlin, right?"_ — yes: `PartialDate` in `:db` models, a sealed
interface of `YearOnly` / `YearAndMonth` / `FullDate`, whose own KDoc already
names test dates as its intended reuse.)

Three hand-rolled copies of the same shape exist today —
`students.expected_high_school_graduation_*` (schema `0005`), `CdsMonthDay`, and
the fourth that `test/06` was about to add. The Kotlin half was already shared;
only the SQL half was copied, and that asymmetry is the whole defect.

The storage becomes a `DATE` normalised to the start of the period plus a
`date_precision` domain (`year` | `month` | `day`), with one shared
`is_period_start` CHECK. It is the only option a database can USE: "not in the
future", ordering, a plain index and a join against the test calendar are each
one predicate. A composite type was rejected because the DAOs are hand-written
JDBC and a composite returns as a Postgres wire string — a hand-rolled format to
remove a hand-rolled format.

**The binding constraint on the Kotlin side:** the `(date, precision)` pair
**never escapes the data-access layer**. It is decoded into `PartialDate` on
read and encoded on write; nothing above the DAO sees a precision column, and no
caller can hold a date whose precision and value disagree. That is also what
disarms this shape's one danger — a stored `2026-01-01` looking precise —
because the only thing above storage is a `YearOnly` with no month to leak.

**D24 — one declinable-answer component, and the status is derived rather than
stored.** (Ian, 2026-09-14: _"Let's stop duplicating this. We should refactor to
a single pattern / component."_)

`AnswerStatus` is one Kotlin enum with one decode site and four hand-written
`TEXT + CHECK IN` copies in the schema, plus a renderer written against
`MoneyProfile` specifically. The component is a `DOMAIN answer_status AS TEXT`
with the vocabulary written once — the argument this repo already makes in
`0054`'s own comment ("declared ONCE as a DOMAIN … not eighteen edits") — a
`value_iff_answered(status, has_value)` function instead of four copied
expressions, and a renderer taking `(status, value, label)` rather than a
profile.

And where the values live in a SIBLING table, the status is **not stored at
all**: persist only the refusal, and derive "answered" from the existence of a
live row. CLAUDE.md already says a derived figure is computed at read time and
never stored, and deriving it makes the contradictory state unrepresentable
rather than merely policed by a service and a test.

Both components are carried by `test/06.1/one-answer-one-date`, which BLOCKS
`test/06`.

**D26 — a citation is a DOCUMENT, and documents get their own table.** (Ian,
2026-09-14: _"We have the need to store links to external documents. There are
also archives (what's an archive?). Let's model this correctly in a separate
table: external_documents. There will be a new table that maps multiple sources
(eg for Utah) between these two tables. We'll need to do a cleanup slice to move
all schema that points to external documents to use this new table."_)

Eight tables carry their own URL columns today: the three CDS tables (`0054`),
`policy_parameters` (`0077`), the canonical money tables (`0083`),
`source_documents` itself (`0087`, plus RFC 200's link-rot columns), the test
calendar this brief landed (`dates_source_url`, `score_release_source_url`), and
the state programmes this brief is building. `source_documents` was the first
attempt at de-duplicating them and is college-keyed, so it cannot hold a
citation about a jurisdiction.

**What an archive is, written down because the question was asked and the answer
shapes the model.** The live URL is the publisher's page as it is now. An
archive URL is a third-party capture of that same page **as it was on the day we
read it** — almost always the Internet Archive. It is not a spare link. It is
**evidence with a date on it**, and we already need it for three reasons that
have each bitten: pages that refuse us (Arizona, New Hampshire), pages that move
or die (measured 2026-09-13 across 441 college documents: 417 found, 15 not
found, 9 unknown), and pages that silently change.

So a capture is not an attribute of a document; it is a thing we made OF a
document, on a date, possibly more than once. Today's single `archive_url`
column cannot say when it was taken, which is the one thing a dated citation
needs.

**The shape:** `external_documents` (the published thing, its publisher, its
link-rot state and when we last checked), `document_captures` (one row per
snapshot, with its date), and **one small join table per owner** — never a
polymorphic owner column. Every citation today is
`NOT NULL REFERENCES
colleges(id)`; a generic owner would downgrade that
guarantee to a check constraint for the convenience of one new caller. The join
carries WHICH CLAIM each document supports, which is the fact Utah needs: its
statute backs the requirement and the grade, its board rule backs the test.

Carried by `test/05.1/external-documents`, with the migration of the other eight
tables in `test/05.2/one-home-for-every-citation`.

**D27 — unicoach owns its codebooks, so unicoach populates them, at migration
time.** (Ian, 2026-09-14, diagnosing a blocked run rather than accepting its
workaround: _"`filled by the data-import run` this is the problem. Unicoach owns
the us_states table. so it should populate it."_)

`test/05` asked for a foreign key from its state column into `us_states`, and it
could not have one. `us_states` is CREATED by a migration and FILLED by the
data-import run, so a seed that arrives at migration time points at an empty
table — the run's suite never reached a test, and 112 of 113 migrations applied
before the seed was refused with
`Key (state)=(AL) is not present in table
"us_states"`. That is true of a fresh
production database too, not only the test one; the two tables that already key
into `us_states` escape it because their rows also arrive at import time.

The codebooks are ours. `0060` creates eleven of them — `us_states`,
`ipeds_regions`, `nces_locales`, the two Carnegie tables, religious
affiliations, athletic associations, football conferences,
`admission_test_policies`, `cip_codes`, `codebook_sources` — from authored
files, and things already key into them (`colleges.state`, `colleges.locale`,
the search index's region, locale and test-policy columns). So the fix is not a
new mechanism: it is the pattern this brief invented twice for its own
hand-maintained data — a committed file, a GENERATED seed migration — applied to
the tables that had it backwards.

**Interim, approved by Ian in the same breath:** `test/05` ships with the
two-letter format rule on the column, and its own test that reads every state
code in the committed file against the published codebook file. A typo still
fails the build, in the file where the fix is rather than at migration time. The
key arrives with `test/05.3/own-our-codebooks`.

**D28 — spend your time solving real, solvable problems.** (Ian, 2026-09-14,
after cutting the fourth proposed mechanism of one afternoon: _"You're trying to
solve a problem that is not really a big problem, and it's hard to solve."_)

Four mechanisms were proposed and cut in this brief on one day: a captures
table, then a capture EDGE, then a self-reference, then a lint that grepped SQL
files for a `GENERATED FILE` header. The header lint was the worst, and its
failure is instructive: it was **unsolvable by that means** — a string match
cannot tell a file that MEANS the header from one that merely SAYS it — and it
duplicated a check that already existed, since a test already asserts the
committed seed is byte-identical to what the generator produces.

The rule has two halves and both bite: **is the problem real**, and **can this
mechanism actually hold it**. It is the same rule as D26's outcome ("a flag with
no caller is a concept the product does not have yet") pointed at effort instead
of at features.

Applied immediately: the guarantee that a citation cannot exist without evidence
needs ONE migration, not a lint and not a deletion — see
`test/05.4/no-citation-without-a-check`.

**D29 — unicoach accommodates external data: it ADDS the new rows, and a human
reviews them.** (Ian, 2026-09-15, said twice: _"The idea here is that the
Unicoach system should accommodate the external data. If it does not, it should
probably be updated."_ and _"Typically, Unicoach should just add the new rows.
But those new rows should be reviewed by a human."_)

This reverses the framing `test/05.3` proposed, while keeping its answer. That
run argued the import must keep running because it is _"the only thing that can
REFUSE a bad codebook change"_. The import's job is the opposite: **it is how
new external data arrives**, not a gate standing between us and the publisher.
When IPEDS publishes something new, unicoach takes it; if our code cannot hold
the new value, **that is our bug to fix**, not grounds to refuse the data.

Three consequences, and the second is the one that keeps it honest:

- **A migration seeds a baseline**, so a freshly built database is complete and
  can be keyed against (D27). The import then ADDS what the publisher has since
  published.
- **The human review is the committed diff.** `db/data/codebooks.json` is
  committed, so an import that adds rows produces a reviewable diff before
  anything ships. That review already exists; a second review mechanism must not
  be built for it (D28).
- **Refusals shrink to what the database cannot express.** Deleting a label that
  thousands of college rows still point at is a FOREIGN KEY violation once the
  keys land — the database refuses it and no script needs to. What remains is
  genuinely ours: an archive whose bytes no longer match their recorded
  fingerprint, and a sentinel arriving dressed as a real value.

**Where this brief knows it is not yet true, recorded rather than discovered
later:** today an IPEDS code we do not recognise is DROPPED, and a search test
relies on it — a missing region proving an unknown code is discarded safely.
Under D29 that is us deciding the publisher is wrong. `test/05.3` measures it
and names the cost; changing it is a follow-on slice, not that run's work.

## Corrections to the record (2026-09-10)

Written down because the brief asserted them to Ian before they were verified.

1. **"You cannot retake it, and there is no recourse" was overstated.** The
   junior-year sitting is genuinely the only qualifying one — PSAT 10 and the
   sophomore sitting explicitly do not count — and there is one in-school window
   a year, so there is no retake. But NMSC operates an **alternate entry** route
   for a student who misses it through illness or emergency: a letter to
   `ae@nmerit.net`, received or postmarked by **April 1, 2027** for this cycle.
   The coach must say that. "Missed it, and there is a route, and the route has
   its own deadline" is a better and truer product than "missed it, too bad".
2. **nationalmerit.org was never blocked.** The first agent's 403 was a
   **User-Agent filter**, not Cloudflare; a browser User-Agent returns 200 on
   every programme page. Every NMSC claim in this brief is now verified from
   NMSC's own 2028-cycle pages.
3. **The money is not NMSC's.** NMSC's own award is \$2,500 one-time, and its
   college-sponsored awards are capped at \$500-\$2,000/yr and require naming
   that school first choice. The large numbers are **university-owned automatic
   packages**, verified from each school's own page: Oklahoma publishes
   \$153,450 non-resident / \$146,850 resident; Alabama ~\$180-200k
   out-of-state; Texas Tech 100% of cost of attendance; UT Dallas ~\$110k;
   Florida State ~\$91,808 out-of-state; Idaho >\$85k; USC \$80k; ASU \$60-70k;
   Maine 100% tuition with **no** first-choice requirement; Auburn \$44k **for
   Alabama residents only**. UCF and Tulsa are competitive, not automatic.
   Baylor, Fordham and Kentucky publish **no** National Merit award on their
   live sites.
4. **The base rate must be visible, and the cutoff is per-state.** 3.5M sit the
   test, 1.2M enter, 16,000 reach Semifinalist, 15,000 Finalist, 6,700 win — a
   ~1.25% base rate. The **Semifinalist cutoff is allocated per state**
   (Commended is national), which means the stored residency state sits exactly
   on the line the money is behind, and the coach must never quote a national
   cutoff as if it were hers.
5. **Currency risk is real and must be designed for.** Auburn cut its award to
   in-state only; Texas Tech's page still carries a 2022 date; three schools
   publish nothing. Every curated row therefore needs `source_url`, `cycle` and
   `checked_on`, with an annual human re-check — these pages are re-keyed each
   cycle, so **this cannot be scraped unattended**. That is D7's expiry
   discipline applied to D8's dataset.

**Effect on the approved decisions:** none is reversed. D13's PSAT arm survives
with the alternate-entry route added and the base rate made visible; D8's
curated set is confirmed at **10-11 schools with dollars today**, plus 3 worth
recording as "no published award".

## What the runs taught

**From RFC 186 (`test/01`, 2026-09-11):**

- **The `:851` early-return trap was real, and the test for it failed before the
  fix.** Worth a standing check on any slice that adds coach context: a block
  that is not ALWAYS composed is a block absent for exactly the new student the
  feature is for.
- **The repo's defaulted-`Clock` pattern is wrong when a service fixes the
  zone.** `LocalDate.now(clock)` reads the INJECTED clock's zone, so the test
  that pinned a UTC instant to prove "we use Eastern" rendered the UTC date and
  **passed for the wrong reason**. Tier-0 review caught it. The landed shape is
  `clock: InstantSource` plus
  `LocalDate.ofInstant(clock.instant(), COACH_ZONE)`. **`test/04`'s calendar
  arithmetic must use the same seam, never `LocalDate.now(...)`.**
- **`HighSchoolYear` is the API the blocked slices asked for**:
  `of(today,
  graduation)` plus `label` ("grade 11 (junior year)"), `grade`
  (`Int?`, null for the two out-of-range states) and `clause`. `test/05` and
  `test/08` gate on JUNIOR and can ask `year == JUNIOR` directly rather than
  re-deriving it.
- **D15 is now code.** `COACH_ZONE = America/New_York`, and the words the coach
  reads ("Eastern Time") are DERIVED from the constant, so they cannot drift
  apart. The same phrase inside `prompts/coach-system-prompt.md` is
  hand-maintained and flagged as such.
- **A day- or month-precise graduation date already behind today is
  `PAST_GRADUATION` immediately**; a year-only date still defers to the 1 July
  rule. That is what makes D3's refusal fire from data rather than from the
  model's guess.

## Open items (declined in run, none blocking)

- **Nothing range-checks the stored graduation year at input.** A typo like 2082
  renders as a confident "not yet in high school". Making the derivation throw
  would fail every turn for that student, so the fix belongs at the
  signup/profile boundary — **brief 0007 territory**, not this brief.
- `composeSystem` still inlines the commitment / fit / money block text while
  the date block is a named helper. Declined as scope: it rewrites code
  `test/02` was editing concurrently.
- `monthName` duplicates `CollegeAdmissionsChatTool.datePhrase`. Left alone
  deliberately: different input types, and "not recorded" (our gap) vs "not
  reported" (the source's) are different words on purpose.
- `FederalAidPolicyService.currentAwardYear` reads its July-1 boundary in UTC
  while the coach now reads Eastern, so on the evening of 30 June the two can
  name different days. Another feature's calendar decision.
- **A wording question for Ian:** the block says "on 15 June 2028" — day before
  month. Unambiguous, and it is what the gate approved, but a US family writes
  "June 15, 2028". One word changes it.

## Learning candidates for /principle (queued, not actioned)

From RFC 186, the three judgement calls no corpus rule covered: (a) when a
service fixes the zone, take an `InstantSource`, never a `Clock`, and never call
`LocalDate.now(clock)`; (b) if a caller branches on an enum constant to fix its
wording, the wording belongs on the constant; (c) `grep -c "@Test"` counts
comments — the JUnit XML `tests=` attribute is the only count that means
anything.

**From RFC 187 (`test/02`, 2026-09-11):**

- **Two prompt defects were caught by REVIEW, not by the author, and both were
  in copy written from D4.** (a) The block banned describing the list as a whole
  as test-optional, then ordered exactly that aggregate claim one paragraph
  later. (b) The explain-once copy instructed school-specific claims no tool can
  source — scholarship score rules, NCAA rules, "a school's own reported range"
  — while the line above it forbids answering from memory. Both rewritten: the
  aggregate is now BEST EFFORT (say it from the schools whose policy we hold,
  say how many that covers, name the ones we do not), and each arm of the
  explanation is labelled general with "go and check that school's page".
  **Carry this into `test/04` and `test/05`, which write more of this copy: D4's
  arms are reasons to CHECK, not facts we hold.**
- **A doc comment that states a count is a claim.** `readInSession`'s KDoc said
  the read costs five statements; the batching test only asserted that a
  five-college list costs the same as a one-college list, so both stayed green
  while the true count was SIX. Now asserted absolutely against a named
  constant.
- **For `test/03`:** the four sections are independent and wire order is
  `AdmissionsField` declaration order — money, factors, test_policy, deadlines.
  A score band belongs beside `test_policy`, and the service's holder is now
  `AdmissionsRows` with one field per table, so a fifth read is one field and
  one line.
- **The land-lock gap, recorded here because it cost 16 minutes of gate time.**
  `ship-land` takes the repo-wide lock; a plain `docs:` commit to `main` does
  not. Four hook runs were invalidated by three `product/**` commits from a
  sibling session that never contended for the lock. The run's own summary:
  _"the lock serialises gates against gates, not against doc traffic."_ The fix
  under review generalises an exemption `ship-land` ALREADY grants in one case —
  its own RFC commit. The write-up is owned by brief 0009.

## Learning candidate, strongest of the wave

No principle in the review corpus covers **reviewing model-facing copy for
self-consistency and sourceability** — which is exactly where RFC 187's two most
valuable findings came from. A prompt block is code the model executes; it can
contradict itself two paragraphs apart, and it can instruct a claim no tool can
source. Nominated to /principle.

## Process changes adopted mid-brief (2026-09-11)

Both fixes came out of this brief's own land failures and are now in force. They
change how the remaining slices work, so they are recorded here rather than only
in the sibling brief that owns the write-up.

- **RFC 193 — `ship-land` has a docs-only fast path.** A base that advanced ONLY
  by commits touching nothing but `product/` no longer costs a second gate run:
  the run is replayed onto that tip and lands, recording
  `LAND_FAST_PATH=docs-only` and the classified commits. It skips the **gate**,
  not the ancestry check. Anything else — one code path in the range, a merge, a
  conflicting replay — falls back to the old refusal. Born from `rfc-187`, which
  passed the full hook four times and was invalidated four times by doc traffic
  that never took the lock.
- **RFC 194 — a commit whose staged paths are ALL under `product/` skips the
  test half** of the pre-commit hook; only `bin/format -c` runs, and the hook
  logs the scope it chose by name. **Measured on this brief's next doc commit:
  seconds instead of ~4 minutes**, with the log line
  `gate scope: product-docs-only`. Such a commit records no `ship-verified`
  marker, by design.

**What this changes for the remaining slices.** This session no longer
negotiates a commit window with sibling sessions before writing the ledger or
`STATUS.md`; it commits immediately after each land. Live runs are told so
explicitly, because a base that moves under them by `product/**` is now absorbed
rather than fatal.

**What it does NOT fix, and every slice here still has to respect:**
`bin/format -c` is **whole-tree**, so another session's unformatted, unstaged
file still fails this session's commit — the fast path makes that an 8-second
discovery instead of a 4-minute one, which is a thirty-fold improvement in the
COST of the failure and none at all in its frequency. Hence the standing
practice: **run `nix develop -c bin/format -c` before spending a commit.**

## Decisions after gate 2 (appended, never edits)

Committed decisions are immutable; a changed decision is a new numbered one.
These were taken by Ian on 2026-09-12 in EXECUTE, and bind every remaining
slice.

**D21 — one date seam, US format. Dates are formatted in ONE place, and the
format is US-centric.** Ian: _"Dates are tricky... we should have a single place
where we format dates. And we should start with a US-centric format e.g. Sep 12,
2026."_

The repo currently renders a user-facing date in at least four ways, and this
brief added one of them:

| Where                                             | Renders                                  |
| ------------------------------------------------- | ---------------------------------------- |
| `CoachingService.monthName` (RFC 186, this brief) | `15 June 2028` — day before month        |
| `CollegeAdmissionsChatTool` (RFC 148)             | `November 1`, no year by design          |
| `admin-web` `CellRender.DATE_FORMAT`              | `MMM d, yyyy` — **already Ian's format** |
| `IpedsLoader.CLOSED_AT_FORMAT`                    | `MM/dd/uuuu` — parser, not user-facing   |

So the target format already exists in the tree and is not a new invention. The
decision is **one seam, not four**, at `Sep 12, 2026`, with the
partial-precision forms derived from the same place rather than hand-written
beside it. It is carried by a new slice, `test/04.5/one-date-seam`.

**D22 — no calendar pressure, no single user. This brief is not delivered
against a calendar, and it is not designed for one user.** Ian: his daughter is
a **high-school junior**, and _"she's just an example / test user. Do not design
specifically for her."_ And on whether to pull the PSAT slice forward because
its October window is near: _"No. We are not trying to deliver by certain dates.
We'll be done when we're done."_

Two consequences, both binding:

- **Slice order stands at D18.** No slice is resequenced because a real-world
  date is approaching. `test/08` stays where it is.
- **No copy, acceptance criterion or test may encode this student.** A junior in
  a particular state with a particular list is a worked example for reasoning
  about the product, never a shape the product is fitted to. Where a slice needs
  a concrete case, it says so as an example and carries at least one case that
  is deliberately unlike it — a senior, an empty list, a state with no
  school-day programme, a student past graduation.
  test/05.4/no-citation-without-a-check LANDED as RFC 220 (main@c1f50155 +
  fe61b1a9, 2026-09-15) — a web link can no longer be stored unless somebody
  says what happened when they opened it and on what day. url_status loses its
  DEFAULT (it was already NOT NULL — the default was the hole, which the run
  corrected against my own dispatch), url_checked_on becomes NOT NULL, and the
  migration REFUSES a dateless database rather than backfilling one: a made-up
  date is the check that never ran looking like one that passed. "unknown WITH a
  date" stays perfectly storable — nine of our rows say it. Re-verified against
  the live migrated database: 63 documents, 0 without a date, and zero would
  have been refused. The table holds 63 rather than 43 because the FAFSA corpus
  (RFC 215) landed into it the same day. Executed: 3,489 tests, 0 failures.
  Door: none. No prompt version cut. test/04.5/one-date-seam LANDED as RFC 227
  (main@758b28e3 + 2ae5fdbd, 2026-09-16) — every date a PERSON reads now comes
  from one seam, in the US shape: Sep 12, 2026, Sep 2028, 2026, and a year-less
  Nov 1 stays year-less. The coach, the testing timeline's English sentences,
  the public explainer pages and the admin tables all read it; wire values stay
  ISO. One new sweep in :db refuses a date pattern, a month-name lookup or a
  month-name LITERAL outside four permitted files, each exception with a written
  reason, and the guard lives in a different module from the seam. Coach prompt
  v39 (rollback v38) carries the two-line date instruction. Executed counts in
  the archived report. Door: every surface that already said a date.
  test/05.3/own-our-codebooks LANDED as RFC 229 (main@0f38fce8 + aa0e668e,
  2026-09-16) — the published codebook tables now install WITH the database at
  migration time, generated from db/data/codebooks.json by bin/codebook-seed, so
  a freshly built database is complete before anything imports. Both state
  columns that always belonged in the state list —
  admission_test_state_programmes.state and fafsa_filing_deadlines.state — now
  really key into us_states, and both interim `^[A-Z]{2}$` format rules are
  DROPPED rather than left beside the key. The acceptance test proves a database
  built by migration ALONE holds all 2,015 codebook rows and refuses 'ZZ'. D29
  governs the import: it keeps running and ADDS the publisher's new rows; the
  committed diff is the human review; refusals are only the fingerprint and
  sentinel cases. **Follow-on, measured and NOT changed here:** a stored code
  with no codebook row is silently turned into NULL where the search index is
  rebuilt (CollegesDao.kt:3018-3020), pinned by two tests — under D29 that is us
  deciding the publisher is wrong, and it needs a data-derived provenance marker
  as its own slice. Executed: db 839 tests 0 failures; codebook-seed harness
  16/16; db-scripts-tests 113/113. test/05.2/one-home-for-every-citation LANDED
  as RFC 228 (main@adc49582 + 646525d2, 2026-09-16) — every cited url now lives
  in external_documents with a status and a date, which makes "every stored link
  has been checked" a property of where the data lives rather than a rule
  somebody remembers. source_documents, policy_parameters and
  admission_test_administrations dropped their own url columns for foreign keys;
  bin/cds-link-check became bin/cds-link-sweep (keeps the two-pass judgement,
  fetches through bin/check-link, sweeps BOTH the page and its mirror — the 446
  mirrors had never been checked once); bin/check-link gained -g; and a schema
  guard fails the build when any table outside external_documents declares a
  url-shaped column. The one operator step: a sweep of 887 urls, committed — 861
  found, 13 not_found, 13 unknown, every row dated. No coach copy changed, no
  prompt version cut. Executed: 3,591 tests 0 failures; 1,142 shell assertions;
  45 calendar-seed assertions. test/06/your-scores LANDED as RFC 207
  (main@edcdd62c + 19d29e93, 2026-09-17) — the student's own test scores,
  self-reported and labelled as such: admission_test_score_declines stores the
  refusal and NOTHING else (status derived via AnswerStatus.of, never stored),
  and admission_test_scores stores one row per component per sitting — the scale
  and the SAT's ten-point step are properties of the COMPONENT, and the sitting
  date is RFC 209's DATE+precision shape decoded to PartialDate only in the DAO.
  The update_test_scores tool records, corrects, removes and declines in
  conversation, and the coach context block remembers them across sessions. No
  superscore stored or spoken; no source column; soft delete. Coach prompt v41
  (rollback v40). Executed: 3,742 tests, 0 failures.

**D30 — the report half is a slice, not a memory. The National Merit package
reaches the family cost report, and it does so as its own slice.** (Ian, at the
RFC 243 approval gate, 2026-09-25: _"Fine with Option A. But I want to be sure
we do this. Can you write this to an existing brief so we do it via /slice"_.)

`test/08/the-psat-is-money` was scoped to the chat, because the chat is the door
its acceptance criteria name and because a parent-facing block on a shareable
page is a second decision with its own privacy surface. Option A was taken —
chat only in that run. The report half is therefore written into `spec.md` as
`test/09/the-report-says-it-too`, BLOCKED on `test/08`, so `slice-board` prints
it and it is dispatched like any other slice. The rule this applies is the one
0001 D12 already states: a product question surfacing mid-run goes back to the
product layer as a numbered decision or a new slice, never as a note in a run's
scratch — `.scratch/` is gitignored, and an unwritten commitment is how folklore
starts.
