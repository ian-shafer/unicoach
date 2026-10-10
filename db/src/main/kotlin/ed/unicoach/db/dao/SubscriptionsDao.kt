package ed.unicoach.db.dao

import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.SubscriptionId
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Outcome of [SubscriptionsDao.upsert]: the write it performed, the no-op it
 * skipped, or the rebind it refused. Never a silent no-op — [Unchanged] and
 * [OwnedByOtherAccount] each name why no row changed.
 */
sealed interface SubscriptionUpsert {
  data class Applied(
    val subscription: Subscription,
  ) : SubscriptionUpsert

  /** The state-distinct guard skipped a no-op refresh; carries the untouched row. */
  data class Unchanged(
    val subscription: Subscription,
  ) : SubscriptionUpsert

  /**
   * The rebind the conflict arm refused; carries the row that already owns the
   * transaction.
   *
   * It is the shared-Apple-ID collision (RFC 244 §2): an Apple ID holds at most
   * one active subscription inside a subscription group, so a second unicoach
   * account signed in to the same Apple ID is handed the FIRST account's
   * `original_transaction_id`. It is refused and COUNTED, never turned into
   * coverage — nobody rides another account's subscription.
   */
  data class OwnedByOtherAccount(
    val existing: Subscription,
  ) : SubscriptionUpsert
}

/**
 * Data-access layer over the versioned `subscriptions` entity (RFC 110; the
 * owner is an ACCOUNT since RFC 244).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller. Every mutation source funnels through [upsert] — the verify
 * path and the Notifications-V2 webhook's refresh alike — so the versioning
 * trigger pair captures both. The `version` bump is DAO-supplied in the conflict arm, never
 * caller-supplied — versions are an audit trail here, not caller-facing
 * concurrency control.
 */
object SubscriptionsDao {
  /**
   * What makes a `subscriptions` row ENTITLE, right now: an entitling status and
   * a window that covers the DB clock's `NOW()`. `NOW()` and not a Kotlin
   * timestamp, consistent with the DB-clock-only posture (RFC 97) and with the
   * in-transaction gate read it serves — an elapsed window therefore stops
   * entitling whether or not Apple has notified us.
   *
   * ONE definition of "current" in this file, named rather than spelled at the
   * read, because the copy that drifts grants coaching nobody paid for. The
   * status set is not spelled here either: it is read from
   * [SubscriptionStatus.ENTITLING], the enum that owns it. The one remaining
   * copy is `subscriptions_user_current_idx`'s predicate (declared by
   * `subscriptions-key-on-account.sql`), which is how the index serves this
   * read and which `SubscriptionEntitlingStatusPinTest` pins against the same
   * enum — as the migrated database reports the predicate, so a later migration
   * that re-creates the index is covered too. A predicate that does not follow a
   * change to `entitles` silently drops the gate onto a sequential scan.
   *
   * A `val`, not a `const val`, because the list is derived rather than typed.
   */
  private val ENTITLING_CLAUSE =
    "status IN (" + SubscriptionStatus.ENTITLING.joinToString(",") { "'${it.value}'" } +
      ") AND period_start <= NOW() AND NOW() < period_end"

