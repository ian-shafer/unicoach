# Brief 0012 — FAFSA support: the coach answers, and a public page explains

Status:

- Phase: **SPEC & SLICE — gate 2 presented, amended once, awaiting Ian.**
  `spec.md` holds six slices `fafsa/01`-`fafsa/06` and decisions D8-D16;
  `slice-board` parses them clean and reports `fafsa/01` and `fafsa/06`
  startable. Gate 1 was **APPROVED with one amendment, 2026-09-14** (D4 reversed
  by Ian: consolidate the state deadlines ourselves). Research in `research/`,
  DISCOVER in `discover/` (including the 51-jurisdiction deadline corpus).
- Gate 1 (prioritisation): **APPROVED 2026-09-14, D4 amended** — seven decisions
  D1-D7 below.
- Gate 2 (spec): **presented 2026-09-14, amended 2026-09-15** — D9 (say we tried
  and could not) and D10 (`us_states` is not ours yet) amended by Ian, and D16
  added after he flagged the in-flight `external_documents` table.
- Handle: `fafsa` (slice IDs `fafsa/NN/name`).
- Ledger: at the end of this file (`fafsa/06` landed as RFC 216, `fafsa/01` as
  RFC 215). `slice-board` reads the ledger from THIS file, never from `spec.md`.

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
