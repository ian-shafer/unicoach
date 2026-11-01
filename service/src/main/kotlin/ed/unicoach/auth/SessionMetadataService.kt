package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionMetadataTooLargeException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UnstorableTextException
import ed.unicoach.db.models.Session
import ed.unicoach.db.session.SessionMetadata
import org.slf4j.LoggerFactory

/** What became of one [SessionMetadataService.update]. */
sealed interface MetadataWrite {
  data class Written(
    val session: Session,
  ) : MetadataWrite

  /** The change would exceed the column's CHECK; nothing was written. */
  data object TooLarge : MetadataWrite

  /**
   * The change holds text the column cannot store (a NUL character, which
   * `jsonb` refuses); nothing was written. Like [TooLarge] it is what a family
   * POSTed, not a server fault.
   */
  data object Unstorable : MetadataWrite

  /** The session was revoked or deleted between read and write. */
  data object SessionGone : MetadataWrite
}

/**
 * The one writer of a live session's metadata (RFC 265).
 *
 * The outcomes a caller acts on are [MetadataWrite] values, not failures: a
 * draft too big to keep, text the column cannot hold and a session revoked
 * mid-request are all things a family can cause. Only a fault, or a version
 * race lost on every attempt, is a failed [Result].
 */
class SessionMetadataService(
  private val database: Database,
) {
  /**
   * Applies [change] to the metadata the caller already read in [seen], and
   * writes it at [seen]'s version: ONE UPDATE in the common case.
   *
   * On a version conflict (an iOS expiry refresh, another tab's write) it
   * re-reads the row, applies [change] to the FRESH value and tries again, up to
   * [MAX_ATTEMPTS] in all. [change] must be a pure function of the stored value:
   * that is what lets a concurrent write to a different form's draft be kept
   * rather than overwritten by a stale copy.
   *
   * Each attempt is its own transaction. A CHECK refusal aborts the transaction
   * it happens in, and nothing earlier in this method is worth keeping with it.
   */
  suspend fun update(
    seen: Session,
    change: (SessionMetadata) -> SessionMetadata,
  ): Result<MetadataWrite> {
    var current = seen
    repeat(MAX_ATTEMPTS) {
      val next = change(current.metadata)
      val written =
        database
          .withConnectionCatching { sql -> SessionsDao.writeMetadata(sql, current.id, current.version, next) }
          .getOrElse { return Result.failure(it) }
      when (val failure = written.exceptionOrNull()) {
        null -> {
          return Result.success(MetadataWrite.Written(written.getOrThrow()))
        }

        is SessionMetadataTooLargeException -> {
          // The only trace an overflow leaves: the family reads a notice, and
          // nothing else records which session ran past the cap, or by how much.
          log.info("session [{}] metadata refused: {}", current.id.value, failure.message)
          return Result.success(MetadataWrite.TooLarge)
        }

        is UnstorableTextException -> {
          // The id and never the cause: the driver's message quotes the JSON it
          // could not store, which holds the family's typing and the CSRF token.
          log.info("session [{}] metadata refused: it holds text the column cannot store", current.id.value)
          return Result.success(MetadataWrite.Unstorable)
        }

        is NotFoundException -> {
          return Result.success(MetadataWrite.SessionGone)
        }

        is ConcurrentModificationException -> {
          current = reread(current).getOrElse { return Result.failure(it) }
            ?: return Result.success(MetadataWrite.SessionGone)
        }

        else -> {
          return Result.failure(failure)
        }
      }
    }
    return Result.failure(
      ConcurrentModificationException(
        "session [${seen.id.value}] metadata write lost the version race $MAX_ATTEMPTS times; nothing was written",
      ),
    )
  }

  /** The row as it stands now, or `null` when it has gone since the conflict. */
  private suspend fun reread(session: Session): Result<Session?> =
    database
      .withConnectionCatching { sql -> SessionsDao.findById(sql, session.id) }
      .mapCatching { found ->
        found.fold(
          onSuccess = { it },
          onFailure = { if (it is NotFoundException) null else throw it },
        )
      }

  private companion object {
    private val log = LoggerFactory.getLogger(SessionMetadataService::class.java)

    /** Attempts in all, the first included: enough to ride out a refresh and a second tab. */
    const val MAX_ATTEMPTS = 3
  }
}
