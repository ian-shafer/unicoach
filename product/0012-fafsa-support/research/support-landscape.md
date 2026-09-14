# Research: FAFSA support landscape — who helps families, and where support is thin

Brief 0012 (FAFSA support). All fetches made 2026-09-14 unless noted.

## Method

- The `websearch` skill (Serper) was **unavailable** — no API key configured in
  this session. I fell back to (a) DuckDuckGo HTML search, which returned one
  useful result set for "what is the FAFSA" before rate-limiting all further
  queries, and (b) direct fetches of primary pages with `httpx` + BeautifulSoup.
- Fetched and read: studentaid.gov's Aidan fact-sheet PDF; GAO-24-107407 (full
  product page, via crawler UA — gao.gov 403s default UAs); 20 U.S.C. § 1090
  (Cornell LII); the 2024-25 FSA Handbook Application and Verification Guide Ch.
  2 (fsapartners.ed.gov); financialaidtoolkit.ed.gov; usa.gov/fafsa; ed.gov's
  FAFSA page; NCAN's FAFSA Tracker and 2026-27 FAFSA pages; fafsatracker.com;
  EdTrust's universal-FAFSA report page; uAspire's site; Sallie Mae's FAFSA hub;
  BigFuture's FAFSA pages; Scoir's site, sitemap, and FAFSA/CSS blog post;
  Appily's guidance hub; savingforcollege.com and uopeople.edu explainers;
  Wikipedia's FAFSA article; YouTube search results for "FAFSA" (parsed from
  `ytInitialData`).
- **Fetch failures, noted honestly:** studentaid.gov itself is a JS SPA — every
  page body returns only "Loading…" to a plain HTTP client, so claims about live
  studentaid.gov page content are cited by URL but marked UNVERIFIED where I
  could not confirm them from a static artifact. niche.com (403), forbes.com
  (403), usnews.com (connection error), nerdwallet.com (404 on guessed URLs),
  fafsa.com and formyourfuture.org (connection errors), and edweek.org (404 on
  guessed URL) could not be assessed. The r.jina.ai rendering proxy now requires
  auth (403). DuckDuckGo, Bing (results ignored the query), and Mojeek (captcha)
  all degraded or blocked after the first query, so the "top-ranking pages" set
  below is anchored on the one successful DuckDuckGo results page for "what is
  the FAFSA" (fetched 2026-09-14).

## 1. The official surface (studentaid.gov, Aidan, the help line)

**What exists.**

- The FAFSA application and its help content live at
  https://studentaid.gov/h/apply-for-aid/fafsa (page body UNVERIFIED — SPA).
- **Aidan**, FSA's virtual assistant (https://studentaid.gov/aidan, SPA). FSA's
  own fact sheet: "Aidan can help customers answer more than 3,000 variations of
  frequently asked questions about federal student aid," including finding
  pages, checking PSLF status, and retrieving loan balances; "At the moment,
  Aidan only speaks English." —
  https://studentaid.gov/sites/default/files/aidan-virtual-assistant-fact-sheet.pdf
  (PDF dated March 30, 2023 / "Last updated May 21, 2021"). Note it is an
  FAQ-router, scoped to canned question variations, not a reasoning coach.
