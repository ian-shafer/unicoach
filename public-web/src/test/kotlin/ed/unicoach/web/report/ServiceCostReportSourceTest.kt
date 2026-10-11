package ed.unicoach.web.report

import com.typesafe.config.ConfigFactory
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.report.CostReportConfig
import ed.unicoach.coaching.report.CostReportShareService
import ed.unicoach.coaching.report.ShareCostReportOutcome
import ed.unicoach.coaching.report.ShareTokenDeriver
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SubjectTaxonomyFixture
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IntendedSubjectWrite
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.web.WebAuthTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-process report read against the real database.
 *
 * RFC 252: a share left LIVE for a closed account still renders nothing,
 * whichever of its two rows is the closed one. The close revokes the share as
 * well; this pins the read's own check, so no other path can leave a closed
 * account's college list on a parent's saved link.
 *
 * RFC 251: the student's subjects reach the page only behind a grant, and a
 * grant or a withdrawal reaches a link minted before it on its next load.
 *
 * No program money is seeded: a college that runs no matching program still
 * answers with a subject entry, so the major answer is present whenever the
 * subjects are answered and a college is on the list.
 */
class ServiceCostReportSourceTest {
  private val database = WebAuthTestSupport.database
  private val permissions = SharingPermissionService(database)
  private val source = ServiceCostReportSource(database, CollegeCostService(database), permissions)

