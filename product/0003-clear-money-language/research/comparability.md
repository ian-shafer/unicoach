# What makes a college cost comparison actually apples-to-apples

_Research report for product brief 0003 "clear money language" — unicoach._

## Method and honesty note

Every load-bearing claim below is cited inline to a primary source, fetched
directly over HTTP during this research pass: the College Scorecard technical
documentation PDF, the IPEDS-derived data it describes, 20 U.S.C. § 1087ll
(statutory cost of attendance), NCES Digest/Condition of Education tables, GAO's
financial-aid-offer audit, College Board's _Trends in College Pricing_, and two
university sites used as concrete existence proofs (residency, guaranteed
tuition).

Tooling limits, stated plainly: the agent's web-search API was unconfigured, so
this is not a literature sweep — it is a targeted read of the documents that
define the numbers unicoach already serves, plus sources reachable by direct
URL. `gao.gov` blocks automated fetches (HTTP 403); the GAO findings below were
read from the Internet Archive's snapshot of the report page. Two claims
commonly made in this space — that first-year merit awards are systematically
"front-loaded" and shrink later, and that net price calculators are unreliable —
I could **not** substantiate from a primary source in this pass and have flagged
as unverified rather than asserted.

## The core problem: two "costs" that look alike are usually different measurements

Almost every apples-to-oranges failure in college cost comparison is the same
failure: a dollar figure is a **statistic about a specific population, in a
specific year, under a specific living arrangement**, and the label ("cost",
"net price") hides all three.

