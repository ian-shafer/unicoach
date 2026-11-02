package ed.unicoach.account

import ed.unicoach.db.models.TableErasure

/** What [AccountErasureService.erase] did (RFC 266 §8). */
sealed interface ErasureOutcome {
  /**
   * The account is erased. [tables] says what the walk did, per table; it is
   * empty on a re-run, which is how a dead-lettered erase job is finished
   * (deletion/04).
   */
  data class Erased(
    val tables: List<TableErasure>,
  ) : ErasureOutcome

  /** The account is live: it must be closed before it can be erased. */
  data object NotClosed : ErasureOutcome

  /** No account has the id. */
  data object NotFound : ErasureOutcome
}
