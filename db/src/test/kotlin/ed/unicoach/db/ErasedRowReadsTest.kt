package ed.unicoach.db

import ed.unicoach.db.dao.ClaimSupportDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegeListEntrySupportDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.ExtractionRunsDao
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.SharingPermissionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.FitLensRunId
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.SharingPermissionId
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.SynthesisRunId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every Kotlin read that can meet an erased row omits it (RFC 266 §8, decision
 * D-I). Before this slice a row mapper read the account key and the personal
 * columns as set, so ONE erased row failed a whole admin list, a job's read,
 * or the App Store webhook. After erasing [ErasureFixture]'s account, each read
 * in §8's table is called with an erased row's id (it answers as for an absent
 * row) or over the whole table (it succeeds and leaves the erased rows out).
 *
 * One transaction per test, rolled back.
 */
class ErasedRowReadsTest {
  companion object {
    private lateinit var connection: Connection

    /** Large enough that "over the whole table" is the whole table on a test database. */
    private const val ALL_ROWS = 100_000

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun begin() {
    connection.autoCommit = false
  }

  @AfterEach
  fun rollback() {
    connection.rollback()
    connection.autoCommit = true
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun ids(
    sql: String,
    vararg args: Any?,
  ): List<String> =
    connection.prepareStatement(sql).use { stmt ->
      args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
      stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

  /** The ids of [table]'s rows erased with the account's student (key moved onto deleted_students). */
  private fun erasedOf(
    table: String,
    studentId: UUID,
  ): List<String> =
    ids("SELECT id::text FROM $table WHERE deleted_student_id = ?", studentId).also {
      assertTrue(it.isNotEmpty(), "the fixture must hold an erased $table row")
    }

  private fun <T> assertAbsent(
    read: String,
    result: Result<T>,
  ) {
    val failure = result.exceptionOrNull() ?: fail("$read must not find an erased row; it returned [${result.getOrNull()}]")
    assertTrue(failure is NotFoundException, "$read must answer an erased row as absent; it failed with [$failure]")
  }

  private fun <T> assertOmits(
    read: String,
    result: Result<List<T>>,
    erased: Collection<String>,
    id: (T) -> String,
  ) {
    val rows = result.getOrElse { fail("$read must succeed over erased rows; it failed with [$it]") }
    val leaked = rows.map(id).filter { it in erased }
    assertEquals(emptyList(), leaked, "$read must omit erased rows")
  }

  @Test
  fun `every read that can meet an erased row omits it`() {
    val account = ErasureFixture.build(session)
    ErasureFixture.close(session, account.userId)
    ids("SELECT table_name FROM erase_account(?)", account.userId.value)
    val user = account.userId
    val student = account.studentId.value

    // users
    assertAbsent("UsersDao.findById", UsersDao.findById(session, user, SoftDeleteScope.ALL))
    assertNull(UsersDao.findByIdOrNull(session, user, SoftDeleteScope.ALL).getOrThrow())
    assertAbsent("UsersDao.findByIdForUpdate", UsersDao.findByIdForUpdate(session, user, SoftDeleteScope.ALL))
    assertOmits("UsersDao.list", UsersDao.list(session, SoftDeleteScope.ALL, ALL_ROWS, 0), listOf(user.value.toString())) {
      it.id.value.toString()
    }
    assertAbsent("UsersDao.findVersion", UsersDao.findVersion(session, user, 1))
    assertEquals(emptyList(), UsersDao.listVersions(session, user).getOrThrow())

    // students, supporters
    assertAbsent("StudentsDao.findById", StudentsDao.findById(session, account.studentId, SoftDeleteScope.ALL))
    assertNull(StudentsDao.findByIdOrNull(session, account.studentId, SoftDeleteScope.ALL).getOrThrow())
    assertAbsent("StudentsDao.findByIdForUpdate", StudentsDao.findByIdForUpdate(session, account.studentId))
    assertOmits("StudentsDao.list", StudentsDao.list(session, SoftDeleteScope.ALL, ALL_ROWS, 0), listOf(student.toString())) {
      it.id.value.toString()
    }
    assertEquals(emptyList(), StudentsDao.listVersions(session, account.studentId).getOrThrow())
    assertAbsent("SupportersDao.findById", SupportersDao.findById(session, account.supporterId, SoftDeleteScope.ALL))

    // conversations: the student's own and the supporter's, both erased
    val convos = listOf(account.studentConvoId, account.supporterConvoId)
    val convoIds = convos.map { it.value.toString() }
    convos.forEach { convo ->
      assertAbsent("ConvosDao.findById", ConvosDao.findById(session, convo, SoftDeleteScope.ALL))
      assertAbsent("ConvosDao.findByIdWithActivity", ConvosDao.findByIdWithActivity(session, convo, SoftDeleteScope.ALL))
      assertEquals(emptyList(), ConvosDao.listTurns(session, convo, SoftDeleteScope.ALL).getOrThrow(), "ConvosDao.listTurns")
    }
    assertOmits("ConvosDao.listWithActivity", ConvosDao.listWithActivity(session, SoftDeleteScope.ALL, ALL_ROWS, 0), convoIds) {
      it.convo.id.value
        .toString()
    }
    val requests =
      ids(
        "SELECT id::text FROM convo_requests WHERE convo_id = ANY (?)",
        connection.createArrayOf(
          "uuid",
          convos
            .map {
              it.value
            }.toTypedArray(),
        ),
      )
    assertTrue(requests.isNotEmpty())
    assertOmits("ConvosDao.listTurns", ConvosDao.listTurns(session, SoftDeleteScope.ALL, ALL_ROWS, 0), requests) {
      it.request.id.value
        .toString()
    }
    requests.forEach {
      assertAbsent(
        "ConvosDao.findTurnByRequestId",
        ConvosDao.findTurnByRequestId(session, ConvoRequestId(it.toLong()), SoftDeleteScope.ALL),
      )
    }

    // the student-keyed tables
    val claims = erasedOf("claims", student)
    claims.forEach { assertAbsent("ClaimsDao.findById", ClaimsDao.findById(session, ClaimId(UUID.fromString(it)))) }
    assertOmits("ClaimsDao.list", ClaimsDao.list(session, ALL_ROWS, 0), claims) { it.id.value.toString() }

    val observations = erasedOf("observations", student)
    observations.forEach { assertAbsent("ObservationsDao.findById", ObservationsDao.findById(session, ObservationId(it.toLong()))) }
    assertOmits("ObservationsDao.list", ObservationsDao.list(session, ALL_ROWS, 0), observations) { it.id.value.toString() }
    assertEquals(
      emptyList(),
      ObservationsDao.listByConvoRange(session, account.studentConvoId, ConvoRequestId(0), ConvoRequestId(Long.MAX_VALUE)).getOrThrow(),
      "ObservationsDao.listByConvoRange",
    )

    val commitments = erasedOf("commitments", student)
    commitments.forEach { assertAbsent("CommitmentsDao.findById", CommitmentsDao.findById(session, CommitmentId(UUID.fromString(it)))) }
    assertOmits("CommitmentsDao.list", CommitmentsDao.list(session, ALL_ROWS, 0), commitments) { it.id.value.toString() }

    val entries = erasedOf("college_list_entries", student)
    entries.forEach {
      val id = CollegeListEntryId(UUID.fromString(it))
      assertAbsent("CollegeListEntriesDao.findById", CollegeListEntriesDao.findById(session, id, SoftDeleteScope.ALL))
      assertEquals(emptyList(), CollegeListEntriesDao.listVersions(session, id).getOrThrow(), "CollegeListEntriesDao.listVersions")
    }
    assertOmits("CollegeListEntriesDao.list", CollegeListEntriesDao.list(session, SoftDeleteScope.ALL, ALL_ROWS, 0), entries) {
      it.id.value.toString()
    }

    val suggestions = erasedOf("fit_suggestions", student)
    suggestions.forEach {
      assertAbsent("FitSuggestionsDao.findById", FitSuggestionsDao.findById(session, FitSuggestionId(UUID.fromString(it))))
    }
    assertOmits("FitSuggestionsDao.list", FitSuggestionsDao.list(session, ALL_ROWS, 0), suggestions) { it.id.value.toString() }

    val profiles = erasedOf("money_profiles", student)
    profiles.forEach {
      val id = MoneyProfileId(UUID.fromString(it))
      assertAbsent("MoneyProfilesDao.findById", MoneyProfilesDao.findById(session, id, SoftDeleteScope.ALL))
      assertEquals(emptyList(), MoneyProfilesDao.listVersions(session, id).getOrThrow(), "MoneyProfilesDao.listVersions")
    }
    assertOmits(
      "MoneyProfilesDao.list",
      MoneyProfilesDao.list(session, SoftDeleteScope.ALL, ALL_ROWS, 0),
      profiles,
    ) { it.id.value.toString() }

    val extractions = erasedOf("extraction_runs", student)
    extractions.forEach { assertAbsent("ExtractionRunsDao.findById", ExtractionRunsDao.findById(session, ExtractionRunId(it.toLong()))) }
    assertOmits("ExtractionRunsDao.list", ExtractionRunsDao.list(session, ALL_ROWS, 0), extractions) { it.id.value.toString() }

    val syntheses = erasedOf("synthesis_runs", student)
    syntheses.forEach { assertAbsent("SynthesisRunsDao.findById", SynthesisRunsDao.findById(session, SynthesisRunId(it.toLong()))) }
    assertOmits("SynthesisRunsDao.list", SynthesisRunsDao.list(session, ALL_ROWS, 0), syntheses) { it.id.value.toString() }

    val lenses = erasedOf("fit_lens_runs", student)
    lenses.forEach { assertAbsent("FitLensRunsDao.findById", FitLensRunsDao.findById(session, FitLensRunId(it.toLong()))) }
    assertOmits("FitLensRunsDao.list", FitLensRunsDao.list(session, ALL_ROWS, 0), lenses) { it.id.value.toString() }

    erasedOf("sharing_permissions", student).forEach {
      assertEquals(emptyList(), SharingPermissionsDao.listVersions(session, SharingPermissionId(UUID.fromString(it))).getOrThrow())
    }

    // the support tables, read by the other row's id
    claims.forEach {
      val id = ClaimId(UUID.fromString(it))
      assertEquals(
        emptyList(),
        ClaimSupportDao.listObservationsForClaim(session, id).getOrThrow(),
        "ClaimSupportDao.listObservationsForClaim",
      )
      assertEquals(
        emptyList(),
        CommitmentSupportDao.listCommitmentsForClaim(session, id).getOrThrow(),
        "CommitmentSupportDao.listCommitmentsForClaim",
      )
    }
    observations.forEach {
      val id = ObservationId(it.toLong())
      assertEquals(
        emptyList(),
        ClaimSupportDao.listClaimsForObservation(session, id).getOrThrow(),
        "ClaimSupportDao.listClaimsForObservation",
      )
      assertEquals(
        emptyList(),
        CollegeListEntrySupportDao.listEntriesForObservation(session, id).getOrThrow(),
        "CollegeListEntrySupportDao.listEntriesForObservation",
      )
    }
    commitments.forEach {
      assertEquals(emptyList(), CommitmentSupportDao.listClaimsForCommitment(session, CommitmentId(UUID.fromString(it))).getOrThrow())
    }
    entries.forEach {
      assertEquals(
        emptyList(),
        CollegeListEntrySupportDao.listObservationsForEntry(session, CollegeListEntryId(UUID.fromString(it))).getOrThrow(),
      )
    }

    // the LLM log
    val calls = account.llmRequestIds.map { it.value.toString() }
    account.llmRequestIds.forEach { assertAbsent("LlmCallsDao.findCallByRequestId", LlmCallsDao.findCallByRequestId(session, it)) }
    assertOmits("LlmCallsDao.listCalls", LlmCallsDao.listCalls(session, ALL_ROWS, 0), calls) {
      it.request.id.value
        .toString()
    }
    assertOmits("LlmCallsDao.listUnlinkedCalls", LlmCallsDao.listUnlinkedCalls(session, Duration.ZERO, ALL_ROWS, 0), calls) {
      it.request.id.value
        .toString()
    }

    // the App Store webhook's read
    assertNull(
      SubscriptionsDao.findByOriginalTransactionId(session, account.originalTransactionId).getOrThrow(),
      "an erased account's subscription must read as unbound",
    )
  }

  @Test
  fun `a parent's conversation about an erased student still reads, naming the student`() {
    val pair = ErasureFixture.parentAndStudent(session)
    ErasureFixture.close(session, pair.studentUserId)
    ids("SELECT table_name FROM erase_account(?)", pair.studentUserId.value)

    val convo = ConvosDao.findById(session, pair.convoId, SoftDeleteScope.ACTIVE).getOrThrow()
    assertEquals(pair.studentId, convo.studentId)
    assertEquals(
      pair.convoId,
      ConvosDao
        .findByIdWithActivity(session, pair.convoId)
        .getOrThrow()
        .convo.id,
    )
    assertEquals(1, ConvosDao.listTurns(session, pair.convoId).getOrThrow().size, "the parent's turns still read")
    LlmCallsDao.findCallByRequestId(session, pair.llmRequestId).getOrThrow()
    assertTrue(ConvosDao.listWithActivity(session, SoftDeleteScope.ALL, ALL_ROWS, 0).getOrThrow().any { it.convo.id == pair.convoId })
  }
}
