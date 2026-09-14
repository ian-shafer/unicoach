package ed.unicoach.web.render

import io.ktor.server.application.ApplicationCall
import java.time.LocalDate

// RFC 204 -- "Who pays for college advice", the first family-facing explainer.
//
// This file is CONTENT. The model and the renderer are in `ExplainerPage.kt`,
// so a copy edit never touches HTML and an HTML edit never touches copy.
//
// Four rules govern every sentence below, and a reviewer should hold the file
// to them:
//
//  - EVERY load-bearing claim about a named company is an `ExplainerBlock.Claim`
//    carrying that company's OWN page and the date we read it. The type makes a
//    sourceless claim uncompilable; `WhoPaysPageTest` makes a claim smuggled
//    into `Prose` a test failure.
//  - QUOTED WORDS ARE THEIRS, verbatim, checked against the live page during
//    this run (the run archive's `source-check.md`). Anything the fetch did not
//    find is not on this page, in any form.
//  - NO INFERENCE ABOUT MOTIVE IS STATED AS FACT. The page says what a page
//    says; the reader draws the conclusion.
//  - NO SCORE, RANK, GRADE OR CHANCE FIGURE OF OUR OWN (brief 0011 D3). Every
//    number on this page is quoted from somebody else's page. That is why the
//    prose carries no digits at all, and why a test asserts it.

/** The date every third-party page below was fetched and its quote matched word for word. */
private val FETCHED_ON: LocalDate = LocalDate.of(2026, 9, 14)

/**
 * The date printed under the title. It IS the fetch date, not a copy of it,
 * because this page is only as current as the pricing pages it quotes
 * (RFC 204 D14(c)) — a re-fetch must move both or the "As of" line lies.
 */
private val AS_OF: LocalDate = FETCHED_ON

/** A page read during this run. The fetch date is supplied once, here. */
private fun createSource(
  publisher: String,
  title: String,
  url: String,
): ExplainerSource = ExplainerSource(publisher = publisher, title = title, url = url, fetchedOn = FETCHED_ON)

/** `GET` path of this guide. Routing, the site header and the tests all read it from here. */
const val WHO_PAYS_PATH = "/guides/who-pays-for-college-advice"

private val scoirHighSchools =
  createSource(
    publisher = "Scoir",
    title = "Pricing for high schools",
    url = "https://www.scoir.com/high-schools/pricing",
  )

private val scoirColleges =
  createSource(
    publisher = "Scoir",
    title = "Pricing for colleges",
    url = "https://www.scoir.com/colleges/pricing",
  )

private val collegeBoardSearch =
  createSource(
    publisher = "College Board",
    title = "Student Search Service",
    url = "https://studentsearch.collegeboard.org/",
  )

// THREE sources below are cited through the Internet Archive, and deliberately.
// `landscape.md` cites those snapshots, and a quote on this page is matched word
// for word against the snapshot text — repointing one at the live site would
// silently unanchor the quote it is cited for. The `/web/2025/` form redirects
// to the snapshot actually read: EAB 20260208173321, EducationDynamics
// 20260106152330, U.S. News 20251230072211. No live site refused this run's
// fetch; the archive is inherited from the research, not a workaround.
private val eabAppily =
  createSource(
    publisher = "EAB (Internet Archive snapshot)",
    title = "Appily solutions for colleges",
    url = "https://web.archive.org/web/2025/https://eab.com/solutions/appily/",
  )

private val unigoReview =
  createSource(
    publisher = "Unigo",
    title = "Review a college",
    url = "https://www.unigo.com/review-a-college",
  )

private val unigoRules =
  createSource(
    publisher = "Unigo",
    title = "College Review Scholarship rules",
    url = "https://www.unigo.com/review-a-college/rules",
  )

private val educationDynamics =
  createSource(
    publisher = "EducationDynamics (Internet Archive snapshot)",
    title = "Higher Education Enrollment Growth Solutions",
    url = "https://web.archive.org/web/2025/https://www.educationdynamics.com/",
  )

private val collegeVineHome =
  createSource(
    publisher = "CollegeVine",
    title = "CollegeVine for universities",
    url = "https://www.collegevine.com/",
  )

