# 0010 — spec & slices: when do I test?

Gate 1 APPROVED 2026-09-10 (D1-D14). This file is the slice plan and the gate-2
decisions. **Brief handle: `test`.** Slice IDs are permanent and never
renumbered.

## Preamble — what the codebase actually is (DISCOVER, `discover.md`)

The brief's framing was right about the bet and wrong about the inventory in
four places. These corrections bind the slices; where this preamble and the
brief disagree, **this preamble wins**, and where it and the code disagree, the
code wins.

1. **Test policy is already ingested. Only the READ is missing.** D5 assumed a
   new IPEDS ADM fetch. In fact `bin/fetch-ipeds:258` already fetches ADM2023,
   `ADMCON7` is the only thing the ADM survey is read for
   (`IpedsLoader.kt:992,1032`), it is stored as
   `college_ipeds.test_policy SMALLINT CHECK IN (1,3,5)`, slugged `required` /
   `test-blind` / `test-optional` in `admission_test_policies`, carried into
   `college_search_index.test_policy`, and **already exposed to the coach as a
   search filter** (`CollegeSearchTool.kt:243`). What does not exist is the
   per-school read: `college_admissions_profile` emits only merit_aid,
   admission_factors and deadlines (`AdmissionsField.kt:18-23`) and never
   touches `college_ipeds`. **So D5 costs zero DDL and zero fetch** — a fourth
   section on `CollegeAdmissionsService` with its own IPEDS citation. Coverage
   is 1,936 of 6,273 colleges, collection year **2023**.
2. **D6's free lunch is smaller than advertised, and the CSV is not committed.**
   `db/seed/scorecard/*` is **gitignored** (`.gitignore:54`) — the file is
   fetched, not committed. Real counts over 6,273 rows: `SATVR25/75` and
   `SATMT25/75` **949** each, `ACTCM25/75` **920**, against `SAT_AVG` **1,037**.
   The band therefore covers **fewer** colleges than the average it is meant to
   replace: ~88 colleges have an average and no band. "Prefer the range" needs a
   stated fallback, which is D16.
3. **Adding a `colleges` column costs a trigger restatement.** `0094:66-68`
   records the trap: a plpgsql body is TEXT and is not dependency-tracked, so a
   stale `log_college_version()` kills the next write with `42703`. One
   migration, three statements: `ALTER colleges`, `ALTER colleges_versions`,
   `CREATE OR REPLACE` the function with both column lists. Per RFC 180 the file
   is a **kebab slug, not a number**; `db/schema/ORDER` carries apply order and
   `ship-order` places the line at land.
4. **Slice 1's real trap is an early return.** `CoachingService.kt:851`:
   `if (pending.isEmpty() && pendingFits.isEmpty() && moneyProfile == null)
   return prompt.body`.
   A block that must ALWAYS render will silently not render for a student with
   no money profile — which is every new student. The student row is **not**
   loaded on that path (only `studentId`); the smallest read is
   `StudentsDao.findById` (`:72-86`), giving a `PartialDate` with year NOT NULL.
   The `Clock` pattern is a defaulted constructor parameter
   (`SynthesisService.kt:83`, `FederalAidPolicyService.kt:150`); production
   passes none and both default to `Clock.systemUTC()`.

Also binding:

- Live coach prompt is **v27** (`service.conf:176`); rollback is
  `COACHING_SYSTEM_PROMPT_VERSION=v26`. A slice edits only
  `prompts/coach-system-prompt.md`; `bin/prompt-seed` cuts a version at land
  **iff the body differs** (RFC 185 D6). Do not hand-write a seed migration.
- Residency is `money_profiles.residency_state`, `^[A-Z]{2}$` plus an
  `AnswerStatus` tri-state, already rendered into the prompt at
  `CoachingService.kt:886-889`. **There is no state enum**, so a state-keyed
  table must validate the code itself.
- A testing figure **must not** carry `FigureStatus` or an assurance tier. That
  seam belongs to the canonical MONEY tables only; borrowing it would claim a
  provenance model testing data does not have.
- **Next free RFC number: 186** (max 185 across `rfc/`, all references and all
  seven worktrees). Recompute at dispatch; never copy this number. _This number
  was already stale within a minute of dispatch — `test/01` took 186 and
  `test/02` claimed 187 — and by wave 2 a sibling brief had taken 188-194. The
  line stands as written to make the point: **claim it live, never read it from
  a document.**_