Concretely, for the fields unicoach loads
([College Scorecard data documentation, June 2024](https://collegescorecard.ed.gov/assets/InstitutionDataDocumentation.pdf)):

- `COSTT4_A` (sticker cost of attendance) is "the average annual cost of
  attendance … for all full-time, first-time, degree-/certificate-seeking
  undergraduates **who receive Title IV aid**", and crucially, "expenses by
  living arrangement (on-campus, off-campus independent, or off-campus with
  family) are **combined via a weighted average** according to the distribution
  of full-time, first-time students utilizing those options at the institution."
- `NPT4_PUB`/`NPT4_PRIV` (net price) is cost of attendance minus
  **grant/scholarship aid only**, for the same full-time / first-time / Title
  IV-receiving population — and at public institutions "this metric is **limited
  to those undergraduates who pay in-state tuition**."
- Income-band net price (`NPT41..45`) splits that same population into bands
  ($0–30k, $30,001–48k, $48,001–75k, $75,001–110k, $110k+), where income is in
  **nominal dollars, not inflation-adjusted**.

So a single school's "cost" is already an average over living arrangements, and
its "net price" is already restricted to first-year, full-time, aided, in-state
students. Comparing two of those is fair only if you say what they are.

## Pitfalls, each with the rule unicoach should adopt

1. **In-state vs out-of-state.** Published out-of-state tuition averages $30,780
   vs $11,610 in-state at public four-years in 2024-25
   ([Trends in College Pricing 2024, Table CP-1](https://research.collegeboard.org/media/pdf/Trends-in-College-Pricing-and-Student-Aid-2024-ADA.pdf))
   — a bigger gap than most public-vs-private comparisons. Worse, Scorecard
   public net price only describes in-state payers. **Rule: never show a public
   school's net price to an out-of-state family without saying it describes
   in-state students.**
2. **Residency is a legal test, not a fact about where you live.** UC requires
   366 days of physical presence plus intent, and moving primarily to attend may
   disqualify you
   ([UC residency requirements](https://www.ucop.edu/residency/residency-requirements.html)).
   **Rule: treat residency as an input the family declares, never inferred from
   a mailing address, and never promise reclassification.**
3. **Living arrangement.** Statute requires distinct allowances for on-campus
   housing, off-campus rent, and a dependent student living at home — the last
   of which "**shall not be zero**"
   ([20 U.S.C. § 1087ll(a)(5)](https://www.law.cornell.edu/uscode/text/20/1087ll)).
   Scorecard's headline figure blends all three. **Rule: hold living arrangement
   constant across schools and name it; if using the blended figure, say it is a
   blend.**
4. **First-time, full-time, Title IV only.** The Department itself warns that
   metrics based on aided students "may result in somewhat biased estimates" and
   "may not serve as a comprehensive indicator for how well institutions serve
   all the students they enroll" (Scorecard documentation, Appendix A). **Rule:
   label net price as "what first-year, full-time students who got federal aid
   actually paid", not "what you will pay".**
5. **Band average vs your award.** A band is a five-way bucket of an entire
   freshman class; two families at $60k with different assets, siblings in
   college, or merit profiles land far apart. **Rule: always frame a band figure
   as a starting range and route to the school's net price calculator for a
   family-specific number.**
6. **Year vintage.** Cost data cover the academic year prior to the IPEDS
   collection; net price and cost come from different IPEDS components with
   different reference periods (Scorecard documentation, Costs section).
   Published tuition rose 2.7–3.9% year-over-year in 2024-25 alone (Trends
   CP-1). **Rule: print the source year on every dollar figure, and never
   compare a figure from one vintage with one from another.**
7. **Per-year vs total.** Only 49.1% of first-time full-time bachelor's students
   at four-year institutions (2016 entry cohort) finished in four years — 45.3%
   at publics
   ([NCES Digest Table 326.10](https://nces.ed.gov/programs/digest/d23/tables/dt23_326.10.asp));
   64% finish within six
   ([NCES Condition of Education](https://nces.ed.gov/programs/coe/indicator/ctr/undergrad-retention-graduation)).
   **Rule: quote per-year cost as the primary unit; if showing a four-year
   total, state the four-year assumption and the school's own four-year
   completion rate alongside it.**
8. **A fifth year is not just one more year of the same price.** Illinois's
   guaranteed-tuition law holds a cohort's rate for four years; after that the
   student moves to a later cohort's schedule
   ([UIUC Registrar](https://registrar.illinois.edu/tuition-fees/tuition-fee-rates/)).
   **Rule: if you model a fifth year, model it at a later-cohort rate, not the
   freshman rate.**
9. **Front-loaded / non-renewable awards.** _Unverified in this pass._ The
   mechanism is real in principle — Scorecard cost and net price describe
   **first-time** students only, so year one is literally what is measured — but
   I found no primary quantification of systematic front-loading. **Rule: ask
   the school whether each award is renewable and on what GPA/credit condition;
   state that unicoach's figures describe year one.**
10. **Loans counted as "aid".** GAO reviewed a nationally representative sample
    of 176 colleges and found an estimated **91% do not include or understate
    net price** in their aid offers; 41% omit it entirely and 50% understate it,
    because "many colleges exclude key costs and factor in loans that must be
    repaid"
    ([GAO-23-104708](https://web.archive.org/web/20260819152455/https://www.gao.gov/products/gao-23-104708)).
    **Rule: unicoach subtracts grants and scholarships only. Loans and
    work-study are how you cover the net price, never a reduction of it — and
    say so when a family reads an offer letter.**
11. **Differential tuition by major.** Public universities widely price
    undergraduate programs differently as an alternative to across-the-board
    increases ([Stange, NBER WP 19183](https://www.nber.org/papers/w19183));
    Illinois's guarantee is even conditioned on "continuous enrollment in **the
    same major**". **Rule: state that engineering, business, and nursing rates
    may exceed the published average, and never present a single tuition number
    as major-independent.**
12. **Fees, insurance, and equipment.** Statutory COA includes "an allowance for
    books, course materials, supplies, and equipment … including a reasonable
    allowance for the documented rental or upfront purchase of a personal
    computer" (§ 1087ll(a)(2)); Scorecard's tuition fields cover "tuition and
    **required** fees" only. Student health insurance is typically a chargeable
    line that can be waived with proof of other coverage — a real four-figure
    swing. **Rule: put tuition + required fees in the stable column, and call
    out insurance and equipment as conditional line items to verify with the
    school.**
13. **High sticker, high discount.** In 2021-22, 87% of average grant aid at
    private nonprofit four-years came from the institutions themselves as
    discounts off their own published prices, vs 48% at publics (Trends
    CP-9/CP-10). Sticker rank and net rank routinely invert. **Rule: never rank
    schools by sticker price; rank by the best available net figure, and show
    sticker only as context labelled "before aid".**
14. **Outcome-side comparability.** `GRAD_DEBT_MDN` is median **federal** loan
    debt accumulated **at that institution** by completers — Parent PLUS is a
    separate series and private loans are absent; Scorecard warns the figure
    "can be placed in context by looking at the borrowing rate… where few
    students borrow, the numbers may represent outliers." Earnings are W-2 plus
    Schedule SE for **federally-aided** students, unadjusted for major mix or
    local wages (Scorecard documentation, Debt and Earnings sections). **Rule:
    pair median debt with the share who borrow, say it excludes parent and
    private loans, and never present earnings as an outcome caused by the
    school.**

## The proposed COMPARISON CONTRACT

Whenever unicoach shows two or more schools side by side, it states these five
assumptions once, above the table, in one sentence each — and any school that
violates one is excluded from the column rather than silently mixed in:

1. **Whose price.** "These are averages for first-year, full-time students who
   received federal aid — not a quote for your family."
2. **Residency held constant.** "Prices shown assume you are an out-of-state
   student at X and an in-state student at Y" — stated explicitly per school,
   never implied.
3. **Living arrangement held constant.** "Housing figures assume living on
   campus" (or the blended Scorecard basis, named as blended).
4. **One year, one vintage.** "All figures are for the 2023-24 academic year and
   will rise a few percent per year."
5. **Aid basis.** "Net price = total cost minus grants and scholarships. Loans
   are not subtracted."

And two presentation rules for uncertainty:

- **Split the table into a stable block and a variable block** — tuition &
  required fees (a price the school sets and publishes) above; housing & food,
  books, transport, personal (allowances the school _estimates_, and which the
  student's choices move) below, marked as estimates. This is exactly the split
  the brief wants, and it is also the honest one: the top block is a price, the
  bottom block is a forecast.
- **Missing data is a labelled blank, never a zero, never an interpolation.** "X
  does not report this" is a comparison-relevant fact; a silently-omitted row
  makes a school look cheap.

## What this means for brief 0003

1. **Load the four cost-split fields unicoach is already leaving on the table.**
   The Scorecard file ships `BOOKSUPPLY`, `ROOMBOARD_ON`, `ROOMBOARD_OFF`,
   `OTHEREXPENSE_ON`, `OTHEREXPENSE_OFF`, and `OTHEREXPENSE_FAM` (verified
   present in the raw header in
   `college/src/test/resources/scorecard-institutions-real-fixture.csv`), but
   `CollegeScorecardLoader.kt` ingests none of them (`grep -c ROOMBOARD` → 0).
   The stable/variable split the brief asks for does not need a new data vendor
   — it needs six more columns and a migration. This is the single
   highest-leverage finding here.
2. **Adopt exactly two money nouns and never vary them:** _tuition & required
   fees_ (stable, the school's published price) and _living & personal costs_
   (variable, the school's estimate, moved by choices). "Cost of attendance" is
   their sum; "net price" is that sum minus grants and scholarships only. Wire
   this vocabulary into the coach system prompt, the `college_cost_profile` tool
   output, and the planned S5 Family Cost Report identically.
3. **Make residency and living arrangement first-class comparison inputs,
   alongside income band.** `money_profiles` already carries residency state as
   a tri-state; the comparison surface should consume it and _print_ it ("shown
   as out-of-state"), because an unstated residency assumption is the single
   most likely way a side-by-side goes wrong.
4. **Ship the contract as visible copy, not a disclaimer.** Parents' comparison
   failure mode is trusting a number whose basis they can't see; students' is
   not knowing loans aren't aid. Five short assumption lines above the table
   serve both audiences and cost one screen of space.
5. **Add a per-figure vintage label and a "not reported" row rule** to the
   existing `data_availability` machinery, and keep the standing prohibition on
   estimating: where the Scorecard is silent, unicoach says so and points at the
   school's net price calculator.
