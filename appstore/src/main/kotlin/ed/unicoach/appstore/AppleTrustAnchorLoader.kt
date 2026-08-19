package ed.unicoach.appstore

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Reads the pinned Apple trust anchors from a bundled PEM (RFC 112). Pinning
 * Apple's own root rather than trusting the JVM's default store is the point: a
 * notification chain that validates against any other CA is not Apple's.
 *
 * The resource is a constructor parameter so a different anchor set can be
 * substituted — by a test forcing a load failure, or by a future deployment
 * pinning a different root — without reaching through a global.
 *
 * Failure on: a missing or unparseable resource — a broken anchor set is a
 * broken build, not a runtime condition. Composition roots call [load] once at
 * boot and inject the certificates themselves onward, so that failure surfaces
 * as a refused start rather than a 401 on every notification.
 */
class AppleTrustAnchorLoader(
  /** Apple Root CA – G3, DER-converted from Apple's certificate authority page. */
  private val resourcePath: String = "/apple-root-ca-g3.pem",
) {
  fun load(): Result<Set<X509Certificate>> =
    runCatching {
      val bytes =
        AppleTrustAnchorLoader::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
          ?: throw IllegalStateException("Apple trust anchor resource [$resourcePath] is missing from the classpath")
      val certificates =
        bytes.inputStream().use { stream ->
          CertificateFactory
            .getInstance("X.509")
            .generateCertificates(stream)
            .map { it as X509Certificate }
        }
      if (certificates.isEmpty()) {
        throw IllegalStateException("Apple trust anchor resource [$resourcePath] contains no certificate")
      }
      certificates.toSet()
    }
}