## The unit of contention is the FILE and the KIND of edit

Derived twice on 2026-09-11, from opposite directions, in traffic with the
sibling `/chart say-it-once` session (brief 0009). It binds every future slice
in this brief.

A CONFLICTS edge says two slices touch the same file. That is not one risk, it
is two, and only one of them needs serialising:

- **A DELETION rebased across concurrent ADDITIONS can silently drop a sentence
  that landed minutes earlier**, and no test catches it, because the test corpus
  does not know the sentence was ever meant to be there. This needs a real
  window.
- **An add/add rebase has no such failure mode.** Git either merges or surfaces
  a conflict, and both intents survive.

So three slices touching `prompts/coach-system-prompt.md` got three different
answers on the same afternoon: `concise/06` (adds) overlapped freely, `test/03`
(never opens the file) started immediately, and `concise/03` (deletes) got a
held window. **Holding a slice that does not open the contended file is
cargo-culting the agreement past its reason.**

Two corollaries this brief follows:

- A hold is scoped to a FILE and a KIND, never to a brief or a wave.
- `Needs: CONFLICTS` is currently one word for both hazards. Where a slice in
  this brief DELETES from a file another live slice adds to, say so in the
  reason, because the board cannot.

**The final clause, added 2026-09-11 after the freeze did not catch the real
collision.** The window we agreed protected `prompts/coach-system-prompt.md`.
The conflict arrived through `CollegeCostChatTool.DESCRIPTION` — the OTHER file
that deletion pass deleted from — which nobody protected, because no other
SESSION was editing it. The sibling's own sibling slice was. So:

> the unit of contention is the FILE and the KIND of edit — and for a DELETION,
> it is EVERY file the deletion touches, whoever is editing them, **including
> your own sibling slices**.

A freeze scoped to where the danger was expected, rather than to where the
change actually reached, is a freeze with a hole in it.

**A fourth parallel-work hazard, and the first where the tooling did nothing
wrong** (2026-09-11). This section itself was committed to `main` by the sibling
session in `1af7b493`, under brief 0009's commit message, because its `git add`
named a path it did not own. It was reviewed and left in place rather than
reverted: the text was finished, and re-landing finished prose costs a commit
for nothing. Recorded here so the attribution is not lost to the commit log. The
general form, which is the same rule this section already states one layer
along: **enumerate what you are about to act on, then act — never act on the set
you think you own.** In a shared checkout a `git add` of another session's path
is silent, passes every gate, and lands; neither RFC 193 nor RFC 194 would catch
it, because a docs-only commit is exactly what it looks like.

**The corrected form of the rule, since the first form invited the error.** Both
sessions scoped a freeze to a FILE, independently, and both were wrong the same
way. Do not start from the file. **Start from the change: enumerate every file
your pass touches, then protect that set.** This binds `test/04`, whose
hand-maintained calendar will one day be revised by deleting rows, and
`test/07`/`test/08`, which share one curated dataset.

**And the finding underneath it, which this brief inherits as a review rule.**
The rule that nearly vanished was held ONLY by an assertion inside the very test
file the slice was rewriting. **A guard inside the artifact under rewrite is not
a guard**: it gets edited in good faith by the person most likely to break it,
because rewriting that file IS the task. Any slice here that ratchets a budget,
pins a sentence or asserts a count must put that guard somewhere its own work
does not open.

## Slices

Every slice states its door. Every slice inherits D9 (never test prep, never
score prediction, never chancing) and D4 (the claim is list-anchored; the
reasoning is explained once).

---

### `test/01/the-coach-knows-today`

**Needs:**

- CONFLICTS test/02/does-this-school-want-a-score — both edit
  `prompts/coach-system-prompt.md` and so both mint a coach prompt version from
  the same file (RFC 185: `bin/prompt-seed` cuts a version at land iff the body
  differs). Different regions of it — a date/grade block here, D4's explain-once
  copy there — so this is a rebase risk, **not** an order: whichever lands
  second rebases and cuts the later version.