private val usNewsMethodology =
  createSource(
    publisher = "U.S. News & World Report (Internet Archive snapshot)",
    title = "How U.S. News Calculated the Rankings",
    url = "https://web.archive.org/web/2025/https://www.usnews.com/education/best-colleges/articles/how-us-news-calculated-the-rankings",
  )

private val collegeVineChancing =
  createSource(
    publisher = "CollegeVine",
    title = "Is CollegeVine chancing accurate?",
    url = "https://blog.collegevine.com/is-collegevine-chancing-accurate/",
  )

private val princetonReviewMethodology =
  createSource(
    publisher = "The Princeton Review",
    title = "Ranking methodology",
    url = "https://www.princetonreview.com/college-rankings/ranking-methodology",
  )

private val princetonReviewFaq =
  createSource(
    publisher = "The Princeton Review",
    title = "Best Colleges FAQ",
    url = "https://www.princetonreview.com/college-rankings/best-colleges/faq",
  )

private val princetonReviewRatings =
  createSource(
    publisher = "The Princeton Review",
    title = "College ratings",
    url = "https://www.princetonreview.com/college-rankings/college-ratings",
  )

private val collegeVineCalculator =
  createSource(
    publisher = "CollegeVine",
    title = "Admissions calculator",
    url = "https://www.collegevine.com/admissions-calculator/",
  )

private val sycophancyStudy =
  createSource(
    publisher = "arXiv",
    title = "Sycophantic AI Decreases Prosocial Intentions and Promotes Dependence",
    url = "https://arxiv.org/abs/2510.01395",
  )

/**
 * Every company this page speaks about by name.
 *
 * The guard set for RFC 204 D-B, handed to the page so `thirdPartyLeaks()` can
 * enforce it: a test asserts that no unsourced string on the page contains one
 * of these names. It counts the BLOCK TYPE, not a call spelling, so the same
 * sentence written a different way cannot slip past it.
 *
 * TWO tests hold the SET itself honest, in both directions, because a guard set
 * that drifts from the copy guards nothing. One asserts every name here is
 * spoken inside a claim, so a name cannot be left behind when its sentence
 * goes. The other asserts every source publisher on the page contains a name
 * from here, so a NEW company cannot be written about without joining the set.
 *
 * ONE DELIBERATE EXEMPTION: unicoach itself. Claims about our own business are
 * cited to our own pages, which are internal links rather than fetched
 * third-party URLs (D-E).
 */
val NAMED_THIRD_PARTIES: Set<String> =
  setOf(
    "Scoir",
    "College Board",
    "BigFuture",
    "EAB",
    "Appily",
    "Unigo",
    "EducationDynamics",
    "CollegeVine",
    "U.S. News",
    "Princeton Review",
  )

private val whyWeWroteThis =
  ExplainerSection(
    heading = "Why we wrote this",
    blocks =
      listOf(
        ExplainerBlock.Prose(
          "We read this market to decide what unicoach should build. The research is more " +
            "useful to a family than it is to us, so we are publishing it. One rule runs through " +
            "all of it: who pays for a product decides what that product is able to tell you.",
        ),
        ExplainerBlock.Prose(
          "Nothing here is a rumour or a guess about anyone's motives. Every claim about " +
            "another company is taken from that company's own pricing, methodology or product " +
            "page, and the quoted words are theirs. Those pages are listed at the foot, with the " +
            "date we read them. Pricing changes, so check that date.",
        ),
      ),
  )

