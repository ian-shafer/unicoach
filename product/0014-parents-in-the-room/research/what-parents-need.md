# What parents of college applicants actually need — and where existing tools fail them

Research report for `product/0014-parents-in-the-room` (parent accounts).
Written 2026-09-14.

## Method

- **Tools used:** direct HTTP fetching (`httpx` + BeautifulSoup) of primary
  sources; `pypdf` for PDF extraction (Challenge Success white paper); the NYT
  snowplow-parenting article retrieved via the Wayback Machine; Hechinger Report
  found via its WordPress search API.
- **Tools unavailable:** the `websearch` skill (Serper) has **no API key
  configured** in this session, so no Google search was possible. DuckDuckGo,
  Bing, and Mojeek all served bot-challenge or junk pages to scripted HTTP. All
  sources below were therefore reached by fetching known primary URLs directly;
  every quoted figure was verified in the fetched page text.
- **Sources I could not verify and therefore do not cite for numbers:**
  Fidelity's college-savings surveys (newsroom blocked, HTTP 202), T. Rowe Price
  "Parents, Kids & Money" (404 on guessed URL), GAO FAFSA-rollout reports (403),
  NACAC's current State of College Admission dashboards (member-gated; only the
  public landing page was readable). Claims from these bodies of work are either
  omitted or flagged as unverified.
- **A note on the Sallie Mae data:** the fetched page is _How America Pays for
  College 2026_ (Sallie Mae/Ipsos, AY 2025-26 — the current edition of the study
  the task named as "2024").

## Findings, by theme

### 1. Money is the parent's half of the project — and the roles split cleanly (STRONG)

The Sallie Mae/Ipsos national study of college students and parents
([How America Pays for College 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/))
shows a stable division of labor:

- Parent income and savings is the **largest single funding source: 39% of costs
  ($13,087/yr on average)**; parent borrowing adds another 11%. Students' own
  income/savings covers just 10%.
- **Who decides differs by domain.** The _school_ choice is the student's in 66%
  of families (joint in 29%). The _how-to-pay_ decision is joint in 39%,
  parent-only in 26%, student-only in 35%.
- **Cost screens the list at every stage:** 79% of families eliminated at least
  one school on cost alone; cost was used to narrow choices before researching
  (58%), before applying (60%), and after seeing aid offers (63%).
- 80% of families say they are "willing to stretch financially," and 75% would
  rather borrow than have the student not attend — i.e., parents' stated values
  push _toward_ over-borrowing unless someone injects cost truth.

**Implication:** the parent's job is the money plan, and the student's job is
the choice. That is exactly unicoach's "support without directing" inversion,
and it is what families already do when healthy.

### 2. The affordability conversation is systematically avoided (STRONG)

Same study
([Sallie Mae 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/)):

- Fewer than half of families have discussed **what all years of college will
  cost (44%)**, the starting salary in the student's field (38%), or
  non-repayable payment options (37%).
- 58% made a plan to pay for _all_ years before enrolling — meaning 42% did not.
- 84% are confident the student finishes a bachelor's in 4 years, versus a ~61%
  actual 6-year completion rate; among families _not_ fully confident, only
  **42% have discussed the financial implications of extended enrollment**.

This is the single clearest unmet need: the conversation both sides know they
should have, structured and prompted by a neutral third party.

### 3. FAFSA makes the parent a legally required actor, and it hurts (STRONG)

