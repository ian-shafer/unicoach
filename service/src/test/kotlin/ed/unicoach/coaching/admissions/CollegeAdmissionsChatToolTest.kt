package ed.unicoach.coaching.admissions

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.StudentScopedChatTool
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    val id = AdmissionsTestDb.seedListedCollege(student, name)
    AdmissionsTestDb.seedMeritAid(id, freshmenFtTotal = 2000, noNeedMeritCount = 500, noNeedMeritAvg = 12500)
    AdmissionsTestDb.seedFactors(id, interview = FactorRating.CONSIDERED)
    AdmissionsTestDb.seedDeadline(id, round = ApplicationRound.EARLY_DECISION_1)
    AdmissionsTestDb.seedDeadline(id, round = ApplicationRound.REGULAR, closing = CdsMonthDay(1, 15), notification = null)
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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 2000, noNeedMeritCount = 500, noNeedMeritAvg = 12500)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = null, noNeedMeritCount = 358, noNeedMeritAvg = 16112)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 480, noNeedMeritCount = 0, noNeedMeritAvg = 0)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 2760, noNeedMeritCount = null, noNeedMeritAvg = null)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = null, noNeedMeritCount = null, noNeedMeritAvg = 12500)

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
    val citations =
      AdmissionsField.entries.map { field ->
        rendered
          .getValue(field.wireName)
          .jsonObject
          .getValue("source")
          .jsonObject
      }
    assertEquals(
      List(3) { "Cited University's 2024-25 Common Data Set" },
      citations.map { it.getValue("cited_as").jsonPrimitive.content },
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
