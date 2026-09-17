package ed.unicoach.db.dao

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Captures WARNs [exercise] emits via a root-logger [ListAppender] (the
 * `LlmCallLogTest` idiom, lifted out of its three private copies).
 */
fun captureWarns(exercise: () -> Unit): List<String> {
  val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
  val appender = ListAppender<ILoggingEvent>().apply { start() }
  root.addAppender(appender)
  try {
    exercise()
  } finally {
    root.detachAppender(appender)
  }
  return appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
}
