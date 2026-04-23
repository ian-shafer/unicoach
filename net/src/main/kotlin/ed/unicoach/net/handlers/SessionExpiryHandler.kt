package ed.unicoach.net.handlers

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SessionFindResult
import ed.unicoach.db.dao.SessionUpdateResult
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
        is SessionFindResult.NotFound -> JobResult.Success // expired/revoked, no-op
        is SessionFindResult.DatabaseFailure ->
          JobResult.RetriableFailure(
            findResult.error.exception.message ?: "Database error",
          )
        is SessionFindResult.Success ->
          extendIfApproaching(session, findResult.session)
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
      is SessionUpdateResult.Success -> JobResult.Success
      is SessionUpdateResult.NotFound ->
        JobResult.Success // version mismatch, already updated
      is SessionUpdateResult.DatabaseFailure ->
        JobResult.RetriableFailure(
          result.error.exception.message ?: "Database error",
        )
    }
  }
}
