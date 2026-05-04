package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.DaoResult
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
          is DaoResult.Success -> {
            AuthResult.Success(daoResult.value)
          }
          is DaoResult.PermanentError.DuplicateEmail -> {
            AuthResult.DuplicateEmail(emailAddr.value)
          }
          is DaoResult.PermanentError -> {
            AuthResult.DatabaseFailure(
              when (daoResult) {
                is DaoResult.PermanentError.ConstraintViolation -> daoResult.error
                is DaoResult.PermanentError.DatabaseError -> daoResult.error
                else -> ExceptionWrapper.from(RuntimeException("Permanent error during user creation"))
              },
            )
          }
          is DaoResult.TransientError -> {
            AuthResult.DatabaseFailure(
              when (daoResult) {
                is DaoResult.TransientError.DatabaseError -> daoResult.error
                else -> ExceptionWrapper.from(RuntimeException("Transient error during user creation"))
              },
            )
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
            is DaoResult.PermanentError -> MeResult.Unauthenticated
            is DaoResult.TransientError -> MeResult.DatabaseFailure(
              when (sessionResult) {
                is DaoResult.TransientError.DatabaseError -> sessionResult.error
                else -> ExceptionWrapper.from(RuntimeException("Transient error during session lookup"))
              },
            )
            is DaoResult.Success -> {
              val userId =
                sessionResult.value.userId
                  ?: return@withConnection MeResult.Unauthenticated

              when (val userResult = UsersDao.findById(session, userId)) {
                is DaoResult.PermanentError -> MeResult.Unauthenticated
                is DaoResult.TransientError -> MeResult.Unauthenticated
                is DaoResult.Success -> MeResult.Authenticated(userResult.value)
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
            is DaoResult.Success -> LogoutResult.Success
            is DaoResult.PermanentError -> LogoutResult.Success
            is DaoResult.TransientError -> LogoutResult.DatabaseFailure(
              when (result) {
                is DaoResult.TransientError.DatabaseError -> result.error
                else -> ExceptionWrapper.from(RuntimeException("Transient error during logout"))
              },
            )
          }
        }
      }
    } catch (e: Exception) {
      LogoutResult.DatabaseFailure(ExceptionWrapper.from(e))
    }
}
