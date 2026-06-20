package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.Validator

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
  ): Result<RegisterResult> {
    val input = RegistrationInput(email, name, password)
    val validationResult = validator.validate(input)

    if (validationResult.hasErrors()) {
      return Result.success(RegisterResult.ValidationFailure(validationResult.errors, validationResult.fieldErrors))
    }

    val emailAddr = (EmailAddress.create(email) as ValidationResult.Valid).value
    val personName = (PersonName.create(name) as ValidationResult.Valid).value

    val hashStr =
      try {
        argon2Hasher.hash(password)
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
            return@withConnection Result.success(RegisterResult.DuplicateEmail(emailAddr.value))
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
            SessionsDao
              .remintToken(
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
          SessionsDao
            .create(
              session = session,
              input =
                ed.unicoach.db.models.NewSession(
                  userId = user.id,
                  tokenHash = newHash,
                  userAgent = userAgent,
                  initialIp = initialIp,
                  metadata = null,
                  expiration = java.time.Duration.ofSeconds(sessionExpirationSeconds),
                ),
            ).getOrThrow()
        }

        Result.success(RegisterResult.Success(user, newToken))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getCurrentUser(tokenHash: TokenHash): Result<ed.unicoach.db.models.User?> =
    try {
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
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun logout(tokenHash: TokenHash): Result<Unit> =
    try {
      database.withConnection { session ->
        val result = SessionsDao.revokeByTokenHash(session, tokenHash)
        if (result.isFailure && result.exceptionOrNull() !is NotFoundException) {
          Result.failure(result.exceptionOrNull()!!)
        } else {
          Result.success(Unit)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun login(
    email: String,
    password: String,
    oldCookieToken: String?,
    sessionExpirationSeconds: Long,
    userAgent: String?,
    initialIp: String?,
  ): Result<LoginResult> =
    try {
      val emailStr = email.trim().lowercase()
      val emailValidation = EmailAddress.create(emailStr)
      if (emailValidation !is ValidationResult.Valid) {
        return Result.success(LoginResult.InvalidEmail((emailValidation as ValidationResult.Invalid).error))
      }
      val emailAddr = emailValidation.value

      var user: ed.unicoach.db.models.User? = null

      database.withConnection { session ->
        val userResult = UsersDao.findByEmail(session, emailAddr)
        val exception = userResult.exceptionOrNull()
        if (exception != null && exception !is ed.unicoach.db.dao.NotFoundException) {
          throw exception
        }
        user = userResult.getOrNull()
      }

      if (user == null) {
        return Result.success(LoginResult.UserNotFound)
      }

      val pwdHash =
        when (val method = user!!.authMethod) {
          is AuthMethod.Password -> method.hash
          is AuthMethod.Both -> method.hash
          is AuthMethod.SSO -> null
        }

      if (pwdHash == null) {
        return Result.success(LoginResult.PasswordNotSet)
      }

      val isValid = argon2Hasher.verify(pwdHash.value, password)

      if (!isValid) {
        return Result.success(LoginResult.PasswordMismatch)
      }

      val newToken = tokenGenerator.generateToken()
      val newHash = TokenHash.fromRawToken(newToken)

      database.withConnection { session ->
        if (oldCookieToken != null) {
          val oldHash = TokenHash.fromRawToken(oldCookieToken)
          val revokeResult = SessionsDao.revokeByTokenHash(session, oldHash)
          val exception = revokeResult.exceptionOrNull()
          if (exception != null && exception !is ed.unicoach.db.dao.NotFoundException) {
            throw exception
          }
        }

        SessionsDao
          .create(
            session = session,
            input =
              ed.unicoach.db.models.NewSession(
                userId = user!!.id,
                tokenHash = newHash,
                userAgent = userAgent,
                initialIp = initialIp,
                metadata = null,
                expiration = java.time.Duration.ofSeconds(sessionExpirationSeconds),
              ),
          ).getOrThrow()
      }

      Result.success(LoginResult.Success(user!!, newToken))
    } catch (e: Exception) {
      Result.failure(e)
    }
}
