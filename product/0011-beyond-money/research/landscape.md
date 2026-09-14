# Research — Landscape: what the competition sells beyond price

Brief 0011 "Beyond money". Written 2026-09-10. Built from three parallel
research passes (crowd-review tier, institutional tier, algorithmic/folk tier)
plus direct federal-source fetches. Full raw notes with complete failure lists:
`/tmp/0010-notes-A-reviews.md`, `/tmp/0010-notes-B-institutional.md`,
`/tmp/0010-notes-C-algorithmic-folk.md`.

## Method

Every load-bearing claim below carries an inline URL. `[GUESS]` marks inference.

**Worked:** direct fetch of methodology, pricing and college-profile pages;
`web.archive.org/web/2025/<url>` for sites that 403; the official CDS 2025-26
**.docx** template parsed item by item
(https://commondataset.org/wp-content/uploads/2025/11/CDS_2025-2026-Word_Template.docx);
Google News RSS and the OpenAlex API for journalism and papers.

**UNAVAILABLE — read this before trusting any gap below:**

- **The `websearch` skill has no Serper API key** and fails on every call.
  DuckDuckGo, Brave, Ecosia, Mojeek, Startpage and searx all served captchas.
  **Bing HTML works only for short, low-brand queries** — a company name
  collapses the result set to that company's homepage, and `site:` is ignored.
  This file is therefore **strong on published methodology and weak on
  journalism and criticism**.
- `niche.com` 403s a plain client and `r.jina.ai` now 403s too. Niche's
  **overall-grade weights, safety weights, current scholarship rules and privacy
  language are NOT verified.**
- `collegevine.com/schools` is JS-gated, so **which non-money axes its college
  profiles show is unverified**. BigFuture's Admissions/Academics/Campus Life
  tabs are client-rendered, so its axis list here may be understated.
- All reddit rules/wiki endpoints were blocked; the r/A2C chance-me rule text is
  second-hand.
- **College Board's Student Search price per name is not verified.** The widely
  repeated "~$0.50/name" is hearsay and is deliberately not quoted as fact.
- 403 or paywalled: NYT, AP, Fast Company, science.org, Stanford Report, Inside
  Higher Ed articles.

## The map, one line per product

| Product           | Beyond-price axes                                      | Presentation                       | Who pays                                 |
| ----------------- | ------------------------------------------------------ | ---------------------------------- | ---------------------------------------- |
| Niche             | ~13 graded axes incl. party scene, dorms, food, safety | Letter grade **on a forced curve** | 15,000 school clients + data licensing   |
| Unigo             | Free-text reviews, "Return on Education™"              | Testimonials, black-box score      | EducationDynamics (enrollment marketing) |
| Princeton Review  | 50 top-25 lists + 8 ratings (60–99)                    | Rank lists; `60*` = did not report | Test prep / tutoring upsell              |
| U.S. News         | Almost none in the formula                             | One rank                           | Badge licences + ads to ranked schools   |
| BigFuture         | Setting, size, religion, deadlines                     | Raw stat only                      | Colleges, via recruitment / Search       |
| Naviance / Scoir  | Scattergram, career and personality tests              | 2-D scatter of dots                | High-school licence + college licence    |
| Common App        | Setting, size, MSI, specialized mission                | Filter facets only                 | Member dues from colleges                |
| Appily (EAB)      | Deepest raw-stat set in the market                     | Gauge bars **vs "US National"**    | ML leads sold to colleges                |
| CollegeVine       | Chance %                                               | Single percentage                  | Universities — it pivoted to B2B         |
| ChatGPT et al.    | Anything you ask                                       | Prose, confidently                 | Subscription, or nothing                 |
| r/A2C, r/ChanceMe | Reach / target / safety                                | Vibes                              | Nobody                                   |

---

## 1. Crowd review and rankings

### Niche — a curve sold as a grade

Niche grades ~13 axes: Overall, Best Fit, Academics, Value, Diversity, Campus,
Athletics, Party Scene, Location, Professors, Dorms, Campus Food, Student Life,
Safety
(https://web.archive.org/web/2025/https://www.niche.com/colleges/rankings/methodology/).
Money is **one axis of thirteen**; Campus Food is its peer.

The grade is a **forced normal curve**, published on that page: A+ requires z ≥
1.96 and goes to **2.5%** of schools, and the cut points hand **B- or worse to
50% of colleges by construction**. "A+ campus life" means _top 2.5% relative to
peers_, not "good". Grades are positional goods and can never all improve.
Nothing on the display surface says so.

The weights are the story. **Best Academics** = acceptance rate **17.9%** +
yield **9.3%** + professors 15.1% + student surveys 12.2% + diversity 9.3% +
grad rate 9.3% + retention and research — so **27.2% of "Academics" is
selectivity and yield**, an input, not learning, and yield rewards ED gaming
(https://web.archive.org/web/2025/https://www.niche.com/colleges/rankings/best-college-academics/methodology/).
**Top Party Schools** = 70% surveys + **10% US Census bar density** + 5%
restaurant density — nothing about Clery liquor referrals or actual harm
(https://web.archive.org/web/2025/https://www.niche.com/colleges/rankings/top-party-schools/methodology/).
**Best Student Life** is 15% party scene and 10% athletics, so a student who
wants neither gets a score made of things they do not want.

User-generated factors get an undisclosed **Bayesian shrink** by response count;
Niche publishes neither the prior nor **the per-school N**, so you cannot tell
whether "A campus food" rests on 12 reviews or 1,200. It concedes bot reviews
affect rankings, and colleges have a **write path into their own inputs** via a
Partner Account — contradicting its own line, "Our college rankings don't use
data submitted to us by colleges" (methodology page above).

### Unigo — the reviews are bought

Owned by **EducationDynamics**, an agency selling colleges "cost-per-start"
reductions (https://www.educationdynamics.com/). The review corpus is purchased:
completing a review enters you in the **$1,000 College Review Scholarship**,
judged 25% writing ability, 25% creativity, 25% originality
(https://www.unigo.com/scholarships/our-scholarships/college-review-scholarship),
and the winners read as marketing copy. Official rules set the age floor at
**14** and make entry consent for the Sponsor "**and its related companies**" to
call and market to you
(https://www.unigo.com/scholarships/our-scholarships/shout-it-out-scholarship).
"Return on Education™" has no published methodology. **An incentive that selects
for enthusiasm, judged by the site that profits from the lead.**

### Princeton Review — the best editorial stance, on the thinnest evidence

**Rankings** are 50 lists, **top 25 only**, purely from a 98-question student
survey; **ratings** are separate 60–99 scores from administrator surveys
(https://www.princetonreview.com/college-rankings/ranking-methodology). PR
refuses an overall list on principle: "We don't believe that any one school is
the best overall … It's not which school is best academically (there's no such
thing) but which school is best for you"
(https://www.princetonreview.com/college-rankings/best-colleges/faq).

Worth stealing: **`60*` means the school did not report enough data** — an
explicit, visible missing-data marker
(https://www.princetonreview.com/college-rankings/college-ratings). Its **Fire
Safety rating** (sprinkler heads _in the room_, supervised detectors, malicious
and unwanted alarms per year, % of alarms monitored) is the only checkable
safety axis anyone publishes.

The evidence is weakest where it matters most: **172,000 students across 392
schools = an average of 439 per school**, self-selected and online, re-surveyed
**at a minimum of every three years**, so a 2026 "Happiest Students" rank may be
2023-24 data, undated to the reader. No response rate is published, and a school
may request extra cycles (i.e. drive its own turnout). The frame is editorial:
"Any college we consider adding to the guide must **agree to allow us to conduct
independent surveys of its students**" — **a college can veto itself out of the
book** (FAQ).

### U.S. News — moved toward money, not away from it

2026 weights
(https://web.archive.org/web/2025/https://www.usnews.com/education/best-colleges/articles/how-us-news-calculated-the-rankings):
student outcomes **52%** (6-yr grad rate 16, grad-rate performance 10, **Pell
grad rate 5.5**, **Pell grad performance 5.5**, retention 5, **median borrower
debt 5**, **earnings vs a high-school grad 5**); **peer assessment 20%**;
faculty resources 11–15%; financial resources 8%; test scores 5%; faculty
research 4%.

- "Factors like **acceptance rates, alumni giving, class size and high school
  class rank are no longer used**" (same page). **The two most-read products now
  contradict each other**: U.S. News dropped selectivity entirely; Niche still
  weights acceptance rate 17.9% inside _Academics_. Neither reconciles it for
  the reader.
- **21% of the formula is now Pell outcomes + earnings + debt** — U.S. News
  moved _into_ unicoach's territory, crudely. Its debt measure "does not control
  for the proportion of graduates who borrowed" (their own words), non-graduates
  are excluded from debt and earnings entirely, and missing data is **imputed
  favourably** for schools with few federal borrowers.
- **Vintage**: debt and earnings are Scorecard FY2019-20 and 2020-21, "for the
  third consecutive year" — students who graduated around 2015-16. The
  methodology page says so; **the rank carries no year stamp.** **Peer
  assessment is 20% of the score at a 30.8% response rate** (4,591 academics,
  2025), and U.S. News' own caveat is that mid-tier rank changes are "often
  statistically insignificant"
  (https://web.archive.org/web/2025/https://www.usnews.com/education/best-colleges/articles/ranking-criteria-and-weights).
- The formula has essentially **no campus-life, fit, culture or safety axis**.
  Those go to side lists, several derived from the same 30.8%-response dean
  survey.

---

## 2. The institutional tier — the family is routed, not served

### BigFuture (College Board)

Raw stats only: type, `Medium • Urban`, avg/yr after aid, SAT range, grad rate,
acceptance rate (printed to a spurious 2 d.p., 66.03%), religion, deadlines
(https://bigfuture.collegeboard.org/colleges/abilene-christian-university). No
grades, ratings, reviews, chances or rankings — the purest "we will not judge"
posture, and the least useful.

The footer, verbatim: profiles are "based **primarily on information supplied by
the colleges themselves** … with some data provided via federal and state
agencies." **No per-field provenance and no "as of" year anywhere.** The "From
the Institution" block is ~350 words of unaudited college marketing presented as
content — on a 2026 fetch it still cites a **2022** Princeton Review award and
an unsourced "96% employed or enrolled".

The student's saved BigFuture list is itself a licensed targeting signal: "More
than **1,200 colleges** … rely on Student Search"; shared on opt-in are score
**ranges**, the **BigFuture college list** and college preferences, but not
actual scores or grades (https://studentsearch.collegeboard.org/). Discovery in
US college search is structurally a **score-sorted mailing list** — test well
and 1,200 institutions write to you.

**Landscape was discontinued on 2 Sept 2025**
(https://allaccess.collegeboard.org/update-landscape): "Landscape will be
discontinued. We will continue to provide colleges and universities with data on
**SAT and AP performance**." The one institutional mechanism putting a student's
_circumstances_ beside their numbers is gone, and the residue is explicitly test
performance. **Context is now nobody's job.**

### Naviance and Scoir — the scattergram

Naviance reaches "over 10 million students in **40% of U.S. high schools**"
(https://www.powerschool.com/solutions/naviance-campaign/), though the vendor
publishes three different scale numbers on three pages. The scattergram shows
"the acceptance history of students **within a particular high school** to a
specific college": x = test score, y = GPA, dot = accepted/denied/waitlisted
(https://en.wikipedia.org/wiki/Naviance).

**No probability is ever stated.** The chart implies a decision boundary and
lets a 17-year-old infer one. Defects: **n is one high school's history**, often
0–8 dots, so a boundary through six dots is drawn with the authority of 200;
**two axes only** — no essays, recs, rigour, legacy, athlete, intended major,
need-aware status or ED-vs-RD, so a denied dot above an accepted dot looks like
caprice when it is usually a hook; **self-fulfilling**, since a school that
never sent anyone shows an empty chart read as "not for us", worst where
counselling capacity is lowest; **self-reported outcomes**, so rejections are
under-reported and reaches look reachable
`[GUESS — mechanism obvious, no study found]`; **test-optional contaminates the
x-axis** post-2020 with no vendor documentation; and **no error bars, no
vintage, no n displayed**.

Scoir publishes both price lists, which makes the incentive legible. High
schools pay **$4.80/student/year** (https://www.scoir.com/high-schools/pricing);
colleges pay $26,040 / $39,960 / **$56,040** a year
(https://www.scoir.com/colleges/pricing) — and the Growth tier delivers match
messages to the "'Promotion' folder (**6% read rate**)" while Enterprise gets
"'**Primary Inbox**' delivery & notification (**40% read rate**)". **A ~6.7x
read-rate multiplier, sold for about $16k/year.** Which colleges a student sees
is, in writing, a function of spend.

### Common App — collects, refuses to advise

1,164 colleges searchable; the **entire** non-money axis set is location,
application type, campus setting, financial aid, MSI status, type, enrollment
size and specialized mission (https://www.commonapp.org/explore). Nothing
evaluative — it cannot rank its dues-paying members. Its real product is data:
application-trend reports by demographic, community income and institution type,
with **ZIP-code median income** as the unit throughout
(https://www.commonapp.org/about/reports-and-insights). **An input for unicoach,
not a rival.** Paid by member dues and rewarded for volume, Common App will
never say "your list is long enough".

### Appily (EAB) — best presentation, worst-disclosed incentives

Appily is a consumer skin over EAB's enrollment-marketing lineage — **Royall &
Company, acquired 2015 for $850M** (https://en.wikipedia.org/wiki/EAB_(company))
— and colleges buy "student leads backed by machine learning"; EAB claims "65%
of college-bound high school seniors" use it
(https://eab.com/solutions/appily/). Its profiles carry the deepest raw
non-money set anywhere
(https://www.appily.com/colleges/abilene-christian-university): class-size
distribution (2-19: 46%, 20-39: 38%, 40-99: 13%, 100+: 1%), GPA distribution of
enrolled students, class rank, a **need-aware flag**, CIP-level majors, %
first-generation, LGBTQIA and women's-center booleans, athletics rosters, and
post-graduation stats (avg salary 6 years out $48,900, top-5 employers, median
federal debt $16,890). **The idea worth stealing: every figure sits on a 0–100
gauge with an explicit "US National:" comparator** — decision-useful at zero
data cost, and BigFuture, Naviance, Common App and Scoir all omit it.

What it gets wrong: **no vintage on any field**; IPEDS, Scorecard and unsourced
proprietary data share one visual register (top-5 employers have no methodology
page); a stale cohort-default-rate with no cohort year; and a **campus safety
tab of four booleans** — 24-hr patrol, emergency phones, escort rides, mobile
alert — "Yes" at essentially every school. Decorative. No Clery counts.

---

## 3. The algorithmic and folk tier

### CollegeVine — the student product is now the funnel, not the business

**The pivot is total and it matters.** `collegevine.com` and `/colleges` both
read "**The AI operating system for universities**"
(https://www.collegevine.com/); `/high-schools` is a 404. The institutional
modules include enrollment management with "**yield modeling**" and financial
aid with "**aid leveraging**" — the practice of computing the _minimum_ grant
needed to yield a given student. **CollegeVine sells the tool that decides how
little to offer, to the same institutions whose price the student is trying to
understand**, while running the free chancing engine that "publish[es] your
profile to get recruited by admissions officers"
(https://www.collegevine.com/students). The activity ticker there names the
counterparties — High Point, Assumption, Marquette, Belmont, Central Michigan,
UT Tyler: tuition-discount-dependent regional privates, not the T20 schools
students chance themselves against `[inference; the college
list is verbatim]`.

The engine outputs **a single percentage** from ~75 inputs (GPA and test vs the
**25th percentile** of past admits, rigour, extracurriculars on a 12-sub-tier
scale, gender, state, intended major; race removed after SFFA). It **explicitly
does not model essays, recommendations, high-school context, early
decision/action, or legacy**
(https://blog.collegevine.com/is-collegevine-chancing-accurate/).

Its only published accuracy evidence is a **calibration** table (predicted 5% →
actual 7.2%; 50% → 48.1%; 95% → 94.0%) from that same **2021** post, on
**self-reported outcomes from its own users**, with **no N, no response rate, no
cohort year and no discrimination metric (AUC/Brier)**. CollegeVine states the
limit itself: calibration "tests how accurate the predicted chances are _on
aggregate_". **A model that returned each school's published admit rate to every
applicant would also be perfectly calibrated.** And because hooked applicants
are a small share of the applicant pool but a large share of the admit pool,
hook-blindness can hide inside good calibration
`[reasoned inference, unpublished]`.

Credit where due — their own copy makes exactly the move unicoach makes about
sticker price: "The acceptance rate on a school's website is not your chance of
getting in … some have a 99% chance of acceptance, while others have less than
1%" (https://www.collegevine.com/admissions-calculator/).

### ChatGPT as counsellor — real demand, one measured failure mode

The demand is a supply gap: student-to-counselor ratio **372:1**, nearly **1 in
5 high schools have no college counselor at all**, only about a fifth of
counselor time goes to admissions advising, and an EAB survey finds **nearly
half of students already use AI** to navigate applying
(https://hechingerreport.org/ai-educators-college-counselors/). NACAC CEO
**Angel Pérez**, same piece: "Ask [generative] AI what college you're going to
get into — you're not going to get the right answers"; consumer AI "**can't
detect misinformation** … and is vulnerable to recruiters who use tricks to put
certain universities … at the top."

The strongest citation in the landscape is about **flattery**. Cheng, Lee,
Khadpe, Yu, Han and Jurafsky, "Sycophantic AI Decreases Prosocial Intentions and
Promotes Dependence" (https://arxiv.org/abs/2510.01395; _Science_, doi
10.1126/science.aec8352): across **11 state-of-the-art models**, models **affirm
users' actions 50% more than humans do**; in two preregistered experiments (**N
= 1604**) sycophancy increased users' conviction of being right, and users
**rated the flatterer higher, trusted it more, and were more willing to reuse
it**.

**Honest gap:** there is **no published error rate** for LLM college-advice
facts — deadlines, net price, admit rates, whether a named scholarship exists.
The counselor-side claim is expert assertion, not measurement. Do not let the
brief state a hallucination rate; there isn't one.

### r/ApplyingToCollege — the mods already refused the question

**r/A2C removed Chance Me posts in 2017** because "no one outside of an
admissions office can predict the outcome of an application", and because
students with unusual applications "become discouraged from applying based on
responses"
(https://blog.campussonar.com/blog/the-evolution-of-chance-me-on-reddit). The
exiles formed r/ChanceMe (~112k). Slate's verdict on the method: "There's no
hard rubric, standard, or measurable of someone's chances. **It is strictly
based on vibes**", plus the same defect CollegeVine has — "a bit of
**survivorship bias** … many users wouldn't post … if they didn't feel like they
had a strong résumé already" — and users who "lie for validation"
(https://slate.com/technology/2024/06/reddit-ivy-league-college-admissions-toxic.html).

**Note the symmetry.** The paid ML tier and the free folk tier share a
data-generating process (self-reported outcomes from a self-selected population)
and emit the same output format (reach / target / safety). CollegeVine did not
invent a better abstraction; it put a percentage on the folk one.

---

## 4. The three questions the brief asked directly

### Who predicts admission chances, and how honest is it?

- Who tries: **CollegeVine** (a percentage, calibration-only, hook-blind),
  **Naviance/Scoir** (~6 dots, no stated probability), **Reddit** (vibes),
  **Appily** (a "See My Chances" CTA with **no published methodology**).
  **BigFuture, Common App, U.S. News and Princeton Review refuse.**
- What a **school-level admit rate structurally cannot tell you**, verified
  against the CDS 2025-26 template:
  1. **It is an ED+EA+RD blend by construction.** Item **C1**: "Include early
     decision, early action, and students who began studies during summer in
     this cohort."
  2. **C21** collects ED applications and ED admits, so **an ED rate is
     computable per school**. **C22 collects NO early-action counts** — closing
     date, notification date and restrictiveness only. **An EA rate is not
     derivable, so most schools' rates cannot be unblended.**
  3. **There is no CDS item anywhere for admission by major, program, school or
     college.**
  4. Hooks are enormous and unmodelled: among **white Harvard admits, over 43%
     are ALDC** (athlete/legacy/dean's list/faculty child), and "roughly **three
     quarters of white ALDC admits would have been rejected** if they had been
     treated as white non-ALDCs" (https://www.nber.org/papers/w26316). Top-1%
     families are **more than twice as likely** to attend an Ivy-Plus as
     middle-class students with **comparable scores**, two-thirds of it from
     higher admit rates at the same scores — and **no such advantage exists at
     flagship publics** (https://www.nber.org/papers/w31492).
  5. Yield protection and demonstrated interest are unobservable to the student
     — and yield modelling is a product colleges buy.
- **Program-level admission is where the variance is.** UIUC publishes it:
  admitted middle-50% SAT is **1480–1550 for Grainger Engineering vs 1200–1400
  for Social Work**, a ~280-point gap inside one campus
  (https://admissions.illinois.edu/Apply/Freshman/profile). The UC system
  publishes admission by discipline but warns "**use caution** … not as a
  predictor of an applicant's chances", and notes major choice is generally
  **not** a factor at most UC campuses
  (https://www.universityofcalifornia.edu/about-uc/information-center/freshman-admission-discipline).
  **So the right move is a per-school fact — "does this school admit by major?"
  — not a universal major coefficient.**

### Who reports outcomes by MAJOR rather than by school?

**In the consumer tier: nobody.** Appily gives one school-level "avg salary 6
years after graduation". U.S. News weights one school-level earnings measure at
5%, saturating against a **$32,000** high-school-grad threshold. Niche pipes
school-level Scorecard earnings into Value. BigFuture shows no earnings at all;
Princeton Review has no earnings axis. Caveat: Niche's per-major ranking
methodology pages 404'd in the archive, so a partial exception there is
possible.

**The one place it exists is the federal source itself.** College Scorecard
publishes a field-of-study file — earnings and debt by CIP × credential — and
its consumer site already searches by major
(https://collegescorecard.ed.gov/data/data-documentation/,
https://collegescorecard.ed.gov/search/?major_indices=51). It is free, public,
and **not ingested by unicoach today**. The competitive fact: the consumer
products have chosen not to use the best public data on the highest-stakes
question. Same shape as the money story — **one school-level number hides the
truth, and someone already published the disaggregated one.**

### Who treats "will I be happy there" as answerable, and on what evidence?

- **Princeton Review says yes, on one Likert item.** "Happiest Students: Based
  on how strongly students agree or disagree with the statement, '**I am happy
  at my school**.'" Friendliest Students, Best-Run Colleges,
  LGBTQ-Friendly/Unfriendly and race/class interaction are each likewise a
  **single agree/disagree item**
  (https://www.princetonreview.com/college-rankings/ranking-methodology). Basis:
  ~439 self-selected students per school, up to three years old, at a college
  that consented to be surveyed. That is the entire empirical foundation for the
  most emotionally loaded claim in college search.
- **Niche says yes**, via a curved letter grade over unverified reviews with a
  hidden N; **Unigo**, via testimonials it paid for; **Appily**, via free-text
  reviews with no rating, no count, no date and no verification.
- **Nobody conditions happiness on who the student is.** There is no "happy for
  students like you" anywhere in this landscape. "MyFit", "Your Best Fit", "See
  your fit" and "right-fit students" appear across five products and **none
  publishes a methodology**.

---

## 5. The gaps a truth-telling, source-citing coach could own

**Gap 1 — Provenance and vintage on a NON-money field. Nobody does this at
all.** Vintage appears only in a methodology page nobody reads, or nowhere.
BigFuture is "primarily school-supplied" and does not mark which fields. Appily
mixes IPEDS, Scorecard and unsourced proprietary data in one visual register
with no year. U.S. News ranks 2026 on 2015-16 earnings. Princeton Review shows a
possibly-3-year-old happiness rank undated. **unicoach's figure-honesty
discipline transfers to the non-money axes unchanged, and no incumbent competes
on it.** They structurally cannot: a source-and-year stamp on a school-supplied
field is an admission that the school wrote it, and the school is the customer.

**Gap 2 — "Which colleges belong on my list?" cannot be answered honestly by
anyone paid by colleges.** Scoir sells primary-inbox placement at a documented
**6% → 40% read-rate step for about $16k/year**. College Board licenses your
saved list and score band to 1,200+ institutions. Appily sells ML leads. Common
App is paid per member and rewarded for volume. Niche has 15,000 paying school
clients. **Nobody in this market is paid to say "your list is long enough", "you
would not be happy there", or "don't apply".** Negative advice is a
differentiator precisely because it is unfundable everywhere else.

**Gap 3 — Sample size, and refusing to aggregate over a number too small to mean
anything.** Niche hides N behind an undisclosed Bayesian shrink. Princeton
Review publishes a 392-school average of 439 and no per-school N or response
rate. The Naviance scattergram — seen by roughly 40% of US high schoolers — is
n≈6 dots, self-reported, no error bars, no vintage, test-optional contaminated.
**A product that says "3 reviews — we won't grade this" does what the whole
market structurally avoids**, because a blank cell carries no ad inventory. PR's
`60*` is the precedent to steal.

**Gap 4 — The single school-level number, on the axes where it is a lie.** Admit
rate is an ED+EA+RD blend the CDS itself cannot unblend, hiding a ~280-point
within-campus spread. Earnings by school hide the fact that the major explains
more of it. Graduation rate by school hides the Pell/first-gen split U.S. News
now weights at 11% and no consumer product shows a family. **This is the money
story on a new axis, and unicoach already knows how to say "that number is not
your number".** (A secondary fifth: the conflict of interest can simply be named
— CollegeVine sells aid leveraging and yield modeling to colleges while running
the student chancing funnel, and U.S. News sells badges to the schools it ranks.
Only a product paid by families can say so.)

## Implications for unicoach

Ranked by my confidence, highest first.

1. **BUILD: Scorecard field-of-study — earnings and debt by CIP × credential.**
   The brief's candidate C. Federal, free, documented, and **used by no consumer
   product in this landscape**; unicoach already stores the CIP inventory, so
   the join exists. The line that sells it: _the school's median earnings is not
   your major's median earnings._

2. **BUILD: a source-and-year stamp on every non-money field.** Highest
   confidence, lowest cost — pure reuse of brief 0008's machinery (Gap 1).
   Concretely: mark which IPEDS/CDS fields are school-supplied. BigFuture's own
   footer admits its profiles are, and it prints 4-year-stale college marketing
   as fact.

3. **BUILD: "vs national" comparators on stats already stored.** Near-zero cost.
   Appily's gauge-vs-"US National" is the best presentational idea in the market
   and needs no new data. "60% graduation rate" is not decision-useful; "60% vs
   52% national" is.

4. **BUILD, as a refusal: an admit-rate honesty surface, not a chancing
   engine.** Say what the number is — "12% is an ED+EA+RD blend from CDS C1 for
   Fall 2025; this school's ED rate was 24% from C21; EA counts are not
   collected by the CDS at all" — and what it cannot include (legacy, athlete,
   your major, demonstrated interest). Stored provenance, not a model:
   **strictly more honest than the market leader at zero modelling cost.**

5. **DO NOT BUILD: a percentage chance of admission.** A million-member
   subreddit banned the question in 2017 because nobody outside an admissions
   office can answer it. The market leader's only evidence is a 2021 calibration
   table with no N, no response rate and no discrimination metric, on
   self-reported data, blind to factors (ALDC, ED) that flip ~3/4 of some
   subgroups' outcomes. A calibrated number that cannot discriminate is a
   school-level admit rate in costume.

6. **BUILD: a per-school "does this school admit by major?" fact.** UIUC shows a
   280-point within-campus SAT spread; UC states major is generally not a factor
   at most of its campuses. A universal major coefficient would be wrong at UC
   and right at UIUC. The honest artifact is the per-school fact plus the
   published unit-level profile where one exists.

7. **DECIDE: a hard N-floor and a visible "we won't say" state for any crowd or
   survey figure** (Gap 3). Steal Princeton Review's `60*`: an explicit
   non-response marker beats a shrunk estimate. This is the natural non-money
   analogue of money's D7.

8. **DO NOT BUILD: a happiness, vibe or fit score.** The best-documented
   competitor answers "will I be happy there" from **one Likert item, ~439
   self-selected students, up to 3 years stale**; the rest use bought or
   unverified reviews. unicoach has facts about the _place_, not about
   belonging. The defensible version: state place facts with sources (size,
   setting, distance, religion, housing, greek share, study abroad) and refuse
   the summary judgement.

9. **CONSIDER: Pell/first-gen disaggregated completion** (candidate B),
   contingent on the data-feasibility report. U.S. News now puts 11% of its
   formula on Pell graduation and Pell graduation _performance_, yet **no
   consumer product shows a family "students like me finished at this rate"**.
   The disaggregation is the product; a school-level rate alone invites exactly
   the wrong inference.

10. **PLAN AROUND, do not wish away: sycophancy is a measured market gradient.**
    Eleven models affirm users 50% more than humans, and users _prefer and
    trust_ the flatterer (N=1604, https://arxiv.org/abs/2510.01395). Every
    honest refusal unicoach ships will test worse on immediate satisfaction than
    a flattering competitor. Decide that at gate 1, not later in retention
    numbers. Cheap adjacent move: **there is no published error rate for LLM
    college-advice facts** — scoring 200 deadline / net-price / admit-rate
    questions against IPEDS and the CDS would be novel, on-brand and directly
    citable. Lowest confidence goes to **safety and support services**
    (candidate G): Appily's safety tab is four always-true booleans and nobody
    else has anything, but unicoach ingests no Clery data, so it is a real gap
    and a later beat.
