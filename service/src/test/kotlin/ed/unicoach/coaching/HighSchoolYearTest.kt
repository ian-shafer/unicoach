package ed.unicoach.coaching

import ed.unicoach.db.models.PartialDate
import java.time.LocalDate
import java.time.Month
import java.time.Year
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The year-of-school derivation (RFC 186). Pure: no clock, no database -- the
 * caller supplies today, which is the one place a [java.time.Clock] is read.
 */
class HighSchoolYearTest {
  private val autumn = LocalDate.of(2026, 9, 11)

  private fun gradYear(year: Int): PartialDate = PartialDate.YearOnly(Year.of(year))

  @Test
  fun `each graduation year one further out is one grade lower`() {
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(autumn, gradYear(2027)))
    assertEquals(HighSchoolYear.JUNIOR, HighSchoolYear.of(autumn, gradYear(2028)))
    assertEquals(HighSchoolYear.SOPHOMORE, HighSchoolYear.of(autumn, gradYear(2029)))
    assertEquals(HighSchoolYear.FRESHMAN, HighSchoolYear.of(autumn, gradYear(2030)))
  }

  @Test
  fun `the school year turns over on 1 July, not on 1 January`() {
    val classOf2028 = gradYear(2028)
    // January is in the list because the calendar year turns over there and the
    // school year does not: a rule written on today.year alone passes the two
    // June dates and fails this one.
    assertEquals(HighSchoolYear.JUNIOR, HighSchoolYear.of(LocalDate.of(2027, 1, 4), classOf2028))
    assertEquals(HighSchoolYear.JUNIOR, HighSchoolYear.of(LocalDate.of(2027, 6, 30), classOf2028))
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(LocalDate.of(2027, 7, 1), classOf2028))
  }

  @Test
  fun `a graduation year behind the running academic year is past graduation, never grade 13`() {
    assertEquals(HighSchoolYear.PAST_GRADUATION, HighSchoolYear.of(autumn, gradYear(2026)))
    assertEquals(HighSchoolYear.PAST_GRADUATION, HighSchoolYear.of(autumn, gradYear(2020)))
  }

  @Test
  fun `a graduation year further out than grade 9 is before high school, never grade 8`() {
    assertEquals(HighSchoolYear.BEFORE_HIGH_SCHOOL, HighSchoolYear.of(autumn, gradYear(2031)))
    assertEquals(HighSchoolYear.BEFORE_HIGH_SCHOOL, HighSchoolYear.of(autumn, gradYear(2035)))
  }

  @Test
  fun `the month and day are never consulted, so a year-only date yields the same grade`() {
    val yearOnly = gradYear(2028)
    val fullDate = PartialDate.FullDate(LocalDate.of(2028, 6, 15))
    val yearAndMonth = PartialDate.YearAndMonth(Year.of(2028), Month.JUNE)
    // A June date and a December date in the same year are the same grade: the
    // rule reads the year, which is why a date recorded with no month still
    // answers the question.
    val decemberDate = PartialDate.FullDate(LocalDate.of(2028, 12, 31))

    assertEquals(HighSchoolYear.of(autumn, yearOnly), HighSchoolYear.of(autumn, fullDate))
    assertEquals(HighSchoolYear.of(autumn, yearOnly), HighSchoolYear.of(autumn, yearAndMonth))
    assertEquals(HighSchoolYear.of(autumn, yearOnly), HighSchoolYear.of(autumn, decemberDate))
  }

  @Test
  fun `the academic year ending is next calendar year from July onwards`() {
    assertEquals(Year.of(2027), HighSchoolYear.academicYearEnding(LocalDate.of(2026, 7, 1)))
    assertEquals(Year.of(2027), HighSchoolYear.academicYearEnding(LocalDate.of(2026, 12, 31)))
    assertEquals(Year.of(2027), HighSchoolYear.academicYearEnding(LocalDate.of(2027, 6, 30)))
  }

  @Test
  fun `a recorded date already behind today is past graduation before the July rollover`() {
    // Grade 12 by the year rule, but the day we hold has been and gone: saying
    // "expects to finish high school on 5 June 2027" and "grade 12" in one
    // sentence, three weeks after the date, is the contradiction this answers.
    val graduatedInJune = PartialDate.FullDate(LocalDate.of(2027, 6, 5))
    assertEquals(HighSchoolYear.PAST_GRADUATION, HighSchoolYear.of(LocalDate.of(2027, 6, 20), graduatedInJune))
    // On the day itself they are still a senior.
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(LocalDate.of(2027, 6, 5), graduatedInJune))
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(LocalDate.of(2027, 6, 4), graduatedInJune))
  }

  @Test
  fun `a month-only date passes at the end of its month, and a year-only date defers to the year rule`() {
    val june2027 = PartialDate.YearAndMonth(Year.of(2027), Month.JUNE)
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(LocalDate.of(2027, 6, 30), june2027))
    assertEquals(HighSchoolYear.PAST_GRADUATION, HighSchoolYear.of(LocalDate.of(2027, 7, 1), june2027))

    // Year-only: nothing inside 2027 can say the date has passed, so the
    // student stays a senior until the school year turns over.
    assertEquals(HighSchoolYear.SENIOR, HighSchoolYear.of(LocalDate.of(2027, 6, 20), gradYear(2027)))
  }

  @Test
  fun `every in-school constant's label names its own grade`() {
    HighSchoolYear.entries
      .filter { it.grade != null }
      .forEach { year ->
        assertTrue(year.label.startsWith("grade ${year.grade} "), "[$year] label and grade disagree: [${year.label}]")
      }
  }

  @Test
  fun `every label is written for a student to hear, not a grade number to read`() {
    assertEquals("grade 11 (junior year)", HighSchoolYear.JUNIOR.label)
    assertEquals("not yet in high school", HighSchoolYear.BEFORE_HIGH_SCHOOL.label)
    assertEquals("past their expected high-school graduation", HighSchoolYear.PAST_GRADUATION.label)
  }
}
