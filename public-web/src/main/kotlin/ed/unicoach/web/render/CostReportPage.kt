package ed.unicoach.web.render

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.costs.ArrangementCost
import ed.unicoach.coaching.costs.BorrowingCoverage
import ed.unicoach.coaching.costs.BorrowingWire
import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CollegeResidencyBasis
import ed.unicoach.coaching.costs.ComparisonBasis
import ed.unicoach.coaching.costs.ComponentRole
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.CostLine
import ed.unicoach.coaching.costs.LineOrigin
import ed.unicoach.coaching.costs.MoneyBasis
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.ResidencyBasis
import ed.unicoach.coaching.costs.SingleSchoolBasis
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.applicableTuitionFor
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.AssuranceTierGrouping
import ed.unicoach.coaching.costs.canonical.CoveredFigure
import ed.unicoach.coaching.costs.canonical.FilingLink
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.testing.AutomaticAwardAnswer
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.PublishedGpa
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ResidencyTierBasis
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import ed.unicoach.db.models.ResidencyBasis as PublishedResidency

// The Family Cost Report (RFC 155): the parent-facing page behind a student's
// share link. It renders the SAME computation the coach reads in chat
// (CollegeCostProfile) as HTML, through the shared siteLayout, and it renders
// nothing the student did not already have.
//
// Three rules govern every line below, and they are the reason this file is
// long where a table would be short:
//
//  - NO STUDENT IDENTITY (D-F). No name, no email, no id. The link is not proof
//    of who sent it, so the page never asserts who did. It does state the
//    family's income band in dollars, because a net price whose band is unstated
//    is the dishonesty the whole cost slice exists against.
//  - NO UPGRADE CUE (D-G). CollegeCostProfile.precisionOffersFor() is an
//    in-chat prompt for the STUDENT to answer a question. A logged-out parent
//    cannot answer it, so this file never calls it -- only the honest half, the
//    labelled statement of what is missing, survives here.
//  - A MISSING PART IS A LABELLED BLANK. Never a zero, never a neighbour's
//    number, and never a total summed from a partial set (that rule is
//    ArrangementCost's own -- this file only prints what it computes).
//
// The money vocabulary is RFC 141's throughout: tuition and fees, housing and
// food, the published price, a financial aid offer. Never "room and board",
// "sticker price", "award", or "without need". The SENTENCES are the cost
// domain's own (ComparisonBasis, SingleSchoolBasis, MoneyBasis) -- this file
// authors no money copy, so a parent can never be shown a caveat the coach
// would have worded differently.

/** The `<title>` suffix and the page's own heading; no student is named in either (D-F). */
private const val PAGE_TITLE = "Cost report"

/** The heading marker a test asserts on, and the page's honest claim of whose list this is. */
private const val REPORT_HEADING = "Your student's college list"

/** The tier block's own heading -- page chrome, not money copy: the sentences under it are the domain's (RFC 191). */
private const val KINDS_HEADING = "What kind of numbers these are"

/**
 * The heading over one school's published automatic-scholarship grid (RFC 239),
 * scoped by [gridHeadingOf] to the students the school published it for.
 *
 * "Scholarships" and not "awards": RFC 141's vocabulary is this page's
 * throughout, and the one sentence on the page that says "automatic award" is
 * the standing one below, which is the corpus's own name for the thing.
 */
private const val AWARD_GRID_HEADING = "Automatic scholarships this school publishes"

/**
 * WHAT KIND OF NUMBER a published grid is, said ONCE for the page (RFC 191) and
 * never per school -- the same rule the assurance-tier sentences beside it obey.
 *
 * It is the one thing a family most needs said about this block: the school
 * published a table, and a table is not an offer anybody has made them.
 *
 * "SCHOLARSHIP" AND NOT "AWARD", although RFC 239's prose wrote the sentence with
 * the second word. This page may not say "award" at all (RFC 141's vocabulary,
 * and the sweep in `CostReportPageTest` that executes it), because that word
 * makes published money sound granted -- which is the exact confusion THIS
 * sentence exists to prevent. A school's own band names and its own published
 * words are quoted verbatim and are a different thing: RFC 141 governs our
 * vocabulary, not a quotation of theirs.
 */
private const val AWARD_KIND_SENTENCE = "An automatic scholarship is published by the school and is not a financial aid offer."

/** A `gpa_only_grid` school in one sentence: it pays automatically, and a test score does not move what it pays. */
private const val NO_SCORE_EFFECT_LINE = "A test score does not change what this school's automatic scholarships pay."

/** The published cell that asks for no test score -- the tier a student who submits none lands in, at a school where a score IS money. */
private const val NO_SCORE_ROW_LABEL = "no test score"

/** D-A said aloud: the parent must know the page moves under them, because it does. */
private const val LIVE_LINE = "This report is live \u2014 it updates as your student updates their list."

/**
 * The one blank label for a figure this school does not publish. Never a zero.
 *
 * `internal` for one reason, stated because visibility is otherwise noise here:
 * it is a CLAIM ABOUT A NAMED SCHOOL, and RFC 166 gave three blanks it is false
 * of (the publisher's suppression, a gap of ours, a figure held only in another
 * year). The tests that pin where it may no longer appear assert on THIS string
 * rather than on a copy of it, so a reworded label cannot leave them passing
 * against words the page no longer prints.
 */
internal const val NOT_REPORTED = "Not reported by this school"

/** The blank for a public school's tuition when the family's state is not on file. */
private const val TUITION_WITHHELD = "Not shown \u2014 the state the family lives in is not on file"

/**
 * The blank for a blended figure this school publishes for in-state students
 * while this family would pay the out-of-state price (RFC 157 D-A).
 *
 * NOT a caveat under the number, and not a footnote: RFC 142 landed because a
 * labelled `net_price_q1..q5` was still read as "the Q5 net price", and a number
 * printed beside a family's own name is read as theirs whatever the note says.
 * This page has no coach in the loop -- a parent reads it alone -- so the figure
 * goes and the reason stays.
 *
 * The REASON is the domain's own, in [WithheldReason.cellPhrase] -- the short
 * form beside the sentence the coach ships -- so a page and a coach explain one
 * blank with one vocabulary. Only [totals] is this page's, and it is a parameter
 * because the pointer has to be true where it is printed: the per-school table
 * has the family's own totals directly above this row, and the summary table has
 * none at all. A blank with no destination is a number taken away; the totals
 * built from out-of-state tuition and fees are in the same report and the parent
 * is sent to them.
 */
private fun withheldBlankFor(
  reason: WithheldReason,
  totals: String,
): String = "Not shown \u2014 ${reason.cellPhrase} \u2014 $totals."

/** The blank in the per-school table, where the family's own totals are directly above this row. */
private const val TOTALS_ABOVE = "the totals above"

/** The blank in the cross-school table, where this school's own totals are below rather than above. */
private const val TOTALS_IN_SCHOOL_TABLE_BELOW = "the totals in this school's own table below"

/**
 * The blank for a component that is not part of a way of living at all.
 *
 * No arrangement is short a role today: gate-2 D17 gave living at home the
 * food-and-housing line it used to lack (RFC 166 §7), so all three ways of
 * living carry all three roles. It stays because the mapping is data -- a fourth
 * arrangement, or a role one of them does not price, must render as itself
 * rather than as a school that failed to report something.
 */
private const val NOT_PART_OF_ARRANGEMENT = "Not a part of this way of living"

/**
 * The note beside the ONE amount on this page that is not a school's figure (RFC
 * 166 §7, gate-2 D17): the `$0` food-and-housing line of living at home.
 *
 * Short, because the cell is a cell -- the whole sentence, and the reason the
 * zero is honest, arrives above the table through the domain's own
 * `AtHomeAssumptionBasis` statement, which this page renders verbatim with every
 * other basis fact. This is the pointer that stops a parent reading the zero off
 * the row as something the school published, and it says whose number it is in
 * its own words: OURS, never theirs.
 */
private const val ASSUMED_BY_US = "our assumption, not a figure this school published"

/** No partial sum is ever printed as a total; this says so where the total would have been. */
private const val NO_TOTAL = "No total \u2014 a part of this price is missing"

/** The heading both assumption blocks carry: one question, whichever shape answers it. */
private const val ASSUMPTIONS_HEADING = "What these figures assume"

/**
 * "Per year" in TWO roles, deliberately one string: the CORNER header over the
 * row labels — every figure in this table is a yearly one — and the fallback
 * column header at a school that publishes no component, where there is no way
 * of living to head a column. Both say the same thing about the same figures,
 * so a school with no component prints "Per year | Per year" by design, not by
 * copy-paste.
 */
private const val SINGLE_COLUMN_HEADER = "Per year"

/** That fallback is exactly ONE column wide, and the whole-school rows span it. Named beside the header it matches. */
private const val SINGLE_COLUMN_COUNT = 1

/**
 * The column labels, written once each.
 *
 * They are not decoration: every one of them is printed TWICE -- as a `<th>` in
 * the desktop grid and again in the cell's `data-label`, which is what the phone
 * layout shows once the table stops being a grid. Two hand-typed copies of one
 * label is a header and a stacked card that can disagree about what a column is.
 */
private const val SCHOOL_COLUMN = "School"
private const val WAY_OF_LIVING_COLUMN = "Way of living"
private const val TUITION_COLUMN = "Tuition and fees"
private const val PUBLISHED_PRICE_COLUMN = "The published price"
private const val NET_PRICE_COLUMN = "The likely price after a financial aid offer"
private const val TOTAL_ROW_LABEL = "Total per year"

/** The cross-school table's own heading: the comparison the assumption lines above it promise. */
private const val SUMMARY_HEADING = "The schools side by side"

/**
 * Said when no one way of living is priced at every school, which is
 * [ed.unicoach.coaching.costs.ArrangementBasis]'s own finding: without a way of
 * living held constant the summary compares only the two school-wide figures,
 * and the parts are quoted school by school below.
 */
