# RFC 204 — Who pays for college advice: the first public explainer page

**Slice:** `beyond/09/who-pays-for-college-advice` (brief 0011, "Beyond money").
**Lane:** A (rfc) — a new public surface with copy about named third parties.
**Status:** proposed.

## Summary

`public-web` gains its first family-facing explainer page: **how the
college-guidance market is paid for, and what that means for the advice a family
gets**. It is static, needs no account, sets no cookie, and asks for nothing. It
names real companies, and every load-bearing claim about one of them is quoted
from that company's own published pricing, methodology or product page, with the
URL and the date we fetched it printed on the page.

It also names unicoach's own incentive, because a page about who pays that omits
its author is the failure it is describing.

The material is `product/0011-beyond-money/research/landscape.md`, which already
carries an inline URL per claim.

## Motivation

Brief 0011 decision D13 (Ian, 2026-09-10): `public-web` gains a family-facing
explainer section — static, no account, no paywall, one page per topic — and the
first page is this brief's "who pays for college advice" material. D14 sets the
content rules, and this slice's acceptance criteria ARE those rules:

- (a) every load-bearing claim carries a public URL and the date we fetched it;
- (b) a claim about another company quotes its OWN published methodology,
  pricing or product page — never a rumour, never an inference about motive
  stated as fact;
- (c) the page carries a visible "as of" date, because a competitor's pricing
  page will drift;
- (d) brief 0011 D3 applies unchanged: no scores, grades, ranks or chance
  figures anywhere on it;
- (e) no lead capture, no email wall, no ad.

The slice adds one more: **a claim the research marked unverified may not
appear.**

## Detailed Design

### D-A. The prose is a typed content model, not Markdown and not free-form HTML

The slice left the authoring format to /ship. Three candidates were weighed.

1. **Markdown at runtime.** Costs a new `public-web` dependency (a Markdown
   renderer), forces `kotlinx.html`'s `unsafe { }` raw-HTML escape hatch, and —
   the deciding reason — makes a claim and its source **untestable**: once the
   page is a string, no test can assert that every claim carries a URL and a
   date, and D14(a) becomes a promise rather than a check.
2. **Hand-written `kotlinx.html` calls**, as `PrivacyPage.kt` and `TermsPage.kt`
   do today. Honest and dependency-free, but the citation discipline is again a
   convention: a paragraph can be added with no source and nothing notices.
3. **A typed content model rendered by `kotlinx.html`** — chosen.

The model makes the invariant structural:

```kotlin
/** A published page we read, and the date we read it. */
data class ExplainerSource(
  val publisher: String,
  val title: String,
  val url: String,
  val fetchedOn: LocalDate,
)

sealed interface ExplainerBlock {
  val text: String

  /** Connective prose in unicoach's own voice. Names no third party. */
  data class Prose(override val text: String) : ExplainerBlock

  /** A load-bearing claim. Cannot exist without a source: D14(a) is the type. */
  data class Claim(override val text: String, val source: ExplainerSource) : ExplainerBlock

  /** Prose about unicoach ending in a link to one of OUR OWN pages. */
  data class SelfCited(
    override val text: String,
    val target: SitePath,
    val linkText: String,
  ) : ExplainerBlock
}

/** The closed set of our own pages an explainer may cite. */
enum class SitePath(val value: String) { PRIVACY("/privacy"), TERMS("/terms") }

data class ExplainerSection(val heading: String, val blocks: List<ExplainerBlock>)

data class ExplainerPage(
  val title: String,
  val standfirst: String,
  val asOf: LocalDate,
  val sections: List<ExplainerSection>,
)
```

`Claim` has a non-null `ExplainerSource`, so **a claim with no URL and no fetch
date does not compile**. That is the strongest form D14(a) can take.

The **third case exists for D-E** and for nothing else: the claims about our own
business must be checkable by following a link to `/privacy` and `/terms`, and
neither of the other two cases can carry one honestly — `Prose` has no link at
all, and `Claim` demands a fetched third-party URL with a fetch date, which an
internal page has neither of. `target` is a CLOSED enum of our own pages, so the
case cannot smuggle an outbound link into the body prose — that is a compile
error, not a test failure — and it is not a `Claim`, so naming a company inside
one still fails the D-B sweep. `text` is hoisted onto the interface because that
sweep walks every block's text whatever its case.

