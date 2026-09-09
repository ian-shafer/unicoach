# Product status

One page, five jobs: a glanceable TL;DR of the next steps, what the product does
today (a user manual, one entry per feature), what we are building next
(prioritised, with honest in-progress state), a backlog for unscheduled ideas,
and paste-ready prompts to kick off new sessions. **/chart reads this file first
and updates it after every landed slice** — if this file and a brief disagree,
the brief's ledger wins and this file gets fixed.

Updated: 2026-09-09 — **RFC 179, `soft/02/assurance-tiers`** (`main@922e33c6` +
`55b829f3`): a figure now says what KIND of number it is, beside the figure
itself. `AssuranceTier` — `ADMINISTRATIVE_RECORD` / `MANDATORY_SURVEY` /
`VOLUNTARY_SELF_REPORT` — is a pure function of
`(MoneySource,
source_variable)`, the pair both fact tables already store, read
as a PAIR with `FigureStatus` and never collapsed into it. `source_variable` now
travels to the copy seam as RFC 177 threaded `source`, a SHOWN figure gets a
note at last (so the tier can appear beside a dollar amount, not only on blank
cells), and the Common Data Set's own read path (`AidPolicyDao`) calls the SAME
resolver, so there is no CDS special case. An unmapped Scorecard variable is a
located, typed fault, and the closure test derives its key space from the
loader's own registry. **No migration, no column, no seeded table, no prompt
version** — this is a code-level correction with no rollback knob. **Next free
RFC 184** (`rfc/` tops out at 180; `pipeline/rfc-178`, `-181`, `-182` and `-183`
are live runs — recompute this, never copy it); **next free migration 0095**
(`db/schema` tops out at `0094`, landed with RFC 176). Brief 0008 has two slices
left, `soft/03/the-year-we-cite` and `soft/04/two-more-ingest-refusals`, both
READY. **Brief 0006 is closed**: `shape/08/drop-the-publisher-shape` landed as
RFC 176 (2026-09-09, migration 0094) and the publisher money columns are gone
from `colleges` and `colleges_versions`; only the deferred
`shape/09/exchange-participation` remains.

## TL;DR — next steps, most important first

1. **A FIGURE NOW SAYS WHAT KIND OF NUMBER IT IS (RFC 179,
   `soft/02/assurance-tiers`, 2026-09-09).** unicoach speaks four publishers
   with one voice: a Common Data Set cell a college typed about itself and a
   federal loan record carry the same `REPORTED` status, and until today a
   family had no way to tell them apart. Each money figure now resolves an
   **assurance tier** — `ADMINISTRATIVE_RECORD`, `MANDATORY_SURVEY`,
   `VOLUNTARY_SELF_REPORT` — as a pure function of the
   `(MoneySource,
   source_variable)` pair both fact tables already store, and
   one seam turns it into ONE sentence beside the figure: "This comes from
   federal loan and tax records, not from the college…", "The college had to
   report this to the federal government, and the form checks it against last
   year's answer. Nobody audits it.", "The college published this about itself.
   No one checks it." The tier is read as a PAIR with `FigureStatus` and never
   collapsed into it — an imputed IPEDS cell is soft in a different way from a
   self-reported one — and the two sentences are always composed status-first,
   tier-second, never concatenated. Two things had to be built for that to be
   more than decoration: `source_variable` now travels to the copy seam exactly
   as RFC 177 threaded `source`, and a **shown** figure gets a status note at
   last, since the seam previously spoke only for blank and imputed cells and a
   tier hung there would never appear next to a dollar amount. In chat,
   `figure_statuses[]` entries gain `assurance` and `assurance_statement`. On
   the report page the tier rides the shown debt paragraph, and otherwise only
   the blank arms the DOMAIN speaks for (imputed / suppressed / not-applicable /
   year-gap) — an ordinary "Not reported by this school" cell deliberately keeps
   its short form and carries no tier. The softest tier's one visible figure,
   **average percent of need met** (CDS `H.209`/`H.211`), is served through
   `AidPolicyDao`, which now calls the SAME resolver, so the Common Data Set has
   no special case. An unmapped Scorecard variable is a located, typed fault,
   never a guessed tier, and the closure test derives its key space from the
   loader's own registry rather than restating it. **No migration, no column, no
   seeded table and no prompt version**, so there is nothing to roll back — the
   rollback is a revert. The door is the existing one: any family asking the
   coach what a school costs, and any parent opening a shared Family Cost
   Report. This is one sentence beside a number, not a new screen.

2. **A FIGURE'S PUBLISHER IS NOW DERIVED, AND THE LIE IS GONE (RFC 177,
   `soft/01/one-hedge-seam`, 2026-09-08).** Until today unicoach told families
   that an IPEDS figure came from the U.S. Department of Education College
   Scorecard. One hand-typed constant said so in chat and on the Family Cost
   Report, and the coach prompt said it in prose, while
   `CanonicalMoneyLoader.ORDERED_SOURCES` — the code that actually decides which
   of two conflicting prices a family sees — puts **IPEDS first**. The cause was
   structural, not a typo: the winning `MoneySource` was read out of the store
   and **dropped two lines later**, so no file under `service/` or `public-web/`
   could know who published a number, and every surface that wanted to say it
   typed a name by hand. The publisher now travels to **one seam** and the
   sentence is derived there. `MoneySourceCopy` holds the only English name of a
   publisher; `FigureStatusCopy` names it exactly where the sentence is about
   the publisher's ACT — "the Scorecard withholds this figure to protect
   students' privacy", "IPEDS estimated this for the school" — and leaves the
   school's own silence and our own gap unattributed, because naming a publisher
   there would misattribute the gap. A publisher is named only when it published
   a figure a family can actually SEE: a suppressed or value-free row no longer
   puts a publisher's name in the page's sources sentence. Adding a fifth
   publisher now fails to compile at the seam rather than shipping a silently
   wrong sentence. Coach prompt **v23** stops naming the Scorecard by hand and
   says "attribute each figure to the source the tool names beside it"; rollback
   = **v22**. The door is the existing one — any family asking what a school
   costs, and any parent opening a shared Family Cost Report.

3. **SEARCH NOW RANKS ON THE PRICE YOU WOULD ACTUALLY PAY (RFC 169,
   `shape/05/search-on-your-price`, 2026-09-08).** Until now every family was
   ranked on ONE price — the Scorecard net price, which at a public school is
   the **in-state** figure after federal aid — so a family in Washington asking
   for cheap California publics was ordered on a price it would never be billed.
   That is not a rounding difference: over 2,971 operating four-year schools,
   that ladder and an out-of-state published-total ladder order about **22% of
   school pairs the opposite way** (Kendall tau 0.561, brief 0005's research).
   Now a query runs on exactly **one price ruler**. With the family's state on
   file, the whole query — ranking, filters, cheapest-first, "like Bowdoin but
   cheaper" — reads each school's **published on-campus total at that family's
   own tuition tier**, and the result says in one sentence that **aid is not in
   that ruler**: there is no out-of-state net price and there never can be,
   because subtracting an in-state average grant from an out-of-state total is
   the arithmetic RFC 149 forbids. With no state on file the ruler is the net
   price exactly as before, and the basis is in the **metric name on the wire**,
   not a footnote. The two rulers can never mix — the anchor carries the ruler
   it was read on, and a mixed pairing is refused. Search now also **asks for
   the state**, an offer that never gates a result. **D19 landed with it at zero
   DDL cost**: a school whose publisher does not separate an in-district price
   is ranked AND labelled with the sentence the cost tools already use (641 of
   3,264 schools in the default universe), never silently treated as in-state.
   Degrades two ways, both named: no residency → the net-price ruler with its
   basis said aloud; no published total → the school is dropped, counted and
   named under `excluded_unknown`, never substituted. Migration 0091 is four
   nullable index columns and **no new index**. Rollback = coach prompt v21.

4. **A FAMILY CAN NOW SEE WHAT GRADUATES HERE ACTUALLY BORROWED, INCLUDING
   PRIVATE LOANS (RFC 175, `shape/07b/borrowing`, 2026-09-08).** Until now the
   only debt sentence unicoach said was the Scorecard's, which counts **federal
   loans only** — the tidier half. Ask "do students at Amherst take out loans?"
   and the coach now answers from that school's own Common Data Set: the class
   that graduated in a named year, how many of them borrowed, and the average
   cumulative principal they owed — **federal and private said separately**, and
   never added together. The doors are the existing `college_cost_profile` tool
   and the **Family Cost Report page**, which carries the CDS half beside the
   Scorecard's federal figure, never summed with it and never compared. Every
   figure is spoken as the **school's own claim** ("Amherst College reports…"),
   because a CDS filing is an unaudited self-report, not an administrative
   record. Three honesty rules ride on it: a **zero average is refused** — the
   source writes `0` when nobody borrowed, and 91 of 138 state-loan averages
   were that placeholder, so the coach would have said "they owed $0 on average"
   — while the zero COUNT stands, since "no student here borrowed a state loan"
   is true and worth saying; a cell **we** could not read is said as OUR gap,
   never the school's silence; and a filing whose figures contradict its own
   graduating class is withheld and named as **our** inability to reconcile it.
   Coverage: about 220 of 417 seeded colleges have a borrowing block, and the
   ~90 with no filing at all hear "we don't hold a Common Data Set for this
   school", never "students here don't borrow". Rollback = coach prompt v20.

5. **A FAMILY CAN NOW FIX ITS OWN MONEY FACTS ON A SCREEN (RFC 171,
   `profile/02/your-details-screen`, 2026-09-05). BRIEF 0007 IS COMPLETE.** The
   door is a new slide-over menu row **"Your details"**, above "My colleges",
   which pushes a screen carrying the household income band and the state or
   territory of residence. No conversation is needed to correct either one.
   Every field reads in one of three states — not answered, the value, or "you
   chose not to say" — and offers **"Prefer not to say"**, which names its cost
   (the coach stops asking), and **"Remove my answer"**, which clears the field
   and re-arms the coach's invitation. A declined field keeps a live picker, so
   **no choice here is a one-way door**. Each field saves on its own, at once,
   with a terse per-field "Saved" receipt announced to VoiceOver, and the coach
   **never mentions the edit in chat**. The pickers are RFC 165's
   `GET /api/v1/vocabularies` rendered as-is — server labels, server order, all
   **59 jurisdictions the server accepts**, which is why the field says "state
   or territory": a menu listing Guam must not call Guam a state. RFC 163's
   Swift lists survive **only as the offline fallback**, pinned to the server by
   tests, and a vocabulary that is absent, empty or holds one unrenderable entry
   falls back **whole, per vocabulary**, with the screen saying the list may be
   incomplete. Client-only: **no server change, no migration, no new table** (G4
   held), and therefore **no rollback knob at all** — no flag, no prompt
   version. Two open items go on the record: the app now has **two residency
   menus** (this screen's 59 served jurisdictions against onboarding's
   deliberate 51-item RFC 163 menu — changing onboarding is a /chart decision),
   and **`dependency` is still chat-only**, on neither REST verb, so no screen
   can reach the money profile's fourth tri-state.

6. **THE COACH CAN NOW ANSWER "DO WE HAVE TO DO THE CSS PROFILE?" (RFC 170,
   `shape/07a/need-and-forms`, 2026-09-05).** Ask what forms a school requires,
   or whether it meets full financial need, and the answer comes from that
   school's own Common Data Set with the year and a citation. Two honesty rules
   ride on it: a form is **required or unknown, never "not required"** (the
   corpus carries no false checkbox), and where WE failed to read a cell we say
   that, rather than reporting it as the school's silence. Coverage is the real
   CDS universe — 338 of 417 seeded colleges, and the CSS Profile answer exists
   for only 73 — so "not reported in this school's CDS" is the common answer and
   is said plainly. It also left the money layer cleaner than it found it: a
   filing now has ONE row (`source_documents`) instead of a copy per table, and
   the academic year is a typed start year instead of a `'YYYY-YY'` string with
   an `'undated'` sentinel.

7. **A FAMILY CAN NOW SEE THE CANONICAL MONEY STORE (RFC 166,
   `shape/04/cost-answers-from-canonical`, 2026-09-05).** This is the first
   slice of brief 0006 a person can SEE. Ask the coach — or read a shared Family
   Cost Report — what a school costs, and the answer is built from
   `price_figures` and `cohort_money_stats`, not from the Scorecard-shaped
   columns on `colleges`. Four things can be said that could not be said before:
   the **in-district** price is named as its own tier rather than collapsed into
   "in-state" (~269 colleges); **fees are split from tuition** where the school
   reports them split; **living at home now has a complete total**, with its
   food-and-housing line printed as a labelled `$0` and the assumption stated as
   **ours**, never as the school's silence (D17); and each figure's **status is
   spoken** — the publisher withheld it for privacy, the publisher estimated it,
   the school did not report it, or unicoach does not hold it. The RFC
   149/151/152/157 honesty rules did not change; they now read stored rows
   instead of repairing publisher shape at read time. Coach prompt **v19**,
   rollback `COACHING_SYSTEM_PROMPT_VERSION=v18`; migration **0086**. `colleges`
   kept its columns until `shape/08`, which dropped all eighteen of them (RFC
   176, 2026-09-09). Review found ten blockers, all of them wrong money
   statements: the sharpest is that RFC 162 landed mid-run with a second
   `avg_net_price` series, and a measure-only key would have served that
   grant-aided figure to every family as "the overall average net price" — no
   test caught it, because the fixtures wrote at the reader's own addresses. A
   cross-module address-contract test now closes that hole.

8. **ONE SOURCE FOR EVERY MONEY NUMBER (RFC 176,
   `shape/08/drop-the-publisher-shape`, 2026-09-09).** No door, by design — this
   is the slice that is invisible when it is right. `colleges` and
   `colleges_versions` no longer carry money at all: all eighteen
   publisher-shaped columns are dropped (migration 0094), and every money figure
   the product speaks comes from `price_figures` / `cohort_money_stats`. **The
   one thing a family can notice**: a search result's per-band net prices and
   Pell share now agree with the same school's cost answer. They could differ
   before, because the search payload read the Scorecard's copy of a figure
   while the cost answer read the publisher's own newer one (IPEDS SFA writes
   both at the same address). There is no rollback knob and no prompt version —
   the removal is a migration, and the pre-drop database dump kept outside the
   repo is the only route back to the dropped values.

9. **UNICOACH NOW KNOWS WHO AN AID NUMBER IS ABOUT (RFC 162,
   `shape/03/ipeds-sfa`, 2026-09-05).** IPEDS SFA fills the canonical store with
   aid: net price by income band, Pell share and **average award** (a figure the
   Scorecard does not publish), grant mix by source, and loan share and average.
   Each row carries its population as structure, and the scope follows the
   denominator — a share is over the cohort, an average is over the students who
   actually got that aid — so "average grant" can no longer quietly mean two
   different things. A new `cohort_population_counts` table stores the residency
   and living-arrangement headcounts behind those bases: at College of DuPage,
   1,488 of 2,099 students pay the in-district rate. **These rows reached
   families on 2026-09-05**, when `shape/04` (RFC 166) made every cost answer
   read the canonical store. Three spec facts were corrected against the real
   files and are defects for /chart to carry: SFA names the same concept
   differently for publics and privates (the spec's variable list would have
   silently dropped **~65% of colleges**), `Z` is an implied **zero** rather
   than an imputation (**this corrects landed RFC 161** — one flag vocabulary
   now serves both IPEDS surveys, and 17,357 honest zeros in one file were at
   stake), and `not_reported_by_institution` cannot be filled from SFA at all
   because NCES imputes instead of blanking.

