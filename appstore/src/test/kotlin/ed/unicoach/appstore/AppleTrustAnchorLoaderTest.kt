package ed.unicoach.appstore

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the committed trust anchor to being the genuine Apple Root CA – G3
 * (RFC 112). Everything [AppleJwsVerifier] proves rests on these bytes, and
 * nothing else in the tree would notice if they were replaced by a certificate
 * that merely bears the name.
 */
class AppleTrustAnchorLoaderTest {
  private val anchors = AppleTrustAnchorLoader().load().getOrThrow()

  @Test
  fun `the bundled anchor set parses to exactly one certificate`() {
    assertEquals(1, anchors.size, "the pinned anchor set is Apple Root CA - G3 alone")
  }

  @Test
  fun `it is the Apple root, issued by itself`() {
    val root = anchors.single()
    assertTrue(
      root.subjectX500Principal.name.contains("CN=Apple Root CA - G3"),
      "the anchor's subject is Apple Root CA - G3; found [${root.subjectX500Principal.name}]",
    )
    assertEquals(root.subjectX500Principal, root.issuerX500Principal, "a root is its own issuer")
  }

  @Test
  fun `it is self-signed under its own key`() {
    // Name equality is cosmetic; the signature check is what proves these bytes
    // are Apple's root rather than any certificate that copied its DN.
    val root = anchors.single()
    root.verify(root.publicKey)
  }

  @Test
  fun `it is not expired`() {
    anchors.single().checkValidity()
  }

  @Test
  fun `a missing resource fails, naming the path it looked for`() {
    // The injectable path is the seam the composition root's boot-time failure
    // rests on; this proves it is a real one and that the failure says where.
    val absent = "/apple-root-ca-that-was-never-bundled.pem"
    val failure = AppleTrustAnchorLoader(absent).load().exceptionOrNull()
    assertTrue(failure is IllegalStateException, "a missing anchor resource is a broken build; found [$failure]")
    assertTrue(failure.message!!.contains(absent), "the failure names the resource it looked for; found [${failure.message}]")
  }
}
