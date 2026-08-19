package ed.unicoach.appstore

import ed.unicoach.common.util.DataSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads one inbound App Store Server Notification V2 (RFC 112): the endpoint's
 * entire authentication, since the request carries no session and nothing about
 * its source is trusted.
 *
 * Order, and why: bound the string → verify the outer JWS → read the target
 * block (`data` when present, else `summary`) and hold its `bundleId` and
 * `environment` against [config] → verify the nested
 * `data.signedTransactionInfo`, when there is one, and read its
 * `originalTransactionId`. The target check precedes the nested verification so
 * a notification for another bundle costs one chain validation, not two.
 *
 * `data.status` and `data.signedRenewalInfo` are deliberately unread: the
 * notification is a TRIGGER, and the state comes from Apple's API.
 */
class AppleNotificationVerifier(
  private val verifier: AppleJwsVerifier,
  private val config: AppStoreConfig,
) {
  /**
   * Failure (bug-grade, distinct from [AppleNotificationOutcome.Untrusted]) on:
   * an Apple-signed payload missing `notificationUUID` or `notificationType`;
   * one carrying neither a `data` nor a `summary` object; a target block
   * carrying no `bundleId` or no `environment`; an unknown environment string;
   * or a verified transaction carrying no `originalTransactionId`. A new Apple
   * payload shape must be looked at, not guessed at — the same posture
   * [AppStoreServerApi] takes toward an unknown status integer.
   *
   * `suspend` because up to two X.509 chain validations and their ES256
   * signature checks run per notification — CPU-bound work that must not sit on
   * a caller's thread. The switch to [Dispatchers.Default] lives here rather
   * than at the route handler, so calling this is safe from any context and no
   * caller has to know it is expensive.
   */
  suspend fun read(signedPayload: String): Result<AppleNotificationOutcome> =
    withContext(Dispatchers.Default) {
      runCatching {
        // The cheap refusal, before any chain work.
        val bounded = jwsBoundsFailure(signedPayload, MAX_JWS, "signedPayload")
        if (bounded != null) return@runCatching AppleNotificationOutcome.Untrusted(bounded)

        val payload =
          when (val verification = verifier.verified(signedPayload)) {
            is JwsVerification.Refused -> {
              return@runCatching AppleNotificationOutcome.Untrusted(
                "signedPayload is not an Apple-signed notification: [${verification.refusal.reason()}]",
              )
            }

            is JwsVerification.Verified -> {
              verification.payload
            }
          }

        val targetJson = targetBlock(payload)
        val target = readTarget(targetJson)
        val foreign = foreignTarget(target)
        if (foreign != null) return@runCatching foreign

        val transaction =
          when (val nested = nestedTransaction(targetJson)) {
            is NestedTransaction.Absent -> null
            is NestedTransaction.Untrusted -> return@runCatching AppleNotificationOutcome.Untrusted(nested.reason)
            is NestedTransaction.Verified -> nested.payload
          }

        AppleNotificationOutcome.Verified(buildNotification(payload, target, transaction))
      }
    }

  /** Who the notification is for, read once from whichever block carries it. */
  private class Target(
    val bundleId: String,
    val environment: AppStoreEnvironment,
  )

  /**
   * The block naming the notification's target. A V2 payload carries exactly
   * one: the summary-bearing types (`RENEWAL_EXTENSION` / `SUMMARY`) carry a
   * `summary` and no `data` at all.
   */
  private fun targetBlock(payload: JsonObject): JsonObject =
    payload[DATA_FIELD]?.jsonObject
      ?: payload[SUMMARY_FIELD]?.jsonObject
      ?: throw IllegalArgumentException(
        "Apple-signed notification carries neither a [$DATA_FIELD] nor a [$SUMMARY_FIELD] block — " +
          "a new Apple payload shape must be looked at, not guessed at. Raw: [$payload]",
      )

  /**
   * The target block's identity. Both fields are required: a block naming only
   * one of them is a payload-shape change, not an absence, and
   * [requiredText]/[parseEnvironment] refuse it rather than guess.
   */
  private fun readTarget(targetJson: JsonObject): Target =
    Target(
      bundleId = targetJson.requiredText(BUNDLE_ID_FIELD, SUBJECT),
      environment = parseEnvironment(targetJson.requiredText(ENVIRONMENT_FIELD, SUBJECT)),
    )

  /**
   * How the notification's target differs from the one this box serves, or null
   * to apply it. The mismatching values travel untouched; the sentence about
   * them is the route handler's job. App Store
   * Connect holds separate Production and Sandbox notification URLs, so a
   * crossing is misconfiguration or an attack; both deserve refusal.
   */
  private fun foreignTarget(target: Target): AppleNotificationOutcome.ForeignTarget? =
    when {
      target.bundleId != config.bundleId -> {
        AppleNotificationOutcome.ForeignTarget.WrongBundle(targeted = target.bundleId, served = config.bundleId)
      }

      target.environment != config.environment -> {
        AppleNotificationOutcome.ForeignTarget.WrongEnvironment(targeted = target.environment, served = config.environment)
      }

      else -> {
        null
      }
    }

  /**
   * The verified nested transaction, if the notification names one. Rule 1 of
   * the design lives here: the nested JWS goes through [AppleJwsVerifier] rather
   * than being decoded. The outer signature does cover this string, so decoding
   * would be defensible — verifying keeps the rule absolute and grep-checkable,
   * at the cost of one more chain validation.
   */
  private fun nestedTransaction(target: JsonObject): NestedTransaction {
    val signed = target[SIGNED_TRANSACTION_FIELD]?.jsonPrimitive?.content ?: return NestedTransaction.Absent
    return when (val verification = verifier.verified(signed)) {
      is JwsVerification.Verified -> {
        NestedTransaction.Verified(verification.payload)
      }

      is JwsVerification.Refused -> {
        NestedTransaction.Untrusted(
          "notification's nested [$SIGNED_TRANSACTION_FIELD] is not Apple-signed: [${verification.refusal.reason()}]",
        )
      }
    }
  }

  /** Whether the notification named a transaction, and whether it was Apple's. */
  private sealed interface NestedTransaction {
    data object Absent : NestedTransaction

    data class Verified(
      val payload: JsonObject,
    ) : NestedTransaction

    data class Untrusted(
      val reason: String,
    ) : NestedTransaction
  }

  /**
   * The notification the rest of the system sees, assembled from the three
   * places its fields come from: the outer payload, the target block already
   * read into [target], and the verified nested transaction when there was one.
   */
  private fun buildNotification(
    payload: JsonObject,
    target: Target,
    transaction: JsonObject?,
  ): AppleNotification =
    AppleNotification(
      notificationUuid = payload.requiredText("notificationUUID", SUBJECT),
      notificationType = payload.requiredText("notificationType", SUBJECT),
      subtype = payload.optionalText("subtype"),
      environment = target.environment,
      bundleId = target.bundleId,
      originalTransactionId = transaction?.requiredText("originalTransactionId", SUBJECT),
    )

  companion object {
    /**
     * A V2 notification nests up to three JWSes, each carrying its own x5c
     * chain; 32 KiB bounds hostile input with room to spare. [jwsBoundsFailure]
     * holds the string to it.
     */
    val MAX_JWS: DataSize = DataSize.ofKibibytes(32)

    private const val DATA_FIELD = "data"
    private const val SUMMARY_FIELD = "summary"
    private const val BUNDLE_ID_FIELD = "bundleId"
    private const val ENVIRONMENT_FIELD = "environment"
    private const val SIGNED_TRANSACTION_FIELD = "signedTransactionInfo"

    /** Names this reader's JSON in every [requiredText] refusal. */
    private const val SUBJECT = "Apple-signed notification"

    /** Named from [AppleJwsVerifier] rather than restated, so the two never drift apart. */
    private const val X5C = AppleJwsVerifier.X5C_HEADER

    /**
     * A [JwsRefusal] as the operator-facing prose an
     * [AppleNotificationOutcome.Untrusted] reason carries. Built HERE — the
     * boundary where the taxonomy leaves [AppleJwsVerifier] for text — and not
     * at the check that raised it, so a caller wanting to tell a forged chain
     * from ordinary malformed noise still can.
     */
    private fun JwsRefusal.reason(): String =
      when (this) {
        is JwsRefusal.MalformedToken -> {
          "JWS is not a decodable token: [${cause.message}]"
        }

        is JwsRefusal.AbsentCertificateChain -> {
          "JWS header carries no [$X5C] certificate chain"
        }

        is JwsRefusal.UnreadableCertificateChain -> {
          "JWS header's [$X5C] is not a list of certificates: [${cause.message}]"
        }

        is JwsRefusal.EmptyCertificateChain -> {
          "JWS header's [$X5C] certificate chain is empty"
        }

        is JwsRefusal.UndecodableCertificate -> {
          "JWS [$X5C] entry [$index] is not a decodable X.509 certificate: [${cause.message}]"
        }

        is JwsRefusal.NoCertificateBelowTrustAnchor -> {
          "JWS [$X5C] certificate chain carries no certificate below the trust anchor"
        }

        is JwsRefusal.ChainNotTrusted -> {
          "JWS [$X5C] certificate chain does not validate to a pinned Apple trust anchor: [${cause.message}]"
        }

        is JwsRefusal.MissingNotificationSignerMarker -> {
          "JWS [$X5C] leaf [$leafSubject] is not an Apple notification signer " +
            "(marker extension [${AppleJwsVerifier.NOTIFICATION_SIGNER_OID}] absent)"
        }

        is JwsRefusal.LeafKeyNotEllipticCurve -> {
          "JWS [$X5C] leaf key is not an EC key, so it cannot have produced an ES256 signature: [$keyAlgorithm]"
        }

        is JwsRefusal.SignatureInvalid -> {
          "JWS ES256 signature does not verify under the [$X5C] leaf's key: [${cause.message}]"
        }

        is JwsRefusal.MalformedPayload -> {
          "JWS payload is not a readable JSON object: [${cause.message}]"
        }
      }

    /** Apple spells the two environments `Sandbox`/`Production`; [AppStoreEnvironment.parse] takes either casing. */
    private fun parseEnvironment(value: String): AppStoreEnvironment =
      AppStoreEnvironment.parse(value)
        ?: throw IllegalArgumentException(
          "Apple-signed notification names environment [$value], which is neither Sandbox nor Production",
        )
  }
}
