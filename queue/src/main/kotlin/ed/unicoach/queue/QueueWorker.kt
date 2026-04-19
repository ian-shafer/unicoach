package ed.unicoach.queue

import ed.unicoach.db.Database
import ed.unicoach.queue.dao.AttemptCountResult
import ed.unicoach.queue.dao.JobFindResult
import ed.unicoach.queue.dao.JobUpdateResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class QueueWorker(
  private val database: Database,
  private val jobsDao: JobsDao,
  private val handlers: List<JobHandler>,
  private val stuckJobCheckInterval: Duration = 1.minutes,
  private val completedJobRetention: Duration = 30.days,
  private val completedJobReapInterval: Duration = 1.hours,
) {
  private val logger = LoggerFactory.getLogger(QueueWorker::class.java)

  private val handlerMap =
    buildMap {
      for (handler in handlers) {
        require(!containsKey(handler.jobType)) {
          "Duplicate handler registered for job type: ${handler.jobType}"
        }
        put(handler.jobType, handler)
      }
    }

  private val channels = mutableMapOf<JobType, Channel<Unit>>()
  private val scopeRef = AtomicReference<CoroutineScope?>(null)

  @Volatile private var isRunning = false
  private var handlerJobs: List<kotlinx.coroutines.Job> = emptyList()
  private var backgroundJobs: List<kotlinx.coroutines.Job> = emptyList()

  fun start(scope: CoroutineScope) {
    if (!scopeRef.compareAndSet(null, scope)) {
      throw IllegalStateException("Worker is already started")
    }
    isRunning = true

    handlerMap.keys.forEach { jobType ->
      channels[jobType] = Channel(capacity = Channel.CONFLATED)
    }

    handlerJobs =
      handlers.flatMap { handler ->
        val channel = channels.getValue(handler.jobType)
        List(handler.config.concurrency) {
          scope.launch(Dispatchers.IO) {
            workerLoop(handler, channel)
          }
        }
      }

    backgroundJobs =
      listOf(
        scope.launch(Dispatchers.IO) { listenLoop() },
        scope.launch(Dispatchers.IO) { stuckJobReaperLoop() },
        scope.launch(Dispatchers.IO) { completedJobReaperLoop() },
      )
  }

  fun stop(timeout: Duration) {
    val scope = scopeRef.getAndSet(null) ?: return
    isRunning = false

    // Wake up any handlers indefinitely suspended in idle receive loops
    channels.values.forEach { it.close() }

    runBlocking {
      try {
        withTimeout(timeout) {
          backgroundJobs.forEach { it.cancelAndJoin() }
          handlerJobs.joinAll()
        }
      } catch (e: TimeoutCancellationException) {
        scope.cancel()
      }
    }
  }

  /**
   * Maintains a persistent database connection bypassing the pool to listen for enqueued
   * job notifications, instantly unblocking worker channels when new work is scheduled.
   */
  private suspend fun listenLoop() {
    while (isRunning) {
      try {
        val conn = database.createRawConnection()
        try {
          conn.createStatement().use { stmt ->
            stmt.execute("LISTEN jobs_channel")
          }
          val pgConn = conn.unwrap(org.postgresql.PGConnection::class.java)

          // Broadcast to all channels immediately upon connecting
          channels.values.forEach { it.trySend(Unit) }

          while (isRunning) {
            val notifications =
              withContext(Dispatchers.IO) {
                pgConn.getNotifications(1000)
              }
            if (notifications != null) {
              for (notification in notifications) {
                val jobTypeStr = notification.parameter
                val jobType = JobType.fromValue(jobTypeStr)
                if (jobType != null) {
                  val channel = channels[jobType]
                  if (channel != null) {
                    channel.trySend(Unit)
                  } else {
                    logger.warn("Received notification for jobType [{}] but no handler is registered.", jobType.value)
                  }
                }
              }
            }
            yield()
          }
        } finally {
          conn.close()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: java.sql.SQLException) {
        if (!isRunning) return

        if (!e.isTransientConnectionError()) throw e

        logger.error("Database connection error in [listenLoop], backing off and reconnecting", e)
        // If the PostgreSQL server is unreachable or the network drops,
        // we must back off before retrying the connection. Removing this
        // delay would cause the coroutine to instantly spin-lock, burning 100% CPU
        // and flooding logs. We use a short delay to balance rapid recovery with safety.
        delay(2000)
      }
    }
  }

  /**
   * Orchestrates continuous polling, claiming, and execution for a specific job handler,
   * managing retry backoffs, timeouts, and state transitions until the queue is drained.
   */
  private suspend fun workerLoop(
    handler: JobHandler,
    channel: Channel<Unit>,
  ) {
    val config = handler.config

    while (isRunning) {
      try {
        withTimeoutOrNull(config.delayedJobPollInterval) {
          channel.receive()
        }
      } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        logger.info("Worker channel closed for jobType [{}], shutting down loop.", handler.jobType.value)
      }

      while (isRunning) {
        val claimedJob =
          database.withConnection { session ->
            val findResult = jobsDao.findNextScheduledJob(session, handler.jobType)
            if (findResult is JobFindResult.Success) {
              val claimResult = jobsDao.claimJob(session, findResult.job.id, config.lockDuration)
              if (claimResult is JobUpdateResult.Success) claimResult.job else null
            } else {
              null
            }
          }

        if (claimedJob == null) break
        val startedAt = claimedJob.updatedAt

        channel.trySend(Unit) // Baton Pass

        val jobResult =
          try {
            withTimeoutOrNull(config.executionTimeout) {
              handler.execute(claimedJob.payload)
            } ?: JobResult.RetriableFailure("Execution timed out")
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Uncaught exception in job handler [{}]", handler.jobType.value, e)
            JobResult.RetriableFailure(e.message ?: "Unknown error")
          }

        database.withConnection { session ->
          val attemptNumber =
            when (val countResult = jobsDao.countAttempts(session, claimedJob.id)) {
              is AttemptCountResult.Success -> countResult.count + 1
              else -> 1
            }
          val status = jobResult.status
          val errorMsg =
            when (jobResult) {
              is JobResult.RetriableFailure -> jobResult.message
              is JobResult.PermanentFailure -> jobResult.message
              is JobResult.Success -> null
            }

          jobsDao.insertAttempt(session, claimedJob.id, attemptNumber, startedAt, status, errorMsg)

          when (jobResult) {
            is JobResult.Success -> {
              val res = jobsDao.completeJob(session, claimedJob.id)
              check(res is JobUpdateResult.Success) { "Failed to complete job: $res" }
            }
            is JobResult.PermanentFailure -> {
              val res = jobsDao.deadLetterJob(session, claimedJob.id)
              check(res is JobUpdateResult.Success) { "Failed to dead-letter job: $res" }
            }
            is JobResult.RetriableFailure -> {
              val maxAttempts = claimedJob.maxAttempts ?: config.maxAttempts
              if (attemptNumber >= maxAttempts) {
                val res = jobsDao.deadLetterJob(session, claimedJob.id)
                check(res is JobUpdateResult.Success) { "Failed to dead-letter job: $res" }
              } else {
                val delayDuration = config.backoffStrategy.delayFor(attemptNumber)
                val res = jobsDao.reschedule(session, claimedJob.id, delayDuration)
                check(res is JobUpdateResult.Success) { "Failed to reschedule job: $res" }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Sweeps the database periodically to reclaim interrupted or hung jobs that surpassed
   * their maximum lock duration, resetting them back to scheduled status for retry.
   */
  private suspend fun stuckJobReaperLoop() {
    while (isRunning) {
      delay(stuckJobCheckInterval)
      if (!isRunning) break
      try {
        database.withConnection { session ->
          val result = jobsDao.resetStuckRunning(session)
          if (result is ed.unicoach.queue.dao.JobResetResult.Success && result.count > 0) {
            logger.info("Reset [{}] stuck jobs", result.count)
          }
        }
      } catch (e: java.sql.SQLException) {
        if (!e.isTransientConnectionError()) throw e

        logger.error("Database connection error in [stuckJobReaperLoop]", e)
      }
    }
  }

  /**
   * Deletes successfully completed jobs from the database that have exceeded the
   * configured retention period to maintain long-term query performance and storage constraints.
   */
  private suspend fun completedJobReaperLoop() {
    while (isRunning) {
      delay(completedJobReapInterval)
      if (!isRunning) break
      try {
        database.withConnection { session ->
          val result =
            jobsDao.deleteBefore(
              session,
              setOf(JobStatus.COMPLETED),
              completedJobRetention,
            )
          if (result is ed.unicoach.queue.dao.JobDeleteResult.Success && result.count > 0) {
            logger.info("Reaped [{}] completed jobs", result.count)
          }
        }
      } catch (e: java.sql.SQLException) {
        if (!e.isTransientConnectionError()) throw e

        logger.error("Database connection error in [completedJobReaperLoop]", e)
      }
    }
  }
}

private fun java.sql.SQLException.isTransientConnectionError(): Boolean {
  val state = this.sqlState ?: ""
  // 08 = Connection Exception, 53 = Insufficient Resources, 57P = Admin/Crash Shutdown
  return state.startsWith("08") || state.startsWith("53") || state.startsWith("57P")
}
