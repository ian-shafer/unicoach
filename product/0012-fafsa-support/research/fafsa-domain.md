# FAFSA domain research — what the FAFSA is today (brief 0012)

Fetched 2026-09-14 unless noted. Award-year notation: "2026-27" = July 1, 2026 –
June 30, 2027.

## Method

- The `websearch` skill (Serper) was UNAVAILABLE in this session — no API key
  configured. Noted honestly; all research was done without it.
- Search fallbacks: DuckDuckGo HTML endpoint worked briefly, then rate-limited;
  Bing/Google/Brave/Ecosia/searx were blocked or unusable. Most load-bearing
  facts therefore come from **direct fetches of primary sources**:
  fsapartners.ed.gov (Dear Colleague Letters, Electronic Announcements, the FSA
  Handbook Application and Verification Guide — all server-rendered and
  fetchable), ed.gov press releases, the Federal Register API, govinfo.gov
  (public law text), ncan.org, salliemae.com, and the Wayback Machine (for
  gao.gov and one delisted NCAN article, both of which block direct fetches).
- studentaid.gov itself is a JavaScript SPA whose pages return an empty HTML
  shell to non-browser fetches. Where a claim's canonical home is a
  studentaid.gov page, I cite it as the canonical URL but verified the content
  through the FSA Handbook, ED press releases, Federal Register notices, or
  (twice, flagged) a search-result snippet of the page.

## 1. The post-Simplification form (2024-25 onward)

The FAFSA Simplification Act (enacted Dec. 2020, fully effective with the
2024-25 form) plus the FUTURE Act rebuilt the form. Primary source for all of
the following: DCL GEN-23-11, "FAFSA Simplification Act Changes for
Implementation in the 2024-25 Award Year"
(<https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2023-08-04/fafsa-simplification-act-changes-implementation-2024-25>):

- **SAI replaced EFC.** "The Act requires that schools transition from the
  Expected Family Contribution (EFC) to the Student Aid Index (SAI) beginning in
  the 2024-25 Award Year." The SAI can be **negative, down to -1,500** (EFC
  bottomed at 0), which lets schools distinguish degrees of need at the bottom.
- **Sibling discount removed.** The changes include "Removal of the number of
  family members in college from the eligibility calculation" — two kids in
  college no longer roughly halves each one's expected contribution. The income
  protection allowance was increased partly to offset this.
- **Contributor model.** "Contributors are individuals, such as a parent or
  spouse, that may be required to provide information on an applicant's FAFSA
  depending on the applicant's dependency or marital status." Each contributor
  logs in with their own StudentAid.gov account and completes their own section.
- **Consent to IRS direct data exchange is mandatory.** Federal tax information
  (FTI) flows from the IRS via the FUTURE Act Direct Data Exchange (FA-DDX).
  "The FUTURE Act also made providing consent for the exchange of FTI a
  requirement for receiving federal student aid." (GEN-23-11, same URL.) A
  financial aid administrator cannot consent on anyone's behalf (2025-26 FSA
  Handbook AVG Ch. 1,
  <https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch1-application-process-fafsa-isir>).
  Consequence: **one parent refusing consent kills the student's federal aid
  eligibility, including Pell** — the FAFSA is rejected with no SAI calculated
  until "consent and approval" is provided (AVG Ch. 1, same URL). The canonical
  family-facing statement lives at
  <https://studentaid.gov/help-center/answers/topic/questions_about_fafsa/articles>
  (SPA — content not directly fetchable; consequence verified via the sources
  above).
- **Pell tied to SAI and poverty tables.** Max Pell is automatic when a
  dependent student's single parent has AGI ≤ 225% of the poverty guideline (≤
  175% if not a single parent), or when the parent isn't required to file a
  federal return; minimum-Pell tests use 325%/275%. Otherwise Pell = max Pell
  minus SAI. (2025-26 FSA Handbook AVG Ch. 3,
  <https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility>;
  GEN-23-11 confirms auto-max-Pell non-filers get SAI = -1,500.)
- **Asset rules changed.** Net worth of ALL businesses (previously only 100+
  employee businesses) and of family farms is now reported; the primary
  residence remains excluded. Child support received moved from income to
  assets. (GEN-23-11, same URL.)
- **Fewer questions, and separated students are no longer "married"** on the
  form; dependency criteria otherwise largely carried over. (GEN-23-11.)

## 2. Current cycle mechanics (as of 2026-09-14)

