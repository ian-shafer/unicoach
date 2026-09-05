package ed.unicoach.web

import ed.unicoach.coaching.costs.AT_HOME_ASSUMPTION_STATEMENT
import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.UcsdScorecardRow
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.ResidencyTierBasis
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.web.render.NOT_REPORTED
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
  fun `the blended-figure hint under both tables says which residency those figures are on`() {
    val body = render(costProfile(listOf(stateSchool(), campusSchool()), answeredMoney()))

    // The clause is appended to BOTH hint sentences, not to one of them: the
    // summary and the per-school table print the two blended rows alike.
    val clause =
      "At a public school it, and the likely price after a financial aid offer, are figures for " +
        "students paying in-state tuition."
    assertTrue(
      body.contains("so it is not the sum of the parts quoted below. $clause"),
      "the summary hint must carry the residency clause",
    )
    assertTrue(
      body.contains("so it is not the sum of the parts above it. $clause"),
      "and so must the per-school hint",
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
      body.contains(requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER))),
      "the blank says whose gap it is, in the domain's own words: [$body]",
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
      body.contains(requireNotNull(FigureStatusCopy.statementOf(FigureStatus.NOT_COLLECTED_BY_US))),
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
      body.contains(requireNotNull(FigureStatusCopy.statementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER))),
      "the privacy suppression is stated: [$body]",
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
}
