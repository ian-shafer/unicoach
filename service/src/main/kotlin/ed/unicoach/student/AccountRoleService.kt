package ed.unicoach.student

import ed.unicoach.db.Database
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.Student as StudentRow
import ed.unicoach.db.models.Supporter as SupporterRow

/**
 * What an account is here for (RFC 268): the student, someone supporting a
 * student, or — until it answers "Who are you here for?" — neither.
 *
 * The server-side twin of the iOS app's `resolveProfileState` (RFC 241). An
 * account holding BOTH rows is a [Student]: it is what the chat checks first,
 * and what the iOS app opens on.
 */
sealed interface AccountRole {
  data class Student(
    val student: StudentRow,
  ) : AccountRole

  data class Supporter(
    val supporter: SupporterRow,
  ) : AccountRole

  /** Neither row: an account that has not onboarded. */
  data object None : AccountRole
}

/**
 * The one read of [AccountRole] (RFC 268). Every web check of "has this account
 * onboarded" — the chat's student gate, the onboarding wall, `/get-started`
 * itself — reads it here, so the three cannot disagree about who has.
 *
 * Both rows are read in ACTIVE scope: a closed student or supporter row is no
 * role, as it is to every other reader.
 */
class AccountRoleService(
  private val database: Database,
) {
  suspend fun roleOf(userId: UserId): Result<AccountRole> =
    database.withConnectionCatching { session ->
      StudentsDao.findByUserIdOrNull(session, userId).getOrThrow()?.let { return@withConnectionCatching AccountRole.Student(it) }
      SupportersDao.findByUserId(session, userId).getOrThrow()?.let { return@withConnectionCatching AccountRole.Supporter(it) }
      AccountRole.None
    }
}
