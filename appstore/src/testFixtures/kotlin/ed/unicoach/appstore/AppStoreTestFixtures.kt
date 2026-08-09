package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

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
}
