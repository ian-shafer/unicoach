# Research: the money domain model (brief 0006)

**Question.** What is the right domain model of US college money for a
family-facing coach — the concepts unicoach should store, independent of any
source's shape?

## Method

- **websearch skill was unavailable** (no Serper API key configured in this
  session).
- Instead, primary sources were fetched directly over HTTP on 2026-09-02 and
  quoted from the returned HTML: the **2024–25 Federal Student Aid Handbook**
  (fsapartners.ed.gov — Vol 3 Ch 2 cost of attendance; AVG Ch 3 SAI; Vol 7 Ch 1
  Pell eligibility; Vol 8 Ch 4 loan limits), **wiche.edu** (WUE),
  **msep.mhec.org** (MSEP), **nebhe.org** (Tuition Break), **sreb.org**
  (Academic Common Market), **cssprofile.collegeboard.org**.
- `studentaid.gov` consumer pages returned a JavaScript shell (no
  server-rendered text), so the FSA Handbook is cited in their place; it is the
  more precise source anyway.
- Specific dollar parameters that the fetched pages did not carry (e.g. the
  current Pell maximum) are **flagged inline as unverified-this-session** rather
  than asserted.
- Repo claims cite `file:line` from greps of `/Users/ian/Work/unicoach` on
  2026-09-02.

---

## 1. Price structure

### 1.1 Cost of attendance (COA) and its components

Federal law (HEA §472, operationalized in the FSA Handbook Vol 3 Ch 2) defines
COA as "an estimate of that student's educational expenses for the period of
enrollment," built from named components
([FSA Handbook 2024–25 Vol 3 Ch 2](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/vol3/ch2-cost-attendance-budget)):

| Component                                                                     | Notes (from the Handbook chapter)                                                         |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Tuition and fees                                                              | "normally assessed for a student carrying the same academic workload"                     |
| Books, course materials, supplies, equipment                                  | may in some cases fold into tuition-and-fees                                              |
| **Living expenses (food and housing)**                                        | "formerly known as 'room and board'"; allowance varies by the student's housing situation |
| Transportation                                                                | school–residence–work travel; never vehicle purchase                                      |
| Miscellaneous personal expenses                                               | only for ≥ half-time students                                                             |
| Dependent care, disability expenses, licensure costs, loan fees, study-abroad | conditional components, per-student                                                       |

Domain consequences:

- **The federal renaming to "food and housing" confirms brief 0003's
  vocabulary** — "housing and food," never "room and board." The Handbook uses
  the new term explicitly.
- **COA is residency-specific by construction.** The Handbook says schools "can
  have different standard costs for different categories of students, such as
  one COA for out-of-state students, who are charged higher tuition, and a
  different COA for in-state students" (same chapter). So a single
  `cost_of_attendance_per_year_usd` column (the repo today:
  `db/schema/0059.name-numeric-columns-by-unit.sql:55`) is a blend that the
  federal model itself does not have. **A COA figure without a residency basis
  is not a fact; it is a summary of facts.**
