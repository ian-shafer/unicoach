package ed.unicoach.coaching.testing

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.COACH_ZONE_LABEL
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `admission_test_timeline` wire (RFC 197): what the model is actually handed.
 *
 * The behaviour is asserted in [TestTimelineServiceTest]; this suite asserts the
 * things only the payload can be wrong about -- that every date on it is one we
 * hold or one we derived and said so, that the expiry payload carries NO date at
 * all, and that the zone travels with the day.
 */
class AdmissionTestTimelineChatToolTest {
  private val tool = AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11))

  @BeforeEach
  fun setUp() {
    TestTimelineTestDb.reset()
  }

  @Test
  fun `the tool name and the advertised schema are the shared college_ids contract`() {
    assertEquals(AdmissionTestTimelineChatTool.TOOL_NAME, tool.name)
    assertEquals(
      AdmissionTestTimelineChatTool.TOOL_NAME,
      tool.definition
        .getValue("name")
        .jsonPrimitive.content,
    )
    val schema = tool.definition.getValue("input_schema").jsonObject
    assertEquals("object", schema.getValue("type").jsonPrimitive.content)
    assertTrue(
      StudentScopedChatTool.COLLEGE_IDS_FIELD in schema.getValue("properties").jsonObject,
      "the tool must advertise the shared subset filter: [$schema]",
    )
    assertEquals(
      false,
      schema
        .getValue("additionalProperties")
        .jsonPrimitive.content
        .toBoolean(),
    )
  }

  /**
   * NO DATE FROM MEMORY (D20's sibling rule, and the reason this tool exists).
   *
   * Every `yyyy-mm-dd` string anywhere in the payload -- including inside the
   * `why` sentences, which is where a fabricated date would actually hide -- must
   * be a cell of the seeded calendar, the resolved anchor, today, or the ONE
   * documented derivation. The permitted set is built from the seeded rows and
   * from the derivation's own rule, so it cannot be widened by accident.
   */
  @Test
  fun `every date in the payload is a row we hold, the resolved anchor, today, or the one derivation`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val payload = tool.execute(student, buildJsonObject {})

      val calendar = AdmissionTestCalendarRows.seeded()
      val permitted =
        calendar
          .flatMap {
            listOfNotNull(
              it.testDate,
              it.registrationDeadline,
              it.lateRegistrationDeadline,
              it.scoreRelease?.date,
              it.expiresOn,
              // The documented derivation, stated here as the rule and not as a
              // list of values: fourteen days before registration closes.
              it.registrationDeadline.minus(TestTimelineService.FEE_WAIVER_LEAD),
            )
          }.map { it.toString() }
          .toSet() +
          setOf(TODAY.toString(), RESOLVED_ANCHOR.toString())

      val found = DATE_PATTERN.findAll(payload.toString()).map { it.value }.toSet()
      assertTrue(found.isNotEmpty(), "the payload must contain dates at all, or this sweep proves nothing")
      assertEquals(
        emptySet(),
        found - permitted,
        "the payload names a date that is not in the calendar, not the resolved anchor, not today, " +
          "and not the documented fee-waiver derivation",
      )
    }

  /**
   * D7 ON THE WIRE. Past the calendar's expiry the payload carries no date at
   * all: not a sitting, not the calendar's own expiry, not even today. A refusal
   * with a date beside it is an invitation to compute from that date.
   */
  @Test
  fun `the expired payload carries no date at all, and a refusal naming both publishers`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(EXPIRED_CALENDAR)
      val expiredTool = AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt(PAST_THE_EXPIRY))

      val payload = expiredTool.execute(student, buildJsonObject {})

      assertEquals(
        true,
        payload
          .getValue("calendar")
          .jsonObject
          .getValue("expired")
          .jsonPrimitive.content
          .toBoolean(),
      )
      assertEquals(JsonArray(emptyList()), payload.getValue("sittings").jsonArray)
      val refusal = payload.getValue("refusal").jsonPrimitive.content
      assertTrue(refusal.contains("College Board"), "the refusal must name College Board: [$refusal]")
      assertTrue(refusal.contains("ACT"), "the refusal must name ACT: [$refusal]")
      assertEquals(
        emptyList(),
        DATE_PATTERN.findAll(payload.toString()).map { it.value }.toList(),
        "past expiry the tool returns dates for nothing: [$payload]",
      )
      // And the positive control for the sweep above: the pattern does fire on a
      // payload that DOES carry a date, so an empty result means what it says.
      assertTrue(DATE_PATTERN.containsMatchIn("""{"as_of":"2026-09-11"}"""), "the date pattern must be able to fire")
    }

  /** The fee-waiver date on the wire is the derivation, and it says so in the same object. */
  @Test
  fun `fee_waiver_by is fourteen days before registration closes and is flagged derived`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val payload = tool.execute(student, buildJsonObject {})

      val sittings = payload.getValue("sittings").jsonArray.map { it.jsonObject }
      assertTrue(sittings.isNotEmpty(), "there must be sittings ahead of this clock")
      sittings.forEach { sitting ->
        val registration = LocalDate.parse(sitting.getValue("registration_deadline").jsonPrimitive.content)
        assertEquals(
          registration.minus(TestTimelineService.FEE_WAIVER_LEAD).toString(),
          sitting.getValue("fee_waiver_by").jsonPrimitive.content,
        )
        assertEquals(
          true,
          sitting
            .getValue("fee_waiver_by_is_derived")
            .jsonPrimitive.content
            .toBoolean(),
          "the date must never reach the model without the statement that it is ours",
        )
      }
    }

  /** The day the coach works from, and the zone it is in, travel together -- and the zone is ours, not the clock's. */
  @Test
  fun `as_of is the US day and carries the zone it was computed in`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()
      val afterMidnightUtc = AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt("2026-09-12T02:30:00Z"))

      val payload = afterMidnightUtc.execute(student, buildJsonObject {})

      assertEquals("2026-09-11", payload.getValue("as_of").jsonPrimitive.content)
      assertEquals(COACH_ZONE_LABEL, payload.getValue("as_of_zone").jsonPrimitive.content)
    }

  /**
   * The publisher's silence on the wire: an explicit null for the date AND an
   * explicit null for the verdict, never `true`, never `false`, and never an
   * omitted key -- an absent key is a silence the model would fill.
   */
  @Test
  fun `an unannounced score release is an explicit null and an explicit null verdict`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val payload = tool.execute(student, buildJsonObject {})

      val spring =
        payload
          .getValue("sittings")
          .jsonArray
          .map { it.jsonObject }
          .filter {
            it
              .getValue("test_date")
              .jsonPrimitive.content
              .startsWith("2027-")
          }.filter { it.getValue("test").jsonPrimitive.content == AdmissionTest.SAT.value }
      assertEquals(3, spring.size, "the three spring SAT sittings are still ahead on this clock")
      spring.forEach { sitting ->
        assertEquals(JsonNull, sitting.getValue("score_release_date"))
        assertEquals(JsonNull, sitting.getValue("usable_for_anchor"))
        assertTrue(
          payload.getValue("data_availability").jsonArray.any {
            it.jsonObject
              .getValue("test_date")
              .jsonPrimitive.content ==
              sitting.getValue("test_date").jsonPrimitive.content
          },
          "each one must be named in data_availability",
        )
      }
    }

  /** No list, no anchor: an explicit null anchor and the general sequence, labelled general in its own words. */
  @Test
  fun `with no school on the list the anchor is an explicit null and the general sequence answers`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()

      val payload = tool.execute(student, buildJsonObject {})

      assertEquals(JsonNull, payload.getValue("anchor"))
      val general = payload.getValue("general_sequence").jsonPrimitive.content
      assertTrue(general.contains("general"), "the sequence must label itself general: [$general]")
    }

  /** An id that is not on the student's list is REPORTED, and the rest of the answer still comes back. */
  @Test
  fun `an off-list college id is reported rather than failing the read`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val stranger = AdmissionsTestDb.seedCollege("Stranger College")

      val payload =
        tool.execute(
          student,
          buildJsonObject {
            put(
              StudentScopedChatTool.COLLEGE_IDS_FIELD,
              JsonArray(listOf(JsonPrimitive(stranger.value.toString()))),
            )
          },
        )

      assertEquals(
        listOf(stranger.value.toString()),
        payload.getValue("unknown_college_ids").jsonArray.map { it.jsonPrimitive.content },
      )
      assertEquals(JsonNull, payload.getValue("anchor"), "no college was selected, so there is no deadline to anchor on")
    }

  /** Malformed input is a structured refusal the model reads, never a throw (the ChatTool contract). */
  @Test
  fun `an unknown input field is refused as a structured error`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()

      val payload = tool.execute(student, buildJsonObject { put("when", "soon") })

      assertTrue(StudentScopedChatTool.isToolRefusal(payload), "an unknown field is a refusal: [$payload]")
    }

  /**
   * RFC 143's guard: no bare source code and no bare number reaches the model.
   * [AdmissionTestTimelineChatTool.NUMBERS_BY_CONTRACT] is EMPTY for this tool --
   * every date is an ISO string -- so a date that arrived as a number would fail
   * here, which is the point.
   */
  @Test
  fun `no bare source code or bare number reaches the model`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val payload = tool.execute(student, buildJsonObject {})

      assertEquals(
        emptyList(),
        BareSourceCodeGuard
          .listViolations(payload, AdmissionTestTimelineChatTool.NUMBERS_BY_CONTRACT)
          .map(BareSourceCodeGuard::mapMessage),
      )
      // The guard must be able to fire, or the assertion above is vacuous.
      assertTrue(
        BareSourceCodeGuard
          .listViolations(buildJsonObject { put("test_date", 20261003) }, emptySet())
          .isNotEmpty(),
      )
    }

  /**
   * THE DESCRIPTION IS SOURCEABLE. Every field name it instructs the coach to
   * read is a field this tool actually emits -- on a real payload, not in a
   * comment. A description that names a key the wire does not carry is an
   * instruction to state something no tool can source, which is the defect RFC
   * 187 shipped twice.
   */
  @Test
  fun `every field the description names is a field the payload carries`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val resolved = tool.execute(student, buildJsonObject {})

      TestTimelineTestDb.reset()
      val expiredStudent = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(EXPIRED_CALENDAR)
      val expired =
        AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt(PAST_THE_EXPIRY))
          .execute(expiredStudent, buildJsonObject {})

      val emitted = keysOf(resolved) + keysOf(expired)
      val described = DESCRIBED_FIELDS.filter { AdmissionTestTimelineChatTool.DESCRIPTION.contains(it) }
      assertEquals(
        DESCRIBED_FIELDS,
        described,
        "the description must name every field it routes the coach through",
      )
      assertEquals(
        emptyList(),
        DESCRIBED_FIELDS.map { it.substringAfterLast('.') }.filterNot { it in emitted },
        "the description names a field the wire does not carry: emitted [$emitted]",
      )
      // And the rule it must never state: the coach is told not to name dates
      // from memory, which is only honest because every date above came from here.
      assertTrue(
        AdmissionTestTimelineChatTool.DESCRIPTION.contains("from memory"),
        "the description must forbid naming a date from memory",
      )
      // The superscore sentence says we HOLD NOTHING and sends them to the
      // school's own page. It must never instruct the coach to state a policy
      // from a record: nothing in this repo holds one (RFC 187's lesson).
      assertTrue(
        AdmissionTestTimelineChatTool.DESCRIPTION.contains("holds no school's superscore rule"),
        "the description must say we hold no superscore rule, rather than telling the coach to state one",
      )
    }

  /**
   * D13 ON THE WIRE. The service value's ordering is asserted in
   * [TestTimelineServiceTest]; this is the array the model actually reads. Both
   * publishers reach it, and the order is the calendar's own -- by test date --
   * so neither publisher is privileged by position.
   */
  @Test
  fun `the sittings array carries both tests, in test-date order`() =
    runTest {
      val student = listedStudentWithAnEarlyActionDeadline()
      val sittings =
        tool
          .execute(student, buildJsonObject {})
          .getValue("sittings")
          .jsonArray
          .map { it.jsonObject }

      val usable =
        sittings.filter {
          it
            .getValue("usable_for_anchor")
            .jsonPrimitive.content
            .toBooleanStrictOrNull() == true
        }
      assertEquals(
        setOf(AdmissionTest.SAT.value, AdmissionTest.ACT.value),
        usable.map { it.getValue("test").jsonPrimitive.content }.toSet(),
        "both tests have a sitting whose scores arrive in time, so both must reach the model: [$sittings]",
      )

      val dates = sittings.map { it.getValue("test_date").jsonPrimitive.content }
      assertEquals(dates.sorted(), dates, "no publisher's sittings may come first as a block")
    }

  /**
   * A CLOSED REGISTRATION AND AN UNANNOUNCED SCORE RELEASE ARE INDEPENDENT
   * FACTS, and both must survive to the wire (the service half is in
   * [TestTimelineServiceTest]).
   *
   * On 2027-04-20 the SAT's 2027-05-01 sitting is past its 2027-04-16
   * registration deadline AND has no announced score release. The verdict on the
   * wire is `false` -- the fact we hold -- and the sitting is STILL named in
   * `data_availability`, so the publisher's silence is reported rather than
   * swallowed by the verdict.
   */
  @Test
  fun `a closed sitting is false on the wire and still named in data_availability`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2028)
      val college = AdmissionsTestDb.seedListedCollege(student, "Spring College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()
      val springTool = AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt(NOON_EASTERN_2027_04_20))

      val payload = springTool.execute(student, buildJsonObject {})

      val closed =
        payload
          .getValue("sittings")
          .jsonArray
          .map { it.jsonObject }
          .single {
            it.getValue("test").jsonPrimitive.content == AdmissionTest.SAT.value &&
              it.getValue("test_date").jsonPrimitive.content == CLOSED_SPRING_SITTING
          }

      assertEquals(JsonNull, closed.getValue("score_release_date"), "College Board has announced no release for it")
      assertEquals(
        false,
        closed
          .getValue("usable_for_anchor")
          .jsonPrimitive.content
          .toBoolean(),
        "registration closed on 2027-04-16, and a fact we hold is not withheld behind an unknown",
      )
      assertEquals(
        false,
        closed
          .getValue("registration_open")
          .jsonPrimitive.content
          .toBoolean(),
        "the fact the verdict turns on must be on the wire beside it",
      )
      assertTrue(
        payload.getValue("data_availability").jsonArray.any {
          it.jsonObject
            .getValue("test_date")
            .jsonPrimitive.content == CLOSED_SPRING_SITTING
        },
        "the unannounced release must still be reported: [${payload.getValue("data_availability")}]",
      )
    }

  /**
   * AN ABSENT LATE DEADLINE IS AN EXPLICIT NULL, and its label stays.
   *
   * This file says twice that an absent key is a silence the model would fill,
   * and it used to omit both keys whenever the publisher published no later
   * deadline -- which real rows do. A sitting missing the pair is
   * indistinguishable, to a reader, from a sitting the renderer forgot.
   */
  @Test
  fun `a sitting with no late deadline carries an explicit null and keeps its label`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(NO_LATE_DEADLINE_CALENDAR)

      val sitting =
        tool
          .execute(student, buildJsonObject {})
          .getValue("sittings")
          .jsonArray
          .single()
          .jsonObject

      assertEquals(JsonNull, sitting.getValue("late_or_changes_deadline"))
      assertEquals(
        "late registration",
        sitting.getValue("late_or_changes_deadline_label").jsonPrimitive.content,
        "the label is what that publisher WOULD cover, and is not a claim that a date exists",
      )
    }

  /**
   * AN EMPTY CALENDAR REFUSES, AND SAYS SO TRUTHFULLY. It is not an out-of-date
   * calendar, so `calendar.expired` is false and the sentence is the other one --
   * and the payload still carries no date at all.
   */
  @Test
  fun `an empty calendar refuses without claiming the calendar is out of date`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()

      val payload = tool.execute(student, buildJsonObject {})

      val refusal = payload.getValue("refusal").jsonPrimitive.content
      assertTrue(refusal.contains("do not hold"), "the refusal must say we hold no calendar: [$refusal]")
      assertEquals(
        false,
        payload
          .getValue("calendar")
          .jsonObject
          .getValue("expired")
          .jsonPrimitive.content
          .toBoolean(),
        "a table with nothing in it is not a calendar that has gone out of date",
      )
      assertEquals(
        emptyList(),
        DATE_PATTERN.findAll(payload.toString()).map { it.value }.toList(),
        "a refusal names no date, whichever refusal it is: [$payload]",
      )
    }

  /** One listed school with a dated Early Action round, and the committed calendar -- the shape most of these tests want. */
  private fun listedStudentWithAnEarlyActionDeadline(): StudentId {
    val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
    val college = AdmissionsTestDb.seedListedCollege(student, "Wire College")
    AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
    TestTimelineTestDb.seedCommittedCalendar()
    return student
  }

  /** Every key name in [element], at any depth -- the sweep the description assertion reads. */
  private fun keysOf(element: JsonElement): Set<String> =
    when (element) {
      is JsonObject -> element.keys + element.values.flatMap { keysOf(it) }
      is JsonArray -> element.flatMap { keysOf(it) }.toSet()
      is JsonPrimitive -> emptySet()
    }

  /** The seeded calendar, read back through the DAO so the permitted-date set is the rows that are actually there. */
  private object AdmissionTestCalendarRows {
    fun seeded() =
      ed.unicoach.db.dao.AdmissionTestCalendarDao
        .list(AdmissionsTestDb.sqlSession)
        .getOrThrow()
  }

  private companion object {
    const val NOON_EASTERN_2026_09_11 = "2026-09-11T16:00:00Z"
    const val PAST_THE_EXPIRY = "2026-11-01T16:00:00Z"

    /**
     * Noon Eastern on 2027-04-20: past the SAT 2027-05-01 sitting's 2027-04-16
     * registration deadline, and before the committed calendar's 2027-07-31
     * expiry.
     */
    const val NOON_EASTERN_2027_04_20 = "2027-04-20T16:00:00Z"

    /** The SAT sitting that is both closed for registration and unannounced for scores on that clock. */
    const val CLOSED_SPRING_SITTING = "2027-05-01"

    /** Today, on [NOON_EASTERN_2026_09_11]'s clock, in the coach's zone. */
    val TODAY: LocalDate = LocalDate.parse("2026-09-11")

    /** The anchor the seeded November 1 round resolves to for a 2027 graduation. */
    val RESOLVED_ANCHOR: LocalDate = LocalDate.parse("2026-11-01")

    /** An ISO date anywhere in a payload string, `why` sentences included. */
    val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    /** The fields the description routes the coach through, and which the wire must therefore carry. */
    val DESCRIBED_FIELDS =
      listOf(
        "usable_for_anchor",
        "data_availability",
        "closing_as_published",
        "closing_resolved",
        "resolution_basis",
        "fee_waiver_by",
        "fee_waiver_by_is_derived",
        "registration_deadline",
        "registration_deadline_time",
        "late_or_changes_deadline_label",
        "general_sequence",
        "anchor_rejections",
        "closed_deadlines",
        "refusal",
        "calendar.expired",
      )

    /** One live sitting whose publisher publishes no later deadline -- the real ACT/SAT shape this used to omit keys for. */
    val NO_LATE_DEADLINE_CALENDAR =
      listOf(
        TestTimelineTestDb.administration(
          test = AdmissionTest.ACT,
          testDate = "2026-11-07",
          registrationDeadline = "2026-10-23",
          lateRegistrationDeadline = null,
          scoreReleaseDate = "2026-11-20",
          expiresOn = "2027-07-31",
        ),
      )

    /** A calendar already past its expiry on [PAST_THE_EXPIRY]'s clock. */
    val EXPIRED_CALENDAR =
      listOf(
        TestTimelineTestDb.administration(
          test = AdmissionTest.SAT,
          testDate = "2026-11-07",
          registrationDeadline = "2026-10-23",
          lateRegistrationDeadline = "2026-10-27",
          scoreReleaseDate = "2026-11-20",
          expiresOn = "2026-10-31",
        ),
      )
  }
}
