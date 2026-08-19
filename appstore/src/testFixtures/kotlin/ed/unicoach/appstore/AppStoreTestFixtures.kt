package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.ConfigFactory
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.security.auth.x500.X500Principal

/**
 * Canonical App Store fixtures (RFC 110): a generated test P-256 key, and
 * builders for the two signed artifacts (transaction and renewal-info JWSes)
 * and the Get All Subscription Statuses 200 body that embeds them. Every
 * fixture JWS is signed with the test key via java-jwt, so it is a structurally
 * real, decodable JWS (wire-faithful; signatures are never verified by design —
 * the verify path trusts TLS, not the x5c chain).
 */
object AppStoreTestFixtures {
  val keyPair: KeyPair =
    KeyPairGenerator
      .getInstance("EC")
      .apply { initialize(ECGenParameterSpec("secp256r1")) }
      .generateKeyPair()

  val privateKey: ECPrivateKey get() = keyPair.private as ECPrivateKey
  val publicKey: ECPublicKey get() = keyPair.public as ECPublicKey

  /** The test key as appstore.conf carries it: one-line base64 PKCS#8. */
  val privateKeyBase64: String = Base64.getEncoder().encodeToString(keyPair.private.encoded)

  const val ISSUER_ID = "57246542-96fe-1a63-e053-0824d011072a"
  const val KEY_ID = "TESTKEY001"
  const val BUNDLE_ID = "coach.uni.UnicoachiOS"
  const val PRODUCT_ID = "coach.uni.UnicoachiOS.monthly10"
  const val ORIGINAL_TRANSACTION_ID = "100000123456789"
  const val TRANSACTION_ID = "200000123456789"

  fun credentials(): AppStoreCredentials = AppStoreCredentials(issuerId = ISSUER_ID, keyId = KEY_ID, privateKey = privateKey)

  fun authTokens(clock: java.time.Clock = java.time.Clock.systemUTC()): AppStoreAuthTokens =
    AppStoreAuthTokens(credentials(), BUNDLE_ID, clock)

  /** An ES256 JWS over [payload], signed with the test key. */
  fun sign(payload: Map<String, Any>): String = JWT.create().withPayload(payload).sign(Algorithm.ECDSA256(publicKey, privateKey))

  /**
   * A signed StoreKit 2 transaction JWS. [expiresDate] null models a
   * non-auto-renewable entry (no expiry); Apple timestamps are epoch
   * milliseconds.
   */
  fun signedTransaction(
    originalTransactionId: String = ORIGINAL_TRANSACTION_ID,
    transactionId: String = TRANSACTION_ID,
    productId: String = PRODUCT_ID,
    purchaseDate: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    expiresDate: Instant? = Instant.parse("2026-09-01T00:00:00Z"),
  ): String =
    sign(
      buildMap {
        put("originalTransactionId", originalTransactionId)
        put("transactionId", transactionId)
        put("productId", productId)
        put("bundleId", BUNDLE_ID)
        put("purchaseDate", purchaseDate.toEpochMilli())
        if (expiresDate != null) put("expiresDate", expiresDate.toEpochMilli())
        put("type", "Auto-Renewable Subscription")
      },
    )

  /** A signed renewal-info JWS, optionally carrying [gracePeriodExpiresDate]. */
  fun signedRenewalInfo(
    originalTransactionId: String = ORIGINAL_TRANSACTION_ID,
    productId: String = PRODUCT_ID,
    gracePeriodExpiresDate: Instant? = null,
  ): String =
    sign(
      buildMap {
        put("originalTransactionId", originalTransactionId)
        put("productId", productId)
        put("autoRenewStatus", 1L)
        if (gracePeriodExpiresDate != null) put("gracePeriodExpiresDate", gracePeriodExpiresDate.toEpochMilli())
      },
    )

  /** One `lastTransactions` entry of a status response. */
  class LastTransaction(
    val status: Int,
    val signedTransactionInfo: String,
    val signedRenewalInfo: String = signedRenewalInfo(),
  )