private const val SUMMARY_NO_HELD_ARRANGEMENT =
  "No one way of living is priced at every school here, so this table compares only each school's own " +
    "school-wide figures; the parts of the price are quoted school by school below."

/** The blank for a summary tuition cell whose school publishes a different figure per way of living. */
private const val SUMMARY_TUITION_VARIES = "Quoted per way of living below"

/**
 * The in-state basis of BOTH blended figures, written once and joined onto each
 * table's hint (RFC 157): the two hints differ in whose figure it is and what
 * the parts are, never in what the figures are on.
 *
 * One SENTENCE, with no glue on either end -- the join is written at the two
 * call sites below, where it can be seen, rather than hidden in a leading space
 * every caller has to remember.
 */
internal const val BLENDED_IN_STATE_CLAUSE =
  "At a public school it, and the likely price after a financial aid offer, are figures for students paying " +
    "in-state tuition."

/** Said under the summary table, because two published prices side by side invite exactly this arithmetic. */
internal const val SUMMARY_HINT =
  "The published price is each school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts quoted below."

/**
 * The same warning under ONE school's table, where "each school's" would be
 * false. Written once, like its twin.
 *
 * `internal`, with [BLENDED_IN_STATE_CLAUSE] and [SUMMARY_HINT], for the reason
 * [NOT_REPORTED] is: the tests that pin WHERE these sentences may no longer
 * appear assert on the constants themselves, so a reworded hint cannot leave an
 * absence assertion passing vacuously against words the page no longer prints.
 */
internal const val SCHOOL_BLENDED_HINT =
  "The published price is this school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts above it."

/**
 * The blended hint, with the in-state clause appended only where a PUBLIC school
 * is on this page.
 *
 * [BLENDED_IN_STATE_CLAUSE] is a sentence about public schools, and it used to
 * be a constant tail on both hints -- so a parent comparing two private colleges
 * read a sentence about in-state tuition under a table where no school prices by
 * residency at all. The control is the cost DOMAIN's own
 * ([CollegeControl.Public]), read off the colleges this hint is printed over:
 * the per-school hint asks about its one school, the page-level hint about every
 * school it stands above, because the clause is true of the page exactly when
 * one of them is public.
 */
private fun blendedHintFor(
  hint: String,
  hintStandsOver: List<CollegeCost>,
): String = if (hintStandsOver.any { it.control is CollegeControl.Public }) "$hint $BLENDED_IN_STATE_CLAUSE" else hint

/**
 * The hint said ABOVE the school sections, over every school it stands over --
 * under the summary table where there is one ([ReportShape.Compared]), and on
 * its own where there is not ([ReportShape.ManyUncompared]).
 */
private fun pageBlendedHint(colleges: List<CollegeCost>): String = blendedHintFor(SUMMARY_HINT, colleges)

/** The hint said under the ONE table on a one-school page, over that school alone. */
private fun schoolBlendedHint(cost: CollegeCost): String = blendedHintFor(SCHOOL_BLENDED_HINT, listOf(cost))

/**
 * WHICH shape of report this profile is, decided ONCE.
 *
 * Three facts hang off this question -- which assumption block is rendered,
 * whether a school section states its own residency basis, and where the blended
 * hint is said -- and each used to re-derive it from `comparisonBasis` and the
 * college count in its own words. Two hand-written booleans that must agree are
 * how a one-school page ended up stating its residency sentence twice; a THIRD
 * would be free to disagree with both.
 *
 * The shapes are the cost domain's, not this page's invention:
 * [ComparisonBasis.of] deliberately builds nothing below two colleges, and this
 * page has always kept a named arm for two or more colleges it was handed no
 * comparison for. A fourth shape must fail to compile in the `when`s below
 * rather than inherit a silence.
 */
private sealed interface ReportShape {
  /** Nothing on the list: nothing is priced, so there is no assumption to state. */
  data object NoColleges : ReportShape

  /** The one school the assumption block above IS the basis of ([SingleSchoolBasis]). */
  data class OneSchool(
    val cost: CollegeCost,
  ) : ReportShape

  /** Two or more schools the domain built a comparison for. */
  data class Compared(
    val basis: ComparisonBasis,
  ) : ReportShape

  /** Two or more schools with no comparison basis -- priced, and compared by nobody. */
  data object ManyUncompared : ReportShape

  companion object {
    fun of(profile: CollegeCostProfile): ReportShape {
      val basis = profile.comparisonBasis
      val single = profile.colleges.singleOrNull()
      // Compared FIRST, and the order is load-bearing: the domain builds no
      // basis below two colleges, so a basis implies two or more and can never
      // steal the one-school shape out from under the residency line this page
      // stops printing on it.
      return when {
        basis != null -> Compared(basis)
        single != null -> OneSchool(single)
        profile.colleges.isEmpty() -> NoColleges
        else -> ManyUncompared
      }
    }
  }
}

/**
 * The band the page has ALREADY stated, or null where it states none.
 *
 * EXHAUSTIVE over the closed vocabulary, never `as?`: a variant added to
 * [MoneyBasis] that also names a band must fail to compile here and decide
 * whether the page states it, rather than fall silently to null and put the note
 * back beside every net-price figure on the page.
 */
private fun bandSaidAboveOf(basis: MoneyBasis): IncomeBand? =
  when (basis) {
    is MoneyBasis.AnsweredBand -> basis.band
    MoneyBasis.Declined, MoneyBasis.Unanswered -> null
  }

/**
 * The Family Cost Report for one student's [profile].
 *
 * Rendered through [siteLayout] with `noindex` on (D-H) so the shared chrome is
 * unchanged and the page still cannot be indexed. The response headers that go
 * with it (`Cache-Control: no-store`, `X-Robots-Tag`, `Referrer-Policy`) are the
 * route's, in `Routing.kt`, because they must also ride the 404.
 *
 * The residency basis is derived ONCE here and the per-school sentences are
 * passed down. It used to be re-derived inside two render functions, which made
 * one fact about the family into two computations that only convention kept
 * equal.
 */
suspend fun ApplicationCall.respondCostReportPage(
  profile: CollegeCostProfile,
  nav: ShellNav = ShellNav.SignedOut,
) {
  val residencyBases = residencyBasesOf(profile)
  val shape = ReportShape.of(profile)
  // ONE derivation of the money basis for the whole page, like the residency
  // bases above it: the assumption block states it and the net-price cells ask
  // it which band was stated, and two calls could not disagree but could drift.
  val moneyBasis = MoneyBasis.of(profile.moneyProfile)
  val bandSaidAbove = bandSaidAboveOf(moneyBasis)
  // The assumption block IS this school's own basis on exactly one shape, and
  // that one fact decides both what a school section may stop saying (its
  // residency sentence) and where the blended hint is said (RFC 188).
  val basisIsThisSchool = shape is ReportShape.OneSchool
  respondHtml {
    siteLayout(PAGE_TITLE, noindex = true, nav = nav) {
      section("report") {
        h1 { +REPORT_HEADING }
        p("report-live") { +LIVE_LINE }
        assumptions(shape, residencyBases, moneyBasis)
        if (profile.colleges.isEmpty()) {
          p("report-empty") { +"There is no college on this list yet, so there is nothing to price." }
        } else {
          // The comparison the assumption lines above promise, made real: the
          // shape is [ReportShape.Compared] on exactly the same condition (two
          // or more colleges the domain compared), so the page can never state
          // the comparison assumptions over a page that compares nothing.
          (shape as? ReportShape.Compared)?.let { summaryTable(profile, it.basis, bandSaidAbove) }
          // The blended hint is a fact about the published price, not about one
          // school: it is said ONCE, above the tables it warns about. The
          // summary table carries it on a compared page and the one school
          // carries it on its own page, which leaves the uncompared many -- a
          // shape that is still priced, and still owed the warning.
          if (shape is ReportShape.ManyUncompared) p("report-hint") { +pageBlendedHint(profile.colleges) }
          profile.colleges.forEach {
            schoolSection(
              cost = it,
              residency = residencyBases.getValue(it.collegeId),
              basisIsThisSchool = basisIsThisSchool,
              bandSaidAbove = bandSaidAbove,
            )
          }
        }
        // What KIND of numbers the page's prose figures are, said once for the
        // set each sentence covers (RFC 191) -- above the publishers, because a
        // reader asks what a number is before asking who published it.
        tierKindsSection(profile.colleges)
        // The publishers of the figures THIS page carries, never a constant:
        // a page with no IPEDS figure on it may not claim an IPEDS source, and
        // the constant this replaced claimed the Scorecard for every figure
        // (RFC 177 D5).
        sourcesSection(profile.moneySources)
      }
    }
  }
}

/**
 * Each school's own residency FACT, keyed by college id -- ONE derivation for
 * the whole page.
 *
 * The typed [CollegeResidencyBasis] rather than its sentence: the one-school
 * basis takes the fact, and this page only prints the statement derived from it,
 * so nothing here flattens a domain fact to prose on the way through.
 *
 * [ResidencyBasis] refuses an empty college list by construction (a residency
 * held constant across no table states nothing), so an empty list is an empty
 * map rather than a call that throws.
 */
private fun residencyBasesOf(profile: CollegeCostProfile): Map<CollegeId, CollegeResidencyBasis> {
  if (profile.colleges.isEmpty()) return emptyMap()
  return ResidencyBasis
    .of(profile.colleges, profile.moneyProfile)
    .byCollege
    .associateBy { it.collegeId }
}

/**
 * The assumption lines above the table.
 *
 * Two shapes, and the split is the domain's own (RFC 151 D-B): with two or more
 * colleges the per-call statements are rendered VERBATIM from
 * [ComparisonBasis.statements], because they are the same sentences the coach
 * must say in chat. Below two colleges the domain deliberately builds no
 * comparison, so [SingleSchoolBasis] states the basis for that ONE school
 * instead -- never by constructing a comparison object the domain refused to
 * build.
 *
 * Both shapes render the WHOLE list the domain hands over rather than naming the
 * lines one by one, so a statement added to the domain reaches the parent
 * instead of reaching only the coach.
 *
 * The money line is said last and is present in both shapes (D-F): a price for a
 * family like this one has to say which family that is.
 */
