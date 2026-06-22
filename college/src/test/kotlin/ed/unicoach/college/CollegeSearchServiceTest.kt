package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.NewCollege
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollegeSearchServiceTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs CASCADE").use { it.execute() }
      }
      Unit
    }

  private val service = CollegeSearchService(database)

  private fun newCollege(
    unitId: Int,
    state: String = "CA",
    netPrice: Int? = 20000,
  ) = NewCollege(
    unitId = unitId,
    opeid = null,
    name = "Test U $unitId",
    city = "Townsville",
    state = state,
    region = 8,
    locale = 13,
    latitude = null,
    longitude = null,
    control = 1,
    undergradEnrollment = unitId,
    admissionRate = 0.5,
    satAvg = null,
    costAttendance = null,
    netPrice = netPrice,
    tuitionInState = null,
    tuitionOutState = null,
    graduationRate = 0.7,
    medianEarnings = 50000,
    pctPell = 0.4,
    website = null,
  )

  private fun seed(input: NewCollege) =
    runBlocking {
      database.withConnection { session: SqlSession -> CollegesDao.upsert(session, input).getOrThrow() }
    }

  @Test
  fun `clamps limit to the supported range`() =
    runBlocking {
      for (u in 1..30) seed(newCollege(u))

      val tooMany = service.search(CollegeQuery(limit = 100)).getOrThrow()
      assertEquals(CollegeSearchService.MAX_LIMIT, tooMany.size)

      val tooFew = service.search(CollegeQuery(limit = 0)).getOrThrow()
      assertTrue(tooFew.size >= CollegeSearchService.MIN_LIMIT)
    }

  @Test
  fun `delegates filtering to the DAO and returns matches`() =
    runBlocking {
      seed(newCollege(11, state = "CA"))
      seed(newCollege(12, state = "TX"))

      val matches = service.search(CollegeQuery(states = listOf("CA"), limit = 25)).getOrThrow()
      assertEquals(listOf(11), matches.map { it.unitId })
    }

  @Test
  fun `zero matches returns an empty list, not a failure`() =
    runBlocking {
      seed(newCollege(21, state = "CA"))
      val result = service.search(CollegeQuery(states = listOf("ZZ"), limit = 25))
      assertTrue(result.isSuccess)
      assertTrue(result.getOrThrow().isEmpty())
    }
}
