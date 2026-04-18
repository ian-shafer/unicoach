package ed.unicoach.queue

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobTypeTest {
  @Test
  fun `fromValue returns enum for valid string`() {
    val result = JobType.fromValue("TEST_JOB")
    assertEquals(JobType.TEST_JOB, result)
  }

  @Test
  fun `fromValue returns null for unknown string`() {
    val result = JobType.fromValue("totally_unknown_type")
    assertNull(result)
  }
}
