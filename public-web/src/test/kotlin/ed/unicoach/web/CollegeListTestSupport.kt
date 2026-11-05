package ed.unicoach.web

import ed.unicoach.coaching.StartConvoResult
import ed.unicoach.coaching.collegelist.AddToListResult
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.NewObservation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.session.CollegeEntryDraft
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.assertIs

/**
 * What the My colleges suites (RFC 271) share: a signed-in student, colleges
 * the name search can find, entries made through the production service, and
 * reads of the rows and the session draft their assertions are about.
 */
object CollegeListTestSupport {
  private val database get() = WebAuthTestSupport.database

  /** A student who has onboarded, signed in: their student id and session token. */
  fun signedInStudent(): Pair<StudentId, String> {
    val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
    val studentId = WebAuthTestSupport.seedStudent(user)
    return studentId to WebAuthTestSupport.login(user.email.value)
  }

  /**
   * A word no other seeded college carries, letters only, so a search for it
   * matches this test's colleges alone: `colleges` is not truncated between
   * tests, and the name search forgives one keystroke.
   */
  fun uniqueWord(): String =
    "Zq" +
      UUID
        .randomUUID()
        .toString()
        .filter(Char::isLetter)
        .take(10)

  /**
   * Colleges named [names], in Townsville, CA, then the name words and the
   * search index rebuilt in the same transaction — the ingest's own phases,
   * which a college seeded behind its back needs before a search can see it.
   */
  fun seedColleges(names: List<String>): List<CollegeId> =
    runBlocking {
      database.withConnection { session ->
        CodebookReferenceFixture.seed(session)
        val ids = names.map { name -> CollegesDao.upsert(session, newCollegeFixture(uniqueIpedsUnitId(), name)).getOrThrow().id }
        CollegesDao.rebuildNameWords(session).getOrThrow()
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        ids
      }
    }

  fun seedCollege(name: String): CollegeId = seedColleges(listOf(name)).single()

  /** An ipeds unit id no other seeded college holds: `upsert` keys on it, and a collision would rename a college. */
  private fun uniqueIpedsUnitId(): Int = (UUID.randomUUID().leastSignificantBits and 0x3FFFFFFF).toInt()

  /** An entry on [studentId]'s list, made through the production service, as the coach or the iOS app makes one. */
  fun addEntry(
    studentId: StudentId,
    collegeId: CollegeId,
    reasons: String? = null,
    observationIds: List<ObservationId> = emptyList(),
  ): CollegeListEntry =
    runBlocking {
      val outcome =
        WebAuthTestSupport.collegeListService
          .addToList(studentId, collegeId, CollegeListEntryStatus.CONSIDERING, reasons, null, observationIds)
          .getOrThrow()
      assertIs<AddToListResult.Success>(outcome).entry
    }

  /** [entryId]'s row, removed or not. */
  fun storedEntry(
    studentId: StudentId,
    entryId: CollegeListEntryId,
  ): CollegeListEntry =
    runBlocking {
      database
        .withConnection { session ->
          CollegeListEntriesDao.findByIdAndStudent(session, entryId, studentId, SoftDeleteScope.ALL)
        }.getOrThrow()
    }

  /** [studentId]'s active entries, oldest first. */
  fun entriesOf(studentId: StudentId): List<CollegeListEntry> =
    runBlocking { database.withConnection { session -> CollegeListEntriesDao.listActiveByStudent(session, studentId) }.getOrThrow() }

  /** How many entries, removed or not, [studentId] has. */
  fun entryCount(studentId: StudentId): Int =
    WebAuthTestSupport.count("SELECT count(*) FROM college_list_entries WHERE student_id = '${studentId.value}'")

  /** A quote from [studentId]'s own conversation: a real one, started through the production coach. */
  fun seedQuote(
    studentId: StudentId,
    quote: String,
  ): ObservationId =
    runBlocking {
      val started =
        assertIs<StartConvoResult.Started>(WebAuthTestSupport.defaultCoachingService.startConvo(studentId, "Hello", null).getOrThrow())
      database
        .withConnection { session ->
          ObservationsDao.append(session, NewObservation(studentId, started.convo.id, started.userTurn.id, Instant.now(), quote))
        }.getOrThrow()
        .id
    }

  /** The edit form's draft the session behind [token] holds, or `null`. */
  fun entryDraftOf(token: String): CollegeEntryDraft? {
    val drafts =
      WebAuthTestSupport
        .resolveSession(token)!!
        .session.metadata.drafts
    return if (drafts.hasCollegeEntry()) drafts.collegeEntry else null
  }

  /** A GET as the session behind [token], redirects not followed. */
  suspend fun ApplicationTestBuilder.getAs(
    path: String,
    token: String,
  ): HttpResponse = noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
}