- Under FAFSA Simplification, "**Contributor: Parent**" (and "Contributor:
  Parent Spouse or Partner") is a formal role in the application: a dependent
  student's FAFSA cannot be completed until each parent contributor creates
  their **own StudentAid.gov account (FSA ID)**, logs in, completes their own
  section, and consents to IRS data transfer
  ([FSA Handbook, Application and Verification Guide, ch. 2](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2024-2025/application-and-verification-guide);
  the consumer-facing page is
  [studentaid.gov's parent-info page](https://studentaid.gov/apply-for-aid/fafsa/filling-out/parent-info),
  which is JS-rendered and could not be text-verified here).
- It is genuinely hard: **56% of completing families needed help** with the
  FAFSA (49% of parents; 62% of students), and only **25% of families know the
  FAFSA opens in October**
  ([Sallie Mae 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/)).
  26% of families skipped it entirely, most often from the (usually false)
  belief their income was too high.
- The 2024 rollout showed the failure mode at its worst: parents **without
  Social Security numbers were locked out for months** by a federal glitch, and
  mixed-status families feared that naming an undocumented parent would create
  immigration risk — some students filed without parent data at all
  ([Hechinger Report, "Four cities of FAFSA chaos"](https://hechingerreport.org/four-cities-of-fafsa-chaos-students-tell-how-they-grappled-with-the-mess-stress/)).

**Timeline consequence:** the parent has personally-owned deadlines that no
student-facing tool surfaces to them — FSA-ID creation (do it weeks early),
their FAFSA contributor section + IRS consent, CSS Profile parent financials
where required, verification documents, and (later) the PLUS application and any
appeal. A parent-side deadline view is a real gap, not a nice-to-have.

### 4. Parent borrowing (PLUS) is the highest-stakes decision a parent makes alone (STRONG)

- Parent PLUS has **no ability-to-pay underwriting** and lets parents borrow up
  to the full cost of attendance. Brookings found repayment outcomes worsening —
  8.8% of parents entering repayment in 2014 owed over $100,000 (vs 0.4% in
  2000), with default rising across the board and alarmingly slow repayment at
  the worst decile of institutions
  ([Brookings, Looney & Lee](https://www.brookings.edu/articles/parents-are-borrowing-more-and-more-to-send-their-kids-to-college-and-many-are-struggling-to-repay/)).
- The Century Foundation counts **3.7M+ Parent PLUS families owing $104B+**,
  with sharply racialized harm: ten years into repayment, parents whose children
  attended top-Black-enrollment colleges still owe **96% of principal** on
  average (vs 47% for top-white-enrollment colleges); defaulting parents have
  had **Social Security benefits garnished**
  ([TCF, "Parent PLUS Borrowers"](https://tcf.org/content/report/parent-plus-borrowers/)).
- Families half-know the danger: 64% expect the _student_ to help repay the
  parents' loans, and 66% support federal loan limits
  ([Sallie Mae 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/)).

No mainstream tool tells a parent "this PLUS amount is unsafe for your finances"
_before_ they sign. That is squarely inside unicoach's money-truth strength.

### 5. Timeline + cost anxiety is high and rising; the process itself is the pain (STRONG)

The Princeton Review's 2026 College Hopes & Worries survey (9,446 respondents;
2,267 parents)
([press release](https://www.princetonreview.com/press/college-hopes-worries-press-release-2026),
[survey hub](https://www.princetonreview.com/college-rankings/college-hopes-worries)):

- **73% report high or very-high application stress** (56% in 2003). The top
  stressor is **"sticker shock" (37%)**, ahead of admission expectations (29%),
  deadlines (19%), and information overload (15%).
- The biggest worry is now **debt (35%)** — in 2003 it was rejection (52%) and
  debt was 6%.
- The toughest part of the experience: **"completing admission and aid
  applications" (42%, up from 35%)** — administration, not essays.
- 93% are applying for aid; **50% of parents (vs 34% of students) estimate total
  cost above $150,000** — parents carry the cost dread.

### 6. Over-involvement is common, and the thing parents optimize for doesn't pay (STRONG on prevalence; MODERATE on outcomes)

- A nationally representative NYT/Morning Consult poll of parents of
  18-28-year-olds: **76% reminded their adult children of deadlines, 75% made
  appointments for them, 16% of college parents texted/called to wake them for
  class or tests, 11% would contact their child's employer, 8% contacted a
  professor or administrator about grades**
  ([NYT, "How Parents Are Robbing Their Children of Adulthood," 2019](https://www.nytimes.com/2019/03/16/style/snowplow-parenting-scandal.html),
  verified via
  [Wayback](https://web.archive.org/web/2019/https://www.nytimes.com/2019/03/16/style/snowplow-parenting-scandal.html)).
  The piece's thesis: snowplow parenting clears obstacles so children never meet
  failure — the behavior unicoach's inversion exists to redirect.
- Challenge Success (Stanford GSE-affiliated), reviewing outcome research across
  100,000+ surveyed students: **college selectivity shows no significant
  relationship with later learning, job satisfaction, or well-being**;
  engagement/"fit" is what predicts thriving, with only modest earnings effects
  of selectivity concentrated in first-gen/underserved students
  ([white paper, "A 'Fit' Over Rankings," 2018](https://challengesuccess.org/wp-content/uploads/2021/04/Challenge-Success-White-Paper-on-College-Admissions-October-2018.pdf)).
  This is the evidence base for telling a parent: prestige-pushing buys stress,
  not outcomes.

### 7. What parents want to know vs what students want private (MODERATE — thinnest direct evidence)

- The legal default flips at exactly this life stage: under **FERPA, when a
  student turns 18 or enters postsecondary at any age, all records rights
  transfer from parent to student**
  ([U.S. Dept. of Education, "What is FERPA?"](https://studentprivacy.ed.gov/faq/what-ferpa)).
  Colleges will not talk to parents without the student's consent; a product
  that defaults to student-controlled sharing matches the legal regime the
  family is about to enter.
- Preference divergence is measurable: **47% of parents want the college within
  250 miles of home vs 33% of students**, a gap that has widened every year
  since 2007, and parents' and students' "dream college" lists differ
  ([Princeton Review 2026](https://www.princetonreview.com/press/college-hopes-worries-press-release-2026)).
- Students lean on informal channels (friends, family, social media) while
  parents use formal ones (aid offices, government sources)
  ([Sallie Mae 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/))
  — the two halves of the family literally research in different places.
- I found **no verifiable quantitative survey on which parts of the application
  students hide from parents**; treat "students want essay/decision privacy,
  parents want progress visibility" as informed inference, not cited fact.

### 8. Where existing tools fail parents (MODERATE — partly inference from the above)

- **Trusted help is scarce and analog:** the most-used and most-trusted source
  is the college financial aid office (used by 50%, trusted by 81%); **AI tools
  are the least trusted (43%)** despite 18% usage
  ([Sallie Mae 2026](https://www.salliemae.com/about/leading-research/how-america-pays-for-college/)).
  An AI coach earns parent trust by citing sources and being right about money,
  not by being conversational.
- **NACAC's public reporting** confirms counselor capacity is a structural gap
  (its State of College Admission covers caseloads; detail is member-gated —
  [landing page](https://www.nacacnet.org/state-of-college-admission-report/)),
  so parents backfill the counselor role with no training.
- The dominant tools (Common App, Naviance/SCOIR, college portals) are
  **student-accounts-only**; the federal aid system forces parents in but gives
  them a separate, confusing account with no shared view. Nothing on the market
  coaches the parent's _behavior_ — everything either excludes them or hands
  them the steering wheel.

## Ranked parent needs (by evidence strength)

1. **A money plan and the scripted affordability conversation** — largest
   funder, least-discussed topic, top stressor. (Sallie Mae; Princeton Review)
2. **FAFSA-as-contributor navigation with parent-owned deadlines** — legally
   required role, 56% need help, 25% know the open date. (FSA Handbook; Sallie
   Mae; Hechinger)
3. **A PLUS-loan guardrail before signing** — unbounded borrowing, documented
   ruin, racialized harm. (Brookings; TCF)
4. **Role coaching: how to support without directing** — snowplow behaviors are
   majority behaviors; the prestige goal they serve doesn't predict outcomes.
   (NYT/Morning Consult; Challenge Success)
5. **A shared-but-boundaried view of the student's process** — legal default
   (FERPA) and preference divergence support student-controlled sharing; direct
   survey evidence on privacy preferences is thin.

## Implications for unicoach (opinionated, ranked)

1. **The parent's FIRST coaching session should produce a written 4-year money
   plan and book the family cost conversation.** Deliverables: a real total-cost
   number for the current list (parents already dread $150k+; give them truth
   instead of dread), a who-pays-what split, and a scheduled, scripted
   student-parent conversation covering the three under-discussed topics
   (all-years cost, field starting salary, free money). This is unicoach's
   existing money strength pointed at the person who actually funds 50% of
   college.
2. **Ship a parent-owned deadline track, separate from the student's.** FSA ID
   early, FAFSA contributor section + IRS consent in October, CSS Profile parent
   financials, verification, PLUS decision in spring. The parent is a _required
   actor_ the current tools treat as a bystander. Include the
   mixed-status/no-SSN path explicitly — it is where the system hurts families
   most.
3. **Build the PLUS guardrail: an ability-to-pay check before any PLUS
   recommendation, ever.** The federal government won't underwrite; unicoach
   should. "Here is the monthly payment at this amount, against your stated
   finances; here is the line we advise you not to cross." This is the
   highest-stakes single intervention in the product.
4. **Coach the inversion explicitly, with receipts.** When a parent asks
   unicoach to push rankings or take over tasks, the coach should cite the
   evidence (selectivity ≠ outcomes; deadline-reminding is what 76% of parents
   do and it delays adulthood) and redirect to money, logistics, and emotional
   support. This is the product's point; make the coach say it out loud.
5. **Default sharing to student-controlled, coarse-grained status
   (FERPA-shaped).** Parent sees milestones and money tasks; essays, chats, and
   decision deliberations stay private unless the student shares. Frame it to
   parents as practice for the FERPA world they enter at enrollment — a selling
   point, not a limitation.
6. **Cite sources in parent-facing money answers.** Parents trust aid offices
   (81%) and distrust AI (43%); an AI coach that shows its federal/primary
   sources borrows the trust of the sources.
