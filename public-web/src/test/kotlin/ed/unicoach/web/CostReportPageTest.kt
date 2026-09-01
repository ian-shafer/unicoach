package ed.unicoach.web

import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.db.models.IncomeBand
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  @Test
  fun `a two-school list states the five comparison assumptions in the domain's own words`() {
    val profile = costProfile(listOf(stateSchool(), privateSchool()), answeredMoney())
    val basis = requireNotNull(profile.comparisonBasis) { "two colleges must carry a comparison basis" }

    val body = render(profile)

    assertTrue(body.contains(basis.population.statement), "missing the population line")
    assertTrue(body.contains(basis.residency.statement), "missing the residency line")
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

  @Test
  fun `a school priced only for living at home shows no housing and food line`() {
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
    assertFalse(body.contains("Housing and food"), "living at home has no housing-and-food figure to show")
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

    assertTrue(body.contains("U.S. Department of Education College Scorecard"), "missing the Scorecard attribution")
    assertTrue(body.contains("Common Data Set"), "missing the CDS attribution")
    assertTrue(body.contains("They are not an offer"), "the page must say these are not an offer")
    assertTrue(
      body.contains("Only a school's own financial aid offer is a price for this family"),
      "the page must say what a price actually is",
    )
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
}
