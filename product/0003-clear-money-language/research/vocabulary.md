# Money vocabulary that lands with parents and students

Research note for product brief 0003 ("clear money language"), unicoach.

## Method and honesty note

The installed `websearch` skill had no Serper key configured, so search ran
through the DuckDuckGo HTML endpoint (which rate-limited partway through) plus
direct fetches of primary documents. Everything load-bearing below was read in
full text from the source: the US Code, the GAO report PDF, the uAspire/New
America report PDF, the College Cost Transparency Initiative (CCTI) glossary and
standards PDFs, an FSA electronic announcement, an NCES statistical report PDF,
and live College Navigator / College Scorecard pages. Not readable: gao.gov
product pages (403), studentaid.gov glossary pages (JS-rendered, empty text),
the College Financing Plan template PDF itself (404 at the ed.gov path; its
contents are described second-hand via GAO and FSA guidance), and BigFuture
(404). No claim below rests on those. There is **no** published usability study
I could find that A/B-tests specific word choices with parents; the evidence is
about term _variance_ and _misunderstanding_, not about which of two synonyms
tests better. Treat glossary picks as evidence-informed judgement, not as
measured.

## 1. The canonical terms, and where they actually come from

**Cost of attendance (COA)** is statutory. 20 U.S.C. § 1087ll
(https://www.law.cornell.edu/uscode/text/20/1087ll) enumerates the components:
tuition and fees; an allowance for **books, course materials, supplies, and
equipment**; an allowance for **transportation**; an allowance for
**miscellaneous personal expenses**; and an allowance for **living expenses,
including food and housing costs**. Two things matter. First, the split brief
0003 wants is already in the statute's grain: item (1) is a price the school
sets, items (2)–(5) are _allowances_ that vary with the student's choices (on
campus, off campus, at home). Second, the FAFSA Simplification Act rewrote item
(5): the old "room and board" language is gone, replaced by "living expenses,
including food and housing costs," with explicit sub-allowances for on-campus,
off-campus, at-home, and military-housing students. That rename has already
propagated to consumer surfaces — NCES College Navigator now labels the line
**"Food and Housing"**, alongside "Tuition and fees", "Books and supplies", and
"Other expenses" (https://nces.ed.gov/collegenavigator/?id=166027#expenses).

**Direct vs indirect** has an authoritative plain-English rendering from the
College Cost Transparency Initiative (CCTI, the ~400-institution voluntary
standard convened by NASFAA and nine peer associations): "**Costs Payable to the
School**" (direct/billable: tuition, fees, housing and meals for on-campus
students, health insurance) and "**Costs Paid to Others**"
(indirect/non-billable: books, course materials, transportation, personal
expenses, computer, off-campus rent and food) —
https://www.aplu.org/wp-content/uploads/CCTI-Glossary-Final.pdf. This is the
best available parent-legible phrasing of exactly the stable/variable split
brief 0003 is after, and it is a _published standard_, not an invention.