private fun FlowContent.assumptions(
  shape: ReportShape,
  residencyBases: Map<CollegeId, CollegeResidencyBasis>,
  moneyBasis: MoneyBasis,
) {
  when (shape) {
    is ReportShape.Compared -> {
      assumptionList("report-basis", shape.basis.statements, moneyBasis)
    }

    is ReportShape.OneSchool -> {
      assumptionList(
        "report-single-basis",
        SingleSchoolBasis.of(shape.cost, residencyBases.getValue(shape.cost.collegeId)).statements,
        moneyBasis,
      )
    }

    ReportShape.NoColleges -> {
      // Nothing is priced, so there is no assumption to state. NAMED, because
      // the branch it used to share said the same nothing about a page that
      // does print prices.
      Unit
    }

    ReportShape.ManyUncompared -> {
      // Two or more colleges with no comparison basis is not a shape the cost
      // domain builds -- but the price tables below are rendered regardless, and
      // a price with nothing said about whose price it is is the one thing D-F
      // forbids. The family's own money line is the floor, always.
      assumptionList("report-basis", emptyList(), moneyBasis)
    }
  }
}

/** One assumption block: every statement the domain made, then the family's own line. */
private fun FlowContent.assumptionList(
  sectionClass: String,
  statements: List<String>,
  moneyBasis: MoneyBasis,
) {
  section(sectionClass) {
    h2 { +ASSUMPTIONS_HEADING }
    ul {
      statements.forEach { li { +it } }
      li { +moneyBasis.statement }
    }
  }
}

/**
 * The cross-school summary: rows are schools, read across in one place.
 *
 * The per-school tables below are the DETAIL -- columns there are the ways of
 * living, so a total belongs to exactly one column. That shape cannot be read
 * across schools, and a page that states the comparison assumptions while
 * showing four separate tables asserts a comparison it never makes. This table
 * is that comparison, and it carries only the figures that survive being put
 * side by side: the tuition and fees for ONE way of living held constant, and
 * the two school-wide figures.
 *
 * The way of living is [ed.unicoach.coaching.costs.ArrangementBasis]'s own --
 * the first arrangement every school here is priced for -- so the arrangement
 * named in the assumption lines above IS the arrangement this table uses. When
 * the domain finds none comparable, the column is not shown at all and the page
 * says why: an invented "held constant" is the exact dishonesty the basis lines
 * exist against.
 *
 * Every degradation rule of the detail tables holds here: a missing figure is a
 * labelled blank, never a zero and never the neighbouring school's number, and
 * no total is summed from parts. Below the phone breakpoint the shared
 * `report-table` rules stack each row into a card per school, with the column
 * header restored beside each figure.
 */
private fun FlowContent.summaryTable(
  profile: CollegeCostProfile,
  basis: ComparisonBasis,
  bandSaidAbove: IncomeBand?,
) {
  val held = basis.livingArrangement.comparable.firstOrNull()
  section("report-summary") {
    h2 { +SUMMARY_HEADING }
    p("report-hint") { +heldArrangementHint(held) }
    table("$DATA_TABLE_CLASS report-table report-summary-table") {
      thead { summaryHeaderRow(held) }
      tbody { profile.colleges.forEach { summaryRow(it, held, bandSaidAbove) } }
    }
    // The page's ONE blended hint when this table exists: the school sections
    // below it print none.
    p("report-hint") { +pageBlendedHint(profile.colleges) }
  }
}

/** What this table holds constant -- or the domain's own finding that nothing is comparable. */
private fun heldArrangementHint(held: LivingArrangement?): String =
  if (held == null) {
    SUMMARY_NO_HELD_ARRANGEMENT
  } else {
    "Every tuition and fees figure in this table is for ${held.label}, which is the one way of living " +
      "every school here is priced for."
  }

/** The column names; the way-of-living column exists only when one is held constant. */
private fun THEAD.summaryHeaderRow(held: LivingArrangement?) {
  tr {
    th(scope = ThScope.col) { +SCHOOL_COLUMN }
    if (held != null) th(scope = ThScope.col) { +WAY_OF_LIVING_COLUMN }
    th(scope = ThScope.col) { +TUITION_COLUMN }
    th(scope = ThScope.col) { +PUBLISHED_PRICE_COLUMN }
    th(scope = ThScope.col) { +NET_PRICE_COLUMN }
  }
}

/** One school read across: the constant it is quoted under, then its three figures. */
private fun TBODY.summaryRow(
  cost: CollegeCost,
  held: LivingArrangement?,
  bandSaidAbove: IncomeBand?,
) {
  tr("report-row-summary") {
    rowHeader(cost.name)
    held?.let { arrangement ->
      // Words, not money: the constant this table holds, said in every row so
      // the stacked phone card still names it.
      td("report-value report-way-of-living") {
        attributes[DATA_LABEL] = WAY_OF_LIVING_COLUMN
        +arrangement.label
      }
    }
    valueCell(TUITION_COLUMN, summaryTuitionCell(cost, held))
    valueCell(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW))
    valueCell(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW, bandSaidAbove))
  }
}

/**
 * This school's tuition and fees for the way of living the table holds constant,
 * or -- with no arrangement held constant -- the one figure that is true of the
 * school rather than of one column.
 *
 * Two genuinely different policies, so each is its own named function below: one
 * looks a figure up under a constant the domain chose, the other decides whether
 * a school-wide figure exists at all.
 */
private fun summaryTuitionCell(
  cost: CollegeCost,
  held: LivingArrangement?,
): SchoolFigure = if (held == null) schoolWideTuition(cost) else heldArrangementTuition(cost, held)

/**
 * The LOOKUP policy: this school's tuition for the arrangement the table holds
 * constant. A school not priced for it, or priced for it with no published
 * tuition, is a labelled blank -- never another arrangement's figure standing in.
 */
private fun heldArrangementTuition(
  cost: CollegeCost,
  held: LivingArrangement,
): SchoolFigure {
  val amount =
    cost.breakdown
      ?.arrangements
      .orEmpty()
      .firstOrNull { it.arrangement == held }
      ?.tuitionLine
      ?.amountUsd
  return figureOf(amountUsd = amount, blank = tuitionBlankFor(cost))
}

/**
 * The COLLAPSE policy: with no way of living held constant there is no single
 * cell to read across, so a figure is shown only when this school publishes the
 * SAME tuition for every way of living it is priced for -- the only case in
 * which one number is true of the school rather than of one column. A school
 * that publishes several is a blank pointing at the detail table below.
 */
private fun schoolWideTuition(cost: CollegeCost): SchoolFigure {
  val distinct =
    cost.breakdown
      ?.arrangements
      .orEmpty()
      .mapNotNull { it.tuitionLine?.amountUsd }
      .distinct()
  return when (distinct.size) {
    1 -> figureOf(amountUsd = distinct.single(), blank = tuitionBlankFor(cost))
    0 -> figureOf(amountUsd = null, blank = tuitionBlankFor(cost))
    else -> figureOf(amountUsd = null, blank = SUMMARY_TUITION_VARIES)
  }
}

/** One school: its own residency line, its price table, its merit practice, and its debt context. */
private fun FlowContent.schoolSection(
  cost: CollegeCost,
  residency: CollegeResidencyBasis,
  /**
   * Whether the assumption block ABOVE is this one school's own basis -- one
   * fact, in one sense, deciding both of the sentences this section may stop
   * saying. Two parameters ("say the residency line", "here is your hint") could
   * be handed a combination that is not a page: a section that both states its
   * own residency AND prints the one-school hint says the residency twice again.
   */
  basisIsThisSchool: Boolean,
  bandSaidAbove: IncomeBand?,
) {
  section("report-school") {
    h2 { +cost.name }
    // The city and state, and nothing else. The kind of school is NOT printed
    // here: `CollegeControl.label` is a wire label ("private_nonprofit"), and the
    // residency line below already says in English whether this school prices by
    // where the family lives, which is the only thing the control decides here.
    p("report-school-where") { +"${cost.city}, ${cost.state}" }
    // On a one-school report the assumption block above IS this school's basis
    // and already states the residency sentence ([SingleSchoolBasis]). Anywhere
    // else this line is the only place a SPECIFIC school's basis is stated, so
    // it stays.
    if (!basisIsThisSchool) p("report-school-residency") { +residency.statement }
    tuitionTierLine(cost)
    priceTable(cost, bandSaidAbove)
    // ...and the same fact says the hint here, because a one-school page has no
    // summary table above to have said it.
    if (basisIsThisSchool) p("report-hint") { +schoolBlendedHint(cost) }
    meritBlock(cost)
    awardGridBlocks(cost)
    debtBlock(cost)
  }
}

/**
 * WHICH tuition prices this school publishes, in the domain's own sentence (RFC
 * 166 §4) -- including `publisher_does_not_separate_in_district`, the one a
 * family at a community college most needs, which reached the coach and never
 * reached the parent reading this page alone.
 *
 * Printed only where this school publishes at least one tuition price. The
 * decision is the DOMAIN's, read off [CollegeCost.residencyTiers] in an
 * exhaustive `when` with no `else`: a SEVENTH basis must say here whether this
 * page speaks it, rather than inheriting a silence or a sentence nobody chose.
 * A school that publishes no tuition at all is `no_published_tuition`, and its
 * one honest sentence is already on the page as the blank beside the tuition
 * row -- printing it twice would say the same nothing in two voices.
 *
 * This used to be a count of [CollegeCost.publishedTuitionTiers] against the
 * catch-all `single_published_price`, which was this page guarding around a
 * basis that lied; the basis now names the empty case itself.
 */
