package ed.unicoach.coaching.collegelist

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CollegeListServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE observations, college_list_entry_support, college_list_entries, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users, colleges CASCADE",
      )
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val service by lazy { CollegeListService(database) }

  private var unitIdCounter = 920000

  private fun createUser(emailSuffix: String = UUID.randomUUID().toString()): User {
    val email = (EmailAddress.create("cls-svc-$emailSuffix@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("Svc User") as ValidationResult.Valid).value
    val pwd = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(sqlSession, NewUser(email = email, name = name, displayName = null, passwordHash = pwd))
      .getOrThrow()
  }

  private fun createStudent(): StudentId {
    val user = createUser()
    val date = (PartialDate.parse("2028") as ValidationResult.Valid).value
    return StudentsDao.create(sqlSession, NewStudent(user.id, date)).getOrThrow().id
  }

  private fun createCollege(): CollegeId =
    CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          unitId = unitIdCounter++,
          opeid = null,
          name = "Test College",
          city = "Townsville",
          state = "CA",
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = 40000,
          netPrice = 20000,
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          pctPell = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun createConvo(studentId: StudentId): ConvoId {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    return ConvoId(convoId)
  }

  private var promptCounter = 0

  private fun appendRequest(convoId: ConvoId): ConvoRequestId {
    val promptId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'coach', ?, 'be a coach')").use { stmt ->
      stmt.setObject(1, promptId)
      stmt.setString(2, "p${promptCounter++}")
      stmt.executeUpdate()
    }
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, provider, model_requested, system_prompt_id, content, turn_id)
        VALUES (?, 'anthropic', 'claude-opus-4-8', ?, '[]'::jsonb, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, promptId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return ConvoRequestId(rs.getLong("id"))
        }
      }
  }

  /** Adds [college] to [student]'s list with no citations and returns the created entry. */
  private suspend fun addEntry(
    student: StudentId,
    college: CollegeId,
  ) = (
    service
      .addToList(student, college, CollegeListEntryStatus.CONSIDERING, null, emptyList())
      .getOrThrow() as AddToListResult.Success
  ).entry

  private fun observation(
    studentId: StudentId,
    convoId: ConvoId,
    quote: String = "I want to study engineering",
  ): ObservationId {
    val req = appendRequest(convoId)
    return ObservationsDao
      .append(sqlSession, NewObservation(studentId, convoId, req, Instant.now(), quote))
      .getOrThrow()
      .id
  }

  // --- addToList ---

  @Test
  fun `addToList happy path returns Success and links every valid citation`() =
    runTest {
      val student = createStudent()
      val convo = createConvo(student)
      val college = createCollege()
      val obs1 = observation(student, convo, "a")
      val obs2 = observation(student, convo, "b")

      val result =
        service
          .addToList(student, college, CollegeListEntryStatus.CONSIDERING, "Great fit", listOf(obs1, obs2))
          .getOrThrow()
      assertTrue(result is AddToListResult.Success)
      assertEquals(CollegeListEntryStatus.CONSIDERING, result.entry.status)

      connection.prepareStatement("SELECT COUNT(*) FROM college_list_entry_support WHERE entry_id = ?").use { stmt ->
        stmt.setObject(1, result.entry.id.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(2, rs.getInt(1))
        }
      }
    }

  @Test
  fun `addToList with a citation owned by a different student returns ObservationNotFound`() =
    runTest {
      val student = createStudent()
      val otherStudent = createStudent()
      val convo = createConvo(otherStudent)
      val college = createCollege()
      val foreignObs = observation(otherStudent, convo)

      val result = service.addToList(student, college, CollegeListEntryStatus.CONSIDERING, null, listOf(foreignObs)).getOrThrow()
      assertTrue(result is AddToListResult.ObservationNotFound)
      assertEquals(foreignObs, result.observationId)
    }

  @Test
  fun `addToList for a college already on the active list returns AlreadyOnList`() =
    runTest {
      val student = createStudent()
      val college = createCollege()
      service.addToList(student, college, CollegeListEntryStatus.CONSIDERING, null, emptyList()).getOrThrow()

      val second = service.addToList(student, college, CollegeListEntryStatus.APPLYING, null, emptyList()).getOrThrow()
      assertTrue(second is AddToListResult.AlreadyOnList)
    }

  // --- updateEntry ---

  @Test
  fun `updateEntry with a stale version returns VersionConflict`() =
    runTest {
      val student = createStudent()
      val college = createCollege()
      val entry = addEntry(student, college)

      val stale =
        service
          .updateEntry(student, entry.id, entry.version - 1, CollegeListEntryStatus.APPLYING, null, emptyList())
          .getOrThrow()
      assertTrue(stale is UpdateEntryResult.VersionConflict)
    }

  @Test
  fun `updateEntry with a wrong-owner entry id returns NotFound`() =
    runTest {
      val student = createStudent()
      val otherStudent = createStudent()
      val college = createCollege()
      val entry = addEntry(student, college)

      val result =
        service
          .updateEntry(otherStudent, entry.id, entry.version, CollegeListEntryStatus.APPLYING, null, emptyList())
          .getOrThrow()
      assertTrue(result is UpdateEntryResult.NotFound)
    }

  @Test
  fun `updateEntry adding a new citation appends without touching prior citations`() =
    runTest {
      val student = createStudent()
      val convo = createConvo(student)
      val college = createCollege()
      val obs1 = observation(student, convo, "first")
      val entry =
        (
          service
            .addToList(student, college, CollegeListEntryStatus.CONSIDERING, null, listOf(obs1))
            .getOrThrow() as AddToListResult.Success
        ).entry

      val obs2 = observation(student, convo, "second")
      val updated =
        service
          .updateEntry(student, entry.id, entry.version, CollegeListEntryStatus.APPLYING, "notes", listOf(obs2))
          .getOrThrow()
      assertTrue(updated is UpdateEntryResult.Success)

      connection.prepareStatement("SELECT observation_id FROM college_list_entry_support WHERE entry_id = ?").use { stmt ->
        stmt.setObject(1, entry.id.value)
        stmt.executeQuery().use { rs ->
          val ids = mutableSetOf<Long>()
          while (rs.next()) ids.add(rs.getLong("observation_id"))
          assertEquals(setOf(obs1.value, obs2.value), ids)
        }
      }
    }

  // --- removeFromList / getForStudent ---

  @Test
  fun `removeFromList soft-deletes and a subsequent getForStudent returns NotFound`() =
    runTest {
      val student = createStudent()
      val college = createCollege()
      val entry = addEntry(student, college)

      val removed = service.removeFromList(student, entry.id, entry.version).getOrThrow()
      assertTrue(removed is RemoveEntryResult.Success)

      val getResult = service.getForStudent(student, entry.id).getOrThrow()
      assertTrue(getResult is GetEntryResult.NotFound)
    }
}