**What and why.** The coach is told today's date and the student's expected
graduation date, and derives their year of school from the two. Without this the
coach cannot say "you are a junior; registration closes in eight days" — it
knows neither half of that sentence. Every other slice is blocked on it, and it
is valuable alone across every deadline question in the product.

**Foundations.** `students.expected_high_school_graduation_year/_month/_day`
(schema `0005`), `StudentsDao.findById`, `CoachingService.composeSystem`, the
defaulted-`Clock` constructor pattern, `prompts/coach-system-prompt.md` (v27).

**Decided here.** The block is **always present** — the `composeSystem` early
return at `:851` must be restructured, not worked around, and a test must prove
the block renders for a student with **no** money profile, no commitments and no
fit suggestions. `Clock` is injected as a defaulted constructor parameter,
matching the two existing sites. Year of school is derived, never stored.

**Left to /ship's design.** Whether the derivation lives in `:common` beside
`PartialDate` or in the coaching module; the exact wording of the block.

**Acceptance criteria.**

- The assembled system text always contains today's date and the student's
  expected graduation date, for every student, including one with no money
  profile. An executed test asserts the no-profile case.
- The coach can state the student's year of school and the time to a named date
  without being told either in the conversation.
- **D3 refusal:** where the student's own words contradict the stored graduation
  date — a **college** junior asking about the SAT — the coach says plainly that
  unicoach coaches US high-school applicants and does not advise on GRE, GMAT or
  LSAT, instead of answering anyway. An executed test covers it.
- **D13:** the prompt never treats "the SAT" as the default noun.
- A graduation date whose month and day are absent still yields a year of
  school; the coach never invents a month.
- **Door:** the chat, on every message. No new surface.

**First-session test.** A new student with an empty profile asks "how long have
I got?" and gets an answer anchored to a real date.

---

### `test/02/does-this-school-want-a-score`

**Needs:**

- CONFLICTS test/01/the-coach-knows-today — the same coach-prompt file, the same
  reason. Note what this edge is NOT: a per-school policy is not a date, so
  nothing in this slice waits on `01`'s work.

**What and why.** `college_admissions_profile` gains a fourth section: whether
this school requires, does not require, or will not look at a test score. With
86% of colleges test-optional, this is the fact that makes every other sentence
in the brief honest, and it is the cheapest real feature here — the data is
already in the database and already filterable in search.

**Foundations.** `college_ipeds.test_policy`, `admission_test_policies`,
`CollegeAdmissionsService`, `AdmissionsField.kt:18-23`, the CDS citation pattern
already used for merit aid and deadlines.

**Decided here.** IPEDS `ADMCON7`, not CDS C8 (D5). **Zero DDL.** The claim
always carries its collection year (**2023**) in the coach's own words — brief
0008's rule — and a standing sentence that a school can change its policy, with
the school's own page as the place to confirm. No warning, no flag, no score: an
absent policy says "we do not have this school's policy", never anything about
the school.

**Left to /ship's design.** The wire shape of the fourth section; whether the
three slugs are re-worded at the copy seam.

**Acceptance criteria.**

- The tool returns the policy with its IPEDS citation and collection year for a
  school that has one, and an explicit "not reported" for the 4,337 that do not.
- The coach never states a policy without its year.
- The coach never generalises one school's policy to the list.
- **D4 second arm** lands here: the first time testing comes up the coach
  explains, once, what these tests are for, when a student **needs** one, when
  they would **want** one anyway, and when they are better off **not** sitting
  one — then answers the actual question.
- **Door:** the chat, via `college_admissions_profile`, reached by asking about
  any school on the list.

---

### `test/03/the-range-not-the-average`

**Needs:**

- CONFLICTS test/02/does-this-school-want-a-score — both edit
  `CollegeAdmissionsService` and the admissions wire. A rebase risk, not an
  order.

**What and why.** Surface the 25th/75th percentile score bands so a student sees
a range she can locate herself in, rather than an admitted-student average that
reads like a bar.

**Foundations.** The Scorecard fetch (`SATVR25/75`, `SATMT25/75`, `ACTCM25/75`),
`colleges`, `log_college_version()`, RFC 180 migration naming.

