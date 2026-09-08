package ed.unicoach.web.render

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
import ed.unicoach.coaching.costs.CostSources
import ed.unicoach.coaching.costs.LineOrigin
import ed.unicoach.coaching.costs.MoneyBasis
import ed.unicoach.coaching.costs.MoneyProfileStatuses
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.ResidencyBasis
import ed.unicoach.coaching.costs.SingleSchoolBasis
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.applicableTuitionFor
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.ResidencyTierBasis
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
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
private const val BLENDED_IN_STATE_CLAUSE =
  "At a public school it, and the likely price after a financial aid offer, are figures for students paying " +
    "in-state tuition."

/** Said under the summary table, because two published prices side by side invite exactly this arithmetic. */
private const val SUMMARY_HINT =
  "The published price is each school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts quoted below. " + BLENDED_IN_STATE_CLAUSE

/** The same warning under ONE school's table, where "each school's" would be false. Written once, like its twin. */
private const val SCHOOL_BLENDED_HINT =
  "The published price is this school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts above it. " + BLENDED_IN_STATE_CLAUSE

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
suspend fun ApplicationCall.respondCostReportPage(profile: CollegeCostProfile) {
  val residencyBases = residencyBasesOf(profile)
  respondHtml {
    siteLayout(PAGE_TITLE, noindex = true) {
      section("report") {
        h1 { +REPORT_HEADING }
        p("report-live") { +LIVE_LINE }
        assumptions(profile, residencyBases)
        if (profile.colleges.isEmpty()) {
          p("report-empty") { +"There is no college on this list yet, so there is nothing to price." }
        } else {
          // The comparison the assumption lines above promise, made real: the
          // basis object is non-null on exactly the same condition (two or more
          // colleges), so the page can never state the comparison assumptions
          // over a page that compares nothing.
          profile.comparisonBasis?.let { summaryTable(profile, it) }
          profile.colleges.forEach { schoolSection(it, residencyBases.getValue(it.collegeId)) }
        }
        sourcesSection()
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
  profile: CollegeCostProfile,
  residencyBases: Map<CollegeId, CollegeResidencyBasis>,
) {
  val basis = profile.comparisonBasis
  val single = profile.colleges.singleOrNull()
  when {
    basis != null -> {
      assumptionList("report-basis", basis.statements, profile.moneyProfile)
    }

    single != null -> {
      assumptionList(
        "report-single-basis",
        SingleSchoolBasis.of(single, residencyBases.getValue(single.collegeId)).statements,
        profile.moneyProfile,
      )
    }

    profile.colleges.isEmpty() -> {
      // Nothing is priced, so there is no assumption to state. NAMED, because
      // the branch it used to share said the same nothing about a page that
      // does print prices.
      Unit
    }

    else -> {
      // Two or more colleges with no comparison basis is not a shape the cost
      // domain builds -- but the price tables below are rendered regardless, and
      // a price with nothing said about whose price it is is the one thing D-F
      // forbids. The family's own money line is the floor, always.
      assumptionList("report-basis", emptyList(), profile.moneyProfile)
    }
  }
}

/** One assumption block: every statement the domain made, then the family's own line. */
private fun FlowContent.assumptionList(
  sectionClass: String,
  statements: List<String>,
  moneyProfile: MoneyProfileStatuses,
) {
  section(sectionClass) {
    h2 { +ASSUMPTIONS_HEADING }
    ul {
      statements.forEach { li { +it } }
      li { +MoneyBasis.of(moneyProfile).statement }
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
) {
  val held = basis.livingArrangement.comparable.firstOrNull()
  section("report-summary") {
    h2 { +SUMMARY_HEADING }
    p("report-hint") { +heldArrangementHint(held) }
    table("report-table report-summary-table") {
      thead { summaryHeaderRow(held) }
      tbody { profile.colleges.forEach { summaryRow(it, held) } }
    }
    p("report-hint") { +SUMMARY_HINT }
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
) {
  tr("report-row-summary") {
    rowHeader(cost.name)
    held?.let { arrangement ->
      // Words, not money: the constant this table holds, said in every row so
      // the stacked phone card still names it.
      td("report-value report-way-of-living") {
        attributes["data-label"] = WAY_OF_LIVING_COLUMN
        +arrangement.label
      }
    }
    valueCell(TUITION_COLUMN, summaryTuitionCell(cost, held))
    valueCell(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW))
    valueCell(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW))
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
) {
  section("report-school") {
    h2 { +cost.name }
    // The city and state, and nothing else. The kind of school is NOT printed
    // here: `CollegeControl.label` is a wire label ("private_nonprofit"), and the
    // residency line below already says in English whether this school prices by
    // where the family lives, which is the only thing the control decides here.
    p("report-school-where") { +"${cost.city}, ${cost.state}" }
    p("report-school-residency") { +residency.statement }
    tuitionTierLine(cost)
    priceTable(cost)
    p("report-hint") { +SCHOOL_BLENDED_HINT }
    meritBlock(cost)
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
private fun FlowContent.priceTable(cost: CollegeCost) {
  val arrangements = cost.breakdown?.arrangements.orEmpty()
  val columnCount = if (arrangements.isEmpty()) SINGLE_COLUMN_COUNT else arrangements.size
  table("report-table") {
    thead { priceTableHeaderRow(arrangements) }
    tbody {
      tuitionRow(cost, arrangements)
      componentRows(cost, arrangements)
      if (arrangements.isNotEmpty()) totalRow(arrangements)
      wholeSchoolRow(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_ABOVE), columnCount)
      wholeSchoolRow(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_ABOVE), columnCount)
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
  tr("report-row-tuition") {
    rowHeader(TUITION_COLUMN)
    if (arrangements.isEmpty()) {
      valueCell(SINGLE_COLUMN_HEADER, figureOf(amountUsd = null, blank = tuitionBlankFor(cost)))
      return@tr
    }
    arrangements.forEach { arrangement ->
      valueCell(
        arrangement.arrangement.label,
        figureOf(amountUsd = arrangement.tuitionLine?.amountUsd, blank = tuitionBlankFor(cost)),
      )
    }
  }
}

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
    // every ordinary blank on the page into a sentence.
    FigureStatus.NOT_REPORTED_BY_INSTITUTION -> otherwise

    // [FigureStatus.REPORTED] carries no note at all, so it cannot arrive here;
    // it is named rather than folded into an `else` so the `when` stays a
    // decision about every status.
    FigureStatus.REPORTED,
    FigureStatus.IMPUTED_BY_PUBLISHER,
    FigureStatus.SUPPRESSED_BY_PUBLISHER,
    FigureStatus.NOT_APPLICABLE,
    FigureStatus.NOT_COLLECTED_BY_US,
    -> note.statement
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
      tr("report-row-component") {
        rowHeader(role.label)
        arrangements.forEach { arrangement ->
          val field = role.fieldOf(arrangement.arrangement)
          val line = arrangement.componentLines.firstOrNull { it.field == field }
          val blank = if (field == null) NOT_PART_OF_ARRANGEMENT else blankFor(cost, field, NOT_REPORTED)
          valueCell(
            arrangement.arrangement.label,
            // A note belongs to a figure that EXISTS, which is exactly the
            // assumed at-home zero: [figureOf] attaches it to the amount and
            // drops it with the blank, so the sentence can never end up
            // describing a cell with no number in it.
            figureOf(amountUsd = line?.amountUsd, blank = blank, note = assumedNoteFor(line)),
          )
        }
      }
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
    td("report-value") {
      colSpan = columnCount.toString()
      figureBody(figure)
    }
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
): SchoolFigure {
  val blank = blendedBlankFor(cost, CostField.NET_PRICE, totals)
  return when (val netPrice = cost.netPrice) {
    is NetPrice.BandSpecific -> {
      figureOf(
        amountUsd = netPrice.amount,
        blank = blank,
        note = "the average for families with a household income of ${netPrice.band.bracket}",
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
    attributes["data-label"] = columnLabel
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
    share?.let { p { +"${MeritAidWire.shareLabel(it)}." } }
    average?.let { p { +"${MeritAidWire.averageLabel(it)}." } }
    p("report-source") { +"Source: ${merit.source.citedAs}." }
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
      p {
        +(
          "Students who finished here carried a median of ${WholeDollars.spoken(debt)} in federal loans. " +
            "The source publishes no year for this figure."
        )
      }
    }
    borrowingParagraphs(cost)
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
  val borrowing = coverage.figures
  p { +"${BorrowingWire.cohortLabel(borrowing.source)}." }
  // WHICH loan types are spoken, in what order, and when an absence is said
  // out loud, all come from [BorrowingWire.listSentences] -- the one home of this
  // policy. A second list here is how the page and the coach came to narrate
  // the same filing in two different orders.
  BorrowingWire.listSentences(coverage).forEach { sentence -> p { +"$sentence." } }
  p("report-source") { +"Source: ${borrowing.source.citedAs}." }
}

/** Where every figure came from, and the sentence the whole page exists to keep honest. */
private fun FlowContent.sourcesSection() {
  section("report-sources") {
    h2 { +"Sources and what this is not" }
    p {
      +(
        "The cost and price figures come from the ${CostSources.SCORECARD_ATTRIBUTION}. The merit and " +
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