  /**
   * Insert-or-refresh keyed on `original_transaction_id`. The conflict arm sets
   * `version = subscriptions.version + 1` (satisfying `enforce_versioning`) and
   * updates product/status/period only when the existing row's `user_id`
   * matches AND the incoming state actually differs (`IS DISTINCT FROM` over
   * the four mutable columns). The state-distinct guard keeps an app-launch
   * re-verify from minting a version row (and bumping `updated_at`) when
   * Apple's state is unchanged.
   *
   * Zero rows returned is therefore ambiguous, so the DAO classifies with a
   * follow-up SELECT by `original_transaction_id` in the same transaction: same
   * account → [SubscriptionUpsert.Unchanged], different account →
   * [SubscriptionUpsert.OwnedByOtherAccount]. Never a silent no-op or a rebind.
   */
  fun upsert(
    session: SqlSession,
    userId: UserId,
    originalTransactionId: String,
    productId: String,
    status: SubscriptionStatus,
    periodStart: Instant,
    periodEnd: Instant,
  ): Result<SubscriptionUpsert> {
    val written =
      session.mutateReturning(
        """
        INSERT INTO subscriptions (user_id, original_transaction_id, product_id, status, period_start, period_end)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (original_transaction_id) DO UPDATE SET
          version = subscriptions.version + 1,
          product_id = EXCLUDED.product_id,
          status = EXCLUDED.status,
          period_start = EXCLUDED.period_start,
          period_end = EXCLUDED.period_end
        WHERE subscriptions.user_id = EXCLUDED.user_id
          AND (subscriptions.product_id, subscriptions.status, subscriptions.period_start, subscriptions.period_end)
              IS DISTINCT FROM (EXCLUDED.product_id, EXCLUDED.status, EXCLUDED.period_start, EXCLUDED.period_end)
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, userId.value)
          stmt.setString(2, originalTransactionId)
          stmt.setString(3, productId)
          stmt.setString(4, status.value)
          stmt.setTimestamp(5, Timestamp.from(periodStart))
          stmt.setTimestamp(6, Timestamp.from(periodEnd))
        },
        map = ::mapSubscription,
        onNoRow = { NoRowWritten() },
      )

    return written.fold(
      onSuccess = { Result.success(SubscriptionUpsert.Applied(it)) },
      onFailure = { failure ->
        if (failure !is NoRowWritten) return Result.failure(failure)
        // The conflict arm's WHERE matched nothing: classify which guard held.
        findByOriginalTransactionId(session, originalTransactionId).mapCatching { existing ->
          when {
            existing == null -> {
              // The row vanished between the two statements — impossible in one
              // transaction over an undeletable table; surfaced as corruption in
              // THIS module's own vocabulary, because `mapCatching` is not the
              // `queryOne` envelope and so nothing here reaches
              // [mapDatabaseError]: a raw driver exception thrown from this arm
              // would cross into `:service` carrying neither [PermanentError]
              // nor [TransientError], and `SubscriptionRefreshHandler` would
              // retry the state this comment calls impossible and permanent.
              //
              // The message names BOTH keys the arm's two siblings compare — the
              // Apple transaction and the account the write was keyed on — so a
              // 500 from here identifies the session that produced it.
              throw DatabaseException(
                IllegalStateException(
                  "Upsert wrote no subscriptions row and none exists for [$originalTransactionId]: " +
                    "caller account [${userId.asString}]",
                ),
              )
            }

            existing.userId == userId -> {
              SubscriptionUpsert.Unchanged(existing)
            }

            else -> {
              SubscriptionUpsert.OwnedByOtherAccount(existing)
            }
          }
        }
      },
    )
  }

  /**
   * The gate's read: the ACCOUNT's entitling row covering the DB clock's now
   * ([ENTITLING_CLAUSE]), latest `period_end` on the rare tie. Null when none.
   *
   * Keyed by the account and not by a role (RFC 244): one human may hold both a
   * `students` and a `supporters` row, and Apple will not sell that person two
   * subscriptions, so the account is who pays. A caller holding a role id
   * resolves the account first — one indexed hop through
   * `students.user_id` / `supporters.user_id` — and asks here.
   *
   * Null means THIS account has not paid, and nothing more: no account rides
   * another's subscription, so there is no second read to try.
   */
  fun findCurrent(
    session: SqlSession,
    userId: UserId,
  ): Result<Subscription?> =
    session
      .queryList(
        """
        SELECT * FROM subscriptions
        WHERE user_id = ? AND $ENTITLING_CLAUSE
        ORDER BY period_end DESC
        LIMIT 1
        """.trimIndent(),
        bind = { it.setObject(1, userId.value) },
        map = ::mapSubscription,
      ).map { it.firstOrNull() }

  /** Lookup by Apple's key; the webhook refresh's entry point, and test support. */
  fun findByOriginalTransactionId(
    session: SqlSession,
    originalTransactionId: String,
  ): Result<Subscription?> =
    session
      .queryList(
        "SELECT * FROM subscriptions WHERE original_transaction_id = ?",
        bind = { it.setString(1, originalTransactionId) },
        map = ::mapSubscription,
      ).map { it.firstOrNull() }

  /** Internal marker for an upsert whose conflict arm matched no row; never escapes [upsert]. */
  private class NoRowWritten : Exception()

  private fun mapSubscription(rs: ResultSet): Subscription =
    Subscription(
      id = SubscriptionId(UUID.fromString(rs.getString("id"))),
      version = rs.getInt("version"),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      originalTransactionId = rs.getString("original_transaction_id"),
      productId = rs.getString("product_id"),
      status = parseStatus(rs.getString("status")),
      periodStart = rs.getInstant("period_start"),
      periodEnd = rs.getInstant("period_end"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
    )

  /**
   * Reconstructs the status from its persisted string. The DB CHECK already
   * guarantees a member value is stored, so a miss here indicates row
   * corruption, surfaced as a [SQLException] (→ [DatabaseException]), never a
   * user-facing failure.
   */
  private fun parseStatus(value: String): SubscriptionStatus =
    runCatching { SubscriptionStatus.from(value) }
      .getOrElse {
        throw SQLException("Persisted subscriptions.status is not a valid value: [$value]: [${it.message}]", it)
      }
}