10. **THE iOS COLLEGE LIST NOW SAYS WHAT IT MEANS ON THE WIRE (RFC 168,
    `profile/03/list-screen-parity`, 2026-09-05).** The client's college-list
    PATCH carries a three-state `LivingPlanUpdate` — keep / set / clear — with a
    hand-written encoder: keep sends neither wire key, so a Save from the list
    screen cannot destroy a per-college living plan the coach set in chat, and
    clear sends `livingPlanClear: true`, which the app previously could not
    express at all. Sending both keys is unrepresentable in the type, so the
    server's 400 cannot be reached. `reasons` keeps its opposite, deliberate
    rule — omission clears it, which is the shipped Clear button (RFC 164 D4) —
    and that is asserted in all three living-plan states. **Nothing user-visible
    on its own**: brief 0007 D3 defers the per-college living-plan picker, so
    there is no new control and no new screen; RFC 164 already stopped the data
    loss server-side on deploy. What this adds is that the contract is now
    written down in Swift and pinned by a server test that sends the Swift
    encoder's exact bytes. The slice's other deliverable was an audit of the iOS
    list screen against everything the chat tool can do: nine gaps, **reported
    not fixed**, now backlog lines B1-B9 in brief 0007's ledger — including that
    an unknown `status` would black out the whole list for every shipped build,
    and that **reorder exists on no surface** (no column, no field, no route).

11. **FORMS CAN STOP GUESSING: ONE ENDPOINT SERVES THE VOCABULARY (RFC 165,
    `profile/01/served-vocabulary`, 2026-09-05).** `GET /api/v1/vocabularies` is
    a registry, not a money-profile route: one map of vocabulary name to
    entries, every entry `value` + `label`, extras allowed and nothing fewer.
    That shape is the point — Ian widened the slice at the /ship gate because
    the same need returns at every form and picker, and a per-feature route
    would have grown one client adapter per shape. Registered today are the two
    `profile/02` needs: the five income bands with their spoken dollar ranges,
    and the 59 residency codes with names and jurisdiction kind. The residency
    set is literally the set the server validates against, so **a code a picker
    can offer can never be a code the write path rejects**. Adding a vocabulary
    later is one registration line — no new route, no client change. **It has a
    user now**: `profile/02/your-details-screen` (RFC 171, 2026-09-05) renders
    both pickers from this endpoint, and RFC 163's Swift copies of the two lists
    were demoted to an offline fallback pinned to the server by tests, which is
    what brief 0007 D6 asked for.

12. **THE IN-DISTRICT PRICE IS REAL NOW (RFC 161, `shape/02/ipeds-ic-ay`,
    2026-09-05).** IPEDS's published-charges file fills the canonical store
    ahead of the College Scorecard, and it fixes a wrong number we have been
    serving: the Scorecard collapses in-district into "in", so a community
    college's in-state price was its in-district price. Measured over the whole
    file the two sources agree on five of six shared figures at 100.0%, disagree
    on in-state tuition-and-fees for **269 institutions**, and **every single
    disagreement is exactly the in-district figure**. Fees also split from
    tuition, and four academic years land per file. **These rows became visible
    to families at `shape/04` (RFC 166, 2026-09-05)**, which serves the
    in-district tier as its own tier. **Two spec facts were corrected against
    the pinned codebook, and both are now resolved**: `CHG7/8AY` is off campus
    **NOT** with family, so **no source anywhere publishes with-family food and
    housing** — **Ian decided (D17, 2026-09-05) to treat living at home as $0
    food-and-housing and say so in words**, on the grounds that eating at home
    is negligible and is not a new expense caused by enrolling, which is what a
    cost-of-attendance figure measures. `shape/04` (RFC 166) now shows that
    complete with-family total with the assumption stated as ours, never a
    silent zero and never a blank blamed on the school. IC_AY also covers 3,825
    institutions, not ~6,100, because program-year reporters live in a different
    file, so the Scorecard stays the only source for the rest.

13. **THE MONEY STORE IS SHAPED LIKE MONEY (RFC 158, `shape/01/canonical-store`,
    2026-09-04).** Brief 0006's substrate: a price carries its residency, its
    living arrangement and its academic year; a statistic carries the population
    it describes; and an absence carries a reason instead of being an
    indistinguishable NULL. The four read-time repair RFCs (149/151/152/157) can
    become projections of stored rows rather than a growing compensation stack.
    The payoff **arrived** at `shape/04/cost-answers-from-canonical` (RFC 166,
    2026-09-05): those four RFCs are now projections of these rows, and every
    cost answer a family reads comes from them. The cutover is **complete**:
    `shape/05/search-on-your-price` (RFC 169) moved the other reader, and
    `shape/08/drop-the-publisher-shape` (RFC 176, migration 0094) dropped the
    publisher-shaped columns from `colleges` and `colleges_versions`. There is
    no second place a money figure can come from any more.

14. **BEAT 1 IS COMPLETE: the coach now asks to share the Family Cost Report, at
    a moment it chooses (RFC 160, `first-value/06/invite-your-parent`,
    2026-09-03).** Brief 0001's wedge is closed end to end. Until now the report
    existed but the coach could only produce a link when the student thought to
    ask. The synthesis pass now writes a **share-nudge reflection** for a
    student whose list is worth showing a parent (**>= 2 schools**, no live
    link, never opted out), and the next conversation's opener raises it once,
    naturally — the one sanctioned exception to the standing never-offer-unasked
    rule. **Ian amended the design at the gate: nudging more than once is fine
    for an active student** (a repeat waits **14 days** AND needs the list to
    have actually changed, so it always has something new to point at), **and a
    student can say "never ask me again"** — `stop_cost_report_offers` records
    that permanently, drops any nudge already written, and still leaves sharing
    available on request. `share_events` now records what the share surface does
    (minted, repeat, reissued, revoked, opted out), which is both the first read
    on share-rate and the substrate Beat 2's parent-account claim path needs.

15. **FIRST BRIEF 0006 SLICE LANDED: the coach now answers Pell and loan
    questions with cited federal facts (RFC 159, `shape/06/pell-and-loans`,
    2026-09-03).** A family can ask "can we get a Pell grant?" in session one —
    no college list, no profile — and get an honest answer naming the
    **2026-27** maximum (**$7,395**) and minimum (**$740**), the income tests
    that decide maximum eligibility, and the source it came from. Loan questions
    get the Direct Loan limits, stated as **caps that are never subtracted from
    a price**. Because Federal Student Aid has not yet published the 2026-27
    loan volume, the loan answer **says out loud that it is the prior award
    year's** — the stale-year rule proved by real data, not a test fixture. Pell
    is always a **range and an eligibility framing, never a promised award**.
    The dependency question is invited in flow and is fully declinable; both
    loan tables are served either way.

16. **BRIEF 0006 — MONEY IN UNICOACH SHAPE — GATES 1+2 APPROVED (Ian,
    2026-09-02, defaults, no amendments); WAVE 1 NOW HALF DONE.** The standing
    mistake is named: every money figure is stored in its publisher's shape, and
    RFCs 149/151/152/157 are a growing read-time compensation stack. Approved
    bet: a canonical money layer — **PriceFigure** (college × concept ×
    residency × arrangement × year) split from **CohortMoneyStat** (measure ×
    population basis × vintage), a six-value stored missingness status,
    unicoach-authored vocabularies external codes map into, IPEDS **IC_AY +
    SFA** un-deferred as keystone sources (resolves brief 0003 D7), a federal
    policy-parameter store, and a beside/backfill/cut-over/drop-last migration.
    Nine slices specced. `shape/06/pell-and-loans` is **LANDED (RFC 159)**; wave
    1's other slice, **`shape/01/canonical-store`, is IN FLIGHT** as
    `pipeline/rfc-158` — it is the substrate `shape/02`, `shape/03`, `shape/04`,
    `shape/05` and `shape/07` all wait on, so wave 2 unblocks when it lands. Ask
    `slice-board`, never this line, for what is startable now. **Brief 0005 is
    PAUSED by 0006 D11** — its question IS `shape/05/search-on-your-price`,
    decided at D14. Spec: `product/0006-money-in-unicoach-shape/spec.md`.

17. **A NUMBER IN THE PARENT'S REPORT WAS NOT THE FAMILY'S NUMBER, AND IAN FOUND
    IT BY USING THE PRODUCT. Fixed by RFC 157** (`main@29242880` + `7c7c56af`,
    2026-09-02). The Scorecard's published cost of attendance (`COSTT4_A`) and
    its net price (`NPT4` family) are figures for students paying the
    **in-state** rate. We printed them beside arrangement totals built from
    **out-of-state** tuition, with no basis stated — so a Washington family
    reading a shared Family Cost Report for UC San Diego saw a
    **$38,701 published price** and **$28,785 after aid**, when their real
    out-of-state published price is near **$77,102**. Now, at a public school
    whose state does not match a **KNOWN** residency, both figures are **removed
    from the answer at the source** — no renderer can print them — and the blank
    names the reason and points at the totals that ARE this family's. A school
    that publishes neither figure still reads as "not reported by this school".
    An **unanswered residency withholds nothing** and states the basis instead.
    A cost answer covering two or more schools carries the basis as a **sixth**
    assumption fact. The fit-lens prompt and both search tool descriptions now
    name the in-state basis at the model boundary, and coach prompt **v16** says
    it too. Migrations **0075** + **0076**, rollback
    `COACHING_SYSTEM_PROMPT_VERSION=v15`. Gate: **2441 tests, 0 failures**,
    shell harnesses green. **It left two open follow-ups — see the Backlog: the
    search index still ranks every family on the in-state net price (a /chart
    slice, not a fix to fold into the next run), and `first-value/06`'s spec
    drift.**

18. **THE FAMILY COST REPORT IS LIVE. `first-value/05/family-cost-report` (S5)
    LANDED as RFC 155 (`main@47cf9d62` + `6777c7c7`, 2026-09-01), so brief
    0001's Beat 1 is ONE SLICE from complete.** A parent no longer needs an
    account, a login, or the app. The student asks the coach to share,
    `share_cost_report` returns a link, and the parent opens
    `https://app.uni.coach/report?token=...` on a phone and reads the student's
    college list as a cost table — the six comparison assumption sentences (RFC
    157 added the blended-figure basis), a cross-school summary table (rows are
    schools, one held-constant way of living), per-school living-cost detail,
    cited merit practice, debt context, and a sources block. The page is **live,
    not a snapshot**: it recomputes on every view and says so, so a parent who
    opens it again in March sees March's answer. `revoke_cost_report_share`
    kills it, and **revoke means every link ever sent is dead**. The token is
    **derived, never stored** — `HMAC-SHA256(shareTokenSecret, row id)`, with
    only the SHA-256 hash in the row — so re-sharing reproduces the same link
    while a database leak yields none, and rotating the secret is a global
    revoke. One live share per student. Coach prompt **v15** (migrations 0073 +
    0074, rollback `COACHING_SYSTEM_PROMPT_VERSION=v14`). Gate: 2405 JVM tests
    and 431 shell assertions, 0 failures. **Operational precondition:**
    `COST_REPORT_SHARE_TOKEN_SECRET` must be set in SSM before this works in
    production; unset, the feature stays dark, declines honestly, and warns once
    at boot.

19. **Brief 0003 — clear money language — COMPLETE. `money/04/where-youll-live`
    LANDED as RFC 152 (`main@f7fcc99c` + `5d067bf0`, 2026-09-01), and with it
    every slice in the brief.** The coach now leads with the one way of living
    the family said they plan, instead of offering three and letting them pick —
    a $7,368/yr swing at the worked example, larger than that school's in-state
    tuition. **The slice spec was wrong and Ian caught it at the gate:** it
    modelled the plan as ONE global field, but living at home is possible at the
    in-state school and impossible at the far one. Preference is global;
    feasibility is a fact about the student–college pair, and our data can never
    decide it, because the Scorecard prices a commuter category at essentially
    every school. So there are two places (brief 0003 **D20**):
    `money_profiles.living_plan` is the default,
    `college_list_entries.living_plan` is a nullable per-college override, and
    resolution has exactly one home — **override → default → show all three**.
    **When it cannot show a total it now says which kind of silence it is** —
    our unanswered residency, a published price we cannot select, or a part the
    school does not publish. Coach prompt **v14** (migration 0072, rollback
    `COACHING_SYSTEM_PROMPT_VERSION=v13`). **Nothing in brief 0003 is startable
    — the brief is done.**

20. **Brief 0004 — college search index — CORE COMPLETE. Every slice has landed
    (RFCs 139, 144, 147, 150, 154 and 153, `search/04/similar-colleges`,
    2026-09-01); only `search/06/unattended-refresh` is left, and it is DEFERRED
    by intent.** A **`similar_colleges`** chat tool decides "similar" per call
    and runs one query over `college_search_index`: the default universe, the
    caller's hard constraints, a weighted-distance `ORDER BY` and `LIMIT <= 10`.
    Five axes — size, selectivity and price over RFC 150's percentile columns,
    setting by locale, subject mix by Jaccard over `subject_slugs`. **Price is
    deliberately not a default**, or a question about character would silently
    become a question about budget. **Unknown data is dropped, counted and
    named, never substituted**, and outcome percentiles are never an axis
    (gate-1 ruling). Coach prompt **v13** sends a school named in words through
    RFC 154's `find_college` first. **Nothing in 0004 is startable now.** The
    debt it leaves is in the Backlog: the `NewCollege` test fixture is a 5th
    copy and the shared helper is in the wrong source set, parked twice over.

21. **Brief 0001 S4 COMPLETE — S4a (RFC 140) and S4b (RFC 148, 2026-08-30).**
    The admissions layer is user-visible: the coach can answer, with citations,
    what a school weighs in admissions, when its rounds close, and how it
    actually behaves on merit aid — and merit rides along inside cost answers.
    Every fact names the school's own Common Data Set, its cycle, and an archive
    link. **THE HONEST DENOMINATOR IS LOAD-BEARING:** the CDS publishes no count
    of no-need freshmen, so the only computable share is "X% of ALL full-time
    freshmen received non-need (merit) aid, average $Y". The wire key is
    `share_of_all_full_time_freshmen_pct` and tests assert the payload never
    contains "without need". A school that reports only a freshman total (28 of

368) is a silence, not a zero.

21. **Before any App Store submission: brief 0002, account deletion** — parked
    in the Backlog (Ian, 2026-08-27), but 5.1.1(v) still blocks review and GDPR
    Art. 17 / CCPA still apply. Nothing in Beat 1 is affected; launch is.

_Rule: this list is rewritten every time the file is updated; it never says "see
below"._

## The product today

What a real user can do, and through which door. An entry is added or amended
when its slice lands — reachability is part of the entry, per the chart skill's
standing rule.

### Chat coaching (the core)

The product is a chat-first AI college coach (iOS app, RFC 117 navigation). The
coach runs on `COACHING_SYSTEM_PROMPT_VERSION=v7` (RFC 145; rollback knob:
`v6`), builds durable memory from conversation (claims/observations, RFC 93
commitments), and calls tools mid-conversation. Door: the iOS chat screen; new
users can chat before subscribing (chat-before-subscription is the house
value-before-ask pattern).

### Know your real price (briefs 0001 S1–S3, RFCs 133–135)

The v1 differentiator: per-school cost truth in session one.

- **How a user reaches it:** ask the coach about cost — or the coach raises it
  unprompted (prompt v3 makes it a first-session moment). Needs schools on the
  list (see college list, below) for the per-school table.
