/*
 * The one place a test builds an [ExtractionScheduler] (RFC 262). Every suite
 * that constructs a `CoachingService` must hand it one -- the parameter is
 * undefaulted -- and most of them are not about extraction at all, so they take
 * [disabledExtraction], the budget fixtures' `generousBudgetService` shape.
 */
package ed.unicoach.coaching.extraction

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.dao.JobsDao

/**
 * A scheduler over [database] reading the packaged `extraction` block with
 * `enabled` set to [enabled]. [jobsDao] is the seam a test uses to make the
 * enqueue fail through the production `DatabaseFailure` mapping.
 */
fun testExtractionScheduler(
  database: Database,
  enabled: Boolean = true,
  jobsDao: JobsDao = JobsDao(),
): ExtractionScheduler =
  ExtractionScheduler(
    QueueService(database, jobsDao),
    ExtractionConfig
      .from(
        ConfigFactory
          .parseString("extraction.enabled = $enabled")
          .withFallback(AppConfig.load("service.conf").getOrThrow()),
      ).getOrThrow(),
  )

/** A scheduler that enqueues nothing: for every suite whose subject is not extraction. */
fun disabledExtraction(database: Database): ExtractionScheduler = testExtractionScheduler(database, enabled = false)