private fun FlowContent.tuitionTierLine(cost: CollegeCost) {
  val statement =
    when (cost.residencyTiers) {
      ResidencyTierBasis.THREE_TIERS_PUBLISHED,
      ResidencyTierBasis.TWO_TIERS_PUBLISHED,
      ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT,
      ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER,
      ResidencyTierBasis.SINGLE_PUBLISHED_PRICE,
      -> cost.residencyTiers.statement

      // No tier to name: the tuition row's own blank says why, in the school's
      // own status words.
      ResidencyTierBasis.NO_PUBLISHED_TUITION -> return
    }
  p("report-school-tuition-tiers") { +statement }
}

/**
 * One school's price table: tuition and fees first (the stable block), then the
 * estimated components, then the arrangement total, then the published price,
 * then the likely price after a financial aid offer.
 *
 * Columns are the ways of living this school is priced for, so a total belongs
 * to exactly one column and no figure is ever read across two. A school that
 * publishes no component at all has no way of living to head a column and gets
 * one plain column instead -- its tuition, published price and price after aid
 * are still true and still shown.
 */
private fun FlowContent.priceTable(
  cost: CollegeCost,
  bandSaidAbove: IncomeBand?,
) {
  val arrangements = cost.breakdown?.arrangements.orEmpty()
  val columnCount = if (arrangements.isEmpty()) SINGLE_COLUMN_COUNT else arrangements.size
  table("$DATA_TABLE_CLASS report-table") {
    thead { priceTableHeaderRow(arrangements) }
    tbody {
      tuitionRow(cost, arrangements)
      componentRows(cost, arrangements)
      if (arrangements.isNotEmpty()) totalRow(arrangements)
      wholeSchoolRow(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_ABOVE), columnCount)
      wholeSchoolRow(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_ABOVE, bandSaidAbove), columnCount)
    }
  }
}

/**
 * The columns: one per way of living this school is priced for, or a single
 * plain column at a school that publishes no component to head one with.
 */
private fun THEAD.priceTableHeaderRow(arrangements: List<ArrangementCost>) {
  tr {
    th(scope = ThScope.col) { +SINGLE_COLUMN_HEADER }
    if (arrangements.isEmpty()) {
      th(scope = ThScope.col) { +SINGLE_COLUMN_HEADER }
      return@tr
    }
    arrangements.forEach { th(scope = ThScope.col) { +it.arrangement.label } }
  }
}

/** The tuition and fees row: the stable block, above everything estimated. */
private fun TBODY.tuitionRow(
  cost: CollegeCost,
  arrangements: List<ArrangementCost>,
) {
  // A school that publishes no component has no way of living to head a column,
  // so its one cell is headed by the table's own fallback -- the only thing that
  // differs between the two shapes, which is why the row is written once.
  val cells =
    if (arrangements.isEmpty()) {
      listOf(GridCell(SINGLE_COLUMN_HEADER, figureOf(amountUsd = null, blank = tuitionBlankFor(cost))))
    } else {
      arrangements.map { arrangement ->
        GridCell(
          arrangement.arrangement.label,
          figureOf(amountUsd = arrangement.tuitionLine?.amountUsd, blank = tuitionBlankFor(cost)),
        )
      }
    }
  gridRow("report-row-tuition", TUITION_COLUMN, cells)
}

/**
 * One row of the arrangement grid -- said ONCE when it is the same thing in
 * every column (RFC 188).
 *
 * The test is DERIVED and not a list of roles: a row collapses exactly when the
 * figures it would print are equal, `distinct()` over the rendered
 * [SchoolFigure] itself, which carries the amount, the blank label and the basis
 * note. Tuition and books and supplies collapse today because the DOMAIN holds
 * one field for the whole school in each ([ComponentRole.fieldOf] maps every
 * arrangement to `BOOKS_AND_SUPPLIES_PER_YEAR_USD`, and `tuitionLine` is
 * `applicableTuitionFor(control)` for every column) -- but that is their
 * property, not this renderer's rule. Naming the two roles here would let a
 * fourth arrangement with its OWN books field silently collapse two different
 * numbers into one figure.
 *
 * A single-column table collapses nothing: there is no repeat to remove, and the
 * `data-label` the phone layout reads is the way of living, which a one-column
 * row still owes its reader.
 */
private fun TBODY.gridRow(
  rowClass: String,
  label: String,
  cells: List<GridCell>,
) {
  val schoolWide = schoolWideFigureOf(cells)
  tr(rowClass) {
    rowHeader(label)
    if (schoolWide != null) {
      // The span is the row's OWN cell count, never a column count passed in
      // beside the cells: two arguments that must agree are a `colspan` free to
      // disagree with the header above it.
      //
      // No `data-label`: the row's own header names this figure, and the way of
      // living it used to be labelled with is exactly what it no longer varies
      // by. The same shape [wholeSchoolRow] prints, for the same reason.
      spanningValueCell(schoolWide, cells.size)
      return@tr
    }
    cells.forEach { (columnLabel, figure) -> valueCell(columnLabel, figure) }
  }
}

/**
 * The one figure a whole row says, or null where the row really does say a
 * different thing in each column -- the DECISION half of [gridRow], named.
 *
 * The `size > 1` guard is the ONE-COLUMN case: nothing repeats there, and the
 * collapsed cell would drop the `data-label` a phone still reads the way of
 * living from.
 *
 * Structural equality is load-bearing: [SchoolFigure] is a `data class` pair, so
 * `distinct()` compares the amount, the blank label and the basis note, and two
 * cells that differ in any of the three are two different figures. A cell with
 * an assumed-zero note therefore never collapses into a bare zero beside it.
 */
private fun schoolWideFigureOf(cells: List<GridCell>): SchoolFigure? =
  if (cells.size > 1) cells.map { it.figure }.distinct().singleOrNull() else null

/**
 * One cell of the arrangement grid: the figure, and the column it stands under.
 *
 * A named pair rather than `Pair<String, SchoolFigure>`, because the String is
 * not any string -- it is the way of living the phone layout restores beside the
 * figure as its `data-label`, and `it.second` said none of that.
 */
private data class GridCell(
  val columnLabel: String,
  val figure: SchoolFigure,
)

/**
 * Why a school's tuition and fees figure is absent, said as itself.
 *
 * A public school whose family has not said where they live has TWO published
 * figures and neither of them is this family's, so the cell says that rather
 * than "not reported", which would be a false statement about the school.
 */
private fun tuitionBlankFor(cost: CollegeCost): String {
  val control = cost.control
  val withheld = control is CollegeControl.Public && control.tuitionApplicable == TuitionApplicable.UNKNOWN
  if (withheld) return TUITION_WITHHELD
  // WHICH tuition figure this cell would have carried is the domain's own
  // decision ([applicableTuitionFor]), never a second control -> field map here.
  val field = applicableTuitionFor(control) ?: return NOT_REPORTED
  return blankFor(cost, field, NOT_REPORTED)
}

/**
 * The reason a cell is empty, in the STORE's own words where it has any -- the
 * one site on this page that decides what a blank says (RFC 166 §6).
 *
 * [NOT_REPORTED] is a statement ABOUT A NAMED SCHOOL, printed for a parent, and
 * it is false of FOUR of the six statuses: the publisher's estimate, the
 * publisher's suppression, a figure that does not apply at this school, and a
 * figure we have not collected -- which includes RFC 166 §3's year gap, a figure
 * this school DID publish, held only at another academic year. The page prints
 * the domain's sentence for those instead of blaming the school, and authors no
 * copy of its own: the words are [FigureStatusCopy]'s, carried here on
 * [CollegeCost.statusNoteFor].
 *
 * EXHAUSTIVE over the status, not a test for the three bad cases: a seventh
 * status must decide at compile time whether it may be spoken as this school's
 * silence.
 *
 * [otherwise] is the caller's own blank for "the school simply does not report
 * it" -- [NOT_REPORTED] in a cell, the debt paragraph's own sentence below --
 * and it is also what a field with NO ROW AT ALL gets: we hold no status, so we
 * have nothing else to say.
 */
private fun blankFor(
  cost: CollegeCost,
  field: CostField,
  otherwise: String,
): String {
  val note = cost.statusNoteFor(field) ?: return otherwise
  return when (note.status) {
    // The one status [otherwise] is TRUE of: the school reported nothing here.
    // The page keeps its own cell-sized words for it rather than lengthening
    // every ordinary blank on the page into a sentence -- which is also why the
    // tier sentence is NOT composed onto this arm: it rides with the domain's
    // own sentence, and a table cell whose whole content is two long sentences
    // is a different page.
    FigureStatus.NOT_REPORTED_BY_INSTITUTION -> otherwise

    // [FigureStatus.REPORTED] now carries a note -- a shown figure gets one so
    // that it can carry its assurance tier (RFC 179 D6) -- and that note says
    // NOTHING about its status: [CollegeCostService.figureStatusesOf] nulls the
    // statement for a SHOWN reported figure, and a BLANK one cannot be reported
    // at all (`price_figures_value_iff_status_check` /
    // `cohort_money_stats_value_iff_status_check`: a row bears a value exactly
    // when its status is `reported` or `imputed_by_publisher`). A blank cell
    // reached through this arm therefore keeps the caller's own words: a note
    // that says nothing about a status may not blank a cell.
    FigureStatus.REPORTED -> otherwise

    // The four the domain speaks for. Two sentences, in the seam's own order:
    // whose act first, what KIND of number second (RFC 179) -- never one
    // sentence, and never re-worded here. The status sentence is handed over
    // NULLABLE: [AssuranceTierCopy.composedStatementOf] already treats the tier
    // sentence as the FLOOR, and unwrapping it first would fold "this status
    // has no sentence" into the school's silence and drop a tier this note does
    // carry.
    FigureStatus.IMPUTED_BY_PUBLISHER,
    FigureStatus.SUPPRESSED_BY_PUBLISHER,
    FigureStatus.NOT_APPLICABLE,
    FigureStatus.NOT_COLLECTED_BY_US,
    // LINKED, holding no filing: a cost-figure note on this page carries a
    // federal tier, whose sentence promises no link. The page's ONE surface
    // that does hold a filing asks [CdsCitation.linksToFile] instead.
    -> AssuranceTierCopy.composedStatementOf(note.statement, note.assurance, FilingLink.LINKED)
  }
}

