package ed.unicoach.queue.dao

import ed.unicoach.db.dao.SqlSession
import ed.unicoach.error.ExceptionWrapper
import ed.unicoach.queue.AttemptStatus
import ed.unicoach.queue.Job
import ed.unicoach.queue.JobAttempt
import ed.unicoach.queue.JobStatus
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class JobsDao {
    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun <T> executeSafely(
        onError: (ExceptionWrapper) -> T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            onError(ExceptionWrapper.from(e))
        }

    private fun mapJob(rs: ResultSet): Job {
        val lockedUntilTs: Timestamp? = rs.getTimestamp("locked_until")
        val maxAttempts: Int? = rs.getInt("max_attempts").takeUnless { rs.wasNull() }
        val jobTypeStr = rs.getString("job_type")
        val jobType = JobType.fromValue(jobTypeStr)
            ?: error("Unknown job_type value in database: [$jobTypeStr]")
        val payloadStr = rs.getString("payload")
        val payload: JsonObject = Json.decodeFromString(payloadStr)

        return Job(
            id = UUID.fromString(rs.getString("id")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            jobType = jobType,
            payload = payload,
            status = JobStatus.valueOf(rs.getString("status")),
            scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
            lockedUntil = lockedUntilTs?.toInstant(),
            maxAttempts = maxAttempts,
        )
    }

    private fun mapAttempt(rs: ResultSet): JobAttempt =
        JobAttempt(
            id = UUID.fromString(rs.getString("id")),
            jobId = UUID.fromString(rs.getString("job_id")),
            attemptNumber = rs.getInt("attempt_number"),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            finishedAt = rs.getTimestamp("finished_at").toInstant(),
            status = AttemptStatus.valueOf(rs.getString("status")),
            errorMessage = rs.getString("error_message"),
        )

    // ---------------------------------------------------------------------------
    // Job operations
    // ---------------------------------------------------------------------------

    /**
     * Inserts a job with status SCHEDULED. If [newJob].delay is non-null,
     * scheduled_at is set to NOW() + that interval via SQL.
     */
    fun insert(
        session: SqlSession,
        newJob: NewJob,
    ): JobInsertResult =
        executeSafely(JobInsertResult::DatabaseFailure) {
            val sql = """
                INSERT INTO jobs (job_type, payload, max_attempts, scheduled_at)
                VALUES (?, ?::jsonb, ?, NOW() + ?::interval)
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                var idx = 1
                stmt.setString(idx++, newJob.jobType.value)
                stmt.setString(idx++, newJob.payload.toString())
                if (newJob.maxAttempts != null) stmt.setInt(idx++, newJob.maxAttempts) else { stmt.setNull(idx++, java.sql.Types.INTEGER) }
                val delaySeconds = newJob.delay?.inWholeSeconds ?: 0L
                stmt.setString(idx, "$delaySeconds seconds")
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        JobInsertResult.Success(mapJob(rs))
                    } else {
                        JobInsertResult.DatabaseFailure(ExceptionWrapper.from(RuntimeException("Insert succeeded but RETURNING returned no rows")))
                    }
                }
            }
        }

    /**
     * Selects a single job with status = 'SCHEDULED' and scheduled_at <= NOW()
     * for the given [jobType], using SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1.
     */
    fun findNextScheduledJob(
        session: SqlSession,
        jobType: JobType,
    ): JobFindResult =
        executeSafely(JobFindResult::DatabaseFailure) {
            val sql = """
                SELECT * FROM jobs
                WHERE status = 'SCHEDULED'
                  AND job_type = ?
                  AND scheduled_at <= NOW()
                ORDER BY scheduled_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setString(1, jobType.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        JobFindResult.Success(mapJob(rs))
                    } else {
                        JobFindResult.NotFound("No scheduled job of type [${jobType.value}] is ready")
                    }
                }
            }
        }

    /**
     * Transitions a job from SCHEDULED to RUNNING and sets
     * locked_until = NOW() + lockDuration via SQL interval.
     */
    fun claimJob(
        session: SqlSession,
        id: UUID,
        lockDuration: kotlin.time.Duration,
    ): JobUpdateResult =
        executeSafely(JobUpdateResult::DatabaseFailure) {
            val sql = """
                WITH updated AS (
                    UPDATE jobs
                    SET status = 'RUNNING',
                        locked_until = NOW() + ?::interval
                    WHERE id = ? AND status = 'SCHEDULED'
                    RETURNING *
                )
                SELECT true AS was_updated, u.* FROM updated u
                UNION ALL
                SELECT false AS was_updated, j.* FROM jobs j 
                WHERE j.id = ? AND NOT EXISTS (SELECT 1 FROM updated)
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "${lockDuration.inWholeSeconds} seconds")
                stmt.setObject(2, id)
                stmt.setObject(3, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val wasUpdated = rs.getBoolean("was_updated")
                        if (wasUpdated) {
                            JobUpdateResult.Success(mapJob(rs))
                        } else {
                            val currentStatus = rs.getString("status")
                            JobUpdateResult.InvalidState("Job [$id] cannot be claimed because it is currently in status [$currentStatus] instead of [SCHEDULED].")
                        }
                    } else {
                        JobUpdateResult.NotFound("Job [$id] not found.")
                    }
                }
            }
        }

    /**
     * Transitions a job from RUNNING to a terminal status (COMPLETED or DEAD_LETTERED).
     * Always clears locked_until to NULL.
     */
    fun updateStatus(
        session: SqlSession,
        id: UUID,
        status: JobStatus,
    ): JobUpdateResult =
        executeSafely(JobUpdateResult::DatabaseFailure) {
            require(status != JobStatus.RUNNING) { "Jobs must not be updated to RUNNING status using updateStatus. Use claimJob to transition a job to RUNNING." }
            val sql = """
                WITH updated AS (
                    UPDATE jobs
                    SET status = ?,
                        locked_until = NULL
                    WHERE id = ? AND status = 'RUNNING'
                    RETURNING *
                )
                SELECT true AS was_updated, u.* FROM updated u
                UNION ALL
                SELECT false AS was_updated, j.* FROM jobs j 
                WHERE j.id = ? AND NOT EXISTS (SELECT 1 FROM updated)
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setString(1, status.value)
                stmt.setObject(2, id)
                stmt.setObject(3, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val wasUpdated = rs.getBoolean("was_updated")
                        if (wasUpdated) {
                            JobUpdateResult.Success(mapJob(rs))
                        } else {
                            val currentStatus = rs.getString("status")
                            JobUpdateResult.InvalidState("Job [$id] cannot be updated to [${status.value}] because it is currently in status [$currentStatus] instead of [RUNNING].")
                        }
                    } else {
                        JobUpdateResult.NotFound("Job [$id] not found.")
                    }
                }
            }
        }

    /**
     * Resets a job status to SCHEDULED with a future scheduled_at = NOW() + delay
     * for retry backoff. Uses SQL interval for clock safety.
     */
    fun reschedule(
        session: SqlSession,
        id: UUID,
        delay: kotlin.time.Duration,
    ): JobUpdateResult =
        executeSafely(JobUpdateResult::DatabaseFailure) {
            val sql = """
                WITH updated AS (
                    UPDATE jobs
                    SET status = 'SCHEDULED',
                        scheduled_at = NOW() + ?::interval,
                        locked_until = NULL
                    WHERE id = ? AND status != 'RUNNING'
                    RETURNING *
                )
                SELECT true AS was_updated, u.* FROM updated u
                UNION ALL
                SELECT false AS was_updated, j.* FROM jobs j 
                WHERE j.id = ? AND NOT EXISTS (SELECT 1 FROM updated)
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "${delay.inWholeSeconds} seconds")
                stmt.setObject(2, id)
                stmt.setObject(3, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val wasUpdated = rs.getBoolean("was_updated")
                        if (wasUpdated) {
                            JobUpdateResult.Success(mapJob(rs))
                        } else {
                            val currentStatus = rs.getString("status")
                            JobUpdateResult.InvalidState("Job [$id] cannot be rescheduled because it is in status [$currentStatus]")
                        }
                    } else {
                        JobUpdateResult.NotFound("Job [$id] not found")
                    }
                }
            }
        }

    /**
     * Records a completed or failed attempt in job_attempts.
     * Always called after job execution resolves — never at claim time.
     * [startedAt] MUST originate from Job.updatedAt of the claimJob result (DB-sourced timestamp).
     * finished_at is set to NOW() by the database at insert time.
     */
    fun insertAttempt(
        session: SqlSession,
        jobId: UUID,
        attemptNumber: Int,
        startedAt: Instant,
        status: AttemptStatus,
        errorMessage: String? = null,
    ): AttemptInsertResult =
        executeSafely(AttemptInsertResult::DatabaseFailure) {
            val sql = """
                INSERT INTO job_attempts (job_id, attempt_number, started_at, status, error_message)
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, jobId)
                stmt.setInt(2, attemptNumber)
                stmt.setTimestamp(3, Timestamp.from(startedAt))
                stmt.setString(4, status.value)
                if (errorMessage != null) stmt.setString(5, errorMessage) else stmt.setNull(5, java.sql.Types.VARCHAR)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        AttemptInsertResult.Success(mapAttempt(rs))
                    } else {
                        AttemptInsertResult.DatabaseFailure(ExceptionWrapper.from(RuntimeException("Insert succeeded but RETURNING returned no rows")))
                    }
                }
            }
        }

    /**
     * Returns the count of attempts for a given job.
     */
    fun countAttempts(
        session: SqlSession,
        jobId: UUID,
    ): AttemptCountResult =
        executeSafely(AttemptCountResult::DatabaseFailure) {
            val sql = "SELECT COUNT(*) FROM job_attempts WHERE job_id = ?"
            session.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, jobId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    AttemptCountResult.Success(rs.getInt(1))
                }
            }
        }

    /**
     * Returns all attempts for a job, ordered by attempt_number.
     */
    fun findAttemptsByJobId(
        session: SqlSession,
        jobId: UUID,
    ): AttemptFindResult =
        executeSafely(AttemptFindResult::DatabaseFailure) {
            val sql = """
                SELECT * FROM job_attempts
                WHERE job_id = ?
                ORDER BY attempt_number
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, jobId)
                stmt.executeQuery().use { rs ->
                    val attempts = mutableListOf<JobAttempt>()
                    while (rs.next()) {
                        attempts.add(mapAttempt(rs))
                    }
                    AttemptFindResult.Success(attempts)
                }
            }
        }

    /**
     * Resets jobs with status = 'RUNNING' AND locked_until < NOW() back to SCHEDULED.
     * Clears locked_until on reset.
     */
    fun resetStuckRunning(session: SqlSession): JobResetResult =
        executeSafely(JobResetResult::DatabaseFailure) {
            val sql = """
                UPDATE jobs
                SET status = 'SCHEDULED',
                    locked_until = NULL
                WHERE status = 'RUNNING' AND locked_until < NOW()
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                val count = stmt.executeUpdate()
                JobResetResult.Success(count)
            }
        }

    /**
     * Physically deletes jobs where status IN (statuses) and updated_at < NOW() - olderThan.
     * Uses database NOW() to avoid trusting application server clocks for eviction boundaries.
     */
    fun deleteBefore(
        session: SqlSession,
        statuses: Set<JobStatus>,
        olderThan: kotlin.time.Duration,
    ): JobDeleteResult =
        executeSafely(JobDeleteResult::DatabaseFailure) {
            if (statuses.isEmpty()) return@executeSafely JobDeleteResult.Success(0)
            val placeholders = statuses.joinToString(", ") { "?" }
            val sql = """
                DELETE FROM jobs
                WHERE status IN ($placeholders)
                  AND updated_at < NOW() - ?::interval
            """.trimIndent()
            session.prepareStatement(sql).use { stmt ->
                var idx = 1
                for (s in statuses) {
                    stmt.setString(idx++, s.value)
                }
                stmt.setString(idx, "${olderThan.inWholeSeconds} seconds")
                val count = stmt.executeUpdate()
                JobDeleteResult.Success(count)
            }
        }

    /**
     * Deletes specific jobs by ID. Cascades to job_attempts via ON DELETE CASCADE.
     */
    fun deleteByIds(
        session: SqlSession,
        vararg ids: UUID,
    ): JobDeleteResult = deleteByIds(session, ids.toList())

    /**
     * Deletes specific jobs by ID. Cascades to job_attempts via ON DELETE CASCADE.
     */
    fun deleteByIds(
        session: SqlSession,
        ids: List<UUID>,
    ): JobDeleteResult =
        executeSafely(JobDeleteResult::DatabaseFailure) {
            if (ids.isEmpty()) return@executeSafely JobDeleteResult.Success(0)
            val placeholders = ids.joinToString(", ") { "?" }
            val sql = "DELETE FROM jobs WHERE id IN ($placeholders)"
            session.prepareStatement(sql).use { stmt ->
                ids.forEachIndexed { i, id -> stmt.setObject(i + 1, id) }
                val count = stmt.executeUpdate()
                JobDeleteResult.Success(count)
            }
        }

    /**
     * Finds a single job by ID.
     */
    fun findById(
        session: SqlSession,
        id: UUID,
    ): JobFindResult =
        executeSafely(JobFindResult::DatabaseFailure) {
            val sql = "SELECT * FROM jobs WHERE id = ?"
            session.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        JobFindResult.Success(mapJob(rs))
                    } else {
                        JobFindResult.NotFound("Job [$id] not found")
                    }
                }
            }
        }

    /**
     * Returns counts grouped by status, optionally filtered by job type.
     */
    fun countByStatus(
        session: SqlSession,
        jobType: JobType? = null,
    ): JobCountResult =
        executeSafely(JobCountResult::DatabaseFailure) {
            val sql = if (jobType != null) {
                "SELECT status, COUNT(*)::int AS cnt FROM jobs WHERE job_type = ? GROUP BY status"
            } else {
                "SELECT status, COUNT(*)::int AS cnt FROM jobs GROUP BY status"
            }
            session.prepareStatement(sql).use { stmt ->
                if (jobType != null) stmt.setString(1, jobType.value)
                stmt.executeQuery().use { rs ->
                    val counts = mutableMapOf<JobStatus, Int>()
                    while (rs.next()) {
                        val statusStr = rs.getString("status")
                        val status = JobStatus.valueOf(statusStr)
                        counts[status] = rs.getInt("cnt")
                    }
                    JobCountResult.Success(counts)
                }
            }
        }
}
