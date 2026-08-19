package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.serialization.json.JsonObject
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.Base64
import java.util.Date

/**
 * Outcome of holding one inbound JWS against the pinned Apple trust anchors.
 *
 * Refusing a forged, foreign or malformed JWS is what this check is FOR on an
 * unauthenticated endpoint, so a refusal is an ordinary answer here, not a
 * failure: it comes back as data, the same way [AppStoreSubscriptionLookup]
 * answers `NotFound`.
 */
sealed interface JwsVerification {
  data class Verified(
    val payload: JsonObject,
  ) : JwsVerification

  /**
   * [refusal] names which check refused the JWS and what it saw, as a value a
   * caller can switch on. It says nothing to the sender — every refusal is
   * equally unauthenticated, and the sender is told only that.
   */
  data class Refused(
    val refusal: JwsRefusal,
  ) : JwsVerification
}

/**
 * Which check in [AppleJwsVerifier] refused a JWS, and what that check saw: one
 * variant per check, so a forged chain stays distinguishable from a leaf
 * without the marker OID without anyone parsing English.
 *
 * The prose an operator reads is built where a refusal is DISPLAYED — see
 * [AppleNotificationVerifier] — never at the check that raised it.
 */
sealed interface JwsRefusal {
  /** java-jwt could not read the string as a JWS at all. */
  data class MalformedToken(
    val cause: Throwable,
  ) : JwsRefusal

  /** The header carries no `x5c` certificate chain. */
  data object AbsentCertificateChain : JwsRefusal

  /** The `x5c` header is present, and is not a list of strings. */
  data class UnreadableCertificateChain(
    val cause: Throwable,
  ) : JwsRefusal

  /** The `x5c` header is a list, and it is empty. */
  data object EmptyCertificateChain : JwsRefusal

  /** The `x5c` entry at [index] is not a decodable X.509 certificate. */
  data class UndecodableCertificate(
    val index: Int,
    val cause: Throwable,
  ) : JwsRefusal

  /** Every `x5c` entry is a pinned anchor, leaving no path below it to validate. */
  data object NoCertificateBelowTrustAnchor : JwsRefusal

  /** PKIX could not walk the chain to a pinned anchor at the verifier's instant. */
  data class ChainNotTrusted(
    val cause: Throwable,
  ) : JwsRefusal

  /** The leaf, named by [leafSubject], carries no Apple notification-signer marker extension. */
  data class MissingNotificationSignerMarker(
    val leafSubject: String,
  ) : JwsRefusal

  /** The leaf's public key is a [keyAlgorithm] key, which cannot have produced an ES256 signature. */
  data class LeafKeyNotEllipticCurve(
    val keyAlgorithm: String,
  ) : JwsRefusal

  /** The ES256 signature does not verify under the leaf's key. */
  data class SignatureInvalid(
    val cause: Throwable,
  ) : JwsRefusal

  /** The signature holds, and what it covers is not a JSON object. */
  data class MalformedPayload(
    val cause: Throwable,
  ) : JwsRefusal
}

/**
 * The x5c-chain-VERIFYING sibling of decode-only [AppleJws] (RFC 112): proves a
 * JWS was signed by Apple before its payload is read. Every inbound Apple-signed
 * payload goes through here — the outer App Store Server Notification and the
 * `signedTransactionInfo` nested inside it alike.
 *
 * The trust argument [AppleJws] cannot make: those bytes are fetched *from*
 * Apple over TLS, so the transport authenticates them; these arrive *at* us, so
 * only the signature can.
 *
 * Nothing cryptographic is written here — the chain walk is the JDK's PKIX
 * validator and the signature check is java-jwt's ES256.
 */