/**
 * One row per component ROLE -- housing and food, books and supplies, other
 * expenses -- rather than one per cost field, because each way of living has at
 * most one field in a role and a row per field would print two half-empty
 * "Housing and food" rows side by side.
 *
 * [ComponentRole] is the cost DOMAIN's, beside the fields it classifies: a
 * seventh component added to the vocabulary fails to compile there rather than
 * silently rendering no row here while still being counted in the total.
 *
 * A role is a row only when some way of living on this table is actually made of
 * it. No arrangement is short a role today -- D17 gave living at home its own
 * `$0` food-and-housing line (RFC 166 §7) -- so the filter guards a fourth
 * arrangement, not the with-family column.
 */
private fun TBODY.componentRows(
  cost: CollegeCost,
  arrangements: List<ArrangementCost>,
) {
  ComponentRole.entries
    .filter { role -> arrangements.any { role.fieldOf(it.arrangement) != null } }
    .forEach { role ->
      gridRow(
        "report-row-component",
        role.label,
        arrangements.map { arrangement ->
          val field = role.fieldOf(arrangement.arrangement)
          val line = arrangement.componentLines.firstOrNull { it.field == field }
          val blank = if (field == null) NOT_PART_OF_ARRANGEMENT else blankFor(cost, field, NOT_REPORTED)
          GridCell(
            arrangement.arrangement.label,
            // A note belongs to a figure that EXISTS, which is exactly the
            // assumed at-home zero: [figureOf] attaches it to the amount and
            // drops it with the blank, so the sentence can never end up
            // describing a cell with no number in it.
            figureOf(amountUsd = line?.amountUsd, blank = blank, note = assumedNoteFor(line)),
          )
        },
      )
    }
}

/**
 * Whose number this line carries, said beside the number -- for the one line in
 * this domain whose amount is not a publisher's (RFC 166 §7).
 *
 * Read off [CostLine.origin] rather than off the field: the domain decides which
 * amounts are ours, and a renderer testing for a field name would be a second
 * rule free to disagree with the type that refuses every other assumed line at
 * construction.
 */
private fun assumedNoteFor(line: CostLine?): String? = if (line?.origin == LineOrigin.ASSUMED_BY_UNICOACH) ASSUMED_BY_US else null

/** The per-arrangement total, or the blank that says a part is missing -- never a partial sum. */
private fun TBODY.totalRow(arrangements: List<ArrangementCost>) {
  tr("report-row-total") {
    rowHeader(TOTAL_ROW_LABEL)
    arrangements.forEach {
      valueCell(it.arrangement.label, figureOf(amountUsd = it.totalPerYearUsd, blank = NO_TOTAL))
    }
  }
}

/** A figure that belongs to the school rather than to one way of living, spanning every column. */
private fun TBODY.wholeSchoolRow(
  label: String,
  figure: SchoolFigure,
  columnCount: Int,
) {
  tr("report-row-school") {
    rowHeader(label)
    spanningValueCell(figure, columnCount)
  }
}

/** One figure across every column of the table -- the ONE site that writes that cell. */
private fun TR.spanningValueCell(
  figure: SchoolFigure,
  columnCount: Int,
) {
  td("report-value") {
    colSpan = columnCount.toString()
    figureBody(figure)
  }
}

/**
 * One figure in a cell: the dollars, or the labelled reason there are none.
 *
 * A sealed vocabulary rather than a nullable amount beside a blank label. The
 * record spanned four combinations and two of them were nonsense -- an amount
 * beside a blank reason, and a blank beside a basis note -- so which field was
 * live was a convention the reader had to carry from the renderer. A basis note
 * belongs to a figure that exists and a blank label to one that does not, so
 * neither case can now carry the other's copy.
 */
private sealed interface SchoolFigure {
  /** A published figure, with the basis it rests on (RFC 135's ethos label). */
  data class Amount(
    val amountUsd: Int,
    val note: String? = null,
  ) : SchoolFigure

  /** No figure, and the labelled reason there is none. Never a zero. */
  data class Blank(
    val label: String,
  ) : SchoolFigure
}

/** The ONE site that turns "an amount that may be absent, and why" into the case it is. */
private fun figureOf(
  amountUsd: Int?,
  blank: String,
  note: String? = null,
): SchoolFigure = amountUsd?.let { SchoolFigure.Amount(it, note) } ?: SchoolFigure.Blank(blank)

/**
 * The school's own published cost of attendance -- a blended average, and
 * labelled as one.
 *
 * [totals] points at the figures that ARE this family's, for the OTHER reason
 * this cell can be empty (RFC 157 D-A): the school published the figure and it
 * is not this family's. The amount is already null by then -- the cost answer
 * holds it back, so this page cannot print it even by mistake -- and the caller
 * passes the pointer that is true where the cell is printed.
 */
private fun publishedPriceCell(
  cost: CollegeCost,
  totals: String,
): SchoolFigure =
  figureOf(
    amountUsd = cost.stickerCostOfAttendancePerYearUsd,
    blank = blendedBlankFor(cost, CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, totals),
  )

/**
 * The likely price after a financial aid offer, with its basis attached at the
 * figure (RFC 135's ethos label): an overall average can never be printed here
 * looking like a price for this family.
 *
 * [totals] plays the same part it plays for the published price above, and for
 * the same reason: this figure is the more dangerous half of RFC 157's defect,
 * because the arrangement totals beside it are at least residency-correct.
 */
private fun netPriceCell(
  cost: CollegeCost,
  totals: String,
  bandSaidAbove: IncomeBand?,
): SchoolFigure {
  val blank = blendedBlankFor(cost, CostField.NET_PRICE, totals)
  return when (val netPrice = cost.netPrice) {
    is NetPrice.BandSpecific -> {
      figureOf(
        amountUsd = netPrice.amount,
        blank = blank,
        // Said ONCE per page (RFC 188), and only when the page's own money line
        // already names THIS band: [MoneyBasis.AnsweredBand] states the bracket
        // in the assumption block above every figure on the page, so repeating
        // it at each of the six net-price cells of a three-school report told a
        // parent one fact six times. The test is the band itself, never the
        // answered STATUS -- a cell whose band differs from the one the page
        // stated keeps its own note rather than borrowing a sentence about
        // another bracket.
        note =
          "the average for families with a household income of ${netPrice.band.bracket}"
            .takeUnless { netPrice.band == bandSaidAbove },
      )
    }

    is NetPrice.OverallAverage -> {
      figureOf(
        amountUsd = netPrice.amount,
        blank = blank,
        note = "an overall average across all families, not a figure for this family",
      )
    }

    // The case that says itself: there is no number, and [blank] carries the
    // reason. A note about a band or an average would describe a figure this
    // cell is not printing.
    is NetPrice.Withheld -> {
      SchoolFigure.Blank(blank)
    }
  }
}

/**
 * WHY one of the two blended figures is absent, said as itself -- the twin of
 * [tuitionBlankFor].
 *
 * The withheld set is the cost DOMAIN's own ([CollegeCost.withheld]), never
 * re-derived here from the control and the family's state: the service decides
 * what this family may be shown, and a second rule in a renderer would be free
 * to disagree with the number the same page prints.
 *
 * EXHAUSTIVE over the reason, not a boolean test on the field: a member added to
 * [WithheldReason] must fail to compile here -- the one site on this page that
 * owes it copy -- rather than have its blank explained with the in-state
 * sentence. The null branch is the school's own silence, which is what
 * [NOT_REPORTED] has always meant.
 */
private fun blendedBlankFor(
  cost: CollegeCost,
  field: CostField,
  totals: String,
): String =
  when (val reason = cost.withheldReasonFor(field)) {
    WithheldReason.IN_STATE_ONLY_FIGURE -> withheldBlankFor(reason, totals)

    // Not withheld from this family: whether the blank is the school's silence
    // or somebody else's is [blankFor]'s decision, from the store's status.
    null -> blankFor(cost, field, NOT_REPORTED)
  }

/** The row's own label, in the leading header cell. */
private fun TR.rowHeader(label: String) {
  th(scope = ThScope.row) { +label }
}

/**
 * One value cell: the figure, under the label the phone layout restores beside
 * it once the table stops being a grid. In the per-school tables the label is
 * the way of living the column stands for; in the summary, where the ROWS are
 * schools, it is the figure the column names.
 */
private fun TR.valueCell(
  columnLabel: String,
  figure: SchoolFigure,
) {
  td("report-value") {
    attributes[DATA_LABEL] = columnLabel
    figureBody(figure)
  }
}

/**
 * THE renderer for a figure, and the only one: the dollars, or the labelled
 * blank that says why there are none.
 *
 * One function because three copies of this rule had already drifted -- the
 * third had lost the `report-blank` span, so a whole-school blank rendered
 * unstyled beside a styled one in the table above it.
 *
 * The note prints ONLY beside a figure. It is the figure's BASIS ("an overall
 * average across all families"), so under a blank it described a number that is
 * not there: a parent read "Not reported by this school" followed by a sentence
 * about what the missing figure would have averaged.
 */
private fun FlowContent.figureBody(figure: SchoolFigure) {
  when (figure) {
    is SchoolFigure.Amount -> {
      +WholeDollars.spoken(figure.amountUsd)
      figure.note?.let { note -> span("report-note") { +note } }
    }

    is SchoolFigure.Blank -> {
      span("report-blank") { +figure.label }
    }
  }
}

