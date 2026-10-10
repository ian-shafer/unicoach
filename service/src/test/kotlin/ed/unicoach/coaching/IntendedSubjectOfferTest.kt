package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.college.TeachesYourField
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.IntendedSubjectsDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.IntendedSubjectName
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewSubject
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored field of study on the search door (RFC 245 A2): the four answer
 * states and the failed read, one test each, through the REAL
 * [IntendedSubjectsService] against Postgres.
 *
 * It is a file of its own rather than more of [CollegeChatToolTest] because the
 * fixture is a different one: these cases need a taxonomy row and a programme
 * census, so the one seeded school can actually be LABELLED, which the
 * residency cases next door have no use for.
 *
 * The three things every case here asserts are the three the slice promises:
 * the offer is made in exactly one state, the label appears in exactly one
 * state, and NOTHING about the page itself changes in any of them.
 */
class IntendedSubjectOfferTest {
  private val database = CoachingTestDb.database

  /** The subject this student stores. */
  private val subjectSlug = "registered-nursing"

  /**
   * The taxonomy prefix [subjectSlug] is published under, and a six-digit census
   * code INSIDE it.
   *
   * The containment is this fixture's whole premise: the index materialises
   * `subject_slugs` by expanding the prefix over the census code, so a pair that
   * did not nest would label the one seeded school `unknown` and prove nothing.
   * Built from the prefix rather than stated beside it, so the relation is
   * executed instead of checked by eye.
   */
  private val subjectCipPrefix = "51"

  private val censusCipCode = subjectCipPrefix + "3801"

  private val moneyProfiles = MoneyProfileService(database)
  private val intendedSubjects = IntendedSubjectsService(database, SubjectVocabularyService(database))

  /**
   * The chat-free tool, on a codebook LOADED after this suite's taxonomy row
   * exists (RFC 245 tier 1).
   *
   * It cannot be an empty codebook: a stored subject is only judgeable against
   * the ACTIVE taxonomy, because `college_search_index.subject_slugs` is
   * materialised through that same taxonomy -- so a tool holding no taxonomy at
   * all labels nothing, which is the truthful answer and not the one these cases
   * are about.
   */
  private lateinit var wrapped: CollegeSearchTool

  private lateinit var adapter: CollegeChatTool

  /**
   * One school whose programme census names this student's subject, and the
   * index rebuilt over it.
   *
   * Nothing writes `college_search_index` directly -- only the rebuild does, and
   * `subject_slugs` is materialised from `college_programs_census` through the
   * ACTIVE taxonomy, so a test that seeded the census and skipped either of
   * those would label a school `unknown` and prove nothing.
   */
  @BeforeEach
  fun seedLabellableSchool() {
    resetStudentAndCollegeTables()
    seedSchoolTeachingTheStoredSubject()
    wireSearchDoorOnLoadedTaxonomy()
  }

  private fun resetStudentAndCollegeTables() {
    CoachingTestDb.truncate(
      "college_programs_census",
      "colleges",
      "subjects",
      "intended_subjects",
      "intended_subject_status",
      "money_profiles",
      "students",
      "users",
    )
    CodebookReferenceFixture.seed(CoachingTestDb.sqlSession)
  }

  /**
   * One school whose programme census names [subjectSlug], THROUGH the rebuild:
   * nothing writes `college_search_index` directly, and `subject_slugs` is
   * materialised from `college_programs_census` by expanding the taxonomy's own
   * prefix over the census code.
   */
  private fun seedSchoolTeachingTheStoredSubject() {
    val college = CollegesDao.upsert(CoachingTestDb.sqlSession, newCollege()).getOrThrow()
    CodebooksDao
      .upsertSubject(CoachingTestDb.sqlSession, NewSubject(subjectSlug, "Nursing", listOf(subjectCipPrefix)))
      .getOrThrow()
    CollegeIpedsDao
      .upsertProgramsCensus(
        CoachingTestDb.sqlSession,
        NewCollegeProgramsCensus(college.id, censusCipCode, 5, 12, 2023),
      ).getOrThrow()
    CollegesDao.rebuildSearchIndex(CoachingTestDb.sqlSession).getOrThrow()
  }

  /** The two doors under test, on a codebook loaded AFTER the taxonomy row exists. */
  private fun wireSearchDoorOnLoadedTaxonomy() {
    wrapped = CollegeSearchTool(CollegeSearchService(database), loadCodebook())
    adapter = CollegeChatTool(wrapped, moneyProfiles::getForStudent, intendedSubjects::getForStudent)
  }