- **What it does:** for each listed school, sticker vs. likely net price for the
  family's income band, plus debt/earnings context, every number cited to its
  source-year. Sources: Scorecard income-band net price (NPT41–45) and median
  debt on `colleges` (RFC 133); `college_cost_profile` tool (RFC 135).
- **Degrades gracefully:** no money profile → overall net price, labeled as
  such; partial profile → best answer from what exists; unreported data says
  "not reported", never evades. No cost feature is gated on profile completion
  (0001 D11/D12).
- **Live in prod** (2026-08-27): end-to-end phone test passed — the coaching
  holds — and S1–S3.5 (RFCs 133–136) is deployed.

### Money language (brief 0003 M1, RFC 141)

One vocabulary for money, spoken everywhere the coach talks about cost.

- **How a user reaches it:** every cost answer in the chat coach — no user
  action, live on the next `service` deploy.
- **What it does:** the coach says _tuition and fees_ (the price the school
  sets), _housing and food_ (never "room and board"), _the published price_
  (never "sticker price"), _a financial aid offer_ (never an "award"), and
  always uses the word _loan_ for a loan. It never subtracts loans or work-study
  from a price. Parents and students get the same words; only the pronoun and
  time horizon change.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v4`; the v4 row is immutable and
  stays in the catalog.
- Honest limit: no test can assert what the coach actually says — the tests pin
  the seed's structure and content only.
- **RFC 142** closed the first live defect: the coach said "Q5 net price"
  because `college_search` sent it `net_price_q1..q5` and told it to cite the
  matching band. Every band now travels with its dollar range ("$110,000 or
  more") from one emitter, and prompt v6 bans source jargon generally.

### What a school costs, split into parts you can act on (brief 0003 M2, RFC 149)

One blended number became three priced living arrangements.

- **How a user reaches it:** ask the coach what a school costs — any cost answer
  in the chat coach about a school on the college list. No user action, live on
  the next `service` deploy after the migrations run.
- **What it does:** for each listed school the `college_cost_profile` tool
  returns `cost_by_living_arrangement` — `on_campus`, `off_campus`,
  `with_family` — each with tuition and fees, housing and food, books and
  supplies, other expenses, and that arrangement's total. The coach leads with
  the split, names which arrangement it is quoting, and marks the estimated
  lines as estimates. Living at home can now be priced against living on campus.
- **Where the numbers come from:** the same pinned Scorecard snapshot we already
  ingest — `ROOMBOARD_ON/OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM` — landed
  as six nullable columns on `colleges` (migration 0062), each with a nonneg
  CHECK.
- **How it degrades:** an arrangement missing a part shows the parts it has and
  **no total** — a partial sum is never presented as a total. `with_family`
  shows no housing and food line at all, because the source publishes no such
  figure; that absence is explicit, never a `$0`. A school silent on a component
  says so in `data_availability`. If we do not know the family's state, the
  tuition line and every total are withheld rather than guessing a residency,
  and the coach asks the residency question instead. **At a public school whose
  state does not match a KNOWN residency, the blended published price and the
  net price are withheld too** (RFC 157) — both are in-state figures and this
  family is not in-state — and the blank says so and points at the arrangement
  totals, which ARE built from their tuition. A school that publishes neither
  figure still reads as "not reported by this school", never as withheld.
- **"No residence halls" is an answer, not a gap:** read from IPEDS
  `offers_housing`, and reported whenever known. If a school flagged as having
  no housing nonetheless publishes on-campus figures, the published figures win,
  the flag is shown beside them, and the coach is told to say both — we never
  hide a number the school published.
- **Years are stated, not implied:** each academic year is emitted with the list
  of figures it covers (components and tuition are AY2022-23; the blended
  published price and net price are AY2021-22). Median debt and median earnings
  are dated by no source we hold, so they carry no year rather than borrowing
  one. The old "data ingested YYYY" phrasing — which was when we loaded the
  file, never the year of the figures — is gone.
- **Never mixed:** nothing computes `net price − tuition` (aid applies to the
  blend, not to a part), the blended cost of attendance is never presented as an
  arrangement total, and only same-vintage figures enter a sum. All three are
  enforced by tests, one of which scans the cost package's source for the
  arithmetic itself.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v8`; the v8 row is immutable and
  stays in the catalog.

### Comparing schools without hiding the assumptions (brief 0003 M3, RFC 151)

**The door:** the chat coach. Ask about more than one school on your college
list — "compare these three" — and the answer arrives as a short table with six
plain sentences above it.

A dollar figure is a statistic about a population, a year, a residency and a way
of living. A bare column label hides all four, which is how a side-by-side
quietly lies. So whenever a cost answer covers two or more schools, the tool now
returns one `comparison_basis` object and the coach states it **before** the
numbers, as ordinary copy rather than a disclaimer at the bottom:

- **whose price** — averages for first-year, full-time students who received
  federal aid, not a quote for your family;
- **the residency held constant**, said per school. An all-private table is
  never given a caveat about public tuition, and a mixed table names which
  schools the caveat is about;
- **the way of living held constant** — and it names only the arrangements every
  school in the table is actually priced for. The moment one column has no
  figure, "held constant" is false and the coach says so;
- **the academic year** each figure comes from;
- **the aid basis** — a net price is the published price minus grants and
  scholarships. Loans and work-study are never subtracted. This one had never
  been on the wire before;
- **the basis of the blended figures** (the sixth fact, RFC 157) — the published
  price and the net price are figures for students paying the **in-state** rate.
  Where that is not this family's rate the two figures are not shown at all, and
  the sentence says which schools that applies to.

Tuition and fees — the price the school sets and publishes — renders above the
estimated living costs, and the coach says which block is which. Rows are
schools, inside RFC 124's three-column phone cap, or it says it as a list.

**How it degrades.** A school that does not report a part gets a labelled blank,
never a zero, never a neighbour's number, and never a total summed from the
parts that happen to be there. A public school outside a KNOWN residency shows
**no** published price and **no** price after aid — the blank names the reason
and points at that school's arrangement totals — while an **unanswered**
residency withholds nothing and states the in-state basis instead. A school with
no residence halls is said to have none, not "unreported". A school whose type
we cannot recognise says plainly that no published price can be selected for it,
rather than being dropped or guessed at. One school only? No comparison object
rides at all — a one-school answer is already fully labelled and must not be
narrated as a comparison.

**Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v10`. The v10 row is immutable and
stays in the catalog, so this is one environment variable, no migration.

### Where you'll live, and what that costs (brief 0003 M4, RFC 152)

**Door:** the chat coach. When a cost answer would change materially depending
on where the student lives, the coach invites the question in the flow of the
answer — never as a form. Saying "I'd live at home" changes the totals in the
same turn.

Two places hold the answer, because it is two facts. **Your usual plan** lives
on the money profile (`on_campus` / `off_campus` / `with_family`) and means
"where I'd live when I have the choice". **A per-school override** lives on the
college-list entry and means "not at this one". A Seattle family can live at
home for the in-state school and cannot for the far one, and no data we hold can
decide that — the Scorecard prices a commuter category at essentially every
school. So the family decides, and the coach never infers living at home.
Resolution is **override → default → show all three arrangements**.

What the coach does with it: it **leads** with the resolved plan and names it in
the student's words ("living at home"), and says whether that came from what
they said about this school or from their usual plan. The other two arrangements
stay in the answer, so "what if he lived on campus there instead?" is still
answerable in the same turn.

**When there is no total, it says which kind of silence it is.** Three cases,
each labelled: _we have not been told which state the student is a resident of_
— our gap, and one question closes it; _we cannot tell which of this school's
published prices applies_ — also our gap, but no question the family can answer
closes it; and _the school does not publish every part of that way of living_ —
the school's gap, said plainly. The first two are never reported as the school
publishing no price. In all three the coach quotes the parts that are there,
names what is missing, and never adds up what is there and calls it a total.

**Degrades (guided, not gated):** with no answer, every cost surface works
exactly as it did before — all three arrangements, each labelled. A decline is
permanent and never re-raised. The invitation only appears where the school has
at least two _priced_ arrangements, so it is never asked where the answer would
change nothing — which means at a public school it follows the residency
question rather than competing with it.

**Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v13`. The v13 prompt row is
immutable and stays in the catalog; the columns are additive and unread by the
older prompt.

### Asking where you live before asking what you earn (brief 0003 M1.2, RFC 145)

The cheap question that moves the bigger number now gets asked, and gets asked
first.

- **How a user reaches it:** any cost answer in the chat coach about a
  **public** school on their list, while we do not know their state — no user
  action, live on the next `service` deploy.
- **What it does:** the coach offers to record the state the family lives in,
  naming what it unlocks (whether they would pay the in-state or the
  out-of-state published price), **before** it raises household income —
  residency corrects a public school's tuition by a median $6,300/yr against
  ~$1,376 for a middle-band income correction. The cue rides the tool result
  (`precision_offer` is now an ordered list of invitations), not the model's
  memory.
- **Where it stays quiet:** a list of only private schools gets no residency
  offer — there is one price and the question would buy nothing.