/**
 * What this school reported about money it gives for something other than
 * financial need (RFC 148), in the domain's own sentences and under the
 * school's OWN citation -- the CDS is a different source from the Scorecard and
 * is cited separately. It is never subtracted from any price above.
 *
 * ATTRIBUTED ONCE, ABOVE THE FIGURES (RFC 190): the citation is an attribution
 * line under the heading rather than a footnote at the foot of the block, for
 * the reason the borrowing header is one -- the claimant is stated before the
 * figures it covers, and every sentence below it comes from that one filing.
 *
 * A citation with no fact under it is not data. [MeritPractice][ed.unicoach.coaching.admissions.MeritPractice]
 * already refuses to exist without a measure, but it can hold recipients with no
 * freshman headcount to make a share out of and no average -- which renders a
 * heading and a source line with nothing between them. That is a section
 * claiming a school reported something while showing nothing it reported, so
 * this returns before opening it.
 */
private fun FlowContent.meritBlock(cost: CollegeCost) {
  val merit = cost.meritAid ?: return
  val share = merit.shareOfAllFullTimeFreshmen
  val average = merit.averageNonNeedAid
  if (share == null && average == null) return
  section("report-merit") {
    h3 { +"Money this school gives for something other than financial need" }
    // The claimant FIRST, under the heading, and not again at the foot (RFC
    // 190): the same string, said before the figures it covers rather than
    // after them, so the block is scoped by its attribution.
    //
    // AND IT IS A LINK (RFC 196). The url was already in hand here and was
    // thrown away at the point of render, while `AssuranceTierCopy`'s
    // voluntary-self-report sentence says, in our own words, "We link to the
    // college's own file so you can see it". The link TEXT is the citation's
    // own `citedAs` -- never the url, which runs to 1,744 characters in our own
    // corpus -- so the page gains a link and no words.
    p("report-source") {
      +"Source: "
      sourceLink(merit.source)
      +"."
    }
    share?.let { p { +"${MeritAidWire.shareLabel(it)}." } }
    average?.let { p { +"${MeritAidWire.averageLabel(it)}." } }
  }
}

/**
 * What this school PUBLISHES about its automatic scholarships (RFC 239) -- one
 * block per published grid, and nothing whatever about the student.
 *
 * NOTHING ABOUT THE STUDENT IS THE WHOLE RULE HERE (D-E). This page is read by a
 * logged-out parent from a share link, and it holds no test score:
 * [CollegeCost.automaticAwards] comes from the student-free door of
 * `AutomaticAwardService`, so there is no score, no band a student reaches and no
 * point gap in scope to print. "Your student scored 1250, so this school pays
 * $6,000" would be a NEW privacy ruling for this page rather than an inherited
 * one, and RFC 239 declines to make it. The absence is asserted by test, because
 * that assertion IS the ruling.
 *
 * ONE BLOCK PER GRID, because residency is part of what the school published: of
 * the 40 curated colleges, 20 publish two grids -- one for students they class
 * as residents and one for everybody else -- and which of them a family falls
 * under is a legal status the SCHOOL determines. Printing one of them would be
 * picking that status for the family; printing both, each scoped by its own
 * heading, prints what the school published and picks nothing.
 *
 * A SIBLING OF [meritBlock] AND NOT A CHILD OF IT (RFC 190): that block
 * attributes everything under its heading to one Common Data Set filing, and a
 * curated scholarship page is a second publisher. Each block here names its own.
 */
private fun FlowContent.awardGridBlocks(cost: CollegeCost) {
  cost.automaticAwards.mapNotNull { printableGridOf(it) }.forEach { awardGridBlock(it) }
}

/** One grid this page will print, with the heading that scopes it to the students the school published it for. */
private data class PrintableGrid(
  val award: AutomaticAward,
  val heading: String,
  /** True for a `score_grid` school: a test score changes the money. False for a school whose automatic table a score does not move. */
  val scoreIsMoney: Boolean,
)

/**
 * Which grids this page prints, decided ONCE so the block and the standing
 * sentence in [tierKindsSection] can never disagree about whether the page
 * speaks about automatic scholarships at all.
 *
 * An exhaustive `when` with no `else`, the [borrowingParagraphs] shape: three of
 * the five answers return before a section is opened, and a sixth arm must say
 * here whether this page speaks it.
 *
 *  - `NotCurated` -- we have not read this school. The page is not the place to
 *    explain our own corpus coverage (the rule [borrowingParagraphs] states).
 *  - `NonePublished` -- a verified absence. True, cited, and not a scholarship
 *    grid: a heading over nothing is what [meritBlock] refuses.
 *  - `Expired` -- our reading of this school is stale, so it is not shown to a
 *    family as current money (D7/D20).
 */
private fun printableGridOf(answer: AutomaticAwardAnswer): PrintableGrid? {
  val award: AutomaticAward
  val scoreIsMoney: Boolean
  when (answer) {
    is AutomaticAwardAnswer.NotCurated -> {
      return null
    }

    is AutomaticAwardAnswer.NonePublished -> {
      return null
    }

    is AutomaticAwardAnswer.Expired -> {
      return null
    }

    is AutomaticAwardAnswer.NoScoreEffect -> {
      award = answer.award
      scoreIsMoney = false
    }

    is AutomaticAwardAnswer.ScoreBands -> {
      award = answer.award
      scoreIsMoney = true
    }
  }
  return PrintableGrid(award = award, heading = gridHeadingOf(award.residencyBasis), scoreIsMoney = scoreIsMoney)
}

/**
 * WHO the school published this grid for, said in the heading. NEVER ABSENT:
 * the three bases the table admits each have a heading, and the two it refuses
 * are corruption rather than a silence this page is entitled to.
 *
 * ALIASED AT THE IMPORT as `PublishedResidency`, because this file already has
 * a `ResidencyBasis`: [ed.unicoach.coaching.costs.ResidencyBasis] is the
 * FAMILY's basis for a price, and this one is the axis the SCHOOL published a
 * grid on. Two different facts that must never be read as one.
 *
 * `in_district` and `unknown` are refused by the table's own CHECK
 * (`create-college-automatic-awards.sql`), so no stored row reaches them --
 * which is why reaching them is CORRUPTION AND IS NAMED rather than a grid this
 * page quietly declines to print. A school publishing two grids would otherwise
 * print one of them and read as complete, and nothing -- not the page, not a log
 * -- would say a current, cited grid had been suppressed. This page answers
 * every other absence with a labelled blank (`NOT_REPORTED`, [withheldBlankFor],
 * [blankFor]); the one thing it does not do is show a family a shorter table
 * than the school published with nothing saying so.
 */
private fun gridHeadingOf(residency: PublishedResidency): String =
  when (residency) {
    PublishedResidency.NOT_APPLICABLE -> {
      AWARD_GRID_HEADING
    }

    PublishedResidency.IN_STATE -> {
      "$AWARD_GRID_HEADING for students it classes as in-state"
    }

    PublishedResidency.OUT_OF_STATE -> {
      "$AWARD_GRID_HEADING for students it classes as out-of-state"
    }

    PublishedResidency.IN_DISTRICT, PublishedResidency.UNKNOWN -> {
      error(
        "automatic award grid carries residency basis [${residency.value}], which this page cannot name an " +
          "audience for (college_automatic_awards_residency_basis_check refuses it, so this row is corrupt)",
      )
    }
  }

/**
 * One school's published grid: who it is for, who published it and when we read
 * it, what it asks a student to submit, the day scores must be there by, and the
 * cells themselves.
 *
 * ATTRIBUTED ONCE, ABOVE THE CELLS (RFC 190), exactly as [meritBlock] and
 * [borrowingParagraphs] are: the claimant and the day we read the page, then
 * everything that came off it. Never once per band.
 *
 * NOT A LINK, unlike [sourceLink]. That helper renders a [CdsCitation], whose
 * `linkTargetOrNull` asserts the scheme before anything reaches an `href` --
 * "href is where escaping does not save a logged-out parent". This corpus's
 * `source_url` has no such door yet, so the publisher is named and no anchor is
 * opened; naming the publisher is what the citation owes a family.
 *
 * The block is ABSENT only where the school published NO cell at all -- a
 * heading and a citation with nothing under them is a section claiming the
 * school published something while showing nothing it published ([meritBlock]'s
 * rule). Every cell the school did publish is printed: [bandLine] can no longer
 * drop one, so a shorter grid than the school's is not a state this page has.
 */
private fun FlowContent.awardGridBlock(grid: PrintableGrid) {
  val award = grid.award
  // In the order the read served them (`AutomaticAwardsDao` orders by test, then
  // by the bottom of the band, then by the grade point average floor -- the
  // order a school prints its own grid in), so this page never re-sorts a
  // school's table into an order it did not publish.
  val cells = award.bands.map { bandLine(it, grid.scoreIsMoney) }
  if (cells.isEmpty()) return
  section("report-award-grid") {
    h3 { +grid.heading }
    p("report-source") { +"Source: ${award.publisher}, read on ${UsDate.of(award.checkedOn)}." }
    if (!grid.scoreIsMoney) p { +NO_SCORE_EFFECT_LINE }
    // The school's OWN sentence about submitting a score where it published one.
    // Where it published none, the silence is stated as the silence it is --
    // never resolved into a "yes" or a "no" the page would be inventing.
    p("report-award-requirement") {
      +(award.scoreRequirementAsPublished ?: "This school does not say whether a test score must be submitted.")
    }
    // THROUGH THE ONE DATE SEAM. [UsDate] is the only thing in this repo that
    // gives a date a human shape, and it returns a date and nothing else -- the
    // sentence around it is this caller's.
    award.scoreDeadline?.let { deadline -> p("report-award-deadline") { +"Scores must be with this school by ${UsDate.of(deadline)}." } }
    ul { cells.forEach { cell -> li { +cell } } }
  }
}

