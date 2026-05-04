package ed.unicoach.net.handlers

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.Database
import ed.unicoach.db.dao.DaoResult
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import ed.unicoach.queue.SessionExpiryPayload
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionExpiryHandler(
  private val database: Database,
  private val slidingWindowThreshold: Duration,
) : JobHandler {
  override val jobType = JobType.SESSION_EXTEND_EXPIRY
  override val config =
    JobTypeConfig(
      concurrency = 1,
      maxAttempts = 3,
      lockDuration = 1.minutes,
      executionTimeout = 30.seconds,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val data: SessionExpiryPayload
    val tokenHash: TokenHash
    try {
      data = payload.deserialize<SessionExpiryPayload>()
      val hashBytes =
        java.util.Base64
          .getDecoder()
          .decode(data.tokenHash)
      tokenHash = TokenHash(hashBytes)
    } catch (e: Exception) {
      return JobResult.PermanentFailure(
        "Malformed payload: ${e.message}",
      )
    }

    return database.withConnection { session ->
      when (val findResult = SessionsDao.findByTokenHash(session, tokenHash)) {
        is DaoResult.PermanentError.NotFound -> JobResult.Success // expired/revoked, no-op
        is DaoResult.PermanentError ->
          JobResult.PermanentFailure(
            (findResult as? DaoResult.PermanentError.DatabaseError)?.error?.exception?.message ?: "Permanent error during session lookup",
          )
        is DaoResult.TransientError ->
          JobResult.RetriableFailure(
            (findResult as? DaoResult.TransientError.DatabaseError)?.error?.exception?.message ?: "Transient error",
          )
        is DaoResult.Success ->
          extendIfApproaching(session, findResult.value)
      }
    }
  }

  private fun extendIfApproaching(
    session: ed.unicoach.db.dao.SqlSession,
    sess: Session,
  ): JobResult {
    val threshold = Instant.now().plus(slidingWindowThreshold)
    if (sess.expiresAt.isAfter(threshold)) {
      return JobResult.Success // not approaching expiry
    }
    return when (
      val result =
        SessionsDao.extendExpiry(session, sess.id, sess.version)
    ) {
      is DaoResult.Success -> JobResult.Success
      is DaoResult.PermanentError.NotFound ->
        JobResult.Success // version mismatch or not found, already updated
      is DaoResult.PermanentError ->
        JobResult.PermanentFailure(
          (result as? DaoResult.PermanentError.DatabaseError)?.error?.exception?.message ?: "Permanent error during session extension",
        )
      is DaoResult.TransientError ->
        JobResult.RetriableFailure(
          (result as? DaoResult.TransientError.DatabaseError)?.error?.exception?.message ?: "Transient error",
        )
    }
  }
}
