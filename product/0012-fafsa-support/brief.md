# Brief 0012 — FAFSA support: the coach answers, and a public page explains

Status:

- Phase: **COMPLETE — all six slices of `spec.md` have landed** (`fafsa/06` RFC
  216, `fafsa/01` RFC 215, `fafsa/02` RFC 223, `fafsa/03` RFC 230, `fafsa/04`
  RFC 238, `fafsa/05` RFC 237, the last two on 2026-09-25). Both doors are open:
  a family can read every jurisdiction's deadline on `/fafsa` with no account,
  and ask the coach the same question in chat, and the two answers come from one
  service. Gate 1 was **APPROVED with one amendment, 2026-09-14** (D4 reversed
  by Ian: consolidate the state deadlines ourselves). Research in `research/`,
  DISCOVER in `discover/` (including the 51-jurisdiction deadline corpus).
- Gate 1 (prioritisation): **APPROVED 2026-09-14, D4 amended** — seven decisions
  D1-D7 below.
- Gate 2 (spec): **presented 2026-09-14, amended 2026-09-15** — D9 (say we tried
  and could not) and D10 (`us_states` is not ours yet) amended by Ian, and D16
  added after he flagged the in-flight `external_documents` table.
- Handle: `fafsa` (slice IDs `fafsa/NN/name`).
- Ledger: at the end of this file, one line per landed slice. `slice-board`
  reads the ledger from THIS file, never from `spec.md`. **`fafsa/05`'s line is
  still owed** — it landed as RFC 237 (main@a8c9b044, 2026-09-25) and its own
  run has not closed its books yet.

## The question

Ian, 2026-09-14, verbatim: _"I want to support students and parents asking about
the FAFSA. I'm not sure what this support should look like because I don't know
a lot about the FAFSA. I also want to have a public page giving high-signal
information about the fafsa, simila to our "who pays for college" HTML page."_

Two asks, one open question. The asks: (1) the coach should handle FAFSA
questions from students and parents; (2) a public `public-web` explainer page
about the FAFSA, in the pattern `beyond/09/who-pays-for-college-advice`
established (specced in brief 0011, gate-2 approved, **not yet landed** — the
FAFSA page would be its sibling, not its copy). The open question: Ian does not
yet know the FAFSA well enough to say what "support" means, so PRIORITISE must
first establish what the FAFSA actually is today, what families actually ask,
and where existing support is thin — then gate 1 decides the shape of the bet.

## What the repo does today (grepped, not speculated)

The FAFSA is not a stranger here. Four foundations already stand:

| Foundation                               | What it is                                                                                                                                                                                                                                                                                                                                 | Where                                                                                                                                            |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `federal_aid_policy` chat tool (RFC 159) | Pell Grant + Direct Loan policy parameters resolved to the latest verified award year, per-document citations, the July 1 boundary, and the student's **dependency answer** echoed tri-state. Takes no input — federal policy is the same for everyone.                                                                                    | `service/.../aid/FederalAidPolicyChatTool.kt`, `policy_parameters` table (schema 0077; the modeled-ahead `aid_policy_facts` was DROPPED by 0087) |
| Prompt routing to it                     | The coach is told to reach for that tool for borrowing-limit questions and "what the FAFSA works out for them — the Student Aid Index — rather than answering either from memory."                                                                                                                                                         | `prompts/coach-system-prompt.md` (v35) line 166                                                                                                  |
| Per-school form requirements (RFC 170)   | `aid_form_requirements` from each school's own CDS filing: FAFSA, CSS Profile, noncustodial Profile, institutional/state forms. FAFSA coverage documented as 272 schools, but the committed seed carries `fafsa_required` for only 219 distinct colleges — reconcile before spec; "not listed in this school's CDS", never "not required". | `db/.../models/AidForm.kt`, schema 0087, `college_cost_profile` tool                                                                             |
| The explainer-page pattern               | `public-web` render stack (`Layout.kt`, `PrivacyPage.kt`, `TermsPage.kt`), `Routing.kt`, `LegalPagesTest.kt`; brief 0011 **D14** content rules (every load-bearing claim carries a public URL and fetch date, visible "as of", no lead capture, unverified claims may not appear).                                                         | `public-web/src/main/kotlin/ed/unicoach/web/render/`, `product/0011-beyond-money/spec.md`                                                        |

