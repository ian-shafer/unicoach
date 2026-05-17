package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.EmailAddress
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.Validator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthService(
  private val database: Database,
  private val argon2Hasher: Argon2Hasher,
  private val tokenGenerator: ed.unicoach.util.TokenGenerator,
  private val validator: Validator<RegistrationInput> = RegistrationValidator(),
) {
  suspend fun register(
    email: String,
    name: String,
    password: String,
    oldCookieToken: String?,
    sessionExpirationSeconds: Long,
    userAgent: String?,
    initialIp: String?,
  ): Result<RegisterOutcome> {
    val input = RegistrationInput(email, name, password)
    val validationResult = validator.validate(input)

    if (validationResult.hasErrors()) {
      return Result.success(RegisterOutcome.ValidationFailure(validationResult.errors, validationResult.fieldErrors))
    }

    val emailAddr = (EmailAddress.create(email) as ValidationResult.Valid).value
    val personName = (PersonName.create(name) as ValidationResult.Valid).value

    val hashStr =
      try {
        withContext(Dispatchers.IO) {
          argon2Hasher.hash(password)
        }
      } catch (e: Exception) {
        return Result.failure(e)
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
        val daoResult = UsersDao.create(session, newUser)
        if (daoResult.isFailure) {
          val ex = daoResult.exceptionOrNull()
          if (ex is DuplicateEmailException) {
            return@withConnection Result.success(RegisterOutcome.DuplicateEmail(emailAddr.value))
          } else {
            return@withConnection Result.failure(ex ?: RuntimeException("Error during user creation"))
          }
        }
        val user = daoResult.getOrNull()!!

        val newToken = tokenGenerator.generateToken()
        val newHash = TokenHash.fromRawToken(newToken)
        var wasReminted = false

        if (oldCookieToken != null) {
          val oldHash = TokenHash.fromRawToken(oldCookieToken)
          val found = SessionsDao.findByTokenHash(session, oldHash)
          if (found.isSuccess) {
            val sessionVal = found.getOrNull()!!
            SessionsDao.remintToken(
              session = session,
              id = sessionVal.id,
              currentVersion = sessionVal.version,
              newUserId = user.id,
              newTokenHash = newHash.value,
              newExpirationSeconds = sessionExpirationSeconds,
            ).getOrThrow()
            wasReminted = true
          }
        }

        if (!wasReminted) {
          SessionsDao.create(
            session = session,
            newSession = ed.unicoach.db.models.NewSession(
              userId = user.id,
              tokenHash = newHash,
              userAgent = userAgent,
              initialIp = initialIp,
              metadata = null,
              expiration = java.time.Duration.ofSeconds(sessionExpirationSeconds),
            ),
          ).getOrThrow()
        }

        Result.success(RegisterOutcome.Success(user, newToken))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getCurrentUser(tokenHash: TokenHash): Result<ed.unicoach.db.models.User?> =
    try {
      withContext(Dispatchers.IO) {
        database.withConnection { session ->
          val sessionResult = SessionsDao.findByTokenHash(session, tokenHash)
          if (sessionResult.isFailure) {
            if (sessionResult.exceptionOrNull() is NotFoundException) {
              return@withConnection Result.success(null)
            }
            return@withConnection Result.failure(sessionResult.exceptionOrNull()!!)
          }

          val userId = sessionResult.getOrNull()!!.userId ?: return@withConnection Result.success(null)

          val userResult = UsersDao.findById(session, userId)
          if (userResult.isFailure) {
            if (userResult.exceptionOrNull() is NotFoundException) {
              return@withConnection Result.success(null)
            }
            return@withConnection Result.failure(userResult.exceptionOrNull()!!)
          }
          Result.success(userResult.getOrNull())
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun logout(tokenHash: TokenHash): Result<Unit> =
    try {
      withContext(Dispatchers.IO) {
        database.withConnection { session ->
          val result = SessionsDao.revokeByTokenHash(session, tokenHash)
          if (result.isFailure && result.exceptionOrNull() !is NotFoundException) {
            Result.failure(result.exceptionOrNull()!!)
          } else {
            Result.success(Unit)
          }
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
}
