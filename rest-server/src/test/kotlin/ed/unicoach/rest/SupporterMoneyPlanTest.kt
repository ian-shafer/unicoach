package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeLevel
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.ValueBearingStatus
import ed.unicoach.rest.ParentCoachServer.ScriptedCoach
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The money plan end to end (RFC 261), over the SERVED routes with the real
 * database and the scripted provider seam: a parent's turn in which the model
 * calls `family_money_plan`, and the tool result the provider was actually
 * SENT -- as `llm_requests` logged it -- carries the all-years figure the
 * server derived, four times the seeded one-year price.
 */
class SupporterMoneyPlanTest {
  companion object {
    private lateinit var parentCoach: ParentCoachServer

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      parentCoach = ParentCoachServer.start()
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::parentCoach.isInitialized) parentCoach.close()
    }

    private const val TUITION_AND_FEES_IN_STATE_PER_YEAR_USD = 14_000
    private const val HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD = 12_500
    private const val BOOKS_AND_SUPPLIES_PER_YEAR_USD = 1_100
    private const val OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD = 2_400

    /** The on-campus way of living's published one-year price: the four parts it is made of. */
    private const val ON_CAMPUS_PER_YEAR_USD =
      TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        BOOKS_AND_SUPPLIES_PER_YEAR_USD + OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD

    private val PRICE_YEAR = AcademicYear(2023)
  }

  @BeforeEach
  fun reset() {
    parentCoach.coach.reset()
  }

  private val family get() = parentCoach.family

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = parentCoach.db.prepareStatement(sql)
    }

  @Test
  fun `the money plan tool result the provider is sent carries four times the seeded one-year price`() {
    runBlocking {
      val linked = family.family()
      val college = family.seedCollege("Money Plan College")
      seedOnCampusPrice(college)
      seedFourYearLevel(college)
      // The student's own doors: an on-campus plan for this school, and an
      // in-state residency, so the public school's one-year price totals.
      family.addToList(linked.student, college, "I like it")
      family.answerMoneyProfile(linked.student)
      val watermark = parentCoach.llmRequestWatermark()

      parentCoach.coach.enqueue(ScriptedCoach.toolUse("family_money_plan"))
      val streamed = parentCoach.startParentConvo(linked.parent.cookie, linked.studentId, "Write our four-year money plan for her list")
      assertEquals(HttpStatusCode.OK, streamed.status, streamed.body)
      assertEquals("message", streamed.frames.last().first, "the parent's turn completed: ${streamed.body}")

      val plan = toolResultsAbove(watermark).single { it.has("all_years_statement") }
      val entry = plan["colleges"].single { it["college_id"].asText() == college.toString() }
      val published = entry["published_price"].single()
      assertEquals(4, entry["years"].asInt())
      assertEquals("on_campus", published["way_of_living"].asText())
      assertEquals(ON_CAMPUS_PER_YEAR_USD, published["per_year_usd"].asInt())
      assertEquals(4 * ON_CAMPUS_PER_YEAR_USD, published["all_years_usd"].asInt())
    }
  }

  /** Every tool result logged in `llm_requests` above [watermark], parsed from the string the model reads. */
  private fun toolResultsAbove(watermark: Long): List<JsonNode> =
    RawSql
      .list(parentCoach.db, "SELECT content::text FROM llm_requests WHERE id > ? ORDER BY id", watermark) {
        toolResultsIn(parentCoach.json(it.getString(1)))
      }.flatten()

  private fun toolResultsIn(node: JsonNode): List<JsonNode> =
    when {
      node.isObject && node["type"]?.asText() == "tool_result" -> listOf(parentCoach.json(node["content"].asText()))
      node.isContainerNode -> node.flatMap { toolResultsIn(it) }
      else -> emptyList()
    }

  /** The four parts of the on-campus price at one academic year, at the addresses the cost read reads. */
  private fun seedOnCampusPrice(college: UUID) {
    MoneyVocabularyFixture.seed(session)
    val amounts =
      mapOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD to TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD to HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD to OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      )
    val rows =
      amounts.map { (field, amountUsd) ->
        val address = (field.figureAddress as FigureAddress.Price).address
        NewPriceFigure(
          collegeId = college,
          priceConcept = address.concept,
          residencyBasis = address.residency,
          arrangement = address.arrangement,
          academicYear = PRICE_YEAR,
          reading = FigureReading.Present(amountUsd, ValueBearingStatus.REPORTED),
          source = MoneySource.IPEDS_IC_AY,
          sourceVariable = "FIXTURE",
        )
      }
    CanonicalMoneyDao.insertPriceFigures(session, rows).getOrThrow()
  }

  /** A `college_ipeds` row saying the school is four-year, joined on the college's own unit id. */
  private fun seedFourYearLevel(college: UUID) {
    RawSql.execute(
      parentCoach.db,
      """
      INSERT INTO college_ipeds (ipeds_unit_id, survey_year, cy_active, inst_level)
      SELECT ipeds_unit_id, 2023, TRUE, ? FROM colleges WHERE id = ?
      """.trimIndent(),
      CollegeLevel.FOUR_OR_MORE_YEARS_CODE,
      college,
    )
  }
}