class AppleJwsVerifier(
  private val trustAnchors: Set<X509Certificate>,
  private val clock: Clock = Clock.systemUTC(),
  private val appleJws: AppleJws = AppleJws(),
) {
  /**
   * [JwsVerification.Refused], carrying the [JwsRefusal] variant for the check
   * that fired — all equally unauthenticated — on: a string java-jwt cannot
   * decode as a JWS; absent, non-list or empty `x5c`; an undecodable
   * certificate; a chain that PKIX cannot validate to a [trustAnchors] member at
   * the clock's instant; a leaf without Apple's notification marker OID; a
   * signature that does not verify under the leaf's key (which includes any
   * `alg` other than ES256); a payload that is not a JSON object.
   *
   * The exceptions the JDK and java-jwt throw over hostile input are carried
   * into refusals where they are raised; anything left to propagate — an empty
   * [trustAnchors] set, say — is a broken build, not an inbound request.
   */
  fun verified(jws: String): JwsVerification {
    val decoded =
      runCatching { JWT.decode(jws) }
        .getOrElse { return JwsVerification.Refused(JwsRefusal.MalformedToken(it)) }
    val chain =
      when (val chain = certificateChain(decoded)) {
        is CertificateChain.Unreadable -> return JwsVerification.Refused(chain.refusal)
        is CertificateChain.Certificates -> chain.certificates
      }
    val leaf = chain.first()
    val refusal = chainRefusal(chain) ?: signerRefusal(leaf) ?: signatureRefusal(jws, leaf)
    return refusal?.let { JwsVerification.Refused(it) } ?: payload(jws)
  }

  /** The `x5c` header's certificates, leaf first, exactly as RFC 7515 §4.1.6 orders them. */
  private fun certificateChain(decoded: DecodedJWT): CertificateChain {
    val encoded =
      runCatching { decoded.getHeaderClaim(X5C_HEADER).asList(String::class.java) }
        .getOrElse { return CertificateChain.Unreadable(JwsRefusal.UnreadableCertificateChain(it)) }
        ?: return CertificateChain.Unreadable(JwsRefusal.AbsentCertificateChain)
    if (encoded.isEmpty()) {
      return CertificateChain.Unreadable(JwsRefusal.EmptyCertificateChain)
    }
    val factory = CertificateFactory.getInstance("X.509")
    val certificates =
      encoded.mapIndexed { index, entry ->
        // RFC 7515 encodes x5c entries as standard base64 DER, not base64url.
        runCatching {
          factory.generateCertificate(Base64.getDecoder().decode(entry).inputStream()) as X509Certificate
        }.getOrElse {
          return CertificateChain.Unreadable(JwsRefusal.UndecodableCertificate(index, it))
        }
      }
    return CertificateChain.Certificates(certificates)
  }

  /** The `x5c` header read as certificates, or why it cannot be. */
  private sealed interface CertificateChain {
    data class Certificates(
      val certificates: List<X509Certificate>,
    ) : CertificateChain

    data class Unreadable(
      val refusal: JwsRefusal,
    ) : CertificateChain
  }

  /**
   * Why [chain] does not walk to a pinned anchor, or null when it does.
   *
   * Apple sends three entries — leaf, WWDR intermediate, and Apple Root CA – G3
   * — while RFC 5280 §6.1 validates a path that EXCLUDES the trust anchor, so a
   * trailing entry that is already an anchor is dropped before the [CertPath] is
   * built. Dropping it concedes nothing: trust comes from the bundled anchor, so
   * a trailing certificate an attacker appends is not a member, stays in the
   * path, and fails PKIX.
   *
   * `isRevocationEnabled = false`: an inbound Apple request must not block on an
   * outbound OCSP fetch to Apple, and the pinned root plus the marker OID carry
   * the trust. The accepted exposure is a leaf revoked but not yet expired.
   */
  private fun chainRefusal(chain: List<X509Certificate>): JwsRefusal? {
    val path = if (chain.size > 1 && chain.last() in trustAnchors) chain.dropLast(1) else chain
    if (path.isEmpty()) {
      return JwsRefusal.NoCertificateBelowTrustAnchor
    }
    val parameters =
      PKIXParameters(trustAnchors.map { TrustAnchor(it, null) }.toSet()).apply {
        isRevocationEnabled = false
        date = Date.from(clock.instant())
      }
    return runCatching {
      CertPathValidator
        .getInstance("PKIX")
        .validate(CertificateFactory.getInstance("X.509").generateCertPath(path), parameters)
    }.fold(
      onSuccess = { null },
      onFailure = { JwsRefusal.ChainNotTrusted(it) },
    )
  }

  /**
   * Why the leaf is not held to Apple's notification-signing marker extension,
   * or null when it carries one. Without this check, any certificate Apple
   * issues for any purpose could sign a forged notification, because they all
   * chain to the same root: it is this, not the chain, that makes the leaf a
   * *notification signer*.
   */
  private fun signerRefusal(leaf: X509Certificate): JwsRefusal? =
    if (leaf.getExtensionValue(NOTIFICATION_SIGNER_OID) != null) {
      null
    } else {
      JwsRefusal.MissingNotificationSignerMarker(leaf.subjectX500Principal.name)
    }

  /**
   * Why the signature does not verify under the leaf's key, or null when it
   * does. The algorithm is PASSED IN rather than read from the header — what
   * defeats the `alg: none` and `alg: HS256` confusion attacks. A non-EC leaf
   * key is refused here too.
   */
  private fun signatureRefusal(
    jws: String,
    leaf: X509Certificate,
  ): JwsRefusal? {
    val key =
      leaf.publicKey as? ECPublicKey
        ?: return JwsRefusal.LeafKeyNotEllipticCurve(leaf.publicKey.algorithm)
    return runCatching {
      JWT
        .require(Algorithm.ECDSA256(key, null))
        .build()
        .verify(jws)
    }.fold(
      onSuccess = { null },
      onFailure = { JwsRefusal.SignatureInvalid(it) },
    )
  }

  /**
   * The payload, decoded by [AppleJws] — the sibling that owns the module's one
   * JWS-payload decode — now that every check above holds, the signature being
   * this caller's trust reason. Last check and last step: a signed payload that
   * is not a JSON object is not a notification either.
   */
  private fun payload(jws: String): JwsVerification =
    appleJws.payload(jws).fold(
      onSuccess = { JwsVerification.Verified(it) },
      onFailure = { JwsVerification.Refused(JwsRefusal.MalformedPayload(it)) },
    )

  companion object {
    /**
     * RFC 7515 §4.1.6's certificate-chain header. Public so the one place that
     * renders a [JwsRefusal] for an operator names the same header this reads,
     * rather than restating the string.
     */
    const val X5C_HEADER = "x5c"

    /**
     * Apple's "App Store Server Notification signer" certificate extension.
     * Published by Apple alongside the Notifications V2 documentation; a leaf
     * carrying it is the only thing this box will accept a notification from.
     */
    const val NOTIFICATION_SIGNER_OID = "1.2.840.113635.100.6.11.1"
  }
}
