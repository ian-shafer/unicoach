# What unicoach already answers that is NOT about money

An audit of the shipped surface: every chat tool, the coach prompt, the DB, the
iOS screens, and the parent report — read as of `main` on 2026-09-10.

## Method

What I read (all paths relative to `/Users/ian/Work/unicoach`, on the working
checkout; `.claude/worktrees/**` copies were excluded from every grep):

- `prompts/coach-system-prompt.md` — the whole coach prompt body, 158 lines. It
  is a Markdown FILE that a generator turns into a seed migration
  (`bin/prompt-seed-scripts-tests:11`, `:91`); `db/schema/` now carries
  `seed-coach-system-prompt-v27.sql` (STATUS.md still says v26 — the file tops
  out one version higher).
- Every `*ChatTool*.kt` under `service/src/main` and `college/src/main`, plus
  the registry wiring in
  `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:354-423`.
- `college/src/main/kotlin/ed/unicoach/college/CollegeQueryVocabulary.kt` (the
  search filter vocabulary) and `CollegeMatchRow.kt` (the search result
  serializer).
- `db/schema/*.sql` — all `CREATE TABLE` statements (72 tables).
- iOS sources under `ios-app/UnicoachiOS/*.swift` (screens and view models only;
  the vendored Google/Apple SDK sources were excluded).
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` — the
  parent report.
- `product/STATUS.md` "## The product today" (line 537 onward).

What was UNAVAILABLE / not done:

- **I did not run the app, the server, or the test suite.** No `nix develop`
  run, no DB. Every claim below is read from source, not observed at runtime.
  Coverage counts (e.g. "415 launch-set colleges") are quoted from
  `product/STATUS.md`, not measured.
- **No live database.** So "dead data" below means _no code path reads the
  column into a tool result or a screen_, proven by grep, not "the table is
  empty".
- I did not use web search. This topic is entirely internal.
- I did not read the admin web surface (`admin-web/`) in depth; it is an
  operator tool, not a student door.

---

## 1. Every chat tool the coach can call

Registered in one list at
`rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:354-423`. Every
turn advertises the full registry — there is no per-turn subsetting
(`chat/src/main/kotlin/ed/unicoach/chat/ToolRegistry.kt:5-11`).

| #  | Tool name                    | What it answers                                                                                                                                                                                                                                                                                                                                                                        | Money?                                                              |
| -- | ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 1  | `search_colleges`            | Structured search over the College Scorecard index: subject/CIP, states, region, urbanization, control, enrollment size, admission rate, completion rate, test policy, religious affiliation, Carnegie class + size, athletic association, ROTC, study abroad, on-campus housing, active, four-year — **plus** two price bounds and a price sort (`CollegeQueryVocabulary.kt:590-615`) | **Mixed — mostly NOT money** (22 of 24 filter fields are non-money) |
| 2  | `find_college`               | Resolve a school NAME/nickname/misspelling to a `college_id` (`college/src/main/kotlin/ed/unicoach/college/FindCollegeTool.kt:171`)                                                                                                                                                                                                                                                    | No                                                                  |
| 3  | `similar_colleges`           | "Schools like Bowdoin", ranked on axes `size`, `selectivity`, `price`, `setting`, `subjects` (`db/src/main/kotlin/ed/unicoach/db/models/SimilarityQuery.kt:19-37`)                                                                                                                                                                                                                     | **Mixed — 4 of 5 axes are non-money**                               |
| 4  | `update_money_profile`       | Write the student's income band, residency state, living plan (`service/.../MoneyProfileChatTool.kt:291`)                                                                                                                                                                                                                                                                              | Yes                                                                 |
| 5  | `college_cost_profile`       | The whole cost answer: published charges by arrangement, net price by income band, residency tiers, aid policy (`required_forms`, need met), borrowing at graduation, figure statuses, assurance tiers (`service/.../costs/CollegeCostChatTool.kt:1223`, 1913 lines)                                                                                                                   | Yes                                                                 |
| 6  | `update_college_list`        | Add / restatus (`considering`/`applying`/`admitted`/`rejected`) / remove a school, and the free-text `reasons` (`service/.../collegelist/CollegeListChatTool.kt:540`; schema `db/schema/0024.create-college-list.sql:38-43`)                                                                                                                                                           | No                                                                  |
| 7  | `college_admissions_profile` | Three CDS sections per listed school: merit aid, the **C7 admissions-factor grid**, and **application rounds + deadlines** (`service/.../admissions/CollegeAdmissionsChatTool.kt:183, 216-230`)                                                                                                                                                                                        | **Mixed — merit is money; factors + deadlines are NOT**             |
| 8  | `federal_aid_policy`         | Pell max/min, SAI floor, income tests, Direct Loan annual/aggregate limits (`service/.../aid/FederalAidPolicyChatTool.kt:169`)                                                                                                                                                                                                                                                         | Yes                                                                 |
| 9  | `share_cost_report`          | Mint a no-login parent link to the Family Cost Report (`service/.../report/ShareCostReportChatTool.kt:110`)                                                                                                                                                                                                                                                                            | Yes                                                                 |
| 10 | `revoke_cost_report_share`   | Kill every live share link (`.../RevokeCostReportShareChatTool.kt:63`)                                                                                                                                                                                                                                                                                                                 | Yes                                                                 |
| 11 | `stop_cost_report_offers`    | "Never suggest sharing again" (`.../StopCostReportOffersChatTool.kt:70`)                                                                                                                                                                                                                                                                                                               | Yes                                                                 |

Two more tools exist but are **not in the registry** — they are forced-tool
calls in background passes, never offered to the coach: `record_college_query`
and `record_fit_reason` (the fit lens,
`service/.../fitlens/FitLensService.kt:164-165`), `record_synthesis`
(`service/.../synthesis/SynthesisService.kt:754`), and `record_extraction`
(`service/.../extraction/ExtractionService.kt:804`).

**Score: 6 of 11 registered tools are pure money. Only ONE tool
(`update_college_list`) is pure non-money, and it stores the student's own
words, not a fact about a college.** The genuinely non-money _facts_ unicoach
holds all arrive through two mixed tools: `search_colleges` (institution
attributes) and `college_admissions_profile` (what the admission office weighs,
and when it wants to hear from you).

---

## 2. What the prompt tells the coach about non-money topics

The prompt is 158 lines. **80 of them contain an explicit money word**
(cost/price/tuition/aid/loan/Pell/grant/net/income/debt/fee/$). The blocks that
are neither money nor Markdown formatting are lines 37–46 (college list +
admissions), 63–68 (search), and 79–87 (find / similar).

### The one general guardrail

> `prompts/coach-system-prompt.md:5` — "Never invent facts about the student, or
> about specific colleges, deadlines, or requirements — say plainly when you
> don't know."

That single line is the entire defence against invented non-money facts. It
names three categories: **the student, colleges, deadlines, requirements**. It
does not name majors, campus culture, admission chances, class size, safety,
outcomes, or student life.

> `prompts/coach-system-prompt.md:6` — "Keep the conversation on college
> coaching; gently redirect anything else."

Note what this does NOT do: it does not scope the coach to _what unicoach has
data for_. "Is Oberlin artsy?" is squarely college coaching, so line 6 permits
it and line 5 is the only brake.

### The tool-grounding lines (all money, or nearly)

Every "answer from the tool, not from memory" line in the prompt is attached to
a money tool or to the search doors:

- `:15` — "Use the `college_cost_profile` tool rather than remembered figures."
- `:41` — "...the figures come from each school's own Common Data Set, so
  attribute them to it **and never to memory**." (admissions/merit/deadlines —
  the one non-money instance)
- `:84` — "call the `similar_colleges` tool **rather than naming peers from
  memory**: what counts as similar is decided by that call, not by a list you
  happen to remember."
- `:113` — "answer from the `federal_aid_policy` tool **rather than from
  memory**"
- `:134` — "answer from the `aid_policy` section of `college_cost_profile`
  **rather than from memory**"

### How it is told to behave with no data

The "no data" discipline is rich — and it is written **entirely in money
vocabulary**:

- `:44` — "When a field is named in a result's `data_availability`, that school
  does not report it: say so plainly rather than estimating it."
- `:45` — "When a round is flagged as not offered, that is the school saying it
  does not offer that round — a reported fact, not missing data."
- `:66` — "a school that does not report something has not been shown to fail
  it, and treating a silence as a no would quietly shrink the student's
  options."
- `:129-133` — the four figure-status sentences, ending "**None of these is ever
  a zero, and none of them is ever a reason to guess.**"
- `:130` — "That last sentence is ours and not the school's — it is our gap, so
  never tell a family the school failed to report a figure we have not
  gathered."

This is a mature "our gap vs. their silence" doctrine. **It exists only for
figures a tool returned.** There is no equivalent for a topic no tool covers at
all.

### Guardrails that DO NOT exist

I grepped the prompt for each term. Occurrence counts:

| Term                                                 | Occurrences in the prompt |
| ---------------------------------------------------- | ------------------------- |
| `completion` / graduation rate                       | **0**                     |
| `earnings` / median earnings                         | **0**                     |
| `major`                                              | **0**                     |
| outcome                                              | **0**                     |
| essay, recommendation, class rank, GPA               | **0** each                |
| test-optional / test policy                          | **0**                     |
| chance / admit (as in odds)                          | **0**                     |
| campus culture, vibe, safety, weather                | **0**                     |
| religious affiliation, athletics, study abroad, ROTC | **0** each                |

So, concretely, **the prompt today does not forbid**:

1. **Chancing.** Nothing says "never estimate this student's odds of admission".
   The admissions tool returns a C7 factor grid ("very important" /
   "considered"), and `:41-46` tell the coach how to read it — but no line stops
   the model from adding "with your GPA you're a strong candidate". unicoach
   stores no GPA, no test score, and no transcript, so any such statement is
   pure model invention.
2. **Campus vibe / student life.** No line covers it. Line 5's "facts about
   specific colleges" arguably covers it; a warm coach will read "is it a party
   school?" as an opinion question, not a fact question, and answer.
3. **Majors and programs, beyond search.** `:63` tells the coach to search by
   subject word rather than a guessed code — a _retrieval_ rule. Nothing says
   "do not describe a school's program from memory". The DB stores program
   TITLES (`college_programs`), and the search result only returns `programs`
   when a program filter was written
   (`college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt:184-190`).
4. **Outcomes.** `search_colleges` returns `completion_rate_150pct_4yr_share`,
   `median_earnings_10y_after_entry_usd`, `median_debt_at_completion_usd` and
   `pell_share` on every row (`CollegeMatchRow.kt:176-179`). **The prompt names
   none of these.** There is no year-attribution rule for them, no cohort
   caveat, no "this is the whole institution, not your major" caveat. Compare
   the treatment money gets (`:142-143`, borrowing: "say that cohort out loud
   with its year").

**This is the biggest honesty risk in the product.** The money surface has ~80
lines of attribution law; the non-money surface has one sentence. An LLM asked
"what's Kenyon like?" will answer from weights, fluently, with no citation and
no way for a family to tell that answer apart from the cited price beside it.

---

## 3. Non-money data in the DB, not reachable by any tool or screen

72 tables exist. The non-money data that IS reachable:
`college_admission_factors` and `college_deadlines` (via
`college_admissions_profile`), and the search-index attribute columns (via
`search_colleges` filters).

Reachable-but-unspoken / unreachable:

| Table or column                                                                                                                                                                                                                                                                                                                                                       | State                                                                                                                                                                                                                                                                                                  |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `median_earnings_10y_after_entry_usd`, `completion_rate_150pct_4yr_share`, `pell_share`                                                                                                                                                                                                                                                                               | **Emitted to the model on every search row** (`CollegeMatchRow.kt:176-179`) but named in **no prompt line**, on **no screen**, and in **no report section**. Half-dead: the model sees them and has no instructions for them. This is worse than dead.                                                 |
| `college_programs`, `college_programs_census`                                                                                                                                                                                                                                                                                                                         | Read only to expand a `subject` filter and to fill `programs` when a program filter was written (`CollegeMatchRow.kt:184-190`). A student cannot ask "what does Rice offer?" and get a list.                                                                                                           |
| `football_conferences`                                                                                                                                                                                                                                                                                                                                                | Loaded as a codebook (`db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt`, `college/src/main/kotlin/ed/unicoach/college/CodebookLoader.kt`) and **offered by no filter** — `CollegeQueryVocabulary.FIELD_NAMES` has `athletic_association` but no conference field. Genuinely dead.                |
| `carnegie_2021_basic_classes`, `carnegie_2021_size_settings`, `nces_locales`, `religious_affiliations`, `admission_test_policies`, `athletic_associations`                                                                                                                                                                                                            | Reachable as **search filters only**. No screen renders them, and no tool answers "tell me about this school" from them. A student must guess to filter on them.                                                                                                                                       |
| `has_rotc`, `has_study_abroad`, `has_housing`                                                                                                                                                                                                                                                                                                                         | Search filters (`CollegeQueryVocabulary.kt:196-215`). `has_housing` also feeds a cost answer. `has_rotc` / `has_study_abroad` are otherwise unspoken.                                                                                                                                                  |
| `college_admission_factors` C7 columns (rigor, class_rank, gpa, test_scores, essay, recommendations, interview, extracurriculars, talent, character_qualities, first_generation, alumni_relation, geography, state_residency, religious_affiliation, volunteer_work, work_experience, applicant_interest — `db/schema/0054.create-cds-admissions-tables.sql:107-134`) | Reachable in chat, **on no screen and in no report section**. 18 published, school-authored, non-money facts per school, and a parent can never see them. STATUS.md:1611 puts coverage at 374 of 415 launch-set colleges.                                                                              |
| `claims` (topics: `academics, activities, finances, location, career, timeline, wellbeing` — `db/schema/0019.create-coaching-memory.sql:69,83`)                                                                                                                                                                                                                       | Six of seven topics are non-money. Written by the extraction pass; read by the fit lens and the opener. **Not readable by the coach through any tool**, and not shown to the student anywhere.                                                                                                         |
| `fit_suggestions` / `fit_lens_runs`                                                                                                                                                                                                                                                                                                                                   | The fit lens proposes ONE real school grounded in the student's claims (`service/.../fitlens/FitLensService.kt:57-60`), surfaced in the next session's opener (`CoachingService.kt:812-826`) behind `config.surfaceFitSuggestions`. This is unicoach's only shipped _fit_ engine and it has no screen. |
| `observations`                                                                                                                                                                                                                                                                                                                                                        | Rendered on `CollegeEntryDetailView` as "From your conversations" (`ios-app/UnicoachiOS/CollegeEntryDetailView.swift:94-100`) — the one non-money reading surface in the app.                                                                                                                          |

---

## 4. What we collect today, and what a fit / admissions / major answer would additionally need

### Collected today

| Field                                                                      | Where                                                                                                                                      | Store                                                                                                           |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- |
| Expected high-school graduation date (year required, month + day optional) | Onboarding, "When do you graduate?" (`OnboardingView.swift:83-165`)                                                                        | `students.expected_high_school_graduation_{year,month,day}` (`db/schema/0005.create-students.sql:16-18`)        |
| Residency state (2-letter)                                                 | Onboarding `statePicker` (`OnboardingView.swift:198-207`), Your Details (`YourDetailsView.swift:109-115`), and chat `update_money_profile` | `money_profiles.residency_state` + `residency_status` ∈ `unanswered/answered/declined` (`db/schema/0046:37-39`) |
| Household income band (5 bands, $0–30k … $110k+)                           | Onboarding `incomePicker` (`OnboardingView.swift:209-250`), Your Details, chat                                                             | `money_profiles.income_band` (`db/schema/0046:34-43`)                                                           |
| Living plan (on campus / off campus / at home), per school and default     | Chat only                                                                                                                                  | `college_list_entries` living-plan column (`db/schema/0071.add-college-list-entry-living-plan.sql:17`)          |
| Dependency status (federal aid)                                            | Chat only, asked once (`prompt:118`)                                                                                                       | (federal aid path)                                                                                              |
| College list: school + status + free-text `reasons`                        | `CollegeListView`, `CollegeEntryDetailView`, chat                                                                                          | `college_list_entries`                                                                                          |
| Everything else the student says                                           | Extraction pass                                                                                                                            | `claims` (7 topics), `observations`, `commitments`                                                              |

Onboarding says the money questions are optional: "**Optional — you can add
these any time**" / "Answer now or later; just ask me in chat. I'll work without
them." (`OnboardingView.swift:177-181`). Your Details is explicitly framed as
money: "**What unicoach knows about your family's money.**"
(`YourDetailsView.swift:132`).

**Note the shape:** three structured profile fields exist, and all three are
money fields. There is no structured field about the student themself.

### What a fit / admissions / major answer would ADDITIONALLY need to ask

A _chancing_ or _admissions-fit_ answer needs, at minimum:

- Unweighted GPA (and the school's scale), or class rank/decile — the CDS C7
  grid rates `rigor`, `class_rank`, `gpa` and `test_scores` separately, so a
  single number cannot be read against it honestly.
- SAT/ACT score, and whether the student intends to submit it (this interacts
  with `admission_test_policies` — a test-optional school's published mid-50%
  describes submitters only).
- Course rigor: number of AP/IB/dual-enrolment courses.
- First-generation status, and legacy/alumni relation — both are C7 rows
  (`first_generation`, `alumni_relation`), so a student who does not answer
  cannot be scored on a factor the school says it weighs.
- Intended major (and whether the school admits by major — many public flagships
  do; unicoach stores no such flag).
- **None of these exist in the schema today.** Every one is a new sensitive
  field with a `declined` state, mirroring the money-profile precedent
  (`income_band_status`).

A _non-academic fit_ answer needs far less new data and is much safer: preferred
distance from home, campus setting (`locale_type` already exists as a filter
word), size band, urban/rural, religious affiliation preference, athletics,
weather/region. These map ONTO existing published filter columns — the student
side is missing, not the college side.

---

## 5. Where a new non-money feature physically attaches

- **Prompt.** `prompts/coach-system-prompt.md`, a file, formatted by `deno fmt`
  and turned into a `seed-coach-system-prompt-vNN.sql` by the generator
  (`bin/prompt-seed-scripts-tests:91`). Adding non-money guardrails costs one
  prompt version bump and a rollback knob (`COACHING_SYSTEM_PROMPT_VERSION`).
  **This is the cheapest lever in the codebase.**
- **A new chat tool.** One `ChatTool` implementation plus one line in the
  registry list at `Application.kt:354-423`. `StudentScopedChatTool.kt` (269
  lines) already supplies the student-scoped base class, and
  `putCollegeIdsSchema()` / `readCollegeIds()` already supply the "one or more
  schools, defaulting to the active list" input shape used by
  `college_admissions_profile`.
- **Widening an existing tool.** `college_admissions_profile` already returns a
  C7 factor grid and deadlines per listed school and is named "admissions", not
  "money" — adding test-score ranges or an outcomes section there needs no new
  tool and no new prompt door.
- **Search.** A new filter is one entry in
  `CollegeQueryVocabulary.schemaProperties()` plus `FIELD_NAMES`
  (`CollegeQueryVocabulary.kt:73-240, 590-615`); the vocabulary is shared by
  `search_colleges` AND the fit lens's `record_college_query`, so one edit
  widens both.
- **Search results.** `CollegeMatchRow.matchObject()` (line 132) is the one
  serializer; the outcome columns are already in it.
- **iOS screens.** `CollegeEntryDetailView.swift` (162 lines) is a per-school
  detail screen with three sections — Status, "Why it's on your list", "From
  your conversations". **A fourth section is the natural home for a per-school
  non-money fact card**, and the screen already exists and is reachable from
  `CollegeListView`. `YourDetailsView.swift` is a field/answer/decline pattern
  that a non-money profile field could reuse verbatim — but its copy and title
  are money-specific.
- **Parent report.** `public-web/.../CostReportPage.kt` renders `report`,
  `report-summary`, `report-school`, `report-merit`, `report-debt`,
  `report-sources` sections (lines 249, 391, 502, 953, 983, 1082). A non-money
  section would attach beside `report-merit`. Note the page is called the Family
  **Cost** Report — its identity is money.
- **Background passes.** `FitLensService` already reasons over `claims` and
  proposes a school; it is the existing home for "fit" and needs no new
  orchestration to widen.

---

## Implications for unicoach

Ranked by my confidence, highest first.

1. **The cheapest credible first non-money slice: write the missing guardrails
   into the prompt.** Add a small block that (a) forbids estimating this
   student's admission odds, because unicoach stores no GPA and no test score;
   (b) forbids describing campus culture, student life, or a school's programs
   from model memory, and instead offers what a tool can cite; (c) gives the
   completion-rate and median-earnings figures the search tool ALREADY returns a
   cohort + year attribution rule, matching the borrowing rule at
   `prompt:142-143`. Cost: one prompt version, one rollback knob, zero
   migrations, zero new data. This is the only slice here that reduces risk
   instead of adding it. **Confidence: very high.**
2. **The biggest honesty risk is chancing, and it is live today.** Nothing in
   158 prompt lines stops the coach from saying "you'd be competitive at
   Michigan". The C7 grid the coach reads makes it _more_ likely, not less,
   because it hands the model the factors and no rule about what to do with
   them. Any non-money work must start by closing this, or it will amplify it.
   **Confidence: very high.**
3. **`median_earnings_10y_after_entry_usd`, `completion_rate_150pct_4yr_share`
   and `pell_share` are already on the wire with no rules.** This is the worst
   state a figure can be in: visible to the model, invisible to the honesty
   machinery. Either give them the full RFC 177/179/183 treatment (publisher,
   assurance tier, cited year) or stop emitting them. **Confidence: high.**
4. **`college_admissions_profile` is the door, not a new tool.** It is already
   non-money-capable (C7 factors + deadlines), already list-scoped, and already
   374/415 covered. A "what this school looks for" section on
   `CollegeEntryDetailView` — the school's own CDS words, cited, no scoring — is
   a real non-money feature with **no new college data and no new student
   data**. **Confidence: high.**
5. **Non-academic fit is far safer than admissions fit, and the college side is
   already built.** Setting, size, region, religious affiliation, athletics,
   ROTC, study abroad, Carnegie class are all published columns behind search
   filters today. What is missing is the STUDENT side: three profile fields
   exist and all three are money. A small preference profile (distance from
   home, setting, size) would light up filters that already work. **Confidence:
   high.**
6. **Do NOT build chancing.** It needs GPA, test scores, rigor, first-gen and
   legacy — five new sensitive fields — to produce an output that is a
   probability nobody publishes and no citation can support. It is the single
   feature most likely to make unicoach's cited money figures look like the same
   kind of claim as an invented one. **Confidence: high (as a reason not to
   build).**
7. **The fit lens is shipped, cited nowhere in the product manual, and has no
   screen.** It proposes a real school grounded in the student's claims and
   surfaces it only in a session opener. Giving it a visible surface ("here's
   why we thought of this one") is a non-money feature that is already 90%
   built. Check whether `surfaceFitSuggestions` is on in prod first — I could
   not. **Confidence: medium-high.**
8. **`claims` has six non-money topics
   (`academics, activities, location, career, timeline,
   wellbeing`) that the
   student can never see.** `CollegeEntryDetailView`'s "From your conversations"
   proves the pattern works per school; a whole-student version is the same
   component against a different query. It also makes the coach's memory
   auditable, which is an honesty win, not just a feature. **Confidence:
   medium-high.**
9. **The parent surface is named "Cost Report" and has no non-money section.**
   If non-money becomes a pillar, that page's identity has to change or a second
   artifact has to exist. Bolting a "what this school looks for" block onto a
   page whose heading promises price is a product decision, not a rendering one.
   **Confidence: medium.**
10. **`football_conferences` is loaded and offered by nothing.** Either add the
    filter beside `athletic_association` or drop the codebook. Trivial either
    way; listed only because it is the one unambiguously dead non-money table I
    found. **Confidence: medium (small stakes).**
