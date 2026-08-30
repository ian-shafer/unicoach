package ed.unicoach.coaching

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewCollegeMeritAid
import ed.unicoach.db.models.StudentId
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID

/**
 * The DB scaffolding every student-scoped coaching test shares: the connection
 * and session, the statement-counting session, the truncation, and the seeders
 * whose subject matter belongs to no single tool (a student, a college-list
 * entry, a CDS merit-aid row).
 *
 * One home because `college_cost_profile` and `college_admissions_profile` read
 * overlapping tables and were otherwise keeping byte-identical copies of this
 * plumbing in [ed.unicoach.coaching.costs.CostsTestDb] and
 * [ed.unicoach.coaching.admissions.AdmissionsTestDb] -- including the merit-aid
 * seeder for the SAME table, which is the one both tools must agree about.
 * Each domain fixture still owns its own domain seeders and its own truncation
 * list; only what they genuinely share lives here.
 *
 * Resources are JVM-lifetime (lazy, never closed); `bin/test` recreates the
 * test database per run.
 */
object CoachingTestDb {
  private val dbConfig =
    DatabaseConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow(),
      ).getOrThrow()

  val database: Database by lazy { Database(dbConfig) }

  val connection: Connection by lazy {
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
  }

  val sqlSession: SqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /**
   * A session that records the SQL of every statement it prepares, so "one
   * query for the whole answer, never one per college" is an assertion rather
   * than a test name. It delegates every statement to [sqlSession], so the read
   * under test is the real read against the real database.
   */
  class CountingSession(
    private val delegate: SqlSession = sqlSession,
  ) : SqlSession {
    val prepared: MutableList<String> = mutableListOf()

    override fun prepareStatement(sql: String): PreparedStatement {
      prepared += sql
      return delegate.prepareStatement(sql)
    }
  }

  /** Truncates exactly the tables the calling suite touches, cascading through the CDS/list FKs. */
  fun truncate(vararg tables: String) {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE ${tables.joinToString(", ")} CASCADE")
    }
  }

  /** A student and the user row it hangs off; [label] only distinguishes the seeded email. */
  fun createStudent(label: String): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES " +
          "('$userId', '$label-$userId@test.com', 'Test User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
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

  /**
   * One CDS merit-aid row (RFC 148 D4/D7) -- shared because BOTH tools render
   * this same table and must render it identically. Every measure is nullable
   * on purpose: the interesting cases are a school that reports the average but
   * no freshman total, a school that reports a real 0, and a school that
   * reports the freshman total and no merit measure at all.
   */
  fun seedMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    freshmenFtTotal: Int? = 2000,
    noNeedMeritCount: Int? = 500,
    noNeedMeritAvg: Int? = 12500,
    sourceUrl: String,
    archiveUrl: String?,
  ) {
    CdsAdmissionsDao
      .upsertMeritAid(
        sqlSession,
        NewCollegeMeritAid(
          collegeId = collegeId,
          sourceYear = sourceYear,
          freshmenFtTotal = freshmenFtTotal,
          noNeedMeritCount = noNeedMeritCount,
          noNeedMeritAvg = noNeedMeritAvg,
          sourceUrl = sourceUrl,
          archiveUrl = archiveUrl,
        ),
      ).getOrThrow()
  }
}