/**
 * One published cell, as a family reads it: what the school calls it, what it
 * asks for, and what it pays.
 *
 * THE SCHOOL'S OWN WORDS WHERE IT PUBLISHES NO FIGURE. Nineteen of the 571
 * committed cells pay "tuition and fees" rather than a number, and they are the
 * TOP bands -- rendering a blank or a zero there would show a school's best
 * award as its worst.
 *
 * NO CELL IS EVER DROPPED. This used to answer null for a cell it could not
 * price, and the caller's `mapNotNull` then printed the surviving cells as the
 * school's grid -- a family reading an incomplete money table that looks
 * complete, with nothing saying so. [AutomaticAwardBand] refuses at construction
 * to carry neither an amount nor the school's words, so [paysOf] reads that
 * guarantee instead of adding a null arm whose only effect is a silent drop.
 *
 * A cell on [BandScoreAxis.NoScore] names no test: that is the published
 * NO-SCORE row -- the tier a student who submits nothing lands in -- and it is
 * what makes "a score is worth more than no score" visible without inventing a
 * zero band. It is a MEMBER of the axis, so this renderer reads it rather than
 * inferring it from a test it did not find.
 */
private fun bandLine(
  band: AutomaticAwardBand,
  scoreIsMoney: Boolean,
): String {
  val pays = paysOf(band)
  // [NO_SCORE_ROW_LABEL] ONLY WHERE A SCORE IS MONEY. At a school whose grid a
  // score moves, a cell on the no-score row IS the published no-score tier and
  // saying so is the point. At a school whose table a score does not move,
  // EVERY cell is on that row, and labelling them all "no test score" would
  // invent a test-optional tier out of a school that has no test axis at all.
  val noScoreRow = band.score is BandScoreAxis.NoScore
  val asks = listOfNotNull(scoreRangeOf(band) ?: NO_SCORE_ROW_LABEL.takeIf { noScoreRow && scoreIsMoney }, gpaRangeOf(band))
  // A cell with no condition on it at all states none: the school published a
  // name and an amount, and the page invents no qualification it did not print.
  if (asks.isEmpty()) return "${band.awardName} — $pays"
  return "${band.awardName} — ${asks.joinToString(", ")} — $pays"
}

/**
 * What this cell pays, in the school's own terms.
 *
 * NEVER ABSENT. `college_automatic_award_bands_award_has_value_check` and
 * [AutomaticAwardBand]'s own `require` both refuse a cell carrying neither an
 * amount nor the school's words, so this states that guarantee rather than
 * dropping a published cell out of a money table in silence.
 */
private fun paysOf(band: AutomaticAwardBand): String =
  band.awardPerYearUsd?.let { "${WholeDollars.spoken(it)} a year" }
    ?: checkNotNull(band.awardAsPublished) {
      "band [${band.id.value}] of award [${band.awardId.value}] states neither an amount nor the school's own " +
        "words (college_automatic_award_bands_award_has_value_check)"
    }

/** The band on the test's own published scale, or null for the published no-score row, which [bandLine] labels instead. */
private fun scoreRangeOf(band: AutomaticAwardBand): String? =
  when (val axis = band.score) {
    is BandScoreAxis.NoScore -> null
    is BandScoreAxis.Range -> "${axis.test.displayName} ${axis.low}–${axis.high}"
  }

/**
 * The grade point average cell, at whichever end the school published one.
 *
 * Either bound may be absent, because a school may publish "3.50 and above" or
 * no grade point average axis at all, and a missing bound is never filled in
 * with a number the school did not print.
 */
private fun gpaRangeOf(band: AutomaticAwardBand): String? {
  // THE ONE HOME FOR THE TYPOGRAPHY ([PublishedGpa]), because the coach's tool
  // says the same published bound aloud and the rule is about a BigDecimal, not
  // about a report.
  val low = band.gpaLow?.let { PublishedGpa.of(it) }
  val high = band.gpaHigh?.let { PublishedGpa.of(it) }
  return when {
    low != null && high != null -> "GPA $low–$high"
    low != null -> "GPA $low and above"
    high != null -> "GPA up to $high"
    else -> null
  }
}

/**
 * Debt context: TWO publishers with two vintages in one section (RFC 175, D9).
 *
 * The parent-facing artifact is where a federal-only debt figure misleads most,
 * so the school's own Common Data Set half sits beside it. Four rules keep that
 * honest, and they are stated here because they are the whole reason the two
 * paragraphs are allowed to share a section:
 *
 * - The Scorecard sentence keeps saying "federal loans" and keeps saying that
 *   the source publishes no year for it.
 * - The CDS sentences name the SCHOOL as the claimant and carry its filing
 *   year, because these are self-reported survey answers (D10).
 * - The two are never summed, never differenced and never presented as one debt
 *   number. Nothing here adds them, and nothing may.
 * - Where the school filed no private figure, this says so, rather than leaving
 *   a family with the impression that federal is the whole of it.
 *
 * No figure in this section is a price, and none of it is ever subtracted from
 * one (brief 0003).
 */
private fun FlowContent.debtBlock(cost: CollegeCost) {
  val debt = cost.medianDebtAtCompletionUsd
  section("report-debt") {
    if (debt == null) {
      // The Scorecard suppresses this figure for privacy more often than it
      // suppresses any other, so the school's-silence sentence was the WRONG
      // one at exactly the schools that reach it most.
      p {
        +blankFor(
          cost,
          CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
          "This school does not report the federal loan debt students carried when they finished.",
        )
      }
    } else {
      p { +shownDebtSentence(cost, debt) }
    }
    borrowingParagraphs(cost)
  }
}

/**
 * The median federal debt, said, with what KIND of number it is under it (RFC
 * 179): `GRAD_DEBT_MDN` is the federal loan file, not something the college
 * filed, and the shown paragraph is where a family first hears that.
 *
 * One level down from the block that decides this is a `p`: what the paragraph
 * SAYS is a sentence-building decision, not layout. Composed by the seam that
 * owns the composition, never by a `+ " "` here -- the ORDER of the two
 * sentences is [AssuranceTierCopy]'s, once, for every surface.
 *
 * IT NAMES ITS OWN PUBLISHER (RFC 190). This paragraph and the borrowing block
 * below it are two publishers in one section: the block header attributes
 * everything under it to the school's own Common Data Set, so a federal figure
 * that named nobody would read as covered by that header. The name comes from
 * the CELL the figure came from, through [MoneySourceCopy] -- never hand-typed,
 * and never at all where the domain resolved no cell.
 */
private fun shownDebtSentence(
  cost: CollegeCost,
  debt: Int,
): String {
  val figure =
    "students who finished here carried a median of ${WholeDollars.spoken(debt)} in federal loans. " +
      "The source publishes no year for this figure."
  // UNREACHABLE for a shown debt figure, and provably so: [CollegeCost.medianDebtAtCompletionUsd]
  // and this note read the SAME cohort row -- a value here means that row exists, and a row with
  // provenance always yields a note (RFC 179 D6). It stays because the note list is assembled by
  // another module: a page may not invent a tier the domain did not resolve, so where the note is
  // somehow absent the figure is said alone rather than under a guessed one -- and unattributed,
  // under the page's own sources section, because a page may not invent a publisher either.
  val note =
    cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)
      ?: return figure.replaceFirstChar { it.uppercase() }
  // This figure's OWN publisher, read off the cell it came from (RFC 184) and spoken by the one
  // seam that names publishers, never typed here: the borrowing block below is attributed to the
  // school's own Common Data Set, and a federal figure left unattributed beside it reads as
  // covered by that header.
  return "${MoneySourceCopy.openingLabelOf(note.cell.source)} reports that $figure"
}

/**
 * The figures this page says in PROSE, and therefore the figures whose KIND it
 * states (RFC 191).
 *
 * The covered set of the tier block, derived from the same condition the
 * paragraph itself renders on -- one federal debt figure per school that has
 * one -- rather than listed beside it. A second list would be free to drift into
 * naming a figure the page does not print, which is the header-covers-the-wrong-
 * figure failure this block exists to avoid.
 *
 * Table cells are NOT here on purpose. A blank cell still says its own status
 * sentence with its own tier beside it ([blankFor]), because a figure with no
 * number is not a kind of number -- the exclusion RFC 189 wrote into the tool
 * description, on this surface. A shown cell states no tier today and states
 * none after this RFC; that gap is RFC 191's open item, not a thing this
 * function may quietly close.
 */
private fun proseFiguresOf(cost: CollegeCost): List<CoveredFigure> {
  if (cost.medianDebtAtCompletionUsd == null) return emptyList()
  val note = cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD) ?: return emptyList()
  return listOf(
    CoveredFigure(
      collegeName = cost.name,
      field = CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      assurance = SpokenAssurance(note.assurance),
    ),
  )
}

/**
 * The figures whose tier this page states IN A CELL rather than in the block
 * ([blankFor]), so the block can tell whether a bare label would reach one of
 * them (RFC 191 D4).
 *
 * A blank keeps its own two sentences and is covered by no group -- a figure
 * with no number is not a kind of number. But the label is the same label: with
 * one school's debt figure shown and another's withheld on a different tier, an
 * unqualified "the median federal loan debt" above one sentence would claim the
 * other figure too. So these are handed to the grouping as context and never
 * rendered by it.
 *
 * Exactly the four statuses [blankFor] speaks a tier for, read from the same
 * enum it switches on: a status this page answers with the caller's own blank
 * says no tier, and adding it here would qualify a label against a sentence
 * nobody printed.
 */
private fun blankFiguresOf(cost: CollegeCost): List<CoveredFigure> =
  cost.figureStatuses
    .filter {
      it.status in
        setOf(
          FigureStatus.IMPUTED_BY_PUBLISHER,
          FigureStatus.SUPPRESSED_BY_PUBLISHER,
          FigureStatus.NOT_APPLICABLE,
          FigureStatus.NOT_COLLECTED_BY_US,
        )
    }.map { CoveredFigure(collegeName = cost.name, field = it.field, assurance = SpokenAssurance(it.assurance)) }