- **COA is arrangement-specific.** The living-expenses allowance "is based on
  the student's situation": on-campus, off-campus, or living with family each
  get a different allowance (same chapter). The repo already learned this the
  hard way (arrangement components in
  `db/schema/0062.add-college-cost-components.sql:8-16`, which notes the
  with-family arrangement "renders no housing-and-food line rather than a $0
  one").
- The five family-visible price concepts unicoach needs are therefore: **tuition
  and fees**, **housing and food**, **books and supplies**, **other expenses**
  (transportation + personal, usually published merged), and the **published
  price** (their same-vintage, same-basis sum — brief 0003's term for COA).

### 1.2 Residency tiers, and who has which

Three tiers exist in the wild, and they are a property of the _charge_, not the
school:

- **Privates:** one price; residency is **not applicable** (not "unknown"). A
  model that can only say NULL cannot distinguish "private, single price" from
  "public, price not collected" — this must be a stored status.
- **Public four-years:** in-state and out-of-state tuition and fees.
- **Community colleges (and some publics):** **in-district / in-state /
  out-of-state** — three tiers. IPEDS IC collects all three separately (brief
  0006 already flags IPEDS IC as the deferred source that adds in-district;
  brief.md "Why now" #4).

So the residency dimension needs at least: `in_district`, `in_state`,
`out_of_state`, `not_applicable` (single-price school), plus a way to represent
a **publisher blend** (Scorecard COSTT4_A/NPT4 are in-state-based figures at
publics — the RFC 157 defect, brief.md "The question"). Recommendation: blends
are _cohort statistics_ (§5), never rows in the price table; then "a price row
names its residency basis or cannot be stored" (success criterion 1) is
structural.

### 1.3 Tuition reciprocity / exchange programs — modelable fact or per-student deal?

Four major regional programs, all fetched live 2026-09-02:

- **WUE (WICHE, 16 western states/territories):** students "pay no more than
  150% of the institution's resident tuition rate"
  ([wiche.edu/tuition-savings/wue](https://www.wiche.edu/tuition-savings/wue/)).
  The _rate cap_ and _participation_ are modelable; but institutions limit WUE
  to specific programs and cap award counts, and some treat it as competitive —
  so the family's _entitlement_ is not.
- **MSEP (MHEC, Midwest):** "a discounted tuition rate with an average annual
  tuition savings of $7,000... volunteer based with eight states and over 70
  institutions" ([msep.mhec.org](https://msep.mhec.org/)). Discount level varies
  by institution; participation is per-institution and voluntary.
- **NEBHE Tuition Break (New England RSP):** discount applies only "when they
  enroll at out-of-state public colleges... and pursue **approved programs**"
  ([nebhe.org/tuitionbreak](https://nebhe.org/tuitionbreak/)) — typically
  programs not offered by the home state's publics. Keyed by _program × home
  state_, not college.
- **SREB Academic Common Market (South):** an "in-state tuition program" for
  approved programs not available in the student's home state
  ([sreb.org/academic-common-market](https://sreb.org/academic-common-market)).

**Verdict:** _participation_ (college × program-family × year) is a storable
per-college fact and is genuinely decision-useful ("you're in Nevada; this
Arizona school is a WUE school"). The _resulting price for this family_ is
conditional on major, home state, capacity, and sometimes competition — it is a
computation over participation + profile, and at WUE schools only an upper bound
(≤150% of a stored in-state figure). **Never store an "exchange price" as if it
were a published price.**

### 1.4 Arrangements, per-year vs per-program, vintages

- **Arrangements:** `on_campus` / `off_campus` / `with_family` — matches both
  the federal living-allowance categories (Handbook Vol 3 Ch 2) and the repo's
  read-time invention (brief.md, "the embryo of the canonical model"). Some
  figures are arrangement-invariant (tuition and fees, books) — model as `any`,
  not by duplication.
- **Per-year vs per-program:** IPEDS distinguishes _academic-year reporters_
  from _program reporters_ (short certificate programs price the whole program).
  unicoach's four-year-college focus makes per-year the v1 unit, but the model
  should carry a `per_academic_year` unit tag rather than bake "per year" into
  every column name forever, so a program-priced trade school is representable
  later, not wrong.
- **Vintages:** every figure carries its **academic year** (e.g. 2023–24).
  Sources mix vintages within one file (Scorecard famously so). Brief 0003's
  rule — only same-vintage figures may be summed — becomes checkable only if
  vintage is per-figure, not per-college-row. This is the strongest single
  argument for a figure-per-row shape.

---

## 2. Aid taxonomy — and the school/family/computation split

### 2.1 The taxonomy

Two orthogonal axes:

- **Gift aid vs self-help.** Gift aid = grants and scholarships (not repaid).
  Self-help = loans and work-study. Brief 0003's rule "a loan is a loan; loans
  are never subtracted from a price" is exactly the gift/self-help line drawn in
  data.
- **Need-based vs merit (non-need).** Need-based keys off the FAFSA/SAI (or CSS
  Profile); merit keys off the student. CDS section H2/H2A separates them, which
  is why `college_merit_aid` exists (brief.md repo table).

Instruments, with the federal facts verified this session:

- **Pell Grant** (federal gift aid, need-based). Since 2024–25, eligibility
  flows from the SAI and from direct AGI-vs-poverty-guideline tests: e.g. max
  Pell if a dependent student's single parent has AGI ≤ 225% (non-single ≤ 175%)
  of the poverty guideline for family size and state
  ([FSA Handbook AVG Ch 3](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility)).
  Families encounter Pell as a line in the aid offer; coaches encounter it as
  "do we qualify, roughly how much." The max award is set per award year (widely
  $7,395 in recent years — **amount unverified this session**; studentaid.gov
  page was a JS shell).
- **FAFSA and the SAI.** The FAFSA Simplification Act replaced the EFC with the
  **Student Aid Index** from 2024–25. The SAI "may be negative (but not less
  than -1,500)"; non-tax-filers are assigned SAI = −1500 (AVG Ch 3, quoted). SAI
  is an eligibility index, not "what you will pay."
- **State grants:** keyed by _family's state_ (sometimes × school's
  state/sector), not by college. A per-college model cannot hold them honestly;
  a state-program entity can.
- **Institutional grants:** need-based ("meets N% of need") and merit (CDS H2A
  counts and average awards). These are per-college _cohort statistics and
  policies_.
- **CSS Profile:** College Board's institutional-methodology application;
  "unlocks access to more than $14 billion in nonfederal aid" and is required by
  a specific, listable set of colleges
  ([cssprofile.collegeboard.org](https://cssprofile.collegeboard.org/)).
  _Whether a college requires CSS Profile_ is a clean per-college
  boolean-with-vintage.
- **Federal Direct Loans.** Verified from
  [FSA Handbook Vol 8 Ch 4](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/vol8/ch4-annual-and-aggregate-loan-limits):
  a dependent first-year undergrad may borrow up to **$5,500** total sub+unsub,
  "no more than
  $3,500... subsidized"; an independent (same level) up to **$12,500**; grad
  students **$20,500**/yr unsubsidized (all quoted from the chapter). Well-known
  companions (unverified this session but stable): dependent annual
  $5,500/$6,500/$7,500 by year, aggregate $31,000 dependent, $57,500 independent
  undergrad. **PLUS loans** (parent, grad) borrow up to COA minus other aid,
  with a credit check. Caution: these are _federal policy parameters that change
  by statute_ — 2025 reconciliation legislation alters PLUS availability from
  July 2026 (unverified this session) — so they must be keyed by **award year**,
  never hard-coded.
- **Work-study:** federal self-help, campus-administered, need-based; per-family
  it is at most "may be offered," never a price input.

### 2.2 The three-way (really four-way) split

This is the load-bearing classification for the schema:

| Kind                                                     | Examples                                                                                                                                                                                                    | Where it lives                                                                                                                               |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| **Facts about a school** (per-college × vintage)         | published prices by residency×arrangement; cohort net-price averages by income band; Pell share; % of need met; merit counts/averages; CSS Profile required; exchange participation; debt/earnings outcomes | canonical college-money tables                                                                                                               |
| **Facts about a family** (profile)                       | residency state, income band, living plan (exists: `db/schema/0046.create-money-profiles.sql:34-55`, `0070`), and any future family size / dependency answers                                               | `money_profiles`, tri-state honesty kept                                                                                                     |
| **Facts about a year** (federal/state policy parameters) | Pell max/min, SAI formula tables, loan limits, poverty guidelines, interest rates                                                                                                                           | a small vintage-keyed parameter store — **a category the brief's table lacks today**; without it, loan/Pell talk hard-codes numbers in prose |
| **Computations** (never stored)                          | this family's estimated net price, SAI, Pell amount, loan gap, WUE entitlement                                                                                                                              | read-time, from the three fact stores                                                                                                        |

The single most common domain-modeling error in this space is storing a
computation (a family-specific answer) as a school fact. The Scorecard's NPT4 is
_already_ that error institutionalized: a cohort-average computation stored as
if it were a price. Model it as what it is — a **cohort statistic** with a
population basis — and the temptation disappears.

---

## 3. Outcome measures

- **Median debt at completion** (Scorecard; repo:
  `median_debt_at_completion_usd`, brief.md repo table) — a _cohort outcome_,
  cumulative over the whole program, in loan dollars. Relates to price as
  evidence ("what borrowing actually happened"), never arithmetically (different
  cohort, different years, per-program vs per-year).
- **Median earnings 10y after entry** (Scorecard) — cohort outcome; the
  denominator is _entrants_, not graduates. Pairs with debt for "is the debt
  serviceable."
- **Repayment/default measures** (Scorecard repayment rates, cohort default
  rate) — optional later; same shape.

Model all three as **cohort statistics** in the same entity as net-price
averages: each has a measure, a population basis, and a vintage. The rule from
brief 0003 generalizes: _outcome figures never enter a sum with price figures_.
Keeping them in a separate entity from `PriceFigure` makes that unrepresentable
too.

---

## 4. The family's questions → stored concepts

| Family question                                    | Answered from                                                                                      | Stored or computed?                                                                                                                           |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| "What's the sticker price for _us_?"               | PriceFigure rows at (their residency tier × their living plan × latest vintage)                    | stored; the profile picks the row                                                                                                             |
| "What would we actually pay?"                      | cohort net-price-by-income-band stat (their band) + honest framing                                 | the _cohort average_ is stored; **their price is a computation we refuse to fake** — the honest answer is a range/average with basis narrated |
| "Do we qualify for Pell?"                          | family AGI/size/state + poverty-guideline pathways + Pell parameters (year facts)                  | computation; **school-independent** — needs zero college facts                                                                                |
| "What loans would this mean?"                      | federal loan-limit parameters (year) + the gap after gift aid                                      | computation; loans presented beside, never subtracted from, price (0003)                                                                      |
| "Is this school generous with merit?"              | CDS H2A stats: % of non-need freshmen with merit, average merit award (`college_merit_aid` exists) | stored per-college × vintage                                                                                                                  |
| "Do they meet full need?"                          | CDS % of need met / meets-full-need policy                                                         | stored (aid-policy fact), modeled ahead of data                                                                                               |
| "Does living at home change it?"                   | with_family arrangement rows; honest blank when the school publishes none (0062 precedent)         | stored                                                                                                                                        |
| "We live in [state] — is there a discount?"        | residency-tier rows + exchange participation (WUE/MSEP/RSP/ACM) + state grant programs             | participation stored; entitlement computed/narrated                                                                                           |
| "Do we have to do the CSS Profile?"                | per-college application-requirement fact                                                           | stored, vintage-keyed                                                                                                                         |
| "How much debt do graduates have? Is it worth it?" | cohort outcome stats (debt, earnings)                                                              | stored; never mixed into price arithmetic                                                                                                     |

Note which questions need _no college data at all_ (Pell, loan limits): they are
answerable the moment the parameter store and profile exist — cheap, high-trust
wins.

---

## 5. Recommended concept inventory

Conceptual entities (no DDL), in dependency order:

1. **Vocabularies (unicoach-authored):** `residency_basis` (in_district,
   in_state, out_of_state, not_applicable), `arrangement` (on_campus,
   off_campus, with_family, any), `figure_status` — the missingness enum:
   **value / not_reported_by_institution / suppressed_by_publisher /
   not_collected_by_us / not_applicable** (success criterion 2 becomes data),
   `price_concept` (tuition_and_fees, housing_and_food, books_and_supplies,
   other_expenses, published_price), `income_band` (gate-1 decision whether it
   stays Scorecard-quintile-shaped; whatever is chosen, it becomes an authored
   vocabulary external bands map INTO).
2. **PriceFigure** — key: college × price_concept × residency_basis ×
   arrangement × academic_year; value: amount-or-status (+ source/provenance).
   The RFC 157 defect is unrepresentable: no basis, no row. `published_price`
   rows exist only when a same-vintage, same-basis sum exists (0003's
   partial-sum rule as a build-time check).
3. **CohortMoneyStat** — key: college × measure × population_basis ×
   (income_band?) × vintage. Measures: avg net price (overall and by band), Pell
   share, median debt at completion, median earnings 10y, % need met, merit
   share, avg merit award. The population/residency/aid basis is a stored
   attribute — RFC 151's `comparison_basis` moved from Kotlin into data
   (brief.md success direction). NPT4 and COSTT4_A land here, labeled as the
   in-state-based Title-IV-aided statistics they are — never in PriceFigure.
4. **AidPolicyFact** — key: college × policy × year: css_profile_required,
   meets_full_need, need_blind, offers_merit. Mostly CDS-sourced; **model in v1,
   fill as sources land** (modeled-ahead-of-data).
5. **ExchangeParticipation** — key: program (WUE/MSEP/NEBHE-RSP/SREB-ACM) ×
   college × year (+ optional program-scope note). Participation only; the price
   effect is narrated at read time (WUE: "no more than 150% of the in-state
   figure" — a derived ceiling, computed from a stored in-state PriceFigure, per
   schema convention that derived figures are computed at read time).
6. **PolicyParameterSet** — key: award_year × parameter (pell_max, pell_min,
   loan limits by level/dependency, sub caps, poverty-guideline linkage). Tiny,
   authored, hand-verified against FSA sources each cycle. New entity class;
   enables the two school-independent family questions.
7. **FamilyMoneyProfile** — exists (`0046`, `0070`); keep tri-state
   unset/declined/value; candidate v2 additions: family size, dependency status,
   single-parent flag (exactly the inputs the Pell poverty-guideline pathways
   need — AVG Ch 3).

**v1 vs modeled-ahead:** v1 = 1, 2, 3, 7 (everything current sources can
backfill). Modeled-ahead = 4, 6 (define now so the shape exists; fill from
CDS/authored data). Later = 5 (needs a scraped/curated source), per-program
pricing units, state grant programs as an entity.

**Things that can never be a stored per-college fact** (flag for gate 1):

- _This family's_ net price, SAI, Pell award, or loan amount — computations,
  always.
- A family's exchange-program entitlement or resulting price (conditional,
  capped, sometimes competitive — §1.3).
- State grant eligibility (a family×state-program fact, not a college fact).
- Any single "generosity score" — it can only be an at-read projection of cited
  cohort stats, or it launders a computation into a fact.
- A residency-blended price. Blends exist only as publisher cohort statistics
  with a named basis; the price table refuses them by construction.
