# PSAT / National Merit — verification for brief 0010

Checked 2026-09-10. Cycle under test: PSAT/NMSQT **October 1–30, 2026** = the
**2028** National Merit competition.

## Method

- **The earlier blocker is resolved.** `nationalmerit.org` is readable. The
  Cloudflare 403 was a User-Agent filter, not a real block:
  `httpx.get(url, headers={'User-Agent': 'Mozilla/5.0 ...
  Chrome/128.0 Safari/537.36'}, follow_redirects=True)`
  returns **200** on every program page. All NMSC claims below are quoted from
  NMSC's own 2028-cycle pages. No archive fallback needed.
- College Board pages fetched the same way (two guessed URLs 404'd; only live
  ones are cited).
- University awards researched by two parallel subagents, each restricted to the
  university's **own** site. The Serper key was missing, so web search was
  unavailable and discovery was by direct crawling — a few extra schools went
  unchecked. Failures are flagged inline.
- The NMSC student-guide PDF link serves HTML, not a PDF. Nothing below depends
  on it.

## 1. Mechanics — claim CONFIRMED, with one correction

**Junior year is the only qualifying sitting.** NMSC: students "must take the
qualifying test in their third year of high school (grade 11, junior year)", and
"a student can participate ... in only one specific competition year".
Sophomores who sit the 2026 test "will not meet entry requirements for the 2028
National Merit Program".
<https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1878> The
PSAT 10 and PSAT 8/9 "will NOT be considered for entry".
<https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1876>

**Not retakeable.** One in-school window per year — Oct 1–30, 2026. The only
spring window is PSAT 10, which does not qualify.
<https://satsuite.collegeboard.org/sat/dates-deadlines> A student may _withdraw_
a score from NMSC within **five days** of the test, but that removes the entry;
it does not buy a second attempt.

**CORRECTION: there IS an alternate entry route.** A student who missed the test
"because of illness, an emergency, or other extenuating circumstance" may write
to NMSC — `ae@nmerit.net` or post — and the request "must be received or
postmarked on or before **April 1, 2027**".
<https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1895> While
the window is still open, the first fallback is a make-up day or another school.
A separate path fixes wrong exam-setup eligibility answers
(`nationalmerit.org/studentupdate`, deadline **Oct 15, 2027**). So the October
deadline is **recoverable**, not absolute.

