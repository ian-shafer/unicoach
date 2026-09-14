# Evidence — what matters about a college choice beyond price

Research file for product brief 0011 "beyond money". Topic: the research
evidence on what actually matters in a college choice once price is set aside.

## Method

What I did:

- **Primary literature**: fetched abstracts and paper pages directly from NBER
  (`https://www.nber.org/papers/wNNNNN`), Opportunity Insights, Brookings/BPEA
  and Gallup over plain HTTPS. Abstracts are quoted/paraphrased with the paper
  URL. I could **not** read full PDFs of the NBER working papers
  (paywalled/gated), so every effect size below comes from an abstract, a
  published summary page, or my own computation — never from a body table I did
  not see. Where I state a number that is not in an abstract I say where it came
  from.
- **Own computation on federal data**: downloaded the two current College
  Scorecard bulk files from the ED download host listed on
  <https://collegescorecard.ed.gov/data/> —
  `Most-Recent-Cohorts-Field-of-Study_06102026.zip` (146 MB CSV, 227,980 rows,
  178 columns) and `Most-Recent-Cohorts-Institution_06102026.zip` (95 MB CSV,
  3,308 columns) — and computed the dispersion, coverage and gap statistics
  marked **[own calc]**. These are reproducible: the file names, the column
  names and the filters are stated inline.
- **Repo grounding**: read `db/schema/*.sql` in this checkout for what unicoach
  stores.

What was **UNAVAILABLE**:

- **`websearch` is not configured** in this session ("no Serper API key is
  configured"). So there was no keyword discovery step: I navigated to sources I
  already knew by name, plus the NBER working-paper search API
  (`/api/v1/working_page_listing/...`), which did work and did surface papers I
  had not named.
- **US News**: `usnews.com` refused my HTTP client (connection error on two
  methodology URLs). I therefore make **no** citation-backed claim about the
  current US News weights. The "rankings are noise" claim below rests on
  academic evidence about the _inputs_ (selectivity), not on a reading of the
  methodology.
- **Gallup-Purdue full report PDF**: the 2014 report landing page loaded; the
  report PDF itself I did not parse. Numbers below are from Gallup's own
  published article.
- **Bowen/Chingos "Crossing the Finish Line"**: I reached the publisher page but
  not the book text, so I do **not** quote its undermatch percentages. The
  undermatch evidence below is from NBER papers I could read.

---

## 1. Does WHERE you go matter for earnings, once ability is controlled?

**The headline finding is "mostly no — with sharp exceptions."**

Dale & Krueger, "Estimating the Return to College Selectivity over the Career
Using Administrative Earnings Data" (NBER w17159, 2011; published _J. Human
Resources_ 2014), <https://www.nber.org/papers/w17159>. College & Beyond survey
linked to SSA earnings, 1976 and 1989 entering cohorts. Abstract, verbatim in
substance:

> "the return to college selectivity is sizeable for both cohorts in regression
> models that control for variables commonly observed by researchers, such as
> student high school GPA and SAT scores. However, when we adjust for unobserved
> student ability by controlling for the average SAT score of the colleges that
> students **applied to**, our estimates … fall substantially and are
> **generally indistinguishable from zero**. There were notable exceptions … For
> black and Hispanic students and for students who come from **less-educated
> families**, the estimates … remain **large**."

- Effect size: naive estimate sizeable; selection-adjusted estimate ≈ 0 for the
  average student; still large for Black, Hispanic and first-generation-ish (low
  parental education) students. The exact coefficients are in the gated paper.
- Population: US students who _applied to_ selective colleges, 1976 and 1989
  entry.
- Limits: identification rests on "students who applied to the same set of
  schools are comparable"; it says nothing about students who never applied to a
  selective school, and it is one narrow (elite-ish) slice of the sector. The
  original 1999/2002 version is NBER w7322, <https://www.nber.org/papers/w7322>,
  with the same conclusion plus one extra: _average tuition_ of the school
  **was** significantly related to earnings.

Mountjoy & Hickman, "The Returns to College(s): Relative Value-Added and Match
Effects" (NBER w29276, 2021), <https://www.nber.org/papers/w29276>. Texas admin
data; compares students admitted to the _same set_ of Texas public universities:

