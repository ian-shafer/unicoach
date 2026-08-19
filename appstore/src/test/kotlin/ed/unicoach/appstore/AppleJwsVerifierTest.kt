package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins [AppleJwsVerifier] (RFC 112) — the whole authentication of the
 * notifications endpoint. Every chain here is locally minted, so each case
 * varies exactly one axis of a chain that would otherwise verify: that is what
 * makes a refusal attributable to the check under test rather than to a
 * differently-broken fixture.
 *
 * The refusal VARIANT is asserted, not prose: [JwsRefusal] names which check
 * fired, so a check that fires for the wrong reason is as broken as one that
 * does not fire. The text an operator reads is built at the display boundary
 * ([AppleNotificationVerifier]), and is asserted in that class's test.
 */
class AppleJwsVerifierTest {
  private val payload = mapOf("notificationUUID" to "abc", "notificationType" to "DID_RENEW")

  private fun verifierFor(
    chain: AppStoreTestFixtures.TestCertificateChain,
    clock: Clock = Clock.systemUTC(),
  ): AppleJwsVerifier = AppleJwsVerifier(setOf(chain.root), clock)

  /** Which check the verifier refused [jws] on — a refusal always names its check. */
  private fun refusal(
    chain: AppStoreTestFixtures.TestCertificateChain,
    jws: String,
    clock: Clock = Clock.systemUTC(),
  ): JwsRefusal {
    val outcome = verifierFor(chain, clock).verified(jws)
    return assertIs<JwsVerification.Refused>(outcome, "expected a refusal; got [$outcome]").refusal
  }

  /** The payload the verifier accepted from [jws]. */
  private fun verifiedPayload(
    chain: AppStoreTestFixtures.TestCertificateChain,
    jws: String,
    clock: Clock = Clock.systemUTC(),
  ): JsonObject {
    val outcome = verifierFor(chain, clock).verified(jws)
    return assertIs<JwsVerification.Verified>(outcome, "expected a verified payload; got [$outcome]").payload
  }

  // ---------------------------------------------------------------------------
  // Accepted wire shapes
  // ---------------------------------------------------------------------------

  @Test
  fun `a valid chain verifies and returns the signed payload`() {
    val chain = AppStoreTestFixtures.certificateChain()

    val verified = verifiedPayload(chain, chain.sign(payload))

    assertEquals("abc", verified["notificationUUID"]?.jsonPrimitive?.content)
    assertEquals("DID_RENEW", verified["notificationType"]?.jsonPrimitive?.content)
  }

