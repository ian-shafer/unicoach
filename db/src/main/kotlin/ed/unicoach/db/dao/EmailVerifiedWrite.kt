package ed.unicoach.db.dao

import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId

/**
 * Everything that follows proof of an address, in [session]'s transaction (RFC
 * 254): mark it verified, burn the account's outstanding mailed verification
 * links, then run [afterVerified]. The ONE sequence every verifying path runs —
 * the mailed link (`DbEmailVerifier`), an SSO sign-in (`AuthService`) and an
 * operator's mark (admin `UsersResource`).
 *
 * Burning the links matters on every path: one left spendable could be spent
 * later and run [afterVerified] a second time. The hook is a REQUIRED argument
 * so no path can forget it, and a function so this module names nothing above it.
 *
 * In `:db`, beside the two DAOs it composes, because it is the one module all
 * three verifying modules reach — `:service` and `:admin-web` do not depend on
 * `:auth`.
 */
fun recordEmailVerified(
  session: SqlSession,
  userId: UserId,
  afterVerified: (SqlSession, UserId) -> Unit,
): User {
  val user = UsersDao.markEmailVerified(session, userId).getOrThrow()
  VerificationTokensDao.consumeAllForUser(session, userId).getOrThrow()
  afterVerified(session, userId)
  return user
}