> "a relatively tight, though non-degenerate, distribution of relative
> value-added … **Selectivity poorly predicts value-added** within student
> choice sets, with only a fleeting selectivity earnings premium **fading to
> zero after a few years** in the labor market. Non-peer college inputs like
> **instructional spending** more strongly predict value-added … Colleges that
> boost **BA completion, especially in STEM majors, also tend to boost
> earnings.**"

- Population: Texas public 4-year universities. Limits: public sector, one
  state, no elite privates; "tight but non-degenerate" means schools _do_
  differ, just not along the axis families use.

Chetty, Deming & Friedman, "Diversifying the American Elite?" (NBER w31492,
2023), <https://www.nber.org/papers/w31492>. Waitlist-based quasi-random design,
Ivy-Plus vs average flagship public:

> attending an Ivy-Plus college "increases students' chances of reaching the
> **top 1%** of the earnings distribution by **50%**, nearly **doubles** their
> chances of attending an elite graduate school, and almost **triples** their
> chances of working at a prestigious firm."

- Crucially, the same abstract reports **little effect on average earnings** in
  the broader sense — the effect is concentrated in the **upper tail**. Elite
  attendance buys tail outcomes, not a higher median.
- Population: _waitlisted_ applicants at several Ivy-Plus colleges — i.e.
  students already at the admissions margin of the most selective schools in the
  country. This is ~0.5% of US undergraduates and is irrelevant to most unicoach
  users.

Chetty et al., "Mobility Report Cards" (NBER w23618, 2017),
<https://www.nber.org/papers/w23618>; data at
<https://opportunityinsights.org/paper/mobilityreportcards/>:

> "children whose parents are in the **top 1%** are **77 times** more likely to
> attend an Ivy League college than those in the bottom quintile"; "children
> from low- and high-income families have **similar earnings outcomes
> conditional on the college they attend**, indicating that low-income students
> are **not mismatched** at selective colleges"; bottom-to-top-quintile mobility
> is highest at "certain **mid-tier public universities**, such as CUNY and
> California State colleges."

- Limits stated by the authors themselves: "our descriptive analysis does
  **not** identify colleges' causal effects." Mobility rates mix access with
  outcomes.

**Verdict for the product.** For the typical student, institution choice has a
small average earnings effect once you condition on where they applied. It
matters more for (a) low-income / first-gen / Black and Hispanic students, and
(b) upper-tail outcomes at Ivy-Plus. That is a _nuance_ message, not a ranking
feature.

---

## 2. Does MAJOR matter more than institution?

**Yes, on the earnings axis, and it is not close.**

Causal evidence: Kirkebøen, Leuven & Mogstad, "Field of Study, Earnings, and
Self-Selection" (NBER w20816, 2015), <https://www.nber.org/papers/w20816>.
Norwegian centralized admissions cutoffs, holding the next-best alternative
fixed:

> "different fields have **widely different payoffs**, even after accounting for
> institutional differences and quality of peer groups. For many fields the
> payoffs **rival the college wage premium** — suggesting the choice of field is
> potentially as important as the decision to enroll in college."

- Limits: Norway, compressed wage structure, centralized admission. External
  validity to the US is an assumption, not a result.

Hastings, Neilson & Zimmerman (NBER w19241, 2013),
<https://www.nber.org/papers/w19241>, Chile, 1,100+ degree programs, RD at
admission cutoffs: "returns are heterogeneous, with **large, positive returns to
highly selective degrees and degrees in health, science, and social science
fields**. **Returns to selectivity do not vary by student socioeconomic
status.**"

US descriptive magnitude — **[own calc]**, College Scorecard field-of-study file
`Most-Recent-Cohorts-Field-of-Study.csv` (June 2026 release), bachelor's
programs only (`CREDLEV == 3`), earnings = `EARN_MDN_4YR` (median earnings 4
years after completion):

