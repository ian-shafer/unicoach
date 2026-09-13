package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NewSourceDocument]'s own `init` guard (RFC 200): you cannot claim to know
 * whether a school's file is still there without saying when you looked.
 *
 * The rule is stated twice on purpose -- here and as the
 * `source_documents_checked_when_known` CHECK -- and only this half can name
 * the writer. Without a test the Kotlin half is a comment: the DB would still
 * refuse the row, hundreds of rows later and with nothing to point at.
 *
 * The `unknown` case is the interesting one, and it is LEGAL with a date. An
 * `unknown` WITH a day is "we asked and got no usable answer"; an `unknown`
 * with NO day is "we never asked". Both keep the link, and the date is the
 * only thing that tells a broken checker from a stale one (RFC 182), so a
 * guard that refused a dated `unknown` would delete that distinction.
 */
class NewSourceDocumentTest {
  private fun document(
    status: SourceUrlStatus,
    checkedOn: LocalDate?,
  ) = NewSourceDocument(
    collegeId = CollegeId(UUID.fromString("00000000-0000-4000-8000-000000000001")),
    source = MoneySource.COMMON_DATA_SET,
    academicYear = AcademicYear(2024),
    sourceUrl = "https://example.edu/cds-2024-25.pdf",
    archiveUrl = null,
    sourceUrlStatus = status,
    sourceUrlCheckedOn = checkedOn,
  )

  @Test
  fun `found with no date is refused, and the message names the document`() {
    val failure = assertFailsWith<IllegalArgumentException> { document(SourceUrlStatus.FOUND, null) }
    val message = failure.message ?: ""
    assertTrue(message.contains("[found]"), "the refusal must name the verdict: [$message]")
    assertTrue(
      message.contains("https://example.edu/cds-2024-25.pdf"),
      "the refusal must name the document it is about: [$message]",
    )
  }

  @Test
  fun `not_found with no date is refused -- the one verdict that drops an anchor`() {
    val failure = assertFailsWith<IllegalArgumentException> { document(SourceUrlStatus.NOT_FOUND, null) }
    assertTrue(
      (failure.message ?: "").contains("[not_found]"),
      "the refusal must name the verdict: [${failure.message}]",
    )
  }

  @Test
  fun `found and not_found are ACCEPTED once they carry the day we looked`() {
    val day = LocalDate.of(2026, 9, 13)
    for (status in listOf(SourceUrlStatus.FOUND, SourceUrlStatus.NOT_FOUND)) {
      val built = document(status, day)
      assertEquals(status, built.sourceUrlStatus)
      assertEquals(day, built.sourceUrlCheckedOn)
    }
  }

  @Test
  fun `unknown WITH a date is legal -- we asked and got no usable answer`() {
    val day = LocalDate.of(2026, 9, 13)
    val built = document(SourceUrlStatus.UNKNOWN, day)
    assertEquals(SourceUrlStatus.UNKNOWN, built.sourceUrlStatus)
    assertEquals(day, built.sourceUrlCheckedOn)
  }

  @Test
  fun `unknown with NO date is legal -- we never asked`() {
    val built = document(SourceUrlStatus.UNKNOWN, null)
    assertEquals(SourceUrlStatus.UNKNOWN, built.sourceUrlStatus)
    assertNull(built.sourceUrlCheckedOn)
  }

  @Test
  fun `every status is covered by this file, so a new member cannot arrive untested`() {
    assertEquals(
      setOf(SourceUrlStatus.UNKNOWN, SourceUrlStatus.FOUND, SourceUrlStatus.NOT_FOUND),
      SourceUrlStatus.entries.toSet(),
      "a new SourceUrlStatus member needs its own date rule stated above",
    )
  }
}