  /**
   * A Get All Subscription Statuses 200 body embedding [lastTransactions] in
   * one subscription group.
   */
  fun statusResponseBody(vararg lastTransactions: LastTransaction): String =
    buildJsonObject {
      put("environment", "Sandbox")
      put("bundleId", BUNDLE_ID)
      put(
        "data",
        buildJsonArray {
          add(
            buildJsonObject {
              put("subscriptionGroupIdentifier", "21000001")
              put(
                "lastTransactions",
                buildJsonArray {
                  for (transaction in lastTransactions) {
                    add(
                      buildJsonObject {
                        put("originalTransactionId", ORIGINAL_TRANSACTION_ID)
                        put("status", transaction.status)
                        put("signedTransactionInfo", transaction.signedTransactionInfo)
                        put("signedRenewalInfo", transaction.signedRenewalInfo)
                      },
                    )
                  }
                },
              )
            },
          )
        },
      )
    }.toString()

  /** The canonical active-subscription 200 body: one entry, status 1. */
  fun activeStatusResponseBody(
    transactionId: String = TRANSACTION_ID,
    purchaseDate: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    expiresDate: Instant = Instant.parse("2026-09-01T00:00:00Z"),
  ): String =
    statusResponseBody(
      LastTransaction(
        status = 1,
        signedTransactionInfo =
          signedTransaction(
            transactionId = transactionId,
            purchaseDate = purchaseDate,
            expiresDate = expiresDate,
          ),
      ),
    )

  // ---------------------------------------------------------------------------
  // Notification signing (RFC 112): locally minted x5c chains
  // ---------------------------------------------------------------------------

  /**
   * A locally minted certificate chain and the leaf key that signs under it —
   * everything [AppleJwsVerifier] inspects about an inbound JWS.
   *
   * [certificates] is leaf-first, exactly as RFC 7515 orders `x5c`; [root] is
   * the anchor a verifier under test is pinned to. Every negative case here is a
   * parameter of [certificateChain] rather than a hand-built JWS, so the
   * positive and negative fixtures differ in one axis at a time.
   */
  class TestCertificateChain(
    val root: X509Certificate,
    val certificates: List<X509Certificate>,
    private val leafKey: PrivateKey,
  ) {
    val leaf: X509Certificate get() = certificates.first()

    /** The chain as a JWS `x5c` header value: standard base64 DER, leaf first. */
    private val x5c: List<String> get() = certificates.map { Base64.getEncoder().encodeToString(it.encoded) }

    /**
     * A JWS carrying this chain as `x5c`. [algorithm] defaults to a genuine
     * ES256 signature by the leaf key; the algorithm-confusion cases pass
     * `Algorithm.none()` or an HMAC instead, so the only thing that differs from
     * an accepted notification is the thing under test.
     */
    fun sign(
      payload: Map<String, Any>,
      algorithm: Algorithm = Algorithm.ECDSA256(leaf.publicKey as ECPublicKey, leafKey as ECPrivateKey),
    ): String =
      JWT
        .create()
        .withHeader(mapOf("x5c" to x5c))
        .withPayload(payload)
        .sign(algorithm)

    /**
     * A genuinely signed JWS over an arbitrary payload string. The claim-map API
     * above can only express a JSON object, so this is the only way to sign the
     * payload shapes a verifier must still refuse — valid JSON that is not an
     * object.
     */
    fun signRawPayload(payloadJson: String): String {
      val header = """{"alg":"ES256","x5c":[${x5c.joinToString(",") { "\"$it\"" }}]}"""
      val encoder = Base64.getUrlEncoder().withoutPadding()
      val headerSegment = encoder.encodeToString(header.toByteArray(Charsets.UTF_8))
      val payloadSegment = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
      val signature =
        Algorithm
          .ECDSA256(leaf.publicKey as ECPublicKey, leafKey as ECPrivateKey)
          .sign(headerSegment.toByteArray(Charsets.UTF_8), payloadSegment.toByteArray(Charsets.UTF_8))
      return "$headerSegment.$payloadSegment.${encoder.encodeToString(signature)}"
    }
  }

  /** Serial numbers are unique per minted certificate, so no two fixtures collide. */
  private val CERTIFICATE_SERIAL = AtomicLong(1)

  private fun generateKeyPair(): KeyPair =
    KeyPairGenerator
      .getInstance("EC")
      .apply { initialize(ECGenParameterSpec("secp256r1")) }
      .generateKeyPair()