- **The help line**: the Federal Student Aid Information Center, 1-800-4-FED-AID
  (1-800-433-3243) — described as "a free hotline sponsored by the U.S.
  Department of Education"
  (https://www.savingforcollege.com/article/what-is-the-fafsa; number also
  listed on https://en.wikipedia.org/wiki/FAFSA). Official contact page:
  https://studentaid.gov/contact (SPA, UNVERIFIED).
- FSA also runs a practitioner-facing static site, the Financial Aid Toolkit
  (https://financialaidtoolkit.ed.gov/tk/learn/fafsa.jsp), including
  school-level FAFSA completion data feeds to states
  (https://financialaidtoolkit.ed.gov/tk/learn/fafsa/completion.jsp).

**Where families still get stuck — GAO's audit of the 2024-25 rollout**
(GAO-24-107407, published Sep 24, 2024,
https://www.gao.gov/products/gao-24-107407):

- "Nearly three-quarters of calls to Education's call center went unanswered
  during the first 5 months of the rollout due to understaffing."
- "Education's guidance instructed call center representatives to advise some
  students facing technical problems to just 'try again later' rather than
  proactively notifying them when the problem was resolved, which sometimes took
  months."
- "The new process also created additional barriers, including an inefficient
  process for students' parents or spouses who lacked a Social Security number
  to verify their identities. This process prevented some families from even
  accessing the application."
- GAO made seven recommendations, including that FSA "overhaul the submission
  process for parents and spouses without Social Security numbers," "ensure
  sufficient call center staffing," and improve translation services. As of the
  page's status updates, ED "plans to complete these efforts in calendar year
  2026" — i.e., the no-SSN contributor redesign was still open work into the
  2026-27 cycle.

**Takeaway:** the official surface is authoritative but reactive — a form, an
English-only FAQ bot, and a phone line that collapsed exactly when demand
spiked. Its content is also invisible to plain HTTP clients (SPA), which is part
of why third-party SEO pages rank so well.

## 2. Nonprofit / completion ecosystem

- **NCAN (National College Attainment Network)** runs the FAFSA Tracker
  dashboard: "This data dashboard tracks & ranks states' progress toward 100% of
  their HS seniors completing the FAFSA" —
  https://www.ncan.org/Web/Web/FAFSA/2026-27-FAFSA/2026-27-fafsa.aspx. The
  tracker itself is a Tableau dashboard
  (https://www.ncan.org/Web/Web/FAFSA/FAFSA-Tracker.aspx) built by Data Insight
  Partners (http://www.fafsatracker.com/, which offers state/district completion
  views for National, Kansas, Michigan, Nevada, New York). NCAN's 2026-27 page
  aggregates practitioner resources, an awareness toolkit, FAQs, training
  webinars, and state-deadline lookups — all practitioner-facing, not
  student-facing. NCAN's "Form Your Future" campaign brand: formyourfuture.org
  was unreachable at fetch time (connection error); campaign existence via NCAN
  tracker page + third-party reference
  (https://connectedandengaged.fhi360.org/resources/form-your-future-fafsa-tracker/,
  from search results). The widely cited "billions in Pell left on the table"
  figure: Wikipedia cites "A 2018 NerdWallet study found that students missed
  out on $2.6 billion in free federal Pell grants by not completing the FAFSA"
  (https://en.wikipedia.org/wiki/FAFSA); newer NCAN estimates exist but I could
  not fetch them — amounts beyond the 2018 figure are UNVERIFIED.
- **State FAFSA-completion mandates.** EdTrust (report page dated March 16,
  2026): "some states have adopted universal FAFSA completion policies,
  requiring or strongly encouraging high school seniors to complete the FAFSA as
  a condition of graduation. Fourteen states have implemented these policies,
  although Louisiana and New Hampshire have since repealed theirs." Its
  case-study states are Colorado, Illinois, Indiana, and Louisiana, and it
  recommends "clear opt-out waivers" and "user-friendly FAFSA data dashboards" —
  https://edtrust.org/resource/universal-fafsa/. (A full current list of the
  fourteen states was not verifiable from fetchable sources this session; the
  commonly named set — LA, IL, TX, AL, CA, CO, IN, NH, etc. — is UNVERIFIED here
  beyond EdTrust's four case states.)
- **uAspire** is the flagship financial-aid-advising nonprofit: "We improve the
  economic mobility of underrepresented students by increasing access to
  financial aid" (https://www.uaspire.org/). Its Afford Program gives seniors
  "robust, individualized support in their high schools and over text messaging
  to apply for financial aid," with a grade-12 arc of: complete financial aid
  applications → "Review Student Aid Report" → complete verification → compare
  offers → appeal (https://www.uaspire.org/solutions/advising). Two notes: (a)
  this is the closest existing analog to coach-style FAFSA support, and it is
  delivered person-by-person through schools/CBOs, not as a scalable product;
  (b) even uAspire's live page still says "Student Aid Report" — a term retired
  in 2024-25 in favor of the FAFSA Submission Summary (per the FSA Handbook,
  below) — showing how fast this content rots.
- **School-counselor tooling** is mostly completion-tracking, not
  question-answering: FSA gives high schools submission/completion data via
  state agencies
  (https://financialaidtoolkit.ed.gov/tk/learn/fafsa/completion.jsp), and
  vendors like Data Insight Partners dashboard it (fafsatracker.com). Scoir's
  counselor-facing blog covers FAFSA verification support
  (https://www.scoir.com/blog/help-students-through-fafsa-verification).

## 3. Commercial players (each claim from the company's own page)

- **College Board / BigFuture**: its FAFSA hub is mostly a pointer to
  studentaid.gov ("Go to Federal Student Aid… Start Application") plus College
  Board tools, and a sweepstakes: "Did you know completing the FAFSA qualifies
  you for a $40K BigFuture Scholarship?" —
  https://bigfuture.collegeboard.org/pay-for-college/get-help-paying-for-college/fafsa.
  The scholarship-drawing page: "Students who verify they completed the FAFSA
  during their senior year will earn an entry into monthly BigFuture scholarship
  drawings for $500 and $40,000," with double entries for families earning "less
  than $60,000" —
  https://bigfuture.collegeboard.org/pay-for-college/bigfuture-scholarships/scholarship-details/complete-the-fafsa.
  Its FAFSA page stated "The 2026–27 Free Application for Federal Student Aid
  (FAFSA®) is now open" (as of 2026-09-14). Note the College Board's own paid
  product, CSS Profile, is the FAFSA's fee-based sibling (Wikipedia: "The CSS is
  a fee-based product of the College Board" —
  https://en.wikipedia.org/wiki/FAFSA).
- **Sallie Mae**
  (https://www.salliemae.com/college-planning/financial-aid/fafsa/): a genuinely
  detailed FAFSA guide ("FAFSA® 2027-28: Your first step to applying for free
  money for college") wrapped in loan and lead-gen surface: persistent "Apply
  for a loan" CTA, "$2,000 No Essay Scholarship — Enter to win… in 2 minutes or
  less," "Scholly Easy Apply Scholarships." Internal staleness as of 2026-09-14:
  one section says "The 2027–28 FAFSA® is expected to be available by October 1,
  2026," while another still says of 2026-27, "The federal government has not
  announced the exact opening date yet" — even though BigFuture (above) says the
  2026-27 form is already open. Its deadline content ("June 30, 2026 — the
  federal deadline for filing the FAFSA® for the 2026-2027 academic year") also
  conflicts with USAGov ("For the school year 2026-27, the federal deadline to
  submit the FAFSA is June 30, 2027" — https://www.usa.gov/fafsa). Sallie Mae's
  June 30, 2026 line is wrong or at best mislabeled; the federal deadline is the
  end of the award year (USAGov, savingforcollege both say June 30, 2027 for
  2026-27).
- **Scoir**: a college-network platform ("Create Account for Free" for families;
  pricing sold to high schools — https://www.scoir.com/). Its FAFSA content is
  blog-level: "FAFSA & CSS Profile: A Straightforward Guide"
  (https://www.scoir.com/blog/fafsa-css-profile-a-straightforward-guide) is
  SAI-current but generic, and contains zero mention of divorced parents,
  separation, or contributors (checked full text).
- **Niche**: FAFSA blog content exists
  (https://www.niche.com/blog/how-to-fill-out-the-fafsa/) but the site
  403-blocks non-browser clients and had no Wayback snapshot — content quality
  UNVERIFIED.
- **Appily (EAB)**: "College Planning Made Simple: College Search, Scholarships,
  Virtual Tours & Direct Admission" (https://www.appily.com/). Its "Paying for
  College" articles hub surfaced budgeting/application-fee articles and no
  prominent FAFSA explainer on the category page as of 2026-09-14
  (https://www.appily.com/guidance/articles/paying-for-college).
- **Paid FAFSA-filing services**: Wikipedia warns that lookalike sites "such as
  fafsa.com, fafsa.net, may appear to be official but most of these sites charge
  a fee for assistance" (https://en.wikipedia.org/wiki/FAFSA). fafsa.com itself
  was unreachable at fetch time, so its current operation is UNVERIFIED. The
  broader paid-consultant market (IECs charging for aid-form help) could not be
  sized from fetchable sources this session — UNVERIFIED.
- **YouTube/TikTok advice ecosystem** (YouTube search "FAFSA", parsed
  2026-09-14): FSA's own explainer "What Is FAFSA®?" leads with 5.79M views (2
  years old); the rest of page one is third-party — The Scholarship System ("3
  FAFSA secrets to help you get the most financial aid," 224K views; "FAFSA
  Income Limits: What Parents Need to Know," 30K), College Money Method
  ("Screen-by-screen walkthrough of the 2026-27 FAFSA," 24K), university
  walkthroughs (University of the Pacific 2026-27 step-by-step, 59K), a
  10-year-old Ramsey Show clip (201K views) still surfacing, and Fox Business
  FAFSA-fraud news (136K). Pattern: high-traffic "secrets/maximize aid" framing
  next to genuinely useful walkthroughs, with stale multi-year-old videos still
  ranking. TikTok itself could not be assessed (no fetchable interface) — any
  claim about TikTok FAFSA advice volume is UNVERIFIED.

## 4. What a high-signal FAFSA page would contain that top-ranking pages don't

Top results for "what is the FAFSA" (DuckDuckGo, 2026-09-14): studentaid.gov,
usa.gov, ed.gov, Wikipedia, savingforcollege.com, usnews.com, forbes.com,
uopeople.edu, programclarity.com. Assessed:

- **Wikipedia** (https://en.wikipedia.org/wiki/FAFSA): stale at multiple
  load-bearing points — Pell max quoted "as of the 2019–2020 Award Year," still
  describes the Student Aid Report (SAR) and the retired myStudentAid mobile
  app, headline non-completion stat is from 2018.
- **UoPeople blog** (https://www.uopeople.edu/blog/what-is-fafsa/, ranks
  top-10): still teaches "Expected Family Contribution (EFC)" and the SAR — both
  retired for the 2024-25 cycle (the FSA Handbook now describes SAI and the
  FAFSA Submission Summary:
  https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide/ch2-filling-out-fafsa).
  The page is an enrollment-marketing surface ("Request Info / Apply Now" chrome
  throughout). No visible update date.
- **SavingForCollege**
  (https://www.savingforcollege.com/article/what-is-the-fafsa): content is
  current (SAI, FAFSA Submission Summary, correct 2026-27 dates: open "by
  October 1, 2025," deadline "June 30, 2027") and it even lists the FSAIC
  hotline — but it sits inside a 529-plan/loan-review marketing site with
  subscription and "Find a 529 Pro" lead paths, and cites no primary sources
  inline.
- **Sallie Mae** (see §3): internally inconsistent dates on one page,
  wrong/mislabeled federal deadline, loan CTAs and sweepstakes lead capture
  throughout.
- **usnews.com / forbes.com**: both bot-blocked; from long experience these are
  ad-supported listicles, but that is UNVERIFIED this session.

**The gap a high-signal page can fill, concretely:** (1) a visible last-verified
date per fact; (2) inline citations to studentaid.gov, the FSA Handbook AVG, and
statute — none of the assessed pages cite primary sources for a single claim;
(3) current-cycle correctness (SAI not EFC, FAFSA Submission Summary not SAR,
the actual open date and both federal AND state deadlines — state deadlines are
routinely earlier and are where real money is lost); (4) zero lead capture in
the explainer body (every commercial competitor monetizes the page with loans,
529 leads, sweepstakes, or enrollment marketing); (5) honest edge-case coverage
(divorced parents, no-SSN contributors, dependency — see §5), which none of the
assessed explainer pages address at all.

## 5. Where coach-style support is genuinely underserved

The questions families actually get stuck on are conditional, and static pages
answer them badly or not at all:

- **"My parents are divorced — whose info goes on the form?"** The real rule
  changed with FAFSA simplification and is genuinely counterintuitive: "If
  parents are divorced or never married and not living together, the parent on
  the FAFSA form should be the parent who provided more than 50% of the
  financial support for the student during the last 12 months. This may be
  different from the parent the student lived with during that same period… If
  neither parent provided more than 50%… the parent (and current spouse, if
  applicable) with the greater income and assets is a required contributor." —
  2024-25 FSA Handbook, AVG Ch. 2
  (https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide/ch2-filling-out-fafsa).
  Child support paid counts toward the payer's support share (same source).
  **None of the assessed commercial explainers (Scoir, SavingForCollege's main
  explainer, Sallie Mae's FAFSA hub page, BigFuture's FAFSA pages) mention
  divorce or separation even once** (full-text checks, 2026-09-14). The old
  "custodial parent" heuristic that saturates legacy web content is now wrong.
- **"My parent has no SSN."** GAO documented that the identity-verification path
  for no-SSN parents "prevented some families from even accessing the
  application," and ED's redesign (including ITIN-based IRS data transfer) was
  still planned "for the 2026-27 cycle" as of March 2025
  (https://www.gao.gov/products/gao-24-107407). The Handbook's mechanics
  (all-zeros SSN on paper, ITIN field, FTI consent) are spread across a
  practitioner document families will never read (AVG Ch. 2, above). This is a
  live, high-anxiety, mixed-status-family question with almost no consumer-grade
  coverage.
- **"Am I dependent?"** The independence criteria are a statutory checklist
  (born before Jan 1 of the cutoff year, married, grad student, active
  duty/veteran, foster care/orphan since 13, emancipated, unaccompanied homeless
  youth…) with genuinely tricky worked examples in the Handbook — e.g., a
  separated-but-not-divorced student paying child support, or a student whose
  custodial mother died and whose dependency reverts to the surviving biological
  father, not the stepfather (AVG Ch. 2 examples, same URL). Static explainers
  don't touch dependency overrides or provisional independence.
- The support channels that DO handle these — the FSAIC phone line and school
  counselors — are exactly the ones GAO showed to be capacity-constrained (74%
  of calls unanswered at peak), and uAspire-style human advising doesn't scale
  beyond its partner schools. An honest, cited, conversational coach that can
  walk a specific family's facts through the Handbook rules — and say when the
  answer is "call your school's aid office, and here's exactly what to ask" —
  has no direct competitor in the set surveyed. (Aidan answers "variations of
  frequently asked questions," in English only, per FSA's own fact sheet.)

## 6. Legal / compliance guardrails for a paid FAFSA-guidance product

- **The form itself must be free, and no one may charge for its processing
  pipeline**: "no parent or student shall be charged a fee by the Secretary, a
  contractor, a third-party servicer or private software provider, or any other
  public or private entity for the collection, processing, or delivery of
  Federal financial aid through the use of such application," and eligibility
  may be determined "only by using the Free Application for Federal Student Aid"
  — 20 U.S.C. § 1090(d)(3) (https://www.law.cornell.edu/uscode/text/20/1090).
- **Preparer conditions**: the statute's "Free preparation authorized" clause, §
  1090(d)(2), conditions preparer activity on: (A) putting the preparer's name,
  address, SSN/EIN, and organizational affiliation on the applicant's form; (B)
  being "subject to the same penalties as an applicant for purposely giving
  false or misleading information"; (C) "clearly inform[ing] each individual
  upon initial contact, that the Free Application for Federal Student Aid is a
  free form that may be completed without professional assistance"; and (D) not
  producing or using "any other form for the purpose of applying for Federal
  financial aid" (same URL). Wikipedia summarizes the fee-based regime: "The
  Higher Education Opportunity Act of 2008 authorized fee-based FAFSA
  preparation," with the same must-disclose-the-free-option and non-affiliation
  transparency duties (https://en.wikipedia.org/wiki/FAFSA). ED's current
  consumer-facing scam guidance lives at https://studentaid.gov/resources/scams
  (SPA — content UNVERIFIED this session).
- **Practical read for unicoach** (analysis, not legal advice): the tripwires
  attach to _preparing/submitting the form_ and to _charging for the form
  pipeline_. A paid coach that explains rules, cites sources, and helps a family
  understand their own situation — without filling out or submitting the FAFSA
  on their behalf, without collecting FSA credentials, and while stating plainly
  and early that the FAFSA is free at studentaid.gov — sits outside the §
  1090(d)(2) preparer role, and the "the FAFSA is free" disclosure is cheap,
  on-brand honesty anyway. If unicoach ever DID help complete the form, the
  (d)(2)(A) preparer-identification duty would bite. Whether ED or the FTC has
  taken recent enforcement action against fee-based FAFSA advice services could
  not be verified this session — UNVERIFIED. A "not a financial-aid
  administrator / verify with your school's aid office" disclaimer matches how
  the Handbook itself allocates final authority to institutions.

## Implications for the brief (compressed)

1. A public FAFSA explainer wins on exactly four axes competitors demonstrably
   fail: per-fact primary citations, visible freshness (the ecosystem shipped
   EFC/SAR content two years after retirement, and Sallie Mae contradicts itself
   on one page), edge-case honesty (divorce/no-SSN/dependency — absent from
   every assessed commercial page), and zero lead capture.
2. Coach differentiation is the conditional questions plus capacity: the
   government's own auditor documented the official channel failing at peak (74%
   of calls unanswered) and the no-SSN fix still shipping into 2026-27.
3. Compliance posture is straightforward and marketable: never touch the form or
   credentials, always say the FAFSA is free — which is also the honesty-first
   brand position.
