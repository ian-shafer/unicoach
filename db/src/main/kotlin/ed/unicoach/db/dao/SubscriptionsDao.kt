package ed.unicoach.db.dao

import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.SubscriptionId
import ed.unicoach.db.models.SubscriptionStatus
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Outcome of [SubscriptionsDao.upsert]: the write it performed, the no-op it
 * skipped, or the rebind it refused. Never a silent no-op — [Unchanged] and
 * [OwnedByOtherStudent] each name why no row changed.
 */
sealed interface SubscriptionUpsert {
  data class Applied(
    val subscription: Subscription,
  ) : SubscriptionUpsert

  /** The state-distinct guard skipped a no-op refresh; carries the untouched row. */
  data class Unchanged(
    val subscription: Subscription,
  ) : SubscriptionUpsert

  /** The rebind the conflict arm refused; carries the row that already owns the transaction. */
  data class OwnedByOtherStudent(
    val existing: Subscription,
  ) : SubscriptionUpsert
}

/**
 * Data-access layer over the versioned `subscriptions` entity (RFC 110).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller. Every mutation source funnels through [upsert] — the verify
 * path and the Notifications-V2 webhook's refresh alike — so the versioning
 * trigger pair captures both. The `version` bump is DAO-supplied in the conflict arm, never
 * caller-supplied — versions are an audit trail here, not caller-facing
 * concurrency control.
 */
object SubscriptionsDao {
  /**
   * Insert-or-refresh keyed on `original_transaction_id`. The conflict arm sets
   * `version = subscriptions.version + 1` (satisfying `enforce_versioning`) and
   * updates product/status/period only when the existing row's `student_id`
   * matches AND the incoming state actually differs (`IS DISTINCT FROM` over
   * the four mutable columns). The state-distinct guard keeps an app-launch
   * re-verify from minting a version row (and bumping `updated_at`) when
   * Apple's state is unchanged.
   *
   * Zero rows returned is therefore ambiguous, so the DAO classifies with a
   * follow-up SELECT by `original_transaction_id` in the same transaction: same
   * student → [SubscriptionUpsert.Unchanged], different student →
   * [SubscriptionUpsert.OwnedByOtherStudent]. Never a silent no-op or a rebind.
   */
  fun upsert(
    session: SqlSession,
    studentId: StudentId,
    originalTransactionId: String,
    productId: String,
    status: SubscriptionStatus,
    periodStart: Instant,
    periodEnd: Instant,
  ): Result<SubscriptionUpsert> {
    val written =
      session.mutateReturning(
        """
        INSERT INTO subscriptions (student_id, original_transaction_id, product_id, status, period_start, period_end)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (original_transaction_id) DO UPDATE SET
          version = subscriptions.version + 1,
          product_id = EXCLUDED.product_id,
          status = EXCLUDED.status,
          period_start = EXCLUDED.period_start,
          period_end = EXCLUDED.period_end
        WHERE subscriptions.student_id = EXCLUDED.student_id
          AND (subscriptions.product_id, subscriptions.status, subscriptions.period_start, subscriptions.period_end)
              IS DISTINCT FROM (EXCLUDED.product_id, EXCLUDED.status, EXCLUDED.period_start, EXCLUDED.period_end)
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, studentId.value)
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
              // transaction over an undeletable table; surface as corruption.
              throw SQLException("Upsert wrote no subscriptions row and none exists for [$originalTransactionId]")
            }

            existing.studentId == studentId -> {
              SubscriptionUpsert.Unchanged(existing)
            }

            else -> {
              SubscriptionUpsert.OwnedByOtherStudent(existing)
            }
          }
        }
      },
    )
  }

  /**
   * The gate's read: the entitling row covering the DB clock's now —
   * `status IN ('active','grace') AND period_start <= NOW() AND NOW() < period_end`,
   * latest `period_end` on the rare tie. Null when none. Compares against
   * `NOW()`, consistent with the DB-clock-only posture (RFC 97) and with the
   * in-transaction gate read it serves.
   */
  fun findCurrent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Subscription?> =
    session
      .queryList(
        """
        SELECT * FROM subscriptions
        WHERE student_id = ? AND status IN ('active','grace')
          AND period_start <= NOW() AND NOW() < period_end
        ORDER BY period_end DESC
        LIMIT 1
        """.trimIndent(),
        bind = { it.setObject(1, studentId.value) },
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
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
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