- **Never forced (0001 D11/D12):** a declined state is never raised again, and
  every cost answer still works without it, naming the basis it used.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v6`; the v6 row is immutable and
  stays in the catalog.

### Pell and loans, answered from federal facts (brief 0006 `shape/06`, RFC 159)

**The door:** the chat coach, in the flow of a money conversation. No college
list, no money profile, no set-up — session one works. Asking "can we get a Pell
grant?" or "what would loans look like?" is enough; the coach calls
`federal_aid_policy`, which takes no arguments because federal policy is the
same at every college.

**What it does.** It states the **award year** it is talking about and then the
facts for it. For Pell: the maximum award (**$7,395** for 2026-27), the minimum
(**$740**), the Student Aid Index floor, and the income tests that decide
**maximum** eligibility — a dependent student's parents at or below **225%**
(single parent) or **175%** (other parents) of the federal poverty guideline for
their family size and state. For loans: the Direct Loan grid — annual limits by
year of study with the subsidized cap inside each, and the aggregate ceilings —
for dependent and independent undergraduates and for graduate students. Every
group carries its citation: the spoken document name and the Federal Student Aid
URL it was verified against.

**How it stays honest.** Pell is framed as **eligibility and a range, never a
promised award** — the coach cannot tell a family what they will get, because
that depends on a FAFSA they have not filed. Loan limits are **caps on
borrowing**, and the prompt forbids subtracting them from any price (brief
0003). No arithmetic happens in the code at all: the tool serves facts and the
coach narrates them, so no estimate can be quietly stored as if it were a fact.

**When the figures are a year behind, it says so.** Each topic serves the latest
award year that is **complete** and not in the future. Right now that means Pell
is 2026-27 but the loan grid is 2025-26, because Federal Student Aid has not
published Volume 8 of the 2026-27 handbook and 2025 legislation changes graduate
and parent lending from July 2026. The loan answer carries a sentence saying
exactly that. A partly-published newer year never displaces a complete older
one, and a topic that cannot be served at all is logged for us rather than
silently dropped for the family.

**Degrading.** The coach may ask whether the student is a **dependent** or
**independent** student for federal aid, naming why it helps (it picks the right
loan table). The question is skippable and resumable, and the answer is served
in full either way — when it is unknown, both tables are shown rather than a
guess. The profile field follows the same three-state shape as income band,
residency and living plan: unanswered, answered, or declined, never inferred.

**Rollback.** The coach's wording is prompt **v17**; pinning
`COACHING_SYSTEM_PROMPT_VERSION=v16` restores the previous behaviour without a
deploy. The parameters themselves are rows: a corrected federal figure is a new
migration, never an edit to a landed one.

### The canonical money store (brief 0006 `shape/01`, RFC 158)

**Not a user-facing feature — an operator one, and the foundation the next four
slices stand on.** The door a family walks through opened at
`shape/04/cost-answers-from-canonical` (RFC 166, 2026-09-05): these tables now
answer every cost question the coach and the Family Cost Report are asked.
Recorded here because the product's money surface has a different floor under
it.

**What it is.** Two fact tables beside `colleges`, filled offline by the ingest:
`price_figures` holds one published figure per row, keyed by college, price
concept, residency basis, living arrangement and academic year — so a price
carries the four things that decide whether it is _yours_. `cohort_money_stats`
holds numbers _about a population_ (average net price, the published-cost blend,
Pell share, median debt, median earnings), each with the population, residency
scope and aid scope it describes. The split is the point: a residency-blended
average can no longer sit beside a price as though it were one, because the
price table has no blend concept for a foreign key to name.

**Missingness now has a reason.** A figure's absence is one of six stored
statuses, not a bare NULL. The Scorecard's `PrivacySuppressed` — previously
discarded, indistinguishable from "the school didn't report it" — survives the
fill as `suppressed_by_publisher`. A value exists exactly when its status bears
one, enforced twice: a database CHECK, and a sealed Kotlin type that will not
compile the invalid pairing.

**The vocabulary is ours.** Five authored tables (residency bases, arrangements,
figure statuses, price concepts, income bands) are seeded from
`db/data/money-vocabulary.json`, validated fatally at load and proven against
the Kotlin enums in both directions. The five income bands now carry their
dollar ranges as data rather than as text in an enum.

**How you reach it.** `bin/ingest-colleges` runs the `canonical-money` phase
automatically; `-v` points at a different vocabulary file. The run prints the
canonical row counts and the per-status breakdown on stderr, and writes the same
facts to `college_index_build` (`price_figure_rows`, `cohort_money_stat_rows`,
`canonical_money_summary`).

**How it degrades.** Since RFC 166 a user-facing surface DOES read it, and it
degrades the way that surface does: a missing figure is a labelled blank naming
whose silence it is, never a zero. For the operator: the phase is transactional
and idempotent — a failure rolls the rebuild back and leaves the previous fill
standing, and a re-run of the same snapshot produces identical counts.

**Rollback.** The tables have a reader now (`shape/04`), so leaving them
unfilled removes cost answers rather than changing nothing. The reader's own
rollback knob is the coach prompt version (`COACHING_SYSTEM_PROMPT_VERSION=v18`
for the words); the store itself has no switch.

### Where the money numbers come from (brief 0006 `shape/02`, RFC 161)

**Door: `shape/04` (RFC 166, 2026-09-05).** These rows are read by the coach's
cost answers and the Family Cost Report; before that date nothing read them.
What this slice changed is _which source unicoach believes_ when two of them
disagree.

unicoach now pins IPEDS `IC2023_AY.csv` — the federal published-charges file —
alongside the College Scorecard, and fills the canonical `price_figures` table
from IPEDS **first**. Where both publish the same figure, the IPEDS value wins
and the row records which source it came from; nothing is ever averaged.

**Why it matters.** The Scorecard publishes tuition as an in/out pair, having
already collapsed in-district into "in". IPEDS publishes all three tiers
separately. Measured over the whole 2023 file: the two sources agree 100.0% on
out-of-state tuition, on-campus and off-campus food and housing, books, and
with-family other expenses — but agree only **92.1%** on in-state
tuition-and-fees, and **all 269 disagreements are exactly the in-district
figure**. Austin Community College's in-state price was being served as
**$2,550**; it is **$8,580**, and $2,550 is what you pay if you live in the
district. That class of error is now fixed at the source for the 3,825
institutions IC_AY covers.

Also gained: **fees split from tuition** (`fees_only`, so "tuition and fees" can
be decomposed rather than guessed at), **four academic years per file** (stored;
consumers still serve the latest), and **imputation flags carried as status** —
IPEDS marks values it estimated, and an estimated dollar figure can never be
presented as reported.

**What it does not have.** IPEDS assumes a student living with family pays
nothing for food and housing, so it publishes no such figure — and neither does
any other source. unicoach adopts the same assumption deliberately (**D17**):
living at home counts as **$0** food-and-housing, and the cost answer will say
so in words rather than printing a silent zero or blaming the school for a
missing figure. The reasoning is that eating at home is not free but is
negligible, and is not a _new_ expense caused by enrolling — which is what a
cost-of-attendance figure measures. IC_AY also covers 3,825 institutions, not
the full ~6,100: schools that report on a program-year calendar are in a
different file, and for those the Scorecard remains the only source, in-district
collapse and all.

**How it degrades.** A run that loads no IC_AY file still rebuilds the canonical
store from the last IC_AY data staged, so prices do not vanish between ingests.
Stale staged rows that no longer map are counted and reported rather than
failing the run, and rows for an institution that has left the file are pruned
so last year's price can never be served as current. A file whose survey year
disagrees with the pinned window is refused **before any phase commits**.

**Rollback knob.** Run the ingest without the IC_AY file group and the canonical
store falls back to Scorecard-only prices at the next full rebuild.

### What aid actually looks like at a school (brief 0006 `shape/03`, RFC 162)

**Door: `shape/04` (RFC 166, 2026-09-05).** These rows now reach families
through the coach's cost answers and the Family Cost Report; before that date
nothing read them. What this slice changed is that unicoach stores AID as well
as price, and stores who each aid number is about.

unicoach pins IPEDS SFA (student financial aid, aid year 2022-23) as the third
canonical source and fills `cohort_money_stats` from it: net price, net price by
the five income bands, Pell share and average award, grant mix by source
(federal, state and local, institutional), and loan share and average. A new
table, `cohort_population_counts`, stores the headcounts that make those numbers
mean something — how many students pay each residency rate, and how many live on
campus, with family, or off campus.

**Why it matters.** A single "average aid" number hides who it describes. Every
row now carries its population as structure, not prose, and the rule is that the
scope follows the DENOMINATOR: a _share_ is a share of the whole cohort, an
_average_ is an average over the students who actually received that aid. The
net price a public school reports is about students who received grant aid; the
income-band figures are about Title IV-aided students; they are different
cohorts, so they are stored as different rows rather than one being allowed to
overwrite the other. Population counts make the residency tiers concrete: at
College of DuPage, 1,488 of 2,099 students pay the in-district rate, so a
statement about "the in-district price" can be weighted rather than assumed.
Pell **average award** is a figure the Scorecard does not publish at all.

**What it does not have.** SFA describes a first-time full-time cohort for most
of its aid mix, so it is not a statement about every student at the school. It
trails the sticker-price file by about a year. And it cannot say "the school did
not report this": NCES imputes rather than blanking, so that status has no
producer here — an imputed value is marked as the publisher's estimate instead,
and is never presented as reported.

**A published zero is a zero.** IPEDS marks implied zeros with a flag that RFC
161 read as an imputation. Measured over the real files, those cells always
carry exactly 0 and are written by NCES itself, so both surveys now read them as
**reported**. That corrects landed behaviour: 17,357 honest zeros in one file
would otherwise have been labelled publisher guesses.

**How it degrades.** Every way a cell can fail to become a row is counted and
named in the ingest summary — a value that will not parse, a value present under
a not-applicable flag, a variable the fill expected but the loader never staged,
and an institution matching neither the public nor the private variable family.
An unrecognised imputation letter fails the run before any of it is served. The
fill reads only the staging its own run loaded, so an ingest without the SFA
group cannot resurrect a previous run's rows.

**Rollback knob.** Run the ingest without the SFA file group (`-S`/`-f`) and the
canonical store simply carries no `ipeds_sfa` rows at the next full rebuild;
IC_AY and Scorecard figures are untouched.

### What a school costs, answered from unicoach's own money store (brief 0006 `shape/04`, RFC 166)

**Door: ask the coach what a school costs, or open a shared Family Cost
Report.** This is the same door as before — no new screen, no new question — but
everything behind it changed. Every figure in the answer is now read from
`price_figures` and `cohort_money_stats`, the canonical store `shape/01`,
`shape/02` and `shape/03` filled, instead of from the Scorecard-shaped columns
on `colleges`.

**What you get that you did not before.**

- **The in-district price, named as its own tier.** A community college's
  in-district rate is no longer served under an "in-state" label. Roughly 269
  colleges carry a value-bearing in-district figure today. The existing
  residency offer's wording widens where one exists; no new question is asked.
- **Fees split from tuition**, where the school reports them split, so "tuition
  and fees" can be decomposed rather than guessed at.
- **A complete living-at-home total.** Its food-and-housing line prints as a
  labelled **$0**, and the answer says in words that this is **unicoach's
  assumption** (D17: eating at home is not free, but it is not a _new_ expense
  caused by enrolling). It is never a silent zero, and it is never described as
  something the school published.
- **The status of every figure, spoken.** A price the publisher withheld for
  privacy is said to be withheld by the publisher, not by the school. A value
  the publisher estimated is named as the publisher's estimate. A gap of ours is
  said to be **ours**.

**What did not change.** The honesty rules from RFCs 149, 151, 152 and 157 — the
arrangement totals, the assumption lines above a comparison, the silence
taxonomy, the withholding of a wrong-basis figure — behave exactly as before.
They are now projections of stored rows rather than read-time repairs, which is
the whole point of the brief.

**How it degrades.** A missing figure is a **labelled blank, never a zero**, and
the label names whose gap it is. A cost answer never mixes years: the year is
bound to the figures it dates, so a sticker price and a net price cannot be
printed under each other's year. A row unicoach cannot decode is dropped for
that college alone and logged for the operator; it can never fail every
college's answer. When the FitLens digest has no honest figure it omits the key
rather than asserting a false data claim.

**Rollback knob.** `COACHING_SYSTEM_PROMPT_VERSION=v18` returns the coach to the
previous prompt's words. The store itself has no switch — it is the source the
answers read.

### What a school asks you to file, and how it treats need (brief 0006 `shape/07a`, RFC 170)

**Door: ask the coach.** "Do we have to do the CSS Profile?", "does Amherst
require the noncustodial parent's form?", "does this school meet full financial
need?" No screen, no profile, no list entry needed — the answer rides on the
same `college_cost_profile` tool the cost answers already use.

**What it says.** Two different things, from that school's own Common Data Set,
each cited with the filing's year and a link to the school's own document:

- **The forms it requires** — FAFSA, the CSS Profile, the noncustodial Profile,
  the school's own form, a state form, the business/farm supplement. A form the
  filing does not list reads "not listed in this school's CDS", **never "not
  required"**: the corpus carries required checkboxes and no unrequired ones, so
  a "no" would be invented.
- **How it treats need** — the average share of need met, the number of freshmen
  whose need was fully met over the number who received any financial aid, and
  the average need-based grant. Each names its own cohort out loud, because the
  CDS reports these three against three different groups and "need fully met"
  sounds like "of everyone with need" when it is not.

**Coverage, honestly.** The CDS is a few hundred mostly-selective schools, not
6,000: 338 of the 417 seeded colleges carry a filing, need figures land for
312-321 of them, FAFSA for 272, and **the CSS Profile answer exists for only
73**. Everything else says so.

**How it degrades.** Three silences are three different sentences, and they are
never mixed up: _we hold no filing for this school_; _the filing does not report
this_; and **we could not read this cell** — our own gap, recorded as ours
(`not_collected_by_us`) and shown as a form we could not read, never as the
school's silence. Every other section of a cost answer is unaffected when the
aid-policy section is empty.

**Rollback knob.** `COACHING_SYSTEM_PROMPT_VERSION=v19` returns the coach to the
prompt without these answers; the rows stay in place and nothing else changes.
Running the ingest without the CDS group (`-m -a -d -p`) leaves the aid tables
unfilled — and, since RFC 170, that is refused loudly rather than silently
erasing what a previous run loaded.

### What graduates here borrowed, private loans included (brief 0006 `shape/07b`, RFC 175)

**The door:** ask the coach "do students at Amherst take out loans?" or "what do
students here borrow?" — the existing `college_cost_profile` tool answers, no
profile required and nothing gated — and the **Family Cost Report** page a
parent opens carries the same figures beside the Scorecard's federal one.

Until this landed, the only debt figure unicoach spoke was the Scorecard's
`median_debt_at_completion`, which counts **federal loans only**. Private loans
— no federal protections, no income-driven repayment — were invisible. The
Common Data Set answers the whole question, and now so do we: **the class that
graduated in a named year**, how many of them borrowed, and the average
cumulative principal they owed, split by loan type (any / federal /
institutional / state / private). Federal and private are said separately and
**never added together**; the "any loan" figure is always the school's own, not
our sum of the others. A loan is never subtracted from a price, and a debt
figure is never presented as a price.

Every figure is attributed to **the school's own filing**, with its year:
"Amherst College reports…". That is deliberate — a CDS filing is an unaudited
self-report, not an administrative record, and the voice says so.

**How it degrades**, four ways, each with its own sentence:

- no filing at all (~90 of 417 seeded colleges) → "we don't hold a Common Data
  Set for this school", never "students here don't borrow";
- a filing with no borrowing block → "this school's CDS doesn't report
  borrowing";
- a cell **we** could not read → said as OUR gap, never as the school's silence;
- figures that contradict the filing's own graduating class → withheld, and
  named as **our** inability to reconcile them, not as the school's failure.

A share is only ever said when both its count and its graduating class exist;
otherwise the average is said and the share is withheld rather than divided by a
guessed denominator. A **zero average is refused at ingest** — the source writes
`0` when nobody borrowed, and two thirds of the state-loan averages were that
placeholder — while a zero COUNT stands, because "no student here borrowed a
state loan" is true.

Coverage against the seeded set: graduating class 287, any-loan borrowers 218,
federal 214, private 212, institutional 156, state 147; averages 218 / 216 / 215
/ 65 / 46.

**Rollback:** coach prompt v21 → `COACHING_SYSTEM_PROMPT_VERSION=v20`. The data
stays; the coach stops speaking it.

### Search that ranks on the price you would pay (brief 0006 `shape/05`, RFC 169)

**The door:** the chat coach's search — "small schools in Oregon under $30k",
"like Bowdoin but cheaper" — plus every fit-lens sweep. No new screen, no new
tool; the doors that existed now rank differently.

Until this landed, every family was ranked on the same price: the Scorecard net
price, which at a public school is the **in-state** figure after federal aid. A
family in Washington asking for cheap California publics was therefore ordered
on a number it would never be billed. Measured over 2,971 operating four-year
schools, that ladder and an out-of-state published-total ladder order about
**22% of school pairs the opposite way** (Kendall tau 0.561, brief 0005).

A query now runs on exactly **one price ruler**, resolved once before any SQL is
built:

- **With the family's state on file** — ranking, filters, cheapest-first sort,
  the price axis of "schools like X" and `cheaper_than_anchor` all read that
  school's **published on-campus total at the family's own tuition tier**
  (in-state or out-of-state). The result says, in one sentence, that **aid is
  not in that ruler**. There is no out-of-state net price and there never can
  be: subtracting an in-state average grant from an out-of-state total is the
  arithmetic RFC 149 forbids.
- **With no state on file** — the ruler is the net price, exactly as before, and
  its basis is carried in the **metric name on the wire**, not in a footnote.

**The two rulers can never mix.** An anchor carries the ruler it was read on,
and a query that would pair two rulers is refused rather than quietly averaged —
a private's position moves when the publics around it move, so two ladders in
one distance term order schools by an arithmetic nobody could defend.

Search also now **asks for the state**. It is an offer, never a gate: a family
that does not answer still gets results, on the named net-price ruler.

A school whose publisher does not separate an **in-district** price is ranked
AND **labelled**, with the same sentence the cost tools already use — never
silently treated as in-state. 641 of 3,264 schools in the default search
universe carry that label; 354 correctly stay silent, because their publisher
did answer.

**How it degrades**, two ways, each said out loud:

- no residency on file → the net-price ruler, with its basis named on the wire;
- no published total for a school → that school is **dropped, counted and named
  under `excluded_unknown`** — never substituted with the other ruler's number.

Migration 0091 adds four nullable columns to `college_search_index` (two tier
values, two positions on the one out-of-state ladder) and extends the named
percentile CHECK from four clauses to six. It adds **no index**: every
published-price read is a `CASE` over two tier columns, which no single-column
btree serves.

**Rollback:** coach prompt v22 → `COACHING_SYSTEM_PROMPT_VERSION=v21`. The four
columns are additive, and a query with no residency on file resolves to the
net-price ruler — the pre-RFC-169 behaviour, column for column.

### Who published this number (brief 0008 `soft/01`, RFC 177)

**The door.** No new surface. Any family asking the coach what a school costs,
and any parent opening a shared Family Cost Report, sees it in the sentences
already there.

**What it does.** Every money figure now carries the publisher whose row won it,
and one seam turns that into English. A family is told which organisation
published the number in front of them, and the answer is read off the data
rather than typed by a developer at each site. Before this, chat and the report
page both said every cost figure came from the College Scorecard, while IPEDS
actually wins most of them — so families were told the wrong source for the
price of their school.

The publisher is named where the sentence is about the publisher's act: a figure
that is shown ("this figure comes from IPEDS' survey of college costs"), one the
publisher estimated, and one the publisher withholds for privacy. Where the gap
belongs to the school, or to us, the sentence stays as it was and names nobody —
crediting a publisher for a school's silence would be a new mis-attribution.

**How it degrades.** A publisher is named only when it published a figure the
family can actually see. A school whose only Scorecard rows are suppressed or
empty is described as an IPEDS-sourced page, and the sources sentence disappears
entirely for a page with no money figure on it, rather than naming a publisher
of nothing.

**Rollback.** `COACHING_SYSTEM_PROMPT_VERSION=v22` restores the previous coach
prompt; every earlier prompt row is immutable and stays in the catalog. The code
seam has no flag — it is a correction, not an experiment.

### How hard is the number behind this figure (brief 0008 `soft/02`, RFC 179)

**The door.** No new surface — the same door as RFC 177. Any family asking the
coach what a school costs, and any parent opening a shared Family Cost Report,
reads it in the sentences already there.

**What it does.** A money figure now says what KIND of number it is, in one
sentence beside the figure. Three tiers, derived from the publisher and the
published cell id, never typed by hand:

- **an administrative record** — "This comes from federal loan and tax records,
  not from the college. It covers only students who got federal aid, and small
  numbers are blurred a little to protect privacy." (median federal debt at
  completion)
- **a mandatory survey** — "The college had to report this to the federal
  government, and the form checks it against last year's answer. Nobody audits
  it." (every IPEDS charge and aid figure, and the 16 Scorecard cells that are
  re-published IPEDS)
- **a voluntary self-report** — "The college published this about itself. No one
  checks it. We link to the college's own file so you can see it." (every Common
  Data Set figure)

The tier is separate from WHY a figure is missing and is read as a pair with it:
status first — whose act the gap was — then the tier. The two are never merged
into one sentence, and a family is never shown a score, a percentage or a rating
(standing decision D7).

**Where it is actually visible.** In **chat**, a `figure_statuses[]` entry now
exists for a SHOWN figure too, carrying `assurance` and `assurance_statement`; a
shown reported figure still says nothing about its status, so it has no
`statement` — it gains the tier sentence only. On the **report page** the tier
rides the shown debt paragraph, and elsewhere only through the blank arms the
domain speaks for — imputed, suppressed, not-applicable, or a year gap that is
ours. An ordinary "Not reported by this school" blank deliberately keeps its
short cell-sized form and carries **no tier**: a table cell whose whole content
is two long sentences is a different page. The softest tier's one visible figure
is **average percent of need met** (CDS `H.209`/`H.211`), which the coach
answers "does Amherst meet full financial need?" from; it is not on the report
page at all, because the page renders no aid-policy section.

**How it degrades.** A tier is only ever spoken beside a figure a family can see
or beside a blank the domain speaks for; nothing new appears on a page with no
money on it. The Common Data Set's own read path calls the same resolver, so
there is no second mapping to drift. A Scorecard variable with no mapping is a
located, typed fault at read time rather than a guessed tier, and the case is
unreachable by construction: the closure test derives the key space from the
loader's own registry, so a 13th loaded column fails the test instead of
throwing in front of a family.

**Rollback.** There is **none to set**: no migration, no column, no seeded table
and **no new prompt version**. This is a code-level change, so undoing it means
reverting `main@922e33c6`.

### The served vocabularies (brief 0007 `profile/01`, RFC 165)

**Door: none for a user yet — this is a server surface a client walks through.**
`GET /api/v1/vocabularies` (session cookie required, no student profile needed)
returns every closed vocabulary the product publishes, as one document:

    { "version": "<content hash>",
      "vocabularies": { "<name>": { "entries": [ { "value": ..., "label": ... } ] } } }

`value` is what the server accepts; `label` is what a person reads. A vocabulary
may add extra keys — `residency_states` adds `jurisdictionKind`, because a UI
that calls Palau a state is wrong — but never fewer. Array order is display
order, decided by the server, so no client sorts anything.

Registered today: **`income_bands`** — the five household bands with their
spoken dollar ranges ("$30,001 to $48,000"), never the slug (RFC 142) — and
**`residency_states`** — the 59 USPS codes with names, which is _literally_ the
set `MoneyProfileService` validates a write against. That identity is the point:
a picker built from this endpoint cannot offer a value the write path rejects.

**What may be added:** a vocabulary that is closed, small, user-independent, and
carries a real human label. That admits living arrangement, dependency status
and college-list status later. It excludes the subjects taxonomy and the college
catalog, which are large or open. Only vocabularies with a consumer are
registered — adding one is a single registration line, no new route and no
client change.

**How it degrades:** it does not. If a residency code has no name row, the
endpoint fails the whole request (500) rather than quietly serving 58 of 59
states — a shrunken list is exactly the divergence this endpoint exists to
remove. That does mean a database where the codebooks have never been loaded
serves 500 here, which is a boot-order coupling worth knowing.

**Rollback knob:** none needed; it is additive and read-only. Removing a
vocabulary from the registry is one line.

**Who uses it:** the "Your details" screen (RFC 171, 2026-09-05) renders both
pickers from this endpoint — server labels, server order — and RFC 163's Swift
copies of the two lists are now only its offline fallback, pinned here by tests.

**`version`** is a short content hash of the served document, so a client can
skip re-rendering. It is deliberately not an ETag, and **nothing consumes it**:
`profile/02` decodes the field and ignores it, and the endpoint sets no cache
headers. Whoever adds vocabulary caching starts there.

### Your details — your income band and where you live (brief 0007 `profile/02`, RFC 171)

The two money-profile facts, readable and correctable without a conversation.

- **How a user reaches it:** the slide-over menu row **"Your details"**, above
  "My colleges" — it pushes a screen. That row is the whole door; the coach
  never sends anyone here and never mentions an edit made here.
- **What it does:** two fields — household income band, and state or territory
  of residence. Each one always reads in one of three states: not answered, the
  value, or "you chose not to say". Two controls sit under each field. **"Prefer
  not to say"** declines it and names its cost — the coach stops asking.
  **"Remove my answer"** clears the field back to not-answered, which re-arms
  the coach's invitation. A declined field keeps a **live picker**, so a decline
  is never a one-way door.
- **Saving:** each field saves on its own, immediately, and shows a terse
  per-field "Saved" receipt that VoiceOver announces. There is no Save button
  for the screen and no confirmation step.
- **Where the menus come from:** `GET /api/v1/vocabularies` (RFC 165) — server
  labels, server order, and all **59 jurisdictions the server accepts**. That is
  why the field is "state or territory" and not "state": a menu that lists Guam
  must not call Guam a state. RFC 163's shipped Swift lists remain **only as the
  offline fallback**, pinned to the server by tests.
- **Degrades gracefully:** a vocabulary that is absent, empty, or carries one
  unrenderable entry falls back to the shipped list **whole, per vocabulary**,
  and the screen says the list may be incomplete. A failed save keeps the
  family's answer on screen and offers retry. A 404 reads as "nothing answered
  yet", not as an error; a 409 `student_profile_required` routes the user rather
  than showing the screen.
- **Rollback knob:** none. This is a client-only slice — no server change, no
  migration, no new table, no flag and no prompt version to turn back.
- Honest limits: the app now carries **two residency menus** — this screen
  offers the 59 served jurisdictions, while onboarding keeps RFC 163's
  deliberate 51-item menu; changing onboarding is a /chart product decision. And
  the money profile's fourth field, `dependency`, is still **chat-only** — it is
  on neither REST verb, so no screen can reach it.

### Money profile (brief 0001 S2, RFC 134)

Where the family's income band and residency state live, so the right price band
can be chosen.

- **How a user reaches it:** the coach invites in conversation
  (`update_money_profile` tool) — the student can start, stop mid-way, resume
  across sessions, skip entirely; never forced, never a form — and, since RFC
  171, the **"Your details"** screen above, where the income band and the state
  or territory can be set or cleared directly.
- Tri-state per field (unset / declined / value), atomic upsert, admin read-only
  view.

### Finding a college by name (brief 0004 S1, RFC 139; matching replaced by RFC 146)

Typing a school's name finds it even when the typing is imperfect.

- **How a user reaches it:** the iOS college-list screen's name search
  (`GET /api/v1/colleges?q=…`), and the coach's `search_colleges` tool in chat —
  both inherited the upgrade with no API change.
- **What it does:** a typo is **one keystroke wrong** — a substitution,
  insertion, deletion, or adjacent transposition — so a school matches when
  every word of the query is within one keystroke of some word of its name or
  curated aliases, or the query is a literal substring of them. "Amhurst" finds
  Amherst College, "Amhurst Colege" finds it too, and "Mizzou" finds
  Missouri-Columbia. Exact and prefix matches still rank first. Trigram
  similarity (`pg_trgm`) and its 0.6 threshold are gone: they ranked Elmhurst
  University above an absent Amherst College for "Amhurst", and no threshold
  repaired that (RFC 146).
- **For the coach:** results now carry an unclamped `total_matches` ("312 match;
  showing 25" is finally sayable), a `sort_by` that never filters, and a
  `credential_level` word enum ("bachelors"), never raw Scorecard codes.
- **Operationally:** every ingest writes a `college_index_build` provenance row
  (source sha256s, per-table counts, skip taxonomy, non-null deltas), asserts
  its source headers before writing a single row, and prints a change summary —
  a no-op load can no longer masquerade as a real one.

### Searching for colleges by what they are (brief 0004 S3, RFCs 147 + 150)

"Small public schools in Maine with a literature program" — asked in plain
English, answered honestly.

- **How a user reaches it:** the coach in chat. It calls `search_colleges` on
  coach prompt **v10**; no user action and no new screen. Live on the next
  `service` deploy once the migrations and an ingest have run.
- **What it does:** filters on **subject** (a 181-subject taxonomy —
  "literature" means CIP 23.01, 23.13, 23.14 _and_ 16.0104 Comparative
  Literature, because a person authored that), **state**, **size**,
  **selectivity**, **price**, **religious affiliation**, **test policy**,
  **Carnegie class**, **athletics**, **ROTC**, **study abroad** and **housing**
  — all against one derived `college_search_index` rather than a join across the
  raw tables. The filters bind codebook slugs with real foreign keys, so no
  federal code reaches the model and none has to be looked up at query time.
- **What it reports:** `total_matches` (the true count, not the size of the page
  it returned), a per-filter `excluded_unknown` count, and `source_years` for
  the figures it used.
- **How it degrades — the honest part:** an index that has not been built yet
  **says so** instead of answering zero (a deploy migrates first, then ingests;
  between the two, search states its own emptiness). A word the vocabulary does
  not know is a **named refusal that lists the vocabulary** — never a silent
  empty result, and never presented as "the search broke". An attribute a school
  does not report is **counted in `excluded_unknown`, never read as "no"** —
  filtering on it excludes the school and says how many it excluded.
- **Where the data comes from:** the same ingest, in two new phases — `subjects`
  loads the authored `db/data/subjects.json` (every CIP prefix validated fatally
  against RFC 147's published codes: 1,690 of 1,710 codes, 405/405 four-digit
  series, 38/38 families), and `search-index` rebuilds the index whole in one
  transaction and records the row count in the `college_index_build` provenance
  row.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v9`; the v9 row is immutable and
  stays in the catalog.
