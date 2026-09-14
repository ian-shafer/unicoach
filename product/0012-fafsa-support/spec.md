# Brief 0012 — spec: FAFSA support

Handle `fafsa`. Slice IDs `fafsa/NN/name`. Gate-1 decisions D1-D7 are in
`brief.md`; D4 was amended by Ian and is the reason this spec has a data store
in it at all.

## DISCOVER corrections — where the brief was wrong

Four DISCOVER agents reported 2026-09-14/15: `discover/repo-grounding.md` (the
code), and `discover/state-deadlines-a|b|c.md` plus
`discover/state-deadlines-summary.md` (the data). The load-bearing corrections:

| The brief assumed                                          | What is true                                                                                                                                                                                                                                                  |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The federal-aid tool reads `aid_policy_facts`              | It reads `policy_parameters` (schema 0077, INTEGER-valued, 24 slugs). `aid_policy_facts` was DROPPED by 0087.                                                                                                                                                 |
| `public-web` would need a new read path                    | `public-web` already depends on `:db` and `:service` in-process and owns a `Database` at boot (RFC 155 D-E). One shared read service, no HTTP hop, no new config.                                                                                             |
| Nothing in `public-web` renders a cited claim              | `CostReportPage.kt`'s `sourceLink` already does. Only the **"as of" date half** is missing.                                                                                                                                                                   |
| Per-school FAFSA coverage is 272 (or 278)                  | **219 of 366 CDS-filing colleges**, measured on the committed seed. 272 and 278 are dated measurements of a corpus that shrank upstream. State it as measured, never hardcode it.                                                                             |
| Region grouping needs new vocabulary                       | `us_states.ipeds_region -> ipeds_regions.name` already exists (schema 0060). Join at read time. But an FK to `us_states` is a TRAP: those rows come from the ingest, not a migration, so a seed migration referencing them cannot apply to the test database. |
| Citations hang off `source_documents`                      | Unusable here: its `college_id` is NOT NULL and a state agency belongs to no college. Use the per-row `source_name`/`source_url` shape from 0077.                                                                                                             |
| The admission-test calendar's staleness rule can be copied | It cannot. Deadlines **recur annually**, so the live set is the AWARD YEAR, not the date; and `currentAwardYear()`'s July-1 rule would serve last cycle's deadlines for nine months a year. Needs its own `currentFilingYear` clock.                          |
| Only one slice may edit the coach prompt at a time         | **Retracted by Ian** (brief 0011 spec). Two slices adding paragraphs is an ordinary rebase; the second lander reads the first's paragraph.                                                                                                                    |

## The dataset we already hold

The three harvest agents covered **all 51 jurisdictions** (50 states + DC) and
produced `discover/state-deadlines-merged.csv`: **148 deadline rows**, 127
verified against a fetched source page, **21 unverified**, and **4 jurisdictions
with no verified row at all — AR, DE, NC, NH**. 137 rows cite the state agency
itself; 7 an archived copy; 4 a secondary aggregator. Kinds: 89 a real date, 48
a rule in words, 8 an explicit "this state publishes none", 3 we could not
establish. 119 rows are the 2026-27 cycle across all 51; 28 rows are 2027-28
across 18.

**This corpus is the argument for D4, and it is also a warning.** The obvious
sources are wrong: a widely-used third-party table conflicts with the agencies
in FL, DC, IN, KS and LA; Oregon's and Tennessee's own live pages still tell
students to file "after October 1", which has not been the open date since
2023-24; Massachusetts is 30 June, not the 1 May everyone prints; Michigan's
July 1 belongs to two closed programmes; Illinois' alternative-application route
closed 30 July 2026. Several states need a **separate state application** on top
of the FAFSA (TN, WV, VA, OK, UT); Pennsylvania's answer depends on the school
type; New Jersey judges the PROCESSED date and has three. Puerto Rico, Guam and
the US Virgin Islands were not researched.

---

## `fafsa/01/the-deadline-table`

**Needs:** —

**What:** The table the whole beat stands on: `fafsa_filing_deadlines`, plus the
Kotlin models, the two enums and the `currentFilingYear` clock. No rows yet.
Product intent: one store for every FAFSA date, so the page and the coach can
never quote different dates at the same family.

**Foundations:** `db/schema/create-admission-test-calendar.sql` (RFC 197) and
`db/schema/0077.create-and-seed-policy-parameters.sql` are the two precedents;
`academic_year` domain (0087); `AidForm.kt` for the enum-with-`fromValue` shape;
`UsStateCodes.kt` for membership checking; `db/schema/ORDER`.

