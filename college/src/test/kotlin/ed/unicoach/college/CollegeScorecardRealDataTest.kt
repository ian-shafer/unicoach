package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real loader against verbatim, machine-extracted real Scorecard rows
 * (the full real headers plus the institution/field-of-study quirk rows RFC 78
 * hardens against). Each observed quirk is an executable assertion: negative
 * net price, out-of-domain optional metrics, the real OPEID column, the 2/4/6
 * CIP grammar, quoted embedded commas, and the `CREDLEV=99` / `UNITID=NA`
 * sentinels.
 */
class CollegeScorecardRealDataTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)

  /**
   * The canonical fill, run explicitly. `load` is the legacy per-file path and
   * does not run the `canonical-money` phase, so the RFC 157 falsifier -- which
   * reads the cells that phase writes since RFC 176 -- asks for it by name.
   */
  private val canonicalMoneyLoader = CanonicalMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-real-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-real-fixture.csv")

  @Test
  fun `negative and NA net prices load from real rows into the canonical store (guards 0022, RFC 133)`() =
    runBlocking {
      // PORTED by RFC 176: `colleges` carries no money, so these real-row
      // guards read the cells the same run writes to `cohort_money_stats`.
      // What they prove is unchanged -- the published data really does carry
      // negative net prices, and an `NA` really is an absence rather than a
      // zero -- and they still run over verbatim Scorecard rows.
      loader.load(institutionCsv, fieldsCsv)
      canonicalMoneyLoader.fill(institutionCsv, sfa = null)

      val netPrices = netPricesByUnit()

      // Ventura (public): the overall blend and the low-income bands are
      // genuinely negative in the published data and must load un-coerced.
      assertEquals(-982, netPrices[125028 to null])
      assertEquals(-1913, netPrices[125028 to IncomeBand.UNDER_30K.value])
      assertEquals(-2393, netPrices[125028 to IncomeBand.K30_TO_48K.value])
      assertEquals(524, netPrices[125028 to IncomeBand.K48_TO_75K.value])
      assertEquals(4165, netPrices[125028 to IncomeBand.K75_TO_110K.value])
      assertEquals(6577, netPrices[125028 to IncomeBand.OVER_110K.value])

      // Auburn Montgomery (public): plain positive bands from the _PUB columns.
      assertEquals(11706, netPrices[100830 to IncomeBand.UNDER_30K.value])
      assertEquals(16117, netPrices[100830 to IncomeBand.OVER_110K.value])

      // Pensacola Christian (private): every band cell is the NA sentinel, so
      // the store bears no value there -- never a zero.
      assertNull(netPrices[136455 to IncomeBand.UNDER_30K.value])
      assertNull(netPrices[136455 to IncomeBand.OVER_110K.value])
    }

  @Test
  fun `the six cost components load from real rows, NA is not reported (RFC 149)`() =
    runBlocking {
      // PORTED by RFC 176, from `colleges` to `price_figures`. Same rows, same
      // claim: "not reported" is a PER-ARRANGEMENT fact, and it is an absence
      // of value rather than a zero.
      loader.load(institutionCsv, fieldsCsv)
      canonicalMoneyLoader.fill(institutionCsv, sfa = null)

      val cells = componentAmountsByUnit()

      // Auburn Montgomery: a school that reports every one of the six, so all
      // three living arrangements are answerable from this row.
      assertEquals(1500, cells[100830 to BOOKS])
      assertEquals(7368, cells[100830 to housing(FigureArrangement.ON_CAMPUS)])
      assertEquals(12762, cells[100830 to housing(FigureArrangement.OFF_CAMPUS)])
      assertEquals(4545, cells[100830 to other(FigureArrangement.ON_CAMPUS)])
      assertEquals(4545, cells[100830 to other(FigureArrangement.OFF_CAMPUS)])
      assertEquals(4545, cells[100830 to other(FigureArrangement.WITH_FAMILY)])

      // Ventura: a community college. The ON-CAMPUS pair is the NA sentinel and
      // the off-campus pair is published -- the real shape that makes "not
      // reported" a per-arrangement fact rather than a per-school one.
      assertNull(cells[125028 to housing(FigureArrangement.ON_CAMPUS)], "NA is not reported, never 0")
      assertNull(cells[125028 to other(FigureArrangement.ON_CAMPUS)])
      assertEquals(22086, cells[125028 to housing(FigureArrangement.OFF_CAMPUS)])
      assertEquals(4968, cells[125028 to other(FigureArrangement.OFF_CAMPUS)])
      assertEquals(4059, cells[125028 to other(FigureArrangement.WITH_FAMILY)])
      assertEquals(1062, cells[125028 to BOOKS])

      // Pensacola Christian: all six are NA -- a school that reports no
      // components at all, and so gets no breakdown rather than a zeroed one.
      assertNull(cells[136455 to BOOKS])
      assertNull(cells[136455 to housing(FigureArrangement.ON_CAMPUS)])
      assertNull(cells[136455 to housing(FigureArrangement.OFF_CAMPUS)])
      assertNull(cells[136455 to other(FigureArrangement.ON_CAMPUS)])
      assertNull(cells[136455 to other(FigureArrangement.OFF_CAMPUS)])
      assertNull(cells[136455 to other(FigureArrangement.WITH_FAMILY)])
    }

  @Test
  fun `out-of-domain optional locale is nulled, institution kept (mechanism A)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      // Pensacola Christian (136455) has LOCALE=2, outside the 11..43 domain.
      val pensacola = withSession { CollegesDao.findByIpedsUnitId(it, 136455).getOrThrow() }
      assertNotNull(pensacola)
      assertNull(pensacola.locale)
      // A valid required field is retained — the row was kept, not dropped.
      assertEquals(2, pensacola.control)
      assertEquals(1, result.fieldsCoercedToNull["locale"])
    }

  @Test
  fun `opeid loaded from real OPEID column (item 3)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      assertEquals("00831000", auburn.opeid)
    }

  @Test
  fun `4-digit and 6-digit CIP programs load (guards 0021)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val cips = withSession { programCipCodes(it, auburn.id.asString) }
      // 4-digit family codes the old six-only CHECK would have rejected.
      assertTrue("0301" in cips, "expected 4-digit CIP 0301, got $cips")
      assertTrue("0901" in cips, "expected 4-digit CIP 0901, got $cips")
    }

  @Test
  fun `quoted embedded comma in CIPDESC parses intact`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val title =
        withSession { programTitle(it, auburn.id.asString, "1101", 3) }
      assertEquals("Computer and Information Sciences, General.", title)
    }

  @Test
  fun `credlev 99 row is skipped and counted, neighbors survive (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val cips = withSession { programCipCodes(it, auburn.id.asString) }
      // The CIPCODE=2601 / CREDLEV=99 program is absent.
      assertTrue("2601" !in cips, "expected the CREDLEV=99 program absent, got $cips")
      assertTrue(
        (result.skipsByReason[SkipReason.CredentialLevelOutOfDomain] ?: 0) >= 1,
      )
      // The other Auburn programs still load.
      assertTrue("0301" in cips && "0901" in cips && "1101" in cips)
    }

  @Test
  fun `UNITID=NA rows are skipped and counted, not silently lost (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertTrue(
        (result.skipsByReason[SkipReason.IpedsUnitIdNa] ?: 0) >= 1,
      )
      // No college or program was synthesized for the OPEID6-keyed NA rows.
      val judson = withSession { CollegesDao.findByIpedsUnitId(it, 1023).getOrThrow() }
      assertNull(judson)
    }

  @Test
  fun `Ventura program links to its own college row`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val ventura = withSession { CollegesDao.findByIpedsUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      val owner = withSession { programCollegeId(it, "0101", 2) }
      assertEquals(ventura.id.asString, owner)
    }

  @Test
  fun `summary has no transient skips against clean real data`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertEquals(0, result.skipsByReason[SkipReason.Transient] ?: 0)
    }

  @Test
  fun `re-running the real-data load is idempotent`() =
    runBlocking {
      val first = loader.load(institutionCsv, fieldsCsv)
      val second = loader.load(institutionCsv, fieldsCsv)
      assertEquals(first.collegesLoaded, second.collegesLoaded)
      assertEquals(first.programsLoaded, second.programsLoaded)
      assertEquals(first.collegesLoaded, withSession { count(it, "colleges") })
      assertEquals(first.programsLoaded, withSession { count(it, "college_programs") })
    }

  @Test
  fun `COSTT4_A sits inside the IN-STATE arrangement span at every public row we hold (RFC 157)`() =
    runBlocking {
      // The falsifier for RFC 157, run over the real rows this repo actually
      // commits rather than over numbers a fixture chose.
      //
      // COSTT4_A is a weighted average of the three living-arrangement totals,
      // and the Scorecard builds it for students paying the IN-STATE rate. A
      // weighted average cannot fall outside the span of its own inputs, so at
      // every CONTROL=1 row that publishes all seven parts, COSTT4_A must lie
      // between the smallest and the largest IN-STATE total. One row outside
      // that span would break the arithmetic argument the whole RFC rests on.
      //
      // PORTED to the canonical cells by RFC 176, not deleted: the seven parts
      // used to be read off `colleges`, which no longer carries money. They are
      // read here from where the same ingest run now writes them -- COSTT4_A
      // from `cohort_money_stats` at `published_cost_blend`, the six parts from
      // `price_figures` -- so this stays what it always was: the proof the
      // ingest saw REAL data, and the falsifier for RFC 157's basis claim.
      //
      // Only rows with all seven parts qualify: a row missing an arrangement
      // (Ventura College publishes no on-campus figures) has no span to test.
      loader.load(institutionCsv, fieldsCsv)
      canonicalMoneyLoader.fill(institutionCsv, sfa = null)

      val blends = publishedCostBlendsByUnit()
      assertTrue(blends.isNotEmpty(), "the real fixture must have filled some blends for this scan to mean anything")

      val cells = componentAmountsByUnit()
      val rows =
        query("SELECT ipeds_unit_id, name, control FROM colleges") { rs ->
          Triple(rs.getInt("ipeds_unit_id"), rs.getString("name"), rs.getInt("control"))
        }

      val checked = mutableListOf<String>()
      rows.forEach { (unitId, name, control) ->
        val costt4a = blends[unitId]
        val inStateTuition = cells[unitId to TUITION_IN_STATE]
        val booksAmount = cells[unitId to BOOKS]
        val housingOn = cells[unitId to housing(FigureArrangement.ON_CAMPUS)]
        val housingOff = cells[unitId to housing(FigureArrangement.OFF_CAMPUS)]
        val otherOn = cells[unitId to other(FigureArrangement.ON_CAMPUS)]
        val otherOff = cells[unitId to other(FigureArrangement.OFF_CAMPUS)]
        val otherFamily = cells[unitId to other(FigureArrangement.WITH_FAMILY)]
        if (control != PUBLIC_CONTROL_CODE ||
          costt4a == null ||
          inStateTuition == null ||
          booksAmount == null ||
          housingOn == null ||
          housingOff == null ||
          otherOn == null ||
          otherOff == null ||
          otherFamily == null
        ) {
          return@forEach
        }
        val totals =
          listOf(
            inStateTuition + booksAmount + housingOn + otherOn,
            inStateTuition + booksAmount + housingOff + otherOff,
            inStateTuition + booksAmount + otherFamily,
          )
        checked.add("name=[$name] COSTT4_A=[$costt4a] in_state_totals=[$totals]")
        assertTrue(
          costt4a >= totals.min() && costt4a <= totals.max(),
          "COSTT4_A must lie inside the in-state span, or it is not built on the in-state rate: " +
            "name=[$name] COSTT4_A=[$costt4a] totals=[$totals]",
        )
      }

      // TWO qualifying public rows, not one: Auburn University at Montgomery
      // and UC San Diego (110680), the institution RFC 157 argues from. Both are
      // verbatim Scorecard rows, and the scan widens by itself the day another
      // real row is committed. A one-row falsifier is an anecdote.
      assertTrue(
        checked.size >= MIN_QUALIFYING_PUBLIC_ROWS && checked.any { it.startsWith("name=[$UCSD_NAME]") },
        "the scan must test at least the two qualifying public rows this repo commits, UC San Diego among " +
          "them, or it is an anecdote: checked=[$checked] filled_units=[${blends.keys}]",
      )
    }

  // ---------------------------------------------------------------------------
  // Canonical money read helpers (RFC 176) -- the cells the ported real-data
  // guards read, addressed by their published concept rather than by a column.
  // ---------------------------------------------------------------------------

  /** `(ipeds_unit_id, income_band)` -> the average net price, whole dollars; absent when no value is borne. */
  private fun netPricesByUnit(): Map<Pair<Int, String?>, Int> =
    query(
      "SELECT c.ipeds_unit_id, s.income_band, s.value FROM cohort_money_stats s " +
        "JOIN colleges c ON c.id = s.college_id " +
        "WHERE s.measure = '${MoneyMeasure.AVG_NET_PRICE.value}' AND s.value IS NOT NULL",
    ) { rs -> (rs.getInt("ipeds_unit_id") to rs.getString("income_band")) to rs.getDouble("value").toInt() }.toMap()

  /** `ipeds_unit_id` -> COSTT4_A, the published cost blend in whole dollars; absent when no value is borne. */
  private fun publishedCostBlendsByUnit(): Map<Int, Int> =
    query(
      "SELECT c.ipeds_unit_id, s.value FROM cohort_money_stats s " +
        "JOIN colleges c ON c.id = s.college_id " +
        "WHERE s.measure = '${MoneyMeasure.PUBLISHED_COST_BLEND.value}' AND s.value IS NOT NULL",
    ) { rs -> rs.getInt("ipeds_unit_id") to rs.getDouble("value").toInt() }.toMap()

  /**
   * `(ipeds_unit_id, concept/residency/arrangement)` -> the published amount;
   * absent when the row bears no value, which is what an `NA` cell becomes.
   */
  private fun componentAmountsByUnit(): Map<Pair<Int, String>, Int> =
    query(
      "SELECT c.ipeds_unit_id, p.price_concept, p.residency_basis, p.arrangement, p.amount_usd " +
        "FROM price_figures p JOIN colleges c ON c.id = p.college_id WHERE p.amount_usd IS NOT NULL",
    ) { rs ->
      val address = rs.getString("price_concept") + "/" + rs.getString("residency_basis") + "/" + rs.getString("arrangement")
      (rs.getInt("ipeds_unit_id") to address) to rs.getInt("amount_usd")
    }.toMap()

  // ---------------------------------------------------------------------------
  // Query helpers (no DAO program-read path exists; read the table directly)
  // ---------------------------------------------------------------------------

  private companion object {
    /**
     * Scorecard `CONTROL` for a public institution -- the only control for which
     * the in-state basis is a distinction at all -- read from the vocabulary's
     * one home ([InstitutionControl]) and never restated here.
     */
    val PUBLIC_CONTROL_CODE = InstitutionControl.PUBLIC.code

    /**
     * The `price_figures` addresses the ported RFC 149/157 guards read, spelled
     * from the house enums. Tuition is the only one that carries a residency
     * basis; the four component concepts have none, which is exactly why they
     * sit on `not_applicable` rather than on the tuition's basis.
     */
    val TUITION_IN_STATE =
      "${PriceConcept.TUITION_AND_FEES.value}/${ResidencyBasis.IN_STATE.value}/${FigureArrangement.NOT_APPLICABLE.value}"

    val BOOKS =
      "${PriceConcept.BOOKS_AND_SUPPLIES.value}/${ResidencyBasis.NOT_APPLICABLE.value}/${FigureArrangement.NOT_APPLICABLE.value}"

    fun housing(arrangement: FigureArrangement) =
      "${PriceConcept.HOUSING_AND_FOOD.value}/${ResidencyBasis.NOT_APPLICABLE.value}/${arrangement.value}"

    fun other(arrangement: FigureArrangement) =
      "${PriceConcept.OTHER_EXPENSES.value}/${ResidencyBasis.NOT_APPLICABLE.value}/${arrangement.value}"

    /**
     * The qualifying public rows this repo commits today: Auburn University at
     * Montgomery and UC San Diego. A FLOOR, not an equality -- the scan widens
     * by itself the day another real public row lands.
     */
    const val MIN_QUALIFYING_PUBLIC_ROWS = 2

    /** The institution RFC 157 argues from, which must be one of the rows the scan reaches. */
    const val UCSD_NAME = "University of California-San Diego"
  }

  private fun programCipCodes(
    session: SqlSession,
    collegeId: String,
  ): Set<String> =
    session.prepareStatement("SELECT cip_code FROM college_programs WHERE college_id = ?::uuid").use { stmt ->
      stmt.setString(1, collegeId)
      stmt.executeQuery().use { rs ->
        val out = mutableSetOf<String>()
        while (rs.next()) out.add(rs.getString(1))
        out
      }
    }

  private fun programTitle(
    session: SqlSession,
    collegeId: String,
    cipCode: String,
    credentialLevel: Int,
  ): String? =
    session
      .prepareStatement(
        "SELECT cip_title FROM college_programs " +
          "WHERE college_id = ?::uuid AND cip_code = ? AND credential_level = ?",
      ).use { stmt ->
        stmt.setString(1, collegeId)
        stmt.setString(2, cipCode)
        stmt.setInt(3, credentialLevel)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }

  private fun programCollegeId(
    session: SqlSession,
    cipCode: String,
    credentialLevel: Int,
  ): String? =
    session
      .prepareStatement(
        "SELECT college_id FROM college_programs WHERE cip_code = ? AND credential_level = ?",
      ).use { stmt ->
        stmt.setString(1, cipCode)
        stmt.setInt(2, credentialLevel)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }
}