- **Honest limits:** `credential_level` **left** `search_colleges` — the program
  census carries bachelor's first majors only, so the filter had one legal value
  and asserting a choice would have been a lie. It returns only if
  `college_programs_census` ever carries more than that. The percentile-rank
  columns are computed but read by nothing until `search/04/similar-colleges`.
  `is_active` is not tri-state (Backlog). The `colleges` state/locale foreign
  keys, which did not land with S3b, landed in the 2026-08-31 fast-follow
  (migration 0067): `state` and `locale` now reference the published codebooks,
  and so does `college_search_index.state`.

### Naming a school in chat and having the coach find it (brief 0004 S5, RFC 154)

**The door:** the chat coach. Say "add Mizzou to my list" — or name any school
in words, including a misspelling or a nickname — and the coach looks it up and
acts on it. No new screen and no user action; live on the next `service` deploy
once migration 0068 has run.

Until this landed, fuzzy name resolution existed in exactly one place and was
reachable only over REST (the iOS college-list picker). `search_colleges` takes
no free text and `update_college_list` demands a UUID, so a school the student
named in words had no path to a `college_id` in conversation.

- **What it does:** a new `find_college` chat tool resolves a name to a college
  over the picker's own `CollegeSearchService.searchByName` — the same
  one-keystroke matching the picker uses (RFC 146). No new SQL, no new table, no
  DDL. Coach prompt **v12** tells the coach to resolve a named school with
  `find_college` and then use the returned id **verbatim** for
  `update_college_list`, the cost tool and the admissions tool;
  `search_colleges` stays for attribute-shaped discovery ("small public schools
  in Maine"). Two tools, one division of labour.
- **How it degrades — honestly, in three different ways:** an index that has not
  been built yet says **the search is unavailable**, and never says the school
  does not exist. A **blank** name is refused by name — the tool says which of
  its own fields was empty, rather than reporting "no school by that name" for
  an input that named nothing. A real **zero-match** is an honest "no school by
  that name", not an error. An over-long name is a rejected input, not a failed
  search, so the coach asks for a shorter name instead of apologising for an
  outage.
- **The module convention is now written down**, in `CollegeSearchService`'s
  KDoc and cross-referenced from `CollegesDao`: search goes through the service
  over `college_search_index`; point-reads by id or unit_id stay on `colleges`;
  ingest and versioning write `colleges`. Recorded ruling: admin-web's college
  browse stays on `colleges`, because it is an unfiltered browse of raw source
  columns the index does not carry.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v11`; the v11 row is immutable
  and stays in the catalog, so this is one environment variable, no migration.
- **Honest limits, carried forward on purpose:** the `NewCollege` test fixture
  builder is now a 4th copy and the seed+rebuild transaction is duplicated, and
  there is still no shared `JsonTool` / `DelegatingChatTool` abstraction —
  `FindCollegeChatTool` is a line-for-line copy of `CollegeChatTool`. Both are
  best fixed when `search/04/similar-colleges` adds the next copy.

### Finding schools like the one you already love (brief 0004 S4, RFC 153)

**The door:** the chat coach. Name a school — "what are some schools like
Bowdoin?" — and the coach resolves the name to a college with `find_college`
(RFC 154) and then calls `similar_colleges`. No new screen and no user action;
live on the next `service` deploy once migration 0069 has run.

- **What it does:** answers with up to ten peers from one query over
  `college_search_index` — the default universe, the caller's hard constraints,
  a weighted-distance `ORDER BY`, `LIMIT <= 10`. There is no similarity table
  and nothing is precomputed: "similar" is chosen per call, because there is no
  single true answer to what makes two schools alike (Ian, gate 1: "I'm not sure
  it even makes sense to pre-define it"). Five axes are available — **size**,
  **selectivity** and **price** over RFC 150's percentile columns, **setting**
  by locale, and **subject mix** by overlap of the 181-subject taxonomy. A bare
  "schools like X" ranks size + selectivity + setting, holds control constant,
  and stays inside active four-years.
- **Price is not a default, on purpose.** Ranking on price by default would turn
  a question about what a school is like into a question about what it costs.
  You get it by asking: **"like X but cheaper"** and **"like X but where I'd
  likely get in"** are anchor-relative asks — the tool expands them against the
  anchor's own numbers and the coach says in words what it did. The second one
  also stops ranking on selectivity, because otherwise the ranking pulls back
  toward the anchor while the constraint pushes away from it.
- **Outcome measures are never a similarity axis** (gate-1 ruling). Earnings and
  completion are reported and cited; they never decide who is "like" whom.
- **How it degrades — by naming what it could not judge, never by guessing:** an
  axis the anchor has no data for is dropped for the whole query and reported
  with its reason; a candidate missing an axis is scored on the rest and says
  which axes it was scored on; a candidate that shares no axis at all is
  excluded and counted. Nothing is substituted, averaged in, or treated as zero.
  An index that has not been built says the search is unavailable rather than
  answering zero. **Every response names each axis and each constraint it
  actually used**, so the coach's explanation is literally the query that ran.
- **Reproducibility is traded away on purpose** (Ian, gate 2: "This is okay to
  give up"). A future surface that needs a stable peer list pins a preset then.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v12`. The tool stays registered
  but un-prompted, so the coach stops reaching for it — one environment
  variable, no migration.

### Know how a school admits and what it pays (brief 0001 S4, RFCs 140 + 148)

School-authored Common Data Set facts for the launch set, now answerable in chat
with citations.

- **How a user reaches it:** conversationally. Ask what a school weighs, when it
  closes, or whether it gives merit aid, and the coach calls the
  `college_admissions_profile` tool over the schools on the student's active
  list. Merit also appears inside cost answers without a second question.
- **What it does:** three cited sections per school — **merit aid** ("X% of all
  full-time freshmen received non-need (merit) aid; the average was $Y"), the
  **C7 admissions-factor grid** in the school's own words ("very important",
  "considered"), and the **application calendar** (which rounds a school runs,
  which it does not, and the dates it published). Each section names the
  school's own CDS document, its cycle, and an archive link.
- **The denominator is honest by construction:** the CDS publishes no count of
  no-need freshmen, so we never claim one. A share is emitted only when both
  counts exist — a freshman total alone is a denominator, not a fact.
- **Degrades gracefully:** a school with no row is named as "not reported",
  never interpolated and never a zero. `0` recipients is a real reported value.
  A month with no day reads "January, day not reported". A round the school says
  it does not offer is said plainly, because that is a fact too.
- **Coverage:** 415 launch-set colleges — merit 366, factors 374, deadline flags
  314 (234 with a concrete date), 0 student-listed schools missing.
- **Live in prod:** yes, behind coach prompt v8.

Refresh the seed with `nix develop -c bin/fetch-cds-seed` (review the diff,
commit), load with `bin/ingest-colleges -m/-a/-d`. The ingest records the seed's
digests and per-table counts into the `college_index_build` provenance row.

### Show a parent the price, with no account (brief 0001 S5, RFC 155)

**The door:** the chat coach. The student asks the coach to share the cost
report; `share_cost_report` returns a link. The parent opens
`https://app.uni.coach/report?token=...` on a phone — **no login, no parent
account, no app**. `revoke_cost_report_share` takes it back. Live on the next
`service` and `public-web` deploy once migrations 0073 and 0074 have run and
`COST_REPORT_SHARE_TOKEN_SECRET` is set.

- **What it does:** renders the student's college list as a cost table for a
  parent — the six `comparison_basis` assumption sentences (RFC 157 added the
  blended-figure basis), a cross-school summary table (rows are schools, one
  held-constant way of living), per-school living-cost detail, cited merit
  practice, debt context, and a sources block. It is the same money vocabulary
  the coach speaks (brief 0003), on a page.
- **The page is live, not a snapshot,** and it says so on the page: every view
  recomputes from the current list, money profile and data, so a parent who
  re-opens the link in March when the aid offers arrive reads March's answer.
- **The token is derived, never stored:**
  `HMAC-SHA256(shareTokenSecret, row
  id)`, with only the SHA-256 hash in
  `cost_report_shares` (migration 0073). Re- sharing reproduces the **same**
  link, a database leak yields **no** working link, and rotating the secret is a
  **global revoke**. **One live share per student**, enforced by a partial
  unique index; revoke is a promise about every link ever sent, not just the
  latest one. There is no expiry — the control is revocation, and it is
  immediate.
- **What the page does not say:** no name, no email, no user id — the link is
  not proof of who sent it, so the page never asserts it; it opens as "Your
  student's college list". It does state the family's income band, because a
  likely price is a price for a family like this one. The page is `noindex` /
  `no-store` / `no-referrer`, and the token is redacted from the request log.
- **How it degrades:** no money profile → overall figures with the basis stated,
  never a band claim. A school missing a part → a labelled blank, never a zero,
  never a neighbour's number, and no partial total. **A public school outside a
  KNOWN residency shows no published price and no price after aid** (RFC 157):
  both are in-state figures, so they are withheld at the source, and the blank
  names the reason and points the parent at the arrangement totals, which are
  built from the tuition this family would actually pay. An unanswered residency
  withholds nothing and states the in-state basis instead. An empty college list
  says so instead of rendering an empty table. An unknown, malformed or revoked
  token → the ordinary branded 404, never "revoked" and never "expired", so the
  page leaks nothing about which links once existed.
- **One public-web origin:** `publicWeb.urlBase` is now the single origin, and
  both the verify-email link and the report link derive from it. The two
  per-link env vars remain as overrides that still win.
- **Rollback:** `COACHING_SYSTEM_PROMPT_VERSION=v15` returns the coach to the
  prompt before RFC 157; `v14` turns the coach's share offer off entirely (both
  rows are immutable and stay in the catalog). Unsetting
  `COST_REPORT_SHARE_TOKEN_SECRET` leaves the feature dark: it declines honestly
  and warns once at boot.