**Decided here:** the row shape, from `discover/repo-grounding.md` §2.2, which
carries a copy-pasteable DDL. Federal rows live in the same table with
`state IS NULL` (a `scope` column would be a stored derivation), forcing
`NULLS NOT DISTINCT` on the natural key `(award_year, state, program, event)`.
`published_text` is NOT NULL on EVERY row beside an optional `deadline_date`,
because a DATE cannot hold "11:59 p.m. Pacific" and a reader must never mistake
our parse for the publisher's words. Three kinds — `date`, `wording`, `none` —
with a value-iff-kind CHECK. **No FK to `us_states`** (the seed migration would
fail on the test database); membership is checked by the generator and the seed
test. **Left to /ship:** the DAO's read shape, and whether `currentFilingYear`
lives beside the clock in `FederalAidPolicyService` or in its own file.

**This slice adds a table (brief 0001 D10).** Its /ship run MUST present the
full DDL explicitly at its approval gate, not buried in the RFC. The DDL in
`discover/repo-grounding.md` §2.2 is written to be shown as-is.

**Acceptance:** the migration applies to a freshly recreated test database; the
value-iff-kind CHECK rejects a `wording` row carrying a date and a `date` row
carrying none; a row whose date falls outside its own cycle window is rejected
(last cycle's date typed under this cycle's key is the one mistake that makes
this table worse than no table); `form_opens` cannot be `none`. No user-visible
change yet — this slice's door is the next slice.

**First session:** nothing a family sees. This is the only slice in the beat of
which that is true.

---

## `fafsa/02/fifty-one-deadlines-typed`

**Needs:**

- BLOCKS fafsa/01/the-deadline-table — technical. The generator emits
  `INSERT ... ON CONFLICT` against columns that must exist, and its validator
  mirrors the DDL's CHECK constraints by name.

**What:** The rows. A hand-typed, cited `db/seed/fafsa-deadlines/deadlines.csv`
with a `PROVENANCE.json`, a sibling generator `bin/fafsa-deadline-seed` that
validates the CSV and generates the seed migration, its shell-test harness, and
the seed test. Product intent: this is the consolidation D4 asked for — the work
we do once so 51 agency pages do not become a family's homework.

**Foundations:** `discover/state-deadlines-merged.csv` is the starting corpus
(148 rows, already cited and dated) — it is INPUT, not the committed artifact.
`bin/test-calendar-seed` + `bin/test-calendar-seed-scripts-tests` +
`AdmissionTestCalendarSeedTest` are the template. `bin/` conventions bind:
`getopts` short options only, all logging to stderr, the reserved usage exit
codes from `bin/functions`.

**Decided here:** one award year at launch, the cycle now being filed. A row
that the harvest could not verify **does not ship as a fact** (see gate-2 D9).
Every row carries the agency's own words, the agency URL, and the day a human
read it. A state with several programmes gets several rows; a state whose rule
is words gets `wording` with those words; a state that publishes nothing gets
`none` WITH a citation, because a verified absence is not our silence. **Left to
/ship:** the CSV's column order, the exact validator predicates, and whether the
generator is a sibling script or a generalisation of the existing one
(`discover/repo-grounding.md` §3 argues sibling, with evidence).

**Acceptance:** the generator refuses a CSV with a fake date under a `wording`
rule, a state code that is not one of the 59 published USPS codes, a row whose
date is outside its cycle, or a citation with no URL — reporting every fault in
one pass with line numbers. `-n` fails when the committed SQL differs from what
a run would generate, so a hand-edited seed is caught. The seed test fails the
build when the committed corpus has no rows for the cycle now being filed.
Nothing on stdout on a normal run. The four unverified jurisdictions are listed
by name in `PROVENANCE.json` as needing human eyes.

**First session:** still nothing a family sees; the value is unlocked by
`fafsa/03` and `fafsa/04`. This is the largest slice in the beat.

---

## `fafsa/03/one-answer-for-both-doors`

**Needs:**

- BLOCKS fafsa/01/the-deadline-table — technical. It reads the DAO, the row
  model and the filing-year clock.
- PREFER fafsa/02/fifty-one-deadlines-typed — product judgement. The service is
  testable on fixtures, but its superseded-cycle and expired arms and the region
  grouping are only honestly exercised against the real seeded rows, and a
  second fixture corpus can drift from the CSV. Overridable by Ian.

**What:** `FafsaDeadlineService` in `:service` — the single answer both doors
render: the live set for the filing year, region grouping joined from
`us_states`/`ipeds_regions`, the jurisdictions we hold nothing for, and a
three-arm refusal (nothing seeded / the cycle has moved on / the rows expired).
Product intent: D4's "page and coach cannot disagree" is bought here, by there
being one place that decides.

**Foundations:** `TestTimelineService` (the sealed live-or-refusal shape, so no
nullable "expired" flag can sit stale beside a date), `FederalAidPolicyService`
(award-year resolution), `CollegeCostService`'s in-process port precedent.

