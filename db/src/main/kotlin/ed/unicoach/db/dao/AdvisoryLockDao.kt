package ed.unicoach.db.dao

import ed.unicoach.db.models.StudentId

/**
 * Transaction-scoped advisory locks (RFC 66) — a net-new pattern in this
 * codebase. The lock SQL originates here because the raw [SqlSession.execute]
 * helper is `internal` to `:db` and not visible from `:service`; this is the
 * public surface callers use.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The lock is held for the remainder of the caller's transaction and
 * released on commit/rollback — callers MUST keep the LLM call outside the
 * transaction that holds it.
 */
object AdvisoryLockDao {
  /**
   * Acquires the per-student transaction-scoped advisory lock, serializing all
   * passes for the same student against the shared `claims`/`claim_support`
   * state. Distinct students hash to distinct keys and never contend. Blocks
   * until the lock is free, then returns success.
   */
  fun lockStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Unit> =
    try {
      // pg_advisory_xact_lock returns a row, so this runs through execute()
      // (the generic helpers expect an update count or a RETURNING row); the
      // result set is consumed and discarded — only the lock side-effect matters.
      session.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))").use { stmt ->
        stmt.setString(1, studentId.value.toString())
        stmt.executeQuery().use { /* drain */ }
      }
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }
}
