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

  @Test
  fun `SEND_EMAIL round-trips through fromValue`() {
    assertEquals(JobType.SEND_EMAIL, JobType.fromValue("SEND_EMAIL"))
    assertEquals("SEND_EMAIL", JobType.SEND_EMAIL.value)
  }
}
