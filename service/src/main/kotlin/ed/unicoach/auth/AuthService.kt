package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CreateResult
import ed.unicoach.db.dao.FindResult
import ed.unicoach.db.dao.SessionFindResult
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.EmailAddress
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.error.ExceptionWrapper
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.Validator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthService(
  private val database: Database,
  private val argon2Hasher: Argon2Hasher,
  private val validator: Validator<RegistrationInput> = RegistrationValidator(),
) {
  suspend fun register(
    email: String,
    name: String,
    password: String,
  ): AuthResult {
    val input = RegistrationInput(email, name, password)
    val validationResult = validator.validate(input)

    if (validationResult.hasErrors()) {
      return AuthResult.ValidationFailure(validationResult.errors, validationResult.fieldErrors)
    }

    val emailAddr = (EmailAddress.create(email) as ValidationResult.Valid).value
    val personName = (PersonName.create(name) as ValidationResult.Valid).value

    val hashStr =
      try {
        withContext(Dispatchers.IO) {
          argon2Hasher.hash(password)
        }
      } catch (e: Exception) {
        return AuthResult.DatabaseFailure(ExceptionWrapper.from(e))
      }

    val pwdHash = (PasswordHash.create(hashStr) as ValidationResult.Valid).value

    val newUser =
      NewUser(
        email = emailAddr,
        name = personName,
        displayName = null,
        authMethod = AuthMethod.Password(pwdHash),
      )

    return try {
      database.withConnection { session ->
        when (val daoResult = UsersDao.create(session, newUser)) {
          is CreateResult.Success -> {
            AuthResult.Success(daoResult.user)
          }
          is CreateResult.DuplicateEmail -> {
            AuthResult.DuplicateEmail(emailAddr.value)
          }
          is CreateResult.ConstraintViolation -> {
            AuthResult.DatabaseFailure(daoResult.error)
          }
          is CreateResult.DatabaseFailure -> {
            AuthResult.DatabaseFailure(daoResult.error)
          }
        }
      }
    } catch (e: Exception) {
      AuthResult.DatabaseFailure(ExceptionWrapper.from(e))
    }
  }

  suspend fun getCurrentUser(tokenHash: TokenHash): MeResult =
    try {
      withContext(Dispatchers.IO) {
        database.withConnection { session ->
          when (val sessionResult = SessionsDao.findByTokenHash(session, tokenHash)) {
            is SessionFindResult.NotFound -> MeResult.Unauthenticated
            is SessionFindResult.DatabaseFailure -> MeResult.DatabaseFailure(sessionResult.error)
            is SessionFindResult.Success -> {
              val userId =
                sessionResult.session.userId
                  ?: return@withConnection MeResult.Unauthenticated

              when (val userResult = UsersDao.findById(session, userId)) {
                is FindResult.NotFound -> MeResult.Unauthenticated
                is FindResult.LockAcquisitionFailure -> MeResult.Unauthenticated
                is FindResult.Success -> MeResult.Authenticated(userResult.user)
                is FindResult.DatabaseFailure -> {
                  // FindResult.DatabaseFailure wraps AppError; extract to ExceptionWrapper
                  // if possible, otherwise wrap in a synthetic exception to preserve the chain.
                  val wrapper =
                    userResult.error as? ExceptionWrapper
                      ?: ExceptionWrapper.from(RuntimeException("User lookup failed: [${userResult.error}]"))
                  MeResult.DatabaseFailure(wrapper)
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      MeResult.DatabaseFailure(ExceptionWrapper.from(e))
    }

  suspend fun logout(tokenHash: TokenHash): LogoutResult =
    try {
      withContext(Dispatchers.IO) {
        database.withConnection { session ->
          when (val result = SessionsDao.revokeByTokenHash(session, tokenHash)) {
            is ed.unicoach.db.dao.SessionUpdateResult.Success -> LogoutResult.Success
            is ed.unicoach.db.dao.SessionUpdateResult.NotFound -> LogoutResult.Success
            is ed.unicoach.db.dao.SessionUpdateResult.DatabaseFailure -> LogoutResult.DatabaseFailure(result.error)
          }
        }
      }
    } catch (e: Exception) {
      LogoutResult.DatabaseFailure(ExceptionWrapper.from(e))
    }
}