  private fun loadCodebook() =
    runBlocking {
      ed.unicoach.college.Codebook
        .load(database)
        .getOrThrow()
    }

  @Test
  fun `a student nobody has asked gets the invitation, and the very same page`() =
    runBlocking {
      // UNANSWERED is the ONE offerable state, and the offer is a sibling of the
      // page rather than a condition on it: the colleges array is compared with
      // the chat-free tool's own, so an offer that had quietly cost a row, a
      // figure or an order fails here.
      val student = CoachingTestDb.createStudent("field-unanswered")
      assertEquals(
        AnswerStatus.UNANSWERED,
        intendedSubjects.getForStudent(student).getOrThrow().status,
        "the premise: nobody has asked this student",
      )

      val result = adapter.execute(student, buildJsonObject { })

      assertEquals(
        IntendedSubjectOffer.FIELD,
        result[IntendedSubjectOffer.KEY]!!
          .jsonObject["field"]!!
          .jsonPrimitive.content,
      )
      assertEquals(
        wrapped.execute(buildJsonObject { }, null, emptySet())["colleges"],
        result["colleges"],
        "the invitation never gates and never edits: $result",
      )
      assertNoLabel(result)
    }

  @Test
  fun `a stored answer labels the rows and offers nothing`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("field-answered")
      store(student, IntendedSubjectsUpdate.Subjects(listOf(subjectSlug)))

      val result = adapter.execute(student, buildJsonObject { })
      assertNull(result["error"], "$result")