- **Not yet:** no iOS share sheet (RFC 155 **D-I**, chat is the door), and no
  share or view is recorded anywhere — that is `first-value/06`.

### The coach asks you to share it (brief 0001 S6, RFC 160)

**The door:** the chat coach, in an ordinary conversation. The student does not
have to think of sharing. The nightly synthesis pass writes a share-nudge
reflection for an eligible student, and the **next conversation's opener**
raises it once, naturally: "when the moment is right, suggest sharing the family
cost report with a parent". If the student says yes, the existing
`share_cost_report` tool returns the link (see the entry above). Live on the
next `service` deploy once migrations 0080-0082 have run, the synthesis
`periodic_jobs` row is enabled, and `COST_REPORT_SHARE_TOKEN_SECRET` is set.

- **What it does:** turns the Family Cost Report from a feature the student had
  to discover into one the coach offers at a moment it chose. Eligibility is
  deterministic, not an LLM judgement: **>= 2 active college-list entries** (the
  report is a comparison, so two schools make one), **no live share** (a student
  who already has a link needs no invitation), **no opt-out**, no nudge already
  open, and the open-commitment cap not reached.
- **Nudging more than once:** allowed, and deliberately so — an active student
  whose list keeps growing may be asked again. A repeat needs **both** a
  **14-day cooldown** and a **change to the college list** since the last nudge,
  so it always has something new to point at. Tune with
  `SYNTHESIS_SHARE_NUDGE_COOLDOWN_DAYS`.
- **"Never ask me again":** the coach calls **`stop_cost_report_offers`**, which
  records a permanent `opted_out` event **and drops any nudge already written**
  in the same transaction — so a nudge that was queued cannot ride a later
  opener after the student said never. It does **not** revoke a live link and
  does **not** disable sharing: the student can still ask to share whenever they
  want. There is no un-opt-out path by design.
- **How it degrades:** a decline or a deferral closes the topic for that
  conversation with no residue, and nothing anywhere is gated on sharing
  (guided, not gated). With the nudge off, the coach behaves exactly as it did
  under RFC 155 — it will still offer the report after a cost comparison, and
  still never opens with it unasked.
- **What it records:** `share_events`, append-only — `minted`, `repeat`,
  `reissued`, `revoked`, `opted_out`, each written in the same transaction as
  the share mutation it describes. Repeat asks were invisible before (re-asking
  returned the same row and touched nothing), so this is the first honest read
  on how sharing is actually used, and the substrate Beat 2's parent-account
  claim path reads.
- **What it does NOT record:** whether a parent ever **opened** the report.
  Deliberate — a page view on a tokenized link is parent-side behaviour, and the
  number would need bot filtering to mean anything. Beat 2 decides.
- **Rollback:** `SYNTHESIS_SHARE_NUDGE_ENABLED=false` stops new nudges (already
  written ones still deliver until fulfilled);
  `COACHING_SURFACE_COMMITMENTS=false` stops all commitment delivery;
  `COACHING_SYSTEM_PROMPT_VERSION=v17` restores the previous coach prompt.
  Disabling the synthesis `periodic_jobs` row stops the pass entirely.

### College list (RFC 91 schema/REST; RFC 136 chat door)

The student's working list of schools — the substrate the cost feature keys off.

- **How a user reaches it:** conversationally, via the `update_college_list`
  chat tool (RFC 136): add, restatus, remove. The coach offers, never nags; an
  entry is always changeable.
- **Native door:** the iOS college-list screen (RFC 137), reached from the
  slide-over menu — view the list, add via name search
  (`GET /api/v1/colleges?q=…`), restatus, edit reasons, remove. Entries now
  carry `collegeName` on the wire.
- **Saving from that screen no longer destroys a living plan** (RFC 164 server
  side, RFC 168 client side): if the coach set a per-college living plan in
  chat, editing the status or the reasons on the screen keeps it. The app can
  also say "clear the plan" on the wire, but **offers no control for it yet** —
  there is no living-plan picker on the screen, and brief 0007 defers one. The
  note field is different on purpose: clearing the note is what the screen's
  Clear button does.
- History: this was the reachability lesson — S1–S3 shipped behind a list no
  user could edit; S3.5/RFC 136 opened the door.

### Accounts and subscriptions

Signup with email verification; Apple/Google SSO identities
(`user_auth_identities`); paid subscriptions (parents are the payers). Known
gap: **no in-app account deletion** — a review-blocking App Store requirement
(5.1.1(v)) tracked as brief 0002, P1 below.

_Backfill note: features that predate this file (college search tool, fit lens,
synthesis commitments, admin surfaces) are documented in their RFCs; entries get
added here as slices touch them._

## Work — prioritised

P1 = needed for public launch or unblocking others; P2 = the differentiator
beat's remainder; P3 = in flight but not on the critical path. Unprioritised
ideas live in the Backlog below, not in the table. "State" is honest partial
progress — this is the column /chart reads to know what "halfway done" means.

