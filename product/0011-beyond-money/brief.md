# Brief 0011 — Beyond money: the rest of the college decision

Status:

- Phase: **EXECUTE** — `spec.md` written, **gate 2 APPROVED by Ian 2026-09-11**
  (verbatim: "approve"). Nine slices `beyond/01`-`beyond/09`, decisions D24-D32.
  `slice-board` parses all nine clean (exit 0) and reports `beyond/01`, `02`,
  `04`, `06`, `09` READY.
- DISCOVER: four parallel agents, all reported 2026-09-10 —
  `discover/prompt-and-wire.md`, `discover/field-of-study.md`,
  `discover/student-profile.md`, `discover/surfaces.md` (879 lines, 47
  corrections; the load-bearing 16 are tabulated in `spec.md`'s preamble).
- Gate 1 (prioritisation): **APPROVED by Ian 2026-09-10 (verbatim: "Okay. I
  approve this gate") — 23 decisions.**
- Gate 2 (spec): **APPROVED by Ian 2026-09-11** (verbatim: "approve").
- Handle: `beyond` (slice IDs `beyond/NN.S/name`).
- Ledger: see the **Ledger** section at the end of this brief.

## The question

Ian: _"We've focused a lot on the money part of the college decision. What else
is important in the college decision? How can we add this to the product?"_

Nine briefs in, unicoach is a money product. Briefs 0001, 0003, 0005, 0006 and
0008 built a canonical money store, a price a specific family would actually
pay, a cost split into parts, price-ranked search, a shareable Family Cost
Report, and an honesty layer that says who published each figure, how soft it is
and what year it describes. That is a real differentiator and it is close to
finished — brief 0008 is COMPLETE and brief 0006 has one deferred slice left.

Money is one axis of a decision families make on several. The question this
brief answers is **which non-money axis earns the next beat**, and what the
first slices of it are. The bar is the same bar money had to clear: _a real
family question, answered from a cited public source, reachable through a door
that exists, and refused honestly when the data cannot support the answer._ An
axis we can only answer with LLM free-association is not a candidate — it is a
risk (see the `unicoach-today` research report, which audits what the coach is
allowed to say today about non-money topics).

## What the repo already has (grepped, not guessed)

Non-money data is already ingested and, in several cases, already searchable.
This is the foundation the candidates below are ranked against.

| Foundation                                                                                                                                                                                                  | Where                                                                                   | State                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `colleges` outcome/selectivity columns — `admission_rate` (ADM_RATE), `sat_avg`, `graduation_rate` (C150_4), `median_earnings` (MD_EARN_WNE_P10), `undergrad_enrollment`, `pct_pell`                        | `db/schema/0015.create-colleges.sql:46-55`                                              | populated from College Scorecard                                          |
| IPEDS institutional attributes — Carnegie class + size, locale/CBSA, religious affiliation, ROTC, study abroad, housing + capacity, application fee, athletic association, football conference, test policy | `db/schema/0055...sql:18-77` (`college_ipeds`)                                          | populated (RFC 144)                                                       |
| 6-digit program census — bachelor's first majors by CIP with award counts                                                                                                                                   | `db/schema/0055...sql:92-114` (`college_programs_census`), `cip_codes` seeded in `0060` | populated (RFC 144)                                                       |
| CDS admissions — `college_admission_factors` (what a school says it weighs), `college_deadlines`, `college_merit_aid`                                                                                       | `db/schema/0054.create-cds-admissions-tables.sql`                                       | populated from the CDS seed                                               |
| The search index — filters/sorts on enrollment, admission rate, completion rate, test policy, religion, Carnegie class/size, ROTC, study abroad, locale, region, sector                                     | `db/schema/0064...sql:82-150`                                                           | live (RFCs 147/150), reachable via `search_colleges` + `similar_colleges` |
| Admissions chat door                                                                                                                                                                                        | `service/.../coaching/admissions/CollegeAdmissionsChatTool.kt` (RFCs 140/148)           | live                                                                      |
| The fit lens — a between-sessions pass that proposes one real unmentioned school with a rationale, `UNIQUE(student_id, college_id)` novelty backstop                                                        | `db/schema/0031.create-fit-lens.sql` (RFC 98)                                           | live                                                                      |
| Aid forms and deadlines a school requires                                                                                                                                                                   | `aid_forms` / `aid_form_requirements` (RFC 170)                                         | live                                                                      |
| **Not ingested**: College Scorecard **field-of-study** file (earnings and debt by CIP x credential), IPEDS completion by subgroup, transfer-out, Clery safety, class size, faculty                          | —                                                                                       | candidate work                                                            |

The honest summary: **unicoach can already filter on many non-money attributes
and already stores the raw material for programs and admissions — but almost
none of it is shaped into an answer to a question a family actually asks.** The
money work is the template: `shape/04` turned a store into an answer, and that
is what any of these candidates needs.

## Candidate bets

Each is written as the family's question, not as a feature name. "Foundation" is
what exists today; "the hard part" is the thing that decides whether it is a
slice or a beat.

| # | The family's question                                                                                     | Foundation today                                                                                     | The hard part                                                                                                                                                                                                     |
| - | --------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A | **"Will I get in?"** — is my list balanced, what is a reach/match/likely for _me_                         | `admission_rate`, `sat_avg`, `college_admission_factors`, `test_policy`, `college_deadlines`         | Honest chancing is nearly impossible per-student from public data; the entire industry does it dishonestly. Requires new student profile fields (GPA, tests). A refusal-shaped version may be the honest product. |
| B | **"Will I finish?"** — completion risk for a student like me, and the undermatch trap                     | `graduation_rate` (C150_4) already in the index                                                      | Needs disaggregated completion (Pell / first-gen / race / part-time) to say "students like me"; school-level rate alone invites a wrong inference.                                                                |
| C | **"Will it pay off?"** — earnings and debt **by major**, not by school                                    | school-level `median_earnings` only; `college_programs_census` gives the CIP inventory               | The Scorecard field-of-study file is not ingested. This is the axis with the strongest research support and the clearest "one school-level number is a lie" story — the same story money told.                    |
| D | **"Can I even study what I want there?"** — programs offered, and how real they are (award volume)        | `college_programs_census` + `cip_codes` + `subjects`                                                 | Vocabulary: a family says "nursing", IPEDS says `513801`. Mapping student words to CIP without inventing programs a school does not run.                                                                          |
| E | **"Would I be happy there?"** — size, setting, distance from home, religion, sport, study abroad, housing | `college_ipeds` + the search index attributes + the fit lens                                         | The honest ceiling: we have facts about the _place_, not about belonging. The risk of letting an LLM narrate "vibe" is highest here.                                                                              |
| F | **"What do I have to do, and by when?"** — requirements, deadlines, forms, test policy                    | `college_deadlines`, `college_admission_factors`, `aid_forms`/`aid_form_requirements`, `test_policy` | Freshness. A stale deadline is worse than no deadline. This is an operational-truth axis, not a research one.                                                                                                     |
| G | **"Will I be safe and supported?"** — Clery crime, disability services, counselling                       | only `disability_band`/`disability_pct`                                                              | Almost no ingested data; Clery is a separate federal source. Likely a later brief, if ever.                                                                                                                       |

## Success criteria for this decision

Gate 1 succeeds if it produces:

1. **One ranked bet** (or an explicit pair), chosen against evidence from the
   five research reports rather than intuition — with the losers recorded and
   why.
2. **A standing rule for non-money honesty.** Money got D7 ("never render a
   confidence score, percentage or rating for a figure"). The non-money axes
   need their own equivalent, because the failure mode is different: not a soft
   number, but an LLM asserting a fact about a school it has no source for. The
   brief must decide what the coach may say when it has no data on a non-money
   question.
3. **A decision on what we ask the student for.** Every candidate except C, E
   and F needs new profile input (GPA, test scores, intended major). Ian's
   standing value-before-ask ethos (brief 0001 D12) governs how, and gate 1 must
   say whether we are willing to ask at all.
4. **A decision on new data ingest.** C, B and G all require a new source. That
   is weeks, not days, and it must be chosen deliberately.

## Research (DISCOVER inputs, fanned out 2026-09-10)

Five parallel subagents, each writing one cited report under `research/`:

- `landscape.md` — what Niche/BigFuture/Naviance/CollegeVine/US News surface
  beyond price, how they present it, and the gaps a source-citing coach could
  own.
- `evidence.md` — what the research literature says actually matters (Chetty,
  Dale & Krueger, Hoxby & Avery undermatching, Scorecard field-of-study,
  Gallup-Purdue), with effect sizes and limits.
- `data-feasibility.md` — a repo inventory of non-money data plus the exact
  IPEDS/Scorecard/CDS/Clery variable behind each candidate axis, with coverage
  and lag.
- `user-value.md` — what students and parents SAY drives the choice (CIRP,
  NACAC, studentPOLL, Sallie Mae), how it has shifted, and where parent and
  student worries differ.
- `unicoach-today.md` — an audit of every chat tool, what the system prompt
  permits on non-money topics, and which stored non-money data no door reaches.

Gate 1 will be presented as numbered decisions with defaults once these land.

## PRIORITISE — synthesis of the five reports (2026-09-10)

All five reports are in `research/` (2,390 lines). Three of them note the same
tool limitation and it is recorded here rather than hidden: **the `websearch`
skill has no Serper key and every search engine was blocked or captcha'd**, so
`landscape`, `user-value` and `evidence` worked from known primary URLs and
direct file downloads. Consequences: no NACAC figures (member-gated), no Niche
or CollegeVine page fetches (403/unfetchable), no US News weight claims from
`evidence`, CIRP norms stop at Fall 2019. `evidence` and `data-feasibility`
compensated by downloading and computing on the College Scorecard bulk files
directly, which is stronger evidence than a search result.

### The four things all five reports agree on

1. **The next axis is not "more school-level numbers". It is the axis where a
   single school-level number is a lie.** `evidence` decomposed 27,617
   bachelor's programmes: **field of study explains R²=0.60 of median earnings,
   institution R²=0.36** (`research/evidence.md`). Debt is nearly flat across
   fields, so debt-to-earnings runs 0.25 for engineering against 0.54 for
   performing arts — **debt is a field problem disguised as a price problem.**
   `landscape` confirms **no consumer product does outcomes by major**; only the
   federal field-of-study file does, and unicoach does not ingest it. This is
   the same story money told, on a new axis.
2. **The most honest per-student answer available is completion, not
   admission.** Scorecard already publishes completion **by Pell,
   first-generation and income** at 79–82% coverage, and the median same-school
   gap is 4.5pp (Pell), 6.8pp (first-gen) and **12.3pp by income** — so the
   headline rate systematically overstates a low-income student's odds. unicoach
   already stores income band and dependency in `money_profiles`, so "students
   like me" needs **no new student field**.
3. **The gap is not data acquisition. It is columns we already have and never
   read.** `db/seed/ipeds/adm2023.csv` is committed in-tree and the loader reads
   **one column** of it; SAT/ACT 25th–75th percentiles,
   applied/admitted/enrolled and ADMCON1..12 are unread. `IC2023.csv`: 138
   columns, we read 8. The field-of-study file: 178 columns, we read 4. Seven of
   `data-feasibility`'s eleven recommendations are "read a column from a file
   already in the repo".
4. **There is a live honesty hole, and any non-money work amplifies it unless it
   is closed first.** The coach prompt is 158 lines and its only defence against
   invented non-money facts is one line. Zero lines mention completion,
   earnings, majors, GPA, admission odds or campus culture — while
   `median_earnings_10y_after_entry_usd`, `completion_rate_150pct_4yr_share` and
   `pell_share` are **already emitted on every search row with no publisher, no
   year and no rule**. That is the worst state a figure can be in: visible to
   the model, invisible to the RFC 177/179/183 machinery.

### The tension worth naming

`user-value` reports that **money is not the top stated reason** families choose
a school — CIRP Fall 2019 (n=95,505) ranks academic reputation 63.2%, good jobs
54.8%, **reputation of my intended major 53.7%**, then cost 50.5%; Princeton
Review 2026 (n=9,446) has "best overall fit" 48% against "most affordable" 12%.
That is not a verdict against the money work. Cost is an **elimination** rule —
79% of families cut a school on price alone, but only 35% of _final_ decisions
are financial. Money decides which schools survive; the rest of the decision
picks the winner, and unicoach is absent from it.

Two structural facts sharpen the choice:

- **Nobody in this market is paid by families.** `landscape`: Niche has ~15,000
  paying school clients; Scoir sells primary-inbox placement; College Board
  licenses a student's saved list plus SAT band to 1,200+ colleges; CollegeVine
  now sells colleges "aid leveraging" — how little to offer a family. **So
  nobody is paid to say "your list is long enough" or "don't apply".** Niche's
  letter grades are a forced curve (A+ = top 2.5%) and 27.2% of its "Academics"
  grade is acceptance rate plus yield.
- **Every honest refusal will test worse than a flattering competitor.**
  Sycophancy is measured, not hypothetical: 11 models affirm users ~50% more
  than humans do (arXiv 2510.01395, N=1604). A brief whose product is refusals
  has to expect that.

### Ranked bets

| Rank  | Bet                                                                                  | Verdict across the five reports                                                                                                                                                                                                                                                                                                                                                                                             |
| ----- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1** | **Guardrails first (prompt-only)**                                                   | `unicoach-today`: the only slice here that **reduces** risk instead of adding it. Zero data, zero DDL, one prompt version, one rollback knob. Closes the three unguarded figures already on the wire and forbids chancing before the C7 factor grid tempts it. Every other bet is safer after it.                                                                                                                           |
| **2** | **C — "Will it pay off?" earnings and debt BY MAJOR**                                | Strongest evidence (R² 0.60 vs 0.36), strongest differentiation (no consumer product does it), continuous with the money story (debt reframed by field), and the CIP inventory is already in `college_programs_census`. Cost: ingest the field-of-study file (38.5% programme-row coverage, 79% of institutions get ≥1 figure) and ask the student for an intended field.                                                   |
| **3** | **B — "Will I finish?" completion for a student like me**                            | Most honest per-student answer available; reuses `money_profiles` income band + dependency, so **no new student field**. Also upgrades our weakest served number: `OMAWDP8_ALL` (80.1%, counts part-time and transfer-in) is already on disk and strictly better than the `C150_4` we serve, and `TRANS_4` would let us say "a quarter transfer out". `user-value` ranks this the **#1** family question.                   |
| **4** | **A-lite — "What do they weigh, and what did they admit?"** _(no chance percentage)_ | The raw file is already in-tree: SAT/ACT 25th–75th at 56.8% coverage, admit counts at ~100%, ADMCON1..12 at 100%, plus the CDS C7 grid and deadlines we already store. `landscape` verified the CDS blends ED+EA+RD in C1 and collects **no** EA counts and **no** major-level admission item — so an admit-rate _honesty_ surface is ownable while a chance percentage is not. Deadlines are the #1 student anxiety (53%). |
| **5** | **E — "Is it the right kind of place?"**                                             | College side is already built (Carnegie size/class, locale, religion, ROTC, study abroad, athletics, all behind live search filters); latitude/longitude for real distance is already in `colleges`, and distance matters more than anyone admits (78.4% enrol in-state). Missing piece is a **student preference profile**, which is new asking. Cheap, but lower evidence value.                                          |
| 6     | D — programs offered                                                                 | Largely absorbed by bet 2: the field-of-study work needs the CIP vocabulary anyway.                                                                                                                                                                                                                                                                                                                                         |
| 7     | G — safety and support                                                               | **Decline.** Clery is fetchable via a working undocumented API, but reported crime rises with reporting _quality_; there is no federal mental-health-quality data. `user-value` and `data-feasibility` independently say no.                                                                                                                                                                                                |

### What every report says NOT to build

- **No admission chance percentage.** It needs five new sensitive fields to
  produce a number nobody publishes and no citation supports, and it would make
  unicoach's cited money figures look like the same kind of claim as an invented
  one.
- **No fit score, happiness score, rank, letter grade or composite.**
  `landscape` traced Princeton Review's "Happiest Students" to one Likert item
  from ~439 self-selected students at consenting schools, up to 3 years stale —
  and nobody conditions happiness on who the student is.
- **No institution-level headline earnings as a quality signal**, and no
  student-faculty ratio claim.
- **No safety rating.**

### The constraint that shapes the spec

`data-feasibility`'s warning is the RFC 183 failure in advance: retention is
Fall 2023, completion Fall 2018, field earnings up to 8–9 years stale across six
different cohort maps. **One answer mixing them without stamps is the mistake we
just finished fixing.** So the non-money vintage stamp is not a nice-to-have to
defer — it belongs in the first data slice, and a third data slice would need a
non-money analogue of `price_figures`. `landscape` independently names this the
#1 ownable gap: **source-and-year provenance on a non-money field, which no
incumbent does at all and whose business model forbids it.**

## Gate 1 — prioritisation decisions (presented 2026-09-10, defaults pre-chosen)

Approving costs one word. Amending costs one line. Ian's answers are recorded
verbatim below the list when given.

| #                                     | Decision                                                                                                                                                                      | Default                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1 — the two-pillar bet               | **The bet.** What earns the next beat?                                                                                                                                        | **A two-pillar beat: "Will it pay off — by major" (bet 2) and "Will I finish — for a student like me" (bet 3), preceded by a prompt-only guardrail slice.** Bets 4 and 5 are specced but scheduled after; bets 6 and 7 are declined.                                                                                                                                                                                                                                             |
| D2 — guardrails ship first            | **Guardrails go first.**                                                                                                                                                      | **Yes.** Slice 1 is prompt-only: no migration, no new data, one prompt version, one rollback knob. It ships before any non-money data slice.                                                                                                                                                                                                                                                                                                                                     |
| D3 — no scores, no invention          | **Standing honesty rule for non-money (the D7 analogue).**                                                                                                                    | **Standing: unicoach never estimates this student's admission odds, never renders a fit, happiness, safety or composite score, letter grade or rank for a school, and never describes a school's culture, programmes or requirements from model memory. A non-money figure is spoken only with its publisher and the cohort or year it describes, or it is not spoken at all.**                                                                                                  |
| D4 — no admission chancing            | **Chancing.**                                                                                                                                                                 | **Do not build it, and do not collect GPA, test scores, rigor, legacy or first-gen for the purpose of predicting admission.** Recorded as a deliberate refusal, not an oversight.                                                                                                                                                                                                                                                                                                |
| D5 — store intended majors            | **Intended field of study.** Bet 2 needs it.                                                                                                                                  | **AMENDED by Ian 2026-09-10 — see the amendment below D14: the answer is STORED and MULTI-VALUED.** Ask for it — invited, optional, value-before-ask (brief 0001 D12): the coach offers it when it would change an answer, the family may skip, and every answer degrades gracefully to the school-level figure with its limits stated.** A profile-screen field follows the brief 0007 pattern.                                                                                 |
| D6 — students-like-me inputs          | **"Students like me" input for completion.**                                                                                                                                  | **Reuse `money_profiles` income band and dependency. No new student field.** With no money profile, serve the all-student figure and say which population it describes.                                                                                                                                                                                                                                                                                                          |
| D7 — stamp in the first data slice    | **Non-money provenance.**                                                                                                                                                     | **Build the source + cohort/year stamp into the FIRST data slice, not later.** Scope it to a shared stamp on the figures these slices serve; do not build a canonical non-money store until a third data slice needs one.                                                                                                                                                                                                                                                        |
| D8 — fix the unguarded figures        | **The three unguarded figures already on the wire** (`median_earnings_10y_after_entry_usd`, `completion_rate_150pct_4yr_share`, `pell_share`).                                | **Fix in slice 1: give them a publisher + cohort/year attribution rule, or stop emitting them.**                                                                                                                                                                                                                                                                                                                                                                                 |
| D9 — honest completion headline       | **Upgrade the completion headline.**                                                                                                                                          | **Yes: serve `OMAWDP8_ALL` (8-year, counts part-time and transfer-in) instead of `C150_4` as the headline, and expose `TRANS_4` so we can say what share transfer out.** Both are already on disk.                                                                                                                                                                                                                                                                               |
| D10 — no safety or mental-health data | **Campus safety (Clery) and mental health.**                                                                                                                                  | **Declined for this brief and recorded as a product refusal**: reported crime rises with reporting quality, and no federal source publishes mental-health quality.                                                                                                                                                                                                                                                                                                               |
| D11 — no wider Common Data Set take   | **CDS licence exposure.** Class size has no federal source; only the CDS has it, at 270–370 institutions, and it is not public-domain.                                        | **Do not widen the CDS take for class size.** Ian's call — this is the one decision where the default is a legal posture, not a product judgement.                                                                                                                                                                                                                                                                                                                               |
| D12 — weave return into the report    | **The parent surface.**                                                                                                                                                       | **The Family Cost Report stays money-only this beat.** Non-money value lands in chat and on the college detail screen; whether the parent artifact grows a non-money section is deferred to its own decision once we see what lands.                                                                                                                                                                                                                                             |
| D13 — public explainer pages          | **A public explainer section in `public-web` (Ian, 2026-09-10).** The research in this brief is genuinely useful to a family, and today it only exists as an internal report. | **Standing pattern: `public-web` gains a family-facing explainer section — static, no account, no paywall, one page per topic.** The first page is this brief's "who pays for college advice" material. Every brief that produces family-useful research may propose one; brief 0010 (`when-do-i-test`) inherits this pattern for a testing explainer without re-asking.                                                                                                         |
| D14 — explainer content rules         | **The content rules for an explainer page.** These pages name real companies in public, so the honesty bar is higher than internal prose, not lower.                          | **(a) Every load-bearing claim carries a public URL and the date we fetched it. (b) A claim about another company quotes its OWN published methodology, pricing or product page — never a rumour, never an inference about motive stated as fact. (c) The page carries a visible "as of" date, because a competitor's pricing page will drift. (d) D3 applies unchanged: no scores, grades, ranks or chance figures anywhere on it. (e) No lead capture, no email wall, no ad.** |

### Note on D13 — why `public-web` and what it costs

`public-web` is a Ktor service rendering pages in Kotlin
(`public-web/src/main/kotlin/ed/unicoach/web/render/`: `HomePage.kt`,
`PrivacyPage.kt`, `TermsPage.kt`, `Layout.kt`, `CostReportPage.kt`), with
routing in `Routing.kt` and a page-per-file test precedent (`LegalPagesTest.kt`,
`HomePageTest.kt`). So one explainer page is **a render file, a route and a
test** — no database, no migration, no account, and it reuses the existing
layout and `site.css`.

Two things are deliberately left to /ship's design phase, not decided here:

- **How the prose is authored.** Essay-length copy hand-written as kotlinx.html
  builder calls is unpleasant to edit. RFC 185 just moved the coach prompt body
  to a Markdown file for exactly this reason, and the same shape may suit these
  pages. That is an implementation choice.
- **Whether the page is one route or a small index.** It becomes an index the
  moment the second page exists — and D13 guarantees a second page (testing), so
  /ship should expect two, but need not build the index before it is earned.

The honest risk to weigh at gate 1: this section is the first part of unicoach
whose job is **acquisition and trust**, not coaching a student who is already
here. It is also the first surface where we publish claims about named third
parties, which is why D14 exists.

### Amendment to D5 (Ian, 2026-09-10) — intended field of study is STORED, and is MULTI-VALUED

D5's default said only "ask". Ian amended it: **store the answer, and allow more
than one value.** That is a real product change, not a wording change — a
stored, multi-valued, family-authored field is a new profile shape the repo does
not have today (`money_profiles` is one row per student with scalar answers). It
therefore raises three decisions that D5 cannot carry on its own, added below at
their defaults.

| #                                    | Decision                                                                                                                                                                                                                                                                                        | Default                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D15 — majors stored as 4-digit codes | **What vocabulary does the family pick from, and at what grain?** Scorecard field-of-study earnings are published at **4-digit CIP x credential**; `cip_codes` holds 1,710 **six-digit** codes; RFC 148 already built a `subjects` taxonomy in the family's own words, which search uses today. | **The family picks from the SERVED vocabulary, not from raw CIP.** Reuse the existing `subjects` taxonomy (and serve it through `GET /api/v1/vocabularies`, RFC 165, exactly as income band and residency are served). A stored answer must resolve to the CIP grain the earnings file is published at, or the answer is stored and the earnings answer is **withheld with a reason** — we never invent a CIP the family did not choose. "Pre-med" is not a field of study and must map to what it really is or be refused as a choice.                 |
| D16 — up to three, undecided counts  | **The shape of a multi-valued answer.**                                                                                                                                                                                                                                                         | **An unordered SET, capped at 3, with no ranking and no "first choice".** A rank invites a score, and D3 forbids scores. **Zero values must not be one state**: `not asked`, `declined` and **`undecided`** are three different answers and are stored as three different things — "I don't know yet" is a legitimate, common and useful answer at 16, and it must not read as silence. This extends the `money_profiles` tri-state discipline (`unanswered \| answered \| declined`) with a fourth explicit value rather than inventing a new pattern. |
| D17 — chat and screen doors          | **The doors.**                                                                                                                                                                                                                                                                                  | **Both, matching the brief 0007 pattern**: the coach may ask in chat when it would change an answer, and the "Your details" screen carries the same field with add and remove. A value set in chat is visible and removable on the screen, and the reverse. Removing the last value returns the student to `undecided`, never to `not asked` — we do not re-arm an invitation the family already answered.                                                                                                                                              |

**This adds a table, so brief 0001 D10 applies**: the slice that lands it must
present the proposed DDL explicitly at /ship's approval gate, not buried in the
RFC. Expected shape is a child table keyed `(student_id, value)` with the STATUS
living on the parent profile row — because a set of rows cannot express
"declined" or "undecided" by itself, which is exactly the trap D16 exists to
close.

## Gate 1 amendments and additions from the 2026-09-10 discussion (Ian)

These were raised by Ian while gate 1 was open, and are folded in at their
defaults. Gate 1 is presented as **23 decisions**.

| #                                     | Decision                                                                                                                                                                                                                                              | Default                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D15 — majors stored as 4-digit codes  | **REWRITTEN (Ian).** Store the intended field of study as a **CIP code**, raw, per the schema conventions — **4-digit grain**, the grain the Scorecard field-of-study file publishes at (`(UNITID, CIPCODE 4-digit, CREDLEV)`; 311 covered families). | The family never sees or types a code: they pick from a served list of the published CIP titles (`CIPDESC`, already loaded into `college_programs`), searchable in ordinary words. Synonyms ("nursing", "compsci", "pre-med") are a **search aid only, never stored**; a term that is not a field of study is refused at the picker with a reason, never silently mapped. With a code stored and no figure available, the answer is withheld and names WHICH silence it is: publisher-suppressed (`PS`), or this school does not run that programme.                                                                |
| D12 — weave return into the report    | **AMENDED (Ian).** The Family Cost Report **weaves** field-of-study earnings and debt into its existing per-college money paragraphs.                                                                                                                 | No new section; earnings and debt by field ARE money, so the page's identity is unchanged — "what this costs you" extended with "and what this major returns here". The strongest line is publisher-computed: `DEBT_ALL_STGP_EVAL_MDN10YRPAY`, the monthly payment on a 10-year plan, by field, by school. A separate NON-money section (fit, place, admissions) stays deferred to its own decision.                                                                                                                                                                                                                |
| D18 — consent before a parent sees it | **Consent for the report.** A student's intended field is THEIR answer, and the report is opened by a parent from a token link with no account.                                                                                                       | **The student is told in plain words that their fields will appear on the report, before the first share that includes them, and can keep them off the report while keeping them in the coach.** One answer, not a per-field toggle. **Silence is not consent.**                                                                                                                                                                                                                                                                                                                                                    |
| D19 — say the coverage limit once     | **The silence budget.** Coverage bites hardest exactly where we weave: 61.5% of bachelor's rows are `PS`, median school has a figure for 8 of its 25 programmes.                                                                                      | **The report states the coverage limit ONCE, in its own voice; a per-college gap is then a short mark, not a repeated sentence.**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| D20 — no majors means no change       | **Degrade to nothing, gracefully.** Most students will be `undecided` or `not asked` for a long time.                                                                                                                                                 | **With no stored field the report renders exactly as it does today** — no placeholder, no prompt, no "add your major" cue. The invitation lives in the coach, under value-before-ask. Consistent with RFC 155's ban on upgrade cues to a logged-out parent.                                                                                                                                                                                                                                                                                                                                                         |
| D21 — a stored field never cuts       | **Search uses the stored fields, and asks for them the way it already asks for residency.**                                                                                                                                                           | **Reuse the RFC 169 (`shape/05`) offer pattern exactly**: search offers the field once, states what the answer would change, **never gates a result**, and remembers a decline. Respect the index's own tri-state: `NULL` `cip_codes` means "no programme census at all" (unknown — never silently dropped), `{}` means "known, and none of them is that subject" (a judged no).                                                                                                                                                                                                                                    |
| D22 — label peers, never rank them    | **`similar_colleges`: the field is a FILTER, not a distance dimension.**                                                                                                                                                                              | **Do not weight the student's major into the similarity distance.** That would make an implicit fit score (D3 forbids scores) and would let a school rank higher for teaching the major slightly more than a peer, which the data cannot support. Instead the field constrains the candidate set — "schools like Purdue that also teach what you want to study" — leaving RFC 153's one ranking untouched.                                                                                                                                                                                                          |
| D23 — the caveat budget               | **The caveat budget (standing).**                                                                                                                                                                                                                     | **A limitation earns copy in proportion to how often it bites.** A common limit is stated ONCE in the product's voice and marked briefly where it applies. A **rare** limit — e.g. the completions census reads a brand-new programme with no graduates as absent — gets a **footnote or nothing**, never a sentence in the main flow. When in doubt, cut it. Sibling of D19. This is a correction to a proven habit: brief 0008 found the money hedge hand-written at ~39 prompt sites plus 19 Kotlin strings before it was derived at one seam. Honesty is a property of the seam, not of how often we apologise. |

### Open, deliberately not defaulted

**Up to three stored fields x six colleges is up to eighteen woven cells on the
report.** Do we show every stored field per college, or only the best-covered
one? Showing one silently picks a favourite among the family's own answers.
**This goes to /ship's design phase with the binding constraint "never pick a
favourite on the family's behalf"**, and the RFC proposes the shape (Ian,
2026-09-10).

### Foundations confirmed for D21/D22 (grepped 2026-09-10)

- `college_search_index` already materialises `cip_codes TEXT[]` and
  `subject_slugs slug[]` per college
  (`db/schema/0064.create-search-index-and-subjects.sql:169-177`), with the
  NULL-vs-`{}` distinction documented in the column comments. **Nothing reads
  them against a student today.**
- `subjects.cip_prefixes` accepts 2, 4 or 6-digit prefixes (`:48-63`), so the
  4-digit stored code to 6-digit index array match has an existing mechanism.
  Which side widens is an implementation choice for /ship.
- `similar_colleges` (RFC 153) is the first and ONLY reader of the percentile
  columns, so a filter is additive while a new weight would mean re-tuning the
  one ranking we have.

### D21 / D22 AMENDED (Ian, 2026-09-10) — never prematurely cut a college

Ian: _"If colleges rank high outside of field of study, we should mention them.
I want to be sure we don't prematurely cut colleges."_ Both decisions are
amended. This follows the RFC 169 D19 precedent exactly: a school with no
separately published in-district price is **ranked AND labelled**, never
silently treated as in-state.

**D22 (amended).** The stored field **neither weights the distance nor removes a
school**. `similar_colleges` returns the same similarity list it returns today;
each result then STATES whether it teaches the student's field — **yes** /
**no** (census known, field absent) / **unknown** (no census at all). A
genuinely similar school that does not teach the field is **shown with that fact
stated, never dropped**. Results MAY be grouped ("closest matches that teach
it", then "also close, without it") — a statement of fact is not a score — but
nothing leaves the list.

**D21 (amended).** Two mechanisms that must not behave alike:

- **An explicit filter the student typed into THIS search** ("schools with
  marine biology") is an instruction and MAY cut — but the result says **how
  many schools the filter removed**, so the family sees the cost of its own
  filter.
- **A field stored on the profile** is CONTEXT, not an instruction, and **never
  cuts**. It orders and labels only — precisely as stored residency changed
  which price ruler search uses (RFC 169) without removing a single school.

Two reasons recorded with the amendment, both independent of Ian's:

- **Students change their minds, and should.** `undecided` is a legitimate
  answer (D16) because the answer is unstable; a tool that hides good schools on
  a 17-year-old's current answer optimises against its own user.
- **Our "no" is weaker than it looks.** The completions census counts awards
  GRANTED, so a real but new programme reads as absent. Cutting on that promotes
  a soft absence to a hard exclusion — the error class brief 0008 spent four
  slices removing from the money figures.

### Correction (2026-09-10): D19 and D23 INHERIT brief 0009's standing rule, they do not invent one

Brief **0009 "say it once"** (handle `concise`, gate 1 passed 2026-09-09)
already carries a `standing` **D8**:

> "Say it once: every surface states a fact, a source and a hedge once per
> block; repetition is a defect, not a safety margin. Compression never removes
> reachability."

That is the parent rule and this brief inherits it without re-asking. **D19 and
D23 are narrowed APPLICATIONS of it, not new policy**: D19 says where the
field-of-study coverage limit is stated on the woven report; D23 adds the
proportionality half — a limitation earns copy in proportion to how often it
bites, and a rare one gets a footnote or nothing. If 0009's D8 and anything here
ever disagree, **0009 wins** and this brief is corrected.

Note also that brief 0009's slices will be re-composing the same Family Cost
Report paragraphs that D12 weaves field-of-study figures into. That is a
**CONFLICTS** edge for the spec, not a blocker — but the slice text must name it
so the two runs do not rebase blind.

### D11 — ANSWERED by Ian, 2026-09-10, verbatim: "a"

Option **(a): do not widen the CDS take.** No class size. The standing CDS
exposure is unchanged by this brief. Recorded as a deliberate product/legal
posture, not an oversight: a future session must not "discover" the class-size
gap and fill it from the CDS without a new decision. The options Ian declined
were **(b)** widen and accept the risk (~5% coverage), and **(c)** pause for
counsel on the whole CDS position.

## Gate 1 — APPROVED by Ian, 2026-09-10, verbatim: "Okay. I approve this gate"

All 23 decisions stand as written, at their defaults, including Ian's in-gate
amendments: **D5** (the field of study is STORED and MULTI-VALUED), **D15**
(stored as a 4-digit CIP code, picked from published titles), **D12** (the cost
report WEAVES field earnings and debt into its money paragraphs rather than
gaining a section), **D21/D22** (a stored field never cuts a college — it orders
and labels; only an explicit typed filter cuts, and it says how many it
removed), **D23** (the caveat budget), and **D11 = (a)**, do not widen the CDS
take.

Standing rules declared or inherited here, for briefs after this one:

- **D3** — no admission odds, no fit/happiness/safety score, no grade or rank,
  nothing from model memory; a non-money figure carries its publisher and the
  cohort or year it describes, or it is not spoken.
- **D4** — chancing is refused, and the five fields it would need are not
  collected for it.
- **D23** — a limitation earns copy in proportion to how often it bites; a rare
  one gets a footnote or nothing. (A narrowing of brief 0009's standing D8,
  which governs.)
- **D13/D14** — `public-web` explainer pages, and the rules for what may be
  published on them about third parties.

Phase advances to **DISCOVER**.

---

## Decisions after gate 2 (Ian, 2026-09-13)

| #                                   | Decision                                                              | Answer                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| ----------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D33 — the ceiling-raise ritual      | Whether `beyond/07` performs a ceiling-raise to add a woven sentence. | **WITHDRAWN.** Ian's call to delete the five numeric size assertions removes the artifact this decision existed to negotiate with. Brief 0009's D20 (`concise/12/budgets-are-not-tests`) owns it.                                                                                                                                                                                                                                                                                                                      |
| D34 — one link per publisher        | How the report cites the field-of-study figures.                      | **Option A (Ian, "Option A").** The College Scorecard becomes one more publisher in the page's existing `sourcesSection` ("Sources and what this is not"), which is where every link on the page already lives. **One link per publisher — never per figure and never per school.** The body prose stays link-free, so a parent's reading is uninterrupted and the whole page gains ONE link. Reuses `sourceLink` and the existing dead-link behaviour (drop the link, keep the text) rather than inventing a pattern. |
| D35 — permissions are modelled once | Where the "show my majors to my parent" permission lives.             | **Option A (Ian, "Option A. Good idea").** `beyond/04` stores the majors and NOTHING else. A new slice `beyond/10/one-place-for-permissions` defines one place for "what this student allows a parent to see", and the new permission is its first occupant. Nothing is delayed: the consumer (`beyond/07`) is in the last wave.                                                                                                                                                                                       |
| D36 — compose, never compromise     | **STANDING.** The rule behind D35, in Ian's words, 2026-09-13.        | **"Model the data correctly, then model a permissions structure to control the data. The real principle is: never compromise design; use composition to build what you need."** Applied here: the majors are one thing and the permission over them is another, and neither is bent to save a migration. Inherited by later briefs without re-asking.                                                                                                                                                                  |

**The evidence behind D35**, recorded so nobody rediscovers it: unicoach already
has THREE unrelated shapes for "the student said no" — a share link revoked once
and forever (`cost_report_shares.revoked_at`), "never suggest sharing again" as
a permanent `share_events` row with **no undo** (`opted_out`), and "prefer not
to say" as an undoable status on a `money_profiles` column. A fourth bespoke
shape is the drift D36 forbids. Converging the existing three is NOT in scope
for `beyond/10`; they are recorded as candidates, and a slice that converges
them is a separate decision.

### D37 — model things as what they are (STANDING; refines D36, which stays as written)

Ian, 2026-09-13, on D36's wording: _"There are other techniques beyond
composition e.g. inheritance."_ D36 named one technique as though it were the
only one. Taken at its default (Option A):

> **Model each thing as what it is.** When something new is needed, build it
> from whatever structure fits — composition, inheritance, delegation,
> parameterisation, a new type — and never deform an existing model to absorb
> it.

The case that produced it: the tempting move was a "show my majors to a parent"
flag on the majors table, because that slice was already opening a migration.
The table would then mean two things — what a student wants to study, and who
may see it — to save one review. The rule forbids that, and is indifferent to
which technique fixes it.

### Decisions D38-D41 (Ian, 2026-09-14) — the taxonomy, and what a student's answer points at

| #                                                     | Decision                                                                                                                | Answer                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D38 — answers point at our subjects                   | What does a stored "what I want to study" answer reference? **SUPERSEDES D15** (which said a raw 4-digit federal code). | **Our own taxonomy.** `db/data/subjects.json` already holds 181 family-worded subjects, each mapping to one or more federal code prefixes, loaded into the `subjects` table and already used by search and named in the coach prompt. A raw federal code cannot represent the real answers: `neuroscience` spans THREE prefixes in different families and grains (`2615`, `3010`, `422706`), and `marine-biology` is FINER than the 4-digit grain the earnings file publishes at. D15's model would have silently picked one box for the student.                                                                                                                                                                                                                                                                                                                            |
| D39 — slug is the key, name is the label              | Identity for a taxonomy that user data now depends on.                                                                  | **Option B (Ian): keep `slug` as the primary key.** "For small datasets it is more ergonomic to use the slug; and changes to names should be rare." A surrogate `id` was offered and declined. This is SAFE because `subjects` already separates the two ideas: **`slug` is identity and is never shown to anyone; `name` is the label and may change freely** without touching a student's row. Only slugs are permanent.                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| D40 — retirement, not deletion                        | The loader's reference-data reload is a delete-and-replace (`DELETE FROM subjects WHERE slug <> ALL (...)`).            | **A subject is RETIRED, never removed, once anything references it.** The loader must refuse with a message naming the slug and its dependants, not a raw foreign-key error, and a retired subject stays resolvable for a student who already chose it. **Correction to an earlier claim of mine:** no reference table carries `ON DELETE CASCADE` — all 41 cascades in the schema are on owned child data — so a student answer could never have been silently deleted. The real risk was a WEDGED ingest, not data loss.                                                                                                                                                                                                                                                                                                                                                   |
| D41 — `intended_subjects` + `intended_subject_status` | The two table names, and the cross-table rule.                                                                          | **Option A (Ian).** Named for the `subjects` taxonomy they point at and for the word the coach already speaks to students. Evidence: "subject" is our word (the table, `subject_slugs`, the prompt's "search for it by its subject word"); `cip_code`/`cip_title` are the publisher's grain on college-side tables; "field of study" is mostly the name of the federal FILE; and "field" is overloaded — `CostField` alone appears 812 times. Shape: **many rows per student** in `intended_subjects` (capped at 3), **one row per student** in `intended_subject_status`. The rule "status is `answered` exactly when at least one subject row exists" **cannot be a CHECK** (no subqueries) and is a **DEFERRED constraint trigger** firing at commit, because mid-transaction the two tables are legitimately inconsistent while a student swaps one subject for another. |

**Scope note (Ian asked whether to fix every table using the reload pattern):**
narrowed deliberately. The other reference tables — states, locales, Carnegie
classes, religious affiliations, federal codes — are referenced only by
`colleges` and the search index, both rebuilt from source. No user data points
at them, so they have no identity-stability problem. `subjects` is the only one
about to carry user references. Fixing the rest for symmetry would cost the
reproducibility that makes them trustworthy.

### D42-D43 (Ian, 2026-09-14) — the tables, and two answer vocabularies

| #                                         | Decision                                                                                                                                                                                                                       | Answer                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| D42 — approve the intended-subject tables | The DDL for `beyond/04`, presented at /ship's gate per brief 0001 D10.                                                                                                                                                         | **APPROVED as presented (Ian: "approve").** `subjects` gains `status` (`active \| deprecated`) — common language, and a status rather than a timestamp because a subject can be dropped from the taxonomy file and later restored, so a timestamp would claim an event history the reload cannot honestly keep. `intended_subject_status` (one row per student, four states) and `intended_subjects` (up to three, unordered) each carry a history table copied from `college_list_entries` (schema 0024) with no deviation: physical delete blocked, removal is an UPDATE setting `deleted_at`, every committed state is snapshotted INCLUDING the removal, history cannot be orphaned, and re-adding after removal is a NEW row with its own history. `student_id` and `subject_slug` are frozen after insert, so `deleted_at` is the only column that ever changes — without that, "what was she planning in March" could return a row that has since become a different subject. |
| D43 — two answer vocabularies, not one    | Brief 0010's unstarted `test/06.1/one-answer-one-date` creates `DOMAIN answer_status AS TEXT`, whose acceptance criteria say a new table declaring its own copy FAILS review. This brief lands first and needs a fourth state. | **Option A (Ian): TWO shared spellings.** Three values where an answer can only be unanswered/answered/declined; four where `undecided` is possible. One question has three possible answers and the other has four — they are two vocabularies, and D37 says model each thing as what it is. A single four-value domain would make "I don't know yet" legal on an income band unless every money column remembered to add a narrowing CHECK, which is the duplication a domain exists to remove. `beyond/04` records its own CHECK as deliberate and temporary; **brief 0010 owns the domain slice** and is told directly.                                                                                                                                                                                                                                                                                                                                                          |

**Correction the code forced on /chart (recorded because the instruction was
mine):** I told the slice to freeze the two columns with the shared
`prevent_immutable_updates` guard. It cannot — schema `0023` narrowed it to `id`
and `created_at`, it takes no column names, and it is attached to eleven other
tables, so widening it would change their behaviour. The migration keeps the
shared guard and adds its own two-column guard beside it, which is what `0023`
and `0024` already do. The code won over the instruction.

### D44 — `undecided` is a STATE, not an empty answer (settled 2026-09-14, no arbitration needed)

Brief 0010's session read Ian's _"'I have not decided my major' is a real,
durable fact"_ as making `undecided` a VALUE of the field, which would collapse
D43 to one vocabulary. That reading settles RE-ASKING BEHAVIOUR, not storage: a
durable fact can be a state. After the field's actual shape was put to them,
**that session withdrew the objection**, so no decision from Ian was required.

**The argument that decided it, and it is specific to this field.** The money
profile's answer is status plus ONE value, where "undecided" can genuinely be
the value. **This field's answer is a SET** of up to three subjects, so
"undecided as a value" can only be `status = answered` with ZERO rows. That:

- re-opens **D16** ("zero values must not be one state") with the sign flipped —
  zero rows would mean either a real answer or a half-failed write, **identical
  on disk**;
- deletes an invariant we can otherwise enforce, "answered implies at least one
  subject";
- and `value_iff_answered` cannot honestly hold for a set-valued answer whose
  legitimate value is the empty set, because that rule is a statement about a
  COLUMN and a set has no column to be non-null. (Brief 0010 confirms its own
  scores slice already had to move that rule out of the database into a service
  for the same reason.)

**The general rule this produced, worth more than the decision:**

> **Cheap-to-reverse is only true when the information still exists.** The value
> model discards the distinction at WRITE time, and no later migration can
> recover which empty answers meant "I don't know" and which were faults. Four
> states keep the distinction and can be collapsed later — the COLLAPSE is the
> reversible direction, never the expansion.

**Outcome:** D43 stands. `test/06.1` builds both spellings and retypes
`intended_subject_status` onto the four-value one. `beyond/04` keeps its four
states and its deferred check.

**Behaviour, unchanged and separate from storage (Ian):** the coach does not
re-ask a student who said "I don't know yet". It may raise the subject when it
would change something concrete, and may return to the question only after a
wait AND a real change since last time — the shape Ian set for the parent-report
nudge, never a bare repeat.

## Ledger

    beyond/09/who-pays-for-college-advice LANDED as RFC 204 (main@fe1cab94 + dfccd799,
      2026-09-14) — a public explainer at /guides/who-pays-for-college-advice: how the
      college-guidance market is paid for, every third-party claim quoted from that
      company's own published page with the URL and read date printed, and who pays
      unicoach including the AI-sycophancy part. Static, no account, no cookie, no form.
      The page content is a typed value, so an unsourced claim does not compile; 15 tests
      hold it. Full suite 3,280 executed, 0 failures.

    beyond/02/the-suppression-sentinel LANDED as RFC 206 (main@da0c308a + 95f4da3f,
      2026-09-14) — the college ingest now reads `PS`, the two-letter code the College
      Scorecard actually writes for a figure it withholds for privacy; it recognised only
      the archived spelling `PrivacySuppressed`, so 1,228 suppressed debt cells read as
      school silence and families were told "This school did not report this figure".
      Both spellings are matched exactly, the per-column absence counts are kept at the
      seam that recognises the sentinel (so the domain-free net-price columns count too),
      an unrecognised token is now logged by column, line and value instead of passing as
      silence, and the three CSV fixtures stop spelling a sentinel the source never emits.
      Loader only: the stored statuses move on the next `bin/ingest-colleges` run. Full
      suite 3,271 executed, 0 failures; the gate re-ran it and passed.

### D45 — the third arm is a counter, not a log (Ian, 2026-09-14, verbatim: "yes")

`beyond/02` landed TWO counters (values coerced out of range, values the
publisher suppressed) plus a LOG LINE for the third arm — a present token that
is neither a known blank nor a sentinel and does not parse. Its review asked for
a third counter and the slice declined it correctly, as a product decision its
gate had not covered. `beyond/03`'s acceptance text already promises "counters
for all three arms reach the operator summary", so the spec and the code
disagreed.

**Resolved in favour of the spec: `beyond/03` BUILDS the third counter.** An
unrecognised token is a silent data-quality failure, and a number in the
operator summary is what makes a sudden change visible; a log line is invisible
until somebody already suspects a problem. This is the same reasoning that gave
the money work its withheld-figure counters — a rule that suppressed all 6,273
colleges must not look like one that suppressed 264.

`beyond/03` must therefore treat the third counter as work it owns, not as
something it inherits.
