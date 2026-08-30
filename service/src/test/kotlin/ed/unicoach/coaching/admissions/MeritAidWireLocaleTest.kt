package ed.unicoach.coaching.admissions

import ed.unicoach.common.util.Share
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals

/**
 * The spoken forms are pinned to [Locale.US] (RFC 148 D4). Under a JVM whose
 * default FORMAT locale groups and separates differently -- de-DE renders
 * `5,2` and `$12.500` -- an ambient-locale formatter would speak one number
 * while the payload carried another, which is exactly the disagreement D4 is
 * there to prevent: the label and the figure are emitted from one construct so
 * that they AGREE, and a locale is not allowed to break that.
 *
 * The default is restored in a `finally` so this test cannot leak a locale into
 * the rest of the suite.
 */
class MeritAidWireLocaleTest {
  @Test
  fun `the spoken share, average and cycle read the same under a non-US default locale`() {
    val original = Locale.getDefault(Locale.Category.FORMAT)
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY)

      val share = requireNotNull(Share.ofOrNull(part = 260, whole = 5000))
      assertEquals(5.2, share.percent)
      assertEquals(
        "5.2% of all full-time freshmen received non-need (merit) aid",
        MeritAidWire.shareLabel(share),
        "a comma decimal would disagree with the [5.2] the payload carries",
      )

      val whole = requireNotNull(Share.ofOrNull(part = 500, whole = 2000))
      assertEquals("25% of all full-time freshmen received non-need (merit) aid", MeritAidWire.shareLabel(whole))

      assertEquals(
        "the average non-need (merit) aid was $12,500 - a grant the student never pays back, " +
          "reported for last year's class, not an offer to this student",
        MeritAidWire.averageLabel(12500),
        "a [12.500] grouping would disagree with the [12500] the payload carries",
      )

      assertEquals("2024-25", CdsCitation.cycleLabel(2024))
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, original)
    }
  }
}
