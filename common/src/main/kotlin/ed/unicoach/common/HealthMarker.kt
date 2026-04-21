package ed.unicoach.common

import java.io.File

class HealthMarker(
  runDir: String,
  serviceName: String,
  private val nonce: String,
) {
  init {
    require(runDir.isNotBlank()) { "runDir must not be blank" }
    require(serviceName.isNotBlank()) { "serviceName must not be blank" }
    require(nonce.isNotBlank()) { "nonce must not be blank" }
  }

  private val file = File(runDir, "$serviceName.check")

  fun write() {
    file.parentFile.mkdirs()
    file.writeText(nonce)
  }

  fun delete() {
    file.delete()
  }

  companion object {
    /**
     * Creates a health marker from system properties, writes it, and
     * registers a shutdown hook for cleanup. No-ops gracefully when
     * system properties are absent (e.g. in test harnesses).
     *
     * Call once, after the service is fully initialized and ready to
     * accept traffic.
     */
    fun markHealthy() {
      val runDir = System.getProperty("run.dir")?.takeIf { it.isNotBlank() } ?: return
      val serviceName = System.getProperty("service.name")?.takeIf { it.isNotBlank() } ?: return
      val nonce = System.getProperty("health.nonce")?.takeIf { it.isNotBlank() } ?: return
      val marker = HealthMarker(runDir, serviceName, nonce)
      marker.write()
      Runtime.getRuntime().addShutdownHook(Thread { marker.delete() })
    }
  }
}