**Decided here.** New columns on `colleges` — **this slice adds DDL and the
instruction directs /ship to present the proposed DDL explicitly at its approval
gate** (standing D10 of brief 0001), including the
`CREATE OR REPLACE log_college_version()` restatement that preamble item 3
requires. **D16 governs the fallback.** The seed CSV is gitignored, so coverage
claims are asserted against a fetch, not a committed file — the slice says so.

**Acceptance criteria.**

- Bands are stored per college and exposed with their Scorecard citation and
  year.
- The coach prefers the band; where only an average exists it follows D16
  exactly, and it never presents an average as a threshold.
- An executed test writes a `colleges` row after the migration, proving the
  version trigger was restated (the `42703` trap).
- The coach never says a student "needs" a score to be competitive.
- **Door:** the chat, beside the policy from `test/02`.

---

### `test/04/the-testing-calendar`

**Needs:**

- BLOCKS test/01/the-coach-knows-today — technical. The arithmetic IS the slice,
  and it is meaningless without today's date and the student's year of school,
  neither of which reaches the coach until `01` lands.
- PREFER test/02/does-this-school-want-a-score — product judgement. The calendar
  can be spoken without per-school policy, but doing so tells the 86% of
  students whose schools are test-optional to sit a test nobody asked them for.
  Overridable by Ian.

**What and why.** The SAT and ACT calendars, and the subtraction nobody does for
one student: from her earliest real deadline, backwards through score release
and registration, to the date she must act. This is the answer to the question
your daughter asked.

**Foundations.** `college_deadlines` (round, cycle, closing month/day),
`test/01`'s date, the CDS seed + `PROVENANCE.json` pattern for committed
reference data, `bin/` conventions (getopts, short options, stderr logging).

**Decided here.** A new table seeded from a **committed** CSV (unlike Scorecard,
this is hand-maintained and must be reviewable in a diff): per row the test, the
date, the registration deadline, the late deadline where one exists, and the
score-release date, with a cycle. **DDL at /ship's gate.** SAT and ACT are
symmetric (D13) — neither is the default. **D7's expiry is a hard behaviour**:
past it the coach says it does not know the current dates and points at the
publisher. **D20 makes staleness a build failure.** **D14's four guardrails**
are encoded here as prompt rules: Score Choice is per date and does not cover
the free sends queued at registration; superscoring is the college's policy and
is stated only from a recorded one; self-reported scores carry revocation risk;
a fee waiver is one per lifetime, carries unlimited free sends, and wants
requesting 1-2 weeks before the registration deadline — which makes the waiver
its own earlier deadline.

**Left to /ship's design.** Whether the arithmetic is a chat tool, a computed
block on an existing tool, or a service read; the table's exact shape.

**Acceptance criteria.**

- Given a student's list, the coach names the earliest binding deadline, the
  last usable sitting for each test, and the date registration closes — and
  shows the subtraction rather than asserting a conclusion.
- Past the calendar's expiry the coach **refuses** to name a date and points at
  College Board / ACT. An executed test drives the clock past expiry and asserts
  the refusal.
- No test date, registration deadline or score-release date is ever produced by
  the model from memory; every one comes from the table.
- The four D14 guardrails each have an executed test.
- **Door:** the chat. The student asks "when do I need to take the SAT" — the
  literal question — and gets her own dates.

**First-session test.** A junior with one school on her list asks the seed
question and gets a dated, sourced, school-anchored answer in one reply.

---

### `test/04.5/one-date-seam`

**Needs:**

- BLOCKS test/04/the-testing-calendar — technical. This slice consolidates the
  date renderings that exist, and `test/04` is adding one of the largest of them
  (test dates, registration deadlines, score releases, a resolved school
  deadline). Consolidating before it lands would leave a seam that immediately
  has a fifth caller written against the old shape.

**What and why.** Ian, 2026-09-12 (**D21**): _"Dates are tricky... we should
have a single place where we format dates. And we should start with a US-centric
format e.g. Sep 12, 2026."_ Today a user-facing date is rendered in at least
four places, in at least three formats, and this brief added one of them — RFC
186 says `15 June 2028`, day before month, which no US family writes. The point
is not the format. It is that a date's shape is currently a property of
whichever file happens to print it.

