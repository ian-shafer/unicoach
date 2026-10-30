package ed.unicoach.db.models

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollegeLevelTest {
  private val college = CollegeId(UUID.randomUUID())

  @Test
  fun `each published code and a NULL read as their own level`() {
    assertEquals(CollegeLevel.FOUR_YEAR, CollegeLevel.of(CollegeLevel.FOUR_OR_MORE_YEARS_CODE, college))
    assertEquals(CollegeLevel.NOT_FOUR_YEAR, CollegeLevel.of(CollegeLevel.TWO_TO_FOUR_YEARS_CODE, college))
    assertEquals(CollegeLevel.NOT_FOUR_YEAR, CollegeLevel.of(CollegeLevel.LESS_THAN_TWO_YEARS_CODE, college))
    assertEquals(CollegeLevel.NOT_HELD, CollegeLevel.of(null, college))
  }

  @Test
  fun `a code outside the published set is refused, naming the code and the college, never read as not four-year`() {
    // The 0055 CHECK keeps such a code out of the table, so this is the read's
    // own guard: a widened domain must fail here, not tell a family the
    // school's programs run shorter than four years.
    for (code in listOf(0, 4, -1)) {
      val e = assertFailsWith<IllegalStateException> { CollegeLevel.of(code, college) }
      val message = e.message.orEmpty()
      assertTrue("inst_level=[$code]" in message, message)
      assertTrue("college_id=[${college.value}]" in message, message)
    }
  }
}