- **A federal law now fixes the open date.** The FAFSA Deadline Act, Pub. L.
  118-145 (Dec. 11, 2024), amended HEA §483(d)(4) so the FAFSA must be available
  "not later than October 1 prior to the applicant's planned year of enrollment"
  (<https://www.govinfo.gov/content/pkg/PLAW-118publ145/html/PLAW-118publ145.htm>).
- **2026-27 FAFSA (current filing cycle for 2026-27 enrollment): opened Sept.
  24, 2025**, after a beta that began Aug. 3, 2025 — ED called it "the earliest
  launch in the program's history" (ED press release, Sept. 24, 2025,
  <https://www.ed.gov/about/news/press-release/us-department-of-education-announces-earliest-fafsa-form-launch-program-history>).
  Federal deadline: **FAFSA received by June 30, 2027; corrections by Sept. 11,
  2027** (Federal Register, 2026-2027 Award Year Deadline Dates, 91 FR 37096,
  June 22, 2026,
  <https://www.federalregister.gov/documents/2026/06/22/2026-12462/2026-2027-award-year-deadline-dates-for-reports-and-other-records-associated-with-the-free>;
  electronic deadlines are 11:59 p.m. Central). Note: the studentaid.gov
  deadlines page (<https://studentaid.gov/apply-for-aid/fafsa/fafsa-deadlines>)
  shows the corrections date as Sept. 12, 2027 per a search snippet — one day
  off the FR notice; UNVERIFIED which the page currently displays (SPA). Treat
  the FR notice as authoritative.
- **2027-28 FAFSA: in beta now, public by Oct. 1, 2026.** Beta phase 1 opened
  Aug. 7, 2026 with community partners; phase 2 opened Aug. 25, 2026 to any
  student/contributor via StudentAid.gov/joinbeta; "public release by Oct. 1,
  2026" (fsapartners EA APP-26-xx, "2027-28 FAFSA Beta Testing Plan (Updated
  Aug. 25, 2026)",
  <https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-07-21/2027-28-fafsa-beta-testing-plan-updated-aug-25-2026>;
  prototype: APP-26-13,
  <https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-09-03/2027-28-fafsa-prototype-now-available>).
  Its federal deadline: June 30, 2028 (corrections Sept. 12, 2028) per a
  studentaid.gov snippet — UNVERIFIED directly (SPA); the 2027-28 FR deadline
  notice is not published yet.
- **State and school deadlines are earlier and vary — this is the deadline that
  actually bites.** Many state grants are first-come-first-served. Concrete
  state examples (compiled list at savingforcollege.com, which mirrors the
  studentaid.gov state table,
  <https://www.savingforcollege.com/article/fafsa-deadlines>; canonical:
  <https://studentaid.gov/apply-for-aid/fafsa/fafsa-deadlines>):
  - **Texas: Jan. 15** priority deadline.
  - **California: March 2** for state aid / Cal Grant (school-certified GPA also
    due; a Sept. 2 later window exists for community-college Cal Grants).
  - **Connecticut: Feb. 15** priority; **Missouri: Feb. 1** priority;
    **Maryland: March 1**; **Indiana: April 1** (Frank O'Bannon, 21st Century
    Scholars, etc.); **Tennessee: April 1** (State Grant, Tennessee Promise);
    **Idaho: March 1** (Opportunity Scholarship).
  - **Illinois, Kentucky, Oregon (Opportunity Grant), Vermont: "as soon as
    possible after opening"** — aid runs out.
  - College priority deadlines (especially private colleges, often Feb/March,
    sometimes earlier for early admission) vary per school — a product must say
    "check each college's own date."

## 3. The process end-to-end

Chain, with the governing sources (2025-26 FSA Handbook, Application and
Verification Guide — the 2026-27 AVG behaves the same for these steps; Ch. 1
<https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch1-application-process-fafsa-isir>,
Ch. 2
<https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch2-filling-out-fafsa-form>,
Ch. 4
<https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch4-verification-updates-and-corrections>):

1. **StudentAid.gov accounts ("FSA ID") — student AND every contributor.** "Each
   person who provides data in the online FAFSA form must have an account
   username and password." Since 2024-25, "all individuals — even those without
   an SSN — can create a StudentAid.gov account"; SSN-holders are matched
   against SSA, SSN-less contributors go through TransUnion knowledge-based
   identity verification (AVG Ch. 1). Accounts can be created before the form
   opens.
2. **Filing.** Student completes their section, invites parent/spouse
   contributors (since 2026-27, via a simple invite code — ED press release
   above); each contributor consents to FTI exchange and signs. Students
   themselves must have an SSN (except Freely Associated States citizens) (AVG
   Ch. 2).
3. **FAFSA Submission Summary** (replaced the Student Aid Report): the
   applicant-facing output with the SAI and estimated Pell eligibility; FTI does
   not appear on it (GEN-23-11).
4. **SAI calculated; schools receive the ISIR** — every listed school gets the
   Institutional Student Information Record electronically (AVG Ch. 1; the FR
   deadline notice sets the last dates schools can receive ISIRs).
5. **Verification.** A subset of applications is selected (Verification Tracking
   Groups, incl. V4/V5 identity groups); schools collect documents; FTI-sourced
   fields generally don't need re-verification (AVG Ch. 4; FR notice sets
   verification document deadlines).
6. **Aid offers.** Each college packages Pell + loans + work-study +
   state/institutional aid into its own offer letter on its own timetable.
7. **Corrections/updates** through fafsa.gov or the school, until the cycle's
   corrections deadline (Sept. 11, 2027 for 2026-27 — FR notice above).

## 4. What families actually ask and get wrong

- **"Which parent files?" (divorced/separated).** Post-2024 rule — different
  from the old "custodial parent" rule: "the parent on the FAFSA form should be
  the parent who provided more than 50% of the financial support for the student
  during the last 12 months. This may be different from the parent the student
  lived with." Child support paid counts toward the payer's share; that parent's
  current spouse (stepparent) is also a required contributor (AVG Ch. 2, URL
  above). The noncustodial/other parent is NOT a FAFSA contributor (though CSS
  Profile schools may still want them — separate instrument).
- **Parents without SSNs / mixed-status families.** The student's own
  eligibility is what matters; an undocumented parent can create an SSN-less
  StudentAid.gov account (TransUnion identity check, AVG Ch. 1) and file.
  Students themselves must be citizens/eligible noncitizens for federal aid, and
  (paper form aside) a student needs an SSN (AVG Ch. 2). GAO found the 2024
  SSN-less identity process was "inefficient... [and] prevented some families
  from even accessing the application" (GAO-24-107407,
  <https://www.gao.gov/products/gao-24-107407>, via Wayback — gao.gov blocks
  direct fetch). Fear of exposing an undocumented parent's data is a real,
  recurring family concern — UNVERIFIED here beyond the GAO access finding;
  worth its own research pass.
