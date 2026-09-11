package ed.unicoach.web

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
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.Share
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.BorrowerCounts
import ed.unicoach.db.models.CollegeBorrowing
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PublishedCell
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.web.render.BLENDED_IN_STATE_CLAUSE
import ed.unicoach.web.render.NOT_REPORTED
import ed.unicoach.web.render.SCHOOL_BLENDED_HINT
import ed.unicoach.web.render.SUMMARY_HINT
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
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

  private fun render(profile: CollegeCostProfile): String {
    var body = ""
    testApplication {
      application {
        testPublicWebModule(costReportSource = FakeCostReportSource(FakeReportAnswer.Live(profile)))
      }
      body = client.get("/report?token=$TEST_LIVE_TOKEN").bodyAsText()
    }
    return body
  }

  /** How many times [needle] is on the page -- the measurement "said once" actually needs. */
  private fun countOf(
    body: String,
    needle: String,
  ): Int = body.split(needle).size - 1

  /**
   * The words a parent actually reads, counted the way RFC 188 measured them:
   * the report section with its markup stripped, entities restored, and
   * whitespace-separated tokens counted.
   *
   * The `<head>` and the site chrome are excluded on purpose -- the budget is on
   * the report's own copy, and a footer link is not a sentence about a school.
   */
  private fun visibleWordsOf(body: String): Int {
    // The marker is ASSERTED, not assumed: `substringAfter` returns the WHOLE
    // document when it misses, so a renamed section would quietly start
    // measuring the head and the site chrome and the ceiling would be a
    // measurement of something else that still passed.
    val opening = "<section class=\"report\""
    assertTrue(body.contains(opening), "the report section must be on the page to be measured")
    val report = body.substringAfter(opening).substringBeforeLast("</section>")
    val text =
      report
        .replace(Regex("<[^>]*>"), " ")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // LAST, always: decoding it first would turn `&amp;lt;` into a `<` the
        // page never printed.
        .replace("&amp;", "&")
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
      body.contains(AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD)),
      "the shown debt paragraph carries the administrative-record sentence: [$body]",
    )
    // Beside the figure, not instead of it, and the two sentences stay two.
    assertTrue(
      body.indexOf("The source publishes no year for this figure") <
        body.indexOf(AssuranceTierCopy.statementOf(AssuranceTier.ADMINISTRATIVE_RECORD)),
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
    val tier = AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY)
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
  fun `a three-school report says every fact it holds in fewer than fifteen hundred words`() {
    // D13: the budget lives in a test, not in an RFC. RFC 188 measured this
    // fixture at 1,767 words before it and 1,530 after; the assertion is a
    // RATCHET -- a repeat added back to the renderer fails here.
    val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))

    val words = visibleWordsOf(body)
    assertTrue(words <= WORD_CEILING, "the three-school page reads [$words] words, over [$WORD_CEILING]")
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
    assertTrue(
      body.contains("Borrowing College's 2024-25 Common Data Set reports these figures for the students who"),
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
    val spoken = wireSentences.filter { body.contains(it) }
    assertEquals(
      wireSentences,
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
    val headerAt = body.indexOf(block.header)
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
    val header = BorrowingWire.blockOf(borrowingFixture("Borrowing College")).header
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

  private companion object {
    /**
     * The word budget for the three-school page (brief 0009 D9/D13), measured
     * on [verboseState] + [verboseCampus] + [verbosePrivate].
     *
     * The number is the one each slice actually LEFT the page at, not the one it
     * hoped for. RFC 188 took it from 1,767 words to 1,530; RFC 190 -- citing
     * each block once instead of re-naming the school, its cycle and its
     * document at every borrowing figure -- takes it from 1,530 to 1,450, which
     * is 80 words of pure repetition off three schools. Both numbers were
     * MEASURED here, on the same three verbose fixtures, by setting this
     * constant to 0 and reading the failure.
     *
     * A ceiling set at a number the page does not meet is a test that is either
     * failing or lying; this one ratchets what was really won and refuses a
     * regression.
     */
    const val WORD_CEILING = 1450
  }
}
