package ed.unicoach.account

import ed.unicoach.db.models.TableErasure

/**
 * What the erase job's door, [AccountErasureService.eraseClose], did (RFC 267
 * §4): any [ErasureOutcome], or [CloseSuperseded], which only a door that names
 * a close can answer.
 */
sealed interface CloseErasureOutcome {
  /** The account was closed again since the job's close, and that later close has its own job. */
  data object CloseSuperseded : CloseErasureOutcome
}

/**
 * What the operator's door, [AccountErasureService.erase], did (RFC 266 §8,
 * RFC 267 §4) -- and, as a [CloseErasureOutcome], what the job's door did when
 * the close it names still stands.
 */
sealed interface ErasureOutcome : CloseErasureOutcome {
  /** The account is erased. [tables] says what the walk did, per table; [confirmation], whether the person is told. */
  data class Erased(
    val tables: List<TableErasure>,
    val confirmation: ErasureConfirmation,
  ) : ErasureOutcome

  /**
   * The account was erased before this call, which did nothing: no walk, and no
   * second email. A duplicate erase job, and the admin door on an erased
   * account, both end here.
   */
  data object AlreadyErased : ErasureOutcome

  /** The account is live: it must be closed before it can be erased (or, for a job, it was reopened). */
  data object NotClosed : ErasureOutcome

  /** No account has the id. */
  data object NotFound : ErasureOutcome
}

/** Whether an erasure enqueued the email that says the account is deleted (RFC 267 §4, decision D-B). */
enum class ErasureConfirmation {
  /** One `ACCOUNT_ERASED` email is queued, in the erasure's own transaction, to the account's verified address. */
  ENQUEUED,

  /** The account had no verified address, so no email is sent; the erasure ran all the same. */
  NO_VERIFIED_ADDRESS,
}