  private val shares: CostReportShareService =
    CostReportConfig
      .from(
        ConfigFactory.parseMap(
          mapOf(
            "costReport.shareUrlBase" to "https://example.test/report",
            "costReport.shareTokenSecret" to "rfc-251-gate-test-share-token-secret-long-enough",
          ),
        ),
      ).getOrThrow()
      .let { config -> CostReportShareService(database, config, config.secret?.let(::ShareTokenDeriver)) }

  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  /** A student with one answered subject and one college on the list, and the raw token of their live link. */
  private fun sharedStudentWithSubjects(): Pair<StudentId, String> {
    val student = WebAuthTestSupport.seedStudent(WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail()))
    val slug = "$SUBJECT_SLUG_PREFIX${UUID.randomUUID().toString().take(8)}"
    runBlocking {
      database.withConnection { session ->
        SubjectTaxonomyFixture.insertSubject(session, slug, SUBJECT_NAME, "26")
        val collegeId = CollegesDao.upsert(session, newCollegeFixture(Random.nextInt(800_000, 899_999))).getOrThrow().id
        CollegeListEntriesDao
          .create(session, NewCollegeListEntry(student, collegeId, CollegeListEntryStatus.CONSIDERING, null, null))
          .getOrThrow()
        IntendedSubjectsDao.replaceAnswer(session, student, IntendedSubjectWrite.Subjects(listOf(slug))).getOrThrow()
      }
    }
    val link = runBlocking { shares.share(student).getOrThrow() }
    return student to assertIs<ShareCostReportOutcome.Link>(link).rawToken
  }

  private fun profileOnTheReport(token: String) =
    runBlocking { assertIs<CostReportOutcome.Found>(source.getByShareToken(token).getOrThrow()).profile }

  private fun programReturnOnTheReport(token: String) = profileOnTheReport(token).programReturn

  private fun answer(
    student: StudentId,
    status: SharingPermissionStatus,
  ) = runBlocking { permissions.set(student, SharingPermission.INTENDED_SUBJECTS, status).getOrThrow() }

  @Test
  fun `a student never asked shows no subjects`() {
    val (_, token) = sharedStudentWithSubjects()

    assertNull(programReturnOnTheReport(token), "silence is not consent")
  }

  @Test
  fun `a student who said no shows no subjects`() {
    val (student, token) = sharedStudentWithSubjects()
    answer(student, SharingPermissionStatus.REFUSED)

    assertNull(programReturnOnTheReport(token))
  }

  @Test
  fun `a student who said yes shows their subjects`() {
    val (student, token) = sharedStudentWithSubjects()
    answer(student, SharingPermissionStatus.GRANTED)

    assertNotNull(programReturnOnTheReport(token), "a grant puts the subjects on the page")
  }

  @Test
  fun `a grant and a withdrawal reach a link minted before them on its next load`() {
    val (student, token) = sharedStudentWithSubjects()
    assertNull(programReturnOnTheReport(token))

    answer(student, SharingPermissionStatus.GRANTED)
    assertNotNull(programReturnOnTheReport(token), "the link already sent shows the subjects after the yes")

    answer(student, SharingPermissionStatus.REFUSED)
    assertNull(programReturnOnTheReport(token), "and stops showing them after the withdrawal")
  }

  /**
   * The gate removes one field, so it is complete only while that field is the
   * profile's ONLY carrier of the student's subjects. Probed over the WHOLE
   * profile the page receives, by the subject's name and slug, with a grant as
   * the positive control: a probe that cannot see the subject once it IS
   * granted would pass the first check vacuously.
   */
  @Test
  fun `without a grant nothing on the report's profile names the student's subjects`() {
    val (student, token) = sharedStudentWithSubjects()

    val ungated = profileOnTheReport(token).toString()
    assertFalse(
      ungated.contains(SUBJECT_NAME) || ungated.contains(SUBJECT_SLUG_PREFIX),
      "no field may carry the subject unasked: [$ungated]",
    )

    answer(student, SharingPermissionStatus.GRANTED)
    val granted = profileOnTheReport(token).toString()
    assertTrue(
      granted.contains(SUBJECT_NAME) && granted.contains(SUBJECT_SLUG_PREFIX),
      "the probe must see the subject once it is granted, or the check above proves nothing: [$granted]",
    )
  }

  /** The mint shape: 43 base64url characters, the 32 bytes of a digest. */
  private fun rawToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

  /** Seeds a student with a LIVE share, proves it renders, and returns the raw token. */
  private suspend fun liveShare(studentId: StudentId): String {
    val raw = rawToken()
    database.withConnection { session ->
      val id = CostReportSharesDao.nextId(session).getOrThrow()
      CostReportSharesDao.create(session, NewCostReportShare(id, studentId, TokenHash.fromRawToken(raw))).getOrThrow()
    }
    assertIs<CostReportOutcome.Found>(source.getByShareToken(raw).getOrThrow(), "a live account's live share renders")
    return raw
  }

  @Test
  fun `a live share of a closed student row does not render`(): Unit =
    runBlocking {
      val studentId = WebAuthTestSupport.seedStudent(WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail()))
      val raw = liveShare(studentId)

      database.withConnection { session ->
        val student = StudentsDao.findById(session, studentId, SoftDeleteScope.ACTIVE).getOrThrow()
        StudentsDao.delete(session, studentId, student.version).getOrThrow()
      }

      assertEquals(CostReportOutcome.NotFound(MissReason.ACCOUNT_CLOSED), source.getByShareToken(raw).getOrThrow())
    }

  @Test
  fun `a live share of a student whose users row alone is closed does not render`(): Unit =
    runBlocking {
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val raw = liveShare(WebAuthTestSupport.seedStudent(user))

      // The operator admin's shape: the users row closed, the students row left live.
      database.withConnection { session ->
        val live = UsersDao.findById(session, user.id, SoftDeleteScope.ACTIVE).getOrThrow()
        UsersDao.delete(session, user.id, live.version).getOrThrow()
      }

      assertEquals(CostReportOutcome.NotFound(MissReason.ACCOUNT_CLOSED), source.getByShareToken(raw).getOrThrow())
    }

  private companion object {
    const val SUBJECT_NAME = "Gate Test Subject"

    /** Every seeded slug starts with this, so the probe can look for any of them. */
    const val SUBJECT_SLUG_PREFIX = "rfc251-"
  }
}