- **Consent refusal kills Pell.** A required contributor declining FTI consent
  makes the student ineligible for federal aid (GEN-23-11 / AVG Ch. 1, quoted in
  §1). This is the single sharpest new failure mode of the post-2024 form.
- **Dependency edge cases.** Age <24, unmarried, undergrad → dependent
  regardless of living alone or parents not claiming them on taxes; independence
  only via the statutory criteria (born before the cutoff, married, grad
  student, active duty/veteran, has dependents, orphan/foster/ward,
  emancipated/legal guardianship, unaccompanied homeless youth) or a documented
  dependency override for unusual circumstances (AVG Ch. 2 & Ch. 5,
  <https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch5-special-cases>).
  "My parents refuse to help" alone does not make a student independent
  (provisionally independent / DO path, AVG Ch. 5).
- **"We make too much to bother."** Among the 26% of college families who did
  NOT file for 2025-26, the top reason was "believing their family income was
  too high (26%)", then "not knowing about the FAFSA (19%)", then "thinking they
  didn't qualify for non-financial reasons (16%)" (Sallie Mae/Ipsos, How America
  Pays for College 2026,
  <https://www.salliemae.com/about/leading-research/how-america-pays-for-college/>).
  Only 25% of families even know the form opens in October (same source).
- **"FAFSA is only for loans" myth.** The FAFSA is the gateway to grants,
  work-study, AND loans — ED awards "more than $120 billion a year in grants,
  work-study funds, and low-interest loans to approximately 13 million
  students," and "more than 21 million students used the FAFSA last year" (ED
  FSA office page, <https://www.ed.gov/about/ed-offices/fsa>). It also gates
  most state and much institutional aid (NCAN,
  <https://www.ncan.org/Web/News/Class-of-2026-Sets-All-Time-High-FAFSA-Completion-Record.aspx>).
- **Assets confusion.** Primary home and retirement accounts stay excluded, but
  small businesses and family farms are now counted (GEN-23-11) — a change that
  generated real anger in farm states and that families still ask about.
- **Help-seeking is the norm, not the exception.** 62% of students and 49% of
  parents who filed needed help; helpers: friends/family 22%, the college's aid
  office 21%, the FAFSA help page 17% (How America Pays 2026, URL above).
- **The 2024 rollout left scar tissue.** GAO: launch delayed ~3 months,
  technical defects blocked completions, "about three-quarters of calls to
  Education's call center went unanswered," and ~9% fewer first-time applicants
  filed, with the largest declines among lower-income students (GAO-24-107407,
  URL above). Product copy should assume families remember this and reassure
  that the current form works (97% beta satisfaction claimed by ED for 2026-27 —
  ED press release above; ED's own number, treat as marketing).

## 5. What rots vs. what is stable (for a product page)

Will rot every year (re-verify each cycle):

- Award-year labels and open dates (e.g., "2027-28 opens by Oct. 1, 2026");
  beta-period details.
- Federal deadline pair (June 30 + ~Sept. 11 corrections — republished each year
  in the Federal Register).
- Every state deadline; school priority deadlines.
- Max/min Pell ($7,395/$740 for 2026-27) — republished each Feb. 1 in a GEN DCL,
  and contingent on appropriations.
- Poverty-guideline dollar cutoffs behind the Pell tables (guidelines update
  annually; the 175%/225% multipliers are statutory and stable).
- Tax "prior-prior year" reference (2026-27 FAFSA uses 2024 tax data; increments
  annually).
- Statistics (completion rates, unclaimed-Pell dollars, survey numbers).
- Policy in motion: One Big Beautiful Bill Act implementation is live —
  Workforce Pell for short-term programs (state certification EA GENERAL-26-44,
  <https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-07-01/eligible-workforce-programs-state-workforce-pell-certification-form-available>),
  NSLDS/loan-limit changes
  (<https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-07-01/one-big-beautiful-bill-act-nslds-professional-access-updates-july-2026>),
  and a projected Pell funding shortfall ($5.4B FY26 → ~$11B FY27 per CBO, via
  NCAN
  <https://www.ncan.org/Web/News/New-CBO-Projection-Sets-up-Pell-Grant-Funding-Battle-.aspx>)
  that could pressure the max award.

Stable (safe to hard-code, cite once):

- SAI (not EFC) is the eligibility index; SAI floor -1,500 (statutory).
- Contributor model + mandatory FTI consent; refusal = no federal aid
  (statutory, FUTURE Act).
- No sibling discount (statutory).
- Oct. 1 availability mandate (Pub. L. 118-145).
- June 30 end-of-cycle federal deadline pattern; state/school deadlines earlier
  and varying.
- Dependency criteria and the >50%-support divorced-parent rule.
- FAFSA is free and covers grants + work-study + loans; FAFSA Submission Summary
  / ISIR terminology.
- SSN-less contributors can get accounts (since 2024-25).

## 6. Numbers worth quoting (each dated; all will age)

- **Max Pell 2026-27: $7,395; minimum $740** — flat since 2023-24; confirmed by
  the Consolidated Appropriations Act, 2026 (DCL GEN-26-01, updated Feb. 18,
  2026,
  <https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2026-01-30/2026-27-federal-pell-grant-maximum-and-minimum-award-amounts>).
- **FAFSA completion, high-school seniors by June 30 of senior year (NCAN):
  class of 2024 — 46%; class of 2025 — 53.9%; class of 2026 — 54.7% by May 1,
  2026, an all-time record (prior record 54.4%, class of 2018), possibly
  breaking 60%** (NCAN, May 13, 2026,
  <https://www.ncan.org/Web/News/Class-of-2026-Sets-All-Time-High-FAFSA-Completion-Record.aspx>).
- **Dollars left unclaimed: class of 2024 left almost $4.4 billion in Pell on
  the table** (up $400M vs class of 2023) — CA and TX each >$0.5B (NCAN, Apr.
  15, 2025,
  <https://www.ncan.org/Web/News/New-Report-Highlights-Benefit-of-Pell-Grant-for-Students-and-States.aspx>).
  Prior years: class of 2023 >$4B
  (<https://web.archive.org/web/2024/https://www.ncan.org/news/662266/NCAN-Report-In-2023-High-School-Seniors-Left-Over-4-Billion-on-the-Table-in-Pell-Grants.htm>);
  2022 $3.58B; 2021 $3.75B.
- **Simplification widened Pell: 1.7 million MORE students qualified for a
  maximum Pell in 2025-26 vs 2023-24 — a 27% increase in max-Pell-eligible
  students** (NCAN, Jan. 28, 2026,
  <https://www.ncan.org/Web/News/FAFSA-Simplification-Yielded-1.7-Million-Additional-Pell-Eligible-Students.aspx>).
- **Family filing rate: 74% of college families filed the FAFSA for 2025-26**
  (up 3pp YoY, back to pre-crisis level); 81% found the process easy, up from
  72% (Sallie Mae/Ipsos How America Pays 2026,
  <https://www.salliemae.com/about/leading-research/how-america-pays-for-college/>).
- **Scale: >21 million FAFSA filers last year; ~$120B/year in federal aid to ~13
  million students** (ED FSA office page,
  <https://www.ed.gov/about/ed-offices/fsa>, as of 2026-09-14).
- **Share of FAFSA filers who receive Pell: UNVERIFIED this session** — no
  fetchable primary gave a current ratio (the FSA Data Center is an SPA).
  Directionally: max-Pell-eligible applicants rose 27% post-simplification (NCAN
  above); do not print a specific share without pulling the FSA Data Center Pell
  end-of-year report.