| 2-digit CIP | field                           | programs | median      | p10     | p90      |
| ----------- | ------------------------------- | -------- | ----------- | ------- | -------- |
| 14          | Engineering                     | 1,792    | **$93,642** | $81,718 | $111,421 |
| 11          | Computer & Information Sciences | 1,284    | $86,234     | $60,823 | $120,729 |
| 51          | Health professions              | 2,748    | $71,696     | $49,037 | $96,003  |
| 52          | Business                        | 4,562    | $68,490     | $52,508 | $92,317  |
| 26          | Biology                         | 1,361    | $58,622     | $45,413 | $74,379  |
| 42          | Psychology                      | 1,226    | $50,323     | $41,351 | $61,363  |
| 13          | Education                       | 1,382    | $49,037     | $41,557 | $59,326  |
| 23          | English                         | 736      | $48,460     | $38,542 | $60,485  |
| 50          | Visual & performing arts        | 1,672    | **$42,989** | $33,966 | $56,951  |

Overall bachelor's median across 27,617 programs at 2,009 institutions:
**$59,399**. Top field median is **2.2×** the bottom field median.

Variance decomposition on log(EARN_MDN_4YR), same 27,617 programs — **[own
calc]**:

- Institution fixed effects explain **R² = 0.355** of the variance.
- 4-digit CIP (field) fixed effects explain **R² = 0.596**.
- Median _within-school_ p90/p10 ratio across that school's own majors (schools
  with ≥8 reported programs, n=1,073 schools): **1.73×**.
- Median _within-field_ p90/p10 ratio across schools (4-digit fields at ≥30
  schools, n=140 fields): **1.49×**.

So: choosing among majors _inside one school_ moves the earnings number more
than choosing among schools _inside one major_. This is descriptive — Scorecard
does not control for who selects into which major — but it agrees in direction
with the two causal papers above.

Debt-to-earnings by field — **[own calc]**, median `DEBT_ALL_STGP_EVAL_MDN` ÷
median `EARN_MDN_4YR`, bachelor's: Engineering **0.25**, Computer Science 0.27,
Mathematics 0.26 → Psychology 0.46, Education 0.49, Visual & performing arts
**0.54**. Median debt is nearly flat across fields ($20k–$26k); the _earnings
denominator_ is what moves. **Debt is a field-of-study problem disguised as a
price problem.**

