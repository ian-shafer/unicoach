package ed.unicoach.fixture

import ed.unicoach.auth.RegistrationInput
import ed.unicoach.auth.RegistrationValidator
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.UserId
import ed.unicoach.util.Argon2Hasher

/** One successfully applied user, for the caller's per-user summary line. */
data class AppliedUser(
  val id: UserId,
  val email: String,
  val verified: Boolean,
  val admin: Boolean,
)

/** The outcome of applying a whole world: every user it created, in file order. */
data class ApplyResult(
  val users: List<AppliedUser>,
)

/** A world file failed validation or collided with existing state; nothing was applied. */
class WorldApplyException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Applies a [WorldFile] to the database at service fidelity (RFC 138): the same
 * signup validation ([RegistrationValidator]), the same argon2id hashing
 * ([Argon2Hasher]), and DAO writes ([UsersDao.create] /
 * [UsersDao.markEmailVerified]) — all inside one transaction, so a failing
 * world leaves the database untouched. Create-only: a declared user that
 * already exists is an error (suggesting `-f`), never an update.
 */
class WorldApplier(
  private val database: Database,
  private val argon2Hasher: Argon2Hasher = Argon2Hasher(),
) {
  private val validator = RegistrationValidator()

  suspend fun apply(world: WorldFile): ApplyResult {
    // Validate every user before touching the database, so a bad file fails
    // fast with every violation reported, not just the first.
    val problems = mutableListOf<String>()
    for (spec in world.users) {
      val errors = validator.validate(RegistrationInput(spec.email, spec.resolvedName, spec.password))
      if (errors.hasErrors()) {
        val rendered = (errors.errors + errors.fieldErrors.map { "${it.field}: ${it.message}" }).joinToString("; ")
        problems.add("user [${spec.email}]: $rendered")
      }
    }
    if (problems.isNotEmpty()) {
      throw WorldApplyException("world file failed signup validation: ${problems.joinToString(" | ")}")
    }

    // Hash outside the transaction: argon2id is deliberately slow, and
    // withConnection's block is non-suspending.
    val newUsers =
      world.users.map { spec ->
        val email = (EmailAddress.create(spec.email) as ValidationResult.Valid).value
        val name = (PersonName.create(spec.resolvedName) as ValidationResult.Valid).value
        val hash = (PasswordHash.create(argon2Hasher.hash(spec.password)) as ValidationResult.Valid).value
        spec to
          NewUser(
            email = email,
            name = name,
            displayName = null,
            passwordHash = hash,
            isAdmin = spec.admin,
          )
      }

    // One transaction for the whole world; any thrown failure rolls back
    // everything already created (a half-applied world is worse than absent).
    val applied =
      database.withConnection { session ->
        newUsers.map { (spec, newUser) ->
          val created =
            UsersDao.create(session, newUser).getOrElse { e ->
              if (e is DuplicateEmailException) {
                throw WorldApplyException(
                  "user [${spec.email}] already exists; state-apply is create-only — " +
                    "re-run with -f to rebuild the world from empty",
                  e,
                )
              }
              throw e
            }
          if (spec.verified) {
            UsersDao.markEmailVerified(session, created.id).getOrThrow()
          }
          AppliedUser(id = created.id, email = spec.email, verified = spec.verified, admin = spec.admin)
        }
      }
    return ApplyResult(applied)
  }
}