private val whereTheMoneyComesFrom =
  ExplainerSection(
    heading = "Where the money comes from",
    blocks =
      listOf(
        ExplainerBlock.Prose(
          "Colleges buy two things from the companies that guide students: a place in front of " +
            "a student, and the student's own details. Some of those price lists are public.",
        ),
        ExplainerBlock.Claim(
          "Scoir sells college-search software to high schools and messaging to colleges, and " +
            "it publishes both price lists. The plan for grades 9-12 is listed at $4.80 per " +
            "student per year, and the page adds that “In addition to the per student per year " +
            "cost, each plan requires a one-time $250 Setup and Training fee”.",
          scoirHighSchools,
        ),
        ExplainerBlock.Claim(
          "For colleges the same company lists three annual tiers: $26,040, $39,960 and " +
            "$56,040. What the money buys is delivery. The middle tier carries match messaging " +
            "with “Promotion” folder delivery (6% read rate), and the top tier carries it with " +
            "“Primary Inbox” delivery & notification (40% read rate). The cheapest tier carries " +
            "no match messaging at all.",
          scoirColleges,
        ),
        ExplainerBlock.Claim(
          "College Board runs Student Search Service, which is one road a student's details " +
            "travel. Its page says “More than 1,200 colleges in the U.S. rely on Student Search " +
            "to find the right students for their campuses.” A student who joins agrees to share " +
            "“certain information about you, including your College Board account information, " +
            "exam score ranges, BigFuture college list, and details about your high school " +
            "performance and college preferences.”",
          collegeBoardSearch,
        ),
        ExplainerBlock.Claim(
          "EAB sells colleges the output of a similar funnel. Its page for Appily, a college- " +
            "search site students use for free, says “With Appily Leads, you can receive student " +
            "leads that are backed by machine learning, making them more likely to convert at " +
            "every stage of the funnel.” The same page states that “65% of college-bound high " +
            "school seniors are using Appily”.",
          eabAppily,
        ),
        ExplainerBlock.Claim(
          "Unigo gathers student reviews of colleges with a prize draw: “Review your college " +
            "for a chance to win a $1,000 scholarship.”",
          unigoReview,
        ),
        ExplainerBlock.Claim(
          "The rules of that draw say “a qualified panel of judges will select one (1) " +
            "potential winner based on the criteria of writing ability (25%), creativity (25%), " +
            "originality (25%), and overall excellence (25%).” So the review a student writes " +
            "about their college is also an entry in a writing competition.",
          unigoRules,
        ),
        ExplainerBlock.Claim(
          "EducationDynamics sells enrollment marketing to colleges, and states its result in " +
            "the buyer's terms: “Our clients have reduced their cost-per-start by 30%, " +
            "demonstrating industry-leading efficiency and unlocking significant cost savings.”",
          educationDynamics,
        ),
        ExplainerBlock.Claim(
          "CollegeVine publishes a free admissions calculator that students use. To " +
            "universities, the same company presents itself as “The AI operating system for " +
            "universities”, whose enrollment management module covers “Recruiting, application " +
            "review, yield modeling, and enrollment forecasting.”",
          collegeVineHome,
        ),
      ),
  )

private val whatThatBuysYou =
  ExplainerSection(
    heading = "What that buys you, and what it costs you",
    blocks =
      listOf(
        ExplainerBlock.Prose(
          "A family never sees the price list. It sees a number, a grade or a rank. Each one is " +
            "built out of particular choices, and the companies publish those choices.",
        ),
        ExplainerBlock.Claim(
          "U.S. News prints the recipe for its rankings. Its largest component is “Student " +
            "Outcomes (52% total weight)”. The second largest is “Peer Assessment (20%)”, which " +
            "its own page describes this way: it “captures key aspects of a school's quality, " +
            "such as innovative teaching and institutional health, which are often difficult to " +
            "measure otherwise.”",
          usNewsMethodology,
        ),
        ExplainerBlock.Claim(
          "The same page records what fell out of the recipe: “Factors like acceptance rates, " +
            "alumni giving, class size and high school class rank are no longer used in the " +
            "methodology but are updated in school profiles.” A rank a family followed a few " +
            "years ago was not measuring the same things as this year's.",
          usNewsMethodology,
        ),
        ExplainerBlock.Claim(
          "A chance percentage is a model, and a model leaves things out. CollegeVine says so " +
            "on its own blog: “While our chancing engine accounts for the most important factors, " +
            "there are some things that we don't yet consider, such as essays, rec letters, high " +
            "school context, early decision, and legacy.”",
          collegeVineChancing,
        ),
        ExplainerBlock.Claim(
          "A ranking can also rest on a single survey question. The Princeton Review's Happiest " +
            "Students list is “Based on how strongly students agree or disagree with the " +
            "statement, ‘I am happy at my school.’”",
          princetonReviewMethodology,
        ),
      ),
  )

