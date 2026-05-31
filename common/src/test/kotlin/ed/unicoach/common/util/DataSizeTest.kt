package ed.unicoach.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DataSizeTest {
  @Test
  fun `ofBytes stores the byte count`() {
    assertEquals(8192, DataSize.ofBytes(8192).bytes)
  }

  @Test
  fun `ofBytes accepts a zero byte count`() {
    assertEquals(0, DataSize.ofBytes(0).bytes)
  }

  @Test
  fun `ofBytes rejects a negative byte count`() {
    assertFailsWith<IllegalArgumentException> { DataSize.ofBytes(-1) }
  }

  @Test
  fun `equal byte counts compare equal`() {
    assertEquals(DataSize.ofBytes(1024), DataSize.ofBytes(1024))
    assertNotEquals(DataSize.ofBytes(1024), DataSize.ofBytes(2048))
  }
}