/**
 * What kind of numbers this page's prose figures are -- each sentence said ONCE,
 * for the set of figures it covers (RFC 191, brief 0009 D3).
 *
 * It replaces the same thirty words printed inside every school's debt
 * paragraph. Which sentence, in what order, and which figures each one covers is
 * [AssuranceTierGrouping]'s -- this file authors no money copy -- and the
 * section is ABSENT where the page shows no prose figure at all, rather than
 * rendering a heading over nothing.
 *
 * It sits above [sourcesSection] because both are standing material about the
 * whole page: what kind of numbers these are, then who published them and what
 * this is not.
 */
private fun FlowContent.tierKindsSection(colleges: List<CollegeCost>) {
  val groups =
    AssuranceTierGrouping.groupsOf(
      figures = colleges.flatMap { proseFiguresOf(it) },
      spokenElsewhere = colleges.flatMap { blankFiguresOf(it) },
    )
  // WHETHER ANY GRID IS PRINTED, asked of the one function that decides it
  // ([printableGridOf]) rather than re-derived from the answer arms here: a
  // second rule would be free to say the sentence on a page that shows no grid,
  // or stay silent on one that does.
  val printsAGrid = colleges.any { cost -> cost.automaticAwards.any { printableGridOf(it) != null } }
  if (groups.isEmpty() && !printsAGrid) return
  section("report-kinds") {
    h2 { +KINDS_HEADING }
    groups.forEach { group ->
      p("report-kind") {
        // Two sentences, never one: the scope names the figures and the tier
        // sentence is [AssuranceTierCopy]'s, byte for byte, never re-worded here
        // (RFC 179). No count, no rank, no score sits beside either.
        span("report-kind-scope") { +group.scope }
        +" "
        +group.statement
      }
    }
    // ONCE FOR THE PAGE (RFC 191), here and never inside a school's block: a
    // family reading ten schools is told once what kind of number a published
    // grid is, in the one section that exists to say what kind of numbers these
    // are.
    if (printsAGrid) p("report-kind") { +AWARD_KIND_SENTENCE }
  }
}

/**
 * What this school says its own graduates borrowed, by loan type.
 *
 * Every sentence comes from [BorrowingWire], the one home of this copy, so the
 * page and the coach say a borrowing figure with the same words, the same
 * cohort and the same attribution -- and a change to either lands in both.
 * Nothing is rendered for a school with no filing: the page is not the place to
 * explain our own corpus coverage.
 *
 * ATTRIBUTED ONCE, IN THE HEADER (RFC 190, superseding RFC 175 D10's
 * per-sentence rule): the block header names the school, its cycle and its
 * Common Data Set, and every sentence below it is a figure from that one
 * filing. The header cannot be dropped -- [BorrowingWire.blockOf] hands it out
 * with the sentences as one value -- and no figure from another publisher is
 * rendered under it: the federal median debt above names its own publisher
 * ([shownDebtSentence]).
 */
private fun FlowContent.borrowingParagraphs(cost: CollegeCost) {
  // Exhaustive over the four states, not a pair of early returns: the page
  // says nothing about our own corpus coverage, and an arm that fell through
  // by accident would print a school's section under another school's rule.
  val coverage =
    when (val reported = cost.borrowing) {
      // Three silences, all of them about which filing WE hold. The parent
      // artifact is not the place to explain our corpus, so the section is
      // simply absent -- the coach payload is where each silence is spoken.
      BorrowingCoverage.NoFiling, is BorrowingCoverage.NoBlock, BorrowingCoverage.NotReadByUs -> return

      is BorrowingCoverage.Reported -> reported
    }
  // The HEADER first, then the figures it covers: the block is attributed
  // once, above the sentences, and never again beneath them (RFC 190). The
  // trailing "Source: <cited as>." line this used to close with printed the
  // same `citedAs` the header now opens with -- and nothing else, no url and
  // no document date -- so removing it loses no reachable fact.
  val block = BorrowingWire.blockOf(coverage)
  // The claimant, as a LINK to the school's own filing, and the rest of the
  // same sentence after it (RFC 196). Both halves are [BorrowingWire]'s --
  // `HEADER_TAIL` is the tail of the string `blockHeader` is built from -- so
  // this composes no sentence of its own, and `BorrowingBlock` now carries the
  // CITATION rather than prose flattened from it, which is what makes the link
  // reachable here at all.
  p {
    sourceLink(block.citation)
    +"${BorrowingWire.HEADER_TAIL}."
  }
  // WHICH loan types are spoken, in what order, and when an absence is said
  // out loud, all come from [BorrowingWire.blockOf] -- the one home of this
  // policy. A second list here is how the page and the coach came to narrate
  // the same filing in two different orders.
  block.sentences.forEach { sentence -> p { +"$sentence." } }
}

/**
 * One CDS filing, said as a LINK to the college's own file (RFC 196, brief 0009
 * D15).
 *
 * THE ONE PLACE THIS PAGE OPENS AN ANCHOR. Merit and borrowing are two blocks
 * with one promise behind them: `AssuranceTierCopy`'s voluntary-self-report
 * sentence ends "We link to the college's own file so you can see it", and that
 * sentence is byte-frozen against the immutable v19 prompt row -- it cannot be
 * reworded, so the link has to exist. One helper rather than two renders means a
 * third CDS block cannot be added with a different link shape.
 *
 * - The TARGET is `source_url`, the college's own file, which is what the
 *   sentence promises. `archive_url` -- our mirror -- is not rendered beside it
 *   and never instead of it; it stays reachable on the coach wire, where every
 *   citation still carries both.
 * - The TEXT is [CdsCitation.citedAs], the string this page already printed. The
 *   url is heterogeneous and long (1,744 characters at its worst in our own
 *   seed) and is never shown to anybody.
 * - [CdsCitation.linkTargetOrNull] answers null for a filing we no longer point
 *   at (RFC 200), and OTHERWISE asserts the scheme and FAULTS rather than
 *   rendering: `href` is where escaping does not save a logged-out parent. The
 *   order is deliberate -- a filing whose file is gone needs no link, so its
 *   url is never judged -- and it is tested.
 * - `rel="noreferrer"` even though the report route already sends
 *   `Referrer-Policy: no-referrer` for exactly this reason. The header rides the
 *   ROUTE and the link is on the PAGE; a page rendered from a second route later
 *   would otherwise lose the guarantee with nothing failing.
 *
 * AND THE SAME WORDS WITH NO ANCHOR, where the college's own file is recorded
 * as gone (RFC 200 D6). Only the anchor goes: the family still learns whose
 * claim this is and in which cycle, because [CdsCitation.citedAs] is printed
 * either way. A link to a 404 is a worse answer than no link, and dropping the
 * citation with it would take a fact off the page to hide a broken url.
 */
private fun FlowContent.sourceLink(citation: CdsCitation) {
  val target = citation.linkTargetOrNull()
  if (target == null) {
    +citation.citedAs
  } else {
    a(href = target) {
      rel = "noreferrer"
      +citation.citedAs
    }
  }
}

/**
 * Where every figure came from, and the sentence the whole page exists to keep
 * honest.
 *
 * [sources] are the publishers that actually won the canonical money figures on
 * THIS page (RFC 177 D5), so the first sentence is derived rather than typed. It
 * used to name the College Scorecard for every figure while the loader ranks
 * both IPEDS surveys above it -- a false attribution on a parent-facing page.
 * With no such figure at all the sentence is omitted: there is no publisher to
 * name.
 *
 * The sentence says "cost, price and federal debt" because that is what the set
 * covers: `CollegeFigures.servedSourcesOf` walks every `CostField`, and
 * `MEDIAN_DEBT_AT_COMPLETION_USD` is one of them. Said as "cost and price" it
 * named a Scorecard that published only the debt figure as a publisher of this
 * school's prices -- the same shape of false attribution, one size smaller.
 *
 * The merit-and-borrowing sentence stays hand-written and stays true: those
 * figures are not canonical money rows, they carry no [MoneySource], and each
 * one is already cited to its own school's Common Data Set beside it.
 */
private fun FlowContent.sourcesSection(sources: List<MoneySource>) {
  section("report-sources") {
    h2 { +"Sources and what this is not" }
    p {
      // ABSENT, never an empty string spliced into the paragraph: with no money
      // figure on the page there is no publisher to name (RFC 177). The
      // nullable reaches its point of use, so the sentence is EMITTED or not,
      // and nothing here restores the `""` sentinel `spokenListOf` returns null
      // instead of.
      moneyAttributionSentence(sources)?.let { +it }
      +(
        "The merit and " +
          "borrowing figures come from each school's own Common Data Set, cited beside them. The federal " +
          "debt figure and the school's own borrowing figures are different measures from different " +
          "publishers over different groups of students, so they are never added together and never " +
          "compared with each other."
      )
    }
    p {
      +(
        "These are averages for past students at each school. They are not an offer, and no figure here is a " +
          "price this family has been quoted."
      )
    }
    p {
      +(
        "Only a school's own financial aid offer is a price for this family. Loans and work-study are never " +
          "subtracted from any price here."
      )
    }
  }
}

/**
 * The attribution sentence for this page's money figures, or NULL where there is
 * no publisher to name (RFC 177 D5).
 *
 * Composition, kept out of [sourcesSection]: the renderer lays the section out
 * and this decides what it SAYS. The trailing space is part of the sentence,
 * because the paragraph continues with the merit-and-borrowing sentence right
 * after it.
 *
 * Null rather than an empty string, all the way from `MoneySourceCopy.spokenListOf`
 * to the emit: absence has one representation on this path.
 */
private fun moneyAttributionSentence(sources: List<MoneySource>): String? =
  MoneySourceCopy.spokenListOf(sources)?.let { "The cost, price and federal debt figures come from $it. " }