| Pri | Work                                  | State                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Where                                        |
| --- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| P1  | How soft is this number? (brief 0008) | **GATES 1+2 APPROVED (2026-09-05); TWO OF FOUR SLICES LANDED.** **`soft/01/one-hedge-seam` LANDED as RFC 177 (`main@3c010785` + `ebaef69e`, 2026-09-08, migration 0093, coach prompt v23)** — attribution is derived per figure at one seam (`MoneySourceCopy` + a source-carrying `FigureStatusCopy`), `CostSources.SCORECARD_ATTRIBUTION` is deleted, and the LIVE defect is dead: IPEDS figures are no longer credited to the College Scorecard in chat or on the report page, pinned by a test that fails on the old code. A publisher is named only when it published a figure the family can SEE, and a fifth `MoneySource` now fails to COMPILE at the seam. Review found and fixed a second instance of the same defect class (the page named publishers of suppressed and value-free rows) and a nullable that would have rendered the literal "null" into coach copy. Two slice-text items were returned to /chart as a spec defect: the four aid-policy constants and the report's merit/borrowing CDS sentence state CORPUS COVERAGE, not a figure's publisher, so deriving them from `MoneySource` would state something false. **`soft/02/assurance-tiers` LANDED as RFC 179 (`main@922e33c6` + `55b829f3`, 2026-09-09, no migration, no prompt version)** — a figure now says what KIND of number it is. `AssuranceTier` (`ADMINISTRATIVE_RECORD` / `MANDATORY_SURVEY` / `VOLUNTARY_SELF_REPORT`) is a pure function of `(MoneySource, source_variable)`, read as a PAIR with `FigureStatus`; `source_variable` now travels to the copy seam, a SHOWN figure gets a note at last (so the tier is visible beside a dollar amount, not only on blank cells), `figure_statuses[]` gains `assurance` + `assurance_statement`, and `AidPolicyDao` — the Common Data Set's own door — calls the SAME resolver, so the CDS has no special case. An unmapped Scorecard variable is a located, typed fault, and the closure test derives the Scorecard key space from the loader's own registry (`ScorecardInstitutionColumns.LOADED_VARIABLES`) instead of hand-listing it. **Four spec corrections returned to /chart**: H.208 is `need_fully_met_count`, a HEADCOUNT in `cohort_population_counts` whose table carries no `source_variable` read row, so it cannot reach the copy seam — the softest sentence is pinned to **H.209 (and H.211)**; `DEBT_MDN` is not a column this loader reads, so it has no tier; DISCOVER item 9 is **stale** (RFC 175 already landed the H4/H5 borrowing facts); and no College Scorecard data dictionary was ever committed and there is no xlsx reader on the JVM classpath, so RFC 179 commits the 24 rows we use as `db/seed/scorecard/dictionary-variable-sources.csv` with its sha256 in `db/seed/scorecard/FETCH-NOTES.md`. **One OPEN item**: a `PublishedCell` value type binding `(source, source_variable)` was asked for by THREE separate review lenses and declined here — the pair travels as two loose primitives and a wrong pairing compiles, but the id is written by the IPEDS and CDS loaders too, so the type crosses `:db`, `:college`, `:service` and `:public-web`. **It wants its own slice.** `soft/03/the-year-we-cite` and `soft/04/two-more-ingest-refusals` are the two slices left, both READY and unblocked; `soft/03` CONFLICTS with `soft/02` on `CanonicalMoneyLoader.kt`, which is rebase risk only, not an order. The money store treats a Scorecard administrative record and a school's unaudited self-report as the same kind of fact, and the hedge that covers the difference is hand-written at ~39 prompt sites plus 19 Kotlin strings. Research killed the obvious shape: a `self_reported` flag on the SOURCE would be false, because **16 of the 18 Scorecard cells we load are re-published IPEDS** and only `GRAD_DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10` (Treasury) are administrative. The axis is real from the publishers' own words — IPEDS is mandatory under 20 USC 1094(a)(17), edit-checked and keyholder-attested; the CDS has no collector, no deadline, no sanction and no audit. Ian's "14 of 249 filings report more borrowers than graduates" reproduces **exactly**, but **12 of the 14 are our own PDF-extraction failures** (two carry the class year as the graduate count), so it measures our extractor, not school honesty — which is why **no findings table is being built** (every internal check runs 0/309 today; `bin/fetch-cds-seed` already drops those blocks). Four slices: `soft/01/one-hedge-seam` (one derived attribution, prompt v21, kills a LIVE defect — IPEDS figures are credited to the Scorecard in chat and on the report page today), `soft/02/assurance-tiers` (three tiers keyed on `(source, source_variable)`, code-side, no migration; BLOCKED on 01), `soft/03/the-year-we-cite` (Scorecard AY2024-25 is stamped 2022, so RFC 161's designed IPEDS displacement NEVER FIRES and NPT4 collides with SFA 2021-22 — held until `shape/07b` lands, to avoid a three-way rebase on `CanonicalMoneyLoader.kt`), `soft/04/two-more-ingest-refusals` (two cross-source refusals; **8 wrong freshman counts ship today**). Standing decision D7: **unicoach never renders a confidence score, percentage or rating for a figure.** | `product/0008-how-soft-is-this-number`       |
| P1  | Money in unicoach shape (brief 0006)  | **Gates 1+2 APPROVED (2026-09-02, defaults, no amendments); WAVE 1 COMPLETE, WAVE 2 MOSTLY DONE.** Canonical money layer: PriceFigure vs CohortMoneyStat, stored missingness status, authored vocabularies, IPEDS IC_AY + SFA un-deferred, policy-parameter store, staged cutover. Ten slices specced (`shape/07b/borrowing` was split out of `shape/07` at design and is now SPECCED, 2026-09-05), **six landed**. **`shape/06/pell-and-loans` LANDED as RFC 159 (2026-09-03)**; **`shape/01/canonical-store` LANDED as RFC 158 (2026-09-04)**; **`shape/02/ipeds-ic-ay` LANDED as RFC 161 (2026-09-05)** — IPEDS charges fill the store ahead of the Scorecard, making the in-district tier real (269 institutions were served their in-district price under an in-state label) and splitting fees from tuition; **`shape/03/ipeds-sfa` LANDED as RFC 162 (2026-09-05, migration 0085)** — IPEDS SFA fills the store with AID (net price by income band, Pell share and average, grant mix by source, loan share and average) plus `cohort_population_counts`, the residency and living-arrangement headcounts behind every basis; aid scope now follows the denominator; **`shape/04/cost-answers-from-canonical` LANDED as RFC 166 (2026-09-05, migration 0086, coach prompt v19)** — **the door**: every cost answer the coach and the Family Cost Report give now reads `price_figures` and `cohort_money_stats`, the in-district tier and split fees are spoken, living at home gets a complete total whose `$0` food-and-housing line is labelled as OUR assumption (D17), and each figure's status is said aloud; the RFC 149/151/152/157 honesty layer survives as a projection. **`shape/07a/need-and-forms` LANDED as RFC 170 (2026-09-05, migrations 0087/0088, coach prompt v20)** — the CDS answers what a school ASKS you to file (`aid_forms` + `aid_form_requirements`) and how it TREATS need (the canonical money tables); the modeled-ahead `aid_policy_facts` was DROPPED rather than filled, because the data is two shapes, not one bag; `source_documents` gives a filing one row so three older CDS tables stop duplicating its urls; `academic_year` and `money_source` became DOMAINs and the `'undated'` sentinel became NULL. **`shape/07b/borrowing` LANDED as RFC 175 (2026-09-08, migrations 0089/0090, coach prompt v21)** — CDS H4/H5 borrowing including the private loans the Scorecard never shows; loan type is part of the MEASURE (the `shape/03` grant-mix pattern), so no table and no `loan_types` vocabulary was added, and Ian's gate flipped D9 (the Family Cost Report page carries it too) and added D10 (a CDS figure is spoken as the school's OWN claim). Review added D11: a zero average over zero borrowers is the source's sentinel, refused at ingest, while the zero COUNT stands. **`shape/05/search-on-your-price` LANDED as RFC 169 (2026-09-08, migrations 0091/0092, coach prompt v22)** — search runs on ONE price ruler: with the family's state on file, each school's published on-campus total at that family's own tuition tier, with aid said to be absent from it in one sentence; with no state on file, the net price as before with its basis in the metric NAME on the wire. The two rulers can never mix (the anchor carries its own ruler; a mixed pairing is refused). Search now ASKS for residency, an offer that never gates a result, and **D19 landed with it at zero DDL cost** — a school with no separately published in-district price is ranked AND labelled (641 of 3,264 in the default universe), never silently treated as in-state. Migration 0091 is four nullable `college_search_index` columns plus a 4→6 clause percentile CHECK and **no index** (every published-price read is a CASE over two tier columns). **Honest next state**: NINE of ten slices landed. **`shape/08/drop-the-publisher-shape` LANDED as RFC 176** (2026-09-09, migration 0094) — the re-spec worry recorded here is spent, and the publisher money columns are gone. `shape/09/exchange-participation` is the one unlanded slice, and it is **DEFERRED by intent**, not blocked. **Spec defects found and closed the same day**: `CHG7/8AY` is off campus NOT with family, so no source publishes with-family food and housing — **D17 (Ian, 2026-09-05) treats living at home as $0 and states the assumption in words**, and shape/04 implemented exactly that; and shape/03's spec text carries a public-only SFA variable list (~65% of colleges would be dropped) plus a `Z`-flag mapping that is wrong for both IPEDS surveys — **RFC 162 corrects landed RFC 161 on `Z`**, and both are open items for /chart. Brief 0005 PAUSED into this brief (D11); D14 decided its search-ranking question.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `product/0006-money-in-unicoach-shape`       |
| P1  | College search index (brief 0004)     | **CORE COMPLETE** — gates 1+2 approved (2026-08-27); every specced slice has landed: `search/01/honest-name-search` (RFC 139, matching later replaced by RFC 146), `search/02/ipeds-attributes` (RFC 144), `search/03a/published-codebooks` (RFC 147), `search/03b/the-index` (RFC 150), `search/05/consumer-sweep` (RFC 154) and `search/04/similar-colleges` (RFC 153, 2026-09-01). S3b was the aha — the derived index serves both search paths. S5 turned out to be an audit (RFC 150 had already repointed every consumer, so there was nothing to delete) and closed the real gap instead with the `find_college` chat tool. S4 closes the brief: `similar_colleges` answers "schools like X" with one query-time weighted distance over the index, no similarity table, on coach prompt **v13** — and it is the first and only reader of the percentile columns S3b computed. The triggered `colleges` state/locale foreign-key fast-follow also LANDED (`main@9789b823`, migration 0067). **Nothing here is startable.** `search/06/unattended-refresh` stays DEFERRED — automate the quarterly ingest only if running it by hand proves annoying. The debt S4 declined moved to the Backlog: the 5th `NewCollege` fixture copy, and genericising `CollegeSearchOutcome`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `product/0004-college-search-index`          |
| P1  | Clear money language (brief 0003)     | **COMPLETE — every slice landed.** `money/01` + `01.1` + RFC 143 + `01.2` + `02` + `03` + **`04/where-youll-live`** (RFCs 141–143, 145, 149, 151, 152; 2026-08-28 to 09-01). The coach asks residency before income, prices three living arrangements from six ingested Scorecard components, states the assumption lines above any side-by-side, and now leads with the one way of living the family said they plan — a global default with a per-college override, because living at home is possible at the in-state school and not at the far one (D20). When it cannot show a total it says which kind of silence it is: our unanswered residency, a price we cannot select, or a part the school does not publish. Prompt v14; v13 is the rollback. Nothing left in this brief.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `product/0003-clear-money-language`          |
| P1  | Beat 1: brief 0001 — COMPLETE         | **BEAT 1 IS DONE. All six slices landed.** `first-value/06/invite-your-parent` **LANDED as RFC 160** (`main@9104eeb7` + `c23953e0`, 2026-09-03): the synthesis pass writes a `share_report` commitment for an eligible student (>= 2 active list entries, no live share, no opt-out, no open nudge, cap not hit) and the existing next-session opener raises it — deterministic code, not an LLM lens, inserted in the read-phase transaction so it fires even when the LLM phases no-op on freshness. **Ian amended the drafted policy at the gate**: re-nudges are allowed (14-day cooldown AND a list change since the last nudge) and "never ask me again" is permanent via the new `stop_cost_report_offers` tool, which also drops any nudge already written. New append-only `share_events` (minted/repeat/reissued/revoked/opted_out) approved at the DDL gate (D10). Migrations 0080-0082, coach prompt v18 (rollback `COACHING_SYSTEM_PROMPT_VERSION=v17`), gate 2515 tests 0 failures. The spec's "share CTA on the report surface" was resolved as written: S5 provided the CTA and token, S6 added the trigger and the tracking; no parent-page CTA was built (RFC 155 D-G forbids upgrade cues to a logged-out parent). **Next for this brief is Beat 2** — parent partner accounts, claim-the-report onboarding — which brief 0001 D9 says is specced only after Beat 1 ships. It now has. `share_events` is the substrate it reads.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `product/0001-v1-differentiator/spec.md`     |
| P1  | Explicit profile view (brief 0007)    | **COMPLETE — gates 1+2 APPROVED (2026-09-04/05, defaults; D5 and D9 amended by Ian); ALL THREE SLICES LANDED.** `profile/01/served-vocabulary` **LANDED as RFC 165** (`main@f837dc81` + `dfc3b539`, 2026-09-05) — `GET /api/v1/vocabularies`, a registry endpoint Ian widened from a money-profile-only route at the /ship gate; income bands and the 59 residency codes are served with spoken labels, the residency set being literally the set the write path validates. `profile/02/your-details-screen` **LANDED as RFC 171** (`main@34471e2f` + `2184cb67`, 2026-09-05) — **the door**: a slide-over menu row "Your details" pushes a screen where a family sets its household income band and state or territory of residence without talking to the coach, each field legible as not answered / the value / "you chose not to say", each saving on its own with a VoiceOver-announced "Saved" receipt and nothing said in chat, "Prefer not to say" naming its cost and "Remove my answer" re-arming the coach's invitation, and a declined field keeping a live picker so no decline is one-way. Both pickers render RFC 165, so D6 holds — RFC 163's Swift lists are now only the offline fallback, pinned to the server by tests. Client-only: no server change, no migration, no new table (G4 held), and no rollback knob. Gate: iOS 625 tests, JVM 2865 tests, 0 failures; 594 shell assertions. `profile/03/list-screen-parity` **LANDED as RFC 168** (`main@a3d851e7` + `9f7e7809`, 2026-09-05) — the iOS PATCH body is an explicit three-state `LivingPlanUpdate`, so a Save from the college-list screen cannot destroy a chat-set living plan and the app can send an explicit clear; `reasons` keeps clear-by-omission. **No UI change** (D3 defers the picker). D9's REST fix landed separately as RFC 164. **Nothing in this brief is startable.** What it leaves for /chart, unfixed and named: the app now has TWO residency menus (this screen's 59 served jurisdictions vs onboarding's deliberate 51-item RFC 163 menu), `dependency` is still chat-only on neither REST verb (D10's drift), `VocabulariesResponse.version` is decoded but unused, and `profile/03`'s audit produced backlog lines **B1-B9** in the brief ledger — reported, not fixed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `product/0007-explicit-profile-view/spec.md` |
| P3  | `bin/state-apply` (RFC 138)           | **Landed** (v1: users world file, create-only). Per-entity replace/reset waits on brief 0002's delete engine — see Backlog.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | `bin/state-apply`                            |

## Sequencing — ask the board, do not read a list

**What can I kick off right now?** One command answers it, from any checkout:

    .prime/agent/skills/slice/scripts/slice-board

It prints every slice as READY / IN FLIGHT / BLOCKED / DEFERRED / LANDED,
computed from the `Needs:` lines in each `spec.md`, the LANDED rows in the brief
ledgers, and live runs from `ship-status`. Blocked slices name the unmet target
**and its reason**. `-p` is porcelain; `-q` prints doc defects only. A non-zero
exit means a doc defect — a `Needs:` entry with no reason, an unknown target, a
slice with no `Needs:` line — and the fix is the doc, not the board.

**This file no longer lists what is startable, on purpose.** It used to, in
three places, and all three drifted — including once within an hour of being
written. Readiness is a fact about the repo at this second, so it is computed,
never remembered. What stays here is what only a person can write: the bet, the
user manual, priorities, and the backlog.

**Brief 0006 leaves the board entirely (2026-09-09).**
`shape/08/drop-the-publisher-shape` LANDED as RFC 176 and was the last startable
slice in the brief. `shape/09/exchange-participation` is DEFERRED by intent, not
blocked, so nothing in brief 0006 is board material. It blocked nothing outside
itself, so its exit unblocks nothing.

Worth keeping, because it is the lesson and not the status: this file said for
four days that **`shape/08` wants a /chart re-spec pass before it is
dispatched** — its text predated RFC 169, the search-index copy it named had
grown four columns wider, and the audit-to-zero-readers list was no longer the
list the slice named. The re-spec never happened, and the slice ran anyway. That
was the right call for a reason worth writing down: the drift was **in the
slice's premise, not in its intent**. A current-tree audit run at design time
found reader count was not zero, the run cut those readers first and proved the
suite green on the new source before dropping anything, and the RFC recorded the
corrected premise. A slice whose _what_ is still right and whose _facts_ have
moved wants an audit inside /ship, not a trip back to /chart — a re-spec would
have re-derived the same list from the same tree and delayed the work by a day.
Keep the /chart pass for slices whose INTENT is in doubt.

**Brief 0008 is half landed (2026-09-09).** `soft/02/assurance-tiers` LANDED as
RFC 179 and leaves the board, as `soft/01/one-hedge-seam` (RFC 177) did the day
before. Both wave-1 slices are gone, and **`soft/02` blocked nothing**, so its
exit unblocks nothing. What remains is `soft/03/the-year-we-cite` and
`soft/04/two-more-ingest-refusals`, both **READY and unblocked** — no `BLOCKS`
edge on either is unmet, and the gate-2 scheduling hold on `soft/03` (wait for
`shape/07b/borrowing`, to avoid a three-way rebase on `CanonicalMoneyLoader.kt`)
is spent: `shape/07b` landed as RFC 175. They have no edge between them, so they
can run in parallel in separate worktrees.

One rebase note, not an order: **`soft/03` CONFLICTS with `soft/02` on
`CanonicalMoneyLoader.kt`**. RFC 179 edits that file — it is where the Scorecard
`source_variable` strings are written — so `soft/03` will rebase over those
lines. `CONFLICTS` is a file-overlap warning, never a sequence, and `soft/02`
has landed, so there is nothing live to collide with.

**Brief 0007 leaves the board entirely (2026-09-05).**
`profile/02/your-details-screen` LANDED as RFC 171, so all three of its slices
are LANDED and **nothing in brief 0007 is startable**. It blocked nothing else,
so its exit unblocks nothing. What it leaves behind is /chart material, not
board material: two residency menus in one app, `dependency` on no REST verb, an
unused `version` field, and backlog lines B1-B9 in the brief ledger.

**A board defect found and FIXED (2026-09-05, after RFC 170).** For a while the
board printed `shape/08/drop-the-publisher-shape` as READY when it is not. The
cause was not the ledgers: brief 0006's `Needs:` lines were written INLINE with
two or three edges in one paragraph, and the board's inline parser takes the
FIRST edge and swallows the rest into that edge's reason text. `shape/08`'s
second edge — BLOCKS `shape/05/search-on-your-price` — was therefore invisible,
so a slice that must not run until `shape/05` lands looked startable. Acting on
it would have dropped the publisher-shaped money columns while `shape/05` still
reads them.

All six affected `Needs:` blocks in this brief are now bullet lists, which the
parser reads correctly, and `shape/08` shows BLOCKED again with its real unmet
edge. **Write multi-edge `Needs:` as bullets, one edge per bullet.** The parser
should refuse an inline block that holds a second edge rather than silently
dropping it — that hardening is an open item, and until it lands the doc form is
the guarantee.

**The rules the board applies**, for reading its output:

- **BLOCKS** — technical, cannot proceed, not overridable.
- **PREFER** — product judgement. Never blocks; it prints beside a READY slice
  so an override is visible instead of hidden.
- **CONFLICTS** — both slices edit the same files. A rebase risk, **not** an
  order. A conflicting live run prints a warning and the slice stays READY.
- **`Status: DEFERRED`** — parked by intent. Never READY. "We chose not to yet"
  is not a dependency and must not be written as one.
- **Adjacency in an ID grants nothing.** `03` after `02` is a plan. Only the
  `Needs:` line is permission.
- A run counts as IN FLIGHT only when its ship state carries `SLICE=<id>`. An
  unstamped run is listed as unattributed and **never guessed at** — that guess
  is what once recorded `pipeline/rfc-147` as `search/03/the-index` when it was
  `search/03a/published-codebooks`.

Slices in the same state with no edge between them are safe to run in parallel,
in separate worktrees. Live runs and their phases: `ship-status`.

## Backlog

Valuable, unscheduled, unprioritised — the parking lot. Append freely (one
bullet, enough context to pick it up cold); /chart promotes an item into the
work table by giving it a priority, or into a brief when it deserves gates.
Nothing here is committed work.

**From RFC 175 (`shape/07b/borrowing`, 2026-09-08):**

- **Source softness already has a brief — feed RFC 175 into it, do not re-open
  it.** Brief 0008 (`how-soft-is-this-number`, gates 1+2 approved 2026-09-05)
  asks exactly the question Ian raised again at RFC 175's gate, and its wave 1
  is startable. RFC 175 D10 is a CONSUMER of `soft/01/one-hedge-seam`: it closed
  the gap in the VOICE by hand — every borrowing sentence attributes the figure
  to the school — which is precisely the hand-written hedge `soft/01` exists to
  derive. When `soft/01` lands, the borrowing copy should move onto that seam
  rather than keeping its own wording.
- **Brief 0008 `soft/01` has drifted against what just landed — a /chart
  decision, not a /ship one.** Its spec claims coach prompt **v21** with
  rollback to v20; RFC 175 took v21 on 2026-09-08, so `soft/01` is now v22 over
  v21. Its spec also names **four** hard-coded "Common Data Set" constants to
  replace; RFC 175 added a further set of borrowing silence and attribution
  constants (`BORROWING_*`, five availability basis codes) that belong in the
  same sweep. The slice's intent is unchanged; its inventory and its version
  numbers are stale.
- **A read seam for BOTH CDS readers.** `AidPolicyDao` and `BorrowingDao` are
  both called directly from `CollegeCostService`, ten lines apart, while the
  canonical cost read next to them is injected (RFC 166). A review finding asked
  for a seam on the new one; it was declined because a seam for one of two
  adjacent readers is worse than none. Either both, or the ~35 DAO objects as a
  convention.
- **`h5_is_contradictory` halts at the first contradiction**, so the fetch drop
  ledger names one offending cell of up to five. The data outcome is right (the
  whole borrowing block drops); only the operator's report is partial.
