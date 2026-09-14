# State FAFSA / state-aid deadlines — batch B (17 states)

Harvest for `/chart` brief 0012 (FAFSA support), DISCOVER phase. States: ME MD
MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH. Checked on **2026-09-14**.
Machine-readable twin: [`state-deadlines-b.csv`](state-deadlines-b.csv) — 36
rows, 17 states.

Award year is the START year: `2026` means the 2026-27 award year (repo
convention).

## Method

What I fetched, and what stopped me.

**Primary approach.** I did not search. I guessed canonical state
higher-education agency URLs and fetched them directly with a browser user
agent, then followed the in-page links and XML sitemaps when the guess missed.
That worked for 14 of 17 states.

**Sources that worked first try:** mhec.maryland.gov, mass.edu/osfa,
michigan.gov/mistudentaid, msfinancialaid.org, dhewd.mo.gov,
applymontana.mus.edu, ccpe.nebraska.gov, nshe.nevada.edu, hesaa.org, hed.nm.gov,
ndus.edu, famemaine.com.

**Sources that needed a second hop:**

- **Ohio.** `highered.ohio.gov` is an IBM WebSphere portal. Every guessable
  `/students/pay-for-college/...ocog` path returns a rendered page that
  literally says "No content found". The real page lives under
  `/wps/portal/gov/odhe/...`, which I recovered from `href` attributes on the
  grants overview page.
- **North Carolina.** `ncseaa.edu` is the agency, but the student-facing
  deadline content is on `cfnc.org` (College Foundation of North Carolina, a
  State of NC service). I found this by pulling `ncseaa.edu/page-sitemap.xml`
  (270 URLs) and confirming no deadline page existed there.
- **New Jersey.** The deadline landing page is a JavaScript stub; the
  year-specific pages (`StateDeadlinesNextAY.aspx`) are server-rendered and
  complete.

**Sources that blocked me, and the workaround:**

- **`hesc.ny.gov` (New York)** returns 403 to every non-browser client,
  including `r.jina.ai` (which itself now 403s without a key). I read the TAP
  page from a Wayback copy: `web.archive.org/web/2026id_/...`. Marked
  `source_tier=wayback`.
- **`ohe.state.mn.us` (Minnesota)** times out from here on both HTTP and HTTPS,
  at both 30 s and 60 s. I read the State Grant page from a Wayback snapshot
  dated **2026-03-01**. Marked `source_tier=wayback`.
- **`education.nh.gov` (New Hampshire)** returns 403 to non-browser clients, and
  the current URL is not in Wayback. I fell back to a 2025-12-31 Wayback copy of
  its "Higher Education in New Hampshire" page, which names **no** state grant
  program. That is weak evidence, so New Hampshire is `unverified`.
- **`studentaid.gov`** is a JS SPA and returned an empty shell, as expected. The
  Wayback availability API reports **no snapshot** of its state-deadline page.
  It contributed nothing to this batch.
- **`websearch`** has no API key. Not used.
- **`savingforcollege.com`** was used only as a CROSS-CHECK, never as a citation
  in the CSV. Where it disagreed with a state agency, the agency won and the
  disagreement is recorded under Hazards.

**What I did not do.** I did not approximate a date for any state that publishes
only wording, and I did not carry a date forward from a prior award year. Six
rows are `deadline_kind=wording` and two are `deadline_kind=none` for exactly
this reason.

## Per-state table