**Foundations.** `CoachingService.monthName` (RFC 186),
`CollegeAdmissionsChatTool`'s `datePhrase` (RFC 148), `admin-web`'s
`CellRender.DATE_FORMAT` — which is **already** `MMM d, yyyy`, so the target
format is a precedent in the tree, not an invention — and whatever `test/04`
lands.

**Decided here.**

- **One seam**, and every user-facing date goes through it. `Sep 12, 2026`.
- **The partial forms are derived from the same seam, never hand-written beside
  it**: `Sep 2026` when the day is unknown, `2026` when only the year is, and
  the year-less form (`Nov 1`) that a Common Data Set cycle genuinely has no
  year for. RFC 148's no-year rule is preserved — it is a fact about the SOURCE,
  and this slice must not let the seam invent a year to make a sentence tidy.
- **Ours vs theirs survives the merge.** RFC 186 says "the day is not recorded"
  (our gap) where the admissions tool says "not reported" (the source's
  silence). Those are different claims and the seam keeps both; a single wording
  here would be a regression dressed as consistency.
- **`test/04`'s resolved deadline stays labelled as ours** (RFC 197 D4). A
  shared formatter must not launder a date we derived into one a school
  published.

**Left to /ship's design.** Which module owns the seam (`:common` is the obvious
home, and note `PartialDate` lives in `:db`, not `:common` — RFC 186 found that
the hard way); whether the iOS and `public-web` surfaces are in scope or are a
follow-on; whether `admin-web` adopts the shared seam or keeps its own.

**Acceptance criteria.**

- One named seam formats every user-facing date in the modules it covers, and a
  test enumerates the callers: a NEW hand-rolled month name or date pattern in
  those modules fails the build. **The guard does not live in a file the seam's
  own work rewrites** (the standing rule below).
- `15 June 2028` is gone. The coach says `Jun 15, 2028`.
- A date we DERIVED is still spoken as ours, and a date with no source year
  still has no year.
- No user-visible date changes meaning — only its shape.
- **Door:** every surface that already says a date; this slice adds no new one.

**First-session test.** A junior with a partial graduation date on file and one
school on her list sees the same date shape in the coach's opening line, in her
school's deadline, and in the testing timeline.

### `test/06.1/one-answer-one-date`

**Needs:** — (nothing: the two components are extracted from code that already
exists, and no slice in this brief has to land first for them to be correct)

**What and why.** Two shapes in this repo are shared in Kotlin and copied in
SQL, and this brief was about to copy both again. D23 and D24 decide what they
become; this slice builds them and migrates the existing copies. It ships no
user-visible behaviour, and that is the point — the surface is identical in
every option, so the slice is judged on whether the seam moved, not on whether
the work did.

**Foundations.** `PartialDate` (`:db` models, a sealed interface already
domain-agnostic by its own KDoc), `AnswerStatus` and `MoneyProfile` (RFC 134),
the `academic_year` / `cds_source_year` / `slug` domains as the precedent for
declaring a vocabulary once, `students.expected_high_school_graduation_*`
(schema `0005`).

**Decided here (D23, D24).**

- `DATE` normalised to the period start, plus a `date_precision` domain, plus
  one shared `is_period_start` CHECK. **DDL at /ship's gate.**
- `DOMAIN answer_status AS TEXT`, one `value_iff_answered` function, and a
  renderer that takes `(status, value, label)` rather than a `MoneyProfile`.
- **`students` migrates in this slice**, not later. Shipping the component and
  leaving the duplicate is the thing Ian objected to.
- The `(date, precision)` pair never escapes the DAO.

**Left to /ship's design.** Whether the answer-status domain and the date
precision domain land in one migration or two; whether the money tables' status
columns retype in the same run or in a follow-on (measure whether retyping a
`TEXT` column to a domain over `TEXT` rewrites the table or only validates it —
both money tables are one row per student, so it is a footnote either way);
where the shared test helper for partial dates lives.

**Acceptance criteria.**

- One definition of each vocabulary in the schema. A new table declaring its own
  copy of either fails review, and the domains are the only way to spell them.
- `students` reads and writes through the new storage with **no change above the
  DAO**, because every layer already holds a `PartialDate`. The ~37 raw-SQL
  inserts across the test corpus move onto a shared helper rather than being
  hand-edited into the new shape.