A new file `WhoPaysPage.kt` holds the content value; `ExplainerPage.kt` holds
the model and the renderer. Content and rendering are separate files so a copy
edit never touches the renderer, and the renderer is the only place that knows
HTML.

### D-B. The third-party guard is a sweep over the CONTENT VALUE, not the source text

D14(b) says a claim about another company quotes that company's own page. The
model alone does not enforce it: someone could name a company inside a `Prose`
block, where there is no source.

So the test walks the **content value** and asserts:

> for every company in `NAMED_THIRD_PARTIES`, every UNSOURCED string that
> contains that company's name is a test failure.

The unsourced strings are the page title, the standfirst, every section heading
and every non-`Claim` block — everything unicoach wrote itself. Sweeping the
blocks alone would leave a heading free to name a company with no source behind
it, which is the same defect one level up.

The set is a FIELD of `ExplainerPage` (`namedThirdParties`) and the walk is
`ExplainerPage.thirdPartyLeaks()`, beside `blocks()` and
`sourcesInReferenceOrder()`. Both sit on the type rather than in one page's
content file or in its test, so the second explainer page inherits the
discipline along with the renderer.

`NAMED_THIRD_PARTIES` is the value this page passes in (`Scoir`,
`College Board`, `BigFuture`, `EAB`, `Appily`, `Unigo`, `EducationDynamics`,
`CollegeVine`, `U.S. News`, `Princeton Review`) — every one of them spoken on
the page, and no name in the set that the page does not speak. The sweep counts
the **attribute** — is this block a `Claim`? — not a call spelling, so it cannot
be bypassed by writing the same sentence a different way. A second test asserts
the set is non-empty and that every name in it is spoken inside a CLAIM, so a
name cannot be silently dropped from the guard while its sentence stays. That
second test reads the claim text and deliberately NOT the rendered page: the
sources list prints every publisher and page title, so a company whose sentence
was deleted would still "appear" on the page through its own citation line, and
the guard could never fire.

A third test closes the other direction, which is the one that rots silently:
every source's **publisher** must contain a guarded name, so a claim about a NEW
company cannot be written without adding that company to the set. `arXiv` is the
one declared exemption — it publishes the sycophancy paper and is not a company
in this market.

One deliberate exemption, stated in the code: **unicoach itself** is not in the
set. Claims about our own business are sourced to our own pages (`/privacy`),
and those are internal links, not fetched third-party URLs. See D-E.

### D-C. Every claim is rendered with its source; one "as of" date sits at the top

Rendering:

- The **body** carries the claim text followed by a small superscript reference
  marker (`[1]`, `[2]`, …) — deduplicated per source, so two claims from one
  Scoir pricing page share one number.
- A **"Sources" section** at the foot lists each source once, in reference
  order: publisher, page title, the URL as a live `<a href>`, and **"Fetched
  2026-09-14."**
- A visible **"As of <date>"** line sits under the page title (D14(c)).

