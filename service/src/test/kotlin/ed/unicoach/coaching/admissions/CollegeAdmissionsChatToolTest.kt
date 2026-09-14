package ed.unicoach.coaching.admissions

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.college.AdmissionTestPolicy
import ed.unicoach.college.CollegeQueryVocabulary
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rendering half of RFC 148: what actually reaches the model's context.
 * Most of these tests are about a sentence rather than a value, because the
 * whole point of the slice is that a figure the coach cannot say honestly must
 * not arrive without the words that make it honest.
 */
class CollegeAdmissionsChatToolTest {
  @BeforeEach
  fun resetDatabase() {
    AdmissionsTestDb.reset()
  }

  private val tool = CollegeAdmissionsChatTool(CollegeAdmissionsService(AdmissionsTestDb.database))

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun collegesOf(result: JsonObject) = result.getValue("colleges").jsonArray.map { it.jsonObject }

  private fun execute(
    student: StudentId,
    raw: String = "{}",
  ): JsonObject = runBlocking { tool.execute(student, input(raw)) }

  private fun meritOf(college: JsonObject): JsonObject? = college[AdmissionsField.MERIT_AID.wireName]?.jsonObject

  private fun testPolicyOf(college: JsonObject): JsonObject? = college[AdmissionsField.TEST_POLICY.wireName]?.jsonObject

  private fun scoreBandsOf(college: JsonObject): JsonObject? = college[AdmissionsField.SCORE_BANDS.wireName]?.jsonObject

  private fun roundsOf(college: JsonObject) =
    college
      .getValue(AdmissionsField.DEADLINES.wireName)
      .jsonObject
      .getValue("rounds")
      .jsonArray
      .map { it.jsonObject }

  private fun availabilityOf(college: JsonObject) =
    college
      .getValue("data_availability")
      .jsonArray
      .map { it.jsonPrimitive.content }

  private fun descriptionOf(): String =
    tool.definition
      .getValue("description")
      .jsonPrimitive.content

  /** A college with every optional section populated — the fixture the guard needs. */
  private fun seedFullyReportingCollege(
    student: StudentId,
    name: String = "Wire University",
  ): CollegeId {
    // The bands are part of "fully reporting" since RFC 195: a section left
    // absent is a section the RFC 143 guard below never walks.
    val id =
      AdmissionsTestDb.seedListedCollege(
        student,
        name,
        satReadingBand = 620 to 700,
        satMathBand = 640 to 750,
        actBand = 28 to 33,
      )
    AdmissionsTestDb.seedMeritAid(
      id,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )
    AdmissionsTestDb.seedFactors(id, interview = FactorRating.CONSIDERED)
    AdmissionsTestDb.seedDeadline(id, round = ApplicationRound.EARLY_DECISION_1)
    AdmissionsTestDb.seedDeadline(id, round = ApplicationRound.REGULAR, closing = CdsMonthDay(1, 15), notification = null)
    AdmissionsTestDb.seedIpedsTestPolicy(id)
    return id
  }

  // ---------------------------------------------------------------------------
  // The definition and the input contract
  // ---------------------------------------------------------------------------