What does NOT exist, at first grep: no FAFSA **dates** anywhere (open date,
federal/state/school deadlines) — the only dated calendar in the product is the
admission-test one (RFC 197), which is a pattern, not coverage; no SAI
arithmetic (deliberately — the tool serves facts and the coach narrates, brief
0003's no-arithmetic rule); no state-aid data; no notion of the student's own
FAFSA status (filed / not filed / needs to).

## Candidate shapes of "support" (to be ranked by research, decided at gate 1)

| #  | Candidate                                                                                                                                                                           | Existing foundation                                        | First-look risk                                                                                                                                |
| -- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| C1 | **Coach answers FAFSA process questions** — what it is, who files, dependency, what happens after submission — from a cited fact store (extend `aid_policy_facts` or sibling table) | RFC 159 tool + prompt routing already carry Pell/loans/SAI | Scope creep: "the FAFSA" is a big surface; needs a fact vocabulary with citations, not prose                                                   |
| C2 | **The public FAFSA explainer page** — high-signal, cited, no account                                                                                                                | `beyond/09` pattern + D14 rules; page stack exists         | `beyond/09` has not landed, so this may build the pattern first; content must survive yearly change (the test-calendar expiry lesson, RFC 197) |
| C3 | **FAFSA calendar** — open date, federal deadline, state deadlines, "file by" nudges keyed to the student's list                                                                     | RFC 197's seeded-calendar + expiry-refusal pattern         | State deadlines are ~50 rows of yearly hand-typed data; expiry discipline required                                                             |
| C4 | **Per-school FAFSA answer** — "does THIS school need it, by when"                                                                                                                   | RFC 170 forms + college list door                          | Deadline data per school is thin/unverified; forms half already shipped                                                                        |
| C5 | **Student FAFSA status in the money profile** — filed? planning to? — so the coach can narrate next steps                                                                           | money profile mechanism (RFC 134+), value-before-ask ethos | An ask before its value is shown violates D12 unless sequenced after C1/C2                                                                     |

Gate 1 will rank these; the two Ian named (C1, C2) are presumptively in unless
research argues otherwise.

## Success criteria for the decision

1. Gate 1 can say, in one sentence each, what a student asks, what a parent
   asks, and what the page answers — grounded in the domain research, not
   guessed.
2. Every candidate chosen names its door (coach tool, public URL) and its data
   source with citation discipline equal to the money store's.
3. The bet survives the FAFSA's yearly cycle: anything dated carries the RFC 197
   expiry lesson; anything about "the new FAFSA" is written against the current
   award year, with an "as of" date.
4. Nothing chosen requires arithmetic the product refuses elsewhere (no SAI
   estimator that quietly becomes a promised award — Pell is "eligibility and a
   range, never a promised award").

## Synthesis — what the research agrees on (2026-09-14)

All three reports are in `research/`; every load-bearing claim below carries a
citation there. Method caveat, honestly: the `websearch` skill had no API key,
so both web reports lean on directly-fetched primary sources (Federal Register,
FSA Handbook, ED, GAO, NCAN, statute) and mark studentaid.gov-only datapoints
UNVERIFIED (it is a JS SPA that returns an empty shell to fetchers).

**The domain.** The post-2024 FAFSA is a different form: SAI replaced EFC (floor
-1,500), every "contributor" (parent/spouse) needs their own account and must
consent to IRS data exchange — **one parent refusing consent kills the student's
federal aid, Pell included**. The 2026-27 form opened 24 Sept 2025; federal
deadline 30 June 2027; **state deadlines are far earlier and are where money is
actually lost** (TX 15 Jan, CA 2 Mar; several states "until funds run out").
Numbers: max Pell $7,395; record 54.7% completion (class of 2026); ~$4.4B Pell
unclaimed (class of 2024); top reason for not filing is "we make too much" (26%
of non-filers). The 2027-28 form goes public by 1 Oct 2026 — dated copy written
this month rots within weeks unless cycle-labeled.

**The gap.** No assessed commercial explainer mentions divorced parents, no-SSN
contributors, or dependency AT ALL; top-ranking pages still teach the retired
EFC/SAR; Sallie Mae's hub contradicts itself on one page and misstates the
federal deadline; every commercial page monetizes with loans, 529 leads,
sweepstakes or enrollment marketing. The official channel is authoritative but
capacity-constrained: GAO found ~74% of help-line calls unanswered at peak and
the no-SSN path still being fixed into 2026-27; Aidan is an English-only FAQ
router. **Coach-style support for the conditional questions (divorced parents:
the >50%-support rule, NOT "custodial parent"; no-SSN accounts; dependency edge
cases) has no direct competitor in the surveyed set.**

**The repo.** C1's blocker dissolved on inspection: FAFSA process rules are
mostly stable statutory prose, and the codebase's native homes for cited prose
are prompt sentences and tool-edge consts (the `PELL_FRAMING_STATEMENT` pattern)
— no new table needed for v1. The page pattern is a one-line route + one render
file + one test, BUT no page in `public-web` yet renders a cited claim: the
first explainer (this one, or `beyond/09` who-pays) builds the citation/"as of"
layer and the second inherits it at Privacy/Terms marginal cost. Every coach
candidate contends for the one-prompt-editor-at-a-time slot against brief 0011's
nine unlanded slices. Compliance is clean and cheap: 20 U.S.C. §1090(d) bites
preparers who touch the form or charge for the pipeline; a coach that explains
rules, never touches the form or credentials, and says early that the FAFSA is
free sits outside it — and the disclosure is on-brand.

**Ranked bets:**

| Rank | Bet                      | Domain report                                      | Landscape report                                                                            | Repo report                                                     |
| ---- | ------------------------ | -------------------------------------------------- | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| 1    | C2 public explainer page | Stable-vs-rot split mapped; quotable numbers ready | Four axes competitors demonstrably fail (citations, freshness, edge cases, no lead capture) | M; builds the citation layer beyond/09 also needs               |
| 2    | C1 coach process answers | The conditional questions are the real pain        | No direct competitor for coach-style conditional answers                                    | M as prompt+tool-edge consts; no DDL                            |
| 3    | C4 per-school forms half | —                                                  | —                                                                                           | S: already shipped, under-surfaced; deadline half is L and thin |
| 4    | C3 deadline calendar     | State deadlines bite hardest                       | Deadline staleness is the ecosystem's worst failure                                         | L; RFC 197 template fits but ~50 hand-typed rows/yr             |
| 5    | C5 profile FAFSA status  | —                                                  | —                                                                                           | S-M mechanical; D12 requires value first                        |

## Gate 1 — decisions, APPROVED by Ian 2026-09-14 (D4 amended)

Six decisions stood at their defaults. **D4 was reversed by Ian**, verbatim:

> "`and link the official deadline page` Disagree. This is where Unicoach brings
> value -- we don't make ppl go read dry federal and state documents; Unicoach
> consolidates and shares data. In fact, the public html page should show a
> table of the dates for each state and region."

Committed decisions:

- **D1 — the beat is page plus coach.** This beat ships two things Ian named:
  the public FAFSA explainer page (C2) and coach answers to FAFSA process
  questions (C1), in that order. C4's already-shipped per-school forms half is
  surfaced harder as part of C1's prompt work, not a separate bet.
- **D2 — FAFSA page builds the citation layer.** The FAFSA page does not wait
  for `beyond/09` (who-pays): whichever explainer lands first builds the
  cited-claim rendering layer (claim + public URL + visible "as of"), and the
  other inherits it. Recorded as a CONFLICTS edge with `beyond/09`, not an
  order.
- **D3 — process facts: prompt and tool edge, no new table.** FAFSA process
  support extends the existing `federal_aid_policy` tool with cited tool-edge
  prose for the stable statutory rules (contributor+consent, divorced-parent
  > 50%-support rule, no-SSN accounts, dependency criteria) plus prompt routing;
  > `policy_parameters` keeps carrying the numbers. No new table for the PROSE.
  > (D4 as amended does add a dated table — see there.)
- **D4 — we consolidate the deadlines; we do not send families away.** AMENDED
  BY IAN, reversing the proposed link-out. Consolidating dry federal and state
  sources into one readable table IS unicoach's value. The public page carries
  **a table of FAFSA-relevant dates for every state** (and groups them by region
  for scanning); the same consolidated data is what the coach speaks from, so
  page and coach cannot disagree. This makes a seeded, cited, expiring date
  store part of THIS beat (the RFC 197 admission-test calendar is the template:
  hand-typed CSV + provenance + generator + expiry refusal + a dated build
  failure). States whose rule is not a date ("as soon as possible", "until funds
  run out") are carried as that WORDING, never flattened into a fake date.
  Per-school priority deadlines remain out of scope — the data is thin and
  unverified; the page says so.
- **D5 — no FAFSA status ask yet.** The money-profile `fafsa_filing` field (C5)
  is DEFERRED until C1 has landed and the coach has something the answer unlocks
  (brief 0001 D12, value before ask).
- **D6 — never touch the form; say it is free.** Standing rule: unicoach never
  fills in, submits, or handles credentials for the FAFSA, and every FAFSA
  surface (page and coach) states early that the FAFSA is free at
  studentaid.gov. This keeps unicoach outside the 20 U.S.C. §1090(d)(2) preparer
  role and matches the honesty brand.
- **D7 — dated copy is cycle-labeled and rots loudly.** Every dated FAFSA fact
  (open dates, deadlines, Pell amounts, statistics) carries its award-year label
  and an "as of" date, sourced to the primary document; the spec decides the
  concrete staleness mechanism (the RFC 197 lesson: prefer a dated build failure
  over a rot-silently page). Stable statutory facts are marked as such and cited
  once. D4's table binds this hardest: a deadline table that rots is worse than
  no table, because a family acts on it.

LEDGER (one line per landed slice, appended as each lands; `slice-board` reads
THIS file, so the line lives here and the spec points at it):

- `fafsa/06/fafsa-process-answers` LANDED as RFC 216 (main@206013f6 + 15d146c2,
  2026-09-15) — the `fafsa_process` coach tool: 30 cited FAFSA process rules
  over six topics (who files and the contributor/consent model, divorced or
  separated parents, a contributor with no Social Security number, dependency,
  what happens after submitting, the form is free), each carrying its publisher,
  its document, that publisher's own https page and 2026-09-14, the day we read
  it; `FafsaRule` does not construct without an https source. Coach prompt v38
  routes to it, states the more-than-half-support rule, names "the parent the
  student lived with" as the old rule that is wrong, and says the FAFSA is free
  and that we never fill it in, submit it or ask for a login (D6). Gate
  decisions taken as recommended: a sibling tool rather than more payload on
  `federal_aid_policy`, no tool argument, no coverage number, no dated 2024
  rollout copy. Suite green: 3,431 tests executed, 0 failures.

- `fafsa/01/the-deadline-table` LANDED as RFC 215 (main@a62a607e + 553c000f,
  2026-09-15) — `fafsa_filing_deadlines`: one row per (award year, jurisdiction,
  programme, event), federal rows as `state IS NULL` on a `NULLS NOT DISTINCT`
  natural key; three kinds with a value-iff-kind CHECK (a published day, the
  agency's own RULE in words, a verified absence WITH its citation);
  `deadline_as_published` on every row, hour and time zone included; citations
  as two foreign keys into `external_documents` (D16), archive never equal to
  its source; `event` four long including the corrections deadline, whose
  Federal Register / studentaid.gov one-day disagreement is stored with its
  sources rather than resolved; `fafsa_cycle_opens_on()` as the one home for the
  cycle bound, which refuses last cycle's date under this cycle's key; and
  `FafsaFilingCycle`, a filing clock separate from the 1 July aid-year rule and
  explicitly not a claim about the only open cycle. Plus `AidDeadlineEvent`,
  `AidDeadlineKind`, the sealed `PublishedDeadline` / `DeadlineJurisdiction`
  pair, `FafsaFilingDeadlineDao` and 17 tests. NO ROWS — `fafsa/02` types them —
  and no door; this is the one slice of the beat a family cannot reach. Gate
  decisions: table approved as presented PLUS a fourth event value (Ian),
  `publisher` kept on the row, and the separate filing clock. Review (three
  passes independently) caught the cycle window a year too wide and a state code
  that was checkable but unchecked; both fixed before the gate. Suite green:
  3,436 tests executed, 0 failures.
- `fafsa/02/fifty-one-deadlines-typed` LANDED as RFC 223 (main@e9eb1523,
  2026-09-16) — the store filled: **128 rows — 124 state across 47
  jurisdictions, 4 federal**; 107 rows the 2026-27 cycle, 21 the 2027-28 cycle
  across 11 jurisdictions; 74 a published day, 48 a rule in the publisher's own
  words, 6 a verified absence. The four jurisdictions no source could be read
  for (AR, DE, NC, NH) ship NOTHING and are named in PROVENANCE.json as needing
  human eyes. `bin/fafsa-deadline-seed` (python3) validates the corpus against
  every landed CHECK, refuses a duplicated natural key by line number, and emits
  one seed migration per reading day; its link-status sweep answered 78 of 84
  cited urls, found Wyoming's Hathaway page 404 (stored `not_found`, never shown
  as a link), and recorded 5 asked-and-unanswered. The build fails when the
  filing cycle has no rows and 90 days before any reading expires. Full
  pre-commit gate green on the landed tree (verified tree recorded).
- `fafsa/03/one-answer-for-both-doors` LANDED as RFC 230 (main@9f8719f1,
  2026-09-23) — one `FafsaDeadlineService` now answers FAFSA dates for BOTH
  future doors, so the page (`fafsa/04`) and the coach (`fafsa/05`) cannot tell
  one family two different stories about the same deadline. Three refusal arms
  stay apart and TYPED — the service returns a reason, the door writes the
  words: we hold NO dates at all; we hold healthy dates whose filing year has
  already PASSED, which is the named failure this slice exists to prevent,
  serving last year's deadlines as this year's; or we hold this year's dates but
  no longer BELIEVE them, because the day a human last verified them has run
  out. A refusal also writes ONE operator line naming the day, the filing year,
  the cycles held and the earliest expiry, because "seed the next cycle" and
  "re-verify the corpus" are different fixes. `missingJurisdictions` ships TYPED
  as `List<DeadlineJurisdiction.State>` — four lenses across two tiers asked for
  it, and the seeded `us_states` set was proven equal to `UsStateCodes.ALL` both
  ways before the conversion was made total, with RFC 229's keying making that
  agreement structural rather than true-of-today's-seed. Gate answers honoured:
  device-zone fallback America/Los_Angeles, jurisdiction on the container. NO
  DOOR YET — a family cannot reach anything new today, and both `fafsa/04` and
  `fafsa/05` BLOCKS-depend on this one and are now unblocked. No migration, no
  table, no prompt version, no flag: not wiring the door IS the rollback. Phase
  5 1,951 executed, 0 failures; the gate ran the full check, 0 failures.
- `fafsa/04/the-fafsa-page` LANDED as RFC 238 (main@b9b219e0, 2026-09-25) — the
  door is open: `GET /fafsa`, reachable from a **"FAFSA" link in the site
  header** with no account, no email wall and nothing to install. It says first
  that the form is free (20 U.S.C. § 1090(d)) and that unicoach never fills it
  in, submits it or asks for a login (D6); then what the form asks now — the
  contributor model, the consent rule whose failure ends federal aid including
  Pell, and the Student Aid Index; then the dates, federal rows first and one
  table per region, every row carrying its programme, the agency's own words or
  the published day, the filing year it belongs to, what kind of date it is, and
  the agency's page with the day we read it. The four jurisdictions no source
  could be read for — Arkansas, Delaware, North Carolina, New Hampshire — say we
  tried, name the agency and link the page we tried (D9). The edge cases are the
  thirty cited rules `fafsa/06` already serves the coach — divorced or separated
  parents and the more-than-half-support rule, a contributor with no Social
  Security number, dependency, what happens after you submit — so the page and
  the coach cannot disagree; scope is stated plainly as the fifty states and DC,
  with the territories named as not covered. Behind it: the deadline service now
  names a missing jurisdiction with its region and the agency we tried, a
  citation carries its link-check verdict so a dead address is printed as text
  and never as a link, and the explainer model gains a fourth block — a table
  whose every row cites itself. Gate answers from Ian: the extra-application
  fact is carried by the agency's own sentence and the programme name rather
  than by a new column; the four unreadable jurisdictions name their agency and
  link the page we tried; the door is a header link. Review caught two defects
  the suite could not see — **Tennessee was invisible**, because its four rows
  cite over `http://` while the page required `https://` and every fixture used
  https, so the state reached neither a table nor the gap list; and **a data fix
  would have been an outage**, because the guard this slice asked for turned
  "Arkansas gains a deadline" into a failed read for all fifty-one, and is an
  operator warning now while the rows serve. `fafsa/05` LANDED as RFC 237
  (main@a8c9b044) mid-review — the collision predicted at kickoff, because both
  slices read the one service and this run changed its shape. The first land
  attempt rebased onto it and the pre-commit gate refused the tree, so this run,
  which owns the change, adapted their tool: the archived address still reaches
  the coach exactly as RFC 237 decided, a recorded grave is withheld through the
  one predicate, `it.uspsCode` became `it.jurisdiction.uspsCode`, one clause of
  their archive legend and two KDoc claims this run falsified were corrected,
  and one Postgres-backed test covers both halves of the archive rule. Named for
  a later slice, not done here: the coach still serves a dead SOURCE url on
  every row, where `CdsCitation` withholds one. No migration, no table, no
  prompt version. Suite on the landed tree, forced and whole: **3,934 tests
  executed, 0 failures, 0 errors, 0 skipped**.
- `fafsa/05/the-coach-speaks-the-same-dates` LANDED as RFC 237 (main@a8c9b044,
  2026-09-25) — the coach's DOOR onto `FafsaDeadlineService`, which had no
  caller until now: a student in the first session, with no profile and no
  college list, asks "when is my FAFSA due?" and gets the dates we hold, never a
  date from the model's memory. `fafsa_deadlines` is registered in `rest-server`
  Application.kt and a test asserts the WIRED registry advertises it, so the
  door is the claim. The tool takes NO arguments: the family's state comes from
  `money_profiles` and from nowhere else — Ian's call at the approval gate,
  verbatim: "We either have it in the profile, or we can get and set it via chat
  or via a profile edit. When we don't have it, we cannot say anything about the
  state they live in. They must give a state to be able to enjoy the benefits of
  unicoach knowing the state." So when we hold no state the coach serves the
  FEDERAL dates FIRST, then asks which state they live in and RECORDS it with
  `update_money_profile`, saying what that unlocks — value before ask, guided
  not gated: a family that declines keeps the federal dates and a labelled gap
  and is not asked again. Every row carries the filing cycle it belongs to,
  whether that cycle is the one being filed (`award_year_status` plus a sentence
  when it is not), the publisher's own words (`as_published`), the publisher,
  that publisher's page, the archived address where the publisher blocks us, and
  the day a human checked it. Any FEDERAL event we hold no live row for is NAMED
  (`federal_events_absent`) and the coach says we do not hold that date and
  sends the family to studentaid.gov, instead of answering short. Five typed
  gaps — `not_asked`, `declined`, `unavailable` (the coach cannot SEE their
  state right now, never that we do not hold it), `not_verified` (we hold no
  deadline for that state that a human verified against its own agency's page,
  never "the state publishes none"), `not_served` (not one of the 50 states and
  DC, D13) — and `not_served` is decided POSITIVELY, so a codebook gap can never
  tell a Californian their state is not one of the fifty. RFC 230's three
  refusals get three distinct sentences (we hold none at all / every one we hold
  is an earlier filing cycle / the ones we hold are out of date), each with no
  date anywhere in the payload and each pointing at studentaid.gov and the
  family's own state agency. Coach prompt **v44**; no new table and no migration
  other than the generated prompt seed. ROLLBACK: remove the registration line
  and the prompt paragraph (`COACHING_SYSTEM_PROMPT_VERSION=v43` is the partial
  one — it unroutes the paragraph, the tool stays registered and goes uncalled).
  HONEST LIMIT: on the committed corpus the only live FEDERAL row is the 2027-28
  beta-testing sentence, because the live set is the newest VERIFIED cycle per
  jurisdiction (D23) — so today the coach truthfully says we do not hold the
  federal filing deadline or the corrections deadline and points at
  studentaid.gov; typing the federal rows for the cycle being filed is this
  brief's standing D24 gap. Also landed in this run: `Residency.forStudent` now
  names its CALLER in the two fault logs, and two shared test guards
  (`PayloadDateGuard`, `BareSourceCodeGuard.CDS_FIELD_ID`) hold patterns four
  suites had hand-typed. One commit — the RFC prose landed inside the code
  commit and passed the full gate, so this line carries ONE SHA. Final suite
  **2,678 executed, 0 failures**; the pre-commit gate passed on the landed tree.
  Review: 41 lenses over 4 tiers, 93 findings, 37 applied, 0 contradictions.
  Note for the reader: `fafsa/04` landed hours later as RFC 238 and adapted this
  tool where their change crossed it — see that line.

## LEARN — running notes (appended as slices land)

From `fafsa/01` (RFC 215), reported by the run:

- **A window constraint written to refuse "last cycle's value" must be probed
  with LAST cycle's value, not one two cycles back.** The cycle-containment
  CHECK shipped a year too wide and its test probed 2025 under a 2027 key, so
  the acceptance criterion was vacuous: the exact row the RFC named as "the one
  mistake that makes this table worse than no table" would have been ACCEPTED,
  by a table whose test suite was green. Three review passes found it
  independently. **Candidate for /principle.**
- **A design that states a sharing rule must apply it to its own code.** RFC 215
  wrote down RFC 213's rule — a vocabulary becomes shared the day a SECOND table
  asks for it — and then copied twelve lines of citation-resolution code,
  comment included, from the state-programme corpus. **Candidate for
  /principle.**
- **A test that restates a rule from another module keeps asserting a contrast
  that has stopped existing.** The proof that the FAFSA filing clock differs
  from the 1 July aid-year clock lived in `:db` against a hand copy of the July
  rule. The failure direction is false REASSURANCE, not a false alarm. It moved
  to `:service`, the only module that can see both.
- **A four-field refusal beats a constraint name.** The schema suite reduced
  every PostgreSQL error to its constraint name, which reports NULL for a NOT
  NULL violation — so the "a row that cites nothing" case could only assert
  null. Keeping SQLSTATE, DETAIL and message beside the name made that case real
  and made every mismatch readable.

From `fafsa/06` (RFC 216), reported by the run:

- **A generated artifact regenerated BEFORE a rebase is stale after it.** The
  v38 prompt seed was generated, then the run rebased onto a `main` carrying
  v37, and the stale seed surfaced as three unrelated-looking test failures. The
  lesson is "regenerate every generated file AFTER the final rebase, never
  before", and no review principle says it yet. **Candidate for /principle.**
- **A repo-wide literal ban can fire on prose outside its scope.** The
  money-attribution guard fired on the statutory free-form rule. The run took
  that guard's own documented per-line exemption with a written reason rather
  than reword a true sentence or widen the guard — the right call, but made on
  taste alone. **Candidate for /principle.**
- **The ledger belongs in `brief.md`, not `spec.md`.** `slice-board` reads only
  `brief.md`, so a ledger written into `spec.md` left a landed slice printing as
  READY. Fixed here; worth carrying into the next brief from the start.
- **Eight row models each carry their own "where did this come from" fields**
  (noted by RFC 215's review, which correctly declined to fix it as out of
  scope). A ninth copy is one slice away. **Candidate for its own brief.**