Reference markers rather than inline links keep the prose readable — a parent
reads the argument, not a field of blue. Every URL is still ON the page, which
is what D14(a) requires. This is the same instinct as brief 0011 D34 for the
Family Cost Report ("one link per publisher, in the sources section; the body
prose stays link-free"), applied to a page whose whole subject is its sources.

Outbound links carry `rel="nofollow noopener"` and no `target`.

### D-D. What the page says

Five authored sections, plus a sixth the renderer generates from the claims
themselves. Every figure below is already sourced in `landscape.md` and was
re-fetched during this run (see D-F).

1. **Why we wrote this.** We did this research to decide what unicoach should
   build. It is more useful to a family than it is to us. One rule runs through
   it: **who pays for a product decides what it is able to tell you.**

2. **Where the money comes from.** Colleges pay for placement and for names.
   Scoir publishes both price lists — high schools pay $4.80 per student per
   year (grades 9-12, plus a one-time $250 setup fee), and colleges pay $26,040,
   $39,960 or $56,040 a year. What the tiers differ in is **where a college's
   message lands**: the middle tier delivers to a "Promotion" folder at a 6%
   read rate, the top tier to the "Primary Inbox" at 40%, and the cheapest tier
   carries no match messaging at all. College Board's Student Search shares a
   student's exam **score ranges**, BigFuture college list and college
   preferences with more than 1,200 colleges on opt-in. EAB sells colleges
   "student leads that are backed by machine learning" from Appily, which it
   says 65% of college-bound seniors use. Unigo collects its reviews with a
   $1,000 scholarship draw, whose published rules judge on writing ability,
   creativity, originality and overall excellence, 25% each. EducationDynamics
   states its enrollment-marketing result in the buyer's terms — clients
   "reduced their cost-per-start by 30%". CollegeVine runs a free admissions
   calculator for students and sells universities "The AI operating system for
   universities", whose enrollment module covers recruiting, application review,
   yield modelling and forecasting.

3. **What that buys you, and what it costs you.** A rank built mostly from
   things a family cannot see: U.S. News' formula is 52% "Student Outcomes" and
   20% "Peer Assessment", and the same page notes that acceptance rates, alumni
   giving, class size and high-school class rank "are no longer used". A chance
   percentage that, in its own blog post, does not "yet consider, such as
   essays, rec letters, high school context, early decision, and legacy". A
   happiness rank built from one agree/disagree statement, "I am happy at my
   school".

4. **The honest moves, credited by name.** Princeton Review refuses an overall
   ranking on principle — "We don't believe that any one school is the best
   overall." — and prints `60*` when a school did not report enough data, a
   visible missing-data marker almost nobody else publishes. CollegeVine's own
   copy says the acceptance rate on a school's website is not your chance of
   getting in. College Board states a limit on what it passes on: "We never
   share your actual test scores, grades, disability status, or phone number."
   Naming these is the point: the page is about incentives, not villains.

5. **Who pays unicoach.** Families do, by subscription. No college pays us for
   placement, for a badge, or for a name. We do not sell personal information
   (our Privacy Policy says so, and it is one click away). Our incentive is that
   a family keeps paying, which is its own pressure — it rewards us for being
   useful and gives us no reason at all to say "apply to three more schools".
   And the honest half: unicoach is an AI product, and a study of eleven current
   models found that they "affirm users' actions 50% more than humans do", and
   that people "rated sycophantic responses as higher quality, trusted the
   sycophantic AI model more, and were more willing to use it again". That is a
   failure mode we have to engineer against, not one we are exempt from.

6. **Sources.** Every URL, with its fetch date.

No score, rank, grade or chance figure is computed or published anywhere on the
page (D3/D14(d)). Nothing is collected: no form, no email field, no analytics
tag, no advertisement (D14(e)).

### D-E. Claims about unicoach are held to the same bar

Three claims are made about us, and each is checkable without trusting us:

| Claim                                                               | How a reader checks it                       |
| ------------------------------------------------------------------- | -------------------------------------------- |
| We do not sell personal information                                 | `/privacy`, linked                           |
| The service is for personal, non-commercial use, paid by the family | `/terms`, linked                             |
| We take no money from colleges                                      | Stated plainly; the page says it is our word |

The third is the one a reader cannot verify from a document, and the page says
so in those words rather than implying a citation it does not have. That is the
honest version of the claim, and it is better than omitting it.

### D-F. Live-URL verification is a run-time step, recorded — not a test

The acceptance criterion is "every external claim on the page resolves to a live
URL recorded in the page's own source". A unit test MUST NOT make a network
call: it would be flaky, slow, and would fail the build when somebody else's
marketing site has an outage.

So the split is:

- **In the test suite (hermetic):** every `Claim` carries a source; every source
  URL is absolute and `https://`; every `fetchedOn` is a real date, not in the
  future; every named third party appears only inside a `Claim`; every source
  listed is referenced by at least one claim and every referenced source is
  listed.
- **In this run, once, recorded:** every URL was fetched and its quote confirmed
  verbatim against the page text. The results are in the run archive as
  `source-check.md`, and this RFC's "Sources verified" section below summarises
  them.

Three sources are cited through `web.archive.org`: `eab.com`,
`educationdynamics.com` and the U.S. News methodology article. That is what
`landscape.md` itself cites, and the snapshot is a public URL that resolves for
any reader and is honest about being an archived copy — the publisher field on
the page says so in words ("Internet Archive snapshot"). Every fetch in this run
returned HTTP 200 and no live site refused the scripted client, so the archive
is a citation choice inherited from the research, not a workaround.

### Sources verified — and the claims this run CUT

24 sub-claims were re-fetched on 2026-09-14 and matched word-for-word against
the live page text (`source-check.md` in the run archive). **These do not appear
on the page cited, so they do not appear on ours:**

| Cut                                                            | Why                                                                                                                                                                                                   |
| -------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| U.S. News' debt and earnings are Scorecard FY2019-20 / 2020-21 | The methodology page names College Scorecard as a source but attributes **no fiscal year** to those two metrics. The page instead states the weights, which it does say.                              |
| Unigo "is owned by" EducationDynamics                          | Neither company's page states ownership. The page makes no ownership claim; it quotes EducationDynamics' own published result instead ("reduced their cost-per-start by 30%").                        |
| "Niche has 15,000 paying school clients"                       | `landscape.md` carries no URL for it. The slice forbids a claim the research marked unverified.                                                                                                       |
| Every claim about **Niche**                                    | No Niche page was fetched in this run, so `source-check.md` has no Niche row and nothing about Niche is verified. The page names Niche nowhere, and `NAMED_THIRD_PARTIES` therefore does not list it. |
| "BigFuture publishes raw figures and declines to grade"        | No BigFuture page was fetched. BigFuture is named on the page only inside College Board's own quoted sentence, which is verified.                                                                     |

Five verified claims were **corrected** before being written into copy, and the
corrected form is what D-D above says: the "Promotion"/6% tier is Scoir's MIDDLE
tier, not its cheapest (the cheapest buys no match messaging at all); Scoir's
$4.80 is the grades 9-12 plan and carries a one-time $250 fee; Unigo's judging
has a FOURTH 25% criterion (overall excellence); CollegeVine's blog says "early
decision" and never "early action"; and the sycophancy paper says "50% more",
not "about 50% more". Common App's live count is 1,167, not 1,164, so the page
states no count at all.

This is the reason the verification is a separate, recorded pass and not a test:
a test can prove a claim HAS a source, and only a human-readable check can prove
the source SAYS it.

### D-G. The route, and the door

The page is reachable at **`/guides/who-pays-for-college-advice`**.

The `/guides/` prefix costs nothing now and earns the index later: D13
guarantees a second explainer (brief 0010's testing page), and a flat URL would
have to move — or not move, and be wrong — on the day it arrives. No index page
is built in this slice; `/guides` itself is not routed yet, and the slice that
adds the second page adds it.

**The door** — how a real user reaches it — is a link labelled **"Who pays for
college advice"** in the shared site header nav (`Layout.kt`), beside Home,
Terms and Privacy. It therefore appears on every public page, including the
Family Cost Report a parent already opens from a shared link, which is exactly
the reader this page is for. **Why this does not violate RFC 155 D-G**, stated
here so a reviewer need not re-derive it. D-G forbids the in-chat precision
OFFER — "tell me your state and I can price tuition exactly" — from crossing
onto the report, because a logged-out parent cannot answer it and a page that
asked them to "would be selling, not informing". A nav link to an explainer asks
the parent for nothing and sells nothing: no account, no form, no email field,
no product pitch, and the destination page itself is under the same prohibition
(D14(e)). It is site navigation, which that page already carries three of. D-G
is about an ASK crossing to the page; this is a link to a page that makes none.

The footer is left alone: it is the legal footer, and this is not a legal page.

### D-H. No database, no migration, no configuration

One render file pair, one route, one nav link, one test file. `site.css` gains a
small `.guide` block reusing the existing type scale. Nothing else in the repo
changes.

## Files Modified

| File                                                                 | Change                                                                                                                                               |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `public-web/src/main/kotlin/ed/unicoach/web/render/ExplainerPage.kt` | NEW — the content model (`ExplainerSource`/`Claim`/`Prose`/`ExplainerSection`/`ExplainerPage`) and the `kotlinx.html` renderer, through `siteLayout` |
| `public-web/src/main/kotlin/ed/unicoach/web/render/WhoPaysPage.kt`   | NEW — the page content value, `NAMED_THIRD_PARTIES`, and `respondWhoPaysPage()`                                                                      |
| `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`        | one nav link in `siteHeader()`                                                                                                                       |
| `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`              | `get("/guides/who-pays-for-college-advice")`                                                                                                         |
| `public-web/src/main/resources/static/site.css`                      | a `.guide` block: measure, heading rhythm, reference-marker and source-list styling                                                                  |
| `public-web/src/test/kotlin/ed/unicoach/web/WhoPaysPageTest.kt`      | NEW — the structural citation guards and the route test                                                                                              |
| `rfc/204-who-pays-for-college-advice.md`                             | NEW — this document                                                                                                                                  |

No migration, no `db/schema/ORDER` line, no coach-prompt edit, no `service.conf`
change.

## Implementation Plan

1. `ExplainerPage.kt`: the model and the renderer. The renderer numbers sources
   in first-reference order, renders the body with markers, then the sources
   list, then the "As of" line under the title.
2. `WhoPaysPage.kt`: the five authored sections as a value — the sixth, Sources,
   is derived by the renderer — with every third-party sentence a `Claim`
   carrying its source and fetch date. Copy is written against
   `source-check.md`, quoting only what a page actually says.
3. `Layout.kt` nav link; `Routing.kt` route.
4. `site.css`: `.guide` styles.
5. `WhoPaysPageTest.kt`: the guards in D-F, plus a `testApplication` route test
   asserting 200, `text/html`, the shared header and footer chrome, the page
   marker, the "As of" line, and that the body contains no `<form>`, no
   `<input>` and no `<script>` (D14(e), asserted rather than assumed).
6. `nix develop -c bin/format` then `nix develop -c bin/test`.

## Tests

`public-web/src/test/kotlin/ed/unicoach/web/WhoPaysPageTest.kt`:

| Test                                                      | Asserts                                                                                          |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `the page renders through the shared layout`              | 200, `text/html`, `site-header` and `site-footer` chrome, the page marker                        |
| `the nav link on every page points at the guide`          | the shared header carries the link on the HOME page too, so the door is on every page (D-G)      |
| `every claim carries a source`                            | every `Claim.source.url` starts `https://`, with a publisher and a title                         |
| `every source has a real fetch date`                      | no source is fetched after the page's own as-of date, none predates the research, none is future |
| `every named third party is spoken only inside a claim`   | `thirdPartyLeaks()` is empty: title, standfirst, headings and non-`Claim` blocks name nobody     |
| `the guard set is live`                                   | every guarded name is spoken inside a `Claim`'s TEXT, not merely somewhere on the page           |
| `the guard set covers every company on the page`          | every source publisher contains a guarded name, `arXiv` excepted — the other direction           |
| `every source is referenced and every reference listed`   | the sources list and the claim set are the same set, no orphan either way, no repeated URL       |
| `every source is printed on the page with its fetch date` | the rendered body carries each URL and its `Fetched <date>.` line (D-C)                          |
| `outbound links are nofollow and open in place`           | every source link is `rel="nofollow noopener"` and the body has no `target=` (D-C)               |
| `the body prose links nowhere but our own pages`          | each third-party URL is linked exactly once, and each self-citation renders its own link (D-E)   |
| `every page we cite about ourselves actually resolves`    | `GET` on each `SitePath` returns 200, so a renamed route cannot leave a link to a 404            |
| `the as-of date is visible`                               | the rendered body contains the `asOf` date                                                       |
| `the page asks for nothing`                               | the rendered body contains no `<form`, no `<input`, no `<script`                                 |
| `the page publishes no grade and quotes nobody unsourced` | no digit, no `%`, no letter grade and no quotation mark in any unicoach-authored string          |

Fifteen tests, and the table is exhaustive: a test added to this file is added
here.

Existing suites that must stay green: `LegalPagesTest`, `HomePageTest`,
`CostReportRoutingTest`, `ErrorPagesTest`, `VerifyEmailRoutingTest` — all assert
the shared chrome, which this run edits.

## Rollback

Delete the route line and the nav link; the two render files and the test become
dead code and are deleted with them. No data is written, so there is nothing to
undo. A full revert of the code commit is safe at any time.