**Decided here:** the page and the coach never re-derive anything the service
decided — not expiry, not the wording of a rule, not the region map. A refusal
names WHICH of the three it is and points at studentaid.gov and the state
agency. **Left to /ship:** the sealed type's exact shape and whether the clock
is injected or read.

**Acceptance:** when the filing cycle has moved past the seeded corpus the
service refuses rather than serving last year's dates — the named failure mode
is a page showing last cycle's date while every row looks healthy; the
jurisdictions we hold nothing for come back as a list, never as silence.

**First session:** nothing yet; the next two slices are the doors.

---

## `fafsa/04/the-fafsa-page`

**Needs:**

- BLOCKS fafsa/03/one-answer-for-both-doors — technical. The page renders what
  the service returns and derives nothing; without it the page would grow its
  own expiry rule, which is exactly the disagreement D4 forbids.

Not written as an edge, because the board cannot see it: brief 0011's
`beyond/09/who-pays-for-college-advice` is specced but its spec is not on
`main`, so naming it here is a hard error. The relationship is real and is a
CONFLICTS, not an order — both slices build the cited-claim rendering layer and
both touch the same CSS and the same region of `Routing.kt` and `render/`. First
mover builds it, the second inherits it. Whichever starts second re-reads this
paragraph and expects a rebase.

**What:** `GET /fafsa` — the public explainer, with the state-and-region date
table as its centre. Product intent, in Ian's words: we do not make people read
dry federal and state documents; we consolidate and share.

**Foundations:** `Routing.kt` (one line), `render/PrivacyPage.kt` (the page
shape), `Layout.kt`/`siteLayout` (chrome), `CostReportPage.kt`'s `sourceLink`
(the cited-claim renderer that already exists), `LegalPagesTest.kt` (the test
shape). Content comes from `research/fafsa-domain.md` and
`research/support-landscape.md`, both cited per claim.

**Decided here:** the page carries (a) what the FAFSA is now — the Student Aid
Index, the contributor model, and the consent rule whose failure mode is total
(one contributor refusing IRS consent ends federal aid, Pell included); (b) the
edge cases no competitor page covers — divorced or separated parents (the

> 50%-support rule, NOT "custodial parent"), a parent with no Social Security
> number, dependency; (c) the dates: federal, then every jurisdiction grouped by
> region. **D6 is on this page, early and plainly: the FAFSA is free at
> studentaid.gov, and unicoach never fills it in, submits it, or touches
> credentials.** No lead capture, no email wall, no ad, no scores or ranks.
> Every load-bearing claim carries a public URL and a visible "as of" date. A
> claim the research marked unverified may not appear. **Left to /ship:** the
> prose voice, the table's mobile layout, and whether the page joins the header
> nav or the footer.

**Acceptance:** a person with no account reaches `/fafsa` and sees every one of
the 51 jurisdictions — a date where there is one, the agency's own words where
the rule is words, an explicit "this state publishes no deadline" where that is
verified, and OUR OWN admission where we have not verified it. Where a state
also demands its own application, the page says so — a table showing one date
per state would make families miss half the work. Every external claim resolves
to a live URL recorded in the page's source. The page states the FAFSA is free
before it states anything else about filing. The test asserts the rendered
jurisdiction count, so a silently half-rendered table fails.

**First session:** a parent with no account, in one visit, learns what the form
now is, what their state's date is, and that we did not charge them to know it.

---

## `fafsa/05/the-coach-speaks-the-same-dates`

**Needs:**

- BLOCKS fafsa/03/one-answer-for-both-doors — technical. The same service and
  the same refusal arms; without it the tool has no answer to speak.
- CONFLICTS fafsa/06/fafsa-process-answers — both add paragraphs to
  `prompts/coach-system-prompt.md` and so both mint a prompt version. An
  ordinary rebase, not a hold: the one-editor rule is retracted.

**What:** A chat tool over the same `FafsaDeadlineService`, plus the prompt
sentences that route a deadline question to it. Product intent: a family asking
in chat gets the same date the page shows, and never a date from the model's
memory.

**Foundations:** `AdmissionTestTimelineChatTool` + its registration in
`rest-server/Application.kt` are the exact precedent; prompt v35 lines 160-166
are where FAFSA routing already lives.

**Decided here:** the coach never states a FAFSA deadline from memory; when the
service refuses, the coach says which of the three reasons it is and points at
the state agency. Value before ask: the coach answers the deadline question
without requiring a profile, a list, or an account state it does not already
have. **Left to /ship:** the tool's argument shape (whether it takes a state or
reads the student's residency answer) and the prompt wording.

