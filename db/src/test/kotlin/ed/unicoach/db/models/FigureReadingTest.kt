package ed.unicoach.db.models

import ed.unicoach.db.dao.CorruptPersistedValueException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

  @Test
  fun `of round-trips every status -- value-bearing to Present, value-free to Absent`() {
    for (status in FigureStatus.entries) {
      val value = if (status.valueBearing) 11000 else null
      val reading = FigureReading.of(value, status, COLUMN, KEY)
      assertEquals(status, reading.status, "status must survive the decode for [$status]")
      if (status.valueBearing) {
        assertEquals(FigureReading.Present(11000, ValueBearingStatus.entries.first { it.status == status }), reading)
      } else {
        assertEquals(FigureReading.Absent(AbsenceStatus.entries.first { it.status == status }), reading)
      }
    }
  }

  @Test
  fun `a value-bearing status with no value is refused, naming the column and the row`() {
    for (status in FigureStatus.entries.filter { it.valueBearing }) {
      val thrown =
        assertFailsWith<CorruptPersistedValueException>("for [$status]") {
          FigureReading.of<Int>(null, status, COLUMN, KEY)
        }
      assertTrue(thrown.message!!.contains(COLUMN), thrown.message!!)
      assertTrue(thrown.message!!.contains(KEY), thrown.message!!)
      assertTrue(thrown.message!!.contains(status.value), thrown.message!!)
    }
  }

  @Test
  fun `a value under a value-free status is refused from the other side`() {
    for (status in FigureStatus.entries.filterNot { it.valueBearing }) {
      val thrown =
        assertFailsWith<CorruptPersistedValueException>("for [$status]") {
          FigureReading.of(11000, status, COLUMN, KEY)
        }
      assertTrue(thrown.message!!.contains("11000"), thrown.message!!)
      assertTrue(thrown.message!!.contains(COLUMN), thrown.message!!)
      assertTrue(thrown.message!!.contains(KEY), thrown.message!!)
    }
  }

  private companion object {
    const val COLUMN = "price_figures.[amount_usd]"
    const val KEY = "college_id=[c0ffee] tuition_and_fees/in_state/not_applicable/2022-23"
  }
}
