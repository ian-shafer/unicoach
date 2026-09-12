# Testing landscape — when do I need to take the SAT?

_Research note for product brief 0010. Written 2026-09-10._

## Method

All claims below were gathered by direct HTTP fetch of primary sources (College
Board, ACT, university admissions pages, university news offices) plus two
reputable secondary sources where no primary aggregate exists. The Serper
web-search tool was **not configured** in this session, so discovery used
DuckDuckGo's HTML endpoint; several guessed URLs 404'd and were replaced by
searched ones. Two sources could **not** be read: nationalmerit.org (Cloudflare
challenge on the interior pages and a 403 on the program-guide PDF) and Brown's
and UT Austin's own testing-policy pages (JS-rendered / restructured) — for
those I cite the university news release or College Board instead and say so.
State-by-state mandate lists come from PrepScholar (secondary, updated Oct
2025); treat the exact state counts as approximate.

## 1. When a junior should actually test

ACT's own guidance: most students test in junior or senior year, and a typical
path is **fall of junior year (baseline) → spring of junior year (retake) → fall
of senior year (final)**
([act.org](https://www.act.org/content/act/en/products-and-services/the-act/scores/when-to-take-the-act.html)).
That matches the standing counselor rule of thumb: sit the first real test by
spring of 11th grade so at least one retake fits before applications.

**SAT 2026–27 (test date → registration deadline)**: Aug 22 → Aug 7; Sep 12 →
Aug 28; Oct 3 → Sep 18; Nov 7 → Oct 23; Dec 5 → Nov 20; Mar 6 2027 → Feb 19; May
1 → Apr 16; Jun 5 → May 21
([College Board](https://satsuite.collegeboard.org/sat/dates-deadlines)). **SAT
score release** is 2–4 weeks: Oct 3 → **Oct 16**, Nov 7 → **Nov 20**, Dec 5 →
**Dec 18**
([College Board](https://satsuite.collegeboard.org/scores/score-release-dates)).

**ACT 2026–27 (date → reg deadline → score release)**: Sep 19 → Aug 14 → Oct 6;
Oct 17 → Sep 11 → Oct 27; Dec 12 → Nov 6 → Dec 22; Feb 27 2027 → Jan 22 → Mar
16; Apr 10 → Mar 5 → Apr 20; Jun 12 → May 7 → Jun 23; Jul 10 → Jun 4 → Jul 20
([ACT](https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html)).

**The deadline interaction is the whole answer.** For a Nov 1 ED/EA deadline,
the last comfortable sittings are **SAT Oct 3** (scores Oct 16) and **ACT Oct
17** (scores Oct 27). The Nov 7 SAT releases Nov 20 — after the deadline — yet
Harvard explicitly says REA applicants may still use "the November series,"
while asking for scores by end of October
([Harvard](https://college.harvard.edu/admissions/apply/first-year-applicants)).
For Regular Decision (Jan 1–15), the **Dec 5 SAT** (Dec 18) and **Dec 12 ACT**
(Dec 22) still land in time. Registration deadlines fall ~2–3 weeks before test
day, so the real action date for a Nov 1 applicant is mid-September, not
October.

## 2. PSAT/NMSQT

The PSAT/NMSQT is administered in school; students do not register themselves.
The fall 2026 in-school window is **October 1–30, 2026** (spring window Mar
1–Apr 30, 2027)
([College Board](https://satsuite.collegeboard.org/sat/dates-deadlines)). It is
the qualifying test for the National Merit Scholarship Program, screening ~1.3M
entrants a year
([College Board](https://satsuite.collegeboard.org/help-center/how-does-student-qualify-national-merit-scholarship-program)),
and scores are shared with NMSC with no opt-out
([College Board](https://satsuite.collegeboard.org/in-school-assessments/scholarships-recognition/national-merit-scholarship-program)).
The load-bearing fact a coach must not miss: **only the junior-year sitting
counts** for National Merit; a sophomore PSAT does not. I could not open NMSC's
own page to re-confirm the 11th-grade entry rule (Cloudflare), so treat that
specific sentence as well-established practice rather than a cited primary fact.

## 3. Test-required vs test-optional in the 2026–27 cycle

Required today, per each school's own page: **MIT**
([mitadmissions.org](https://mitadmissions.org/apply/firstyear/tests-scores/)),
**Yale**
([admissions.yale.edu](https://admissions.yale.edu/standardized-testing)),
**Dartmouth**
([admissions.dartmouth.edu](https://admissions.dartmouth.edu/apply/testing-policy)),
**Stanford** ("ACT or SAT scores are required")
([admission.stanford.edu](https://admission.stanford.edu/apply/freshman/testing.html)),
**Caltech** (required for fall 2027 applicants; subscores, not composite)
([admissions.caltech.edu](https://www.admissions.caltech.edu/apply/first-year-applicants/standardized-tests/)),
and **Harvard**
([college.harvard.edu](https://college.harvard.edu/admissions/apply/first-year-applicants)).
**UT Austin** reinstated for Fall 2025 entry
([UT News](https://news.utexas.edu/2024/03/11/ut-austin-reinstates-standardized-test-scores-in-admissions/)).
**Brown** reinstated in March 2024
([Brown](https://www.brown.edu/news/2024-03-05/admissions)) — its live policy
page did not load for me.

But the aggregate cuts the other way. FairTest's 2026–27 survey of 2,247
policies finds **7% require (161 colleges), 86% test-optional, 4% test-free**;
only 11 institutions added a requirement this cycle while 10 dropped one — a net
change of **one**. Columbia and Princeton stay optional this cycle and switch
next; Duke and UChicago say optional indefinitely
([Inside Higher Ed, Aug 28 2026](https://www.insidehighered.com/news/admissions/traditional-age/2026/08/28/90-colleges-dont-require-test-scores-2026-27)).
The **UC system remains test-free** but is now formally reconsidering, under
pressure from 1,400+ faculty; the decision rests with the Regents and is not
expected before 2027
([LA Times, Jun 11 2026](https://www.latimes.com/california/story/2026-06-11/uc-sat-act-test-requirement-admissions-reconsider)).

**Volatility verdict:** the _aggregate_ is stable; the _top ~50_ is where policy
flips, and flips are announced 12–18 months ahead and are per-college. Encoding
a global rule ("tests are back" / "tests are optional") will be wrong for most
students. Encode **per-college, per-cycle policy with an as-of date**.

## 4. Score mechanics a coach must get right

- **Score Choice** lets a student choose _by test date_ which SAT sittings to
  send — but it does **not** apply to the free score sends selected at
  registration, and some colleges require all scores
  ([College Board](https://satsuite.collegeboard.org/scores/sending-sat-scores/additional/score-choice)).
  Advising "just send your best" while four free sends are already queued is a
  real error.
- **Superscoring is the college's policy, not the testing agency's.** Dartmouth
  superscores automatically across dates and formats; Yale accepts self-reported
  superscores but forbids recomputing an ACT composite across the old
  required-Science and new optional-Science formats
  ([Yale](https://admissions.yale.edu/standardized-testing)).
- **Self-reporting is widely accepted** (Yale, Stanford, Dartmouth); Stanford
  reserves the right to revoke an offer if official scores disagree
  ([Stanford](https://admission.stanford.edu/apply/freshman/testing.html)).
- **Fee waivers**: one College Board code per lifetime, requested via counselor
  or directly, ideally 1–2 weeks before the registration deadline
  ([College Board](https://satsuite.collegeboard.org/sat/registration/fee-waivers));
  with a waiver a student can send **unlimited score reports free**
  ([College Board](https://satsuite.collegeboard.org/help-center/can-i-use-fee-waiver-sending-my-score)).
  ACT runs its own separate waiver program; ACT base fee is $70 (+$25 Writing,
  +$5 Science)
  ([ACT](https://www.act.org/content/act/en/products-and-services/the-act/registration/fees/fee-waivers.html)).

## 5. "Your school already schedules it"

For many students the honest answer is that the test is already on the school
calendar. Roughly **12 states require every junior to take the ACT** (AL, AZ,
HI, IL, MS, MT, NE, NV, NC, SD, WI, WY) with ~21 states offering it free to some
or all juniors, and OH, SC, TN let the student or district pick SAT or ACT
([PrepScholar, Oct 2025](https://blog.prepscholar.com/which-states-require-the-act-full-list-and-advice)).
About **14 states plus DC** contract SAT School Day (CO, CT, DE, ID, IN, KY, MI,
NH, NM, RI, WV among them)
([PrepScholar, Oct 2025](https://blog.prepscholar.com/which-states-require-the-sat)).
The College Board in-school windows are Oct 1–30, 2026 and Mar 1–Apr 30, 2027;
students do not register themselves
([College Board](https://satsuite.collegeboard.org/sat/dates-deadlines)). These
are secondary counts and change yearly — verify against the state DOE before
asserting a specific state.

## Does it change for a _college_ junior?

Materially, yes. A college junior is on a grad/professional timeline: LSAT
sittings run roughly monthly with published score-release dates
([LSAC](https://www.lsac.org/lsat/lsat-dates-deadlines-score-release-dates));
the GRE and GMAT are near-continuous, on-demand appointments rather than seven
fixed Saturdays. The correct advice shifts from "which Saturday" to "test ~12
months before your application deadline." **"Junior" is ambiguous, and the coach
must disambiguate it before answering.** unicoach's stored graduation date
resolves this for free.

## Implications for unicoach

1. **Inject today's date and the student's graduation date into the system
   prompt.** Both already exist (`students`); without them the coach cannot
   compute grade level, cannot pick the right cycle, and cannot say "register by
   Sept 18." This is the cheapest, highest-leverage change in the brief.
2. **Ship a maintained test-calendar dataset**, not prose in a prompt. Rows:
   test, date, registration deadline, late deadline, score-release date, cycle.
   Two agencies × ~7–8 dates × 2 cycles is ~30 rows and must be refreshed
   yearly. An LLM hallucinating a registration deadline is a shipped defect with
   a real cost to a family.
3. **Model test policy per college, per cycle, with an as-of date** — never as a
   global rule. The Common Data Set already carries testing-policy fields, so
   this rides the existing CDS ingest that powers the C7 grid and deadlines.
4. **Compute backwards from the college list's real deadlines.** The
   decision-useful output is "your earliest ED deadline is Nov 1 at X, which is
   required-test; last usable sittings are SAT Oct 3 / ACT Oct 17; register by
   Sep 18 / Sep 11."
5. **Ask for state and school-day status once.** For a large share of students
   the first sitting is free and already scheduled; telling them to pay $68 for
   a Saturday they didn't need is a trust-losing answer.
6. **Encode the four score mechanics as guardrails** (Score Choice ≠ free sends,
   superscore is per-college, self-report vs official, fee waiver = unlimited
   free sends). These are the facts the model is most likely to get subtly
   wrong.