- **`other_institutional_form_required` sits at 26 against a coverage floor of
  25.** Corpus drift, not a code change. The floor was deliberately left alone —
  a future fetch fatalling there is what the floor is for — but it will fatal.
- **`db/seed/cds/PROVENANCE.json` carries a stale drop-reason sample string**
  from before RFC 175's tier-3 rename. Nothing reads it; refreshing costs a live
  fetch of 249 documents.

**Three small follow-ups from RFC 162 (`shape/03/ipeds-sfa`, 2026-09-05):**

- **RFC 161's prose still says `Z` is an imputation.** The code now reads it as
  a reported zero for both IPEDS surveys, and RFC 162 records the correction —
  per `rfc/README.md` the code wins, so this is documentation drift, not a bug.
  A future 0006 slice touching flags should not re-derive the old mapping.
- **`cohort_money_stats_college_idx` (RFC 158) has no reader**, exactly like the
  index RFC 162 dropped on its twin table: the DAO does delete-all, batch insert
  and a full aggregate, and the natural key already leads with `college_id`.
  Worth dropping when a migration next touches that table.
- **`db/seed/ipeds/PROVENANCE.json` is a composite of two real fetches** 19
  minutes apart (`FETCH-NOTES.md` says so). One operator re-fetch (~13 MB) would
  make it a single run. Also, the SFA `prefer_revised` policy is proven only on
  a synthesised archive, because the real SFA2223 zip ships no `_rv` member yet
  — the first year it does is the first live exercise.

**Two open follow-ups from RFC 157 (2026-09-02), at the top because they are the
newest and the first is a product decision, not a bug:**

- **ABSORBED INTO BRIEF 0006 (2026-09-02): the "search ranks every family on the
  in-state net price" follow-up is now `shape/05/search-on-your-price`.** RFC
  157 corrected labels and withholding on cost surfaces; ranking waits on the
  canonical money layer. The product decision it needed was made at 0006's gate
  2 (D14): known non-matching residency ranks on the family's residency-correct
  published price; unknown residency ranks on the net-price stat with the basis
  in the metric name, never silently; one ruler per query; a search-side
  residency offer. Do not schedule this from the Backlog — the slice board owns
  it.

- **`first-value/06/invite-your-parent` has spec drift, recorded when RFC 155
  landed.** Four things the spec assumes are no longer true or were never built:
  the share CTA is a **chat affordance**, not something on the parent page (an
  iOS share sheet was deliberately deferred, RFC 155 **D-I**); **"share events
  tracked" has no storage yet** — `cost_report_shares` holds only id,
  created_at, student_id, token_hash, revoked_at — so tracking is new DDL and
  hits Ian's DDL gate (0001 **D10**); the derived token **cannot be reversed to
  a student**, which constrains Beat 2's parent-account claim path; and **one
  live share per student** means there is no per-recipient attribution. Spec
  edits belong to /chart, before anyone writes the RFC.

- **The money profile's `(value, status)` pairs are not a type** — "answered
  with no value" is representable in `:db`'s models and caught only by the
  database CHECK. A sealed `StoredAnswer<T>` would make it unrepresentable.
  Flagged by review on RFC 152 and declined there as a repo-wide refactor at the
  end of a run; it touches income band and residency, not just the living plan.
- **The tri-state parse ladder now exists in three copies across two surfaces**
  (income band, residency, living plan; REST routes and the chat tools). Four
  RFC 152 review lenses flagged it independently. The extraction belongs on
  `StudentScopedChatTool`, which already declares itself the home for tool input
  scaffolding — but it rewrites code no single slice owns, so it wants its own
  slice.
- **The money-profile wire echo is maintained by hand in two files**
  (`MoneyProfileChatTool` and `CollegeCostChatTool`); RFC 152 added its third
  field to both by hand. One emitter, one test.
- **The living-plan invitation is not suppressed by a per-college override.** A
  school-level answer never closes the global question, so a student who has
  overridden one school still sees the invitation on that school's result.
  Deliberate in RFC 152 and documented in the enum, but it is worth a product
  decision about whether the coach should ask once and stop.

- **The `NewCollege` test fixture is duplicated five times, and the shared
  helper is in the wrong source set** — parked TWICE now: RFC 154 declined it,
  RFC 153 declined it again after adding the fifth copy. The 33-field builder
  exists as a shared helper, but it sits in `:db`'s **test** source set rather
  than `testFixtures`, so `:college` cannot see it and every college-module test
  base writes its own. The fix is to move it to `testFixtures` and delete the
  copies; that touches ~9 files across modules, which makes it **its own slice**
  rather than a fix smuggled into the next feature. It should be scheduled — a
  thing declined twice on the grounds that the next slice will pay for it is a
  thing nobody is paying for.
- **Genericise `CollegeSearchOutcome<out P>` to absorb
  `CollegeSimilarityOutcome`** — declined in RFC 153. It would dedup three
  near-identical result arms, but it ripples into the sibling search feature's
  call sites, so the change is wider than the duplication it removes. Worth
  doing beside the fixture slice, not on its own.
- **`is_active` is not tri-state** — deferred by Ian at RFC 150's gate. The
  index column is `NOT NULL` and coalesces a missing IPEDS row to TRUE, so it
  asserts "open" about a college the ingest knows nothing about; on a
  Scorecard-only ingest it reads TRUE for every row and carries no information.
  The untaken fix: `is_operating BOOLEAN NULL` (TRUE when IPEDS reports active,
  FALSE when it reports otherwise, NULL with no IPEDS row) with the default
  universe reading `IS NOT FALSE` — behaviour-preserving for every real query,
  and it would let a result say "closed in 2019". See RFC 150 `## Deferred`. Not
  a defect S3b introduced: it is brief 0004 D18 carried forward.
- **Account deletion (brief 0002)** — moved out of the work table (Ian,
  2026-08-27). Brief is FRAMED with six decisions D1–D6 awaiting gate 1 at
  `product/0002-account-data-deletion`. Still App-Store-blocking when we submit:
  5.1.1(v) requires in-app account deletion, and GDPR Art. 17 / CCPA apply
  regardless — so this is deferred, not resolved, and it gates public launch.
  Also blocks `bin/state-apply`'s per-entity reset (RFC 138 defers to its
  engine) and repeatable clean-slate testing.
- **IPEDS IC as a cost-data upgrade path (brief 0003 D7) — RESOLVED by brief
  0006 D5 (2026-09-02):** un-deferred as `shape/02/ipeds-ic-ay` (plus
  `shape/03/ipeds-sfa`). The Common Data Set licence question stays open and is
  carried by `shape/07/need-and-forms`'s acceptance criteria.

- **Beat 2: parent partner accounts** — claim-the-report onboarding, linked
  family, parent-side coaching. Deliberately unspecced until Beat 1 ships and
  share-rate is observed (0001 D9).
- **`bin/state-apply` growth** — per-entity replacement/reset once brief 0002's
  delete engine exists (RFC 138 explicitly defers to it); more resource types
  (students, college lists, money profiles) for one-command test worlds.
- **PROMOTED TO BRIEF 0007 (2026-09-04): "iOS: a dedicated view for everything
  the chat can do" is now `product/0007-explicit-profile-view`, gate 1
  APPROVED.** Ian's idea — anything doable in chat should be doable explicitly
  from a screen — scoped by him to **income band, state of residence and the
  college list**. Gate 1 approved all 12 defaults with two amendments: **a
  decline must be undoable from the screen** (D5), and **the REST college-list
  `PATCH` contract is itself a bug** (D9). Three slices specced
  (`profile/01/served-vocabulary`, `profile/02/your-details-screen`,
  `profile/03/list-screen-parity`); ask `slice-board`, not this line, for what
  is startable. **Do not schedule this from the Backlog — the slice board owns
  it.** Two findings worth knowing outside the brief: a **shipped data-loss
  bug** (an iOS college-list Save silently NULLs a chat-set per-college living
  plan; proven by an executed test, fix dispatched to /ship under D9), and the
  standing **parity drift** — `dependency` (RFC 159) is writable in chat and
  absent from the REST wire, and living plan is on the wire with no screen.
  **Provenance labelling** ("you set this" vs "I learned this from your
  conversation") was ruled out of scope at D11 and stays here as a cheap
  differentiator: no consumer product we could find does it.

- **Ingest observability** — dropped from the work table (Ian, 2026-08-27), kept
  here as a known trap, not committed work: `bin/ingest-colleges` runs a
  PREBUILT launcher and checks only that it is executable, so a stale jar loads
  every row, logs `colleges=N`, and leaves new columns NULL — indistinguishable
  from a real load. It has cost an hour twice (dev S1, prod deploy). Verify a
  load by hand instead: `SELECT count(<new_col>), max(version) FROM colleges`.
  The same shape likely exists in other loaders.

## Kickoff prompts

**The short form now works:** open a new Prime Agent session in
`/Users/ian/Work/unicoach` and say _"start work on `money/02/component-split`"_,
naming the **`slice`** skill. It resolves the ID in `spec.md`, checks the
`Needs:` edges (BLOCKS refuses, PREFER asks you once, CONFLICTS only warns),
claims the RFC and migration numbers live, runs /ship, and then updates the
brief ledger and this file. Numbers are never copied from a doc — they move
under you.

The long prompts below remain for context a spec cannot carry. Each /ship run
claims its own worktree, so parallel runs are safe **within a wave**. YOU are
the approval gate in each session.

### How soft is this number? (brief 0008) — two of four landed

Gates 1+2 approved 2026-09-05. `soft/01/one-hedge-seam` (RFC 177) and
`soft/02/assurance-tiers` (RFC 179) have LANDED — do not start either again.
Paste one of these:

    start work on soft/03/the-year-we-cite

    start work on soft/04/two-more-ingest-refusals

Both are READY and neither blocks the other. The hand-held wait on `soft/03` is
spent: `shape/07b/borrowing` landed as RFC 175. `soft/03` CONFLICTS with the
landed `soft/02` on `CanonicalMoneyLoader.kt` — that is file overlap and a
rebase, never an order.

### College search index (brief 0004) — nothing to start

**Brief 0004's core is COMPLETE.** Every specced slice has landed: `search/01`
(RFC 139, matching replaced by RFC 146), `search/02` (RFC 144), `search/03a`
(RFC 147), `search/03b` (RFC 150), `search/05` (RFC 154) and
`search/04/similar-
colleges` (RFC 153, 2026-09-01). The old paste-ready prompt
for `search/04` was removed on the day it landed, so nobody starts a finished
slice.

The only slice left is **`search/06/unattended-refresh`**, and it is **DEFERRED
by intent**, not blocked: a `periodic_jobs` quarterly cron enqueueing the
ingest, seeded `enabled = FALSE`, worth building only if running the ingest by
hand quarterly proves annoying. If you want it, un-defer it in
`product/0004-college-search-index/spec.md` first — the board will never call a
DEFERRED slice READY.

What the brief left behind, if you are picking up the debt: the two Backlog
items above (the fifth `NewCollege` fixture copy with the shared helper stranded
in `:db`'s test source set, and genericising `CollegeSearchOutcome`). Both are
real, both were declined with reasons, and the first has now been parked twice.

### Explicit profile view (brief 0007) — COMPLETE, nothing to start

**All three slices have landed**, `profile/02/your-details-screen` last, as RFC
171 (2026-09-05). There is no kickoff prompt, because there is nothing to kick
off. Do not start any of them again.

1. **`profile/01/served-vocabulary`** — **LANDED, RFC 165** (2026-09-05).
   `GET /api/v1/vocabularies` serves the income bands and the 59 residency codes
   with spoken labels, as a registry rather than a money-profile route.
2. **`profile/02/your-details-screen`** — **LANDED, RFC 171** (2026-09-05). The
   "Your details" menu row opens a screen where a family sets its income band
   and its state or territory of residence, declines a field with its cost
   named, or takes the decline back. Both pickers render RFC 165, and RFC 163's
   Swift lists are now only the offline fallback, so D6 holds.
3. **`profile/03/list-screen-parity`** — **LANDED, RFC 168** (2026-09-05). The
   iOS list screen's writes are non-destructive by statement, not by accident,
   and a clear is expressible. No UI change. It had no dependents, so it
   unblocked nothing.

What the brief leaves for the next /chart conversation, none of it a slice
anyone can pick up: the app now shows **two different residency menus** (the
screen serves the 59 jurisdictions the server accepts; onboarding keeps RFC
163's deliberate 51-item menu), **`dependency` is still chat-only** because it
is on neither REST verb, RFC 165's **`version`** is decoded and ignored — a
content hash with no cache headers behind it, where vocabulary caching would
start — and `profile/03`'s audit left backlog lines **B1-B9** in the brief
ledger.

Gate-1 decisions this brief settled, for whoever works near this code next: a
screen a user opened is NOT the coach re-asking; a decline must be **undoable in
both directions**; clear and decline must **look different**; a 404 means
"nothing answered yet"; the coach **never** mentions a screen edit in chat; the
screen is never a gate; **no new tables anywhere in this brief** — and none was
added.

### Account deletion (brief 0002) — parked in Backlog, kept ready

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md) with
/chart: account data deletion. The brief is FRAMED and awaiting gate 1 — bring
me its six decisions (D1–D6) with defaults before any code. Legally required
(GDPR Art. 17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us
repeatable clean-slate testing. The schema actively refuses deletion today — all
mapped in the brief. I approve every new table personally, with visible DDL at
the gate. Note RFC 138 (bin/state-apply) deliberately deferred its delete/reset
semantics to this brief's engine.

### Clear money language (brief 0003) — nothing to start

Every slice in this brief has landed: `money/01` (RFC 141), `01.1` (142), RFC
143, `01.2` (145), `02` (149), `03` (151) and `04/where-youll-live` (152). There
is no kickoff prompt because there is nothing to kick off.

What the brief leaves behind, for whoever works near this code next — all of it
true in code and none of it allowed to regress: an arrangement missing a part
carries no total; missing data is a labelled blank, never a zero; unanswered
residency withholds the tuition line and every total rather than guessing;
`net_price − tuition` is forbidden and a test scans the source for it; only
same-vintage figures may be summed; no bare source code reaches a tool result
(RFC 143's guard); a multi-school answer states its basis lines before the
numbers; and a resolved living plan LEADS an answer without removing the other
arrangements from it. Two vocabularies must stay apart: `ArrangementGap` says
what the **school** published, `NoTotalReason` says where **we** are silent.

Its declined items are in the Backlog above, not lost: `StoredAnswer<T>`, the
tri-state parse ladder's third copy, the two-file wire echo, and the question of
whether a per-college override should stop the coach asking for a usual plan.

### Beat 1 — COMPLETE, nothing to start

**All six slices of brief 0001 have landed**,
`first-value/06/invite-your-parent` last, as RFC 160 (2026-09-03). Do not start
any of them again.

**Beat 2 is not specced yet, and that is on purpose** — brief 0001 **D9** says
parent partner accounts are specced only after Beat 1 ships. It now has, so Beat
2 is the next chart conversation for this brief, not a slice anyone can pick up.
It reads `share_events` (RFC 160) and reuses the report token as the claim
credential.

PASTE, to open that conversation: run /skill:chart on brief 0001 Beat 2 — parent
partner accounts: claim-the-report onboarding, linked family, parent-side
coaching seeded by the report. Beat 1 shipped complete on 2026-09-03; read the
brief ledger and the RFC 155/160 entries in product/STATUS.md first, and note
that share_events already records minted/repeat/reissued/revoked/opted_out per
student and that the report token is derived (HMAC), never stored.
