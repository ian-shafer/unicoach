package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.UserAuthIdentitiesDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewAuthIdentity
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.Validator

/**
 * A resolved caller: a live, non-expired session row whose user account exists.
 * The non-null [user] is the type-level guarantee that resolution succeeded —
 * [AuthService.resolveSession] never returns this with a missing user.
 */
data class AuthenticatedSession(
  val session: Session,
  val user: User,
)

class AuthService(
  private val database: Database,
  private val argon2Hasher: Argon2Hasher,
  private val tokenGenerator: ed.unicoach.util.TokenGenerator,
  private val emailVerificationService: EmailVerificationService,
  private val googleTokenVerifier: GoogleTokenVerifier,
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
        passwordHash = pwdHash,
      )

    // Raw verification token captured from inside the transaction for best-effort
    // delivery after the commit. Null when registration did not reach a success.
    var verificationRawToken: String? = null
    var registeredUser: ed.unicoach.db.models.User? = null

    val outcome =
      try {
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
                  newLoginMethod = ed.unicoach.db.models.LoginMethod.PASSWORD,
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
                    loginMethod = ed.unicoach.db.models.LoginMethod.PASSWORD,
                  ),
              ).getOrThrow()
          }

          // Issue the verification token inside the user-creation transaction so it
          // is atomic with the user + session; the raw token is captured for
          // best-effort delivery after the commit.
          verificationRawToken = emailVerificationService.issueToken(session, user.id).getOrThrow()
          registeredUser = user

          Result.success(RegisterResult.Success(user, newToken))
        }
      } catch (e: Exception) {
        Result.failure(e)
      }

    // Post-commit, best-effort verification email. A send failure must not fail
    // registration — the user can resend.
    val rawToken = verificationRawToken
    val user = registeredUser
    if (outcome.isSuccess && outcome.getOrNull() is RegisterResult.Success && rawToken != null && user != null) {
      emailVerificationService.sendVerificationEmail(user.email, rawToken)
    }

    return outcome
  }

  /**
   * Rewrites the session user's email and re-arms verification. In one
   * transaction it rewrites `users.email`, clears `email_verified_at`, burns the
   * user's outstanding verification tokens, and issues a fresh one (the raw token
   * captured for post-commit delivery). After commit it best-effort delivers the
   * verification email to the new address — a send failure does not fail the
   * request. Mirrors [register]'s transaction shape.
   */
  suspend fun changeEmail(
    user: ed.unicoach.db.models.User,
    newEmail: String,
  ): Result<ChangeEmailResult> {
    val emailValidation = EmailAddress.create(newEmail)
    if (emailValidation !is ValidationResult.Valid) {
      val message =
        when (val error = (emailValidation as ValidationResult.Invalid).error) {
          is ed.unicoach.common.models.ValidationError.Blank -> "Email must not be blank"
          is ed.unicoach.common.models.ValidationError.InvalidFormat -> "Email must be of the form ${error.expected}"
          is ed.unicoach.common.models.ValidationError.TooLong -> "Email must be at most ${error.maxLength} characters"
        }
      return Result.success(ChangeEmailResult.ValidationFailure(message))
    }
    val emailAddr = emailValidation.value

    // Raw verification token captured from inside the transaction for best-effort
    // delivery after the commit; the updated user carries the new address.
    var verificationRawToken: String? = null
    var updatedUser: ed.unicoach.db.models.User? = null

    val outcome =
      try {
        database.withConnection { session ->
          val daoResult = UsersDao.changeEmail(session, user.id, emailAddr)
          if (daoResult.isFailure) {
            val ex = daoResult.exceptionOrNull()
            if (ex is DuplicateEmailException) {
              return@withConnection Result.success(ChangeEmailResult.DuplicateEmail(emailAddr.value))
            } else {
              return@withConnection Result.failure(ex ?: RuntimeException("Error during email change"))
            }
          }
          val rewritten = daoResult.getOrNull()!!

          // Burn any in-flight token bound to the old address, then issue a fresh
          // one atomic with the email rewrite.
          VerificationTokensDao.consumeAllForUser(session, user.id).getOrThrow()
          verificationRawToken = emailVerificationService.issueToken(session, user.id).getOrThrow()
          updatedUser = rewritten

          Result.success(ChangeEmailResult.Success(rewritten))
        }
      } catch (e: Exception) {
        Result.failure(e)
      }

    // Post-commit, best-effort verification email to the new address. A send
    // failure must not fail the request — the user can resend.
    val rawToken = verificationRawToken
    val refreshed = updatedUser
    if (outcome.isSuccess && outcome.getOrNull() is ChangeEmailResult.Success && rawToken != null && refreshed != null) {
      emailVerificationService.sendVerificationEmail(refreshed.email, rawToken)
    }

    return outcome
  }

  /**
   * Resolves a token to the live session row and its user. Returns
   * `Result.success(null)` for the three user-absent outcomes — no session row,
   * an anonymous session (`userId == null`), or a soft-deleted user — so a
   * populated [AuthenticatedSession] is only ever returned when both a live
   * session and its user exist. A DB fault propagates as a failed `Result`.
   */
  suspend fun resolveSession(tokenHash: TokenHash): Result<AuthenticatedSession?> =
    try {
      database.withConnection { session ->
        val sessionResult = SessionsDao.findByTokenHash(session, tokenHash)
        if (sessionResult.isFailure) {
          if (sessionResult.exceptionOrNull() is NotFoundException) {
            return@withConnection Result.success(null)
          }
          return@withConnection Result.failure(sessionResult.exceptionOrNull()!!)
        }

        val sessionRow = sessionResult.getOrNull()!!
        val userId = sessionRow.userId ?: return@withConnection Result.success(null)

        val userResult = UsersDao.findById(session, userId)
        if (userResult.isFailure) {
          if (userResult.exceptionOrNull() is NotFoundException) {
            return@withConnection Result.success(null)
          }
          return@withConnection Result.failure(userResult.exceptionOrNull()!!)
        }
        Result.success(AuthenticatedSession(sessionRow, userResult.getOrNull()!!))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The user-only projection of [resolveSession], retained for the user-only
   * callers in `admin-web` and the exempt auth handlers. Behaviourally
   * identical to the prior `getCurrentUser`: all three user-absent cases map to
   * `null`.
   */
  suspend fun getCurrentUser(tokenHash: TokenHash): Result<User?> = resolveSession(tokenHash).map { it?.user }

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

      val pwdHash = user!!.passwordHash

      if (pwdHash == null) {
        return Result.success(LoginResult.PasswordNotSet)
      }

      val isValid = argon2Hasher.verify(pwdHash.value, password)

      if (!isValid) {
        return Result.success(LoginResult.PasswordMismatch)
      }

      val newToken =
        database.withConnection { session ->
          mintSession(
            session = session,
            user = user!!,
            loginMethod = LoginMethod.PASSWORD,
            oldCookieToken = oldCookieToken,
            sessionExpirationSeconds = sessionExpirationSeconds,
            userAgent = userAgent,
            initialIp = initialIp,
          )
        }

      Result.success(LoginResult.Success(user!!, newToken))
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Establishes a session from a Google ID token. Verifies the token, gates on
   * `email_verified`, then in one transaction resolves the federated identity
   * (returning login), links it onto an existing email-matched user, or creates a
   * new user — minting a session with [LoginMethod.GOOGLE].
   *
   * Both first-time signup and returning login return [GoogleLoginResult.Success];
   * the result does not distinguish them, so account existence is not disclosed.
   */
  suspend fun loginWithGoogle(
    idToken: String,
    oldCookieToken: String?,
    sessionExpirationSeconds: Long,
    userAgent: String?,
    initialIp: String?,
  ): Result<GoogleLoginResult> {
    val verification = googleTokenVerifier.verify(idToken)
    if (verification.isFailure) {
      return when (verification.exceptionOrNull()) {
        is GoogleTokenUnavailableException -> Result.success(GoogleLoginResult.VerificationUnavailable)
        is GoogleTokenInvalidException -> Result.success(GoogleLoginResult.InvalidToken)
        else -> Result.failure(verification.exceptionOrNull()!!)
      }
    }

    val identity = verification.getOrThrow()
    if (!identity.emailVerified) {
      return Result.success(GoogleLoginResult.EmailNotVerified)
    }

    val subject =
      when (val s = ProviderSubject.create(identity.subject)) {
        is ValidationResult.Valid -> s.value
        is ValidationResult.Invalid -> return Result.success(GoogleLoginResult.InvalidToken)
      }
    val email =
      when (val e = EmailAddress.create(identity.email)) {
        is ValidationResult.Valid -> e.value
        is ValidationResult.Invalid -> return Result.success(GoogleLoginResult.InvalidToken)
      }

    // The whole transaction aborts on a UNIQUE(provider,subject) or
    // users_email_unique_active_idx violation from a concurrent first login; an
    // in-transaction re-read is impossible (the transaction is aborted), so we
    // retry the entire block once. After the winner commits, the second attempt
    // resolves deterministically as a returning login.
    return try {
      runGoogleSignIn(subject, email, identity.name, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp)
    } catch (e: ConstraintViolationException) {
      runGoogleSignIn(subject, email, identity.name, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp)
    } catch (e: DuplicateEmailException) {
      runGoogleSignIn(subject, email, identity.name, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * One transactional sign-in attempt. A `23505`-derived violation
   * ([ConstraintViolationException] or [DuplicateEmailException]) propagates so
   * [loginWithGoogle] can retry the whole block; every other DAO failure is
   * rethrown to abort and surface as a 500.
   */
  private suspend fun runGoogleSignIn(
    subject: ProviderSubject,
    email: EmailAddress,
    nameClaim: String?,
    oldCookieToken: String?,
    sessionExpirationSeconds: Long,
    userAgent: String?,
    initialIp: String?,
  ): Result<GoogleLoginResult> =
    database.withConnection { session ->
      when (val resolution = resolveOrProvisionUser(session, subject, email, nameClaim)) {
        is UserResolution.Disabled -> Result.success(GoogleLoginResult.AccountDisabled)
        is UserResolution.Resolved -> {
          val token =
            mintSession(
              session = session,
              user = resolution.user,
              loginMethod = LoginMethod.GOOGLE,
              oldCookieToken = oldCookieToken,
              sessionExpirationSeconds = sessionExpirationSeconds,
              userAgent = userAgent,
              initialIp = initialIp,
            )
          Result.success(GoogleLoginResult.Success(resolution.user, token))
        }
      }
    }

  /** The user a Google sign-in resolves to, or the soft-deleted (disabled) signal. */
  private sealed interface UserResolution {
    data class Resolved(
      val user: User,
    ) : UserResolution

    data object Disabled : UserResolution
  }

  /**
   * Resolves the Google identity to its user within the open transaction:
   * a returning login (existing `(GOOGLE, subject)` row), a link onto an active
   * email-matched user, or a freshly created user. A `23505`-derived violation
   * ([ConstraintViolationException]/[DuplicateEmailException]) propagates so
   * [loginWithGoogle] can retry the whole block; every other DAO failure is
   * rethrown to abort and surface as a 500.
   */
  private fun resolveOrProvisionUser(
    session: ed.unicoach.db.dao.SqlSession,
    subject: ProviderSubject,
    email: EmailAddress,
    nameClaim: String?,
  ): UserResolution {
    val existing = UserAuthIdentitiesDao.findByProviderAndSubject(session, AuthProvider.GOOGLE, subject)
    val existingError = existing.exceptionOrNull()
    if (existingError != null && existingError !is NotFoundException) {
      throw existingError
    }

    if (existing.isSuccess) {
      // Returning login: load the identity's user across all soft-delete states.
      val resolved = UsersDao.findById(session, existing.getOrThrow().userId, SoftDeleteScope.ALL).getOrThrow()
      return if (resolved.deletedAt != null) UserResolution.Disabled else UserResolution.Resolved(resolved)
    }

    // First sign-in for this subject: link to an active email match, else create.
    val byEmail = UsersDao.findByEmail(session, email)
    val byEmailError = byEmail.exceptionOrNull()
    if (byEmailError != null && byEmailError !is NotFoundException) {
      throw byEmailError
    }

    val target =
      if (byEmail.isSuccess) {
        byEmail.getOrThrow()
      } else {
        UsersDao
          .create(
            session,
            NewUser(
              email = email,
              name = deriveName(nameClaim, email),
              displayName = null,
              passwordHash = null,
            ),
          ).getOrThrow()
      }

    UserAuthIdentitiesDao
      .create(
        session,
        NewAuthIdentity(
          userId = target.id,
          provider = AuthProvider.GOOGLE,
          subject = subject,
          email = email,
          emailVerified = true,
        ),
      ).getOrThrow()
    return UserResolution.Resolved(target)
  }

  /**
   * Revokes a live old-cookie session (mirroring `login`) and mints a fresh
   * session bound to [user] with the given [loginMethod].
   */
  private fun mintSession(
    session: ed.unicoach.db.dao.SqlSession,
    user: User,
    loginMethod: LoginMethod,
    oldCookieToken: String?,
    sessionExpirationSeconds: Long,
    userAgent: String?,
    initialIp: String?,
  ): String {
    if (oldCookieToken != null) {
      val oldHash = TokenHash.fromRawToken(oldCookieToken)
      val revoke = SessionsDao.revokeByTokenHash(session, oldHash)
      val revokeError = revoke.exceptionOrNull()
      if (revokeError != null && revokeError !is NotFoundException) {
        throw revokeError
      }
    }

    val newToken = tokenGenerator.generateToken()
    val newHash = TokenHash.fromRawToken(newToken)
    SessionsDao
      .create(
        session,
        ed.unicoach.db.models.NewSession(
          userId = user.id,
          tokenHash = newHash,
          userAgent = userAgent,
          initialIp = initialIp,
          metadata = null,
          expiration = java.time.Duration.ofSeconds(sessionExpirationSeconds),
          loginMethod = loginMethod,
        ),
      ).getOrThrow()
    return newToken
  }

  /**
   * Derives a [PersonName] from the `name` claim, falling back to the email
   * local-part when the claim is absent or blank. A name that cannot form a valid
   * [PersonName] throws — surfacing as a 500 (no silent placeholder).
   */
  private fun deriveName(
    nameClaim: String?,
    email: EmailAddress,
  ): PersonName {
    val candidate = nameClaim?.trim()?.takeIf { it.isNotEmpty() } ?: email.value.substringBefore('@')
    return when (val result = PersonName.create(candidate)) {
      is ValidationResult.Valid -> result.value
      is ValidationResult.Invalid ->
        throw IllegalStateException(
          "Could not derive a valid PersonName from Google sign-in [candidate=$candidate, error=${result.error}]",
        )
    }
  }
}
