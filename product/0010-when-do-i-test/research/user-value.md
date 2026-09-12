# 0010 research — user value and competitors

## Method

Serper web search was **unavailable** in this session (no API key configured),
so I searched via DuckDuckGo's HTML endpoint and then fetched primary pages
directly with `httpx` (College Board, ASCA, University of Alabama, ASU,
FairTest, CollegeVine, Scoir, MaiaLearning). Pages that refused automated
fetches are named as such: **NACAC** (403), **PowerSchool/Naviance** (Incapsula
block), **Common App member-support** (JS-only page), **Reddit** (403). Claims
that would have rested on those sources are marked as inference, not evidence.
Repo claims come from reading `db/schema/*.sql` in this checkout (read-only).
Dates below are as published on the pages fetched today.

## 1. Where the advice gap actually is

The gap is not "what is the SAT". It is **nobody with time to do the arithmetic
for this specific student**.

- The US national student-to-school-counselor ratio was **372:1 in 2024–25**;
  ASCA recommends 250:1, and only four states (CO, HI, NH, VT) met it
  ([ASCA](https://www.schoolcounselor.org/About-School-Counseling/School-Counselor-Roles-Ratios)).
  That counselor also does scheduling, crisis and mental-health work. I could
  not fetch NACAC's figure for the share of a counselor's time spent on college
  advising (site returned 403), so I do not cite one.
- The question is time-critical and **date-shaped**. SAT weekend dates are fixed
  and few (7 per cycle: Aug 22, Sep 12, Oct 3, Nov 7, Dec 5 2026, then Mar 6,
  May 1, Jun 5 2027), registration closes **2–3 weeks** before, and
  device-lending requests close **30 days** before
  ([College Board](https://satsuite.collegeboard.org/sat/dates-deadlines)).
- Scores land **~13 days after test day** (Nov 7 → Nov 20; Dec 5 → Dec 18)
  ([College Board](https://satsuite.collegeboard.org/scores/score-release-dates)).
  This lag is the whole game: a family that "plans to test in December" for a
  December 4 scholarship deadline has already lost, and nothing on the public
  web tells them that unless they subtract the two tables themselves.
- Testing is now mostly optional but not irrelevant: for 2026–27 applications,
  FairTest records **1,862 test-optional and 78 test-free colleges, with 151
  still requiring scores** out of 2,182 verified
  ([FairTest](https://fairtest.org/wp-content/uploads/2026TOList/test-optional-list.html?v=1)).
  So the honest answer is genuinely _"it depends on your list"_ — which is
  precisely the answer a generic article cannot give.

## 2. Competitor scan

- **College Board BigFuture** — a grade-level checklist ("11th grade: take the
  SAT") ([BigFuture](https://bigfuture.collegeboard.org/checklist)). Correct,
  generic, not personalised, not list-aware, no money link.
- **Khan Academy / Bluebook** — practice and delivery, not planning. They tell
  you how to prepare, never _when_ relative to your deadlines
  ([College Board practice](https://satsuite.collegeboard.org/sat/dates-deadlines)).
- **CollegeVine** — has the exact article. The top-ranked one is dated
  **September 30, 2016** and still quotes "November 5th... register by October
  7th" and "January 21, 2017"
  ([CollegeVine](https://blog.collegevine.com/when-should-i-take-the-sat-or-act/)).
  That is a nine-year-stale answer being served today. Structural weakness, not
  a one-off.
- **Naviance (PowerSchool), Scoir, MaiaLearning** — school/district-licensed
  platforms sold to counselors; families get them only if the school buys them
  ([Scoir](https://www.scoir.com/),
  [MaiaLearning](https://www.maialearning.com/)). Naviance's page refused
  automated fetch. Functionally these are score _record-keeping_, scattergrams
  and task lists
  ([Scoir scattergrams](https://help.scoir.com/article/vs55bpd5mw-viewing-scattergrams)),
  not a planner that back-solves a date from a deadline.
- **Common App** — a place to report scores at application time, and it says
  which of _its_ members require them; it has no opinion about when you test
  ([Common App testing resource](https://www.commonapp.org/static/d5fca99aebaebd5bee20bb26d2edc802/Resource_FY_Testing_ENG_2025.06.25_0.pdf)).
- **ChatGPT / Google** — give the generic "spring of junior year, retake in fall
  of senior year" answer well and free. Inference (not measured here): they do
  not know today's date reliably in-product, do not know the student's
  graduation date, and cannot see the list.

**The differentiated space** is therefore: _this student, this graduation date,
this list, this money picture, arithmetic done for them._

## 3. What only unicoach can say — and it is a MONEY answer

Test scores are not only an admissions input; at many public flagships they are
**a price**. University of Alabama publishes an automatic merit grid where a
score band is dollars per year
([UA, out-of-state/international, 2027 entry](https://afford.ua.edu/scholarships/oos-international-freshman/)):

| SAT       | Award/yr |
| --------- | -------- |
| 1200–1250 | $6,000   |
| 1260–1290 | $8,000   |
| 1300–1320 | $10,000  |
| 1330–1350 | $15,000  |
| 1360–1410 | $24,000  |
| 1420–1600 | $28,000  |

Three facts on that same page turn "when do I test?" into a four-year,
five-figure question:

1. **"Students must submit test scores to be eligible for automatic
   scholarships."** Test-optional for admission ≠ test-optional for money.
2. **"UA does not use superscores for scholarship purposes"** — the grid reads a
   _single sitting_. Generic "just superscore it" advice is wrong here.
3. **"Application deadline and official score reports due by December 4"** (Fall
   2027 entry). Combine with score release: the **Dec 5, 2026 SAT is too late**
   (scores Dec 18); **Nov 7, 2026 is the last usable sitting**, and its
   registration closed **Oct 23, 2026**. A 10-point miss (1250 vs 1260) is
   $2,000/yr — $8,000 over four years — and the retake window is a date the
   family can miss silently.

ASU works the same way: award depends on GPA + score at admission, scholarships
have **priority dates**, and scores must be sent by the testing agency
([ASU](https://tuition.asu.edu/new-american-university)).

unicoach already holds the joins this needs: `students` graduation date,
`college_deadlines` (round/cycle/month/day), `college_merit_aid` (CDS H2A),
`college_admission_factors.test_scores` (CDS C7 rating), and
`colleges.sat_average_equivalent_score`. What is missing is (a) today's date and
the graduation date in the prompt, and (b) SAT date/deadline/score-release
tables and per-school score-gated-merit policy.

## 4. Risk of a wrong or stale answer

A wrong testing answer is **irreversible on a calendar**: a missed registration
deadline cannot be appealed, and a missed scholarship deadline costs real money
(UA: "considered on a funds available basis"). Staleness is the dominant failure
mode — CollegeVine's live 2016 article is the proof. Competitors hedge by
staying generic (BigFuture checklists) or by never advising at all (Scoir,
Common App). unicoach's hedging convention (brief 0008) applies directly:
publish the date, the cycle, and the source URL with every claim, and refuse to
answer a per-school gate when the stored cycle is older than the student's.

## Implications

1. **Ship the cheap half first, immediately**: inject today's date and the
   student's graduation date/grade into the coach system prompt. Without it the
   coach cannot even say "you are a junior; the next SAT is in 6 weeks."
   Prompt-level, low risk, unblocks everything else.
2. **Frame the feature as MONEY, not testing.** The first-class version is
   "which of your schools turn a score into dollars, and what is the last date
   you can still hit it" — that is unicoach's promise, not a test-prep pivot.
3. **Model three date facts, not a curriculum**: SAT/ACT test date →
   registration deadline → score-release date; plus per-school
   score-report-due-by dates from `college_deadlines`. The answer is
   subtraction, not judgement.
4. **Do not build test prep or score prediction.** Khan Academy is free and
   better; chancing engines are a crowded, low-trust market.
5. **Store test policy per (college, cycle) with a source URL, and refuse when
   stale.** The competitor failure here is staleness; make freshness a visible
   product feature.
6. **Recommendation: first-class feature, but scoped as a money-deadline
   engine.** Gate it behind the prompt-level date fix (item 1), which should
   land on its own regardless.