private val theHonestMoves =
  ExplainerSection(
    heading = "The honest moves, credited by name",
    blocks =
      listOf(
        ExplainerBlock.Prose(
          "This page is about incentives, not villains. Several of the same companies do things " +
            "that cost them attention and help a family.",
        ),
        ExplainerBlock.Claim(
          "The Princeton Review refuses to crown a winner. Its FAQ says “We don't believe that " +
            "any one school is the best overall.” and “It's not which school is best academically " +
            "(there's no such thing) but which school is best for you that's important.”",
          princetonReviewFaq,
        ),
        ExplainerBlock.Claim(
          "It also marks its own missing data in public: “if a school has an Academic Rating of " +
            "60* (sixty with an asterisk), it means that the school did not report to us a " +
            "sufficient number of the statistics that go into the rating by our deadline.”",
          princetonReviewRatings,
        ),
        ExplainerBlock.Claim(
          "CollegeVine tells students plainly what a published statistic is not: “The " +
            "acceptance rate on a school's website is not your chance of getting in”.",
          collegeVineCalculator,
        ),
        ExplainerBlock.Claim(
          "College Board states a limit on what it passes to colleges, in its own words: “We " +
            "never share your actual test scores, grades, disability status, or phone number.”",
          collegeBoardSearch,
        ),
      ),
  )

private val whoPaysUnicoach =
  ExplainerSection(
    heading = "Who pays unicoach",
    blocks =
      listOf(
        ExplainerBlock.Prose(
          "Families pay us, by subscription. No college pays us for placement, for a badge, or " +
            "for a name on a list. We do not sell personal information.",
        ),
        ExplainerBlock.SelfCited(
          "That last sentence is in our Privacy Policy, and it is one click away:",
          target = SitePath.PRIVACY,
          linkText = "Privacy Policy",
        ),
        ExplainerBlock.SelfCited(
          "The service is sold to a family for personal, non-commercial use, which is written " +
            "into our terms:",
          target = SitePath.TERMS,
          linkText = "Terms of Service",
        ),
        ExplainerBlock.Prose(
          "That we take no money from colleges is our word. There is no document on this site " +
            "you can read to check it, and we are not going to imply otherwise. What you can " +
            "check is the rest: the two pages above, and the fact that this page asks you for " +
            "nothing.",
        ),
        ExplainerBlock.Prose(
          "Our incentive is that a family keeps paying month after month. That is a pressure of " +
            "its own, and we would rather you knew what it is. It rewards us for being useful, " +
            "and gives us no reason to tell a student to apply to three more schools.",
        ),
        ExplainerBlock.Claim(
          "And the uncomfortable half: unicoach is an AI product, and AI products flatter. A " +
            "study of eleven current models reports that they “affirm users' actions 50% more " +
            "than humans do, and they do so even in cases where user queries mention " +
            "manipulation, deception, or other relational harms.” The same study found that " +
            "“participants rated sycophantic responses as higher quality, trusted the sycophantic " +
            "AI model more, and were more willing to use it again.” A model that tells a student " +
            "their list looks fine is liked more than one that says it does not. We have to " +
            "engineer against that, not claim to be exempt from it.",
          sycophancyStudy,
        ),
      ),
  )

/** The page itself: five authored sections, plus a sixth the renderer derives from the claims. */
val whoPaysPage: ExplainerPage =
  ExplainerPage(
    title = "Who pays for college advice",
    standfirst =
      (
        "Most college guidance is paid for by somebody other than the family reading it. This " +
          "page shows who pays, in each company's own published words, with a link to every " +
          "page we read and the date we read it."
      ),
    asOf = AS_OF,
    sections =
      listOf(
        whyWeWroteThis,
        whereTheMoneyComesFrom,
        whatThatBuysYou,
        theHonestMoves,
        whoPaysUnicoach,
      ),
    namedThirdParties = NAMED_THIRD_PARTIES,
  )

/** `GET /guides/who-pays-for-college-advice`. Static, no account, no cookie, nothing collected. */
suspend fun ApplicationCall.respondWhoPaysPage() {
  respondExplainerPage(whoPaysPage)
}