      val row = rowsOf(result).single()
      assertEquals(
        TeachesYourField.YES,
        row[TeachesYourField.WIRE_KEY]!!.jsonPrimitive.content,
        "the school's census names this student's own subject: $row",
      )
      assertEquals(
        listOf(subjectSlug),
        (row[TeachesYourField.SUBJECTS_TAUGHT_WIRE_KEY] as JsonArray).map { it.jsonPrimitive.content },
        "and the row says WHICH subject matched: $row",
      )
      // They told us. There is nothing left to invite, and no reason to report.
      assertNull(result[IntendedSubjectOffer.KEY], "$result")
      assertNull(result[IntendedSubjectOffer.UNAVAILABLE_KEY], "$result")
    }

  @Test
  fun `a remembered decline is never asked again, and labels nothing`() =
    runBlocking {
      // RFC 169's rule, on this answer: the decline is STORED, so the offer is
      // keyed on the status and not on the subject list being empty. A page that
      // re-offered here would be re-raising a closed topic on every search.
      val student = CoachingTestDb.createStudent("field-declined")
      store(student, IntendedSubjectsUpdate.Declined)

      val result = adapter.execute(student, buildJsonObject { })
      assertNull(result["error"], "$result")
      assertNotNull(result["colleges"], "$result")
      assertNull(result[IntendedSubjectOffer.KEY], "a decline is an answer: $result")
      assertNull(
        result[IntendedSubjectOffer.UNAVAILABLE_KEY],
        "and saying it back on every page is asking again in all but name: $result",
      )
      assertNoLabel(result)
    }

  @Test
  fun `I do not know yet is an answer too, and falls into neither default`() =
    runBlocking {
      // The state residency does not have (RFC 205), which is why it is proved
      // on its own: UNDECIDED is not offerable -- asking again is what the coach
      // prompt forbids -- and there is no subject to label against either.
      val student = CoachingTestDb.createStudent("field-undecided")
      store(student, IntendedSubjectsUpdate.Undecided)
      assertEquals(
        AnswerStatus.UNDECIDED,
        intendedSubjects.getForStudent(student).getOrThrow().status,
        "the premise: the branch under test is UNDECIDED and not a silence",
      )

      val result = adapter.execute(student, buildJsonObject { })
      assertNull(result["error"], "$result")
      assertNotNull(result["colleges"], "$result")
      assertNull(result[IntendedSubjectOffer.KEY], "$result")
      assertNull(result[IntendedSubjectOffer.UNAVAILABLE_KEY], "$result")
      assertNoLabel(result)
    }

  @Test
  fun `a failed subject read leaves the search working, unlabelled and uninvited`() =
    runBlocking {
      // The branch a Postgres-backed test cannot ask for on demand, which is why
      // the read is a seam. A database fault on a SIDE read must never turn a
      // working search into an error -- and must never be reported as "nobody
      // asked", because we do not know what this student said.
      val student = CoachingTestDb.createStudent("field-read-failed")
      store(student, IntendedSubjectsUpdate.Subjects(listOf(subjectSlug)))
      val faulting =
        CollegeChatTool(wrapped, moneyProfiles::getForStudent, {
          Result.failure(IllegalStateException("the intended-subjects read failed"))
        })

      val result = faulting.execute(student, buildJsonObject { })
      assertNull(result["error"], "a side read that failed is not a failed search: $result")
      assertNotNull(result["colleges"], "$result")
      assertNoLabel(result)
      assertNull(result[IntendedSubjectOffer.KEY], "a fault never invites the question: $result")

      val note = result[IntendedSubjectOffer.UNAVAILABLE_KEY]!!.jsonPrimitive.content
      assertTrue(note.contains("could not be read"), note)
      assertTrue(note.contains("we do not know whether we do"), "it says what is TRUE for a fault: [$note]")
    }

  @Test
  fun `rows that disagree with their own status leave the search working, and are never reported as a decline`() =
    runBlocking {
      // Both of this table's invariants are DEFERRED constraint triggers, so a
      // hand-edited row or a second concurrent writer can land an answered
      // status holding more subjects than the cap. `Result.fold` does not catch
      // what its success arm throws, so building the stored field straight out
      // of such a row would turn a SIDE read into a failed search.
      val student = CoachingTestDb.createStudent("field-corrupt-rows")
      val overCap =
        CollegeChatTool(wrapped, moneyProfiles::getForStudent) { id ->
          Result.success(
            IntendedSubjectAnswer(
              id,
              AnswerStatus.ANSWERED,
              (1..IntendedSubjectsDao.MAX_SUBJECTS + 1).map { IntendedSubjectName("subject-$it", "Subject $it") },
            ),
          )
        }

      val result = overCap.execute(student, buildJsonObject { })

      assertNull(result["error"], "two tables disagreeing is not a failed search: $result")
      assertNoLabel(result)
      assertNull(result[IntendedSubjectOffer.KEY], "corruption of ours never invites the question: $result")

      val note = result[IntendedSubjectOffer.UNAVAILABLE_KEY]!!.jsonPrimitive.content
      assertTrue(note.contains("could not be used"), note)
      assertTrue(note.contains("Do not tell them we hold none"), "it never speaks our fault as their answer: [$note]")
    }

  private fun rowsOf(result: JsonObject): List<JsonObject> = result.getValue("colleges").jsonArray.map { it.jsonObject }

  /** No row carries either of RFC 245's two keys -- D20's byte-identical page. */
  private fun assertNoLabel(result: JsonObject) {
    val rows = rowsOf(result)
    assertTrue(rows.isNotEmpty(), "the premise: there is a row that COULD have been labelled: $result")
    for (row in rows) {
      assertFalse(row.containsKey(TeachesYourField.WIRE_KEY), "$row")
      assertFalse(row.containsKey(TeachesYourField.SUBJECTS_TAUGHT_WIRE_KEY), "$row")
    }
  }

  private suspend fun store(
    student: StudentId,
    update: IntendedSubjectsUpdate,
  ) {
    val outcome = intendedSubjects.replace(student, update).getOrThrow()
    assertTrue(outcome is UpdateIntendedSubjectsResult.Success, "the fixture's own write must be stored: $outcome")
  }

  private fun newCollege() =
    NewCollege(
      ipedsUnitId = 909092,
      opeid = null,
      name = "Field Label University",
      city = "Testville",
      state = "NV",
      region = null,
      locale = null,
      latitude = null,
      longitude = null,
      control = 1,
      undergradEnrollmentHeadcount = 5000,
      admissionRateShare = 0.5,
      satAverageEquivalentScore = 1200,
      satReading25thPercentileScore = null,
      satReading75thPercentileScore = null,
      satMath25thPercentileScore = null,
      satMath75thPercentileScore = null,
      actComposite25thPercentileScore = null,
      actComposite75thPercentileScore = null,
      completionRate150pct4yrShare = 0.7,
      completionRate8yrShare = null,
      completionRate8yrPellShare = null,
      completionRate8yrNonPellShare = null,
      transferOutRate4yrShare = null,
      website = null,
    )
}
