package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CreateResult
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.EmailAddress
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.error.ExceptionWrapper
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.JwtGenerator
import ed.unicoach.util.Validator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthService(
  private val database: Database,
  private val jwtGenerator: JwtGenerator,
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
            val token =
              jwtGenerator.mint(
                daoResult.user.id.value
                  .toString(),
              )
            AuthResult.Success(daoResult.user, token)
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
}