  /**
   * Mints one certificate. [issuer] null makes it self-signed (a root). The
   * marker extension is non-critical, as Apple's is, so a verifier that does not
   * look for it would ignore it — which is exactly why
   * [AppleJwsVerifier.NOTIFICATION_SIGNER_OID] is checked explicitly.
   */
  private fun issue(
    commonName: String,
    subjectKey: PublicKey,
    issuer: X509Certificate?,
    issuerKey: PrivateKey,
    notBefore: Instant,
    notAfter: Instant,
    isCertificateAuthority: Boolean,
    notificationSigner: Boolean = false,
  ): X509Certificate {
    val subject = X500Principal("CN=$commonName")
    val serial = BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement())
    val builder =
      if (issuer == null) {
        JcaX509v3CertificateBuilder(subject, serial, Date.from(notBefore), Date.from(notAfter), subject, subjectKey)
      } else {
        JcaX509v3CertificateBuilder(issuer, serial, Date.from(notBefore), Date.from(notAfter), subject, subjectKey)
      }
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCertificateAuthority))
    if (notificationSigner) {
      builder.addExtension(ASN1ObjectIdentifier(AppleJwsVerifier.NOTIFICATION_SIGNER_OID), false, DERNull.INSTANCE)
    }
    return JcaX509CertificateConverter().getCertificate(
      builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)),
    )
  }

  /**
   * Mints a fresh root → intermediate → leaf chain, the shape Apple signs
   * notifications with.
   *
   * Every axis a negative case needs is a parameter: [notificationSigner] false
   * omits the marker OID, [leafNotBefore]/[leafNotAfter] put the leaf outside
   * the verifier's clock, [includeIntermediate] false truncates the chain,
   * [includeRoot] chooses between Apple's three-entry wire shape and the
   * two-entry one, and [trailing] appends a certificate that is not the anchor.
   * Each call mints a NEW root, so two calls are mutually foreign by
   * construction — that is the "foreign root" case.
   */
  fun certificateChain(
    notificationSigner: Boolean = true,
    leafNotBefore: Instant = Instant.now().minus(Duration.ofDays(1)),
    leafNotAfter: Instant = Instant.now().plus(Duration.ofDays(365)),
    includeIntermediate: Boolean = true,
    includeRoot: Boolean = true,
    trailing: X509Certificate? = null,
  ): TestCertificateChain {
    // The CAs' window spans every leaf window a case might ask for, in both
    // directions, so a test that moves the verifier's clock is exercising the
    // LEAF's validity rather than tripping over an issuer that had not been
    // minted yet — Apple's own root is a decade old and good for another.
    val caNotBefore = Instant.now().minus(Duration.ofDays(3650))
    val caNotAfter = Instant.now().plus(Duration.ofDays(3650))

    val rootKeys = generateKeyPair()
    val root =
      issue("Unicoach Test Root CA", rootKeys.public, null, rootKeys.private, caNotBefore, caNotAfter, isCertificateAuthority = true)

    val intermediateKeys = generateKeyPair()
    val intermediate =
      issue(
        "Unicoach Test WWDR",
        intermediateKeys.public,
        root,
        rootKeys.private,
        caNotBefore,
        caNotAfter,
        isCertificateAuthority = true,
      )

    val leafKeys = generateKeyPair()
    val leaf =
      issue(
        "Unicoach Test Notification Signer",
        leafKeys.public,
        intermediate,
        intermediateKeys.private,
        leafNotBefore,
        leafNotAfter,
        isCertificateAuthority = false,
        notificationSigner = notificationSigner,
      )

    val certificates =
      buildList {
        add(leaf)
        if (includeIntermediate) add(intermediate)
        if (includeRoot) add(root)
        if (trailing != null) add(trailing)
      }
    return TestCertificateChain(root, certificates, leafKeys.private)
  }

  // ---------------------------------------------------------------------------
  // App Store Server Notification V2 payloads (RFC 112)
  // ---------------------------------------------------------------------------

  /** Apple's wire spelling of the environment a notification was raised in. */
  const val PRODUCTION_ENVIRONMENT = "Production"
  const val SANDBOX_ENVIRONMENT = "Sandbox"

  /**
   * The [AppStoreConfig] a notification is held against: the target check reads
   * only `bundleId` and `environment`, so credentials stay unconfigured. Built
   * through the production parser rather than a hand-made object, so a fixture
   * can never express a config the real loader would refuse.
   */
  fun appStoreConfig(
    environment: String = "production",
    bundleId: String = BUNDLE_ID,
  ): AppStoreConfig =
    AppStoreConfig
      .from(
        ConfigFactory.parseString(
          """
          appStore.environment = "$environment"
          appStore.bundleId = "$bundleId"
          appStore.connectTimeout = "10s"
          appStore.requestTimeout = "15s"
          """.trimIndent(),
        ),
      ).getOrThrow()

  /**
   * A signed V2 notification, [chain]-signed, wrapping a `data` block whose
   * `signedTransactionInfo` is signed by the SAME chain — the shape Apple sends
   * and the shape both verifications run over.
   *
   * [signedTransactionInfo] is a parameter rather than always derived so a test
   * can nest a transaction signed by a *different* chain (pinning that the
   * nested JWS is verified, not merely decoded), or omit it entirely (Apple's
   * TEST notification).
   */
  fun signedNotification(
    chain: TestCertificateChain,
    notificationType: String = "DID_RENEW",
    subtype: String? = "BILLING_RECOVERY",
    notificationUuid: String? = UUID.randomUUID().toString(),
    bundleId: String? = BUNDLE_ID,
    environment: String? = PRODUCTION_ENVIRONMENT,
    signedTransactionInfo: String? = chain.sign(transactionClaims()),
  ): String =
    chain.sign(
      buildMap {
        if (notificationUuid != null) put("notificationUUID", notificationUuid)
        put("notificationType", notificationType)
        if (subtype != null) put("subtype", subtype)
        put("version", "2.0")
        put("signedDate", Instant.now().toEpochMilli())
        put("data", dataBlock(bundleId, environment, signedTransactionInfo))
      },
    )

  /**
   * A signed V2 notification carrying a `summary` block and NO `data` — the
   * `RENEWAL_EXTENSION` / `SUMMARY` shape, which names a bundle and environment
   * but no transaction.
   */
  fun signedSummaryNotification(
    chain: TestCertificateChain,
    notificationType: String = "RENEWAL_EXTENSION",
    bundleId: String = BUNDLE_ID,
    environment: String = PRODUCTION_ENVIRONMENT,
  ): String =
    chain.sign(
      buildMap {
        put("notificationUUID", UUID.randomUUID().toString())
        put("notificationType", notificationType)
        put("subtype", "SUMMARY")
        put("version", "2.0")
        put("signedDate", Instant.now().toEpochMilli())
        put(
          "summary",
          mapOf(
            "environment" to environment,
            "appAppleId" to 1234567890L,
            "bundleId" to bundleId,
            "productId" to PRODUCT_ID,
            "requestIdentifier" to UUID.randomUUID().toString(),
            "succeededCount" to 0L,
            "failedCount" to 0L,
          ),
        )
      },
    )

  /**
   * A signed V2 notification carrying NEITHER `data` nor `summary` — a payload
   * shape Apple has never sent, which the verifier must refuse to guess at.
   */
  fun signedShapelessNotification(chain: TestCertificateChain): String =
    chain.sign(
      mapOf(
        "notificationUUID" to UUID.randomUUID().toString(),
        "notificationType" to "DID_RENEW",
        "version" to "2.0",
        "signedDate" to Instant.now().toEpochMilli(),
      ),
    )

  /** The `data` block of a V2 notification; a null [signedTransactionInfo] models the TEST notification. */
  private fun dataBlock(
    bundleId: String?,
    environment: String?,
    signedTransactionInfo: String?,
  ): Map<String, Any> =
    buildMap {
      put("appAppleId", 1234567890L)
      if (bundleId != null) put("bundleId", bundleId)
      put("bundleVersion", "1")
      if (environment != null) put("environment", environment)
      if (signedTransactionInfo != null) {
        put("signedTransactionInfo", signedTransactionInfo)
        put("status", 1L)
      }
    }

  /**
   * The claims of a StoreKit 2 transaction, as a map ready for chain signing. A
   * null [originalTransactionId] models the verified-but-unusable transaction
   * the notification verifier refuses rather than guesses at.
   */
  fun transactionClaims(
    originalTransactionId: String? = ORIGINAL_TRANSACTION_ID,
    transactionId: String = TRANSACTION_ID,
    productId: String = PRODUCT_ID,
    purchaseDate: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    expiresDate: Instant = Instant.parse("2026-09-01T00:00:00Z"),
  ): Map<String, Any> =
    buildMap {
      if (originalTransactionId != null) put("originalTransactionId", originalTransactionId)
      put("transactionId", transactionId)
      put("productId", productId)
      put("bundleId", BUNDLE_ID)
      put("purchaseDate", purchaseDate.toEpochMilli())
      put("expiresDate", expiresDate.toEpochMilli())
      put("type", "Auto-Renewable Subscription")
    }
}
