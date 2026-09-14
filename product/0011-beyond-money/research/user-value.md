# Beyond money: what families say drives the college choice

Research report for product brief `0011-beyond-money`. Topic: stated drivers of
college choice, revealed behaviour, the parent/student split, the emotional job
to be done, and which of it a cited-data coach can honestly answer.

## Method

**What worked.** Direct HTTP fetches (httpx, browser User-Agent) of primary
sources, plus `pypdf` text extraction of survey monographs, plus the College
Scorecard API (`api.data.gov/ed/collegescorecard/v1`, `DEMO_KEY`) to verify
which fields actually carry values. Two sub-agents ran the Sallie Mae /
Gallup-Lumina / Art & Science strand and the forum / parent-worry strand in
parallel; their findings are cited to their own URLs below.

**What was UNAVAILABLE — stated limitations, not silent gaps.**

- The `websearch` skill is **not configured** in this session ("no Serper API
  key"). There was no keyword search at all.
- Every general search engine I tried refused a scripted client: DuckDuckGo HTML
  returned a bot-challenge page, Google returned an interstitial, Bing returned
  only ad results, Mojeek returned a captcha, Startpage returned "connection
  suspended", public SearxNG instances returned 403/429. **Everything below was
  reached by guessing or already knowing a URL.** Sources that exist but that I
  could not name a URL for are simply missing.
- **NACAC State of College Admission**:
  <https://www.nacacnet.org/state-of-college-admission/> returns HTTP 200 but
  the body is navigation chrome; the report itself is member-gated. **No NACAC
  numbers appear in this report.** This is the single biggest hole — NACAC is
  the canonical counsellor-side source on choice factors.
- **Niche senior survey**: no reachable URL found. **EAB / Encoura**: `eab.com`
  research URLs I tried returned HTTP 404 or JS-only bodies; **no EAB/Encoura
  numbers here.**
- **CIRP monograph series stops at Fall 2019** at the
  `heri.ucla.edu/monographs/` path (2020, 2021, 2022 all 404). The ranked
  "reasons" table is therefore **pre-pandemic**. Treat any 2020-2026 movement in
  that specific item as unmeasured.
- The 2005 CIRP monograph is a scan; OCR of its norms table failed. The 20-year
  trend below uses the _Fifty-Year Trends_ monograph tables instead, which stop
  at 2015.
- Art & Science studentPOLL issues published only in Adobe InDesign viewers
  (Sep/Oct 2025, Feb/Mar 2026) could not be read.
- **Reddit blocks scripted clients** (403 on the JSON API and on Pushshift).
  Forum evidence in §4 was read through a public Redlib mirror; thread titles
  and scores are real, but this is a second-hand rendering, and no systematic
  sampling was possible.
- **Niche returns 403** behind Cloudflare on every URL tried, so **no Niche
  senior-survey data is in this report.** BestColleges' major-regret survey URL
  404s. No "would you choose the same school again" regret rate could be
  sourced.
- Princeton Review's _College Hopes & Worries_ full report PDF **was** reachable
  and is the best parent-vs-student source found.

---

## 1. The ranked list of stated reasons, and how it moved

### 1a. Fall 2019, all baccalaureate institutions (the last CIRP norms)

Source: _The American Freshman: National Norms Fall 2019_, HERI/UCLA, weighted
norms table, p. 44 —
<https://www.heri.ucla.edu/monographs/TheAmericanFreshman2019.pdf>. n = 95,505
first-time full-time freshmen at 148 institutions, weighted to ~1.5M national
FTFT entrants. Item: _"The following reasons were 'Very Important' in deciding
to go to this particular college."_

| Rank | Reason                                                        | % very important |
| ---- | ------------------------------------------------------------- | ---------------- |
| 1    | This college has a very good academic reputation              | **63.2**         |
| 2    | This college's graduates get good jobs                        | **54.8**         |
| 3    | Academic reputation of my intended major                      | **53.7**         |
| 4    | The cost of attending this college                            | **50.5**         |
| 5    | I was offered financial assistance                            | **48.9**         |
| 6    | Good reputation for its social and extracurricular activities | **47.8**         |
| 7    | A visit to this campus                                        | **46.2**         |
| 8    | I wanted a school about this size                             | **35.5**         |
| 9    | Graduates make a difference in the world                      | **33.5**         |
| 10   | Graduates gain admission to top grad/professional schools     | **30.7**         |
| 11   | I wanted to live near home                                    | **25.4**         |
| 12   | Communication with a professor                                | **21.5**         |
| 13   | My parents/relatives wanted me to come here                   | **17.1**         |
| 14   | Rankings in national magazines                                | **15.2**         |
| 15   | Admitted through Early Action / Early Decision                | **15.0**         |
| 16   | Could not afford first choice                                 | **14.7**         |
| 17   | High school counselor advised me                              | **11.2**         |
| 18   | Not offered aid by first choice                               | **10.8**         |
| 19   | Religious affiliation of the college                          | **9.1**          |
| 20   | My teacher advised me                                         | **8.2**          |
| 21   | Private college counselor advised me                          | **4.9**          |

**Read this carefully.** The two money items (50.5 + 48.9) sit at ranks 4 and 5.
Four non-money reasons outrank both: general academic reputation, job outcomes,
_major-level_ academic reputation, and — barely below — social/extracurricular
reputation and the campus visit. Money is decisive but it is not the top of the
stated list.

### 1b. 15-year trend (all FTFT students)

Source: _The American Freshman: Fifty-Year Trends, 1966-2015_, HERI/UCLA, trend
table pp. 68-70 —
<https://www.heri.ucla.edu/monographs/50YearTrendsMonograph2016.pdf>. 2019
column from the 2019 monograph above; 2012 column cross-checked against
<https://www.heri.ucla.edu/monographs/TheAmericanFreshman2012.pdf> (p. 41).

| Reason                                | 2000             | 2008 | 2012 | 2015 | 2019     |
| ------------------------------------- | ---------------- | ---- | ---- | ---- | -------- |
| Good reputation for social activities | 27.9             | 38.4 | 40.2 | 44.8 | **47.8** |
| The cost of attending this college    | — (31.0 in 2004) | 39.9 | 43.3 | 45.2 | **50.5** |
| I was offered financial assistance    | 32.0             | 43.0 | 45.6 | 47.1 | **48.9** |
| Very good academic reputation         | 55.2             | 64.7 | 63.8 | 69.7 | **63.2** |
| Graduates get good jobs               | 50.9             | 54.2 | 55.9 | 60.1 | **54.8** |
| Rankings in national magazines        | 9.8              | 17.6 | 18.2 | 20.1 | **15.2** |
| I wanted to live near home            | 16.9             | 20.1 | 20.1 | 18.3 | **25.4** |
| A visit to this campus                | 37.6 (2003)      | 41.4 | 41.8 | 42.8 | **46.2** |
| Could not afford first choice         | 9.4 (2006)       | 11.2 | 13.4 | 13.0 | **14.7** |
| Admitted via Early Action/Decision    | 7.1              | 11.8 | 13.7 | 16.3 | **15.0** |

Four things moved, and only one is money:

1. **Social/extracurricular reputation nearly doubled: 27.9 → 47.8 (+20 pts).**
   In 1983 it was 23.6 (Fifty-Year Trends p. 69). "Will I like it there" is now
   cited about as often as price. This is the largest single movement in the
   whole table.
2. **Cost rose ~20 pts (31.0 in 2004 → 50.5) and aid rose ~17 pts (32.0 →
   48.9).** Real, but _not larger_ than the social-fit rise.
3. **"Live near home" jumped 18.3 → 25.4 between 2015 and 2019** (+7 pts in four
   years) after fifteen flat years.
4. **Rankings peaked at 20.1 in 2015 and fell to 15.2 in 2019.** Ranking-chasing
   is a minority behaviour and shrinking.

Also from the same table, the **reason for going to college at all** shifted
decisively to work: _"to be able to get a better job"_ 70.0 (1971) → 71.6 (2000)
→ **85.2** (2015), and _"to get training for a specific career"_ 69.2 (2006) →
**76.1** (2015). The vocational frame is now near-universal, while _"to gain a
general education"_ moved only 64.5 → 71.5.

### 1c. Post-pandemic (2024-2026) confirmations from other surveys

- **Sallie Mae, _How America Pays for College 2026_** (Ipsos, 1,000
  undergraduates + 1,000 parents, fielded 22 Apr - 26 May 2026) —
  <https://www.salliemae.com/about/leading-research/how-america-pays-for-college/>.
  Verbatim: _"the top factors college families used to choose what school to
  attend were the price (40%), proximity to home (39%), whether the school was
  in-state (39%), and the academic program or degree offered (38%)."_ Then
  school aid 36%, campus environment 33%.
- **Gallup-Lumina, _State of Higher Education 2026_** (fielded fall 2025;
  ~14,000 adults 18-59 without a degree) —
  <https://www.gallup.com/file/analytics/709325/Lumina_Foundation-Gallup-State_of_Higher_of_Higher_Education_2026_Report.pdf>.
  Reasons for choosing _where_ to enrol (up to 3): **proximity to home 46%, cost
  39%, aid offered 29%.** Proximity beats cost outright.
- **Art & Science studentPOLL Vol 17 Iss 4, Oct 2024** (4,030 HS seniors, MoE
  ±3.6%) —
  <https://carlton-rollins.squarespace.com/s/studentPOLL-Politics-Policies-and-College-Choice-October-2024-c6c2.pdf>.
  **28% ruled out a college solely because of the politics/policies of the state
  it sits in** (24% in Winter 2023); **64% of those exclusions happen at first
  list-building**, 24% at the final enrol decision; 33% of excluders rule out
  their own home state.

---

## 2. Where stated reasons and revealed behaviour diverge

**(a) Distance is under-stated by roughly 3x.** Only **25.4%** of 2019 freshmen
called "I wanted to live near home" very important. But in Fall 2022, of
2,657,456 first-time degree-seeking undergraduates who were residents of a state
and enrolled anywhere, **2,082,875 — 78.4% — enrolled in their home state**
(NCES Digest of Education Statistics 2023, Table 309.10, columns 2/3 —
<https://nces.ed.gov/programs/digest/d23/tables/dt23_309.10.asp>). Students do
not say geography drives them; geography drives them. Sallie Mae (39%) and
Gallup-Lumina (46%) put proximity level with or above price, which fits the
behaviour better than the CIRP item does.

**(b) Cost is an elimination rule, not a selection rule.** **79% of families
eliminated a school from consideration based on cost alone** (Sallie Mae 2026,
same URL), applied at every stage — 58% before researching, 60% before applying,
53% after admission but before aid offers, 63% after seeing offers. Yet the
**final** deciding factor splits **academic 36% / financial 35% / personal
(location, social life) 27%** — i.e. **63% of final decisions turn on something
other than money**. Art & Science reach the same conclusion independently: _"the
substantive elements of the college experience still matter — usually more so
than affordability alone"_ (studentPOLL Vol 16 Iss 4, Nov 2023 —
<https://www.artsci.com/s/studentPOLL-November-2023-Affordability-Published.pdf>),
with 40% (winter) rising to 52% (spring) of seniors ruling out a school solely
on cost. **The price tool wins the shortlist; something else wins the choice.**

**(c) Families say they want cheaper and behave otherwise.** Art & Science's
experimental work (studentPOLL Vol 15 Iss 1, 2022, summarised in the Nov 2023
issue above) rated the same institution **9.4/10 at high cost with high
amenities, 7.9 at medium, 7.2 at low** — cutting price _and_ the experience
reduced appeal. Purely need-based packages also tested less appealing than mixed
merit+need packages of equal value.

**(d) Optimism about finishing is not supported by the data.** **84% of families
are confident the student will earn a bachelor's in four years** (Sallie
Mae 2026) — against a national first-year retention rate of **82%** for FTFT
bachelor's-seekers entering four-year institutions in fall 2019 (NCES Condition
of Education,
<https://nces.ed.gov/programs/coe/indicator/ctr/undergrad-retention-graduation>),
and institution-level 150%-of-normal-time completion that the College Scorecard
publishes school-by-school. Retention at the least selective public four-years
is **59%**, versus 96% at the most selective — a 37-point spread that families
are not looking at.

**(e) Students land where they land.** "Is this college your first choice?" fell
from **70.6% in 2000 to 58.9% in 2015**, with third-choice-or-lower rising from
8.6% to 15.4% (Fifty-Year Trends p. 70). More than four in ten freshmen are at a
school they did not rank first. Any coaching product that assumes a single dream
school is modelling a shrinking case.

**(f) Rankings are used more than they are admitted.** 15.2% call rankings very
important (2019), down from a 20.1% peak. I have **no citable revealed-behaviour
counterpart** for ranking traffic — treat the common claim that "everyone
secretly uses rankings" as **unverified** here.

---

## 3. Parents vs students

**Who decides what.** Sallie Mae 2026 (same URL): the **student decides which
school 66%** of the time, joint 29%. But **how to pay** is joint 39% / student
35% / **parent 26%**. The two parties own different halves of the same decision
— the student owns _where_, the parent owns _how it gets paid_. A parent-facing
shared cost report is therefore aimed exactly at the half the parent actually
controls; a **non**-money artefact aimed at the parent is aimed at the half they
do not.

**Parents and students want measurably different things.** _Princeton Review,
2026 College Hopes & Worries_ — 24th annual, fielded online Jan-Feb 2026,
**n=9,446 (7,179 students, 2,267 parents)**, all 50 states. Report PDF:
<https://www.princetonreview.com/cms-content/2026-college-hopes-worries-survey-report.pdf>;
hub: <https://www.princetonreview.com/college-rankings/college-hopes-worries>.

| Item                                                       | Students | Parents |
| ---------------------------------------------------------- | -------- | ------- |
| Main consideration: "best overall **fit**"                 | 46%      | **55%** |
| Main consideration: best program for career interests      | **34%**  | 28%     |
| Main consideration: most affordable                        | 13%      | **8%**  |
| Ideal distance from home: **under 250 miles**              | 33%      | **47%** |
| Ideal distance: over 250 miles (all three bands)           | **67%**  | 53%     |
| Biggest concern: won't get into first-choice college       | 27%      | **31%** |
| Health & wellness (incl. mental health) matters "strongly" | **15%**  | 9%      |
| Biggest benefit: better job and higher income              | 41%      | **47%** |
| Toughest part: deciding which colleges to apply to         | 11%      | **16%** |
| "Will college be worth it?" — yes                          | **99%**  | 96%     |

Four things fall out of that table. **(i)** _Fit_, not price and not reputation,
is the stated main consideration for both sides — 48% overall, versus 12% for
"most affordable" and 8% for "best academic reputation". **(ii)** Parents care
about fit _more_ than students do, and about affordability _less_ — the opposite
of the folk model. **(iii)** The distance gap is the sharpest measurable
parent/student conflict (47% vs 33% want under 250 miles), and the report notes
it has held every year since 2007. It matches the Reddit "feeling guilty about
going oos" genre exactly. **(iv)** Students want mental-health information
_more_ intensely than parents (15% "strongly" vs 9%).

Two more from the same report: the top worry has inverted since 2003 — "won't
get in" fell from **52% to 28%**, while "too much debt" rose from **6% to 35%**.
And **"will attend a college I may regret" reached 10%, up from 7% in 2025.** A
new 2026 stressor item puts **information overload** ("winnowing down the list
from a sea of suggestions, rankings, social posts") at **15%**, and sticker
shock at 37% for both sides identically.

Comparison factors families say they would use (same report): **career-center
services 91%** would contribute, **health & wellness services 81%**. In a forced
pick-5 ranking: Academics 96%, Financial Aid 69%, Amenities 69%, **Student Body
/ Campus Culture 61%**, Career Services 57%, City or Town 43%, Health & Wellness
26%.

**Planning gap, not price gap.** Only **58% of families created a plan to cover
all years** before enrolling. Of families **not** confident in a four-year
finish, only **42%** had discussed the financial impact of extended enrolment
(Sallie Mae 2026). The unmet parent job is multi-year and contingency planning,
not the year-one sticker.

**What students are anxious about.** Art & Science studentPOLL Vol 18 Iss 1, May
2025 (n=604 HS seniors, MoE ±4%) —
<https://www.artsci.com/s/studentPOLL-Mental-Emotional-Health-May-2025-6bc4.pdf>.
Mean anxiety about college selection **6.7/10**; **89% moderate-to-high; 43%
rate it 8-10**. Top causes: **meeting application deadlines 53%, uncertainty
about future job prospects 49%, strengthening HS curriculum 49%, cost of
tuition/aid 47%, writing essays 45%, awaiting decisions 44%, application fees
44%, choosing a major/career 42%.** Note that **cost ranks fourth** among
student anxieties. Anxiety is sharply unequal: mean **7.6 for lower-income** vs
**4.7 for higher-income**; **62%** of under-$60K households report high anxiety;
first-gen 7.3 vs 6.5.

**Pressure from parents is the _smallest_ source of student anxiety** in that
survey: parents 36%, teachers/counsellors 32%, friends 28% — below every process
and money item. The intuitive product story "the parent is stressing the kid
out" is not supported.

**What that anxiety makes students want in a school** (same survey): flexible
academic programs 72%, supportive community 67%, generous with aid 65%, **closer
to home 62%**, high acceptance rates 62%, **offers mental health and counselling
services 61%**, lower cost 60%, test-optional 58%, no essays/interviews 58%,
rolling admissions 55%.

**What kills enrolments after they start — and it is not price.** Gallup-Lumina
2025 (fielded Oct 2024, ~14,000 adults) —
<https://www.gallup.com/file/analytics/660188/Gallup-Lumina%20Foundation-State%20of%20Higher%20Education%202025_Report.pdf>:
among enrolled students who considered stopping out in the past six months (32%
did), the top reasons are **emotional stress 49%** and **personal mental health
41%**; students were about **twice as likely to cite mental health or emotional
stress as to cite cost**. Among stopped-out students citing stress, the sources
were **anxiety/depression 74%, loneliness or isolation 60%, school-related
financial matters 59%**. The 2026 report puts **82% of currently enrolled
students** reporting emotional stress at least occasionally.

**Background: confidence in the institution itself is falling.** Gallup:
confidence in higher education **57% (2015) → 48% (2018) → 36% (2023)**
(<https://news.gallup.com/poll/508352/americans-confidence-higher-education-down-sharply.aspx>),
**36% (2024)**
(<https://news.gallup.com/poll/646880/confidence-higher-education-changing.aspx>),
**42% (2025) → 38% (2026)**
(<https://news.gallup.com/poll/712322/confidence-higher-education-slips-back-slightly.aspx>).
Stated reasons for low confidence in 2026: **political agendas 31%, high cost
30%, not preparing students for the workforce 25%.** Two of the top three are
non-money.

---

## 4. The unanswered questions families take to forums — the emotional job

**How this was gathered.** Reddit blocks scripted clients
(`www.reddit.com/*.json` and `api.reddit.com` = 403;
`old.reddit.com/...search.json` returns a 200 HTML interstitial, not JSON;
`api.pushshift.io` = 403). Titles below were read through a public Redlib mirror
(`redlib-2.privadency.com`, listed at
<https://raw.githubusercontent.com/redlib-org/redlib-instances/main/instances.json>)
with canonical reddit.com permalinks, `t=year` (≈ Sep 2025 - Sep 2026). College
Confidential's Discourse JSON API was directly readable. **Niche is 403 behind
Cloudflare — no Niche numbers appear anywhere in this report.**

### 4a. Volume evidence: the "which one, and will it fit" question is bigger than price

College Confidential category topic counts,
<https://talk.collegeconfidential.com/categories.json> (read 2026-09-10):

| Category                     | Topics      |
| ---------------------------- | ----------- |
| college-admissions           | 173,624     |
| what-are-my-chances          | 162,000     |
| **college-search-selection** | **121,469** |
| paying-for-college           | 51,054      |
| sat-and-act                  | 50,155      |
| parents-forum                | 48,153      |
| **transfer-students**        | **43,583**  |
| college-life                 | 35,085      |
| college-essays               | 29,080      |
| college-majors               | 8,684       |
| compare-college-offers       | 702         |

**Search/selection carries ~2.4x the thread volume of paying-for-college**, and
transfer-students alone is ~0.85x it. Caveat in the other direction: on Reddit,
money posts still score highest ("Genuinely HOW are people paying for college",
351 points, <https://www.reddit.com/r/ApplyingToCollege/comments/1ro07z7/>).
Non-money questions are higher **volume**, lower **score**.

### 4b. The recurring non-money question types

1. **"Help me choose between these 2-4 admits."** The single most common shape,
   and r/CollegeAdmissions is almost entirely this. "I have 24 hours to choose
   between Columbia and Vanderbilt full ride" —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1tobqx7/>; "bu vs
   northeastern" —
   <https://www.reddit.com/r/CollegeAdmissions/comments/1s6fpx8/>.
2. **"Does prestige actually matter, or should I pick fit?"** "Nobody chooses
   fit over a T20. Prove me wrong." (141 pts) —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1u6wzb9/>; "Location
   matters a lot more than prestige" —
   <https://www.reddit.com/r/CollegeAdmissions/comments/1utvlf9/>.
3. **Regret, usually post-commit.** "how do i overcome regret from college
   decision" (75) —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1vuyev2/>; "Those Who
   Chose Their State Flagship Over a Dream/Reach School - Was It Worth It?" —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1ss56mc/>; "Has anyone
   ever regretted going to the less expensive college?" (59 posts) —
   <https://talk.collegeconfidential.com/t/has-anyone-ever-regretted-going-to-the-less-expensive-college/3704755>.
4. **Transfer as the escape hatch, for social not financial reasons.**
   "Transferring out of Wesleyan" (150 posts, 4,167 views) —
   <https://talk.collegeconfidential.com/t/transferring-out-of-wesleyan/3700608>;
   "Transferring from Grinnell for social reasons" (85) —
   <https://talk.collegeconfidential.com/t/transferring-from-grinnell-for-social-reasons/3705450>.
5. **"What is this place actually like?"** "Reviewing Every College Town I've
   Visited" (90) —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1ve6a9j/>; "Examples of
   Highly Similar Schools" (88 posts) —
   <https://talk.collegeconfidential.com/t/examples-of-highly-similar-schools/3704370>.
6. **Social-scene fit as a hard filter.** "What colleges should I avoid if I
   don't like Greek life?" (71) —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1qmzp9j/>; "SLACs where
   athletes don't make up more than 20% of the student body" —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1nx8gs6/>.
7. **Loneliness — the regret precursor.** "I was a campus hermit until my senior
   year and it changed everything" (2,984 pts) —
   <https://www.reddit.com/r/college/comments/1nnlbld/>; "I ruined my college
   life." (1,099) — <https://www.reddit.com/r/college/comments/1oz8jq1/>.
8. **Distance from home as an explicit parent-student conflict.** "feeling
   guilty about going oos" (153) —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1sjuofz/>; "Parents
   won't allow me to apply to a full ride scholarship if it means I go out of
   state" — <https://www.reddit.com/r/ApplyingToCollege/comments/1ubcssx/>.
9. **Academic culture: grade deflation, weed-out, competitiveness.** —
   <https://www.reddit.com/r/ApplyingToCollege/comments/1osnhag/>.
10. **Does the major pay?** "Will majoring in journalism be a mistake?" —
    <https://www.reddit.com/r/college/comments/1ocuc1o/>; the parent version,
    "Kid doesn't want a low paying major…" —
    <https://talk.collegeconfidential.com/t/kid-doesn-t-want-a-low-paying-major/3699772>.
11. **Institutional risk (new in this window).** "The college I picked is
    legitimately shutting down." (237) —
    <https://www.reddit.com/r/ApplyingToCollege/comments/1slj1lu/>.

**The emotional job to be done, stated plainly:** _"Reassure me that this
specific choice, between these specific survivors, is not one we will regret —
and that my kid will be OK there."_ It is a **comparison-and-reassurance** job,
not a lookup job. The number of "help me choose" and "did I pick wrong" threads
says families cannot finish the decision alone even after the money is settled.

### 4c. Transfer and stop-out are the measured form of regret

- **13.1% of all continuing and returning undergraduates** in fall 2024 were
  transfer students, up from 11.9% in fall 2020; transfer-in enrolment +4.4%
  year over year (National Student Clearinghouse Research Center, _Transfer
  Enrollment and Pathways, Fall 2024_, published 2025-03-05 —
  <https://nscresearchcenter.org/transfer-enrollment-and-pathways/>).
- **35% of enrolled students considered stopping out** in the prior six months,
  and among them **64% cite emotional stress or mental health — "more than twice
  the percentage who say the cost of their program"**. Item level: emotional
  stress 54%, personal mental health 43%, cost 31%, coursework too difficult
  24%, **"don't feel I belong" 24%**. Scaled to all students, **22% have
  considered stopping out because of mental health or emotional stress — 28% of
  women vs 15% of men.** (Lumina-Gallup, _State of Higher Education 2024_,
  fielded 9 Oct - 16 Nov 2023, n=14,032 —
  <https://www.luminafoundation.org/wp-content/uploads/2024/05/State-of-Higher-Education-2024.pdf>.)
- Same report on climate: **17% report feeling physically unsafe** frequently or
  occasionally, **18% psychologically unsafe**, 18% disrespected, 16%
  discriminated against.
- **No citable "would you choose the same school/major again" percentage was
  obtainable.** The BestColleges major-regret URL 404s and the Gallup poll IDs
  tried 404; with search engines blocked, the live URLs could not be found.
  **Treat regret rate as unmeasured here.**

---

## 5. What a cited-data coach can honestly answer, and what is vibes

I checked availability, not just existence, against the live College Scorecard
API (`https://api.data.gov/ed/collegescorecard/v1/schools?api_key=DEMO_KEY`) and
against the repo.

### Honestly answerable today, data already in the repo

- **Completion.** `completionRate150pct4yrShare` (Scorecard `C150_4`) is already
  a column — `db/src/main/kotlin/ed/unicoach/db/models/College.kt:34`, read at
  `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt:1763`.
- **Distance from home.** `latitude`/`longitude` are already columns
  (`College.kt:28-29`).
- **Size and setting.** `undergradEnrollmentHeadcount` (`UGDS`) and `locale`
  (`LOCALE`) — `College.kt:27,31`.
- **Selectivity.** `admissionRateShare`, `satAverageEquivalentScore`
  (`College.kt:32-33`).
- **What the school actually weighs in admission.** `CollegeAdmissionFactors` /
  `FactorRating` models exist (Common Data Set C7 factors), plus
  `CollegeDeadline`.
- **Which majors exist.** `CollegeProgram` is populated from the Scorecard
  field-of-study file (`CIPCODE`/`CIPDESC`/`CREDLEV`,
  `CollegeScorecardLoader.kt:1787-1789`).
- **Institution-level earnings.** `MD_EARN_WNE_P10` is already ingested and
  lives in the money vocabulary as `median_earnings_10y`
  (`db/schema/0089.extend-money-vocabulary-for-borrowing.sql:35`).

### Honestly answerable, public, but NOT yet ingested (verified live)

- **First-year retention.** `latest.student.retention_rate.four_year.full_time`
  returns 0.9005 for Michigan State, 0.6989 for Alabama A&M. Scorecard variable
  `RET_FT4`, plus pooled/suppressed variants (`RET_FT4_POOLED`, `..._SUPP`) in
  the institution data dictionary
  (<https://collegescorecard.ed.gov/assets/CollegeScorecardDataDictionary.xlsx>).
  **This is the single cheapest non-money addition in the whole report.**
- **Earnings by MAJOR at that school.**
  `latest.programs.cip_4_digit[].earnings...` returns per-CIP medians (verified:
  MSU "Agricultural Business and Management" $34,270 / $48,126 at different
  credential levels). Documentation:
  <https://collegescorecard.ed.gov/assets/FieldOfStudyDataDocumentation.pdf>.
  This directly serves the #2 and #3 stated reasons (job outcomes; major-level
  reputation).
- **Carnegie size-and-setting class** (`CCSIZSET`), **part-time share**,
  **first-generation share** — all verified live on the API.

### Vibes — a coach should refuse or heavily qualify

- **"Will she be happy / find her people?"** No dataset. Any answer is
  invention.
- **"Is the campus safe?"** Clery Act crime data exists at
  <https://ope.ed.gov/campussafety/> — but that URL serves a 2,188-byte
  JavaScript shell with no simple bulk endpoint I could find, and Clery counts
  are notoriously reporting-artefact-driven (a school with better reporting
  looks worse). **Recommend not shipping comparative safety numbers.**
- **"Is the mental-health support good?"** 61% of anxious seniors want this
  (studentPOLL, above) and there is **no federal dataset** on counselling
  capacity. The honest coach can say what services a school _advertises_,
  sourced to the school, and nothing more.
- **"Is the political climate right for us?"** 28% of seniors filter on it
  (studentPOLL) and there is no institution-level dataset. State-law proxies are
  editorialising, not data.
- **Dorms, food, Greek life, "prestige".** No primary data. Vibes.

---

## Implications for unicoach

Ranked by my confidence. Each of the top five is phrased as the question a real
family asks.

1. **"Will she actually finish there — and in four years?"** (highest
   confidence.) Families are 84% confident of a four-year bachelor's; national
   first-year retention is 82% and the selectivity spread runs 96% to 59%. Add
   `RET_FT4` beside the completion rate the repo already carries, and let the
   coach put a school's retention and completion next to the family's own stated
   assumption. Cheap: one Scorecard column, already verified live, on an ingest
   path that already reads that file. It is also the non-money number that most
   changes the _money_ answer — a fifth year is a fifth year of price.

2. **"Does this school actually teach what he wants to do — and do ITS graduates
   in THAT major get jobs?"** (high confidence.) Ranks 2 and 3 in the stated
   list (54.8% and 53.7%) and #1 enrolment motivator in Gallup-Lumina
   (67%/63%/57%). The repo already stores the CIP program census; the
   field-of-study earnings and completion figures sit in a file the loader
   already knows about. This turns "graduates get good jobs" from marketing into
   a cited figure at the major level, where the difference actually lives.

3. **"How far away is it really — and what does 'close to home' cost or save
   us?"** (high confidence.) 78.4% of first-time undergraduates enrol in their
   home state, proximity is the joint-top choice factor in two 2026 surveys
   (39%, 46%), and "live near home" jumped 7 points in the CIRP series between
   2015 and 2019. The repo has lat/long already. Distance is also the one
   non-money variable that is _directly convertible_ to money (flights home,
   in-state tuition, living at home), so it fits the existing product spine
   instead of fighting it.

4. **"What are our real chances here, and what do they actually care about in
   the application?"** (medium-high confidence.) The #1 student anxiety is
   **deadlines (53%)**, not price; #5 is essays (45%); #6 awaiting decisions
   (44%). The repo already models `CollegeAdmissionFactors` (CDS C7) and
   `CollegeDeadline`. A coach that answers "this school rates the essay 'very
   important' and rigor 'considered', and its EA deadline is 1 Nov" removes the
   most-cited anxiety with data it already holds. Note the honest framing:
   report what the school says it weighs, never predict an outcome.

5. **"Is it the right SIZE and kind of place for her?"** (medium confidence.)
   Social and extracurricular reputation is the fastest-rising stated reason in
   fifty years (23.6% in 1983 → 47.8% in 2019) and "size of this college" is a
   stable 35-38%. `UGDS`, `LOCALE` and `CCSIZSET` make an honest, boring,
   citable answer to a question families currently answer from campus-tour
   feelings. Do **not** dress this up as "vibe" — ship it as size, setting, and
   residential share, with the school's own numbers.

6. **Reframe the price tool as a shortlist filter, not a decision tool**
   (medium-high confidence). 79% of families eliminate on cost alone, but only
   35% of final decisions turn on money. The Family Cost Report is doing
   elimination work well; the product gap is the _last_ step, choosing among two
   or three survivors that already passed the money test. A "compare the
   finalists on everything else" surface is the natural next artefact.

7. **Sell the multi-year plan, not the year-one number** (medium confidence).
   Only 58% of families make a plan covering all years, and only 42% of the
   _doubtful_ ones have discussed what a fifth year costs. This is a money
   feature, but it is a **planning** feature rather than a price feature, and it
   is the highest-leverage parent-facing gap in the Sallie Mae data.

8. **Do NOT build campus-safety or mental-health-quality ratings** (medium-high
   confidence in the negative). 61% of anxious seniors want counselling services
   and 49%/41% of stop-outs cite stress and mental health — the _demand_ is real
   and large. But there is no honest comparative dataset: Clery counts punish
   good reporting, and counselling capacity is not federally published. Building
   a rating would violate the product's own figure-honesty commitments. Answer
   the emotional question with coaching language, not numbers.

9. **Do NOT build a political-climate filter** (medium confidence in the
   negative). 28% of seniors use one, so it is tempting; but there is no
   institution-level data, only state-law proxies, and 62% of students say
   schools should stay neutral. Any implementation is the product taking a side
   using someone else's inference. If it is served at all, serve it as the
   student's own stated constraint (a profile fact), never as a computed score.

10. **Do NOT chase rankings** (medium confidence in the negative). Rankings
    peaked at 20.1% "very important" in 2015 and fell to 15.2% by 2019 — rank 14
    of 21. The audience that cares is small and shrinking, and the data is
    proprietary.

**One honest caveat on all of the above.** The ranked CIRP list is from **Fall
2019**; the series has published no newer norms at the URL path used here. The
2024-2026 surveys agree on direction (proximity high, career outcomes high,
mental health rising) but they are not the same instrument. Where the two
disagree — notably on distance — I have trusted the behaviour (78.4% in-state)
over either stated list.