- A stored `2026-01-01` at year precision can never be rendered as a month or a
  day. **The guard for this lives outside the files this slice rewrites** — the
  standing rule that the artifact under change cannot be its own witness.
- No user-visible behaviour changes. The graduation-date sentences RFC 186
  landed render byte-identically before and after; a test pins that.
- **Door:** none of its own. It is consumed by `test/06`, and every existing
  date and declinable answer keeps its current door.

**First-session test.** Unchanged from today: a student with a year-only
graduation date on file hears her year of school and never hears an invented
month.

### `test/05/your-state-already-pays`

**Needs:**

- BLOCKS test/01/the-coach-knows-today — technical. The in-school programmes are
  junior-year facts, so the answer needs the derived year of school.
- PREFER test/04/the-testing-calendar — product judgement. The in-school windows
  read as an exception to the national calendar, so saying them first inverts
  the explanation. Overridable by Ian.

**What and why.** For a large share of students the first sitting is already
scheduled, in school, and free. Telling such a family to pay for a Saturday they
did not need is a trust-losing answer.

**Foundations.** `money_profiles.residency_state` (already in the prompt at
`CoachingService.kt:886-889`), D10's inference rule.

**Decided here.** **Infer from the stored state; never ask for the state
again.** A ~50-row state table — ACT-mandated, SAT School Day, district-choice —
each row carrying a state-DOE `source_url` and a `checked_on`, because the best
public aggregate is secondary and these programmes change yearly. The table
validates the two-letter code itself (there is no state enum). **DDL at /ship's
gate.** The single permitted question is the **school-level** one: does your
school run it, and when. Never gating; "I don't know" is a complete answer.

**Acceptance criteria.**

- With a known residency state the coach states the state's programme without
  asking anything.
- With an unknown state the coach does not invent one and does not re-ask beyond
  the existing residency invitation.
- The coach never says "your school does X" from state data alone — the state
  answers the state's question only.
- **Door:** the chat, inside the answer to the timing question.

---

### `test/05.1/external-documents`

**Needs:**

- BLOCKS test/05/your-state-already-pays — technical. This slice generalises the
  citation the state programmes carry, and `test/05` ships the rows that first
  need two documents. Building it first would have blocked a run that was
  already implemented; building it second means Utah is held out of the seed
  until it lands, which `test/05` records by name.

**What and why.** A citation in this repo is a URL column, copied into eight
tables, and one of them (`source_documents`) is a college-keyed attempt to
de-duplicate the other three. None of them can hold a citation about a
jurisdiction, and none can say WHEN a capture was taken. D26 decides the model;
this slice builds it and adopts it for the state programmes.

**Foundations.** `source_documents` (RFC 170 D13) and its link-rot columns (RFC
200, `found` / `not_found` / `unknown` plus a checked-on date, measured across
441 documents), `bin/cds-link-check`, the CDS seed's `source_url` +
`archive_url` pattern.

**Decided here (D26).** **DDL at /ship's gate.**

- `external_documents`: one row per published document — URL, publisher, kind,
  link-rot state, last checked.
- `document_captures`: one row per snapshot — capture URL and the date captured.
  A capture is a thing we made OF a document, on a date, possibly more than
  once; a single `archive_url` column cannot date itself.
- **One join table per owner, never a polymorphic owner**, carrying WHICH CLAIM
  the document supports.
- Adopt it for `admission_test_state_programmes`, and add the rows `test/05`
  held out for being unsupportable from one page (Utah, and any the run names).

**Left to /ship's design.** Whether `bin/cds-link-check` is general enough to
check a state URL, or whether that waits; whether the publisher vocabulary is a
domain or a reference table; whether captures carry a checksum.

**Acceptance criteria.**

- A row whose claims come from two documents can cite both, and each citation
  says which claim it backs.
- A capture carries the date it was taken, and a citation rendered from it says
  "as it was on that date" rather than implying the live page says so today.
- No existing citation loses a guarantee: what is `NOT NULL REFERENCES` today
  stays a real key.
- **Door:** none of its own — it is the same citations, correctly modelled.
  `test/05`'s door does not change.

---

### `test/05.2/one-home-for-every-citation`

**Needs:**

- BLOCKS test/05.1/external-documents — technical. There is nothing to migrate
  the eight tables ONTO until the model exists.