**Net price** is also statutory but with a narrower meaning than people assume:
20 U.S.C. § 1015a(a)(3) (https://www.law.cornell.edu/uscode/text/20/1015a)
defines it as cost of attendance minus **grant aid only** (need-based and merit,
federal/state/institutional). Loans and work-study are never subtracted. CCTI
states it the same way. Note the Scorecard's consumer site does not use the
words "net price" at all in its headline metric: NPT4 is presented as **"Average
Annual Cost"**, defined as tuition, fees, books, supplies and average living
costs minus average grant/scholarship aid, for _federal-aid recipients only_
(https://collegescorecard.ed.gov/data/glossary/).

**SAI replaced EFC.** The Student Aid Index is the FAFSA Simplification Act's
replacement for the Expected Family Contribution as the need index (CCTI
glossary, above, carries both entries). The rename exists because "expected
family contribution" was read as "the bill you will get," which it never was.

## 2. What the evidence says families misunderstand

- **Term proliferation is measured and extreme.** uAspire/New America reviewed
  515 aid offers: of the 455 that offered an unsubsidized federal loan, there
  were **136 distinct names for that one loan**, and **24 of them did not
  contain the word "loan"** at all; only 18% used the official name
  (https://d1y8sb8igg2f8e.cloudfront.net/documents/Decoding_the_Cost_of_College_Final_6218.pdf).
- **The same report's Table 3 names the specific offenders.** "Board" —
  outdated, students don't know it means meals; replace with _meal plan_.
  "Out-of-pocket" — used inconsistently to mean indirect costs, or COA minus
  gift aid, or COA minus gift aid minus loans; ban. "Self-help" — inconsistent
  bundling of loans + work-study; ban. "Sticker price" — a second word for a
  concept that already has one (COA), and many families don't realise it isn't
  what they'd pay. "Unmet need" — depends on EFC, which families misread.
- **Colleges get net price wrong at scale.** GAO reviewed a nationally
  representative sample of 176 colleges' aid offers: an estimated **91% do not
  include or understate the net price** (41% omit it, 50% understate it by
  excluding key costs or by subtracting loans); 55% do not itemize key direct
  and indirect costs; 55% give no COA. GAO's best-practice list also says
  outright: **do not call the offer an "award"**
  (https://www.lrl.mn.gov/archive/minutes/senate/2025/highered/20250327/highered_20250327_SF2932-GAO-Financial-Aid-Offers.pdf,
  GAO-23-104708, Nov 2022).
- **Ed says the same in its own voice.** FSA's GENERAL-21-70 tells institutions
  to avoid "award" and "letter", to always include COA, and to "break out
  individual components ... and [be clear] what is a fixed cost and what is an
  estimated cost"
  (https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2021-10-28/issuing-financial-aid-offers-what-institutions-should-include-and-avoid).
  That sentence is brief 0003's thesis, written by the Department of Education
  in 2021.
- **Families' baseline numeracy on price is poor, and biased upward.** NCES
  (Horn, Chen & Chapman, _Getting Ready to Pay for College_, NCES 2003-030,
  https://nces.ed.gov/pubs2003/2003030.pdf) found 11th–12th graders and their
  parents planning on in-state public four-years estimated tuition at
  ~$5,400–$5,800 against an actual ~$3,247; only 25% of students and 31% of
  parents were accurate, and 37%/29% could not estimate at all. Old data, but it
  is the federal evidence that overestimation, not underestimation, is the
  default error — which is an argument for leading with net price, not sticker.

## 3. Recommended glossary for unicoach

Use in coach copy and UI. Left column = concept; middle = the exact phrase;
right = notes.

| Concept        | Say exactly this                                                                             | Notes                                                                                           |
| -------------- | -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| COA            | **total cost of attendance** (first mention: "the full yearly cost, including living costs") | Never abbreviate to COA in user-facing text.                                                    |
| Direct costs   | **costs you pay the school**                                                                 | Optionally "(tuition, fees, and on-campus housing and meals)". From CCTI.                       |
| Indirect costs | **costs you pay to others**                                                                  | "(books, travel home, phone, everyday spending)". From CCTI.                                    |
| Tuition + fees | **tuition and fees**                                                                         | Always say in-state / out-of-state where the number is residency-dependent.                     |
| Room and board | **housing and meals**                                                                        | Matches the amended statute and College Navigator ("Food and Housing"). Never "board".          |
| Books etc.     | **books and supplies**                                                                       |                                                                                                 |
| Transportation | **travel**                                                                                   | "getting to and from campus".                                                                   |
| Personal       | **everyday spending**                                                                        | Avoid "miscellaneous personal expenses" and "personal costs".                                   |
| Net price      | **net price — what a family actually pays after grants and scholarships**                    | Always gloss on first use in a thread; never subtract loans or work-study.                      |
| Scorecard NPT4 | **average net price** + the basis sentence                                                   | The existing `college_cost_profile` labelling rule is correct and should be preserved verbatim. |
| Gift aid       | **grants and scholarships (money you don't pay back)**                                       | Ban "gift aid" as a bare term.                                                                  |
| Loans          | **loans (money you pay back, with interest)**                                                | The word "loan" must appear every time — uAspire's 24-terms-without-"loan" finding.             |
| Work-study     | **work-study (a campus job you have to get and work)**                                       | Never counted as money off the price.                                                           |
| Need-based aid | **aid based on your family's finances**                                                      |                                                                                                 |
| Merit aid      | **aid based on your grades, scores, or talents**                                             |                                                                                                 |
| SAI            | **Student Aid Index — a number the FAFSA produces, not a bill**                              | Only mention if the user raises it.                                                             |
| Sticker        | **published price** (or just "the school's full cost before aid")                            | See ban list.                                                                                   |

**Ban list (never emit these):** "sticker price", "out-of-pocket", "self-help",
"unmet need", "EFC" (unqualified), "award" / "award letter" (say **financial aid
offer**), "board", "COA", "NPT4", "gift aid" bare, "affordable" as a verdict
about someone else's finances, and any unsourced estimate of a school's living
costs — the repo has no room-and-board column, so the honest move is "the
Scorecard doesn't report that separately for this school."

## 4. Parent vs student wording

The evidence does not support two vocabularies, and two vocabularies would
defeat the point: uAspire's core finding is that _variance itself_ is the harm,
and the offers are read by student and parent together at the kitchen table.
Keep one glossary. Vary only **framing and subject**, not terms:

- Student-facing: second person, per-year, decision-oriented — "at this school
  you'd pay …", "loans are money you pay back."
- Parent-facing: household framing and multi-year exposure — "your family would
  pay …", "over four years, before any increases." Parent copy may name the
  Parent PLUS loan; per FSA GENERAL-21-70 and CCTI it must be listed
  **separately from student loans and without a dollar amount**.

## What this means for brief 0003

1. **Adopt CCTI's two-bucket split as the product's spine**: _costs you pay the
   school_ vs _costs you pay to others_. It is a published standard, it is
   exactly Ian's stable/variable split, and it inherits FSA's "what is fixed vs
   what is estimated" guidance.
2. **Rename in the coach prompt (db/schema, next version) and in
   `college_cost_profile` output labels**: "housing and meals" not "room and
   board", "financial aid offer" not "award", "published price" not "sticker
   price", plus the ban list above. This is a copy change, not a schema change,
   and can land ahead of any new data.
3. **The data gap is now a vocabulary problem too.** The `colleges` table has no
   housing/meals, books, or transportation columns, so unicoach can name the
   buckets but can only fill one side of the split. Two honest options: (a) keep
   the split as _explanation_ and say plainly which components the Scorecard
   does not report per school (extend `data_availability`), or (b) load the
   IPEDS components that College Navigator already publishes per school and per
   living arrangement — Tuition and fees / Books and supplies / Food and Housing
   / Other expenses — which would let S5's Family Cost Report show the real
   split. (b) is the higher-value bet and should be scoped as a data slice.
4. **Keep leading with net price, and keep the basis sentence.** GAO's 91%
   finding and the NCES overestimation finding both argue that the
   differentiator is a correctly-computed, correctly- labelled net price.
   unicoach's existing rule (band-specific where available, else say plainly
   it's an overall average) is already better than most institutions manage — do
   not weaken it.
5. **Never subtract loans or work-study from a price, anywhere in the product**,
   and never let the coach describe work-study as reducing cost. This is the
   single most common institutional error GAO measured.
6. **One glossary for both audiences**; differ only in pronoun and time horizon.
   If S5 ships a shareable parent report, it should be the same words as the
   chat, so a parent reading the report and a student reading the thread are
   never learning two systems.