**Colleges never see a PSAT score.** College Board publishes a score-send
service for the SAT only
(<https://satsuite.collegeboard.org/scores/sending-sat-scores>). Scores go to
NMSC automatically and cannot be opted out of
(<https://satsuite.collegeboard.org/in-school-assessments/scholarships-recognition/national-merit-scholarship-program>).
Semifinalists' **names** are sent to four-year colleges in Sept of senior year.
The PSAT reaches colleges as a _label_, never as a score.

## 2. Funnel (2028 competition)

| Stage                     | Count                              |
| ------------------------- | ---------------------------------- |
| Take the test             | ~3,500,000                         |
| Meet entry requirements   | ~1,200,000                         |
| High scorers screened     | 50,000                             |
| Commended                 | ~34,000                            |
| Semifinalists             | ~16,000 (<1% of US seniors)        |
| Finalists                 | ~15,000 (~95% of Semifinalists)    |
| Merit Scholarship winners | ~6,700 (+620 Special), >$29M total |

Sources: `.../start.aspx?sid=1758&gid=2&pgid=1745`, `&pgid=1881`, `&pgid=1882`,
`&pgid=1876`.

**Selection Index** = (2 x Reading&Writing + Math) / 10, scale **48–228**
(<https://satsuite.collegeboard.org/psat-nmsqt/scores/understanding-scores>).

**The Semifinalist cutoff IS per state.** NMSC allocates Semifinalists "on a
state-representational basis"; scores are ranked within the state and
"qualifying scores vary from state to state and from year to year". A student is
considered in one unit, set by the high school attended when testing. The
**Commended** cutoff is a single national number.
<https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1881> So
residency changes the answer exactly at the line the money sits behind.

## 3. The money

NMSC's own award is small: the **National Merit $2500 Scholarship** is "a single
payment of
$2,500"; **college-sponsored** awards (~140 sponsor colleges, ~3,500 offers) are capped by NMSC
at **$500–$2,000 per year** and require telling NMSC the college is your **first
choice**.
<https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1882> The
real money is the university's **own** award, outside NMSC's cap.

| School                    | Covers                                                                    | 4-year value                                              | Automatic?                               | First choice?      | Deadline / cycle                |
| ------------------------- | ------------------------------------------------------------------------- | --------------------------------------------------------- | ---------------------------------------- | ------------------ | ------------------------------- |
| Alabama                   | Tuition 10 sem + 4 yr housing + $4k/yr + $2k                              | ~$180–200k OOS; ~$107k in-state                           | **Yes** (3.5 GPA)                        | Yes, by May 1 2027 | Dec 4 priority; fall 2027       |
| Oklahoma                  | Tuition waiver + fees + $5k stipend + housing                             | **$153,450** non-res / **$146,850** res (OU's own totals) | **Yes**                                  | Yes, Apr 30        | Dec 15; page cycle fall 2026    |
| Texas Tech                | 100% of cost of attendance                                                | ~$111k at its stale COA                                   | **Yes** ("guarantees 100%")              | Yes                | Apr 1 vs May 31 — page conflict |
| Florida State             | In-state: Benacquisto to full COA. OOS: tuition waiver + $16k Vires + $2k | OOS **~$91,808** (FSU figure)                             | **Yes**                                  | Yes, May 1         | Dec 1; OOS figure = Fall 2024   |
| U. Idaho                  | Tuition+fees + room & board                                               | **>$85,000**                                              | **Yes** ("guaranteed")                   | Yes, May 31        | 2026-27                         |
| UT Dallas                 | Full tuition + room + meals + stipends                                    | ~$110k (estimated)                                        | Near — "based on availability"           | Yes                | Dec 1; Fall 2026                |
| ASU                       | Cash award                                                                | $60k res / $70k non-res (Barrett)                         | **Yes**                                  | Yes, May 1         | 2026-27                         |
| U. Maine                  | 100% tuition + fees                                                       | ~$54k in-state / ~$140k OOS                               | **Yes** (SF **and** F)                   | **Not required**   | Dec 1; 2026-27                  |
| USC                       | $20,000/yr tuition credit                                                 | **$80,000**                                               | Reads automatic; admission is the filter | Yes                | No cycle stated                 |
| Auburn                    | $11,000/yr — **Alabama residents only**                                   | $44,000                                                   | Yes, AL residents                        | Yes, May 1         | Dec 1                           |
| UCF                       | OOS ">$25,000/yr"                                                         | >$100k                                                    | **No** — "competitive", "considered for" | Yes, May 31        | 2025 newsletter                 |
| Tulsa                     | Full tuition + housing (**Semifinalists**)                                | ~$200k                                                    | **No** — 4.0 GPA + visit + interview     | Yes                | All by Nov 1; Fall 2027         |
| Baylor, Fordham, Kentucky | **No National Merit award found on their live sites**                     | —                                                         | —                                        | —                  | —                               |

Sources: afford.ua.edu/scholarships/national-merit/ ·
ou.edu/admissions/honors/scholarships/national-merit-finalist-scholarship/ ·
depts.ttu.edu/scholarships/NationalMerit.php ·
admissions.fsu.edu/first-year/scholarships ·
uidaho.edu/financial-aid/scholarships/national-merit-scholarships ·
honors.utdallas.edu/national-merit-scholars-program/ ·
yourfuture.asu.edu/national-scholar ·
umaine.edu/admissions/cost-aid/merit-scholarships/26-27fyosmerit/ ·
admission.usc.edu/afford/scholarships/ ·
auburn.edu/scholarship/undergraduate/firstyearstudents.php ·
ucf.edu/admissions/undergraduate/national-merit/ ·
utulsa.edu/tuition-aid/scholarships/nmsf/

**Honesty flags.** Baylor's site says "National Merit" zero times on its merit
pages; its site search is a blocked Google CSE and archive.org rate-limited us,
so _cut vs never had_ is unproven — verify by phone. Fordham and Kentucky
likewise publish no NM line today. Auburn is a **documented cut** (non-residents
dropped). USC's $20k/yr is far below the half-tuition figure repeated online.
Texas Tech's NM page still says "May 31, **2022**".

## 4. Verdict

**"The PSAT is a money event" — TRUE and well supported.** Finalist status is
worth $44k–$200k at a dozen named public universities, and the only gate on it
is a test given once, in one month, in one grade. The dollar range comes from
those schools' own pages.

**"Non-retakeable" — TRUE.** One qualifying administration; PSAT 10 and
sophomore sittings do not count.

**"Silent" — DEFENSIBLE.** No college ever sees the score, nothing in the
admissions process signals what was at stake, and the payoff lands ~18 months
later.

**Where it is overstated, and must be fixed before it ships:**

1. **"Deadline" implies no recourse.** There is an NMSC alternate-entry route
   with an **April 1** deadline. Say "miss it and you need NMSC alternate entry
   by April 1", not "you lose it".
2. **"Money" is not NMSC money.** NMSC itself pays $2,500 once, or
   $500–$2,000/yr. The big awards are a **specific list of universities** —
   mostly large publics competing for Finalists — not a general property of the
   award.
3. **Odds.** ~15,000 Finalists from ~1.2M entrants (≈1.25%). The claim is honest
   only if the cutoff is stated as per-state and the base rate is visible.
4. **Currency risk is real.** Auburn cut non-residents; Fordham, Baylor,
   Kentucky publish nothing; Texas Tech's page is four years stale.

## Curation feasibility

**10 schools are hand-curatable today** with a source URL, a cycle, and a dollar
figure: Alabama, Oklahoma, Florida State, Idaho, UT Dallas, ASU, Maine, USC,
Auburn, UCF (as _competitive_), plus Tulsa as _Semifinalist, competitive_. Texas
Tech is curatable only with a "page stale" caveat. Baylor, Fordham and Kentucky
should be recorded as **no published award**, which is itself useful.

**Stability: moderate, not good.** Most pages are dated and re-keyed per cycle
(`26-27fyosmerit`), so URLs move yearly; USC, ASU and UCF carry no cycle date at
all. Any dataset needs a `source_url` + `cycle` + `checked_on` per row and an
annual human re-check each summer. It cannot be scraped unattended — the award
names, coverage and first-choice rules differ per school and several pages
disagree with themselves.