**Status:** SPECCED, not scheduled. **A candidate for its own brief**: it
touches the money layer, the ingest and three committed seeds, and its blast
radius is wider than anything else in brief 0010.

**What and why.** Move every table that carries its own citation columns onto
`external_documents`: the three CDS tables (`0054`), `policy_parameters`
(`0077`), the canonical money tables (`0083`), `source_documents` (`0087` —
which becomes a join table or is retired), the test calendar (RFC 197), and the
state programmes (RFC 208). Until this lands, the repo has two ways to cite a
document, which is the defect D26 exists to end rather than to double.

**Decided here.** Nothing new: it is D26 applied to what already exists. **DDL
at /ship's gate**, and it will be the largest migration this brief has proposed.

**Acceptance criteria.**

- One way to cite a document in the schema. A table declaring its own
  `source_url` fails review.
- Every existing citation renders byte-identically before and after, pinned by a
  test — this slice changes storage, never a sentence.
- The RFC 200 link-rot check runs over every document, not only the college
  ones.
- **Door:** none. Nothing a family sees changes.

### `test/06/your-scores`

**Needs:**

- BLOCKS test/06.1/one-answer-one-date — technical. This slice's table was
  parked at its gate for adding a fourth hand-rolled variable-precision date and
  a fourth copy of the declinable-answer pattern. Both components must exist
  before the table that consumes them is written; building them afterwards means
  shipping the duplicate and promising to remove it, which is how a third copy
  became a fourth (Ian, 2026-09-14).
- PREFER test/03/the-range-not-the-average — product judgement. A stored score
  with nothing to locate it in is a number we collected and cannot use; the band
  is what makes recording it feel worth the ask. Overridable by Ian.

**What and why.** Remember the student's own scores, so a later session can say
"you are ten points below the next money band".

**Decided here.** A new table, scores by test and date. **DDL at /ship's gate.**
Invited, never demanded; declinable with the decline never re-asked; never
gating any other answer; removable — the money-profile pattern exactly (D12, and
non-negotiable 5). Self-reported by the student, and labelled as such: we never
claim to hold an official score.

**Acceptance criteria.**

- A student can record, change and delete a score in conversation.
- A decline is recorded and never re-raised by the coach itself.
- No answer anywhere in the product is gated on a stored score.
- The coach never re-states a stored score as if it were verified.
- **Door:** the chat. `profile/02/your-details-screen` (brief 0007) is the
  natural explicit surface later; this slice does not build it.

---

### `test/07/a-score-is-money`

**Needs:**

- BLOCKS test/04/the-testing-calendar — technical. "The last sitting that still
  makes this school's money deadline" is the calendar's subtraction; without it
  the slice can state dollars but not the date, which is the half that matters.
- BLOCKS test/06/your-scores — technical. "You are ten points below the next
  band" requires a stored score to compare against.

**What and why.** The differentiator. At a number of public flagships a score
band **is** a price: Alabama pays \$6,000/yr at SAT 1250 and \$8,000/yr at 1260,
requires a submitted score to qualify at all, and **does not superscore for
scholarship purposes**. For Fall 2027 entry its scores are due Dec 4, which
makes the Dec 5 SAT useless and Nov 7 the last usable sitting, with registration
closed Oct 23. A ten-point miss is \$8,000 over four years, lost on a date
nobody announces.

**Decided here.** Hand-curated, narrow and sourced (D8): one row per school per
cycle, each with `source_url`, `cycle` and `checked_on`. **DDL at /ship's
gate.** The coach speaks a grid **only** for a curated school and says plainly
it does not know for the rest — and "we checked and this school publishes no
automatic award" is a recorded, speakable answer, not a silence. **Cannot be
scraped unattended** (pages are re-keyed per cycle); the annual re-check is
D20's discipline again.

**Acceptance criteria.**

- For a curated school on the list, the coach states the band, the dollars, the
  submission requirement, the superscore rule **for scholarships**, and the last
  sitting that still makes the deadline.
- For any other school it says it does not know, and never estimates.
- Test-optional-for-admission is never spoken as test-optional-for-money.
- **Door:** the chat, and it belongs in the cost report's story — the slice
  names which.

---

### `test/08/the-psat-is-money`

**Needs:**

