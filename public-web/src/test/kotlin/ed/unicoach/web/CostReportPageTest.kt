package ed.unicoach.web

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.costs.AT_HOME_ASSUMPTION_STATEMENT
import ed.unicoach.coaching.costs.BorrowingCoverage
import ed.unicoach.coaching.costs.BorrowingWire
import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.MoneyBasis
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.UcsdScorecardRow
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.FilingLink
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.costs.spokenLabel
import ed.unicoach.coaching.testing.AutomaticAwardAnswer
import ed.unicoach.coaching.testing.NationalMeritPublishedAnswer
import ed.unicoach.coaching.testing.NextBand
import ed.unicoach.coaching.testing.PublishedBaseRate
import ed.unicoach.coaching.testing.SittingScore
import ed.unicoach.coaching.testing.StudentStanding
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AutomaticAwardBandId
import ed.unicoach.db.models.AutomaticAwardId
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PublishedCell
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.web.render.BLENDED_IN_STATE_CLAUSE
import ed.unicoach.web.render.NOT_REPORTED
import ed.unicoach.web.render.SCHOOL_BLENDED_HINT
import ed.unicoach.web.render.SUMMARY_HINT
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the Family Cost Report actually says (RFC 155's page section).
 *
 * Every test drives the real route with a faked [ed.unicoach.web.report.CostReportSource]
 * and asserts on the rendered body, because the guarantees here are about the
 * words a parent reads: which assumptions are stated, what a missing figure
 * looks like, and which words may never appear at all.
 */
class CostReportPageTest {
  /** A public school in the family's state, priced for all three ways of living. */
  private fun stateSchool(): CollegeCost =
    costFixture(
      name = "Riverside State University",
      control = CollegeControl.Public(TuitionApplicable.IN_STATE),
      tuitionInState = 12000,
      tuitionOutOfState = 34000,
      publishedPrice = 28000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
      housingAndFoodOnCampus = 11000,
      booksAndSupplies = 1200,
      otherExpensesOnCampus = 2500,
      otherExpensesWithFamily = 3000,
      medianDebt = 21000,
      offersOnCampusHousing = true,
      meritAid = meritFixture("Riverside State University"),
    )

  /** A private school that publishes ONE component, so its one arrangement can carry no total. */
  private fun privateSchool(): CollegeCost =
    costFixture(
      name = "Ashford College",
      control = CollegeControl.PrivateNonprofit,
      tuitionInState = 41000,
      publishedPrice = 60000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 25000),
      housingAndFoodOffCampus = 9000,
    )

  private fun render(profile: CollegeCostProfile): String = renderResponse(profile).second

  /** The page and its status: what a test of a page that must refuse itself reads. */
  private fun renderResponse(profile: CollegeCostProfile): Pair<HttpStatusCode, String> {
    lateinit var result: Pair<HttpStatusCode, String>
    testApplication {
      application {
        testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Live(profile)))
      }
      val response = client.get("/report?token=$TEST_LIVE_TOKEN")
      result = response.status to response.bodyAsText()
    }
    return result
  }

  /**
   * How the page states ONE Common Data Set filing since RFC 196: the claimant
   * inside an anchor pointing at the college's own file.
   *
   * Built from the citation rather than typed, so a test cannot pass on a url
   * or a spoken name the fixture does not actually carry.
   */
  private fun linkedCitation(citation: CdsCitation): String = "<a href=\"${citation.url}\" rel=\"noreferrer\">${citation.citedAs}</a>"

  /** Every index [needle] occurs at in [text] -- the spans a "only inside a quotation" assertion needs. */
  private fun occurrencesOf(
    text: String,
    needle: String,
  ): List<Int> {
    val found = mutableListOf<Int>()
    var at = text.indexOf(needle)
    while (at >= 0) {
      found += at
      at = text.indexOf(needle, at + 1)
    }
    return found
  }

  /**
   * The words a parent actually reads, counted the way RFC 188 measured them:
   * the report section with its markup stripped, entities restored, and
   * whitespace-separated tokens counted.
   *
   * The `<head>` and the site chrome are excluded on purpose -- the budget is on
   * the report's own copy, and a footer link is not a sentence about a school.
   */
  private fun visibleWordsOf(body: String): Int {
    val text = visibleProseOf(body)
    return text.split(Regex("\\s+")).count { it.isNotBlank() }
  }

  /**
   * The three schools the word budget is measured over: every one of them
   * priced for all three ways of living, with a merit filing and a borrowing
   * filing, so the fixture carries every repeating block the page can print.
   */
  private fun verboseState(): CollegeCost =
    costFixture(
      name = "Lakeshore State University",
      control = CollegeControl.Public(TuitionApplicable.IN_STATE),
      tuitionInState = 12000,
      tuitionOutOfState = 34000,
      publishedPrice = 28000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
      housingAndFoodOnCampus = 11000,
      housingAndFoodOffCampus = 13000,
      booksAndSupplies = 1200,
      otherExpensesOnCampus = 2500,
      otherExpensesOffCampus = 2800,
      otherExpensesWithFamily = 3000,
      medianDebt = 21000,
      offersOnCampusHousing = true,
      meritAid = meritFixture("Lakeshore State University"),
      borrowing = borrowingFixture("Lakeshore State University"),
    )

  private fun verboseCampus(): CollegeCost =
    costFixture(
      name = "Eastbrook University",
      tuitionInState = 30000,
      publishedPrice = 52000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
      housingAndFoodOnCampus = 10000,
      housingAndFoodOffCampus = 12000,
      booksAndSupplies = 1000,
      otherExpensesOnCampus = 2000,
      otherExpensesOffCampus = 2400,
      otherExpensesWithFamily = 2600,
      medianDebt = 24000,
      offersOnCampusHousing = true,
      meritAid = meritFixture("Eastbrook University"),
      borrowing = borrowingFixture("Eastbrook University"),
    )

  private fun verbosePrivate(): CollegeCost =
    costFixture(
      name = "Wynnford College",
      tuitionInState = 41000,
      publishedPrice = 60000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 25000),
      housingAndFoodOnCampus = 12000,
      housingAndFoodOffCampus = 14000,
      booksAndSupplies = 1100,
      otherExpensesOnCampus = 2200,
      otherExpensesOffCampus = 2600,
      otherExpensesWithFamily = 2900,
      medianDebt = 27000,
      offersOnCampusHousing = true,
      meritAid = meritFixture("Wynnford College"),
      borrowing = borrowingFixture("Wynnford College"),
    )

  @Test
  fun `a two-school list states the six comparison assumptions in the domain's own words`() {
    val profile = costProfile(listOf(stateSchool(), privateSchool()), answeredMoney())
    val basis = requireNotNull(profile.comparisonBasis) { "two colleges must carry a comparison basis" }

    val body = render(profile)

    assertTrue(body.contains(basis.population.statement), "missing the population line")
    assertTrue(body.contains(basis.residency.statement), "missing the residency line")
    // The sixth fact (RFC 157 D-C): a parent who reads the residency line and
    // then two unlabelled blended rows carries the residency onto them.
    assertTrue(body.contains(basis.blendedFigures.statement), "missing the blended-figure residency line")
    assertTrue(body.contains(basis.livingArrangement.statement), "missing the living-arrangement line")
    assertTrue(basis.academicYears.isNotEmpty(), "the fixture must date some figure")
    basis.academicYears.forEach { assertTrue(body.contains(it.statement), "missing an academic-year line") }
    assertTrue(body.contains(basis.aid.statement), "missing the aid line")
  }

  @Test
  fun `a one-school list carries no comparison block, because the domain builds none`() {
    val profile = costProfile(listOf(stateSchool()), answeredMoney())
    assertEquals(null, profile.comparisonBasis, "one college must carry no comparison basis")

    val body = render(profile)

    assertFalse(body.contains("class=\"report-basis\""), "a one-school report must not render the comparison block")
    assertTrue(body.contains("class=\"report-single-basis\""), "a one-school report states its own basis inline")
    // The comparison copy claims something a one-school report does not do.
    assertFalse(body.contains("in this comparison"), "comparison copy leaked onto a one-school report")
    assertFalse(body.contains("in this table"), "comparison copy leaked onto a one-school report")
  }

  /** A second school priced for the same way of living, so a column can hold it constant. */
  private fun campusSchool(): CollegeCost =
    costFixture(
      name = "Northgate University",
      tuitionInState = 30000,
      publishedPrice = 52000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
      housingAndFoodOnCampus = 10000,
      booksAndSupplies = 1000,
      otherExpensesOnCampus = 2000,
      offersOnCampusHousing = true,
    )

  /** The summary block alone, so a figure found in it cannot be one of the detail tables' figures. */
  private fun summaryOf(body: String): String {
    val start = body.indexOf("class=\"report-summary\"")
    assertTrue(start > 0, "the page must render a cross-school summary")
    val end = body.indexOf("class=\"report-school\"", start)
    assertTrue(end > start, "the summary must sit above the per-school detail")
    return body.substring(start, end)
  }

  @Test
  fun `a two-school list is actually compared, in one table above the per-school detail`() {
    val body = render(costProfile(listOf(stateSchool(), campusSchool()), answeredMoney()))

    val summary = summaryOf(body)
    assertTrue(summary.contains("The schools side by side"), "the comparison the assumption lines promise must exist")
    assertTrue(summary.contains("Riverside State University"), "every school is a row")
    assertTrue(summary.contains("Northgate University"), "every school is a row")
    assertTrue(summary.contains("The published price"), "the summary compares the published price")
    assertTrue(
      summary.contains("The likely price after a financial aid offer"),
      "the summary compares the price after a financial aid offer",
    )
    assertTrue(summary.contains("\$12,000") && summary.contains("\$30,000"), "each school's own tuition and fees")
    assertTrue(summary.contains("\$28,000") && summary.contains("\$52,000"), "each school's own published price")
    // The detail is kept, not replaced: the arrangement columns are still below.
    assertTrue(body.contains("Total per year"), "the per-school arrangement detail must survive the summary")
  }

  @Test
  fun `the summary holds the same way of living the assumption lines name`() {
    val profile = costProfile(listOf(stateSchool(), campusSchool()), answeredMoney())
    val basis = requireNotNull(profile.comparisonBasis) { "two colleges must carry a comparison basis" }
    val held = basis.livingArrangement.comparable.first()

    val body = render(profile)

    assertTrue(basis.livingArrangement.statement.contains(held.label), "the basis line names the arrangement it holds")
    val summary = summaryOf(body)
    assertTrue(summary.contains("Way of living"), "the held constant is named in the table that uses it")
    assertTrue(summary.contains(held.label), "the summary must use the arrangement the basis lines name: [${held.label}]")
  }

  @Test
  fun `a one-school list carries no summary table, because there is nothing to compare`() {
    val body = render(costProfile(listOf(stateSchool()), answeredMoney()))

    assertFalse(body.contains("report-summary"), "one school is not a comparison")
    assertFalse(body.contains("The schools side by side"), "one school is not a comparison")
  }

  @Test
  fun `a summary figure a school does not publish is a labelled blank, never its neighbour's number`() {
    val silent = costFixture(name = "Quiet College", housingAndFoodOnCampus = 8000)

    val body = render(costProfile(listOf(campusSchool(), silent), answeredMoney()))

    val summary = summaryOf(body)
    assertTrue(summary.contains("Not reported by this school"), "an unpublished figure is a labelled blank")
    assertFalse(summary.contains("\$0"), "an unpublished figure is never a zero")
    // Quiet College publishes no price at all, so the only figures in the table
    // are Northgate's own -- one each, never borrowed sideways.
    assertEquals(1, summary.split("\$52,000").size - 1, "a school's published price must appear in its own row only")
  }

  @Test
  fun `with no way of living priced at every school the summary says so and holds none constant`() {
    // Priced only for living at home at one school and only for living on
    // campus at the other: the two share nothing to hold constant.
    val atHome = costFixture(name = "Hillside College", tuitionInState = 20000, otherExpensesWithFamily = 3000)
    val onCampus = costFixture(name = "Northgate University", tuitionInState = 30000, housingAndFoodOnCampus = 10000)
    val profile = costProfile(listOf(atHome, onCampus), answeredMoney())
    assertEquals(
      emptyList(),
      requireNotNull(profile.comparisonBasis).livingArrangement.comparable,
      "the fixture must share no way of living",
    )

    val summary = summaryOf(render(profile))

    assertFalse(summary.contains("Way of living"), "an arrangement nobody shares must not be presented as held constant")
    assertTrue(
      summary.contains("No one way of living is priced at every school here"),
      "the page must say why the parts are quoted school by school",
    )
    assertTrue(summary.contains("Hillside College") && summary.contains("Northgate University"), "both schools still compare")
    assertTrue(summary.contains("\$20,000") && summary.contains("\$30,000"), "one tuition per school is still true of it")
  }

  @Test
  fun `a missing component renders a labelled blank and its arrangement shows no total`() {
    val body = render(costProfile(listOf(privateSchool()), answeredMoney()))

    // The school publishes housing and food off campus and nothing else, so the
    // other two parts of that way of living are labelled blanks, not zeroes.
    assertTrue(body.contains("Not reported by this school"), "a missing component must be a labelled blank")
    assertFalse(body.contains("\$0"), "a missing component must never render as a zero")
    assertTrue(body.contains("No total"), "an arrangement missing a part must show no total")
  }

  /**
   * REVERSAL of committed behaviour, gate-2 D17 (RFC 166 §7). Until this slice
   * this test was `a school priced only for living at home shows no housing and
   * food line` and asserted the opposite -- `assertFalse(body.contains("Housing
   * and food"))` -- because a `$0` there was held to be a fabricated fact about
   * a school that published nothing.
   *
   * D17 overturns that for living at home ALONE: no source publishes a
   * with-family food-and-housing figure because there is nothing to publish --
   * eating at home is not free, but it is not a new cost that ENROLLING creates.
   * So the line exists, its amount is zero, and the zero is OURS. The page's job
   * is to print it as ours, and this test is what stops it printing as the
   * school's.
   *
   * Every other arrangement keeps both committed rules exactly, which the tail
   * of this test pins: no partial total, and no silent zero.
   */
  @Test
  fun `living at home shows our own zero housing and food line, and never as the school's figure`() {
    val atHomeOnly =
      costFixture(
        name = "Hillside College",
        tuitionInState = 20000,
        otherExpensesWithFamily = 3000,
      )
    val breakdown = requireNotNull(atHomeOnly.breakdown) { "the fixture must publish one component" }
    assertEquals(listOf("living at home"), breakdown.arrangements.map { it.arrangement.label })

    val body = render(costProfile(listOf(atHomeOnly), answeredMoney()))

    assertTrue(body.contains("living at home"), "the way of living must be named")
    assertTrue(body.contains("Housing and food"), "living at home now carries a food-and-housing line")
    assertTrue(
      body.contains("\$0<span class=\"report-note\">our assumption, not a figure this school published</span>"),
      "the zero must print WITH the note that says whose number it is: [$body]",
    )
    // This school is one part short of an at-home TOTAL (it publishes no books
    // allowance) -- and the sentence is said ANYWAY, because the `$0` is
    // printed anyway. The gate follows the LINE, not the total: a zero on the
    // page with nothing above it saying whose zero it is, is the exact defect
    // D17 exists to prevent, and it is reachable at every part-published school.
    assertTrue(
      body.contains(AT_HOME_ASSUMPTION_STATEMENT),
      "wherever the zero is shown, the sentence that says whose it is must be shown too: [$body]",
    )
    // And where a total IS settled, the whole sentence is said the same way: the
    // domain's, not the page's, reaching the parent through the same basis list
    // every other honesty statement does.
    val withAtHomeTotal = render(costProfile(listOf(stateSchool()), answeredMoney()))
    assertTrue(withAtHomeTotal.contains("\$16,200"), "the at-home total is complete: tuition, our zero, books, other expenses")
    assertTrue(
      withAtHomeTotal.contains(AT_HOME_ASSUMPTION_STATEMENT),
      "the assumption must be stated in full above the table, in the domain's own words",
    )
    // The one thing the note exists to prevent: the zero read as a school's
    // silence, or as a figure the school reported.
    assertFalse(
      body.contains("\$0<span class=\"report-blank\""),
      "our assumed amount is a figure that exists, never a labelled blank",
    )

    // And the limit of the reversal: a `$0` on its own is not a price list, so a
    // school that publishes nothing at all for living at home gets no at-home
    // arrangement -- our assumption alone can never conjure one.
    val noAtHomeFigures = requireNotNull(privateSchool().breakdown) { "the fixture must publish one component" }
    assertEquals(
      emptyList(),
      noAtHomeFigures.arrangements.map { it.arrangement.label }.filter { it == "living at home" },
      "an arrangement whose only line is our assumption is not an arrangement this school prices",
    )
  }

  @Test
  fun `an unanswered money profile renders the overall figures, labelled, and claims no band`() {
    val school =
      costFixture(
        name = "Ashford College",
        tuitionInState = 41000,
        publishedPrice = 60000,
        netPrice = NetPrice.OverallAverage(24000),
      )

    val body = render(costProfile(listOf(school), UNANSWERED_MONEY))

    assertTrue(body.contains("The household income question is unanswered"), "the page must say which question is open")
    assertTrue(body.contains("an overall average across all families"), "the overall average must be labelled as one")
    IncomeBand.entries.forEach {
      assertFalse(body.contains(it.bracket), "an unanswered profile must claim no income band: [${it.bracket}]")
    }
    assertTrue(body.contains("\$24,000"), "the overall average figure is still shown")
  }

  @Test
  fun `an answered profile states the family's income band in dollars`() {
    val body = render(costProfile(listOf(stateSchool(), privateSchool()), answeredMoney(IncomeBand.K48_TO_75K)))

    assertTrue(body.contains(IncomeBand.K48_TO_75K.bracket), "the band the net price rests on must be stated in dollars")
  }

  @Test
  fun `merit practice is cited to the school's own Common Data Set`() {
    val body = render(costProfile(listOf(stateSchool(), privateSchool()), answeredMoney()))

    assertTrue(body.contains("of all full-time freshmen received non-need (merit) aid"), "missing the merit share sentence")
    assertTrue(body.contains("Riverside State University's 2024-25 Common Data Set"), "missing the CDS citation")
  }

  @Test
  fun `the page names its sources and says what it is not`() {
    val body = render(costProfile(listOf(stateSchool(), privateSchool()), answeredMoney()))

    // DERIVED from the rows the page actually served (RFC 177 D5). These two
    // fixtures publish Scorecard-sourced rows, so the Scorecard is what the page
    // names -- and the IPEDS test below proves it is read off the data rather
    // than typed.
    assertTrue(
      body.contains("The cost, price and federal debt figures come from ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}."),
      "missing the derived cost attribution",
    )
    assertTrue(body.contains("Common Data Set"), "missing the CDS attribution")
    assertTrue(body.contains("They are not an offer"), "the page must say these are not an offer")
    assertTrue(
      body.contains("Only a school's own financial aid offer is a price for this family"),
      "the page must say what a price actually is",
    )
  }

  /**
   * THE LIVE DEFECT, pinned (RFC 177). `CanonicalMoneyLoader.ORDERED_SOURCES`
   * ranks IPEDS SFA and IPEDS IC_AY above the Scorecard, so most price cells in
   * production are IPEDS'. This page attributed every one of them to the College
   * Scorecard, from a constant, and no test could see it because the constant
   * agreed with itself.
   *
   * Fails on the code as it stood before this slice: the page's sentence was the
   * Scorecard's name whatever the rows said.
   */
  @Test
  fun `a school whose price rows are IPEDS is never attributed to the Scorecard`() {
    val ipedsSchool =
      costFixture(
        name = "IPEDS Priced U",
        control = CollegeControl.Public(TuitionApplicable.IN_STATE),
        tuitionInState = 12000,
        tuitionOutOfState = 34000,
        housingAndFoodOnCampus = 11000,
        booksAndSupplies = 1200,
        otherExpensesOnCampus = 2500,
        pricePublisher = PublishedCell.Survey.IC_AY,
      )

    val body = render(costProfile(listOf(ipedsSchool), answeredMoney()))

    // Its PRICE rows are IPEDS', and this school publishes no blended figure at
    // all -- it names no published price, no net price and no median debt -- so
    // every Scorecard row it carries is value-free. D5 names the publisher of a
    // figure a family can SEE, so IPEDS is the whole list here, exactly, and the
    // Scorecard is correctly absent from it. The two-publisher sentence is
    // pinned by the test below, on a school that really does show a Scorecard
    // figure.
    assertTrue(
      body.contains(
        "The cost, price and federal debt figures come from ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)}.",
      ),
      "the page must name the publisher whose rows actually won: [$body]",
    )
    assertFalse(
      body.contains("The cost, price and federal debt figures come from ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}."),
      "the sentence this slice deletes: every price figure attributed to the Scorecard, from a constant",
    )
    assertFalse(
      body.contains("come from ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)} and ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}"),
      "this school shows no Scorecard figure, so the Scorecard may not join the sentence",
    )
  }

  /**
   * The other half of the same rule (RFC 177 D5): two publishers, one sentence.
   *
   * This school is the ordinary shape of a real one -- its price cells are
   * IPEDS', and it also shows a Scorecard figure with a number in it, the median
   * federal debt at completion. Both publishers therefore published a figure on
   * this page, so both are named, joined by "and" and ranked as
   * `CanonicalMoneyLoader.ORDERED_SOURCES` ranks them: IPEDS before the
   * Scorecard.
   *
   * Asserted on a rendered page rather than only in the projection's own unit
   * tests, because the joiner and the order are what a parent reads.
   */
  @Test
  fun `a school with figures from two publishers names both, in the loader's own order`() {
    val twoPublisherSchool =
      costFixture(
        name = "Two Publisher University",
        control = CollegeControl.Public(TuitionApplicable.IN_STATE),
        tuitionInState = 12000,
        housingAndFoodOnCampus = 11000,
        booksAndSupplies = 1200,
        otherExpensesOnCampus = 2500,
        // The Scorecard's row, and it bears a value -- so the Scorecard really
        // is a publisher of a figure this family can see.
        medianDebt = 21000,
        pricePublisher = PublishedCell.Survey.IC_AY,
      )

    val body = render(costProfile(listOf(twoPublisherSchool), answeredMoney()))

    assertTrue(
      body.contains(
        "The cost, price and federal debt figures come from ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)} and " +
          "${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}.",
      ),
      "both publishers of a shown figure must be named, in the loader's order: [$body]",
    )
    assertTrue(body.contains("\$21,000 in federal loans"), "the Scorecard figure that earns it a place must be on the page")
    assertFalse(
      body.contains("come from ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)} and ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)}"),
      "the Scorecard is ranked below IPEDS, so it may never be named first",
    )
  }

  /**
   * D5's rule is VALUE-BEARING, not row-bearing (RFC 177, tier-2 fix).
   *
   * This school's price figures are IPEDS'. Every Scorecard row it carries is
   * value-free -- one of them SUPPRESSED for privacy -- so the Scorecard
   * published no figure this family can see, and the sentence "the cost, price
   * and federal debt figures come from ..." may not name it. That sentence is a
   * claim about figures ON THE PAGE; naming a publisher of none of them is the
   * same false attribution this slice exists to delete, one size smaller.
   *
   * The suppressed figure's OWN sentence still names the Scorecard, and must:
   * there the publisher IS the fact -- it is who withheld the number.
   *
   * Fails on the code as it stood before the fix: `servedSourcesOf` asked whether
   * a ROW answered, so a suppressed cell put its publisher in the page's source
   * list.
   */
  @Test
  fun `a publisher whose only row bears no figure is not named as a source of the figures`() {
    val ipedsPricedScorecardSilent =
      costFixture(
        name = "Suppressed Debt College",
        control = CollegeControl.Public(TuitionApplicable.IN_STATE),
        tuitionInState = 12000,
        housingAndFoodOnCampus = 11000,
        booksAndSupplies = 1200,
        otherExpensesOnCampus = 2500,
        // Every cohort row -- the Scorecard's -- carries no amount, and the debt
        // row carries the publisher's own suppression.
        publishedPrice = null,
        netPrice = NetPrice.OverallAverage(null),
        medianDebt = null,
        absenceStatuses = mapOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        pricePublisher = PublishedCell.Survey.IC_AY,
      )

    val body = render(costProfile(listOf(ipedsPricedScorecardSilent), answeredMoney()))

    assertTrue(
      body.contains(
        "The cost, price and federal debt figures come from ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)}.",
      ),
      "only the publisher of a figure this page shows may be named: [$body]",
    )
    assertFalse(
      body.contains("come from ${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)} and ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}"),
      "a publisher whose every row is value-free published no figure on this page",
    )
    assertTrue(
      body.contains(
        requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.SCORECARD)),
      ),
      "the suppressed figure's own sentence still names who withheld it: [$body]",
    )
  }

  /**
   * The other half of D5: a page with no price figure at all names no publisher.
   * The sentence is omitted rather than emitted with an empty name, because
   * there is nothing to say.
   */
  @Test
  fun `a page with no price figure names no publisher at all`() {
    val body = render(costProfile(emptyList(), answeredMoney()))

    assertFalse(body.contains("The cost, price and federal debt figures come from"), "no figure, no publisher to name")
    MoneySource.entries.forEach { source ->
      assertFalse(body.contains(MoneySourceCopy.labelOf(source)), "[${source.value}] must not be claimed: [$source]")
    }
  }

  @Test
  fun `the report renders the money vocabulary and never the words it must not say`() {
    val body = render(costProfile(listOf(stateSchool(), privateSchool()), answeredMoney()))

    assertTrue(body.contains("Tuition and fees"), "missing the tuition and fees block")
    assertTrue(body.contains("Housing and food"), "missing the housing and food line")
    assertTrue(body.contains("The published price"), "missing the published price")
    assertTrue(body.contains("The likely price after a financial aid offer"), "missing the price after a financial aid offer")

    val lower = body.lowercase()
    listOf("room and board", "sticker price", "award", "without need").forEach {
      assertFalse(lower.contains(it), "the report must never say \"$it\"")
    }
  }

  @Test
  fun `debt context is rendered without a year, because no source dates it`() {
    val body = render(costProfile(listOf(stateSchool()), answeredMoney()))

    assertTrue(body.contains("\$21,000 in federal loans"), "missing the debt figure")
    assertTrue(body.contains("The source publishes no year for this figure"), "the debt figure must be undated on purpose")
  }

  @Test
  fun `the shown debt paragraph says what KIND of number it is, and it is not the college's`() {
    // Where ADMINISTRATIVE_RECORD first becomes visible to a family (RFC 179):
    // the median federal debt is `GRAD_DEBT_MDN`, which the Scorecard relays
    // from the federal loan file. Until a SHOWN figure carried a note there was
    // nowhere on this page to say so, and the number read as the college's own.
    val body = render(costProfile(listOf(stateSchool()), answeredMoney()))

    assertTrue(
      body.contains(AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)),
      "the shown debt paragraph carries the administrative-record sentence: [$body]",
    )
    // Beside the figure, not instead of it, and the two sentences stay two.
    assertTrue(
      body.indexOf("The source publishes no year for this figure") <
        body.indexOf(AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)),
      "the tier sentence follows the figure's own words",
    )
  }

  @Test
  fun `a blank cell says the status first and what kind of number it would have been second`() {
    // The page's own order, stated once (RFC 179): whose act, then what kind of
    // number. Never one concatenated sentence, and never the tier alone -- a
    // reader who is told only "nobody audits it" has not been told why the cell
    // is empty.
    val body =
      render(
        costProfile(
          listOf(
            oneBlankSchool(
              absenceStatuses = mapOf(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
            ),
          ),
          answeredMoney(),
        ),
      )

    val status = requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.SCORECARD))
    val tier = AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)
    assertTrue(body.contains("$status $tier"), "status sentence then tier sentence, in that order: [$body]")
  }

  @Test
  fun `a public school with no residency on file shows neither tuition figure`() {
    val unknownResidency =
      costFixture(
        name = "Riverside State University",
        control = CollegeControl.Public(TuitionApplicable.UNKNOWN),
        tuitionInState = 12000,
        tuitionOutOfState = 34000,
        booksAndSupplies = 1200,
      )

    val body = render(costProfile(listOf(unknownResidency), UNANSWERED_MONEY))

    assertTrue(
      body.contains("Not shown \u2014 the state the family lives in is not on file"),
      "a withheld tuition figure must say why it is withheld",
    )
    assertFalse(body.contains("\$12,000"), "the in-state figure must not be shown as this family's")
    assertFalse(body.contains("\$34,000"), "the out-of-state figure must not be shown as this family's")
  }

  @Test
  fun `no student identity reaches the page`() {
    val body = render(costProfile(listOf(stateSchool(), privateSchool()), answeredMoney()))

    assertFalse(body.contains("student_id"), "the page must carry no student id")
    assertFalse(body.contains("@"), "the page must carry no email address")
  }

  @Test
  fun `every blank on the page is a labelled blank, in one styled shape`() {
    // A school that reports NOTHING school-wide: both whole-school rows are
    // blanks, and they must be the same styled span the per-column blanks are.
    // One of the three copies of this rule had dropped the span, so a
    // whole-school blank rendered unstyled beside a styled one.
    val silentSchool =
      costFixture(
        name = "Silent College",
        tuitionInState = 41000,
        housingAndFoodOffCampus = 9000,
      )

    val body = render(costProfile(listOf(silentSchool), UNANSWERED_MONEY))

    val school = body.substringAfter("class=\"report-row-school\"")
    assertTrue(
      school.contains("<span class=\"report-blank\">Not reported by this school</span>"),
      "a whole-school blank must be the SAME styled blank as every other: [$school]",
    )
    assertFalse(
      body.contains("<td class=\"report-value\" colspan=\"1\">Not reported"),
      "an unstyled bare-text blank means the one renderer was bypassed",
    )
  }

  @Test
  fun `the net-price basis note is printed only where there is a figure to describe`() {
    val noNetPrice =
      costFixture(
        name = "Ashford College",
        tuitionInState = 41000,
        publishedPrice = 60000,
        netPrice = NetPrice.OverallAverage(null),
      )

    val body = render(costProfile(listOf(noNetPrice), UNANSWERED_MONEY))

    // The note is the FIGURE's basis. Under a blank it described a number that
    // is not there: "Not reported by this school" followed by a sentence about
    // what the missing figure would have averaged.
    assertTrue(body.contains("Not reported by this school"), "the missing net price is a labelled blank")
    assertFalse(
      body.contains("<span class=\"report-note\">an overall average across all families"),
      "a basis note must never describe a figure the school did not report",
    )

    // And it IS printed when there is a figure.
    val withNetPrice = costFixture(name = "Ashford College", tuitionInState = 41000, netPrice = NetPrice.OverallAverage(24000))
    val withBody = render(costProfile(listOf(withNetPrice), UNANSWERED_MONEY))
    assertTrue(
      withBody.contains("an overall average across all families, not a figure for this family"),
      "a figure that is an overall average must always say so",
    )
  }

  @Test
  fun `a merit row with no measure under it renders no merit section at all`() {
    // Recipients on file, but no freshman headcount to make a share out of and
    // no average: a heading plus a "Source:" line with nothing between them is a
    // section claiming a school reported something while showing nothing.
    val factless = meritFixture("Ashford College", freshmen = null, recipients = 250, averageAid = null)
    val school = costFixture(name = "Ashford College", tuitionInState = 41000, meritAid = factless)

    val body = render(costProfile(listOf(school), UNANSWERED_MONEY))

    assertFalse(
      body.contains("Money this school gives for something other than financial need"),
      "a merit section with no fact under it must not be opened at all",
    )
    assertFalse(body.contains("Ashford College's 2024-25 Common Data Set"), "no citation without a fact to cite it for")
  }

  // ---------------------------------------------------------------------------
  // RFC 157: the residency basis of the two blended figures
  // ---------------------------------------------------------------------------

  /**
   * UC San Diego as the Scorecard actually publishes it (RFC 157's evidence).
   *
   * Real figures, because the defect is arithmetic: 38,701 is inside the
   * in-state span [25,723 .. 43,459] and below the out-of-state minimum of
   * 59,923, so no arrangement weighting on out-of-state totals can produce it.
   * The reported 77K/77K/60K a WA family saw are reproduced exactly below.
   */
  private fun ucSanDiego(applicable: TuitionApplicable): CollegeCost =
    costFixture(
      name = "UC San Diego",
      control = CollegeControl.Public(applicable),
      city = "La Jolla",
      state = UcsdScorecardRow.STATE,
      tuitionInState = UcsdScorecardRow.TUITIONFEE_IN,
      tuitionOutOfState = UcsdScorecardRow.TUITIONFEE_OUT,
      publishedPrice = UcsdScorecardRow.COSTT4_A,
      netPrice = NetPrice.BandSpecific(IncomeBand.OVER_110K, UcsdScorecardRow.NPT45_PUB),
      housingAndFoodOnCampus = UcsdScorecardRow.ROOMBOARD_ON_CAMPUS,
      housingAndFoodOffCampus = UcsdScorecardRow.ROOMBOARD_OFF_CAMPUS,
      booksAndSupplies = UcsdScorecardRow.BOOKSUPPLY,
      otherExpensesOnCampus = UcsdScorecardRow.OTHEREXPENSE_ON_CAMPUS,
      otherExpensesOffCampus = UcsdScorecardRow.OTHEREXPENSE_OFF_CAMPUS,
      otherExpensesWithFamily = UcsdScorecardRow.OTHEREXPENSE_WITH_FAMILY,
      offersOnCampusHousing = true,
    )

  @Test
  fun `a WA family at UC San Diego reads the out-of-state totals and NEITHER blended figure`() {
    // The page Ian's report showed: correct 77K/77K/60K totals, and two rows
    // below them a $39K "published price" that is a California family's number,
    // with nothing on the page saying so.
    val profile =
      costProfile(
        listOf(ucSanDiego(TuitionApplicable.OUT_OF_STATE)),
        answeredMoney(band = IncomeBand.OVER_110K, state = "WA"),
      )

    val body = render(profile)

    assertTrue(body.contains("\$77,102"), "the on-campus total, built from out-of-state tuition and fees")
    assertTrue(body.contains("\$77,659"), "the off-campus total")
    assertTrue(body.contains("\$59,923"), "the with-family total")

    assertFalse(body.contains("\$38,701"), "COSTT4_A is a California family's published price, never this family's")
    assertFalse(body.contains("\$28,785"), "the top-band net price is in-state too, and understates by ~48,000")

    assertTrue(
      body.contains("this school publishes this figure for in-state students"),
      "the blank names the reason rather than reading as a school that reported nothing",
    )
    assertTrue(
      body.contains("Your family would pay the out-of-state price"),
      "and it points at the figures that ARE theirs",
    )
    assertTrue(
      body.contains("so neither figure is shown for them"),
      "the assumption block states the sixth fact for this one school too",
    )
    assertFalse(
      body.contains("Not reported by this school"),
      "a withheld figure must never be labelled as the school's silence: this school published both numbers",
    )
  }

  @Test
  fun `the same school prints both blended figures for a family in its own state`() {
    // The withholding is about residency and about nothing else: an in-state
    // family is exactly the family these two figures describe.
    val profile =
      costProfile(
        listOf(ucSanDiego(TuitionApplicable.IN_STATE)),
        answeredMoney(band = IncomeBand.OVER_110K, state = "CA"),
      )

    val body = render(profile)

    assertTrue(body.contains("\$38,701"), "the published price is theirs")
    assertTrue(body.contains("\$28,785"), "and so is the top-band price after a financial aid offer")
    assertTrue(body.contains("\$42,902"), "the in-state on-campus total")
    assertFalse(
      body.contains("this school publishes this figure for in-state students"),
      "nothing is withheld from the family the figures describe",
    )
  }

  @Test
  fun `an unanswered residency shows both figures with the basis said, and hides nothing`() {
    // RFC 157 D-B, and brief 0001 D11/D12: an unanswered question is not licence
    // to hide the only price we hold, and no answer is gated on a full profile.
    val profile = costProfile(listOf(ucSanDiego(TuitionApplicable.UNKNOWN)), UNANSWERED_MONEY)

    val body = render(profile)

    assertTrue(body.contains("\$38,701"), "the published price is still shown")
    assertFalse(
      body.contains("this school publishes this figure for in-state students. Your family"),
      "an open question withholds nothing",
    )
    assertTrue(
      body.contains("so both are shown on that basis rather than withheld"),
      "but the basis those figures are on is stated plainly, in the domain's own sentence",
    )
  }

  @Test
  fun `the cross-school table withholds the same figures, and points at the table that has the totals`() {
    // The summary has no arrangement totals in it, so its blank cannot say "the
    // totals above": the pointer has to be true where it is printed.
    val profile =
      costProfile(
        listOf(ucSanDiego(TuitionApplicable.OUT_OF_STATE), campusSchool()),
        answeredMoney(band = IncomeBand.OVER_110K, state = "WA"),
      )

    val summary = summaryOf(render(profile))

    assertFalse(summary.contains("\$38,701"), "the in-state published price must not reach the comparison either")
    assertTrue(
      summary.contains("the totals in this school's own table below"),
      "the summary sends the parent to the table that does hold their totals: [$summary]",
    )
    assertTrue(summary.contains("\$52,000"), "the other school's own published price is unaffected")
  }

  @Test
  fun `the blended-figure hint is said once for the whole page, with the residency it is on`() {
    val body = render(costProfile(listOf(stateSchool(), campusSchool()), answeredMoney()))

    // ONE hint, above the school sections: the warning is about the published
    // price generally (RFC 149 D-F.1 requires it be said, not said N times), and
    // the summary table is where the page already says it.
    assertTrue(
      body.contains("$SUMMARY_HINT $BLENDED_IN_STATE_CLAUSE"),
      "the summary hint must carry the residency clause",
    )
    assertFalse(
      body.contains(SCHOOL_BLENDED_HINT),
      "a school section on a page that already said it must not say it again",
    )
    assertEquals(1, countOf(body, BLENDED_IN_STATE_CLAUSE), "the in-state clause is said exactly once")
  }

  @Test
  fun `a one-school report still carries the blended-figure hint, in its own words`() {
    // There is no summary table to say it above, so the school section is the
    // only place it can be said -- and it must still be said.
    val body = render(costProfile(listOf(stateSchool()), answeredMoney()))

    assertTrue(
      body.contains("$SCHOOL_BLENDED_HINT $BLENDED_IN_STATE_CLAUSE"),
      "the only table on the page must carry the hint: [$body]",
    )
    assertEquals(1, countOf(body, BLENDED_IN_STATE_CLAUSE), "and exactly once")
  }

  @Test
  fun `the in-state clause is absent from a report with no public school on it`() {
    // It is a sentence ABOUT public schools. Under two private colleges it was
    // simply false of every table it stood under.
    val private = render(costProfile(listOf(privateSchool(), campusSchool()), answeredMoney()))
    // Asserted against the RENDERER's own constant, never a hand-typed copy: an
    // absence asserted against a copy passes vacuously the moment the sentence
    // is reworded, which is exactly how this defect would come back green.
    assertFalse(private.contains(BLENDED_IN_STATE_CLAUSE), "no school here prices by residency: [$private]")
    assertTrue(private.contains(SUMMARY_HINT), "the blended-average warning itself still stands")

    // And it IS said where a public school is on the page, mixed or alone.
    val mixed = render(costProfile(listOf(privateSchool(), stateSchool()), answeredMoney()))
    assertEquals(1, countOf(mixed, BLENDED_IN_STATE_CLAUSE), "one public school on the page is one clause")
  }

  @Test
  fun `the residency sentence is said once on a one-school report, and once per school on three`() {
    val one = render(costProfile(listOf(stateSchool()), answeredMoney()))
    val statement =
      "Riverside State University is a public school, and the tuition and fees figure that applies to this " +
        "family is its in-state one."

    // The single-school assumption block IS this school's basis and already
    // states it; the section under it used to state the same sentence again.
    assertEquals(1, countOf(one, statement), "a one-school report states its residency once: [$one]")
    assertFalse(one.contains("class=\"report-school-residency\""), "the duplicate line is the one that goes")

    // With two or more schools the per-school line is the ONLY place a SPECIFIC
    // school's basis is stated, so it stays.
    val three = render(costProfile(listOf(stateSchool(), campusSchool(), privateSchool()), answeredMoney()))
    assertEquals(3, countOf(three, "class=\"report-school-residency\""), "every school states its own basis")
    assertTrue(three.contains(statement), "including the public one")
  }

  @Test
  fun `a figure that is the same in every column is said once, and one that varies is not`() {
    // DERIVED, never a list of roles: the row collapses because the figures it
    // would print are equal. Tuition and books are school-wide facts in the
    // DOMAIN today, and that is their property, not this renderer's rule.
    val body = render(costProfile(listOf(stateSchool()), answeredMoney()))
    val table = body.substringAfter("class=\"report-school\"")

    val tuitionRow = table.substringAfter("class=\"report-row-tuition\"").substringBefore("</tr>")
    assertEquals(1, countOf(tuitionRow, "\$12,000"), "one tuition figure for the whole school: [$tuitionRow]")
    assertTrue(tuitionRow.contains("colspan=\"3\""), "and it spans the ways of living: [$tuitionRow]")

    val books = table.substringAfter("Books and supplies").substringBefore("</tr>")
    assertEquals(1, countOf(books, "\$1,200"), "books and supplies is school-wide too: [$books]")
    assertTrue(books.contains("colspan=\"3\""), "and it spans the ways of living too: [$books]")

    // Housing and food really does vary by way of living, so it is still a cell
    // per column -- three figures, not one collapsed number.
    val housing = table.substringAfter("Housing and food").substringBefore("</tr>")
    assertTrue(housing.contains("\$11,000"), "the on-campus figure: [$housing]")
    assertTrue(housing.contains("\$0"), "and our own at-home zero: [$housing]")
    assertFalse(housing.contains("colspan"), "a row whose figures differ may never be collapsed: [$housing]")
  }

  @Test
  fun `the income band the page states is not repeated at every net-price figure`() {
    val band = IncomeBand.K48_TO_75K
    val body =
      render(costProfile(listOf(stateSchool(), campusSchool(), privateSchool()), answeredMoney(band)))

    // Said once, in the family's own money line above every figure on the page.
    assertTrue(
      body.contains(MoneyBasis.AnsweredBand(band).statement),
      "the band must still be stated in dollars, in the domain's own sentence: [$body]",
    )
    assertEquals(
      1,
      countOf(body, band.bracket),
      "the band is on the page exactly once, not beside all six net-price figures",
    )
    assertTrue(body.contains("\$15,000"), "and every net price is still shown")
    assertTrue(body.contains("\$22,000"), "and every net price is still shown")
    assertTrue(body.contains("\$25,000"), "and every net price is still shown")
  }

  @Test
  fun `the overall-average note still stands beside every figure it corrects`() {
    // The two notes are not the same kind of sentence. A BandSpecific note
    // CONFIRMS a figure that is genuinely for this family's band, so it may be
    // said once above the page; the overall-average note CORRECTS a reader who
    // would otherwise take a figure that is NOT theirs for theirs, and a
    // correction has to be at the number to do its work (RFC 188, brief 0009 D5).
    val overall =
      costFixture(
        name = "Ashford College",
        tuitionInState = 41000,
        publishedPrice = 60000,
        netPrice = NetPrice.OverallAverage(24000),
      )
    val body = render(costProfile(listOf(stateSchool(), overall), answeredMoney()))
    val note = "an overall average across all families, not a figure for this family"

    // Twice: the summary row and the school's own table -- beside BOTH of the
    // figures it corrects, exactly as before this slice.
    assertEquals(2, countOf(body, note), "the correcting note is printed at every figure it corrects: [$body]")
    // While the band note beside the other school's figures is gone.
    assertEquals(1, countOf(body, IncomeBand.K48_TO_75K.bracket), "the confirming note is said once, above")
  }

  @Test
  fun `a three-school report's word count is measured and printed, never asserted`() {
    // Brief 0009 D20 deleted the ceiling this test used to assert (RFC 203).
    // `WORD_CEILING` moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404, the last
    // step UPWARD: every slice ratcheted it to whatever it had just measured, so
    // a threshold edited by the change it constrains asserted nothing and taxed
    // the next honest sentence. The MEASUREMENT is worth keeping and the GATE is
    // not, so this prints the number and lets a person judge growth at the /ship
    // gate, where the value the words buy can actually be weighed.
    //
    // Read it out of the captured standard output of this test:
    //   grep -h MEASURE public-web/build/test-results/test/*.xml
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))

    val words = visibleWordsOf(body)
    println(
      "[MEASURE] three-school cost report: [$words] visible words " +
        "(RFC 203 deleted the ceiling; it had moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404)",
    )
  }

  // ---------------------------------------------------------------------------
  // Whose gap a blank is (RFC 166 §6): the page may not blame the school for it
  // ---------------------------------------------------------------------------

  /**
   * A school that publishes EVERY part of one way of living but one, so the page
   * has exactly one blank cell on it and an assertion about the blank cannot
   * pass on some other row's words.
   */
  private fun oneBlankSchool(
    absenceStatuses: Map<CostField, AbsenceStatus> = emptyMap(),
    heldOnlyInOtherYear: Map<CostField, Int> = emptyMap(),
    booksAndSupplies: Int? = null,
    medianDebt: Int? = 21000,
  ): CollegeCost =
    costFixture(
      name = "Fulton College",
      tuitionInState = 30000,
      publishedPrice = 52000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
      housingAndFoodOnCampus = 10000,
      booksAndSupplies = booksAndSupplies,
      otherExpensesOnCampus = 2000,
      medianDebt = medianDebt,
      offersOnCampusHousing = true,
      absenceStatuses = absenceStatuses,
      heldOnlyInOtherYear = heldOnlyInOtherYear,
    )

  @Test
  fun `a figure the publisher suppressed is spoken as the publisher's, never as this school's silence`() {
    // The commonest absence in the Scorecard, and the sentence a parent is owed:
    // saying "not reported by this school" about a figure the school DID file,
    // and the publisher withheld for privacy, is a false statement about a named
    // school printed for a family.
    val body =
      render(
        costProfile(
          listOf(
            oneBlankSchool(absenceStatuses = mapOf(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER)),
          ),
          answeredMoney(),
        ),
      )

    assertTrue(
      body.contains(
        requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.SCORECARD)),
      ),
      "the blank says whose gap it is, in the domain's own words, and NAMES the publisher (RFC 177): [$body]",
    )
    assertFalse(
      body.contains(NOT_REPORTED),
      "and the school is not blamed for it anywhere on the page",
    )
  }

  @Test
  fun `a gap of OURS is spoken as ours, never as this school's silence`() {
    val body =
      render(
        costProfile(
          listOf(oneBlankSchool(absenceStatuses = mapOf(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to AbsenceStatus.NOT_COLLECTED_BY_US))),
          answeredMoney(),
        ),
      )

    assertTrue(
      body.contains(requireNotNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_COLLECTED_BY_US))),
      "our own gap is ours to own: [$body]",
    )
    assertFalse(body.contains(NOT_REPORTED), "the school never carries our gap")
  }

  @Test
  fun `a figure held only in another academic year names both years, and is never the school's silence`() {
    // The case tier-0 fix 6 created, on the surface that is LIVE for every
    // already-shared link: the school published this figure, we hold it only for
    // another year, and no total may mix years. The chat tool obeyed that rule
    // from the day it landed; this page printed "Not reported by this school".
    val body =
      render(
        costProfile(
          listOf(oneBlankSchool(heldOnlyInOtherYear = mapOf(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to 1200))),
          answeredMoney(),
        ),
      )

    assertTrue(
      body.contains(FigureStatusCopy.yearGapStatementOf(FIXTURE_PRICE_ACADEMIC_YEAR, FIXTURE_YEAR_GAP_ACADEMIC_YEAR)),
      "the blank names the year shown and the year held, in the domain's own sentence: [$body]",
    )
    assertFalse(body.contains(NOT_REPORTED), "a figure the school published is not its silence")
    assertFalse(body.contains("\$1,200"), "and the other year's number is never quoted into this year's table")
  }

  @Test
  fun `a school that really reported nothing still reads as the school's own silence`() {
    // The other half of the rule: [NOT_REPORTED] is not retired,
    // it is confined to the one status it is true of. A blank that stopped
    // saying anything about the school would be the opposite defect.
    val body = render(costProfile(listOf(oneBlankSchool()), answeredMoney()))

    assertTrue(body.contains(NOT_REPORTED), "a genuinely unreported figure is still labelled as one")
  }

  // ---------------------------------------------------------------------------
  // Borrowing at graduation (RFC 175, D9): two publishers, two vintages, one
  // section -- and the rules that keeps honest.
  // ---------------------------------------------------------------------------

  @Test
  fun `the report says the federal debt figure undated and the school's own borrowing with its year`() {
    val body =
      render(
        costProfile(
          listOf(
            costFixtureWithBorrowing(),
          ),
          answeredMoney(),
        ),
      )

    // The Scorecard half is unchanged: federal loans, and no year, because no
    // source we hold dates it.
    assertTrue(body.contains("carried a median of \$21,000 in federal loans"), body)
    assertTrue(body.contains("The source publishes no year for this figure."), body)
    // The CDS half names the school as the claimant and carries its filing
    // year -- these are self-reported answers, not administrative records --
    // in the BLOCK HEADER, once, above the figures it covers (RFC 190).
    // ... and since RFC 196 the claimant is a LINK to that school's own file,
    // so the sentence is an anchor followed by the rest of itself.
    assertTrue(
      body.contains(
        "rel=\"noreferrer\">Borrowing College's 2024-25 Common Data Set</a>" +
          " reports these figures for the students who",
      ),
      body,
    )
    assertTrue(body.contains("Graduates who borrowed federal loans owed"), body)
    // The two are never summed and never differenced. Neither the total nor
    // the difference of the two debt figures appears anywhere on the page.
    listOf("41,747", "64,865", "64,612", "22,865", "\$253").forEach {
      assertFalse(body.contains(it), "the two debt figures were combined: [$it]")
    }
    assertTrue(
      body.contains("never added together and never compared with each other"),
      "the sources section says the two may not be combined: [$body]",
    )
  }

  @Test
  fun `a school that filed no private figure is said to have filed none`() {
    val body =
      render(
        costProfile(
          listOf(
            costFixtureWithBorrowing(
              borrowing =
                borrowingFixture(
                  "Borrowing College",
                  averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
                  borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
                ),
            ),
          ),
          answeredMoney(),
        ),
      )

    assertTrue(body.contains("reports no average for private loans"), body)
    // The page says the CLAIM D9 mandates and stops there: the words are
    // BorrowingWire's, so the page and the coach cannot describe this silence
    // differently, and the page adds no advice of its own.
    assertTrue(
      body.contains(BorrowingWire.privateNotFiledLabel()),
      "the absence sentence is the wire's, said whole: [$body]",
    )
    assertFalse(body.contains("\$43,865"), "no private figure is invented for a school that filed none")
  }

  @Test
  fun `a private cell we could not read is said as OUR gap, beside the federal figure we did read`() {
    val body =
      render(
        costProfile(
          listOf(
            costFixtureWithBorrowing(
              borrowing =
                borrowingFixture(
                  "Borrowing College",
                  averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
                  borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
                  notReadLoanTypes = setOf(LoanType.PRIVATE),
                ),
            ),
          ),
          answeredMoney(),
        ),
      )

    // The half we read is rendered.
    assertTrue(body.contains("\$20,747"), "the federal average we did read must still render: [$body]")
    // The half we did not is OUR gap, in the wire's own words.
    assertTrue(
      body.contains(BorrowingWire.notReadByUsLabel(LoanType.PRIVATE)),
      "the unread private cell is said as ours, said whole: [$body]",
    )
    // And never as the school having filed nothing: that claim is false here.
    assertFalse(
      body.contains("reports no average for private loans"),
      "our own unread cell was spoken as the school's silence (RFC 175 D7): [$body]",
    )
  }

  @Test
  fun `every borrowing figure on the page renders under the block's one attribution`() {
    // The FEDERAL-ONLY school, so the private-absence sentence is on the page
    // as well: it is the one borrowing sentence that never says "graduated",
    // and a sweep keyed on that word would have exempted it silently.
    val body =
      render(
        costProfile(
          listOf(
            costFixtureWithBorrowing(
              borrowing =
                borrowingFixture(
                  "Borrowing College",
                  averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
                  borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
                ),
            ),
          ),
          answeredMoney(),
        ),
      )

    // RFC 190, superseding RFC 175 D10's per-sentence rule and keeping its
    // reason: these are self-reported survey answers with no audit, so a
    // borrowing figure must never read as an administrative record. The
    // attribution now rides on the BLOCK -- one filing, one school, one cycle
    // -- and the page is read without a coach beside it, so the header has to
    // be on the page, above every figure it covers. Swept by the markers of
    // the sentences BorrowingWire can emit, never by a word one of them
    // happens to contain -- the next sentence must join the sweep by being
    // written, not by being remembered.
    // The markers are pinned to the wire's own sentences FIRST: a marker that
    // no longer appears in the sentence it stands for is one the sweep would
    // skip in silence, which is the failure this test exists to prevent.
    // The PAGE's own school, because these same strings are now the sweep's
    // subject below: the block the wire builds for this filing is exactly the
    // block the page was given to render.
    val coverage =
      borrowingFixture(
        "Borrowing College",
        averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
        borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
      )
    val block = BorrowingWire.blockOf(coverage)
    val wireSentences = listOf(block.header) + block.sentences
    // The header's own containment is asserted in its LINKED form below (RFC
    // 196): the page renders the claimant inside an anchor, so the flat string
    // is no longer a substring of the markup even though it is still the
    // sentence being said.
    assertEquals(
      emptyList(),
      borrowingMarkers.filterNot { marker -> wireSentences.any { it.contains(marker) } },
      "a marker no longer matches the BorrowingWire sentence it stands for",
    )

    // Swept over the WIRE's own sentences -- the unescaped strings the page is
    // given -- and not over text scraped back out of the markup. A hand-rolled
    // `<[^>]+>` tag-strip is not an HTML parser and decodes no entity, so the
    // first school name carrying `&` ("Texas A&M" renders as "Texas A&amp;M")
    // would fail this guard for a reason that has nothing to do with
    // attribution. The marker pin above already proves this list is the whole
    // list.
    val spoken = block.sentences.filter { body.contains(it) }
    assertEquals(
      block.sentences,
      spoken,
      "every borrowing sentence this school's filing supports must render: [$body]",
    )
    // THE BLOCK CANNOT RENDER WITHOUT ITS HEADER, and the CDS voice survives in
    // it: the school, its cycle and its document, above every figure below.
    assertTrue(
      block.header.contains("Borrowing College's 2024-25 Common Data Set"),
      "the header is the one attribution, so it must name the school, the cycle and the document: [${block.header}]",
    )
    assertTrue(block.sentences.isNotEmpty(), "a block with no sentences would make this sweep vacuous")
    // The header as the PAGE says it since RFC 196: the claimant is an anchor
    // to the school's own file, the rest of the sentence follows it.
    val renderedHeader = linkedCitation(block.citation) + BorrowingWire.HEADER_TAIL
    assertTrue(body.contains(renderedHeader), "the block header must render, linked: [$body]")
    val headerAt = body.indexOf(renderedHeader)
    assertEquals(
      emptyList(),
      block.sentences.filter { body.indexOf(it) < headerAt },
      "a borrowing figure rendered ABOVE the attribution it is claimed under: [$body]",
    )
    // And nothing below the header re-cites the document: the section is
    // attributed once, which is the whole of what this slice changes.
    assertEquals(
      emptyList(),
      block.sentences.filter { it.contains("Common Data Set") },
      "a figure under the header carried an attribution of its own (RFC 190)",
    )
  }

  @Test
  fun `the federal debt names its own publisher while the borrowing block names the school's own filing`() {
    // The mixed-source section (RFC 175 D9, RFC 190): a federal median debt
    // from the Scorecard's own file, and a Common Data Set block from the
    // school itself. The block header scopes ONLY the second, so the federal
    // figure names its own publisher -- read off the cell it came from, never
    // typed on this page.
    val college = costFixtureWithBorrowing()
    val body = render(costProfile(listOf(college), answeredMoney()))

    val note =
      requireNotNull(college.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)) {
        "the fixture's median debt must carry the cell it came from, or this test asserts nothing"
      }
    val federalPublisher = MoneySourceCopy.openingLabelOf(note.cell.source)
    assertEquals(MoneySource.SCORECARD, note.cell.source, "the federal median debt is the Scorecard's own file")
    assertTrue(
      body.contains("$federalPublisher reports that students who finished here carried a median of \$21,000"),
      "the federal figure names the publisher its own cell came from: [$body]",
    )
    // The school's own filing is named for the borrowing half, and only for it.
    val borrowingBlock = BorrowingWire.blockOf(borrowingFixture("Borrowing College"))
    val header = linkedCitation(borrowingBlock.citation) + BorrowingWire.HEADER_TAIL
    assertTrue(body.contains(header), "the borrowing block is attributed to the school's own filing: [$body]")
    assertTrue(header.contains("Borrowing College's 2024-25 Common Data Set"), header)
    // NEITHER STANDS FOR THE OTHER: the federal publisher is not named inside
    // the borrowing block's header, and the CDS is not named in the federal
    // sentence.
    assertFalse(header.contains(federalPublisher), "the CDS header borrowed the federal publisher's name: [$header]")
    assertTrue(
      body.indexOf(federalPublisher) < body.indexOf(header),
      "the two publishers are two attributions in one section, the federal one first: [$body]",
    )
    // RFC 175 D9 stands: the two figures are never summed and never differenced.
    listOf("41,747", "64,865", "64,612", "22,865", "\$253").forEach {
      assertFalse(body.contains(it), "the two debt figures were combined: [$it]")
    }
  }

  @Test
  fun `the page and the coach speak the loan types in ONE order, federal first`() {
    val borrowing =
      borrowingFixture(
        "Borrowing College",
        averageDebtUsdByLoanType = mapOf(LoanType.ANY to 27202, LoanType.FEDERAL to 20747),
        borrowersByLoanType = emptyMap(),
      )
    val body = render(costProfile(listOf(costFixtureWithBorrowing(borrowing = borrowing)), answeredMoney()))

    // Every type is spoken, and no type is spoken twice: the order is DERIVED
    // from the vocabulary, so a sixth loan type joins both surfaces by being
    // declared rather than by each list remembering it.
    assertEquals(LoanType.entries.toSet(), LoanType.SPOKEN_ORDER.toSet())
    assertEquals(LoanType.entries.size, LoanType.SPOKEN_ORDER.size)
    assertEquals(listOf(LoanType.FEDERAL, LoanType.PRIVATE), LoanType.SPOKEN_ORDER.take(2))

    // And the page renders exactly the wire's list, in the wire's order: the
    // two surfaces used to hold two hand-written lists that disagreed, so the
    // same filing was narrated federal-first on the page and any-loan-first in
    // the payload.
    val sentences = BorrowingWire.blockOf(borrowing).sentences
    assertEquals(
      sentences,
      sentences.sortedBy { body.indexOf(it) },
      "the page renders the wire's sentences out of the wire's order: [$body]",
    )
    assertTrue(
      body.indexOf("borrowed federal loans") < body.indexOf("borrowed a loan of any kind"),
      "the federal figure is said first, because the point of the section is that it is half the picture",
    )
  }

  @Test
  fun `a filing whose only borrowing cells contradict each other renders no borrowing section`() {
    // The state built by [BorrowingCoverage.of] itself, not a hand-made
    // Reported: this is the live D6 shape (ipeds 166629 -- 39 graduates, 2588
    // federal borrowers), and the page must be given exactly what the read
    // produces from it.
    val coverage =
      BorrowingCoverage.of(
        "Borrowing College",
        CollegeBorrowing(
          collegeId = CollegeId(UUID.randomUUID()),
          academicYear = AcademicYear(2024),
          sourceUrl = "https://example.test/cds.pdf",
          archiveUrl = null,
          // STATED, not defaulted (RFC 200): this filing's file was served when
          // we looked, which is the case every assertion below was written for.
          sourceUrlStatus = SourceUrlStatus.FOUND,
          graduatingClass = 39,
          averageDebtUsdByLoanType = emptyMap(),
          borrowersByLoanType = mapOf(LoanType.FEDERAL to 2588),
          gapStatusByLoanType = emptyMap(),
        ),
      )
    val block = assertIs<BorrowingCoverage.NoBlock>(coverage)
    assertEquals(setOf(LoanType.FEDERAL), block.contradicted)

    val body = render(costProfile(listOf(costFixtureWithBorrowing(borrowing = coverage)), answeredMoney()))

    // Neither cell of the pair is spoken, and no share is derived from it.
    listOf("2,588", "2588", "39 students", "6,636%").forEach {
      assertFalse(body.contains(it), "a contradicted borrowing figure reached the page: [$it] in [$body]")
    }
    // And the section is absent whole: the page explains no coverage of ours,
    // so it says nothing here rather than saying it in the school's voice.
    assertEquals(
      emptyList(),
      borrowingMarkers.filter { body.contains(it) },
      "a borrowing sentence was rendered for a filing with nothing speakable in it: [$body]",
    )
  }

  /**
   * One marker per string [BorrowingWire] can put on the report page, in render
   * order: the block header, an average, a share, and the private-absence
   * claim.
   *
   * The citation is NOT a marker of its own any more (RFC 190): the block's
   * trailing "Source: ..." line is gone, and the document it named is in the
   * header marker above -- so a "Source:" marker here would stand for the merit
   * block's line instead and exempt itself from this sweep.
   *
   * A LIST of markers rather than a substring guess: the sweep above keyed on
   * the word "graduated" once, which exempted the private-absence sentence
   * ("its graduates") without anyone noticing. The test that consumes this
   * pins every marker to the wire sentence it stands for, so a change to the
   * copy fails here rather than quietly narrowing the sweep.
   */
  private val borrowingMarkers =
    listOf(
      "reports these figures for the students who graduated",
      "on average by the time they graduated",
      "borrowed federal loans",
      "reports no average for private loans",
    )

  /**
   * The one-blank school with a borrowing block, so the debt section carries
   * BOTH publishers' figures and an assertion about one cannot pass on the
   * other's words.
   */
  private fun costFixtureWithBorrowing(borrowing: BorrowingCoverage = borrowingFixture("Borrowing College")): CollegeCost =
    costFixture(
      name = "Borrowing College",
      tuitionInState = 30000,
      publishedPrice = 52000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
      housingAndFoodOnCampus = 10000,
      booksAndSupplies = 1000,
      otherExpensesOnCampus = 2000,
      medianDebt = 21000,
      offersOnCampusHousing = true,
      borrowing = borrowing,
    )

  @Test
  fun `a median debt the publisher suppressed is not read as a school that does not report it`() {
    // The debt paragraph is prose rather than a cell, and it made the same claim
    // the cells did. `median_debt_at_completion` is a COHORT figure, so it is
    // also the case a status read through the price-only door could not see.
    val body =
      render(
        costProfile(
          listOf(
            oneBlankSchool(
              booksAndSupplies = 1000,
              medianDebt = null,
              absenceStatuses = mapOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
            ),
          ),
          answeredMoney(),
        ),
      )

    assertTrue(
      body.contains(
        requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.SCORECARD)),
      ),
      "the privacy suppression is stated, and the publisher that withheld it is named (RFC 177): [$body]",
    )
    assertFalse(
      body.contains("This school does not report the federal loan debt"),
      "and the school is not said to have withheld what the publisher withheld",
    )
  }

  @Test
  fun `the tuition tiers this school publishes are stated, and only where it publishes one`() {
    // RFC 166 §4's sentence reached the coach and never reached the parent, who
    // reads this page alone. It is printed off [CollegeCost.publishedTuitionTiers]
    // -- the ONE derivation of which tiers exist -- so a school that publishes no
    // tuition at all is never told to publish "one tuition price".
    val stateBody = render(costProfile(listOf(stateSchool()), answeredMoney()))
    assertTrue(
      stateBody.contains(ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.statement),
      "a school with an in-state and an out-of-state price says what it does not separate: [$stateBody]",
    )

    val noTuition = costFixture(name = "Silent Tuition College", housingAndFoodOnCampus = 8000)
    val silentBody = render(costProfile(listOf(noTuition), answeredMoney()))
    assertFalse(
      silentBody.contains(ResidencyTierBasis.SINGLE_PUBLISHED_PRICE.statement),
      "a school that publishes no tuition price must not be said to publish one: [$silentBody]",
    )
    // The page prints the DOMAIN's basis and no longer counts the tiers itself,
    // so the school we hold no price for is `no_published_tuition` and gets no
    // tier paragraph at all -- its tuition row's own blank already says why.
    assertFalse(
      silentBody.contains("report-school-tuition-tiers"),
      "no tier line is printed where there is no tier to name: [$silentBody]",
    )
    assertFalse(
      ResidencyTierBasis.entries.any { silentBody.contains(it.statement) },
      "and no basis sentence at all reaches the page: [$silentBody]",
    )
  }

  @Test
  fun `a school with one tuition price is not told the price applies to every student`() {
    // The narrowed [ResidencyTierBasis.SINGLE_PUBLISHED_PRICE]: at a public
    // school whose out-of-state price we do not hold, the ONE tier is an
    // in-state figure, and `tuition_applicable` beside it says so.
    val oneTier =
      costFixture(
        name = "One Price College",
        control = CollegeControl.Public(TuitionApplicable.IN_STATE),
        tuitionInState = 9000,
      )
    val body = render(costProfile(listOf(oneTier), answeredMoney()))

    assertTrue(
      body.contains(ResidencyTierBasis.SINGLE_PUBLISHED_PRICE.statement),
      "the one price we hold is still stated: [$body]",
    )
    assertFalse(
      body.contains("whoever the student is"),
      "but never as a price that applies to every student: [$body]",
    )
  }

  // ---------------------------------------------------------------------------
  // What KIND of number, said once for the set it covers (RFC 191, brief 0009 D3)
  // ---------------------------------------------------------------------------

  @Test
  fun `the tier sentence is said once for the figures it covers, not once per school`() {
    // The whole slice, measured: three schools, three federal debt figures, ONE
    // sentence saying what kind of number they are. It was printed three times.
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))
    val statement = AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)

    assertEquals(1, countOf(body, statement), "one sentence for the set of figures it covers: [$body]")
    assertTrue(body.contains("report-kinds"), "and it is in the standing block, above the sources")
    assertTrue(body.contains("The median federal loan debt:"), "which names the figures it covers: [$body]")
    // Byte-identical to the domain's own copy, never re-worded on the way out
    // (RFC 179, and the v19 prompt pin over the same sentence).
    assertTrue(body.contains(statement), "and it is [AssuranceTierCopy]'s sentence, byte for byte")
    // The debt paragraph keeps its figure and its publisher and loses only the
    // repeated tier sentence (RFC 190's attribution is untouched).
    assertTrue(body.contains("reports that students who finished here carried"), "the figure still says whose it is")
  }

  @Test
  fun `every figure the page speaks a kind for is covered by exactly one sentence`() {
    // Walked, not restated: the covered set is derived from the colleges'
    // own figure statuses, so a figure that escaped the block fails here.
    val colleges = listOf(verboseState(), verboseCampus(), verbosePrivate())
    val body = render(costProfile(colleges, answeredMoney()))
    val block = kindsBlockOf(body)

    colleges.forEach { cost ->
      val debt = requireNotNull(cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD))
      // The EXPECTED scope, built per college and compared as a string: a
      // predicate that does not mention the college asserts the same thing three
      // times and cannot see a block that names the wrong subset of schools.
      // Every school here is on one tier, so the label is bare -- and that IS
      // the claim: one sentence, covering all three.
      val expected = "The median federal loan debt:"
      assertTrue(block.contains(expected), "[${cost.name}]'s debt figure must be named: [$block]")
      assertEquals(
        1,
        countOf(block, AssuranceTierCopy.statementOf(debt.assurance, FilingLink.LINKED)),
        "and covered by exactly one sentence, the one that is true of it: [$block]",
      )
      AssuranceTier.entries.filter { it != debt.assurance }.forEach { other ->
        assertFalse(block.contains(AssuranceTierCopy.statementOf(other, FilingLink.LINKED)), "and by no other: [${other.value}]")
      }
    }
    // The other two sentences are not said at all: no figure on this page is on
    // either tier, and a sentence said over nothing describes nothing.
    assertEquals(0, countOf(body, AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)))
    assertEquals(0, countOf(body, AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED)))
  }

  @Test
  fun `our own at-home assumption is never swept under a publisher's tier`() {
    // RFC 166 section 7 and RFC 189's review in one test: the `$0` at-home
    // housing line is OURS. It carries no published cell, so it cannot enter the
    // covered set -- and the block must not name housing and food at all.
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))
    val block = kindsBlockOf(body)

    assertTrue(body.contains(AT_HOME_ASSUMPTION_STATEMENT), "the assumption is still stated as ours")
    assertFalse(block.contains(AT_HOME_ASSUMPTION_STATEMENT), "but never inside a publisher's tier block: [$block]")
    // FUTURE-PROOFING, said plainly: only the debt field can enter the covered
    // set today, so no housing string could appear whatever the grouping did.
    // The assertion is the regression guard for the day the covered set widens.
    assertFalse(block.contains("housing and food"), "and no tier sentence claims the line we authored: [$block]")
    assertFalse(block.contains("our assumption"), "the block speaks only for published figures: [$block]")
  }

  @Test
  fun `a figure with no number is in no tier block, and keeps its own words`() {
    // The exclusion RFC 189 wrote into the tool description, on this surface: a
    // withheld figure is not a KIND of number, so it is covered by nothing --
    // and it still says, in its own paragraph, who withheld it and what kind of
    // number it would have been.
    val suppressed =
      oneBlankSchool(
        booksAndSupplies = 1000,
        medianDebt = null,
        absenceStatuses = mapOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
      )
    val body = render(costProfile(listOf(suppressed), answeredMoney()))

    assertFalse(body.contains("report-kinds"), "no shown prose figure is no block at all, not an empty heading: [$body]")
    assertTrue(
      body.contains(requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.SCORECARD))),
      "the suppression is still stated",
    )
    assertTrue(
      body.contains(AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)),
      "and the tier the withheld figure would have had is still reachable (brief 0009 D1): [$body]",
    )
  }

  @Test
  fun `the tier block ranks nothing and counts nothing`() {
    // RFC 179 D10. The block knows how many figures are on each tier and may
    // never say so, and it may never order the three as better or worse.
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))
    val block = kindsBlockOf(body)
    // EVERY scope, not the first: a second group's header is exactly where a
    // count would be added ("2 figures on this tier") and never noticed.
    val scopes = block.split("report-kind-scope\">").drop(1).map { it.substringBefore("</span>") }

    assertTrue(scopes.isNotEmpty(), "the block must have at least one scope to check: [$block]")
    scopes.forEach { scope -> assertFalse(scope.any { it.isDigit() }, "no count in a scope: [$scope]") }
    // Scoped to the BLOCK, not the whole page: "College Scorecard" contains
    // "score", and a page-wide match would be asserting on a publisher's name.
    listOf("strongest", "weakest", "most reliable", "rank", "score", "rating", "tier 1").forEach {
      assertFalse(block.lowercase().contains(it), "no ranking of the three tiers reaches the block: [$it] in [$block]")
    }
  }

  /**
   * The tier block's own markup, with the marker ASSERTED.
   *
   * `substringAfter` returns the WHOLE document on a miss, so a renamed section
   * would quietly turn every assertion below into an assertion about the whole
   * page -- the same hazard [visibleWordsOf] guards, guarded the same way.
   */
  private fun kindsBlockOf(body: String): String {
    assertTrue(body.contains("report-kinds"), "the tier block must be on the page to be read")
    assertTrue(body.contains("report-sources"), "and the sources section must follow it")
    return body.substringAfter("report-kinds").substringBefore("report-sources")
  }

  @Test
  fun `two schools on two tiers get two headers, each naming only its own school`() {
    // The failure this whole block risks: ONE header over two figures of
    // different kinds tells a family that a survey answer is a federal record,
    // or the reverse. The fixture forces the pairing on purpose -- no publisher
    // we load puts a median debt figure on a survey tier -- because the
    // renderer, not only the grouping, has to survive the case.
    val federal = costFixture(name = "Lakeshore State University", tuitionInState = 12000, medianDebt = 21000)
    val surveyed =
      costFixture(
        name = "Wynnford College",
        tuitionInState = 41000,
        medianDebt = 27000,
        debtPublisher = PublishedCell.Survey.SFA,
      )
    val body = render(costProfile(listOf(federal, surveyed), answeredMoney()))
    val block = kindsBlockOf(body)

    assertEquals(2, countOf(block, "report-kind-scope"), "two kinds of number are two headers: [$block]")
    assertTrue(
      block.contains("The median federal loan debt at Lakeshore State University:"),
      "the federal figure names its own school: [$block]",
    )
    assertTrue(
      block.contains("The median federal loan debt at Wynnford College:"),
      "and so does the surveyed one: [$block]",
    )
    // Each sentence said once, and neither header reaches the other's figure.
    assertEquals(1, countOf(body, AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD, FilingLink.LINKED)))
    assertEquals(1, countOf(body, AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)))
    val federalHeader = block.substringBefore("The median federal loan debt at Wynnford College:")
    assertFalse(
      federalHeader.contains(AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)),
      "the survey sentence may not sit under the federal school's header: [$federalHeader]",
    )
  }

  @Test
  fun `a bare label is refused where another school states a different tier for the same figure`() {
    // The over-reach a covered-set-only rule cannot see (RFC 191 D4): one
    // school's debt figure is SHOWN, the other's is withheld and keeps its own
    // sentence in its own paragraph -- on a different tier. An unqualified
    // header over the shown one would claim the withheld one too.
    val shown = costFixture(name = "Lakeshore State University", tuitionInState = 12000, medianDebt = 21000)
    val withheld =
      costFixture(
        name = "Wynnford College",
        tuitionInState = 41000,
        medianDebt = null,
        debtPublisher = PublishedCell.Survey.SFA,
        absenceStatuses = mapOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD to AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
      )
    val body = render(costProfile(listOf(shown, withheld), answeredMoney()))
    val block = kindsBlockOf(body)

    assertTrue(
      block.contains("The median federal loan debt at Lakeshore State University:"),
      "the header names the school whose figure it describes: [$block]",
    )
    assertFalse(
      block.contains("Wynnford College"),
      "and never the school whose figure it does not cover: [$block]",
    )
    // The withheld figure keeps both of its own sentences, outside the block.
    assertTrue(
      body.contains(requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER, MoneySource.IPEDS_SFA))),
      "the suppression is still stated: [$body]",
    )
    assertTrue(
      body.contains(AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)),
      "and the tier it would have had is still reachable: [$body]",
    )
  }

  // ---------------------------------------------------------------------------
  // The link we promised (RFC 196, brief 0009 D15)
  // ---------------------------------------------------------------------------

  /**
   * THE ACCEPTANCE CRITERION, walked rather than listed.
   *
   * `AssuranceTierCopy`'s voluntary-self-report sentence says, in our own
   * words, "We link to the college's own file so you can see it", and that
   * sentence is byte-frozen against the immutable v19 prompt row. Before RFC 196
   * this page rendered ZERO anchors of any kind and still stated two
   * self-reported Common Data Set attributions per school.
   *
   * The sweep STRIPS EVERY ANCHOR from the rendered page and then asserts that
   * no citation shape survives. It walks the surface instead of naming merit and
   * borrowing, so a third Common Data Set block added later joins this test by
   * being rendered, not by being remembered -- the failure mode a list of known
   * call sites has.
   *
   * The shape, not the words: the standing sentence in the sources section says
   * "each school's own Common Data Set" about no particular filing and is not a
   * citation. A citation names a school and a cycle, which is what the regex
   * matches.
   */
  @Test
  fun `no Common Data Set filing is cited anywhere on this page outside a link`() {
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))

    // "<College>'s 2024-25 Common Data Set" -- the citation shape, whichever
    // block states it. `[^<>]` keeps one match from spanning a tag.
    val citation = Regex("""[^<>]*'s \d{4}-\d{2} Common Data Set""")
    assertTrue(
      citation.containsMatchIn(body),
      "the fixture must cite at least one filing, or this sweep proves nothing: [$body]",
    )

    val withoutLinks = body.replace(Regex("""<a\b[^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL), " ")
    assertEquals(
      emptyList(),
      citation.findAll(withoutLinks).map { it.value.trim() }.toList(),
      "a filing is cited on this page without a link to it: [$withoutLinks]",
    )
  }

  /**
   * The promise itself, as an IMPLICATION rather than a claim about today.
   *
   * Said plainly, because a vacuous test that is mistaken for a live one is
   * worse than none: this assertion CANNOT FAIL on the page as it exists.
   * `AssuranceTier.VOLUNTARY_SELF_REPORT` is reachable only from
   * `MoneySource.COMMON_DATA_SET` (`AssuranceTierClosureTest` pins that the
   * Scorecard and both IPEDS surveys never produce it), and no `CostField` is
   * filled from the Common Data Set -- so the sentence cannot render here at
   * all. Brief 0009's criterion, taken literally, was written against a surface
   * that does not exist.
   *
   * It is kept because it is the exact statement of the promise, and it goes
   * live by itself the day a Common Data Set figure reaches this page: the
   * sentence and no link is then a failure here rather than a defect a family
   * finds.
   */
  @Test
  fun `the sentence that promises a link is never printed without one`() {
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))

    if (body.contains(AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED))) {
      assertTrue(body.contains("<a href="), "we said we link to the college's own file and linked nothing: [$body]")
    }
    // The page states self-reported filings whether or not it states that
    // sentence, so the links themselves are asserted unconditionally -- and
    // EXACTLY, not at-least: three schools, a merit filing and a borrowing
    // filing each, and a seventh link would be a filing nobody specified.
    assertEquals(6, countOf(body, "rel=\"noreferrer\""), "three schools, a merit filing and a borrowing filing each")
  }

  /**
   * The link goes to the COLLEGE'S OWN FILE, per school, and never to our
   * mirror.
   *
   * `source_url` is what the sentence promises. `archive_url` is the corpus's
   * tidy permalink -- it stays on the coach wire, where `putCitation` emits
   * both, and it is not rendered here: a second link is chrome a parent did not
   * ask for, and one that replaced the college's own file would quietly change
   * what the sentence means.
   *
   * Per-school urls, because a single shared fixture url would let every
   * assertion pass on a neighbour's link.
   */
  @Test
  fun `each block links that school's own file, never our mirror`() {
    val merit = meritFixture("Ashford College", url = MERIT_URL, archiveUrl = ARCHIVE_URL)
    val borrowing = borrowingFixture("Ashford College", url = BORROWING_URL, archiveUrl = ARCHIVE_URL)
    val college =
      costFixture(
        name = "Ashford College",
        tuitionInState = 30000,
        publishedPrice = 52000,
        netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
        medianDebt = 21000,
        meritAid = merit,
        borrowing = borrowing,
      )

    val body = render(costProfile(listOf(college), answeredMoney()))

    assertTrue(body.contains(linkedCitation(merit.source)), "the merit block links its own filing: [$body]")
    assertTrue(body.contains(linkedCitation(borrowing.figures.source)), "the borrowing block links its own: [$body]")
    assertEquals(0, countOf(body, ARCHIVE_URL), "our mirror is not rendered on this page: [$body]")
  }

  /**
   * A `source_url` that is not an http(s) url STOPS THE PAGE.
   *
   * `href` is the one attribute where escaping does not save a logged-out
   * parent: `javascript:` in an href is live whatever `kotlinx.html` does to the
   * attribute value. Every one of the 5,048 rows in the committed Common Data
   * Set seed is http or https, so a row that reaches here with another scheme is
   * evidence the corpus is wrong or poisoned -- not a case to degrade
   * gracefully.
   *
   * Neither softer answer is available: dropping the link silently would leave
   * us promising a link beside none, and rendering it would put an executable
   * url in front of a family. So it faults, and the family gets the branded 503
   * this route already renders for a read fault.
   */
  @Test
  fun `a source url that is not http renders nothing at all, not a link`() =
    testApplication {
      val poisoned =
        costFixture(
          name = "Ashford College",
          tuitionInState = 30000,
          publishedPrice = 52000,
          netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
          meritAid = meritFixture("Ashford College", url = "javascript:alert(1)"),
        )
      application {
        testPublicWebModule(
          costReportSource =
            FakeCostReportSource(FakeReportAnswer.Live(costProfile(listOf(poisoned), answeredMoney()))),
        )
      }

      val response = client.get("/report?token=$TEST_LIVE_TOKEN")

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "a poisoned url must stop the page")
      assertFalse(response.bodyAsText().contains("javascript:"), "the url reached a parent anyway")
    }

  // ---------------------------------------------------------------------------
  // And no link to a grave (RFC 200)
  // ---------------------------------------------------------------------------

  /**
   * A filing whose own file is gone is still CITED -- in the same words -- and
   * is not linked (RFC 200 D6, D18).
   *
   * Both Common Data Set blocks at once, because they are two renders of one
   * helper and a fix applied to merit alone would leave borrowing linking a
   * 404. The family still learns whose claim it is and in which cycle: only the
   * anchor goes.
   */
  @Test
  fun `a filing whose college file is gone is said in the same words, with no anchor`() {
    val merit = meritFixture("Ashford College", url = MERIT_URL, sourceUrlStatus = SourceUrlStatus.NOT_FOUND)
    val borrowing = borrowingFixture("Ashford College", url = BORROWING_URL, sourceUrlStatus = SourceUrlStatus.NOT_FOUND)
    val college =
      costFixture(
        name = "Ashford College",
        tuitionInState = 30000,
        publishedPrice = 52000,
        netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
        medianDebt = 21000,
        meritAid = merit,
        borrowing = borrowing,
      )

    val body = render(costProfile(listOf(college), answeredMoney()))
    val report = reportSectionOf(body)

    // THE SAME WORDS. Built from the fixtures rather than typed, so the
    // assertion cannot pass on a school or a cycle the page never printed.
    assertTrue(report.contains(merit.source.citedAs), "the merit filing is still cited: [$report]")
    assertTrue(report.contains(borrowing.figures.source.citedAs), "the borrowing filing is still cited: [$report]")
    assertTrue(report.contains("Ashford College"), "the page still names the college")
    assertTrue(report.contains("2024-25"), "and still says which cycle the filing is")
    // AND NO ANCHOR, counted on the attribute rather than on one call spelling.
    assertEquals(0, countOf(report, "href"), "a dead filing was linked anyway: [$report]")
    assertEquals(0, countOf(body, "rel=\"noreferrer\""), "a dead filing was linked anyway: [$body]")
    // The url itself never reaches a parent, in an anchor or as text.
    assertEquals(0, countOf(body, MERIT_URL), "the dead merit url reached the page: [$body]")
    assertEquals(0, countOf(body, BORROWING_URL), "the dead borrowing url reached the page: [$body]")
  }

  /**
   * ONE gone filing does not cost the other one its link.
   *
   * The decision is per CITATION and not per page, and a helper that read a
   * page-level flag would pass every assertion above while dropping every
   * anchor on the report.
   */
  @Test
  fun `a gone filing beside a live one drops only its own anchor`() {
    val merit = meritFixture("Ashford College", url = MERIT_URL, sourceUrlStatus = SourceUrlStatus.NOT_FOUND)
    val borrowing = borrowingFixture("Ashford College", url = BORROWING_URL, sourceUrlStatus = SourceUrlStatus.FOUND)
    val college =
      costFixture(
        name = "Ashford College",
        tuitionInState = 30000,
        publishedPrice = 52000,
        netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
        medianDebt = 21000,
        meritAid = merit,
        borrowing = borrowing,
      )

    val body = render(costProfile(listOf(college), answeredMoney()))

    assertEquals(1, countOf(body, "rel=\"noreferrer\""), "exactly the live filing keeps its link: [$body]")
    assertTrue(body.contains(linkedCitation(borrowing.figures.source)), "the live borrowing filing is linked: [$body]")
    assertTrue(body.contains(merit.source.citedAs), "the gone merit filing is still cited: [$body]")
    assertEquals(0, countOf(body, MERIT_URL), "the dead merit url reached the page: [$body]")
  }

  /**
   * A verdict we do not have KEEPS the link, and a verdict we have and passed
   * renders exactly what it rendered before this RFC.
   *
   * `UNKNOWN` is "we never asked" or "we asked and got no usable answer" -- OUR
   * gap, never the school's silence (RFC 166 s6). A bot-blocked university is
   * not a dead filing, and nine of the 441 documents in the committed corpus
   * are exactly that.
   */
  @Test
  fun `a found filing is byte-unchanged and an unchecked one keeps its link`() {
    listOf(SourceUrlStatus.FOUND, SourceUrlStatus.UNKNOWN).forEach { status ->
      val merit = meritFixture("Ashford College", url = MERIT_URL, sourceUrlStatus = status)
      val borrowing = borrowingFixture("Ashford College", url = BORROWING_URL, sourceUrlStatus = status)
      val college =
        costFixture(
          name = "Ashford College",
          tuitionInState = 30000,
          publishedPrice = 52000,
          netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 22000),
          medianDebt = 21000,
          meritAid = merit,
          borrowing = borrowing,
        )

      val body = render(costProfile(listOf(college), answeredMoney()))

      assertTrue(body.contains(linkedCitation(merit.source)), "[$status] must link the merit filing: [$body]")
      assertTrue(body.contains(linkedCitation(borrowing.figures.source)), "[$status] must link the borrowing filing: [$body]")
      assertEquals(2, countOf(body, "rel=\"noreferrer\""), "[$status] renders both links and no more")
    }
  }

  // ---------------------------------------------------------------------------
  // RFC 239: the school's published automatic-scholarship grid
  // ---------------------------------------------------------------------------

  /** A band on the SAT's own published scale -- the axis most cases here are not about. */
  private fun sat(
    low: Int,
    high: Int,
  ): BandScoreAxis = BandScoreAxis.Range(test = AdmissionTest.SAT, low = low, high = high)

  /**
   * One published cell. The award id is a placeholder: [gridAward] re-keys every
   * band to the row it hangs off, exactly as the read hands them out, so no test
   * can build a grid whose cells belong to another school's row.
   */
  private fun gridBand(
    awardName: String = "Presidential",
    score: BandScoreAxis = sat(1200, 1250),
    gpaLow: BigDecimal? = null,
    gpaHigh: BigDecimal? = null,
    awardPerYearUsd: Int? = 6000,
    awardAsPublished: String? = null,
  ): AutomaticAwardBand =
    AutomaticAwardBand(
      id = AutomaticAwardBandId(UUID.randomUUID()),
      awardId = AutomaticAwardId(UUID.randomUUID()),
      awardName = awardName,
      score = score,
      gpaLow = gpaLow,
      gpaHigh = gpaHigh,
      awardPerYearUsd = awardPerYearUsd,
      awardAsPublished = awardAsPublished,
    )

  /** One school's published grid for one entry year and one residency basis, with its cells. */
  private fun gridAward(
    collegeId: CollegeId,
    bands: List<AutomaticAwardBand> = listOf(gridBand()),
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    awardKind: AutomaticAwardKind = AutomaticAwardKind.SCORE_GRID,
    scoreDeadline: LocalDate? = GRID_DEADLINE,
    scoreDeadlineAsPublished: String? = "Official test scores must be received by December 4.",
  ): AutomaticAward {
    val id = AutomaticAwardId(UUID.randomUUID())
    return AutomaticAward(
      id = id,
      collegeId = collegeId,
      entryYear = AcademicYear(2027),
      residencyBasis = residencyBasis,
      awardKind = awardKind,
      scoreRequirement = ScoreRequirement.REQUIRED,
      scoreRequirementAsPublished = GRID_REQUIREMENT,
      superscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
      superscoreAsPublished = "Scholarship awards are based on a single test administration.",
      scoreDeadline = scoreDeadline,
      scoreDeadlineAsPublished = scoreDeadlineAsPublished,
      lastSittingConsidered = null,
      publisher = GRID_PUBLISHER,
      sourceUrl = "https://example.test/riverside-scholarships",
      archiveUrl = null,
      checkedOn = GRID_READ_ON,
      expiresOn = LocalDate.of(2027, 6, 30),
      bands = bands.map { it.copy(awardId = id) },
    )
  }

  /**
   * The answer the report actually receives: a score grid, which is what
   * `AutomaticAwardService`'s student-free door returns.
   *
   * IT CANNOT BE HANDED A STANDING AT ALL. It used to take one, so that a test
   * could prove the page printed nothing from a full one; the standing now lives
   * on the student-scoped `StudentAwardAnswer` that only the chat door builds,
   * so the privacy ruling is a property of the TYPE this page is handed rather
   * than of this renderer's restraint.
   */
  private fun scoreBands(award: AutomaticAward): AutomaticAwardAnswer =
    AutomaticAwardAnswer.ScoreBands(
      collegeId = award.collegeId,
      award = award,
      lastUsableSitting = null,
      boundBy = emptySet(),
      closedSittings = emptyList(),
      quotedCutOff = null,
    )

  /** A school on the report with one published grid, keyed to the same college the answer names. */
  private fun gridSchool(
    name: String = "Riverside State University",
    collegeId: CollegeId = CollegeId(UUID.randomUUID()),
    answers: List<AutomaticAwardAnswer>,
  ): CollegeCost =
    costFixture(
      name = name,
      collegeId = collegeId,
      control = CollegeControl.Public(TuitionApplicable.IN_STATE),
      tuitionInState = 12000,
      publishedPrice = 28000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
      medianDebt = 21000,
      meritAid = meritFixture(name),
      automaticAwards = answers,
    )

  @Test
  fun `a school where a score is money shows its published grid, its requirement and its deadline`() {
    val id = CollegeId(UUID.randomUUID())
    val award =
      gridAward(
        collegeId = id,
        bands =
          listOf(
            gridBand(awardName = "Presidential", score = sat(1200, 1250), awardPerYearUsd = 6000),
            gridBand(
              awardName = "Presidential",
              score = BandScoreAxis.Range(test = AdmissionTest.ACT, low = 30, high = 31),
              awardPerYearUsd = 8000,
            ),
            gridBand(
              awardName = "Achievement",
              score = BandScoreAxis.NoScore,
              gpaLow = BigDecimal("3.50"),
              awardPerYearUsd = 2000,
            ),
          ),
      )

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(scoreBands(award)))), answeredMoney()))

    assertTrue(body.contains("report-award-grid"), "the block must be on the page: [$body]")
    assertTrue(body.contains("Automatic scholarships this school publishes"), "the block must say whose grid it is: [$body]")
    // The school's own cells, in the order the read served them, each with what
    // it asks for and what it pays.
    assertTrue(body.contains("Presidential \u2014 SAT 1200\u20131250 \u2014 \$6,000 a year"), "the SAT cell: [$body]")
    assertTrue(body.contains("Presidential \u2014 ACT 30\u201331 \u2014 \$8,000 a year"), "the ACT cell: [$body]")
    assertTrue(
      body.contains("Achievement \u2014 no test score, GPA 3.50 and above \u2014 \$2,000 a year"),
      "the published no-score tier says it asks for no score: [$body]",
    )
    assertTrue(body.contains(GRID_REQUIREMENT), "the submission requirement, in the school's own words: [$body]")
    assertTrue(body.contains("Scores must be with this school by Dec 4, 2026."), "the score deadline, through the date seam: [$body]")
    assertTrue(body.contains("Source: $GRID_PUBLISHER, read on Aug 1, 2026."), "cited once, at the head of the block: [$body]")
  }

  @Test
  fun `the grid is a sibling of the merit block and never inside it`() {
    val id = CollegeId(UUID.randomUUID())
    val body =
      render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(scoreBands(gridAward(collegeId = id))))), answeredMoney()))

    // A second publisher UNDER the merit heading would be attributed to that
    // school's Common Data Set filing (RFC 190), so the grid opens its own
    // section between merit and debt.
    assertTrue(
      body.indexOf("report-merit") < body.indexOf("report-award-grid"),
      "the grid follows the merit block: [$body]",
    )
    assertTrue(
      body.indexOf("report-award-grid") < body.indexOf("report-debt"),
      "and precedes the debt block: [$body]",
    )
  }

  @Test
  fun `a school whose automatic table a score does not move still shows the table, and says so`() {
    val id = CollegeId(UUID.randomUUID())
    val award =
      gridAward(
        collegeId = id,
        awardKind = AutomaticAwardKind.GPA_ONLY_GRID,
        bands =
          listOf(
            gridBand(
              awardName = "Trustee",
              score = BandScoreAxis.NoScore,
              // `3.750` as the NUMERIC(4,3) column really holds it: the page
              // prints the school's own two decimals and never a third.
              gpaLow = BigDecimal("3.750"),
              awardPerYearUsd = 4000,
            ),
          ),
      )
    val answer = AutomaticAwardAnswer.NoScoreEffect(collegeId = id, award = award)

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(answer))), answeredMoney()))

    assertTrue(body.contains("report-award-grid"), "a table a score does not move is still published money: [$body]")
    assertTrue(
      body.contains("A test score does not change what this school's automatic scholarships pay."),
      "\"we do not know\" would be false here, so the page says the true thing: [$body]",
    )
    assertTrue(body.contains("Trustee \u2014 GPA 3.75 and above \u2014 \$4,000 a year"), "and it still shows the table: [$body]")
  }

  @Test
  fun `no grid is shown where we have not read the school, it publishes none, or our reading is stale`() {
    val id = CollegeId(UUID.randomUUID())
    val expiredAward = gridAward(collegeId = id)
    val silences =
      mapOf(
        "we have not read this school" to AutomaticAwardAnswer.NotCurated(collegeId = id),
        "the school publishes none" to
          AutomaticAwardAnswer.NonePublished(
            collegeId = id,
            award =
              gridAward(
                collegeId = id,
                bands = emptyList(),
                awardKind = AutomaticAwardKind.NONE_PUBLISHED,
                scoreDeadline = null,
                scoreDeadlineAsPublished = null,
              ),
          ),
        "our reading is stale" to
          AutomaticAwardAnswer.Expired(collegeId = id, award = expiredAward, expiredOn = expiredAward.expiresOn),
        // The ordinary school: no curated grid at all.
      )

    silences.forEach { (silence, answer) ->
      val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(answer))), answeredMoney()))
      assertFalse(body.contains("report-award-grid"), "[$silence] must open no section: [$body]")
      assertFalse(body.contains(AWARD_KIND_SENTENCE), "[$silence] must not say what kind of number a grid is: [$body]")
    }

    val noAnswerAtAll = render(costProfile(listOf(gridSchool(collegeId = id, answers = emptyList())), answeredMoney()))
    assertFalse(noAnswerAtAll.contains("report-award-grid"), "a school with no grid at all opens no section: [$noAnswerAtAll]")
    assertFalse(noAnswerAtAll.contains(AWARD_KIND_SENTENCE), "and says nothing about grids: [$noAnswerAtAll]")
  }

  @Test
  fun `the grid carries no student score, no band the student reaches and no point gap`() {
    // THE PRIVACY RULING, ASSERTED (RFC 239 D-E). A logged-out parent reads this
    // page and it holds no test score today, so "your student scored 1310, so
    // this school pays $6,000" would be a NEW ruling under RFC 155 D-F rather
    // than an inherited one -- and this RFC declines to make it.
    //
    // THE ANSWER THIS PAGE IS HANDED CAN NO LONGER CARRY A STUDENT AT ALL. This
    // case used to build a standing -- a figure, a band reached, a point gap --
    // and assert that none of it was rendered; `AutomaticAwardAnswer.ScoreBands`
    // now has nowhere to put one, because a standing exists only inside the
    // student-scoped `StudentAwardAnswer` the chat door returns. So the ruling
    // is enforced at the SOURCE, and what is left to assert here is the other
    // half of it: the school's own grid still prints in full, and no sentence on
    // this page speaks about a student's score.
    val id = CollegeId(UUID.randomUUID())
    val reached = gridBand(awardName = "Presidential", score = sat(1200, 1250), awardPerYearUsd = 6000)
    val next = gridBand(awardName = "Presidential Elite", score = sat(1300, 1600), awardPerYearUsd = 10321)
    val award = gridAward(collegeId = id, bands = listOf(reached, next))

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(scoreBands(award)))), answeredMoney()))

    assertTrue(body.contains("report-award-grid"), "the school's own grid is still printed: [$body]")
    // The PAGE'S OWN HEADING is "Your student's college list", so the banned
    // phrases are the ones that could only come from a standing: a score said,
    // a gap measured, a band claimed as earned.
    listOf("your student scored", "your score", "points away", "qualifies for", "would earn").forEach { studentWords ->
      assertFalse(body.lowercase().contains(studentWords), "the page must say nothing about the student: [$studentWords]")
    }
    // And the school's own cells are unharmed by the silence.
    assertTrue(body.contains("Presidential Elite \u2014 SAT 1300\u20131600 \u2014 \$10,321 a year"), "the top cell: [$body]")
  }

  @Test
  fun `what kind of number a published grid is, is said once for the page and not once per school`() {
    val first = CollegeId(UUID.randomUUID())
    val second = CollegeId(UUID.randomUUID())
    val body =
      render(
        costProfile(
          listOf(
            gridSchool(name = "Riverside State University", collegeId = first, answers = listOf(scoreBands(gridAward(collegeId = first)))),
            gridSchool(name = "Ashford College", collegeId = second, answers = listOf(scoreBands(gridAward(collegeId = second)))),
            stateSchool(),
          ),
          answeredMoney(),
        ),
      )

    assertEquals(2, countOf(body, "report-award-grid"), "one block per school that publishes a grid: [$body]")
    assertEquals(1, countOf(body, AWARD_KIND_SENTENCE), "and the standing sentence once for the whole page: [$body]")
    assertTrue(body.indexOf("report-kinds") < body.indexOf(AWARD_KIND_SENTENCE), "in the block that says what kind of numbers these are")
  }

  @Test
  fun `a cell the school publishes in words renders those words, never a blank and never a zero`() {
    // Nineteen of the 571 committed cells pay "tuition and fees" rather than a
    // figure, and they are the TOP bands: a blank there shows a school's best
    // award as its worst.
    val id = CollegeId(UUID.randomUUID())
    val award =
      gridAward(
        collegeId = id,
        bands =
          listOf(
            gridBand(awardName = "Presidential", score = sat(1200, 1250), awardPerYearUsd = 6000),
            gridBand(
              awardName = "Presidential Elite",
              score = sat(1500, 1600),
              awardPerYearUsd = null,
              awardAsPublished = "tuition and fees",
            ),
          ),
      )

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(scoreBands(award)))), answeredMoney()))

    assertTrue(
      body.contains("Presidential Elite \u2014 SAT 1500\u20131600 \u2014 tuition and fees"),
      "the school's own words for what it pays: [$body]",
    )
    assertFalse(body.contains("\$0"), "a cell with no figure is never a zero: [$body]")
  }

  @Test
  fun `a school that publishes one grid per residency shows both, each scoped to the students it is for`() {
    // TWENTY of the forty curated colleges publish two grids, and which one a
    // family falls under is a legal status the SCHOOL determines. Printing one
    // of them would be picking that status for the family.
    val id = CollegeId(UUID.randomUUID())
    val inState =
      scoreBands(
        gridAward(
          collegeId = id,
          residencyBasis = ResidencyBasis.IN_STATE,
          bands = listOf(gridBand(awardName = "Crimson", score = sat(1200, 1250), awardPerYearUsd = 6000)),
        ),
      )
    val outOfState =
      scoreBands(
        gridAward(
          collegeId = id,
          residencyBasis = ResidencyBasis.OUT_OF_STATE,
          bands = listOf(gridBand(awardName = "Crimson", score = sat(1200, 1250), awardPerYearUsd = 14000)),
        ),
      )

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(inState, outOfState))), answeredMoney()))

    assertEquals(2, countOf(body, "report-award-grid"), "both published grids, and neither picked for the family: [$body]")
    assertTrue(
      body.contains("Automatic scholarships this school publishes for students it classes as in-state"),
      "the in-state grid says who it is for: [$body]",
    )
    assertTrue(
      body.contains("Automatic scholarships this school publishes for students it classes as out-of-state"),
      "and so does the other one: [$body]",
    )
    assertTrue(body.contains("\$6,000 a year") && body.contains("\$14,000 a year"), "each grid keeps its own money: [$body]")
  }

  @Test
  fun `a school's own words are quoted verbatim, and our own prose never says the word they use`() {
    // THE EXEMPTION, WRITTEN DOWN AND EXECUTED. This page may not say "award"
    // (RFC 141, and the vocabulary sweep above that executes it), because the
    // word makes published money sound granted. But 14 of the corpus's 87
    // distinct band names contain it -- "Academic Achievement Award", "1863
    // Landmark Award" -- and so do two of the schools' own published amounts.
    // Those are proper nouns and quotations: rewriting them would misquote a
    // publisher, which is a worse fault than a banned word inside quotation
    // marks. So the rule is not "the word never appears"; it is "the word never
    // appears in OUR prose", and that is what this asserts.
    val id = CollegeId(UUID.randomUUID())
    val award =
      gridAward(
        collegeId = id,
        bands =
          listOf(
            gridBand(
              awardName = SCHOOL_BAND_NAME,
              score = sat(1200, 1250),
              awardPerYearUsd = null,
              awardAsPublished = SCHOOL_BAND_WORDS,
            ),
          ),
      )

    val body = render(costProfile(listOf(gridSchool(collegeId = id, answers = listOf(scoreBands(award)))), answeredMoney()))

    assertTrue(body.contains(SCHOOL_BAND_NAME), "the school's own name for its band, verbatim: [$body]")
    assertTrue(body.contains(SCHOOL_BAND_WORDS), "and its own words for what it pays, verbatim: [$body]")

    val prose = visibleProseOf(body)
    val quotations = listOf(SCHOOL_BAND_NAME, SCHOOL_BAND_WORDS)
    val quoted = quotations.flatMap { quotation -> occurrencesOf(prose, quotation).map { it until it + quotation.length } }
    assertEquals(quotations.size, quoted.size, "each quotation must be on the page exactly once for this test to mean anything")

    val saidAt = occurrencesOf(prose.lowercase(), "award")
    assertTrue(saidAt.isNotEmpty(), "the word must be on the page at all, or this test asserts nothing: [$prose]")
    saidAt.forEach { at ->
      assertTrue(
        quoted.any { at in it },
        "\"award\" at [$at] is not inside a school's own quoted words, so it is OUR prose saying it: [$prose]",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // RFC 247: the National Merit scholarships a school publishes
  // ---------------------------------------------------------------------------

  /** The base rate every priced row in one read carries: 15,000 of 1,200,000 entrants, said as 1.3%. */
  private val meritBaseRate =
    PublishedBaseRate(
      competitionYear = 2028,
      funnel =
        NationalMeritFunnel(
          testTakers = 3_500_000,
          metEntryRequirements = 1_200_000,
          highScorersScreened = 50_000,
          commended = 34_000,
          semifinalists = 16_000,
          finalists = 15_000,
          winners = 6_700,
          asPublished = "About 1.2 million entrants; some 15,000 reach Finalist.",
        ),
      publisher = MERIT_SPONSOR,
      checkedOn = MERIT_BASE_RATE_READ_ON,
    )

  /** One school's paying claim, with the shape most of the corpus's rows take. */
  private fun payingClaim(
    guarantee: AwardGuarantee = AwardGuarantee.AUTOMATIC,
    standing: QualifyingStanding = QualifyingStanding.FINALIST,
    asPublished: String = MERIT_WORDS,
    awardName: String? = null,
    perYearUsd: Int? = 17_500,
    totalUsd: Int? = 70_000,
    years: Int? = 4,
    firstChoice: FirstChoiceRule? = FirstChoiceRule.Required(deadline = MERIT_FIRST_CHOICE_BY, asPublished = MERIT_FIRST_CHOICE_WORDS),
  ): NationalMeritClaim.Paying =
    NationalMeritClaim.Paying(
      guarantee = guarantee,
      standing = standing,
      asPublished = asPublished,
      awardName = awardName,
      perYearUsd = perYearUsd,
      totalUsd = totalUsd,
      years = years,
      firstChoice = firstChoice,
      applyDeadline = null,
    )

  /** One stored row, built in memory exactly as `NationalMeritAwardsDao` hands it out. */
  private fun meritAward(
    collegeId: CollegeId,
    claim: NationalMeritClaim = payingClaim(),
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
  ): NationalMeritAward =
    NationalMeritAward(
      id = NationalMeritAwardId(UUID.randomUUID()),
      collegeId = collegeId,
      entryYear = AcademicYear(2027),
      residencyBasis = residencyBasis,
      claim = claim,
      publisher = MERIT_PUBLISHER,
      sourceUrl = "https://example.test/riverside-national-merit",
      archiveUrl = null,
      checkedOn = MERIT_READ_ON,
      expiresOn = LocalDate.of(2027, 7, 31),
    )

  /**
   * The answer the report receives for one stored row, decided by
   * `NationalMeritPublishedAnswer.of` -- the one home of the arm rule -- so the
   * page is only ever tested on answers the service can produce. By default on
   * the day the row was read, inside its belief window, and priced with
   * [meritBaseRate]; a null [baseRate] withholds the price.
   */
  private fun meritAnswer(
    award: NationalMeritAward,
    today: LocalDate = award.checkedOn,
    baseRate: PublishedBaseRate? = meritBaseRate,
  ): NationalMeritPublishedAnswer = NationalMeritPublishedAnswer.of(award, today, baseRate)

  /** A school on the report with its National Merit answers, keyed to the same college they name. */
  private fun meritSchool(
    name: String = "Riverside State University",
    collegeId: CollegeId = CollegeId(UUID.randomUUID()),
    answers: (CollegeId) -> List<NationalMeritPublishedAnswer>,
  ): CollegeCost =
    costFixture(
      name = name,
      collegeId = collegeId,
      state = "AL",
      control = CollegeControl.Public(TuitionApplicable.IN_STATE),
      tuitionInState = 12000,
      publishedPrice = 28000,
      netPrice = NetPrice.BandSpecific(IncomeBand.K48_TO_75K, 15000),
      medianDebt = 21000,
      meritAid = meritFixture(name),
      nationalMerit = answers(collegeId),
    )

  /** Every `report-national-merit` section on the page, markup and all -- what "inside the block" means. */
  private fun meritSectionsOf(body: String): List<String> =
    body
      .split("<section class=\"report-national-merit\">")
      .drop(1)
      .map { it.substringBefore("</section>") }

  @Test
  fun `an automatic row shows who it is for, that it is paid, the figures, the school's words and its first-choice rule`() {
    val body =
      render(
        costProfile(
          listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id, residencyBasis = ResidencyBasis.IN_STATE))) }),
          answeredMoney(),
        ),
      )

    val block = meritSectionsOf(body).single()
    assertTrue(block.contains("National Merit scholarships this school publishes"), "the heading: [$block]")
    assertTrue(block.contains("Source: $MERIT_PUBLISHER, read on Sep 25, 2026."), "cited once, at the head: [$block]")
    assertTrue(
      block.contains("For National Merit Finalists entering in 2027-28, for students it classes as in-state."),
      "the standing, the entering class and the school's audience: [$block]",
    )
    assertTrue(block.contains(AUTOMATIC_MERIT_LINE), "an automatic row is said as paid: [$block]")
    assertTrue(block.contains("\$17,500 a year, \$70,000 in total over 4 years."), "the school's own figures: [$block]")
    assertTrue(block.contains(MERIT_WORDS), "the school's own sentence, verbatim: [$block]")
    assertTrue(
      block.contains("$MERIT_FIRST_CHOICE_WORDS Name it first choice by May 1, 2027."),
      "the first-choice rule in the school's words, its day through the date seam: [$block]",
    )
    assertFalse(block.contains("of entrants"), "the base rate is said once for the page, never inside a school's block: [$block]")
  }

  @Test
  fun `a competitive row is said as considered and not promised, and never as paid`() {
    val claim = payingClaim(guarantee = AwardGuarantee.COMPETITIVE, standing = QualifyingStanding.SEMIFINALIST)
    val body = render(costProfile(listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id, claim))) }), answeredMoney()))

    val block = meritSectionsOf(body).single()
    assertTrue(block.contains(COMPETITIVE_MERIT_LINE), "considered, and not promised: [$block]")
    assertFalse(block.contains(AUTOMATIC_MERIT_LINE), "and never the automatic sentence: [$block]")
    assertTrue(block.contains("For National Merit Semifinalists entering in 2027-28."), "a row for everybody names no audience: [$block]")
  }

  @Test
  fun `a row the school prices in words alone prints its words and no figure line, never a zero`() {
    // A COUNT OF YEARS IS NOT A PRICE: a row with years and no amount is still
    // words alone, and the school's sentence says how long it pays.
    listOf(null, 4).forEach { years ->
      val claim = payingClaim(asPublished = MERIT_WORDS_ONLY, perYearUsd = null, totalUsd = null, years = years)
      val body = render(costProfile(listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id, claim))) }), answeredMoney()))

      val block = meritSectionsOf(body).single()
      assertTrue(block.contains(MERIT_WORDS_ONLY), "[$years years] the school's words ARE the price: [$block]")
      assertFalse(block.contains("report-merit-figure"), "[$years years] no figure line where the school publishes no figure: [$block]")
      assertFalse(body.contains("\$0"), "[$years years] and never a zero: [$body]")
    }
  }

  @Test
  fun `a National Merit row with an audience the page cannot name refuses the page rather than print it`() {
    listOf(ResidencyBasis.IN_DISTRICT, ResidencyBasis.UNKNOWN).forEach { corrupt ->
      val (status, body) =
        renderResponse(
          costProfile(listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id, residencyBasis = corrupt))) }), answeredMoney()),
        )
      assertEquals(HttpStatusCode.ServiceUnavailable, status, "[$corrupt] is corruption, and stops the page: [$body]")
      assertFalse(body.contains(MERIT_WORDS), "[$corrupt] must not reach the page as a row: [$body]")
      assertFalse(body.contains("report-national-merit"), "[$corrupt] must open no section: [$body]")
    }
  }

  @Test
  fun `no block and no kinds sentence for an absence, a stale reading, a withheld price or no row at all`() {
    val silences =
      mapOf<String, (CollegeId) -> List<NationalMeritPublishedAnswer>>(
        "the school publishes none" to { id ->
          listOf(meritAnswer(meritAward(id, NationalMeritClaim.NonePublished(MERIT_ABSENCE_WORDS))))
        },
        "our reading is stale" to { id ->
          val award = meritAward(id)
          listOf(meritAnswer(award, today = award.expiresOn.plusDays(1)))
        },
        "no believed cycle publishes a base rate" to { id -> listOf(meritAnswer(meritAward(id), baseRate = null)) },
        "we hold no row" to { _ -> emptyList() },
      )

    silences.forEach { (silence, answers) ->
      val body = render(costProfile(listOf(meritSchool(answers = answers)), answeredMoney()))
      assertFalse(body.contains("report-national-merit"), "[$silence] must open no section: [$body]")
      assertFalse(body.contains(NATIONAL_MERIT_KIND_SENTENCE), "[$silence] must not say what kind of number it is: [$body]")
      assertFalse(body.contains("report-merit-base-rate"), "[$silence] must say no base rate: [$body]")
      assertFalse(body.contains(MERIT_WORDS), "[$silence] must quote none of the school's price: [$body]")
    }
  }

  @Test
  fun `one stale row beside a live one prints only the live one`() {
    val body =
      render(
        costProfile(
          listOf(
            meritSchool { id ->
              val stale = meritAward(id, payingClaim(standing = QualifyingStanding.SEMIFINALIST, asPublished = MERIT_WORDS_ONLY))
              listOf(meritAnswer(stale, today = stale.expiresOn.plusDays(1)), meritAnswer(meritAward(id)))
            },
          ),
          answeredMoney(),
        ),
      )

    val block = meritSectionsOf(body).single()
    assertEquals(1, countOf(block, "<li>"), "only the live row: [$block]")
    assertTrue(block.contains(MERIT_WORDS), "the live row's words: [$block]")
    assertFalse(block.contains(MERIT_WORDS_ONLY), "and never the stale row's: [$block]")
  }

  @Test
  fun `an in-state and an out-of-state row both print, each labelled, and neither is compared with the family`() {
    // THE FAMILY LIVES IN OREGON and the school is in Alabama, so the page holds
    // everything it would need to say "this one is not yours" -- and it does
    // not (D2): residency for tuition is a status the school decides.
    val body =
      render(
        costProfile(
          listOf(
            meritSchool { id ->
              listOf(
                meritAnswer(meritAward(id, payingClaim(perYearUsd = 15_000, totalUsd = 60_000), ResidencyBasis.IN_STATE)),
                meritAnswer(meritAward(id, payingClaim(perYearUsd = 17_500, totalUsd = 70_000), ResidencyBasis.OUT_OF_STATE)),
              )
            },
          ),
          answeredMoney(state = "OR"),
        ),
      )

    val block = meritSectionsOf(body).single()
    assertEquals(2, countOf(block, "<li>"), "both rows, and neither picked: [$block]")
    assertTrue(block.contains("for students it classes as in-state."), "the in-state row says who it is for: [$block]")
    assertTrue(block.contains("for students it classes as out-of-state."), "and so does the other one: [$block]")
    assertTrue(block.contains("\$15,000 a year") && block.contains("\$17,500 a year"), "each keeps its own money: [$block]")
    assertFalse(Regex("\\bOR\\b|Oregon|your state|lives in|not yours").containsMatchIn(block), "no comparison with the family: [$block]")
  }

  @Test
  fun `the National Merit block follows the grid and precedes debt`() {
    val body =
      render(
        costProfile(
          listOf(
            CollegeId(UUID.randomUUID()).let { id ->
              costFixture(
                name = "Riverside State University",
                collegeId = id,
                meritAid = meritFixture("Riverside State University"),
                medianDebt = 21000,
                automaticAwards = listOf(scoreBands(gridAward(collegeId = id))),
                nationalMerit = listOf(meritAnswer(meritAward(id))),
              )
            },
          ),
          answeredMoney(),
        ),
      )

    assertTrue(body.indexOf("report-merit\"") < body.indexOf("report-award-grid"), "merit, then the grid: [$body]")
    assertTrue(body.indexOf("report-award-grid") < body.indexOf("report-national-merit"), "then National Merit: [$body]")
    assertTrue(body.indexOf("report-national-merit") < body.indexOf("report-debt"), "then debt: [$body]")
  }

  @Test
  fun `the National Merit block says nothing about the student`() {
    // A STANDING IS NOT A SCORE (D1): that is what lets the page print the
    // package, and it never lets it print her result. The page's own heading and
    // live line say "your student", so the block itself is what is swept.
    val body = render(costProfile(listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id))) }), answeredMoney()))

    val block = meritSectionsOf(body).single().lowercase()
    listOf("selection index", "your student", "scored", "psat score", "qualifies").forEach { studentWords ->
      assertFalse(block.contains(studentWords), "the block must say nothing about the student: [$studentWords] in [$block]")
    }
    val lower = body.lowercase()
    listOf("selection index", "psat score", "qualifies").forEach { studentWords ->
      assertFalse(lower.contains(studentWords), "nor anywhere on the page: [$studentWords]")
    }
  }

  @Test
  fun `what kind of number a National Merit scholarship is, and the base rate, are said once for the page`() {
    val body =
      render(
        costProfile(
          listOf(
            meritSchool(name = "Riverside State University") { id -> listOf(meritAnswer(meritAward(id))) },
            meritSchool(name = "Ashford College") { id -> listOf(meritAnswer(meritAward(id))) },
            stateSchool(),
          ),
          answeredMoney(),
        ),
      )

    assertEquals(2, countOf(body, "<section class=\"report-national-merit\">"), "one block per school: [$body]")
    assertEquals(1, countOf(body, NATIONAL_MERIT_KIND_SENTENCE), "the kind sentence once for the page: [$body]")
    val baseRate =
      "About 1.3% of entrants in the 2028 National Merit competition reach Finalist. " +
        "Source: $MERIT_SPONSOR, read on Sep 1, 2026."
    assertEquals(1, countOf(body, baseRate), "the base rate once, with its own publisher and read date: [$body]")
    val kinds = kindsBlockOf(body)
    assertTrue(kinds.contains(NATIONAL_MERIT_KIND_SENTENCE), "inside the kinds block: [$kinds]")
    assertTrue(kinds.contains(baseRate), "and so is the base rate: [$kinds]")

    val none = render(costProfile(listOf(stateSchool()), answeredMoney()))
    assertFalse(none.contains(NATIONAL_MERIT_KIND_SENTENCE), "no National Merit row, no kind sentence: [$none]")
    assertFalse(none.contains("of entrants"), "and no base rate: [$none]")
  }

  @Test
  fun `a school's own National Merit words are quoted verbatim, and our own prose never says the word they use`() {
    val claim = payingClaim(awardName = MERIT_SCHOOL_NAME, asPublished = MERIT_SCHOOL_WORDS)
    val body = render(costProfile(listOf(meritSchool { id -> listOf(meritAnswer(meritAward(id, claim))) }), answeredMoney()))

    val prose = visibleProseOf(body)
    val quotations = listOf(MERIT_SCHOOL_NAME, MERIT_SCHOOL_WORDS, MERIT_FIRST_CHOICE_WORDS)
    val quoted = quotations.flatMap { quotation -> occurrencesOf(prose, quotation).map { it until it + quotation.length } }
    assertEquals(quotations.size, quoted.size, "each quotation must be on the page exactly once for this test to mean anything")

    val saidAt = occurrencesOf(prose.lowercase(), "award")
    assertTrue(saidAt.isNotEmpty(), "the word must be on the page at all, or this test asserts nothing: [$prose]")
    saidAt.forEach { at ->
      assertTrue(
        quoted.any { at in it },
        "\"award\" at [$at] is not inside a school's own quoted words, so it is OUR prose saying it: [$prose]",
      )
    }
  }

  private companion object {
    /** The day we read the school's scholarship page -- the read date every grid block cites. */
    val GRID_READ_ON: LocalDate = LocalDate.of(2026, 8, 1)

    /** The day official scores must be with the school, rendered through the one date seam as `Dec 4, 2026`. */
    val GRID_DEADLINE: LocalDate = LocalDate.of(2026, 12, 4)

    /** The school's OWN sentence about submitting a score; the page quotes it and composes none of its own. */
    const val GRID_REQUIREMENT = "A test score is required for scholarship consideration."

    /** Who published the grid, as a family would recognise them on the page. */
    const val GRID_PUBLISHER = "Riverside State University Office of Admissions"

    /**
     * The standing sentence about what kind of number a published grid is (RFC
     * 191), pinned HERE and not imported: it is copy a parent reads, so a
     * reword must fail a test rather than pass one that moved with it.
     */
    const val AWARD_KIND_SENTENCE = "An automatic scholarship is published by the school and is not a financial aid offer."

    /**
     * A band name and a published amount from the corpus's own shape: 14 of the
     * 87 distinct committed band names contain "Award", and two
     * `award_as_published` strings do. They are the SCHOOL's proper nouns and
     * the SCHOOL's sentences, and they are quoted, not reworded.
     */
    const val SCHOOL_BAND_NAME = "Academic Achievement Award"
    const val SCHOOL_BAND_WORDS = "The award amount varies from \$8,000- \$12,000 annually"

    /** One school's own Common Data Set file, and a second one, so a link cannot pass on the other's url. */
    const val MERIT_URL = "https://example.test/ashford-merit-cds.pdf"
    const val BORROWING_URL = "https://example.test/ashford-borrowing-cds.pdf"

    /** Our mirror of the same filing -- on the wire, never on this page (RFC 196). */
    const val ARCHIVE_URL = "https://archive.example.test/ashford-cds.pdf"

    /** Who published a school's National Merit row, and the day we read it, rendered as `Sep 25, 2026`. */
    const val MERIT_PUBLISHER = "Riverside State University Office of Scholarships"
    val MERIT_READ_ON: LocalDate = LocalDate.of(2026, 9, 25)

    /** Who published the base rate, and the day we read it, rendered as `Sep 1, 2026` -- a different day, so neither date can pass for the other. */
    const val MERIT_SPONSOR = "Example Merit Corporation"
    val MERIT_BASE_RATE_READ_ON: LocalDate = LocalDate.of(2026, 9, 1)

    /** The first-choice deadline, rendered through the date seam as `May 1, 2027`. */
    val MERIT_FIRST_CHOICE_BY: LocalDate = LocalDate.of(2027, 5, 1)
    const val MERIT_FIRST_CHOICE_WORDS = "Name us your first choice with NMSC."

    /** A school's own sentences for what it pays, in figures and in words alone. */
    const val MERIT_WORDS = "Finalists receive \$17,500 a year for four years."
    const val MERIT_WORDS_ONLY = "Finalists receive the full cost of tuition and required fees, for four years."
    const val MERIT_ABSENCE_WORDS = "We no longer participate in offering college-sponsored National Merit scholarships."

    /**
     * Pinned HERE and not imported, for [AWARD_KIND_SENTENCE]'s reason: copy a
     * parent reads, so a reword must fail a test.
     */
    const val NATIONAL_MERIT_KIND_SENTENCE =
      "A National Merit scholarship a school publishes is the school's own money, paid only to students who reach " +
        "the standing it names, and is not a financial aid offer."
    const val AUTOMATIC_MERIT_LINE = "The school pays this to every student holding this standing who meets its terms."
    const val COMPETITIVE_MERIT_LINE =
      "The school considers students holding this standing for this scholarship. It does not promise it."

    /** The corpus's own shape: a school's scholarship name and its sentence both say "award", and both are quoted. */
    const val MERIT_SCHOOL_NAME = "National Merit Finalist Award"
    const val MERIT_SCHOOL_WORDS = "Finalists will be awarded full tuition for four years."
  }
}
