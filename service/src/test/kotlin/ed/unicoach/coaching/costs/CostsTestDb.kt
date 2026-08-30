package ed.unicoach.coaching.costs

import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID

/**
 * The one DB fixture for the costs tests, shared by [CollegeCostServiceTest]
 * and [CollegeCostChatToolTest]: connection/session plumbing, the per-test
 * truncation, and the student/college seeders — including the shared dollar
 * figures (40000/20000/9000...) both classes assert against. Resources are
 * JVM-lifetime (lazy, never closed); `bin/test` recreates the test database
 * per run.
 */
object CostsTestDb {
  private val dbConfig =
    DatabaseConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow(),
      ).getOrThrow()

  val database: Database by lazy { Database(dbConfig) }

  /**
   * The one writer both costs test classes seed money-profile answers through
   * — the real service, so a fixture can never record a state or a band the
   * production write path would have normalised or rejected.
   */
  val moneyProfiles: MoneyProfileService by lazy { MoneyProfileService(database) }

  val connection: Connection by lazy {
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
  }

  val sqlSession: SqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var nextIpedsUnitId = 500000

  /** The shared bracket dollar figures (`net_price_q1..q5`) [seedCollege] seeds by default — the one home both test classes read. */
  const val NET_PRICE_Q1 = 9000
  const val NET_PRICE_Q2 = 11000
  const val NET_PRICE_Q3 = 14000
  const val NET_PRICE_Q4 = 17000
  const val NET_PRICE_Q5 = 21000

  /** Truncates every table the cost read touches; each test class calls this from `@BeforeEach`. */
  fun reset() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE money_profiles, college_list_entries, colleges, students, users CASCADE")
    }
  }

  fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'costs-$userId@test.com', 'Costs User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  fun seedCollege(
    name: String,
    state: String = "CA",
    control: Int = 1,
    costAttendance: Int? = 40000,
    netPrice: Int? = 20000,
    netPriceQ1: Int? = NET_PRICE_Q1,
    netPriceQ2: Int? = NET_PRICE_Q2,
    netPriceQ3: Int? = NET_PRICE_Q3,
    netPriceQ4: Int? = NET_PRICE_Q4,
    netPriceQ5: Int? = NET_PRICE_Q5,
    tuitionInState: Int? = 12000,
    tuitionOutState: Int? = 30000,
    medianDebt: Int? = 23000,
    medianEarnings: Int? = 55000,
  ): CollegeId {
    val ipedsUnitId = nextIpedsUnitId++
    return CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          ipedsUnitId = ipedsUnitId,
          opeid = "00$ipedsUnitId",
          name = name,
          city = "Townsville",
          state = state,
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = control,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = costAttendance,
          netPrice = netPrice,
          netPriceQ1 = netPriceQ1,
          netPriceQ2 = netPriceQ2,
          netPriceQ3 = netPriceQ3,
          netPriceQ4 = netPriceQ4,
          netPriceQ5 = netPriceQ5,
          tuitionInState = tuitionInState,
          tuitionOutState = tuitionOutState,
          graduationRate = 0.7,
          medianEarnings = medianEarnings,
          medianDebt = medianDebt,
          pctPell = 0.4,
          website = "https://test$ipedsUnitId.edu",
        ),
      ).getOrThrow()
      .id
  }

  /**
   * The money-profile seeders, here rather than per test class: the cost read
   * is a fold of a college row and a money profile, so both halves of the
   * fixture belong in the same home ([CollegeCostServiceTest] and
   * [CollegeCostChatToolTest] otherwise keep byte-identical copies that drift).
   */
  fun answerBand(
    student: StudentId,
    band: IncomeBand,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(band))).getOrThrow()
  }

  fun declineBand(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }

  fun answerResidency(
    student: StudentId,
    state: String,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set(state))).getOrThrow()
  }

  fun declineResidency(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
    }

  fun addToCollegeList(
    student: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  ) {
    CollegeListEntriesDao
      .create(sqlSession, NewCollegeListEntry(student, collegeId, status, null))
      .getOrThrow()
  }
}