- BLOCKS test/01/the-coach-knows-today — technical. Only the junior-year sitting
  qualifies for National Merit, so every sentence this slice speaks is
  conditioned on the derived year of school.
- PREFER test/07/a-score-is-money — product judgement. Same curated award
  dataset and the same `checked_on` discipline; building the second curation
  seam before the first invents it twice. Overridable by Ian.

**What and why.** The PSAT/NMSQT is not an admissions test — no college ever
sees a score — but it is the only gate to National Merit, and Finalist status
triggers automatic university packages: Oklahoma publishes \$153,450
non-resident, Alabama ~\$180-200k out-of-state, Maine 100% tuition with no
first-choice requirement. It is school-administered in an October window, the
student does not register, and **only the junior-year sitting counts**.

**Decided here.** All five corrections in the brief bind this slice. The
**alternate entry** route (letter to `ae@nmerit.net`, postmarked by April 1) is
spoken whenever the window is missed — "gone forever" is false. The
**Semifinalist cutoff is per state**, so the stored residency state is used and
a national cutoff is never quoted as hers. The **~1.25% base rate** (3.5M sit,
1.2M enter, 15,000 Finalists, 6,700 win) is stated whenever the six-figure
numbers are. Awards are the same curated dataset as `test/07`, including the
three schools recorded as publishing **no** award, and Auburn's in-state-only
cut as the worked example of why `checked_on` exists.

**Acceptance criteria.**

- For a student not yet in junior year, the coach names the window ahead and
  what it is worth. For one past it, it names alternate entry and its deadline.
- The base rate appears wherever the large dollar figures do.
- No NMSC or university figure is ever produced from the model's memory.
- The coach never calls the PSAT an admissions test.
- **Door:** the chat, raised by the coach when the student is a junior and the
  October window is near — the one proactive moment in this brief.

---

## Waves

Wave 1 (parallel): `test/01`, `test/02`. Wave 2 (parallel): `test/03` (CONFLICTS
`test/02`), `test/04`. Wave 3 (parallel): `test/05`, `test/06`. Wave 4:
`test/07`, then `test/08`.

Ask `slice-board`, not this list, for what is startable.

## Gate 2 — the spec decisions

**D15 — today is US Eastern. What "today" means.** Both existing `Clock` sites
default to `Clock.systemUTC()`, and UTC-today is not a US family's today.
_Default:_ deadline arithmetic is computed in **US Eastern**, because that is
the timezone College Board and ACT publish and enforce in; the coach states
**dates**, never "hours left", and never says "closes today" without naming the
publisher's timezone. Where residency is known the coach may say the student's
own local date. _Rejected default: per-student timezone inference from state,
which is wrong for the split-timezone states and buys nothing at date
granularity._

**D16 — average only where no band. The score-band fallback.** Bands cover 949
colleges; the average covers 1,037, so ~88 colleges have an average and no band.
_Default:_ prefer the band; where only an average exists, say it and label it as
**the average score of admitted students**, explicitly not a cut-off; never show
both for the same school; never fabricate a band from an average.

**D17 — date the policy, never refuse it. Stating a policy's age.** `ADMCON7` is
collection year 2023, and the volatile schools are the top ~50. _Default:_ every
policy claim carries its year in the coach's words, plus a standing sentence
that a school can change its policy and its own page is where to confirm. We do
**not** refuse an older policy — refusing 1,936 schools' only policy data would
be worse than dating it honestly.

**D18 — four waves, 01 and 02 first. Slice order.** _Default:_ the four waves
above, with `test/01` and `test/02` dispatched first and in parallel.

**D19 — hand-typed data is a committed CSV. Where hand-maintained data lives.**
_Default:_ a database table seeded from a **committed** CSV with a
`PROVENANCE.json`, following the CDS seed pattern — hand-typed data must be
reviewable in a diff. This differs from Scorecard, whose seed is gitignored
because it is fetched.

**D20 — staleness fails the build. Staleness is a build failure, not a silent
wrong answer.** _Default:_ a test fails when a committed calendar, state table
or merit-grid row is within **90 days** of its expiry, or is past it. Rot is the
competitor's failure mode (CollegeVine's 2016 article still ranks first for this
question); we make ours loud and early rather than discovering it from a family
who missed a date.