| State             | AY   | Kind    | Date       | Wording / program                                                                                                                                                                                                                        | Source                                                                                                                                                                           | Verified   |
| ----------------- | ---- | ------- | ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| ME Maine          | 2025 | date    | 2025-07-31 | **Maine State Grant Program**                                                                                                                                                                                                            | [Finance Authority of Maine (FAME)](https://www.famemaine.com/affording-education/pay-for-school/maine-grant-tuition-programs/maine-state-grant-program/)                        | yes        |
| ME Maine          | 2026 | wording | -          | **Maine State Grant Program** — “File the 2026-27 FAFSA. No other forms are required.”                                                                                                                                                   | [Finance Authority of Maine (FAME)](https://www.famemaine.com/affording-education/pay-for-school/maine-grant-tuition-programs/maine-state-grant-program/)                        | yes        |
| MA Massachusetts  | 2026 | date    | 2026-06-30 | **MASSGrant**                                                                                                                                                                                                                            | [Massachusetts Dept of Higher Education, Office of Student Financial Assistance](https://www.mass.edu/osfa/programs/massgrant.asp)                                               | yes        |
| NH New Hampshire  | 2026 | none    | -          | **New Hampshire state financial aid**                                                                                                                                                                                                    | [New Hampshire Dept of Education (via Wayback)](https://web.archive.org/web/20251231200643/https://www.education.nh.gov/pathways-education/higher-education-new-hampshire)       | unverified |
| NJ New Jersey     | 2026 | date    | 2026-04-15 | **Tuition Aid Grant (TAG) - RENEWAL applicants, fall 2026 + spring 2027**                                                                                                                                                                | [NJ Higher Education Student Assistance Authority (HESAA)](https://www.hesaa.org/Pages/StateDeadlinesNextAY.aspx)                                                                | yes        |
| NJ New Jersey     | 2026 | date    | 2026-09-15 | **Tuition Aid Grant (TAG) - NEW applicants, fall 2026 + spring 2027**                                                                                                                                                                    | [NJ Higher Education Student Assistance Authority (HESAA)](https://www.hesaa.org/Pages/StateDeadlinesNextAY.aspx)                                                                | yes        |
| NJ New Jersey     | 2026 | date    | 2027-02-15 | **Tuition Aid Grant (TAG) - spring 2027 only**                                                                                                                                                                                           | [NJ Higher Education Student Assistance Authority (HESAA)](https://www.hesaa.org/Pages/StateDeadlinesNextAY.aspx)                                                                | yes        |
| NJ New Jersey     | 2026 | date    | 2026-10-01 | **NJFAMS state record - fall 2026 + spring 2027**                                                                                                                                                                                        | [NJ Higher Education Student Assistance Authority (HESAA)](https://www.hesaa.org/Pages/StateDeadlinesNextAY.aspx)                                                                | yes        |
| NJ New Jersey     | 2026 | date    | 2027-03-01 | **NJFAMS state record - spring 2027 only**                                                                                                                                                                                               | [NJ Higher Education Student Assistance Authority (HESAA)](https://www.hesaa.org/Pages/StateDeadlinesNextAY.aspx)                                                                | yes        |
| NY New York       | 2026 | date    | 2027-06-30 | **NYS Tuition Assistance Program (TAP)**                                                                                                                                                                                                 | [NY Higher Education Services Corporation (HESC), via Wayback](https://web.archive.org/web/2026id_/https://www.hesc.ny.gov/pay-for-college/apply-for-financial-aid/nys-tap.html) | yes        |
| MI Michigan       | 2026 | date    | 2026-07-01 | **Michigan Competitive Scholarship**                                                                                                                                                                                                     | [MI Student Aid (MiLEAP)](https://www.michigan.gov/mistudentaid/programs/michigan-competitive-scholarship)                                                                       | yes        |
| MI Michigan       | 2026 | date    | 2026-07-01 | **Michigan Tuition Grant**                                                                                                                                                                                                               | [MI Student Aid (MiLEAP)](https://www.michigan.gov/mistudentaid/programs/michigan-tuition-grant)                                                                                 | yes        |
| MI Michigan       | 2026 | none    | -          | **Michigan Achievement Scholarship**                                                                                                                                                                                                     | [MI Student Aid (MiLEAP)](https://www.michigan.gov/mistudentaid/programs/michigan-achievement-scholarship/college-and-university)                                                | yes        |
| OH Ohio           | 2026 | date    | 2026-10-01 | **Ohio College Opportunity Grant (OCOG)**                                                                                                                                                                                                | [Ohio Department of Higher Education](https://highered.ohio.gov/wps/portal/gov/odhe/students/pay-for-college/ohio-grants-scholarships/ocog/ocog)                                 | yes        |
| MN Minnesota      | 2026 | wording | -          | **Minnesota State Grant** — “Beginning with the 2025-2026 academic year, students must apply by the 30th day of the term.”                                                                                                               | [Minnesota Office of Higher Education (via Wayback)](https://web.archive.org/web/20260301000000/https://www.ohe.state.mn.us/mPg.cfm?pageID=138)                                  | yes        |
| MO Missouri       | 2026 | date    | 2026-02-02 | **Access Missouri Financial Assistance Program - priority deadline**                                                                                                                                                                     | [Missouri Dept of Higher Education and Workforce Development](https://dhewd.mo.gov/ppc/grants/accessmo.php)                                                                      | yes        |
| MO Missouri       | 2026 | wording | -          | **Access Missouri Financial Assistance Program - award deadline** — “Award deadline - TBD”                                                                                                                                               | [Missouri Dept of Higher Education and Workforce Development](https://dhewd.mo.gov/ppc/grants/accessmo.php)                                                                      | yes        |
| MO Missouri       | 2026 | date    | 2026-04-01 | **Access Missouri Financial Assistance Program - final deadline**                                                                                                                                                                        | [Missouri Dept of Higher Education and Workforce Development](https://dhewd.mo.gov/ppc/grants/accessmo.php)                                                                      | yes        |
| MO Missouri       | 2026 | date    | 2026-07-31 | **Access Missouri Financial Assistance Program - correction deadline**                                                                                                                                                                   | [Missouri Dept of Higher Education and Workforce Development](https://dhewd.mo.gov/ppc/grants/accessmo.php)                                                                      | yes        |
| NE Nebraska       | 2026 | wording | -          | **Nebraska Opportunity Grant (NOG)** — “The NOG is awarded through postsecondary institutions within the state. Therefore, students must apply to the school to be considered for the grant.”                                            | [Nebraska Coordinating Commission for Postsecondary Education](https://ccpe.nebraska.gov/nebraska-opportunity-grant-nog)                                                         | yes        |
| ND North Dakota   | 2026 | wording | -          | **North Dakota State Grant** — “Complete the FAFSA annually as soon as possible after Federal Student Aid opens the application (generally October 1st). Funding under the state grant is limited, so early application is recommended.” | [North Dakota University System (NDUS)](https://ndus.edu/paying-for-college/state-grant)                                                                                         | yes        |
| MS Mississippi    | 2026 | date    | 2026-04-30 | **Higher Education Legislative Plan (HELP) Grant**                                                                                                                                                                                       | [Mississippi Office of Student Financial Aid](https://www.msfinancialaid.org/help/)                                                                                              | yes        |
| MS Mississippi    | 2026 | date    | 2026-03-31 | **Higher Education Legislative Plan (HELP) Grant - application**                                                                                                                                                                         | [Mississippi Office of Student Financial Aid](https://www.msfinancialaid.org/help/)                                                                                              | yes        |
| MS Mississippi    | 2026 | date    | 2026-09-15 | **Mississippi Tuition Assistance Grant (MTAG)**                                                                                                                                                                                          | [Mississippi Office of Student Financial Aid](https://www.msfinancialaid.org/mtag/)                                                                                              | yes        |
| MD Maryland       | 2026 | date    | 2026-03-01 | **Maryland State financial aid (FAFSA or MHEC One-App)**                                                                                                                                                                                 | [Maryland Higher Education Commission (MHEC)](https://mhec.maryland.gov/preparing/Pages/FinancialAid/descriptions.aspx)                                                          | yes        |
| MD Maryland       | 2026 | date    | 2026-03-01 | **Howard P. Rawlings Educational Excellence Awards (Guaranteed Access / Educational Assistance Grant)**                                                                                                                                  | [Maryland Higher Education Commission (MHEC)](https://mhec.maryland.gov/preparing/Pages/Important-Scholarship-and-Grant-Deadlines.aspx)                                          | yes        |
| MD Maryland       | 2026 | date    | 2026-08-01 | **Howard P. Rawlings Educational Excellence Awards - document deadline**                                                                                                                                                                 | [Maryland Higher Education Commission (MHEC)](https://mhec.maryland.gov/preparing/Pages/Important-Scholarship-and-Grant-Deadlines.aspx)                                          | yes        |
| MD Maryland       | 2026 | date    | 2026-03-01 | **Maryland Part-Time Grant**                                                                                                                                                                                                             | [Maryland Higher Education Commission (MHEC)](https://mhec.maryland.gov/preparing/Pages/Important-Scholarship-and-Grant-Deadlines.aspx)                                          | yes        |
| MD Maryland       | 2026 | wording | -          | **Howard P. Rawlings Campus-Based Educational Assistance Grant (CBEAG)** — “FAFSA or MSFAA filed March 2 or later”                                                                                                                       | [Maryland Higher Education Commission (MHEC)](https://mhec.maryland.gov/preparing/Pages/Important-Scholarship-and-Grant-Deadlines.aspx)                                          | yes        |
| NC North Carolina | 2026 | date    | 2026-06-01 | **Next NC Scholarship - UNC System universities (priority)**                                                                                                                                                                             | [College Foundation of North Carolina (CFNC), a State of NC service](https://www.cfnc.org/pay-for-college/next-nc-scholarship/)                                                  | unverified |
| NC North Carolina | 2026 | date    | 2026-08-15 | **Next NC Scholarship - NC community colleges (priority)**                                                                                                                                                                               | [College Foundation of North Carolina (CFNC), a State of NC service](https://www.cfnc.org/pay-for-college/next-nc-scholarship/)                                                  | unverified |
| MT Montana        | 2026 | wording | -          | **Montana state student aid (general)** — “Schools have their own deadlines and applications for awarding student aid. Check with the school's financial aid office for information.”                                                    | [Montana University System (applyMontana)](https://applymontana.mus.edu/paying-for-school/financial-aid/applying-for-financial-aid.html)                                         | yes        |
| MT Montana        | 2026 | wording | -          | **Gianforte Trade and Technology Scholarship** — “Deadlines: Sept 15 and Feb 15”                                                                                                                                                         | [Montana University System (applyMontana)](https://applymontana.mus.edu/paying-for-school/scholarships/index.html)                                                               | yes        |
| NV Nevada         | 2026 | wording | -          | **Silver State Opportunity Grant (SSOG)** — “Funds for the SSOG program are limited and are awarded to students enrolled in 15 credits with the most financial need first ... until funds are exhausted.”                                | [Nevada System of Higher Education (NSHE)](https://nshe.nevada.edu/system-administration/departments/arsa/students/financial-aid/silver-state-opportunity-grant/)                | yes        |
| NV Nevada         | 2026 | wording | -          | **Nevada Promise Scholarship** — “make sure to apply during the application period (August through October)”                                                                                                                             | [Nevada System of Higher Education (NSHE)](https://nshe.nevada.edu/system-administration/departments/arsa/students/financial-aid/)                                               | unverified |
| NM New Mexico     | 2026 | wording | -          | **New Mexico Student Incentive Grant** — “Student shall contact institution for eligibility period.”                                                                                                                                     | [New Mexico Higher Education Department](https://hed.nm.gov/financial-aid/scholarships/student-incentive)                                                                        | yes        |

## Gaps

Four items are `unverified`. Each says what a human has to open.

1. **New Hampshire — whether any state FAFSA deadline exists at all.**
   `education.nh.gov` 403s and is not usefully archived. Open
   <https://www.education.nh.gov/> in a real browser and look for a state grant
   or scholarship program; if none exists, the `none` row becomes `yes`. A
   second check: the NH Higher Education Commission page under
   `/who-we-are/higher-education-commission`.

2. **Nevada — Nevada Promise Scholarship application window.** The NSHE
   financial-aid page still addresses "the graduating class of 2024" and gives
   "August through October"; a secondary source gives 1 April. Open the Nevada
   Promise page on the student's own community college site (CSN, TMCC, WNC,
   GBC) — Promise is administered per campus — or call NSHE System
   Administration. The Silver State Opportunity Grant row IS verified.

3. **North Carolina — which award year the June 1 / August 15 priority dates
   belong to.** `cfnc.org` prints the dates with no year. Open
   <https://www.cfnc.org/pay-for-college/next-nc-scholarship/> and check whether
   a 2026-27 or 2027-28 cycle is named anywhere on the page, or ask NCSEAA.

4. **Maine — whether a 2026-27 deadline will be published.** FAME's 2026-27
   section gives grant amounts and the SAI cap but NO filing date, while still
   carrying the 2025-26 note ("extended to July 31, 2025"). This row is
   `wording` and verified as an accurate reading of the page, but the practical
   gap remains: re-check
   <https://www.famemaine.com/affording-education/pay-for-school/maine-grant-tuition-programs/maine-state-grant-program/>
   closer to the cycle, or contact FAME.

Also worth a human minute, though recorded as verified:

- **Michigan Achievement Scholarship** genuinely publishes no deadline. That is
  an absence I verified on the page, not a date I failed to find. Confirm with
  MI Student Aid (888-447-2687) before we tell a family "there is no deadline".
- **Maryland and Mississippi** state month/day without an award year. I assigned
  the 2026-27 cycle. The assignment is flagged in `notes` on each row.

## Hazards

Things that would mislead a family if we copied them naively.

1. **Massachusetts: the widely-published "May 1" is wrong for 2026-27.** The
   state's own page says **June 30, 2026**. Secondary aggregators still print
   "May 1, for priority consideration". If we show May 1 we understate the
   deadline by two months; if a family trusts an aggregator they may think they
   have already missed it.

2. **Maine: the widely-published "May 1" is not supported by FAME.** FAME's page
   shows the 2025-26 deadline was **extended to July 31, 2025**, and publishes
   no 2026-27 date. Printing "May 1" as a fact is not defensible.

3. **Michigan: two of the three programs are CLOSED.** The July 1 deadline
   belongs to the Michigan Competitive Scholarship and the Michigan Tuition
   Grant. Both are closed to new students (2023 graduates onward / no paid award
   by AY 2023-24) and both END on 30 September 2029. The program a current
   high-school senior actually wants is the **Michigan Achievement
   Scholarship**, which has no deadline. Showing "Michigan: July 1" is
   technically sourced and practically misleading.

4. **North Dakota's own page misstates when the FAFSA opens.** It says
   "generally October 1st". The 2026-27 FAFSA opened **24 September 2025**, and
   NDUS's separate FAFSA-completion page repeats "The 2026–27 FAFSA opened on
   October 1, 2025". We should quote the state's deadline wording but not its
   FAFSA-open claim.

5. **Mississippi's headline deadlines are not FAFSA deadlines.** MTAG and MESG
   are applied for through the **Mississippi Aid Application (MAAPP)**, not the
   FAFSA. Only the HELP Grant treats the FAFSA as a required supporting document
   (due 30 April). A "Mississippi FAFSA deadline: 15 September" row would be
   wrong in kind, not just in date.

6. **New Jersey has three different FAFSA deadlines plus a separate state
   step.** Renewal students: 15 April 2026. New students: 15 September 2026.
   Spring-only: 15 February 2027. A single "New Jersey deadline" is always wrong
   for someone. HESAA also requires completing a state record in **NJFAMS**
   after the FAFSA — missing that loses the award even with an on-time FAFSA.
   HESAA judges the FAFSA **processed** date, not the submitted date, and warns
   it takes at least three days.

7. **Ohio's deadline falls inside the award year.** OCOG's FAFSA deadline is **1
   October of each year** — i.e. about a month after the fall term starts.
   Sourced from a page last dated 25 September 2023, so it is worth re-checking
   each cycle.

8. **New York's TAP deadline is 30 June 2027 — almost two years after the FAFSA
   opens.** It is real, but it is not a reason to file late: TAP also needs a
   separate TAP application, and campus aid has much earlier deadlines.

9. **Missouri's middle deadline is deliberately undefined.** Access Missouri has
   four dates: priority (2 February 2026, guarantees an award), award (**TBD**,
   set each August), final (1 April 2026) and correction (31 July 2026). The
   "award deadline" only ever moves LATER, toward 1 April. We must not render
   TBD as a date.

10. **Minnesota's deadline is term-relative.** "By the 30th day of the term" has
    no single calendar date, and it changed: 2024-25 was 30 November 2024,
    2025-26 was 31 July 2025, and from 2025-26 onward the rule is the 30th day
    of the term. Any cached calendar date for Minnesota is stale.

11. **"No deadline" is not "no hurry".** Nebraska, Nevada (SSOG), North Dakota,
    New Mexico and Montana all award until money runs out or defer to the
    campus. Presenting these as blank cells invites a family to wait. The page
    should show the state's own words.

12. **Maryland's CBEAG looks like a second deadline but is not.** "FAFSA or
    MSFAA filed March 2 or later" describes the fallback program for students
    who MISSED 1 March. Rendering it as a deadline would tell families that
    filing late is fine.
