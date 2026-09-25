package ed.unicoach.coaching.testing

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.chat.PayloadDateGuard
import ed.unicoach.coaching.COACH_ZONE_LABEL
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.AdmissionTestStateProgrammeFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.SchoolYearBasis
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TestChooser
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

      val found = PayloadDateGuard.datesIn(payload).toSet()
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
        PayloadDateGuard.datesIn(payload),
        "past expiry the tool returns dates for nothing: [$payload]",
      )
      // And the positive control for the sweep above: the pattern does fire on a
      // payload that DOES carry a date, so an empty result means what it says.
      assertTrue(PayloadDateGuard.isoDatePatternFires(), "the date pattern must be able to fire")
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
        PayloadDateGuard.datesIn(payload),
        "a refusal names no date, whichever refusal it is: [$payload]",
      )
    }

  /**
   * THE ANSWER THIS SLICE EXISTS FOR, ON THE WIRE. A junior in a state that
   * requires the school-day sitting gets the record, whose school year it is, the
   * page it was read from and the day a human read it -- and IS ASKED NOTHING.
   *
   * "Asked nothing" is asserted on the PAYLOAD and not on prose: the state was
   * inferred from a residency answer the cost conversation already collected, so
   * the section carries the state and puts no question of its own to the family
   * (D10).
   */
  @Test
  fun `a junior in a required state gets the record, its year, its source and its checked_on, and is asked nothing`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2028)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val payload = tool.execute(student, buildJsonObject {})
      val section = schoolDaySectionOf(payload)

      assertEquals("grade 11 (junior year)", payload.getValue("year_of_school").jsonPrimitive.content)
      assertEquals("TN", section.getValue("state").jsonPrimitive.content)
      assertEquals(
        true,
        section
          .getValue("held")
          .jsonPrimitive.content
          .toBoolean(),
      )
      assertEquals("required", section.getValue("participation").jsonPrimitive.content)
      assertEquals(
        AcademicYear(AdmissionTestStateProgrammeFixture.DEFAULT_SCHOOL_YEAR).label,
        section.getValue("school_year").jsonPrimitive.content,
        "the school year is the AcademicYear label and never a bare number",
      )
      assertEquals(
        SchoolDayCopy.schoolYearBasisLabelOf(SchoolYearBasis.STATED_BY_SOURCE),
        section.getValue("school_year_basis_label").jsonPrimitive.content,
        "whose year it is travels with the year",
      )
      assertTrue(
        section
          .getValue("source_url")
          .jsonPrimitive.content
          .startsWith("https://"),
        "the state's own page must be on the wire: [$section]",
      )
      assertEquals(
        AdmissionTestStateProgrammeFixture.DEFAULT_CHECKED_ON.toString(),
        section.getValue("checked_on").jsonPrimitive.content,
      )
      assertEquals(
        emptyList(),
        section.values.filter { it.toString().contains('?') },
        "the section asks the family for nothing: the state was inferred, not requested: [$section]",
      )
    }

  /**
   * EVERY KEY, ALWAYS, ON EVERY ARM. An absent key is a silence the model fills
   * (this tool's own rule, twice found by RFC 197's review), and three of the
   * four arms carry almost no data -- which is exactly where a renderer is
   * tempted to omit.
   */
  @Test
  fun `the school-day section carries every key on every arm, as explicit nulls`() =
    runTest {
      val held = TestTimelineTestDb.createStudent(graduationYear = 2028)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(held, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))
      val gap = TestTimelineTestDb.createStudent(graduationYear = 2028)

      val heldKeys = schoolDaySectionOf(tool.execute(held, buildJsonObject {})).keys
      val gapSection = schoolDaySectionOf(tool.execute(gap, buildJsonObject {}))

      assertEquals(SCHOOL_DAY_KEYS, heldKeys.toList(), "the section's keys are decided, in this order")
      assertEquals(SCHOOL_DAY_KEYS, gapSection.keys.toList(), "an arm with no record carries the same keys")
      assertEquals(
        emptyList(),
        SCHOOL_DAY_KEYS.filterNot { it in gapSection },
        "no key may be omitted on the arm that has least to say",
      )
      assertEquals(JsonNull, gapSection.getValue("state"))
      // `held` is the one key that is a BOOLEAN on every arm, because "do we
      // hold a current reading for this state" has an answer in all four cases.
      // It is a statement about OUR data and never about the state: `why` is
      // what says the state may well run one and we have not read it.
      assertEquals(
        false,
        gapSection
          .getValue("held")
          .jsonPrimitive.content
          .toBoolean(),
        "held is false when we hold no current reading, and says nothing about what the state does",
      )
      assertEquals(JsonNull, gapSection.getValue("school_must_offer"))
      assertEquals(JsonNull, gapSection.getValue("state_funded"))
    }

  /**
   * A SENIOR PAST THE PROGRAMME'S GRADE gets the grade fact and no suggestion the
   * sitting is ahead of them. The section states what the STATE does; when it
   * comes round for this student is the coach's to work out from the grades and
   * the year of school, both of which are on the wire.
   */
  @Test
  fun `a senior past the programme grade gets the grade fact and no claim the sitting is ahead`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val payload = tool.execute(student, buildJsonObject {})
      val section = schoolDaySectionOf(payload)

      assertEquals("grade 12 (senior year)", payload.getValue("year_of_school").jsonPrimitive.content)
      assertEquals("11", section.getValue("lowest_grade").jsonPrimitive.content)
      assertEquals("11", section.getValue("highest_grade").jsonPrimitive.content)
      val why = section.getValue("why").jsonPrimitive.content
      assertEquals(
        emptyList(),
        WORDS_THAT_PUT_THE_SITTING_AHEAD.filter { why.contains(it) },
        "the section states the state's grades and never places the sitting in this student's future: [$why]",
      )
    }

  /** A student with no money profile at all: the gap is named, no state is claimed, and no programme is invented. */
  @Test
  fun `a student with no money profile gets no state and no invented programme`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val section = schoolDaySectionOf(tool.execute(student, buildJsonObject {}))

      assertEquals(JsonNull, section.getValue("state"))
      assertEquals(JsonNull, section.getValue("participation"))
      assertEquals(JsonNull, section.getValue("source_url"))
      val why = section.getValue("why").jsonPrimitive.content
      assertTrue(why.contains("money profile"), "the gap must be named for what it is: [$why]")
      assertTrue(
        why.contains("residency question belongs to the cost conversation"),
        "and the coach must be told not to ask for the state here: [$why]",
      )
    }

  /** No school on the list is no anchor, and the state fact does not depend on one: the section is still there. */
  @Test
  fun `a student with an empty college list still gets the state section`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2028)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val payload = tool.execute(student, buildJsonObject {})

      assertEquals(JsonNull, payload.getValue("anchor"), "there is no school on the list to anchor on")
      assertEquals("TN", schoolDaySectionOf(payload).getValue("state").jsonPrimitive.content)
    }

  /**
   * PAST GRADUATION. There is no application cycle and no year of school to place
   * the sitting in, and the section makes no claim that needs one -- it says what
   * the state does, and the grades are the state's.
   */
  @Test
  fun `a student past graduation gets the section with no claim about their own year`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2024)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val payload = tool.execute(student, buildJsonObject {})
      val why = schoolDaySectionOf(payload).getValue("why").jsonPrimitive.content

      assertEquals("past their expected high-school graduation", payload.getValue("year_of_school").jsonPrimitive.content)
      assertEquals(
        emptyList(),
        WORDS_THAT_PUT_THE_SITTING_AHEAD.filter { why.contains(it) },
        "a student who has left school must read no sentence placing the sitting ahead of them: [$why]",
      )
    }

  /** Where the state chose no test, the section names none and says who does -- both ways round (D13). */
  @Test
  fun `a district-chooses state and a student-chooses state each name no test and say who chooses`() =
    runTest {
      val districtStudent = TestTimelineTestDb.createStudent(graduationYear = 2028)
      val studentChoiceStudent = TestTimelineTestDb.createStudent(graduationYear = 2028)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(districtStudent, "TN")
      TestTimelineTestDb.answerResidency(studentChoiceStudent, "SC")
      TestTimelineTestDb.seedProgrammes(
        listOf(
          AdmissionTestStateProgrammeFixture.programme(state = "TN"),
          AdmissionTestStateProgrammeFixture.programme(
            state = "SC",
            participation = SchoolDayParticipation.OFFERED,
            choice = SchoolDayTestChoice.ChosenLocally(TestChooser.STUDENT),
          ),
        ),
      )

      val district = schoolDaySectionOf(tool.execute(districtStudent, buildJsonObject {}))
      val studentChoice = schoolDaySectionOf(tool.execute(studentChoiceStudent, buildJsonObject {}))

      assertEquals("district", district.getValue("test_chooser").jsonPrimitive.content)
      assertEquals(JsonNull, district.getValue("test"), "a state that chose no test names none")
      assertEquals(JsonNull, district.getValue("test_name"))
      assertEquals("the district chooses which test", district.getValue("chooser_label").jsonPrimitive.content)
      assertEquals("student", studentChoice.getValue("test_chooser").jsonPrimitive.content)
      assertEquals(JsonNull, studentChoice.getValue("test"))
      assertEquals("the student chooses which test", studentChoice.getValue("chooser_label").jsonPrimitive.content)
      assertEquals("offered", studentChoice.getValue("participation").jsonPrimitive.content)
    }

  /**
   * A STATE WE HOLD NO ROW FOR IS A GAP IN OUR DATA. It is never the state
   * running nothing: that claim needs a state page saying so, and a wrong one is
   * this slice's own failure pointing the other way.
   */
  @Test
  fun `a state we hold no row for says we hold no record, never that the state runs nothing`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2028)
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "ND")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val section = schoolDaySectionOf(tool.execute(student, buildJsonObject {}))

      assertEquals("ND", section.getValue("state").jsonPrimitive.content)
      assertEquals(
        false,
        section
          .getValue("held")
          .jsonPrimitive.content
          .toBoolean(),
        "we hold no reading for this state, which is what held=false says and all it says",
      )
      val why = section.getValue("why").jsonPrimitive.content
      assertTrue(why.contains("gap in our data"), "the absence must be named as ours: [$why]")
      assertTrue(
        why.contains("never a statement that the state runs none"),
        "and must refuse the negative claim in the same sentence: [$why]",
      )
    }

  /**
   * THE PROPERTY, OVER EVERY ARM: no rendered school-day text names a school or
   * says "your school".
   *
   * Asserted as a sweep rather than per case, so a sentence added later cannot
   * reintroduce the one claim this table can never support -- whether one named
   * high school runs its test day. The `school_must_offer` arm is included
   * deliberately, because it is the arm that comes nearest to a school-level
   * sentence and is the one a renderer would be tempted to write that way.
   */
  @Test
  fun `no rendered school-day section names a school or says your school`() =
    runTest {
      val sections = everySchoolDayArm()
      assertTrue(sections.size >= EVERY_ARM, "the sweep must cover every arm, or it proves nothing: [$sections]")

      sections.forEach { section ->
        // Every rendered value EXCEPT `what_the_state_cannot_answer`, which is
        // the one field whose whole subject is the question we refuse to answer:
        // it names the student's own school in order to say that this record
        // cannot speak for it, which is the opposite of the claim swept for.
        val rendered =
          section
            .filterKeys { it != "what_the_state_cannot_answer" }
            .values
            .filterIsInstance<JsonPrimitive>()
            .joinToString(" ") { it.content }
        assertEquals(
          emptyList(),
          WORDS_THAT_CLAIM_A_SCHOOL.filter { rendered.contains(it, ignoreCase = true) },
          "the state answers the state's question only: [$rendered]",
        )
        assertTrue(
          !rendered.contains(SCHOOL_ON_THE_LIST),
          "no school on the student's list may be named in a state fact: [$rendered]",
        )
      }
    }

  /** The refusal arm is UNCHANGED: no date, and no programme section at all (RFC 208 section 6). */
  @Test
  fun `the refusal payload carries no school-day section`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))
      TestTimelineTestDb.seedCalendar(EXPIRED_CALENDAR)
      val expiredTool = AdmissionTestTimelineChatTool(TestTimelineTestDb.serviceAt(PAST_THE_EXPIRY))

      val payload = expiredTool.execute(student, buildJsonObject {})

      assertTrue(
        SCHOOL_DAY_SECTION !in payload,
        "the refusal carries no date, and checked_on is a date: [$payload]",
      )
      assertEquals(
        emptyList(),
        PayloadDateGuard.datesIn(payload),
        "a state record on a refusal would put a date back on it: [$payload]",
      )
    }

  /** One section per arm of [SchoolDayProgramme], rendered through the real tool -- the sweep's input. */
  private suspend fun everySchoolDayArm(): List<JsonObject> {
    TestTimelineTestDb.seedCommittedCalendar()
    val held = TestTimelineTestDb.createStudent(graduationYear = 2028)
    AdmissionsTestDb.seedListedCollege(held, SCHOOL_ON_THE_LIST)
    TestTimelineTestDb.answerResidency(held, "VA")
    val noRow = TestTimelineTestDb.createStudent(graduationYear = 2028)
    TestTimelineTestDb.answerResidency(noRow, "ND")
    val lapsed = TestTimelineTestDb.createStudent(graduationYear = 2028)
    TestTimelineTestDb.answerResidency(lapsed, "MA")
    val declined = TestTimelineTestDb.createStudent(graduationYear = 2028)
    TestTimelineTestDb.declineResidency(declined)
    val noProfile = TestTimelineTestDb.createStudent(graduationYear = 2028)
    TestTimelineTestDb.seedProgrammes(
      listOf(
        // The must-offer arm: the strongest sentence in the corpus, and the one
        // nearest a school-level claim.
        AdmissionTestStateProgrammeFixture.programme(state = "VA", schoolMustOffer = true),
        // A LAPSED reading, and its checked_on moves with its expiry: the schema
        // refuses a row that expired before it was read, and a fixture that
        // ignored that would be writing a row the corpus could never hold.
        AdmissionTestStateProgrammeFixture.programme(
          state = "MA",
          checkedOn = LocalDate.parse("2025-09-01"),
          expiresOn = LocalDate.parse("2026-09-10"),
        ),
      ),
    )

    return listOf(held, noRow, lapsed, declined, noProfile)
      .map { schoolDaySectionOf(tool.execute(it, buildJsonObject {})) }
  }

  /** The section under test, named once so no case reaches for the key by hand. */
  private fun schoolDaySectionOf(payload: JsonObject): JsonObject = payload.getValue(SCHOOL_DAY_SECTION).jsonObject

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

    /** The section this RFC 208 half is about, named once. */
    const val SCHOOL_DAY_SECTION = "school_day_programme"

    /** The name of the one school on the sweep student's list -- and so a string no state fact may contain. */
    const val SCHOOL_ON_THE_LIST = "Sweep College"

    /** How many arms the property sweep must cover: held, no row, lapsed, declined, and no profile. */
    const val EVERY_ARM = 5

    /**
     * Every key of the school-day section, in the order it is said (RFC 208
     * section 6), asserted as a LIST so a key cannot be dropped on the arm that
     * has least to say -- which is exactly where an omission would be invisible.
     */
    val SCHOOL_DAY_KEYS =
      listOf(
        "state",
        "held",
        "participation",
        "test_chooser",
        "test",
        "test_name",
        "chooser_label",
        "lowest_grade",
        "highest_grade",
        "state_funded",
        "school_must_offer",
        "school_year",
        "school_year_basis",
        "school_year_basis_label",
        "source_url",
        "archive_url",
        "checked_on",
        "expires_on",
        "what_the_state_cannot_answer",
        "why",
      )

    /**
     * Ways a sentence would place the state's sitting in THIS student's future.
     * The section states what the state does and in which grades; when that comes
     * round for one student is the coach's to work out from those grades and
     * `year_of_school`, and a section that did it for them would be wrong for
     * every senior and every student past graduation.
     */
    val WORDS_THAT_PUT_THE_SITTING_AHEAD = listOf("ahead", "this year", "next year", "coming up", "will sit")

    /** The claim this table can never support: a sentence about the student's OWN school. */
    val WORDS_THAT_CLAIM_A_SCHOOL = listOf("your school", "their school", "at school this year")

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
        "school_day_programme",
        "school_year_basis",
        "state_funded",
        "school_must_offer",
        "chooser_label",
        "what_the_state_cannot_answer",
        "archive_url",
        "checked_on",
        "lowest_grade",
        "highest_grade",
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