**Acceptance:** for a student whose state is known the coach names that state's
date and its source; for a state whose rule is words it speaks the words and
does not invent a date; after the cycle turns over it refuses instead of quoting
last year. The tool's output and the page's table come from one call path — a
test proves the same state renders the same text through both doors.

**First session:** a student asks "when is my FAFSA due?" in chat and gets their
state's answer, cited, in the first session, with no profile set up.

---

## `fafsa/06/fafsa-process-answers`

**Needs:**

- CONFLICTS fafsa/05/the-coach-speaks-the-same-dates — the same coach-prompt
  file, the same reason. A rebase risk, not an order. Nothing blocks this slice:
  `aid_form_requirements` is already landed.

**What:** The coach's answers to the FAFSA questions that are not dates: who
files, the contributor and consent model, divorced or separated parents, a
parent with no Social Security number, dependency, and what happens after
submission. Cited prose at the tool edge plus prompt routing — no new table
(D3). Also surfaces harder what already ships: which forms a specific school
requires. Product intent: these are the questions static pages answer worst and
the official help line answers least (about three quarters of calls went
unanswered at the 2024 peak).

**Foundations:** `FederalAidPolicyChatTool`'s framing-statement consts are the
home for cited prose; prompt v35 lines 160-166 and 194; `AidPolicyWire`'s
`required_forms`; `research/fafsa-domain.md` §4 and
`research/support-landscape.md` §5 for the rules and their citations.

**Decided here:** every rule the coach states carries the document it came from
(the FSA Handbook's Application and Verification Guide, or statute). The
divorced-parent rule is stated as the >50%-support rule and explicitly NOT as
"the custodial parent", because the legacy web is wrong and families repeat it.
D6 is restated in the coach's own voice: the FAFSA is free, we never fill it in
or ask for credentials, and where the answer needs an institution the coach says
which office to call and exactly what to ask. Per-school coverage is stated
honestly as measured (219 of 366 filing colleges today), never as a hardcoded
number. **Left to /ship:** which rules earn tool-edge prose versus a prompt
sentence, and the exact paragraph placement.

**Acceptance:** asked "my parents are divorced, whose information goes on the
form?", the coach gives the >50%-support rule with its source and names the
stepparent consequence; asked about a parent with no Social Security number, it
says an account can be created and does not guess at the current process beyond
what is cited; it never states a dependency answer the statute does not support;
it says the FAFSA is free the first time filing comes up. It asks the family for
nothing to answer any of these.

**First session:** a parent asks the question they were too embarrassed to ask a
counsellor, and gets a cited answer in the first session.

---

## Gate 2 — PROPOSED decisions, presented 2026-09-15 (awaiting Ian)

Defaults pre-chosen. Decision numbering continues from gate 1 (D1-D7).

- **D8 — six slices, in this order.** `fafsa/01` table, `02` the typed rows,
  `03` the one read service, `04` the page, `05` the coach's dates, `06` the
  coach's process answers. `fafsa/06` needs nothing and can start immediately in
  parallel, which is the cheapest early value if the data work slips.
- **D9 — an unverified date never ships as a fact.** A row the harvest could not
  verify against the publisher's own page is not committed. The page prints
  those jurisdictions with our own admission that we have not verified them yet,
  naming the agency to call. Today that is four: Arkansas, Delaware, North
  Carolina, New Hampshire. We do not need a fourth kind in the table for this: a
  missing row IS the unknown, and the page turns it into our sentence.
- **D10 — group by the regions the database already has.**
  `us_states.ipeds_region` exists and is joined at read time, so "grouped by
  region" costs nothing and no new vocabulary is invented.
- **D11 — one cycle at launch.** The corpus ships the award year now being
  filed. The 2027-28 rows the harvest already found are added when their
  publishers confirm them, by the same generator. The build fails when the
  committed corpus has no rows for the cycle now being filed — that is the
  yearly reminder, and it is deliberate.
- **D12 — the page is `/fafsa`.** A short, guessable, linkable URL; placement in
  the site chrome is left to /ship.
- **D13 — the beat covers the 50 states and DC.** Puerto Rico, Guam and the US
  Virgin Islands are out of scope and the page says so plainly rather than
  omitting them silently.
- **D14 — a state's extra application is part of the answer.** Where a state
  requires its own form as well as the FAFSA (Tennessee, West Virginia,
  Virginia, Oklahoma, Utah at least), both the page and the coach say so. A
  single date per state would make a family miss half the work, and that is a
  failure the table's shape must carry, not a copy decision.
- **D15 — the deadline table is shown to Ian as DDL at `fafsa/01`'s /ship gate**
  (brief 0001 D10, standing). The proposed DDL is already written, in
  `discover/repo-grounding.md` §2.2, and is presented as-is.

Ledger (one line per landed slice, appended as each lands):

_(empty — nothing landed yet)_
