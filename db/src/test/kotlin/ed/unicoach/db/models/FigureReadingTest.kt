package ed.unicoach.db.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The [FigureReading] status partition (RFC 158, D3): [ValueBearingStatus]
 * and [AbsenceStatus] must together cover [FigureStatus] exactly along its
 * own `valueBearing` flag -- a new status added to one home and not the other
 * fails here, not at a DB CHECK.
 */
class FigureReadingTest {
  @Test
  fun `the two reading enums partition FigureStatus exactly by valueBearing`() {
    assertEquals(
      FigureStatus.entries.filter { it.valueBearing }.toSet(),
      ValueBearingStatus.entries.map { it.status }.toSet(),
      "ValueBearingStatus must name exactly the value-bearing statuses",
    )
    assertEquals(
      FigureStatus.entries.filterNot { it.valueBearing }.toSet(),
      AbsenceStatus.entries.map { it.status }.toSet(),
      "AbsenceStatus must name exactly the value-free statuses",
    )
  }
}
