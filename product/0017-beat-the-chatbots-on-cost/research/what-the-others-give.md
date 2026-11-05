# What the others give: assistants, cost tools, and what families ask for

Research report for brief 0017 (beat the chatbots on cost). Written 2026-11-04
against the one-trial transcript in
[`side-by-side-2026-11-04.md`](side-by-side-2026-11-04.md): a Washington senior,
household income about $120k, six schools (UW Seattle, Western Washington, Cal
Poly SLO, UC Berkeley, San Diego State, Oregon State).

## METHOD

**Read first.** The side-by-side transcript above, in full.

**Searched** (WebSearch, standard mode, 2026-11-04): chatbot college-cost
accuracy (ChatGPT, Gemini, Google AI Overview/AI Mode, Perplexity, Claude);
published evaluations of AI on personal finance and student aid; the dedicated
tools (Niche True Cost, College Scorecard, BigFuture, MyinTuition, TuitionFit,
Appily, school NPCs, the federal NPC Center and template); family research
(Sallie Mae How America Pays, Lumina-Gallup, College Board Trends, EAB and
Spark451 surveys); counsellor guidance (NASFAA, NACAC, the College Cost
Transparency Initiative, GAO); forum threads on College Confidential and
r/ApplyingToCollege; the 2026-27 SAI formula and its predictive power; the
regional exchanges (WUE, MSEP, NEBHE, SREB ACM); and the school-specific facts
the transcript turns on (UC nonresident aid, Husky Promise, Washington College
Grant, Oregon State WUE, Berkeley's 2026-27 budget).

**Fetched and used** (WebFetch): FSA Handbook 2026-27 ch. 3; Berkeley's
cost-of-attendance and nonresident-aid pages; WSAC's Washington College Grant
award table; Oregon State's WUE page; WICHE's WUE page; the Scorecard glossary;
the IPEDS net-price FAQ; the federal NPC Center; the College Board NPC directory
and MyinTuition directory; Middlebury's and Wellesley's MyinTuition pages; the
James G. Martin Center and Inside Higher Ed pieces on Niche True Cost; the Niche
Decoder press release (via a PR mirror); the College Confidential NPC-vs-Niche
thread; two r/ApplyingToCollege threads (via a snapshot mirror); the Campus
Technology summary of Penn AHEAD's NPC study; the JSFA "In the Right Ballpark"
abstract; GAO-23-104708's product page; the collegeprice.org standards page;
Sallie Mae's 2025 press release and 2026 report page; Gallup's 2025 Lumina
write-up; College Board Trends highlights; EAB's student-survey release;
Education Week on the same survey; OHO's "four in ten" post; the Search Engine
Journal write-up of The College Investor's 2024 AI Overview test; InvestmentNews
on Saturn's "Artificial Authority"; DIY Investor on InvestorsObserver's chatbot
test; The Markup on California college chatbots; the NBER digest of Dynarski and
Scott-Clayton; MEFA's SAI walk-through; Fairfield's FM/IM page; Appily's
sticker-vs-net article; Husky Promise.

**Failed.** 403: niche.com (FAQ, how-it-works), thecollegeinvestor.com,
nasfaa.org (issue brief, 2026 HAP summary), globenewswire (EAB parent survey),
businesswire (Spark451), theeduledger, gse.upenn.edu. 404: SF Examiner Niche
piece, collegecost.org standards path, 860amtheanswer mirror. Timeout (three
tries): studentaid.gov/aid-estimator. Paywall/empty: chronicle.com (Apr 15 2026
article), nerdwallet, tomsguide. Binary only: Sallie Mae HAP 2025 PDF, OHO "Be
Found" PDF, GAO highlights PDF (no pdftotext on this machine). Where a figure
below comes only from a search-result snippet of a page I could not open, it
says so.

**Not found at all.** No published evaluation that scores ChatGPT, Gemini, AI
Overview, Perplexity or Claude specifically on college cost-of-attendance or
net-price questions. No forum thread where a family names a chatbot as the
source of a wrong college price. The chatbot evidence is therefore (a) the one
trial in the transcript, (b) general personal-finance accuracy studies that
include student-loan and FAFSA items, and (c) a large body of evidence about the
same error modes in the schools' own calculators, which the chatbots inherit
because they cite those pages.

## 1. What the general-purpose assistants give

### 1.1 What the trial showed, checked against primary pages

The transcript's reading holds up, with one correction in ChatGPT's favour and
one against Google.

- **ChatGPT's Berkeley figure is verifiable.** Berkeley's own 2026-27 page gives
  a California-resident residence-hall total of $54,674 and lists Nonresident
  Supplemental Tuition at $39,270 without stating a nonresident total. $54,674 +
  $39,270 = $93,944, exactly ChatGPT's number.
  <https://financialaid.berkeley.edu/how-aid-works/cost-of-attendance/>
- **Google's $89,700+ matches no page total I found.** Its tuition line
  ($57,736) is not Berkeley's $18,214 + $39,270 = $57,484, and its housing
  ($18,926) is not Berkeley's $16,640 + $7,000 meal plan. The Overview cited
  U.S. News alongside Berkeley, so the row is most likely a blend. Same page as
  above.
- **Gemini's Husky Promise claim is wrong.** Husky Promise requires Washington
  residency and meeting all Pell Grant eligibility criteria; it states no income
  threshold and is not a middle-income programme.
  <https://www.washington.edu/huskypromise/>
- **Gemini's Oregon State range presents a competitive award as the price.**
  Oregon State: "WUE Scholarships are competitive" and "Only about 30% of
  students from WUE-eligible states and territories will be offered the WUE
  Scholarship"; selection is "a holistic review of their admission application";
  apply by February 3 for full consideration.
  <https://admissions.oregonstate.edu/wue>
- **Google's and ChatGPT's "nonresident aid is restricted" is the policy, not a
  hedge.** Berkeley: "New students who are not residents of California will not
  be eligible for our need-based grants or most scholarships" and "institutional
  funds to cover nonresident expenses are not available."
  <https://financialaid.berkeley.edu/how-aid-works/meeting-nonresident-costs/>
  UC Davis says the same for state and university need-based funding.
  <https://financialaid.ucdavis.edu/node/896> ChatGPT's "has substantial
  financial aid, but nonresident aid eligibility is more restricted" is
  therefore wrong in emphasis: for this family the answer at a UC is "none".
- **unicoach's in-state net price is a band mean, and the family's actual state
  grant is computable.** For 2026-27 the Washington College Grant for a family
  of four pays 100% at or below $83,500 and tapers to a 10% award up to
  $139,500; the 10% tier is $1,320 at UW and $880 at Western.
  <https://wsac.wa.gov/node/428> A $120k family of four therefore sits in the
  bottom tier, and its in-state price is sticker minus roughly $1k plus any
  merit, not the $110k+ band mean of $28,383 that unicoach gave (which averages
  households from $110k upward, many of them merit recipients).

### 1.2 Where the figures come from and how current they are

- The two chatbots that cited anything cited the schools' own cost pages and
  U.S. News (transcript, "Sources shown"). ChatGPT's and Google's figures were
  2026-27, the current admission cycle.
- OHO's 2026 "Be Found" benchmark reports that for tuition questions ChatGPT
  draws "80.8% of cited sources" from school websites. That figure comes from
  the search snippet of a PDF whose text I could not extract
  (<https://www.oho.com/sites/default/files/2026-04/Be%20Found%20-%20AI%20Visibility%20%E2%80%93%20OHO%20U%202026-compressed.pdf>);
  treat it as a vendor figure.
- The same vendor's survey (March 2026, 530 respondents) says 40% of students
  actively researching college have used AI for the search, 36% start with AI
  against 32% with Google, and 65% rate AI-generated information as accurate,
  "largely because of visible source citations".
  <https://www.oho.com/blog/four-ten-students-already-ask-ai-about-colleges-what-are-they-learning-about-yours>
- Currency is therefore not the chatbots' weakness. When a school's page is
  current, the chatbot is current. When the page is stale (section 2.1 on NPCs),
  the chatbot is stale with it, and nothing in the answer says so.

### 1.3 Documented error modes

No study isolates college prices. Three bodies of evidence bear on the error
modes the transcript shows.

**Personal-finance accuracy tests that include student aid.**

- The College Investor ran 100 personal-finance queries through Google AI
  Overviews (Oct 2024): 57% accurate, 43% "misleading or incorrect", 12%
  "completely incorrect". Items that failed included "Can I file as independent
  for FAFSA?" and outdated student-loan repayment-plan information;
  time-sensitive figures were the weak spot.
  <https://www.searchenginejournal.com/googles-ai-fails-at-43-of-finance-queries-study-finds/530542/>
  Its 2025 rerun reported 63% correct / 37% misleading, with invented
  institutions ("Sally May") still appearing; the page returned 403 to me and
  these figures are from the search snippet of
  <https://thecollegeinvestor.com/66208/37-of-google-ai-finance-answers-are-inaccurate-in-2025/>.
- Saturn's "Artificial Authority" (reported Sep 2026) ran 121 money questions
  five times each through 17 current models including free and paid ChatGPT,
  Gemini, Claude and Copilot: 43% average accuracy, 12% on multi-part questions
  with interacting rules and precise figures, free models failing 63% of the
  time. Failure types: incomplete answers 37.1%, "omitting a required figure,
  limit, or deadline" 18.4%, fabricated rules 6.1%, outdated guidance 2.3%.
  Wrong answers came "in the same authoritative, fluent tone as correct ones".
  Student loans were in scope; college prices were not.
  <https://www.investmentnews.com/fintech/ai-chatbots-give-wrong-financial-answers-most-of-the-time-study-finds/268267>
- InvestorsObserver (Jan 2026) ran 21 college-financing scenarios through
  ChatGPT, Perplexity and Grok. It found no hallucinations, but all three "often
  failed to quantify the tuition shortfall" between loan caps and cost,
  Perplexity was "overly optimistic" ("graduating debt-free remains possible"),
  and Perplexity was the one that pointed users to net price calculators.
  <https://www.diyinvestor.net/?p=40106>

The transcript's error pattern is the one these studies describe: not invented
schools, but a missing figure (no net price anywhere), a missing condition (WUE
as the price), a stale or blended number, and confident prose around all of it.

**Institutional chatbots.** The Markup and CalMatters (Mar 2026) tested
California community-college bots and found a financial aid office's "hours and
dates" given wrongly, a former president named as current, and students who
stopped using the bot for aid questions because "it kept asking questions
instead of giving a clear answer".
<https://themarkup.org/machine-learning/2026/03/07/california-colleges-spend-millions-on-faulty-ai-systems-the-chatbot-is-outdated>
Different product class, same lesson about asking before answering.

**Families on forums.** I found no thread naming a chatbot as the source of a
wrong college price. I found many naming the schools' own calculators:

- r/ApplyingToCollege (May 2025, small-business household): "Every single
  calculator gave us estimates that were off by 15k to 30k per year, all in the
  wrong direction"; schools estimated at ~$35k came in at ~$55k. Top reply:
  "Small businesses are the number one thing that doesn't play well with net
  price calculators." W-2 households in the same thread found their NPCs
  accurate.
  <https://reddit.sentinel-team.org/posts/1quvjl9/snapshots/2026-02-03T22%3A49%3A59.875027Z>
- r/ApplyingToCollege (Pell-eligible OP): a commenter tested Michigan's NPC as
  an out-of-state student, "it said I would only have to pay $22k a year", "They
  are EXTREMELY inaccurate." Advice given: focus on in-state publics and
  privates that meet full need.
  <https://reddit.sentinel-team.org/posts/1qv5meb/snapshots/2026-02-03T22%3A49%3A59.875027Z>
- College Confidential (May 2026): a parent compared Niche True Cost with 11
  schools' NPCs: "for 4 TC was within 10% of NPC, 3 were 10-20% over NPC, and 4
  were 40-60% under NPC." A reply on why the school's number matters even when
  wrong: an aid package below the NPC gives grounds to call the aid office;
  below Niche's estimate, "good luck arguing your case."
  <https://talk.collegeconfidential.com/t/ever-compare-net-price-calculator-vs-niche-true-cost/3704609>

### 1.4 Per assistant

| Assistant          | Source pattern seen or reported                                                     | Net price? | Residency / exchange                 | Four-year         | Follow-up asked                  |
| ------------------ | ----------------------------------------------------------------------------------- | ---------- | ------------------------------------ | ----------------- | -------------------------------- |
| Google AI Overview | school COA pages + U.S. News; 2026-27; blended Berkeley row                         | none       | residency named, "unlikely" warning  | tuition-only diff | major, scholarships, living plan |
| ChatGPT (free)     | school pages; 2026-27; Berkeley exact; Cal Poly major range                         | none       | WUE conditional at OSU; UC hedged    | yes, flat         | household size, savings, living  |
| Gemini Flash-Lite  | no sources; ranges                                                                  | none       | WUE as the price at OSU; Husky wrong | "$150,000+"       | none                             |
| Perplexity         | not trialled; InvestorsObserver: cites, optimistic, sent users to NPCs              | n/a        | n/a                                  | n/a               | n/a                              |
| Claude             | not trialled; Saturn: best overall on money questions at 61% pass, 33% on hard ones | n/a        | n/a                                  | n/a               | n/a                              |

The Perplexity and Claude rows are the only evidence I could find; neither
source asked a college-price question.

## 2. What the dedicated tools give

### 2.1 Schools' own net price calculators and the federal template

- **Mandate and definition.** The Department's NPC Center defines net price as
  "the amount that a student pays to attend an institution in a single academic
  year AFTER subtracting scholarships and grants" and only links to calculators
  "available on a college's or university's website"; it computes nothing.
  <https://collegecost.ed.gov/net-price>
- **Template inputs.** The federal template asks dependency status, income
  level, living arrangement and (for publics) residency, and returns a median
  net price for the matching bracket from the school's own prior data
  (Department of Education NPC guide, 2011, via
  <https://naicu.edu/media/3v3l1a31/20110510_deptednetpricecalcguide.pdf>; I
  could not open the current NCES template page). Schools may instead run their
  own calculator (College Board's, Ruffalo's, CAP's) with assets, household
  size, number in college and academics.
- **Accuracy.** "In the Right Ballpark?" (JSFA 2016) compared federal-template
  estimates with actual packages for low-income first-years at seven
  institutions: grant estimates "correlate highly" with actual grants, but
  "individual aid packages varied substantially among socioeconomically similar
  students". Its recommendations: publish variability, add a merit input, update
  annually. <https://ir.library.louisville.edu/jsfa/vol46/iss2/3>
- **Staleness.** Penn AHEAD's 2019 study of 80 four-year institutions: "some 40
  used information that was at least two years old", two calculators could not
  be found, and "a third of the institutions failed to highlight a federally
  defined net price". Recommendation: use current or prior-year data, include
  all costs, and never ask students to estimate their own spending.
  <https://campustechnology.com/articles/2019/05/02/net-price-calculators-failing-in-mission-of-helping-students.aspx?p=1>
  (study: <https://ahead-penn.org/node/732>). Niche's FAQ claims three in four
  NPCs are out of date (search snippet; page 403; Niche sells the alternative).
- **Out-of-state and exchanges.** The federal template branches on residency but
  has no exchange logic; whether a WUE rate appears depends on whether the
  school built it in. None of the forum threads reports an NPC that surfaced
  WUE.

### 2.2 Federal data: College Scorecard

- **What the figure is.** "Net price is calculated as the average over all
  full-time, first-time students who receive federal financial aid"; "For public
  schools, this is only the average cost for in-state students." The current
  variables are the "2023-24 award year cohort".
  <https://collegescorecard.ed.gov/data/glossary/> IPEDS confirms: "average net
  price is calculated only for students who paid the in-state or in-district
  tuition rate", with the Title IV cohort "reported by income category".
  <https://nces.ed.gov/IPEDS/report-your-data/faq-average-net-price>
- **Inputs.** None: pick a school, read five income bands.
- **Year.** 2023-24 as of today, the same vintage unicoach shows. The lag is
  structural (institutions report a completed award year, NCES publishes it the
  following year).
- **Out-of-state.** No net price exists for nonresidents; only the published
  out-of-state tuition. This is exactly the gap unicoach reported honestly and
  the chatbots papered over.

### 2.3 Niche True Cost and Financial Aid Decoder

- **Figure.** A personalised estimate, "defaults to a four-year total rather
  than an annual figure" with a per-year toggle, an institutional-aid estimate,
  a funding-gap line, a safe/caution/risky rating against projected earnings, up
  to 20 colleges. Inside Higher Ed, Nov 5 2025:
  <https://www.insidehighered.com/index%2Ephp/node/195396>
- **Inputs.** "Information similar to what families would enter in a college net
  price calculator or the FAFSA", the amount the family wants to pay out of
  pocket, planned career and city (same source); "household income, state
  residency, and academic profile", with the tool assuming "middle-class
  students meet average academic profiles for the schools selected" (James G.
  Martin Center, Jan 5 2026:
  <https://jamesgmartin.center/2026/01/another-college-costs-calculator/>).
- **Sources.** Institutional aid from College Aid Pro, which licenses net
  tuition data for ~4,000 colleges and keeps a database of user-submitted award
  letters; earnings from BLS (Inside Higher Ed, above).
- **Accuracy claim.** Niche's internal testing: within $2,500 or 5% of the
  school's NPC 77% of the time (search snippets of Niche's how-it-works page and
  an SF Examiner piece; neither opened). The College Confidential test in
  section 1.3 found a third "wildly off". The Martin Center test produced
  first-year costs of $3,579 at UNC-Chapel Hill and $9,063 at Duke for a
  middle-class profile and called the gift-aid assumptions "exceptionally high".
- **Critics.** Urban Institute's Kristin Blagg: "Especially with that four-year
  lens, even small changes could make pretty substantial differences"; four-year
  totals "may be intimidating to a fault"; figures don't match Scorecard, likely
  "cost-of-living metrics". Colorado College's Karen Kristof: "I just felt like
  I wanted a lot of yellow signs." (Inside Higher Ed, above.)
- **Decoder** (Mar 17 2026): upload aid letters, get "a standardized,
  side-by-side breakdown", itemised direct/indirect costs, loans and net price,
  a funding gap, and a toggle adding "expenses often missing from aid letters,
  such as travel, books, and personal costs". Pilot: "84% of families adjusted
  their college lists".
  <https://www.techdogs.com/tech-news/pr-newswire/niche-launches-college-financial-aid-comparison-tool-to-help-families-decode-and-compare-offers>
- **Out-of-state / exchanges.** Not described anywhere I could read. Niche says
  it does not cover commuter, transfer, international or athletic-recruit
  students (FAQ, search snippet).

### 2.4 MyinTuition (now College Board)

- **Inputs.** Six: "total family income, value of their house, any remaining
  mortgage balance, cash in the bank", and "savings held in retirement accounts
  and other investment accounts" (Middlebury's description).
  <https://www.middlebury.edu/stories/node/3031>
- **Output.** "The estimates are given as low range, 'best estimate,' and high
  range", covering grants, loan and work-study expectations; "While not a
  guarantee of financial aid, the estimates are intended to provide an accurate
  initial starting point." (same page)
- **Accuracy claim.** Creator Phillip Levine says about three-quarters of
  students pay within $5,000 of the estimate (search snippet of a MyinTuition
  press page; no methodology published that I could find).
- **Coverage.** 69 colleges, ~800,000 estimates a year, acquired by College
  Board May 2026.
  <https://www.wellesley.edu/news/wellesley-college-professors-myintuition-acquired-by-college-board-to-help-more-students-understand-college-costs>
  The directory lists 65 mostly private, meet-need institutions; UVA and William
  & Mary are the public exceptions I recognised. None of the six schools in the
  trial participates.
  <https://bigfuture.collegeboard.org/pay-for-college/get-started/myintuition-estimators>
- **Out-of-state / exchanges.** Not applicable; the model is a per-college
  institutional-aid fit.

### 2.5 BigFuture / College Board NPC

A directory: "estimate your net price at hundreds of colleges that use the
College Board Net Price Calculator", each link opening that school's
College-Board-hosted calculator; a few link to the school's own. No inputs,
outputs or data year are described on the directory page.
<https://bigfuture.collegeboard.org/pay-for-college/get-started/net-price-calculator>
The College Board calculator a school hosts asks the CSS-Profile-style set
(income, assets, household, number in college, academics) that the school
configures; I could not open one to confirm the current fields.

### 2.6 TuitionFit

Crowdsourced actual award letters. Students upload a letter, it is redacted, the
"actual price that a student would see on their bill" is computed, and the
uploader sees "real prices and corresponding aid offers shared by students with
a similar financial need and merit profile" for free. Founder interview:
<https://educationnext.org/straight-conversation-kelley-blue-book-for-college-costs-salisbury>
It answers "what did families like us actually pay", not "what will we pay"; it
needs a letter to unlock, so it is a spring tool. Scale is self-reported ("500+
students empowered annually", "5,000+ colleges") and unverified.

### 2.7 Appily

"College profiles include a college's net price based on your family's income
bracket" and, with a profile, "the average net price for students like you".
<https://www.appily.com/articles/applications/the-difference-between-college-sticker-price-and-net-price>
Source and year are not stated on that page; the FAQ says data comes from
"verified school partners, federal databases, and scholarship foundations"
(<https://www.appily.com/faqs>). This is the Scorecard band figure with a
profile wrapper, and inherits its in-state-only basis.

### 2.8 Regional exchanges

- **WUE.** Nonresidents from 15 states and 3 territories "pay no more than 150%
  of the institution's resident tuition rate" at 170 public institutions;
  "Participating colleges and universities set their own WUE eligibility
  criteria" (GPA, majors, deadlines, caps, first-year only); apply to the
  school, not WICHE. <https://www.wiche.edu/tuition-savings/wue/> Oregon State:
  ~30% of eligible-state applicants, holistic, Feb 3
  (<https://admissions.oregonstate.edu/wue>). Western Washington also offers it
  inbound (transcript).
- **MSEP.** Publics "charge students no more than 150% of the in-state resident
  tuition rate for selected programs", privates 10% off; voluntary per
  institution, ~70 institutions; Illinois and Michigan have no participating
  institutions. <https://mhec.org/msep>
- **NEBHE Tuition Break.** Reduced rate for New England residents, in most cases
  only for "programs that are not offered in a student's home state", with the
  approved list republished each September; some campuses now admit all five
  other states regardless of major.
  <https://nebhe.org/tuitionbreak/eligibility-faqs/>
- **SREB Academic Common Market.** In-state tuition for programmes a resident's
  home-state publics do not offer; state certification required; caps fill early
  (search summary; I did not open an SREB page).

No tool in this section applies an exchange automatically. Every one puts the
burden on the family to know the programme exists, and the only assistant in the
trial that named WUE correctly (ChatGPT) did so conditionally and without the
30% odds or the deadline.

### 2.9 Summary table

| Tool                    | Figure                                 | Inputs                                                            | Year                                     | Out-of-state / exchange             |
| ----------------------- | -------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------- | ----------------------------------- |
| School NPC (federal)    | median net price by bracket            | dependency, income level, living, residency                       | school's choice; ~half ≥2 yrs old (2019) | residency branch; no exchange logic |
| School NPC (vendor)     | personalised estimate                  | income, assets, household, number in college, academics           | school's choice                          | as configured                       |
| College Scorecard       | band mean, aided first-years, in-state | none                                                              | 2023-24                                  | none for nonresidents               |
| Niche True Cost         | personalised, four-year default        | NPC/FAFSA-like + budget + career/city + academics                 | unstated                                 | not described                       |
| MyinTuition             | low / best / high range                | six: income, house value, mortgage, cash, retirement, investments | per college                              | n/a (private meet-need schools)     |
| BigFuture NPC directory | links to school calculators            | per school                                                        | per school                               | per school                          |
| TuitionFit              | real letters from similar students     | upload a letter                                                   | current cycle                            | whatever was uploaded               |
| Appily                  | Scorecard band with a profile          | profile income                                                    | unstated                                 | inherits in-state basis             |
| NPC Center (ED)         | none; links                            | none                                                              | n/a                                      | n/a                                 |

## 3. What families say they want

**Cost is the deciding axis, and it is applied at every stage.** Sallie Mae /
Ipsos, How America Pays for College 2026 (1,000 students, 1,000 parents, Apr 22
to May 26 2026): 79% eliminated a school on cost alone; used cost to eliminate
schools before researching 58%, before applying 60%, after admission but before
aid offers 53%, after offers 63%; price was the top choice factor (40%).
<https://www.salliemae.com/about/leading-research/how-america-pays-for-college/>
The 2025 edition had the same 79% and Sallie Mae's Rick Castellano asking for
"greater transparency when it comes to the actual cost of college".
<https://news.salliemae.com/news-releases/news-releases-details/2025/Families-Report-Spending-More-But-Also-Missing-Out-on-Opportunities-to-Make-College-More-Affordable-According-to-How-America-Pays-for-College-2025/default.aspx>

A tool that answers only after the list is final has missed three of the four
elimination points.

**"What we'd actually pay", and the gap between expectation and offer.** HAP
2026: 57% received the aid they expected, 19% less; among $150k+ households only
46% got what they expected; 39% paid full advertised price; 35% believe "it's
not worth applying if parents earn too much"; 26% of non-filers skipped the
FAFSA because "income too high". Average spend $34,019, 47% borrowed. (same
report page)

**Four-year totals.** HAP 2026: 58% "created a plan to cover all years of
college before enrollment"; among borrowers only 44% had "discussed cost of all
years"; 84% were confident of a four-year finish against a 61% six-year
completion rate. (same page) Niche made four years the default; Blagg's caution
is that small per-year errors compound and the total "may be intimidating to a
fault" (section 2.3). Both can be true: show the year and the total, with the
growth assumption named (Oregon State's trustees approved 6.25% for 2026-27, per
the transcript's fact check).

**Fairness and confusion.** Lumina-Gallup 2025 (13,933 current and prospective
students, Oct 2024): 18% say four-year colleges charge fair prices, 59%
disagree; cost is the top reason enrolled students consider stopping out.
<https://news.gallup.com/poll/657686/college-prices-seen-unfair-worth-investment.aspx>

**Parents are already asking AI, and cost is their worry.** EAB's 2026 parent
survey (2,515 parents, Apr 16 to May 14 2026): 57% have used AI tools such as
ChatGPT to evaluate colleges; 64% cite cost as a top worry (search snippet of
<https://www.globenewswire.com/news-release/2026/08/03/3337445/0/en/More-than-Half-of-Parents-Use-AI-to-Research-Colleges.html>;
403 when fetched). EAB's student survey (5,000+ students, Oct to Nov 2025): 46%
use AI in the college search, up from 26% in spring 2025; 18% "removed a college
from consideration based on information surfaced through AI-generated search
results". <https://eab.com/about/newsroom/press/ai-in-college-search-survey/>
HAP 2026: 18% of families used AI tools as an information source and 43% rate AI
very or somewhat trustworthy, against 81% who trust the aid office. Spark451
(summer 2025): one in three students used AI "to support their college search
and answer questions around academic programs, affordability and campus life",
62% of them ChatGPT (search snippet of the Oct 1 2025 Business Wire release;
403).

**What counsellors and aid officers say an answer must contain.** The College
Cost Transparency Initiative (NASFAA, NACAC, ACE and others; 360+ institutions
committed by Sep 2023, now run by NASFAA) requires every aid offer to show an
estimated total cost of attendance "with a breakdown of costs paid to the
institution versus costs paid to others", grants and scholarships separated from
"student loans or other financing that must be repaid", a net price "derived by
subtracting grants and scholarships from the total Cost of Attendance", whether
aid is one-time or renewable and on what conditions, every loan "unambiguously
labeled as such", and "actionable next steps".
<https://www.collegeprice.org/standards>;
<https://www.NASFAA.org/news-item/31719/Over_300_Institutions_Across_the_Country_Commit_to_Student_Cost_Transparency_for_Financial_Aid_Offers>
GAO (Dec 2022, 176 colleges) found 41% of colleges omit a net price and 50%
understate it by leaving out costs or counting loans as aid, 91% in all; 55% do
not state total cost of attendance.
<https://www.gao.gov/products/gao-23-104708>;
<https://www.uaspire.org/news-events/gao-report-on-financial-aid-offers-shows-little-has-changed,-progress-is-needed>

unicoach already meets the CCT definition (loans never subtracted, grants only).
The chatbots do not give a net price at all, so they cannot fail it; Gemini
fails it by presenting a competitive scholarship as the price.

**Residency and exchanges.** Forum advice converges on "focus on publics in your
own state" (section 1.3) precisely because out-of-state net prices are the ones
nobody can estimate. The exchanges are the only lever at an out-of-state public
for a family above Pell, and every tool leaves them for the family to discover.

**Living-cost lines are soft.** Kelchen, Goldrick-Rab and Hosch (JHE 2017):
nearly half of colleges set off-campus living allowances at least 20% above or
below county-level estimates.
<https://ideas.repec.org/a/taf/uhejxx/v88y2017i6p947-971.html> That is a reason
to label the housing-and-food line as the school's estimate (unicoach does) and
to let the family override it, not a reason to withhold a total.

## 4. The aid-input question

### 4.1 The formula is public and small

The 2026-27 dependent-student formula (Formula A) in the FSA Handbook: parents'
available income = AGI plus listed untaxed income, minus taxes paid, a
payroll-tax allowance (Table A1), an income protection allowance by family size
(Table A2) and an employment expense allowance; parents' asset contribution =
12% of net worth above an asset protection allowance (Table A4), with the
primary residence excluded; the two are summed and assessed at 22% to 47% (Table
A5); the student adds 50% of income above a protection allowance and 20% of
assets; SAI floors at -1,500.
<https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2026-2027/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility>
The tables are in the 2026-27 SAI and Pell Grant Eligibility Guide:
<https://fsapartners.ed.gov/knowledge-center/library/handbooks-manuals-or-guides/2025-06-13/2026-27-student-aid-index-sai-and-pell-grant-eligibility-guide-updated-aug-25-2025>
Retirement accounts and the home are excluded on the asset side (MEFA's
walk-through,
<https://www.mefa.org/blog/heres-how-colleges-calculate-your-sai>). Since
2024-25 the parent contribution is no longer divided by number in college; the
FAFSA still asks the question and ignores it
(<https://nasfaa.org/news-item/31072/NASFAA_2023_Ends_With_Updates_Q_A_on_New_Student_Aid_Index>;
<https://financialaid.sfsu.edu/2024-2025-fafsa-changes>).

So ChatGPT's follow-up asked one input that still matters to the federal formula
(household size, via Table A2), one that matters at the 12% margin
(non-retirement savings), and one that is not an aid input at all (living plan,
which is a cost input). It did not ask the two that matter most for this list:
residency (already known) and academic profile (merit, WUE odds).

### 4.2 How good is an estimate from a few inputs

- Dynarski and Scott-Clayton (NBER, 2006): "Adjusted Gross Income ... marital
  status, family size, and the number of family members in college explain over
  three-quarters of the variation in federal grant aid"; "For 75 percent of
  applicants, using a simplified formula that includes only a subset of income
  items ... produces grants within $100 of those generated by the current
  formula."
  <https://www.nber.org/digest/oct08/federal-student-aid-process-not-efficient>
  This is the research the 2020 FAFSA Simplification Act was built on, and the
  formula it describes is the one now in force.
- MyinTuition's six questions (section 2.4) are an existence proof that income,
  home equity, cash and retirement savings can drive a range estimate that
  meet-need colleges are willing to put their names on; its creator's
  "three-quarters within $5,000" claim is unaudited.
- "In the Right Ballpark?" (section 2.1): high correlation in aggregate,
  substantial variation between similar families, because institutional aid
  depends on things the formula does not see.
- The federal government runs its own estimator from these inputs
  (<https://studentaid.gov/aid-estimator/>; it timed out for me three times, so
  I could not quote its disclaimer).
- Where the simple estimate breaks: self-employment and business assets,
  divorced parents, rental property, and farms. The r/ApplyingToCollege thread
  in section 1.3 is the clearest family-level account ($15k to $30k per year,
  "all in the wrong direction"). Schools that use the CSS Profile also apply
  institutional methodology, which still counts number in college and home
  equity (Fairfield's description:
  <https://www.fairfield.edu/admission-and-aid/financial-aid/policies/>); none
  of the six schools in the trial uses the Profile.

### 4.3 What the SAI buys for this family and this list

Very little, and that is the point. The SAI is a need figure; a price only
follows from it where a school converts need to grant.

- UW and Western: the Washington College Grant is a published income table, not
  an SAI table. A $120k family of four is in the 10% tier ($1,320 at UW, $880 at
  Western for 2026-27). <https://wsac.wa.gov/node/428> The family is far above
  Pell. Federal need at these two schools therefore converts to nothing further
  unless the school adds merit.
- UC Berkeley: no institutional need-based grants for new nonresidents; UC Davis
  the same (section 1.1). The SAI is irrelevant to the price.
- Cal Poly and San Diego State: the State University Grant and Cal Grant are
  resident-only (CSU catalog language via search; I could not open Cal Poly's
  own SUG page). Same result.
- Oregon State: price turns on the WUE scholarship (30% odds, holistic) and
  merit, not on need.

For this list the aid inputs ChatGPT asked for cannot move the answer more than
about $1,500 a year. Residency moved it by $40,000, and WUE could move it by
about $18,000 (150% of resident tuition versus full nonresident), both of which
were knowable before any follow-up question.

### 4.4 Is it responsible to compute one, versus linking to the NPC

Compute it, under four conditions, and still link the NPC.

1. **Range, not point.** MyinTuition's low / best / high is the right shape;
   JSFA's finding of "substantial variation" is the reason. A single dollar
   figure invites the "good luck arguing your case" problem.
2. **Name the inputs and the formula year.** The federal formula is public and
   changes annually; an estimate labelled "2026-27 federal formula from income,
   household size and non-retirement savings" is honest, "estimated cost after
   aid" is not.
3. **Convert need to price only where the conversion is known.** The WA Grant
   table, "no institutional need aid for nonresidents" at the UCs and CSUs, and
   Pell are known conversions. An unknown institutional policy gets the band
   mean with its label, not a guess.
4. **Refuse the cases the formula cannot see.** Business or farm ownership,
   divorced or separated parents, rental property: say so and hand off to the
   NPC and the aid office, which is what the forum veterans do.

Linking to the NPC alone is not the responsible alternative: roughly half were
two or more years stale when last audited (section 2.1), a third do not show a
federal net price, and none of the six schools' NPCs will surface WUE or the WA
Grant tier unless the school built it in. The link should carry the NPC's data
year when it can be read, so the family knows which of the two numbers is older.

## 5. Ranked: five things a cost answer must have to beat a chatbot without losing trust

1. **This year's sticker, from the school's page, dated on every line.** The
   chatbots' only clear win was currency: 2026-27 from the schools' pages, and
   ChatGPT's Berkeley figure reconciles to the page to the dollar (section 1.1).
   unicoach's 2023-24 federal figures were $2k low at UW and $14k low at
   Berkeley. Scorecard is the same 2023-24 cohort
   (<https://collegescorecard.ed.gov/data/glossary/>), so federal data cannot
   close this on its own; the current-year COA page can, and a date on each
   number is what distinguishes it from a blended U.S. News row.

2. **Residency applied per school, with the aid policy and the exchange that
   follow from it, as a next step rather than a caveat.** For this family the
   residency decision was worth $40k a year, the UC/CSU nonresident policy turns
   "aid may be restricted" into "none"
   (<https://financialaid.berkeley.edu/how-aid-works/meeting-nonresident-costs/>),
   and WUE at Oregon State is a 30% shot with a February 3 deadline
   (<https://admissions.oregonstate.edu/wue>). unicoach missed WUE; Gemini made
   it the price; ChatGPT named it without odds or date. The correct line is one
   sentence per out-of-state school.

3. **A net price that says whose it is, never subtracts loans, and offers a
   family-specific range from named inputs.** GAO's 91% and the CCT standard are
   the trust floor and unicoach already meets it (section 3). The next step is
   the state-grant tier computed from the published table
   (<https://wsac.wa.gov/node/428>) and, where the school converts need to
   grant, a low / best / high range from income, household size and
   non-retirement assets under the 2026-27 formula (section 4). The follow-up
   question should therefore ask household size and whether the family owns a
   business or is separated, not where the student will live.

4. **A four-year total beside the per-year figure, with the growth assumption
   stated.** 79% of families eliminate on cost and 58% plan for all years before
   enrolling (HAP 2026); ChatGPT gave a flat four-year table, Niche defaults to
   four years, Blagg warns the total compounds errors. Show both, name the
   assumption (Oregon State: +6.25% for 2026-27), and let the housing line be
   overridden, since nearly half of colleges' living allowances are 20% off
   (Kelchen et al. 2017).

5. **Provenance that is short, and a next step that is the school's own tool.**
   Families rate AI accurate "largely because of visible source citations" (OHO)
   and trust the aid office over everything (81%, HAP 2026). The transcript's
   two provenance paragraphs before the first number cost more than they earned;
   a dated source link per row costs nothing. The action at the end is the
   school's NPC with its data year read off, the COA page, and the exchange
   deadline, because the estimate a family can take into an aid office is the
   school's, not ours (College Confidential, section 1.3).
