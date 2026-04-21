package ed.unicoach.common

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthMarkerTest {
  @Test
  fun `write creates file with nonce contents`() {
    val tempDir = createTempDir()
    try {
      val marker = HealthMarker(tempDir.absolutePath, "test-svc", "abc-123")
      marker.write()
      val file = File(tempDir, "test-svc.check")
      assertTrue(file.exists())
      assertEquals("abc-123", file.readText())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `write creates parent directories`() {
    val tempDir = createTempDir()
    val nested = File(tempDir, "sub/dir")
    try {
      val marker = HealthMarker(nested.absolutePath, "test-svc", "nonce-val")
      marker.write()
      val file = File(nested, "test-svc.check")
      assertTrue(file.exists())
      assertEquals("nonce-val", file.readText())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `delete removes file`() {
    val tempDir = createTempDir()
    try {
      val marker = HealthMarker(tempDir.absolutePath, "test-svc", "nonce-val")
      marker.write()
      val file = File(tempDir, "test-svc.check")
      assertTrue(file.exists())
      marker.delete()
      assertFalse(file.exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `delete is idempotent`() {
    val tempDir = createTempDir()
    try {
      val marker = HealthMarker(tempDir.absolutePath, "test-svc", "nonce-val")
      // delete without write — no exception expected
      marker.delete()
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `constructor rejects blank runDir`() {
    assertFailsWith<IllegalArgumentException> {
      HealthMarker("  ", "svc", "nonce")
    }
  }

  @Test
  fun `constructor rejects blank serviceName`() {
    assertFailsWith<IllegalArgumentException> {
      HealthMarker("/tmp", "  ", "nonce")
    }
  }

  @Test
  fun `constructor rejects blank nonce`() {
    assertFailsWith<IllegalArgumentException> {
      HealthMarker("/tmp", "svc", "  ")
    }
  }

  @Test
  fun `markHealthy noops when system properties are absent`() {
    System.clearProperty("run.dir")
    System.clearProperty("service.name")
    System.clearProperty("health.nonce")
    // Should not throw — graceful no-op
    HealthMarker.markHealthy()
  }

  private fun createTempDir(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "healthmarker-test-${System.nanoTime()}")
    dir.mkdirs()
    return dir
  }
}