  @Test
  fun `the definition carries the ethos contract`() {
    assertEquals(
      CollegeAdmissionsChatTool.TOOL_NAME,
      tool.definition
        .getValue("name")
        .jsonPrimitive.content,
    )
    assertEquals(
      tool.name,
      tool.definition
        .getValue("name")
        .jsonPrimitive.content,
    )
    val description = descriptionOf()
    assertTrue(description.contains("Common Data Set"), "the source must be named: [$description]")
    assertTrue(description.contains("never estimate"), "the no-estimate rule must ride the description")
    assertTrue(description.contains("ALL full-time freshmen"), "the denominator must ride the description")
    assertTrue(description.contains("data_availability"), "the silence must be explained")
    assertTrue(description.contains("offered false"), "a false round flag must be explained as an answer")
    // RFC 187: the test-policy ethos rides the description too -- the policy is
    // dated, it is per school, and its silence is ours.
    assertTrue(
      description.contains(AdmissionsField.TEST_POLICY.wireName),
      "the description must name the section it is stating rules about: [$description]",
    )
    assertTrue(description.contains("say the year"), "a policy claim always carries its collection year: [$description]")
    assertTrue(
      description.contains("never carry one school's policy over to another school"),
      "one school's policy is never generalised to the list: [$description]",
    )
    assertTrue(
      description.contains(TestPolicyCopy.NOT_REPORTED),
      "the absent-policy sentence is the copy object's, never retyped here: [$description]",
    )
    // The section key and the `search_colleges` filter field are ONE word (RFC
    // 187): a student who filtered on it and then asked about one school reads
    // the same word twice. AdmissionsField.TEST_POLICY types that word a second
    // time -- its sibling MERIT_AID derives its key from MeritAidWire.KEY for
    // exactly this reason -- so renaming either side has to fail here.
    assertTrue(
      AdmissionsField.TEST_POLICY.wireName in CollegeQueryVocabulary.FIELD_NAMES,
      "the section key must be the same word search_colleges filters on: [${AdmissionsField.TEST_POLICY.wireName}] " +
        "is not in [${CollegeQueryVocabulary.FIELD_NAMES}]",
    )
    // RFC 195: the band ethos rides the description too -- the cohort is
    // said, the band is per school, an average is never a threshold, and the
    // silence is ours.
    assertTrue(
      description.contains(AdmissionsField.SCORE_BANDS.wireName),
      "the description must name the section it is stating rules about: [$description]",
    )
    assertTrue(
      description.contains("never turn a band into a chance of admission"),
      "D9: a band is never a chance of admission: [$description]",
    )
    assertTrue(
      description.contains("carry one school's band to another school"),
      "one school's band is never generalised to the list: [$description]",
    )
    assertTrue(
      description.contains("never compare a student to it"),
      "D9: nothing compares the student to the figures: [$description]",
    )
    assertTrue(
      description.contains(ScoreBandCopy.NOT_REPORTED),
      "the absent-band sentence is the copy object's, never retyped here: [$description]",
    )
    // Every key the description points a sentence at is interpolated from
    // [ScoreBandWire], so renaming one fails the payload test INSTEAD of
    // leaving the model reading a key that no longer exists.
    for (key in listOf(
      ScoreBandWire.NOT_A_CUT_OFF_KEY,
      ScoreBandWire.ADMITTED_AVERAGE_KEY,
      ScoreBandWire.ADMITTED_AVERAGE_LABEL_KEY,
    )) {
      assertTrue(description.contains(key), "the description must name the key it instructs about: [$key]")
    }
    assertFalse(
      description.contains(" ${MeritAidWire.AVERAGE_LABEL_KEY} says it"),
      "the average-label instruction must point at the band section's own key, not merit_aid's: [$description]",
    )
    assertFalse(
      description.contains("without need"),
      "the tool must never describe the share as a share of freshmen without need: [$description]",
    )
  }