Caveats that must ship with any use of this data (Scorecard's own definitions,
<https://collegescorecard.ed.gov/data/data-documentation/>): earnings cover
**only Title IV aid recipients** (federal aid applicants), are measured from tax
records, are suppressed for small programs, and reflect **selection, not
causation**. Also Ahn/Arcidiacono et al. (NBER w30331,
<https://www.nber.org/papers/w30331>) show mean returns "mask important
features": earnings _growth_ and _variability_ differ by major, so a single
median understates the difference between fields.

---

## 3. Completion risk, and "students like me"

Completion is the single biggest risk in the decision, and it is not evenly
distributed.

National base rate: NSC Research Center, _Completing College_ (12th report),
<https://nscresearchcenter.org/completing-college/> — six-year completion for
the fall 2017 entering cohort was **62.2%**, "essentially unchanged since 2015",
counting completion at **any** institution including after transfer. Roughly
**two in five** students who start do not have a credential six years later.

Institution-level spread — **[own calc]**, Scorecard institution file,
institutions with `PREDDEG == 3` (predominantly bachelor's) and `CURROPER == 1`,
n = 1,944: `C150_4` p10 = **0.28**, median = **0.55**, p90 = **0.82**.

Same-school gaps by student type — **[own calc]**, six-year completion at the
starting institution (`*_COMP_ORIG_YR6_RT`), median gap across institutions:

| comparison                 | n schools | median gap  | p25–p75  | schools where the gap is positive |
| -------------------------- | --------- | ----------- | -------- | --------------------------------- |
| non-Pell − Pell            | 1,541     | **4.5 pp**  | 0.6–9.3  | 78.1%                             |
| continuing-gen − first-gen | 1,581     | **6.8 pp**  | 3.1–11.0 | 90.7%                             |
| high-income − low-income   | 1,438     | **12.3 pp** | 7.2–18.1 | 94.9%                             |

The published headline graduation rate systematically **overstates** the odds
for a low-income or first-gen student, by a median of 5–12 points and by more
than 18 points at a quarter of schools. The correlation between a school's
overall `C150_4` and its low-income completion rate is 0.72 — high, but not high
enough to substitute one for the other. **[own calc]**

School quality _does_ move completion, beyond peer selection. Mountjoy & Hickman
(above) find colleges that boost BA completion also boost earnings. Dillon &
Smith, "The Consequences of Academic Match between Students and Colleges" (NBER
w25069, 2018), <https://www.nber.org/papers/w25069>:

> "Student ability and college quality strongly improve **degree completion**
> and earnings for all students. We find evidence of meaningful
> **complementarity** between student ability and college quality in degree
> completion **at four years** and long-term earnings, but **not** in degree
> completion at six years or STEM degree completion."

Read carefully: complementarity means a strong student gains more from a strong
school — which implies an _equity/efficiency_ tradeoff, not that weak students
are harmed. There is no support here for "don't reach."

**Undermatching.** Dillon & Smith, "The Determinants of Mismatch Between
Students and Colleges" (NBER w19286, 2013),
<https://www.nber.org/papers/w19286>: "substantial amounts of both undermatch …
and overmatch. **Student application and enrollment decisions, rather than
college admission decisions, drive most mismatch.** Financial constraints,
information, and the public college options facing each student all affect the
probability of mismatch. **More informed students attend higher quality
colleges.**"

Hoxby & Avery, "The Missing 'One-Offs'" (NBER w18586 / BPEA Spring 2013),
<https://www.nber.org/papers/w18586>,
<https://www.brookings.edu/articles/the-missing-one-offs-the-hidden-supply-of-high-achieving-low-income-students/>:
there are **25,000–35,000** low-income students per cohort in the top ~10% on
test scores and grades; "the **vast majority** … do not apply to **any**
selective college", even though aid would often make those colleges **cheaper**
than the institutions they do attend; and those who _do_ apply "are admitted and
graduate at high rates." The paper also finds the usual fixes (recruiting
visits, campus tours, access programs) "are likely to be **ineffective**" for
these students because they are geographically scattered.

- Limits: the population is a small, very high-achieving slice. But the
  _mechanism_ — information, not admission — is exactly the thing a coaching
  product can move, and Chetty's finding that low-income students are "not
  mismatched" at selective colleges removes the main counter-argument.

---

## 4. What predicts thriving / experience?

Gallup-Purdue Index, inaugural wave: ~30,000 US bachelor's-degree holders, web
study Feb 4–Mar 7 2014,
<https://news.gallup.com/poll/168848/life-college-matters-life-after-college.aspx>:

> "the **type** of schools these graduates attended — public or private, small
> or large, **very selective or less selective** — **hardly matters at all** to
> their workplace engagement and current well-being."

What did relate, with Gallup's own numbers:

- A professor who "cared about them as a person, made them excited about
  learning, and encouraged them to pursue their dreams" → **odds of being
  engaged at work more than doubled**, and odds of thriving in all well-being
  areas doubled. Only **14%** of graduates strongly agree they had all three.
- An internship/job applying classroom learning + a multi-semester project +
  active extracurricular involvement → **odds of engagement doubled**. Only
  **6%** strongly agree to all three.
- All six experiences ("the Big Six"): **3%** of graduates.

Limits, stated plainly: this is **retrospective self-report** among
internet-using graduates (Gallup notes 90% of graduates had internet access per
a 2013 Census report), it is **correlational**, and recall of "a professor who
cared" is plausibly contaminated by how the respondent's life turned out. It is
nonetheless the largest survey of its kind and its _null_ result — institution
type doesn't predict thriving — is the robust part.

There is no public, per-institution dataset of "share of students who had a
mentor." NSSE runs on engagement items but its institution-level results are
**not** publicly released for most schools. So this finding is **actionable as
advice**, not as a data column.

---

## 5. Which signals are noise?

- **Selectivity / admit rate / average SAT as a proxy for value.** Dale &
  Krueger (w17159): selection-adjusted return ≈ 0 for the average student.
  Mountjoy & Hickman (w29276): "selectivity poorly predicts value-added … only a
  fleeting selectivity earnings premium fading to zero after a few years."
- **Selectivity as a proxy for _your_ completion odds.** Selectivity is a strong
  predictor of the _school's average_ completion rate — **[own calc]**
  corr(`SAT_AVG`, `C150_4`) = 0.81 (R² = 0.66, n = 1,007); corr(`ADM_RATE`,
  `C150_4`) = −0.41. But within a 100-point SAT band the spread is wide — **[own
  calc]**, `C150_4` p10→p90 within band: 1050–1150 → 0.40–0.66; 1150–1250 →
  0.48–0.75; 1250–1350 → 0.62–0.83. Two schools with the same entering class
  differ by ~25 points in whether their students finish. That residual is the
  signal; the level is mostly peer composition.
- **Non-academic admissions credentials as a proxy for quality of student
  body.** Chetty et al. (w31492): legacy preference, non-academic ratings and
  athletic recruitment "are **uncorrelated or negatively correlated** with
  post-college outcomes, whereas academic credentials such as SAT/ACT scores are
  highly predictive."
- **Composite rankings.** I could not read the US News methodology (site blocked
  my client), so I claim only this: a composite whose largest inputs are
  selectivity, peer reputation and resources is built mostly out of the
  quantities the papers above show are weakly related to any individual
  student's outcome. Treat that as a reasoned inference, **not** a cited
  finding.
- **Student-faculty ratio.** I found **no** primary evidence either way in this
  session. Mark it "unevidenced" rather than "noise". The nearest real finding
  is Mountjoy & Hickman's: **instructional spending per student** (`INEXPFTE` in
  Scorecard) predicts value-added better than peer measures — a _different_
  column from student-faculty ratio.

---

## What unicoach already has, in the repo

- `colleges.completion_rate_150pct_4yr_share` — Scorecard `C150_4`, already
  loaded (`db/schema/0015.create-colleges.sql:53`, renamed at
  `db/schema/0059.name-numeric-columns-by-unit.sql:59`). Known gap recorded in
  that migration's own comment (lines 32–36): it is **four-year institutions
  only**, no `C150_L4` fallback, so every <4-year college is NULL.
- `college_programs_census` — 6-digit CIP × award level × award counts per
  college
  (`db/schema/0055.create-college-ipeds-and-programs-census.sql:92-100`).
  unicoach already knows **which majors each college actually graduates people
  in**.
- `subjects` — an authored CIP-prefix taxonomy
  (`db/schema/0064.create-search-index-and-subjects.sql:48-56`), i.e. a
  field-of-study vocabulary already exists and is already used by search.
- IPEDS institutional character: `college_ipeds` carries Carnegie class/size,
  locale, housing, ROTC, study abroad, religious affiliation, athletics, test
  policy (`db/schema/0055...:18-66`).
- **Not present**: retention (`RET_FT4`), completion **by student subgroup**,
  outcome measures (`OMAWDP8_*`), field-of-study earnings, transfer outcomes.

Coverage of the missing data — **[own calc]**, 1,944 four-year-predominant open
institutions: `C150_4` 92.3%, `RET_FT4` 92.7%, `PELL_COMP_ORIG_YR6_RT` 79.3%,
`FIRSTGEN_COMP_ORIG_YR6_RT` 81.3%, `LO_INC_COMP_ORIG_YR6_RT` 81.8%,
`MD_EARN_WNE_P10` 92.1%. Field-of-study earnings are thinner: of a school's own
bachelor's programs, the median share with a reported `EARN_MDN_4YR` is **30%**,
and only 2,009 of 2,537 institutions with bachelor's programs have **any**
program-level earnings figure.

---

## Implications for unicoach

Ranked by my confidence, highest first.

1. **Ship "completion odds for students like me" — this is the strongest, most
   defensible non-money answer available.** Scorecard already publishes six-year
   completion split by Pell, first-gen, income tercile, dependency and loan
   status (`PELL_COMP_ORIG_YR6_RT`, `FIRSTGEN_*`, `LO_INC_*`, at 79–82% coverage
   of four-year schools), and `money_profiles` already stores `income_band` and
   `dependency` (`db/schema/0046.create-money-profiles.sql:34`,
   `db/schema/0078.add-money-profile-dependency-status.sql`) — the join key
   exists. First-gen status is not collected today; it would be one new profile
   question. The message ("this school graduates 55% overall but 43% of students
   from families like yours") is concrete, sourced, per-student, and exactly the
   honesty posture the money work already took. Confidence: **high**.
2. **Treat field of study as a first-class axis, not a search filter.** My own
   decomposition says CIP explains more earnings variance (R² 0.60) than
   institution (R² 0.36), and within one school the spread across majors is
   1.73× vs 1.49× across schools within one major. The `subjects` taxonomy and
   `college_programs_census` already exist. Confidence: **high** that the
   finding is real; **medium** that a clean product surface exists, because
   coverage is only ~30% of a school's programs.
3. **Reframe debt as a field problem.** Median bachelor's debt is nearly flat
   across fields ($20k–$26k) while the debt/earnings ratio runs 0.25
   (engineering) to 0.54 (performing arts). unicoach already computes borrowing;
   adding the _field_ denominator turns an abstract number into a decision.
   Confidence: **high**.
4. **Actively teach the "where you go matters less than you think — except for
   you" nuance.** Dale & Krueger's exception (Black, Hispanic,
   low-parental-education students keep a large selectivity return) plus
   Chetty's "low-income students are not mismatched" makes this a _personalised_
   message the coach can deliver, and it points the same student toward applying
   up rather than down. Confidence: **high** on the evidence, **medium** on how
   to say it without sounding like a ranking.
5. **Build an undermatch check into the college list.** Dillon & Smith: mismatch
   is driven by student _application_ behaviour, not admissions, and "more
   informed students attend higher quality colleges"; Hoxby & Avery: 25k–35k
   high-achieving low-income students per year apply to no selective school
   despite it being cheaper. unicoach has the list, the academics and the price
   engine — it can detect "everything on your list has a lower completion rate
   than schools that would likely admit you and cost you less." Confidence:
   **medium-high**; needs a defensible admit-likelihood model, which is a real
   piece of work.
6. **Do NOT build a ranking, score, or "fit index."** Every composite the
   research examines is dominated by selectivity, which is the specific thing
   shown to have ~0 selection-adjusted return. A unicoach ranking would inherit
   the defect it exists to correct. Confidence: **high**.
7. **Do NOT ship institution-level earnings (`MD_EARN_WNE_P10`) as a headline
   number.** It mixes major composition, local labour markets and student
   selection; Chetty's elite premium is an upper-tail effect, not a median one.
   If earnings appear, they should appear **at the field level, at that
   school**, with the Title IV-recipients-only caveat attached — the same "how
   soft is this number" treatment brief 0008 already built for money.
   Confidence: **high**.
8. **Turn the Gallup "Big Six" into coaching questions, not data columns.** A
   caring professor and an applied internship each roughly double the odds of
   engagement at work, and only 3% of graduates get all six — but there is no
   public per-institution dataset for any of them. The right surface is the chat
   coach asking "when you visit, ask how a first-year finds a research mentor",
   not a badge on a college page. Confidence: **medium-high** on the framing;
   **high** that a data column is impossible today.
9. **Add retention (`RET_FT4`) as a cheap leading indicator.** 92.7% coverage,
   one column, already in the file unicoach ingests; first-year retention is the
   earliest observable of the completion risk in #1. Confidence: **medium-high**
   — the caveat is that it is first-time full-time only and adds little once
   completion-by-subgroup is shipped.
10. **Consider instructional spending per student (`INEXPFTE`) as the one
    "institutional quality" input worth carrying.** Mountjoy & Hickman found it
    predicts value-added conditional on selectivity. One paper, one state, and
    easily misread as "expensive = good". Confidence: **low-medium** — name it,
    let the gate kill it.
11. **Do NOT claim anything about student-faculty ratio, class size, or campus
    "culture fit" from data.** I found no primary evidence in either direction
    this session. Silence is the honest position. Confidence: **high** (about
    the absence of evidence, not about the underlying truth).