  @Test
  fun `Apple's three-entry chain verifies — the anchor is stripped, not validated as a path element`() {
    // leaf, intermediate, root: the shape Apple actually sends. RFC 5280 §6.1
    // validates a path that EXCLUDES the anchor, so a verifier that fed this
    // through unchanged would refuse every real notification.
    val chain = AppStoreTestFixtures.certificateChain(includeRoot = true)
    assertEquals(3, chain.certificates.size)

    assertEquals("abc", verifiedPayload(chain, chain.sign(payload))["notificationUUID"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a two-entry chain verifies — both wire shapes are accepted`() {
    val chain = AppStoreTestFixtures.certificateChain(includeRoot = false)
    assertEquals(2, chain.certificates.size)

    assertEquals("abc", verifiedPayload(chain, chain.sign(payload))["notificationUUID"]?.jsonPrimitive?.content)
  }

  // ---------------------------------------------------------------------------
  // Chain refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a structurally identical chain from a foreign root is refused`() {
    // The verifier is pinned to its own root; the JWS carries another's. This is
    // the whole reason the anchor is bundled instead of the JVM's store trusted.
    val pinned = AppStoreTestFixtures.certificateChain()
    val foreign = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.ChainNotTrusted>(refusal(pinned, foreign.sign(payload)), "the chain check is what refuses a foreign root")
  }

  @Test
  fun `a leaf without the notification marker OID is refused`() {
    // Every certificate Apple issues chains to the same root, so the chain alone
    // does not say "notification signer" — this extension does.
    val chain = AppStoreTestFixtures.certificateChain(notificationSigner = false)

    val refused = assertIs<JwsRefusal.MissingNotificationSignerMarker>(refusal(chain, chain.sign(payload)))

    assertEquals(chain.leaf.subjectX500Principal.name, refused.leafSubject)
  }

  @Test
  fun `an expired leaf is refused against the injected clock`() {
    val chain =
      AppStoreTestFixtures.certificateChain(
        leafNotBefore = Instant.now().minus(Duration.ofDays(30)),
        leafNotAfter = Instant.now().minus(Duration.ofDays(1)),
      )

    assertIs<JwsRefusal.ChainNotTrusted>(refusal(chain, chain.sign(payload)))
  }

  @Test
  fun `a leaf outside its validity is accepted at an instant inside it`() {
    // The clock is a real input, not decoration: the same expired-today chain
    // verifies when the verifier's instant sits inside the leaf's window.
    val chain =
      AppStoreTestFixtures.certificateChain(
        leafNotBefore = Instant.now().minus(Duration.ofDays(30)),
        leafNotAfter = Instant.now().minus(Duration.ofDays(1)),
      )
    val insideWindow = Clock.fixed(Instant.now().minus(Duration.ofDays(10)), ZoneOffset.UTC)

    assertEquals(
      "abc",
      verifiedPayload(chain, chain.sign(payload), insideWindow)["notificationUUID"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `a truncated chain — leaf only, no intermediate — is refused`() {
    val chain = AppStoreTestFixtures.certificateChain(includeIntermediate = false, includeRoot = false)

    assertIs<JwsRefusal.ChainNotTrusted>(refusal(chain, chain.sign(payload)))
  }

  @Test
  fun `a foreign trailing certificate stays in the path and fails PKIX`() {
    // Only a trailing entry that IS the pinned anchor is dropped; anything else
    // an attacker appends must still be validated, and cannot be.
    val foreign = AppStoreTestFixtures.certificateChain()
    val chain = AppStoreTestFixtures.certificateChain(trailing = foreign.root)

    assertIs<JwsRefusal.ChainNotTrusted>(refusal(chain, chain.sign(payload)))
  }

  @Test
  fun `an absent x5c header is refused`() {
    val chain = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.AbsentCertificateChain>(refusal(chain, AppStoreTestFixtures.sign(payload)))
  }

  @Test
  fun `an empty x5c header is refused`() {
    val chain = AppStoreTestFixtures.certificateChain()
    val jws =
      JWT
        .create()
        .withHeader(mapOf("x5c" to emptyList<String>()))
        .withPayload(payload)
        .sign(Algorithm.ECDSA256(AppStoreTestFixtures.publicKey, AppStoreTestFixtures.privateKey))

    assertIs<JwsRefusal.EmptyCertificateChain>(refusal(chain, jws))
  }

  @Test
  fun `an undecodable x5c entry is refused`() {
    val chain = AppStoreTestFixtures.certificateChain()
    val jws =
      JWT
        .create()
        .withHeader(mapOf("x5c" to listOf("!!! not a base64 certificate !!!")))
        .withPayload(payload)
        .sign(Algorithm.ECDSA256(AppStoreTestFixtures.publicKey, AppStoreTestFixtures.privateKey))

    val refused = assertIs<JwsRefusal.UndecodableCertificate>(refusal(chain, jws))

    assertEquals(0, refused.index, "the refusal names which entry could not be decoded")
  }

  @Test
  fun `an x5c header whose entries are not certificates strings is refused, not thrown`() {
    // Hostile shapes are refusals like any other: nothing about an inbound
    // request may reach the caller as a throw. java-jwt renders the non-string
    // entries as strings rather than failing the header read, so it is the
    // certificate decode — not the header read — that refuses them.
    val chain = AppStoreTestFixtures.certificateChain()
    val jws =
      JWT
        .create()
        .withHeader(mapOf("x5c" to listOf(1, 2, 3)))
        .withPayload(payload)
        .sign(Algorithm.ECDSA256(AppStoreTestFixtures.publicKey, AppStoreTestFixtures.privateKey))

    assertEquals(0, assertIs<JwsRefusal.UndecodableCertificate>(refusal(chain, jws)).index)
  }

  // ---------------------------------------------------------------------------
  // Signature refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `alg none is refused — the algorithm is passed in, never read from the header`() {
    // The chain is genuine and the marker OID is present: only a verifier that
    // trusted the header's `alg` would accept an unsigned payload here.
    val chain = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.SignatureInvalid>(refusal(chain, chain.sign(payload, Algorithm.none())))
  }

  @Test
  fun `alg HS256 is refused — the leaf's public key is not a shared secret`() {
    val chain = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.SignatureInvalid>(
      refusal(chain, chain.sign(payload, Algorithm.HMAC256("the leaf public key, as an attacker would use it"))),
    )
  }

  @Test
  fun `a payload mutated after signing is refused`() {
    val chain = AppStoreTestFixtures.certificateChain()
    val segments = chain.sign(payload).split(".")
    val forged =
      Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString("""{"notificationUUID":"abc","notificationType":"REFUND"}""".toByteArray())

    assertIs<JwsRefusal.SignatureInvalid>(refusal(chain, "${segments[0]}.$forged.${segments[2]}"))
  }

  // ---------------------------------------------------------------------------
  // Payload shape
  // ---------------------------------------------------------------------------

  @Test
  fun `a correctly signed payload that is JSON but not an object is refused`() {
    // Genuinely signed by a genuinely valid chain: the only thing wrong with it
    // is the payload's shape. No non-object payload may ever come back as a
    // verified notification, and of the two shape checks that could catch it,
    // java-jwt's structural decode gets here first — which is why this refuses
    // as a malformed TOKEN, with [JwsRefusal.MalformedPayload] as the backstop
    // for a shape that decode lets through.
    val chain = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.MalformedToken>(refusal(chain, chain.signRawPayload("\"a bare JSON string\"")))
  }

  @Test
  fun `a structurally malformed JWS is refused`() {
    val chain = AppStoreTestFixtures.certificateChain()

    assertIs<JwsRefusal.MalformedToken>(refusal(chain, "not-a-jws"))
  }
}