  @Test
  fun `an absent college_ids reads the whole active list and an empty array reads nothing`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "First College")
    AdmissionsTestDb.seedListedCollege(student, "Second College")

    assertEquals(
      2,
      execute(student)
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    val empty = execute(student, """{"college_ids":[]}""")
    assertEquals(
      0,
      empty
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(emptyList(), collegesOf(empty))
  }

  @Test
  fun `ids outside the list come back unknown while the known ones still answer`() {
    val student = AdmissionsTestDb.createStudent()
    val mine = AdmissionsTestDb.seedListedCollege(student, "Mine University")
    val stranger = CollegeId(UUID.randomUUID())

    val result = execute(student, """{"college_ids":["${mine.value}","${stranger.value}"]}""")
    assertEquals(listOf("Mine University"), collegesOf(result).map { it.getValue("name").jsonPrimitive.content })
    assertEquals(
      listOf(stranger.value.toString()),
      result.getValue("unknown_college_ids").jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `malformed input is a structured error, never a throw`() {
    val student = AdmissionsTestDb.createStudent()

    assertNotNull(errorOf(execute(student, """{"college_ids":["not-a-uuid"]}""")))
    assertNotNull(errorOf(execute(student, """{"college_ids":[7]}""")))
    assertNotNull(errorOf(execute(student, """{"college_ids":"all"}""")))
    assertNotNull(errorOf(execute(student, """{"colleges":[]}""")))
    val tooMany = (1..StudentScopedChatTool.MAX_COLLEGE_IDS + 1).joinToString(",") { "\"${UUID.randomUUID()}\"" }
    val capped = errorOf(execute(student, """{"college_ids":[$tooMany]}"""))
    assertNotNull(capped)
    assertTrue(capped.contains("${StudentScopedChatTool.MAX_COLLEGE_IDS}"), "the cap must be named: [$capped]")
  }

  @Test
  fun `sections are emitted in the coach's reading order`() {
    val student = AdmissionsTestDb.createStudent()
    seedFullyReportingCollege(student)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf(
        "college_id",
        "name",
        "city",
        "state",
        "list_status",
        AdmissionsField.MERIT_AID.wireName,
        AdmissionsField.ADMISSION_FACTORS.wireName,
        AdmissionsField.TEST_POLICY.wireName,
        // RFC 195: between the policy and the deadlines, because "does this
        // school want a score" and "what did the scores look like" are one
        // thought and the second is meaningless before the first.
        AdmissionsField.SCORE_BANDS.wireName,
        AdmissionsField.DEADLINES.wireName,
        "data_availability",
      ),
      college.keys.toList(),
      "the section order is the product decision (RFC 145), so it is asserted as a list",
    )
  }

  // ---------------------------------------------------------------------------
  // The honest denominator (D4)
  // ---------------------------------------------------------------------------

  @Test
  fun `the share label names all full-time freshmen and never the students without need`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Share University")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )

    val merit = meritOf(collegesOf(execute(student)).single())
    assertNotNull(merit)
    assertEquals(
      25.0,
      merit
        .getValue(MeritAidWire.SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    assertEquals(
      "25% of all full-time freshmen received non-need (merit) aid",
      merit.getValue("share_label").jsonPrimitive.content,
    )
    assertTrue(MeritAidWire.SHARE_KEY.contains("all_full_time_freshmen"), "the population rides in the key itself")
    assertFalse(
      execute(student).toString().contains("without need"),
      "no payload may describe the share as a share of freshmen without need",
    )
  }

  @Test
  fun `the share is emitted only when both counts are present`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "No Total University")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = 358,
      noNeedMeritAverageUsd = 16112,
    )

    val merit = meritOf(collegesOf(execute(student)).single())
    assertNotNull(merit)
    assertNull(merit[MeritAidWire.SHARE_KEY], "a share with no denominator must not be computed")
    assertNull(merit["share_label"], "nor may its sentence appear without it")
    assertEquals(
      16112,
      merit
        .getValue(MeritAidWire.AVERAGE_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertTrue(
      merit
        .getValue("average_label")
        .jsonPrimitive.content
        .contains("\$16,112"),
    )
  }

  @Test
  fun `zero merit aid is a reported fact`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Amherst-shaped College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 480,
      noNeedMeritRecipientsHeadcount = 0,
      noNeedMeritAverageUsd = 0,
    )

    val merit = meritOf(collegesOf(execute(student)).single())
    assertNotNull(merit)
    assertEquals(
      0.0,
      merit
        .getValue(MeritAidWire.SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    assertEquals(
      0,
      merit
        .getValue(MeritAidWire.AVERAGE_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      "0% of all full-time freshmen received non-need (merit) aid",
      merit.getValue("share_label").jsonPrimitive.content,
    )
    assertTrue(
      merit
        .getValue("average_label")
        .jsonPrimitive.content
        .contains("\$0"),
    )
  }

  @Test
  fun `a college with no merit row still appears, with the silence named`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Silent College")

    val college = collegesOf(execute(student)).single()
    assertNull(meritOf(college), "an absent key, never an empty object")
    assertEquals(
      listOf(
        AdmissionsField.MERIT_AID.wireName,
        AdmissionsField.ADMISSION_FACTORS.wireName,
        AdmissionsField.TEST_POLICY.wireName,
        AdmissionsField.DEADLINES.wireName,
      ),
      availabilityOf(college),
    )
  }

  @Test
  fun `a row carrying only the freshman total is a silence, not a merit section`() {
    // The real corpus shape this guards: 28 of the seed's 368 merit rows report
    // the freshman total and NEITHER merit measure. That total is the share's
    // denominator, not a merit fact, so rendering the row would put a citation
    // with no merit fact under it in front of the coach AND keep the school's
    // merit silence out of data_availability, where the coach reads it.
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Denominator Only College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2760,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = null,
    )

    val rendered = collegesOf(execute(student)).single()
    assertNull(meritOf(rendered), "a denominator with no merit measure is not a merit section")
    assertTrue(
      AdmissionsField.MERIT_AID.wireName in availabilityOf(rendered),
      "the school's merit silence must be sayable: ${availabilityOf(rendered)}",
    )
  }

  @Test
  fun `a single reported merit measure is still a section`() {
    // The complement of the case above, so the emptiness rule cannot be
    // tightened into dropping schools that DO report merit aid: either measure
    // alone is a fact worth saying, and the freshman total may be absent.
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Average Only College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = 12500,
    )

    val merit = assertNotNull(meritOf(collegesOf(execute(student)).single()))
    assertEquals(
      12500,
      merit
        .getValue(MeritAidWire.AVERAGE_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertFalse(MeritAidWire.SHARE_KEY in merit, "no denominator, no share")
  }

  // ---------------------------------------------------------------------------
  // Factors (D6)
  // ---------------------------------------------------------------------------

  @Test
  fun `a null factor is omitted, never rendered as not considered`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Factors University")
    AdmissionsTestDb.seedFactors(college, interview = null, alumniRelation = FactorRating.NOT_CONSIDERED)

    val factors =
      collegesOf(execute(student))
        .single()
        .getValue(AdmissionsField.ADMISSION_FACTORS.wireName)
        .jsonObject
        .getValue("factors")
        .jsonArray
        .map { it.jsonObject }
    val byName = factors.associate { it.getValue("factor").jsonPrimitive.content to it.getValue("importance").jsonPrimitive.content }

    assertFalse("interview" in byName, "an unreported factor is omitted entirely")
    assertEquals("not considered", byName.getValue("alumni_relation"), "the school's own answer is still said")
  }

  @Test
  fun `ratings are spoken labels, not the stored codes`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Spoken University")
    AdmissionsTestDb.seedFactors(college, rigor = FactorRating.VERY_IMPORTANT)

    val payload = execute(student).toString()
    assertTrue(payload.contains("\"very important\""), "the words the coach says must be on the wire")
    assertFalse(payload.contains("very_important"), "the stored code must not be: [$payload]")
    assertTrue(payload.contains("rigor of the high school coursework"), "each factor carries its spoken name")
  }

  // ---------------------------------------------------------------------------
  // Deadlines (D5)
  // ---------------------------------------------------------------------------

  @Test
  fun `an offered round with month and day renders a date phrase`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Dates University")
    AdmissionsTestDb.seedDeadline(college, closing = CdsMonthDay(1, 15), notification = CdsMonthDay(12, 15))

    val round = roundsOf(collegesOf(execute(student)).single()).single()
    assertEquals("January 15", round.getValue("application_deadline").jsonPrimitive.content)
    assertEquals("December 15", round.getValue("decision_notification").jsonPrimitive.content)
    val payload = execute(student).toString()
    assertFalse(payload.contains("\"month\""), "a raw month/day pair is never emitted: [$payload]")
    assertFalse(payload.contains("\"day\""), "a raw month/day pair is never emitted: [$payload]")
  }

  @Test
  fun `a month with no day says the day is not reported`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Half Date College")
    AdmissionsTestDb.seedDeadline(college, closing = CdsMonthDay(1, null), notification = null)

    val round = roundsOf(collegesOf(execute(student)).single()).single()
    assertEquals("January, day not reported", round.getValue("application_deadline").jsonPrimitive.content)
    assertNull(round["decision_notification"], "an unreported date is absent, never guessed")
  }

  @Test
  fun `an offered round with no date carries the flag alone`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Rolling College")
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.ROLLING, closing = null, notification = null)

    val round = roundsOf(collegesOf(execute(student)).single()).single()
    assertEquals(
      true,
      round
        .getValue("offered")
        .jsonPrimitive.content
        .toBoolean(),
    )
    assertEquals("this school offers Rolling Admission", round.getValue("offered_label").jsonPrimitive.content)
    assertNull(round["application_deadline"])
    assertNull(round["decision_notification"])
  }

  @Test
  fun `offered false is rendered as a reported fact`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "No ED2 College")
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.EARLY_DECISION_2, offered = false, closing = null, notification = null)

    val round = roundsOf(collegesOf(execute(student)).single()).single()
    assertEquals(
      false,
      round
        .getValue("offered")
        .jsonPrimitive.content
        .toBoolean(),
    )
    assertEquals("this school does not offer Early Decision 2", round.getValue("offered_label").jsonPrimitive.content)
    assertEquals("early_decision_2", round.getValue("round").jsonPrimitive.content)
    assertEquals("Early Decision 2", round.getValue("round_label").jsonPrimitive.content)
  }

  @Test
  fun `rounds are rendered in ApplicationRound's declared order`() {
    // The order a family hears their deadlines in is ApplicationRound's
    // declaration order (RFC 148 D5), so it is pinned HERE rather than left to
    // an ordinal nobody owns: reordering that enum changes this answer and must
    // fail a test, not pass silently.
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Every Round University")
    // Seeded in reverse, so a rendered order that merely echoed the writes
    // would fail.
    ApplicationRound.entries.reversed().forEach { round ->
      AdmissionsTestDb.seedDeadline(college, round = round, closing = null, notification = null)
    }

    assertEquals(
      ApplicationRound.entries.map { it.value },
      roundsOf(collegesOf(execute(student)).single()).map { it.getValue("round").jsonPrimitive.content },
    )
  }

  // ---------------------------------------------------------------------------
  // Citation (D2)
  // ---------------------------------------------------------------------------

  @Test
  fun `each section carries its own citation and the cycle is spoken, never a bare year`() {
    val student = AdmissionsTestDb.createStudent()
    val college = seedFullyReportingCollege(student, "Cited University")

    val rendered = collegesOf(execute(student)).single()
    // The THREE CDS sections. The test policy is deliberately not among them:
    // it is one federal survey with no per-school document, so it carries its
    // own citation type and is asserted on its own terms below (RFC 187).
    // The score bands are excluded with it, and for the same kind of reason:
    // one federal file with one cohort, cited as itself rather than as the
    // school's own document (RFC 195).
    val cdsFields = AdmissionsField.entries - AdmissionsField.TEST_POLICY - AdmissionsField.SCORE_BANDS
    val citations =
      cdsFields.map { field ->
        rendered
          .getValue(field.wireName)
          .jsonObject
          .getValue("source")
          .jsonObject
      }
    assertEquals(
      List(cdsFields.size) { "Cited University's 2024-25 Common Data Set" },
      citations.map { it.getValue("cited_as").jsonPrimitive.content },
    )
    assertEquals(
      "the U.S. Department of Education's IPEDS admissions survey, collected in 2023",
      testPolicyOf(rendered)!!
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
      "the federal survey names itself, and never the school's own Common Data Set",
    )
    assertTrue(citations.all { it.getValue("url").jsonPrimitive.content == AdmissionsTestDb.SOURCE_URL })
    assertTrue(citations.all { it.getValue("archive_url").jsonPrimitive.content == AdmissionsTestDb.ARCHIVE_URL })
    // The cycle is a spoken string; a lone 2024 under a bare key is exactly
    // what the RFC 143 guard exists to catch.
    assertFalse(execute(student).toString().contains(":2024"), "no field may carry a bare year")

    AdmissionsTestDb.seedMeritAid(college, archiveUrl = null)
    val merit = meritOf(collegesOf(execute(student)).single())!!.getValue("source").jsonObject
    assertNull(merit["archive_url"], "a null archive copy omits the key")
    assertEquals(AdmissionsTestDb.SOURCE_URL, merit.getValue("url").jsonPrimitive.content, "and the school's own url still cites")
  }

  @Test
  fun `the latest cycle is resolved per table, in one result`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Mixed Cycle University")
    AdmissionsTestDb.seedFactors(college, sourceYear = 2024)
    AdmissionsTestDb.seedMeritAid(college, sourceYear = 2025)

    val rendered = collegesOf(execute(student)).single()
    assertEquals(
      "Mixed Cycle University's 2025-26 Common Data Set",
      meritOf(rendered)!!
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
    )
    assertEquals(
      "Mixed Cycle University's 2024-25 Common Data Set",
      rendered
        .getValue(AdmissionsField.ADMISSION_FACTORS.wireName)
        .jsonObject
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
    )
  }

  // ---------------------------------------------------------------------------
  // The test policy (RFC 187)
  // ---------------------------------------------------------------------------

  @Test
  fun `the test-policy section renders the slug, the sentence, the standing caveat and its citation`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Optional University")
    AdmissionsTestDb.seedIpedsTestPolicy(college, testPolicy = 5, surveyYear = 2024)

    val section = testPolicyOf(collegesOf(execute(student)).single())
    assertNotNull(section)
    assertEquals(AdmissionTestPolicy.TEST_OPTIONAL.slug, section.getValue("policy").jsonPrimitive.content)
    // Asserted against the copy object, never a retyped sentence: the words
    // have one home, and a literal here would be a second one.
    assertEquals(
      TestPolicyCopy.statementOf(AdmissionTestPolicy.TEST_OPTIONAL),
      section.getValue("statement").jsonPrimitive.content,
    )
    assertEquals(
      TestPolicyCopy.CONFIRM_WITH_SCHOOL,
      section.getValue("confirm_with_school").jsonPrimitive.content,
    )
    val citedAs =
      section
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content
    assertTrue(citedAs.contains("2024"), "the collection year is IN the sentence, never a bare key: [$citedAs]")
    assertTrue(citedAs.contains("IPEDS"), "the publisher is named: [$citedAs]")
    // No url key: IPEDS publishes no per-school page for this answer, so the
    // section renders its own one-key source rather than reusing putCitation.
    assertEquals(setOf("cited_as"), section.getValue("source").jsonObject.keys)
  }

  @Test
  fun `each published code renders its own sentence, and every one carries the standing caveat`() {
    val student = AdmissionsTestDb.createStudent()
    // D17: the "a school can change this" sentence is not optional, so it is
    // asserted on EVERY rendered section rather than on a representative one.
    AdmissionTestPolicy.entries.forEach { policy ->
      val college = AdmissionsTestDb.seedListedCollege(student, "${policy.slug} University")
      AdmissionsTestDb.seedIpedsTestPolicy(college, testPolicy = policy.code)

      val section = testPolicyOf(collegesOf(execute(student, """{"college_ids":["${college.value}"]}""")).single())
      assertNotNull(section, "[${policy.slug}] must render a section")
      assertEquals(policy.slug, section.getValue("policy").jsonPrimitive.content)
      assertEquals(TestPolicyCopy.statementOf(policy), section.getValue("statement").jsonPrimitive.content)
      assertEquals(
        TestPolicyCopy.CONFIRM_WITH_SCHOOL,
        section.getValue("confirm_with_school").jsonPrimitive.content,
        "[${policy.slug}] must carry the standing caveat too",
      )
    }
  }

  @Test
  fun `a school with no policy emits no section and names the silence`() {
    val student = AdmissionsTestDb.createStudent()
    val noRow = AdmissionsTestDb.seedListedCollege(student, "No Row College")
    val noPolicy = AdmissionsTestDb.seedListedCollege(student, "No Policy College")
    AdmissionsTestDb.seedIpedsTestPolicy(noPolicy, testPolicy = null)

    collegesOf(execute(student)).forEach { college ->
      assertNull(testPolicyOf(college), "an absent key, never an empty object: [${college["name"]}]")
      assertTrue(
        AdmissionsField.TEST_POLICY.wireName in availabilityOf(college),
        "the silence must be sayable: [${availabilityOf(college)}]",
      )
    }
    assertEquals(2, collegesOf(execute(student, """{"college_ids":["${noRow.value}","${noPolicy.value}"]}""")).size)
  }

  @Test
  fun `no raw ADMCON7 code or variable name reaches the payload`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionTestPolicy.entries.forEach { policy ->
      AdmissionsTestDb.seedListedCollege(student, "${policy.slug} University").also {
        AdmissionsTestDb.seedIpedsTestPolicy(it, testPolicy = policy.code)
      }
    }

    val payload = execute(student).toString()
    assertFalse(payload.contains("ADMCON7"), "the survey's internal variable name means nothing to a student")
    // The codes themselves are checked through the PARSER, not by matching the
    // serialized text: a `"policy":"5"` substring probe assumes kotlinx's
    // compact spacing, and it misses the likelier regression anyway -- a code
    // rendered as a JSON NUMBER, which is what `put(key, Int)` does. So every
    // section is read back and held to being words: each value is a non-empty
    // string, and `policy` is a slug that does not parse as a number.
    collegesOf(execute(student)).forEach { college ->
      val section = testPolicyOf(college)
      assertNotNull(section, "every seeded school renders a section")
      section.forEach { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@forEach
        assertTrue(primitive.isString, "[$key] must reach the model as words, not as a bare number: [$primitive]")
        assertTrue(primitive.content.isNotEmpty(), "[$key] must not be an empty string")
      }
      assertNull(
        section
          .getValue("policy")
          .jsonPrimitive.content
          .toIntOrNull(),
        "the policy key carries the slug word, never a published code",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Score bands (RFC 195)
  // ---------------------------------------------------------------------------

  @Test
  fun `a school's bands reach the wire as numbers AND as sentences, with the cohort and the qualifier`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(
      student,
      "Range University",
      satReadingBand = 620 to 700,
      satMathBand = 640 to 750,
      actBand = 28 to 33,
    )

    val college = collegesOf(execute(student)).single()
    val section = scoreBandsOf(college)
    assertNotNull(section)
    val bands = section.getValue(ScoreBandWire.BANDS_KEY).jsonArray.map { it.jsonObject }
    assertEquals(
      ScoredTest.entries.map { it.wireName },
      bands.map { it.getValue(ScoreBandWire.TEST_KEY).jsonPrimitive.content },
      "the two SAT sections then the ACT -- the order a family hears them in",
    )
    // D-C: the measure-bearing numbers ARE on the wire, so a model compares
    // figures rather than parsing English.
    val reading = bands.first()
    assertEquals(620, reading.getValue(ScoreBandWire.SCORE_25TH_KEY).jsonPrimitive.int)
    assertEquals(700, reading.getValue(ScoreBandWire.SCORE_75TH_KEY).jsonPrimitive.int)
    // ...and so is the sentence, computed from those same two numbers.
    val statement = reading.getValue(ScoreBandWire.STATEMENT_KEY).jsonPrimitive.content
    assertEquals(ScoreBandCopy.statementOf(assertIs<ScoreBandResult.Band>(ScoreBand.of(ScoredTest.SAT_READING, 620, 700)).band), statement)
    assertTrue(statement.contains("620") && statement.contains("700"), "the sentence carries the figures: [$statement]")
    assertTrue(
      statement.contains(ScoreBandCitation.COHORT_TERM),
      "the cohort is INSIDE the sentence, never a bare year beside it: [$statement]",
    )
    assertTrue(statement.contains(ScoredTest.SAT_READING.label), "and the test it is about: [$statement]")

    // The qualifier rides the section, once, and is not optional.
    assertEquals(ScoreBandCopy.NOT_A_CUT_OFF, section.getValue(ScoreBandWire.NOT_A_CUT_OFF_KEY).jsonPrimitive.content)
    assertEquals(
      ScoreBandCitation.citedAs,
      section
        .getValue(ScoreBandWire.SOURCE_KEY)
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
      "the federal file names itself and its cohort, never the school's own document",
    )
    assertNull(section[ScoreBandWire.ADMITTED_AVERAGE_KEY], "a school with a band is never shown an average too")
    assertFalse(
      AdmissionsField.SCORE_BANDS.wireName in availabilityOf(college),
      "a rendered section must not also be named as a silence: ${availabilityOf(college)}",
    )
  }

  @Test
  fun `an average-only section emits no bands array and no band qualifier`() {
    // The 88-college shape (D-A), and the default-seeded college's shape too:
    // an average, no band. An empty `bands` array would be furniture, and
    // "a quarter of admitted students scored below the lower number" beside no
    // numbers qualifies nothing -- a standing sentence about figures that are
    // not there is exactly how a model comes to believe it has a range.
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Average Only College", satAverageEquivalentScore = 1310)

    val section = assertNotNull(scoreBandsOf(collegesOf(execute(student)).single()))
    assertNull(section[ScoreBandWire.BANDS_KEY], "no band means no bands key, not an empty array")
    assertNull(section[ScoreBandWire.NOT_A_CUT_OFF_KEY], "the standing qualifier rides bands, and there are none")
    assertEquals(
      setOf(ScoreBandWire.ADMITTED_AVERAGE_KEY, ScoreBandWire.SOURCE_KEY),
      section.keys,
      "an average-only section is the average and its citation, and nothing else",
    )
  }

  @Test
  fun `a school with no score at all is an absent section and a named silence`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "No Score College", satAverageEquivalentScore = null)

    val college = collegesOf(execute(student)).single()
    assertNull(scoreBandsOf(college), "an absent key, never an empty object")
    assertTrue(
      AdmissionsField.SCORE_BANDS.wireName in availabilityOf(college),
      "the gap is OURS and must be sayable: ${availabilityOf(college)}",
    )
  }

  @Test
  fun `D16 renders the average as an average, and never beside a band for the same test`() {
    val student = AdmissionsTestDb.createStudent()
    // Both directions in one test, because the failure mode is showing BOTH.
    AdmissionsTestDb.seedListedCollege(
      student,
      "Average College",
      satAverageEquivalentScore = 1310,
      actBand = 28 to 33,
    )
    AdmissionsTestDb.seedListedCollege(
      student,
      "Banded College",
      satAverageEquivalentScore = 1310,
      satReadingBand = 620 to 700,
      satMathBand = 640 to 750,
    )

    val byName = collegesOf(execute(student)).associateBy { it.getValue("name").jsonPrimitive.content }
    val average = scoreBandsOf(byName.getValue("Average College"))
    assertNotNull(average)
    val averageObject = average.getValue(ScoreBandWire.ADMITTED_AVERAGE_KEY).jsonObject
    assertEquals(1310, averageObject.getValue(ScoreBandWire.ADMITTED_AVERAGE_SCORE_KEY).jsonPrimitive.int)
    assertEquals(
      ScoreBandCopy.AVERAGE_LABEL,
      averageObject.getValue(ScoreBandWire.ADMITTED_AVERAGE_LABEL_KEY).jsonPrimitive.content,
      "the label key is the section's OWN -- `average_label` belongs to merit_aid in this same payload",
    )
    assertTrue(
      averageObject
        .getValue(ScoreBandWire.STATEMENT_KEY)
        .jsonPrimitive.content
        .contains(ScoreBandCopy.AVERAGE_IS_NOT_A_TARGET),
      "an average always arrives with what it is NOT",
    )
    // The ACT band it publishes is beside the average, and no SAT band was
    // invented from the average (D-A).
    assertEquals(
      listOf(ScoredTest.ACT_COMPOSITE.wireName),
      average
        .getValue(ScoreBandWire.BANDS_KEY)
        .jsonArray
        .map {
          it.jsonObject
            .getValue(ScoreBandWire.TEST_KEY)
            .jsonPrimitive.content
        },
    )

    val banded = scoreBandsOf(byName.getValue("Banded College"))
    assertNotNull(banded)
    assertNull(
      banded[ScoreBandWire.ADMITTED_AVERAGE_KEY],
      "a school with an SAT band is never also shown the SAT average",
    )
  }

  // ---------------------------------------------------------------------------
  // The RFC 143 guard (D9)
  // ---------------------------------------------------------------------------

  @Test
  fun `no bare source code reaches a tool result`() {
    val student = AdmissionsTestDb.createStudent()
    // Every optional section is populated on purpose: a section left absent is
    // a section the guard never sees, and that is exactly the shape the next
    // coded field will take. A sparse fixture would let it sleep through.
    seedFullyReportingCollege(student)

    val payload = execute(student)
    // The message renders the violations through the guard's own [mapMessage], so
    // the ASSERTION reads as English while the assertion's VALUES stay typed.
    val violations = listViolations(payload)
    assertEquals(
      emptyList(),
      violations,
      "the admissions result must carry no source code: ${violations.map(BareSourceCodeGuard::mapMessage)}",
    )

    val college = collegesOf(payload).single()
    assertEquals(
      emptySet(),
      AdmissionsField.entries.map { it.wireName }.toSet() - college.keys,
      "every section the allowlist sanctions must be in the payload",
    )
    assertEquals(
      emptySet(),
      MeritAidWire.NUMERIC_KEYS - meritOf(college)!!.keys,
      "every merit measure the allowlist sanctions must be in the payload",
    )
    // The two percentile keys RFC 195 admitted to the allowlist are on the
    // wire, so the guard above walked them rather than sleeping through an
    // absent section.
    assertEquals(
      emptySet(),
      setOf(ScoreBandWire.SCORE_25TH_KEY, ScoreBandWire.SCORE_75TH_KEY) -
        scoreBandsOf(college)!!
          .getValue(ScoreBandWire.BANDS_KEY)
          .jsonArray
          .first()
          .jsonObject.keys,
      "every score key the allowlist sanctions must be in the payload",
    )

    // Positive control: the guard must react to every shape it exists to catch,
    // including the raw {month, day} pair this tool renders as a phrase.
    val doctored =
      JsonObject(
        payload +
          mapOf(
            "cds_source_year" to JsonPrimitive(2024),
            "closing" to buildJsonObject { put("month", 1) },
            "net_price_q5" to JsonPrimitive(31000),
            "source_column" to JsonPrimitive("NPT41"),
          ),
      )
    assertEquals(
      listOf(
        BareSourceCode.QuintileToken("q5"),
        BareSourceCode.Npt4ColumnFamily,
        BareSourceCode.BareNumberField("cds_source_year"),
        BareSourceCode.BareNumberField("month"),
        BareSourceCode.BareNumberField("net_price_q5"),
      ),
      listViolations(doctored),
    )

    // An error envelope is a model-facing tool result too, and a malformed-arg
    // retry is an ordinary path -- so it owes the same property (RFC 143).
    assertEquals(
      emptyList(),
      listViolations(execute(student, """{"college_ids":["not-a-uuid"]}""")),
      "the malformed-input error must carry no source code",
    )

    // The description is prose the model reads before any result, so it owes
    // the token half of the property too.
    val description = descriptionOf()
    assertNull(BareSourceCodeGuard.QUINTILE_CODE.find(description), "nor the description: [$description]")
    assertFalse(description.contains(BareSourceCodeGuard.NPT4), "nor the description: [$description]")
  }
}

// ---------------------------------------------------------------------------
// The generalised source-code guard (RFC 143), hosted in :chat's test fixtures
// since RFC 148 D9 -- the walker is shared, the allowlist stays this tool's own.
// ---------------------------------------------------------------------------

/**
 * The field names whose value is a NUMBER by contract: this tool's merit
 * measures and the result count, read from
 * [CollegeAdmissionsChatTool.NUMBERS_BY_CONTRACT], their one home. Everything
 * else it renders is a string by construction -- spoken ratings, spoken round
 * names, date phrases, the cycle label -- which is the point of the rendering
 * rules rather than a coincidence.
 */
private val NUMBERS_BY_CONTRACT = CollegeAdmissionsChatTool.NUMBERS_BY_CONTRACT

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)
