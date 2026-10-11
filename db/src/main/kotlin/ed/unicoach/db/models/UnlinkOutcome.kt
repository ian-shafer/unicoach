package ed.unicoach.db.models

/**
 * What an unlink did (RFC 249). Two answers and no third: "not yours", "never
 * existed" and "already severed" are one [NotFound], so a door built on it
 * cannot be used to probe which link ids exist.
 */
sealed interface UnlinkOutcome {
  /** The link as it was severed: `deletedAt` stamped, `version` advanced. */
  data class Unlinked(
    val link: SupporterStudentLink,
  ) : UnlinkOutcome

  data object NotFound : UnlinkOutcome
}
