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
import org.slf4j.LoggerFactory
import java.time.Duration

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
  private val googleTokenVerifier: GoogleIdTokenVerifier,
  private val appleTokenVerifier: AppleIdTokenVerifier,
  private val validator: Validator<RegistrationInput> = RegistrationValidator(),
) {
  private val logger = LoggerFactory.getLogger(AuthService::class.java)

  suspend fun register(
    email: String,
    name: String,
    password: String,
    oldCookieToken: String?,
    sessionExpiration: Duration,
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
                newExpirationSeconds = sessionExpiration.seconds,
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
                  expiration = sessionExpiration,
                  loginMethod = ed.unicoach.db.models.LoginMethod.PASSWORD,
                ),
            ).getOrThrow()
        }

        // Issue the verification token and enqueue the verification email inside the
        // user-creation transaction so all three are atomic (RFC 96). A failed
        // enqueue aborts the transaction: no user/token is committed and the route
        // surfaces it as a 500 via getOrThrow().
        val verificationRawToken = emailVerificationService.issueToken(session, user.id).getOrThrow()
        emailVerificationService.enqueue(session, user.email, verificationRawToken).getOrThrow()

        Result.success(RegisterResult.Success(user, newToken))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Rewrites the session user's email and re-arms verification. In one
   * transaction it rewrites `users.email`, clears `email_verified_at`, burns the
   * user's outstanding verification tokens, issues a fresh one, and enqueues the
   * verification email to the new address (RFC 96). A failed enqueue aborts the
   * whole transaction, so the request fails and nothing is committed. Mirrors
   * [register]'s transaction shape.
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

    return try {
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

        // Burn any in-flight token bound to the old address, issue a fresh one, and
        // enqueue the verification email to the new address — all atomic with the
        // rewrite. A failed enqueue aborts the transaction (surfaced as a 500).
        VerificationTokensDao.consumeAllForUser(session, user.id).getOrThrow()
        val verificationRawToken = emailVerificationService.issueToken(session, user.id).getOrThrow()
        emailVerificationService.enqueue(session, rewritten.email, verificationRawToken).getOrThrow()

        Result.success(ChangeEmailResult.Success(rewritten))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
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
    sessionExpiration: Duration,
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
            sessionExpiration = sessionExpiration,
            userAgent = userAgent,
            initialIp = initialIp,
          )
        }

      Result.success(LoginResult.Success(user!!, newToken))
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Establishes a session from an SSO provider's ID token (Google or Apple).
   * Verifies the token with the provider's verifier, gates on `email_verified`,
   * then in one transaction resolves the federated identity (returning login),
   * links it onto an existing email-matched user (refused when that user holds
   * a password credential whose email is unverified), or creates a new
   * user — minting a session with [LoginMethod.GOOGLE] or [LoginMethod.APPLE].
   *
   * Both first-time signup and returning login return [SsoLoginResult.Success];
   * the result does not distinguish them, so account existence is not
   * disclosed. [clientProvidedName] is the Apple route's optional
   * client-supplied name (Apple's token never carries a name claim); the
   * Google route passes null.
   */
  suspend fun loginWithSso(
    provider: AuthProvider,
    idToken: String,
    clientProvidedName: String?,
    oldCookieToken: String?,
    sessionExpiration: Duration,
    userAgent: String?,
    initialIp: String?,
  ): Result<SsoLoginResult> {
    val verifier =
      when (provider) {
        AuthProvider.GOOGLE -> googleTokenVerifier.value
        AuthProvider.APPLE -> appleTokenVerifier.value
      }

    val verification = verifier.verify(idToken)
    if (verification.isFailure) {
      return when (val failure = verification.exceptionOrNull()) {
        is IdTokenUnavailableException -> Result.success(SsoLoginResult.VerificationUnavailable(failure))
        is IdTokenInvalidException -> Result.success(SsoLoginResult.InvalidToken(InvalidTokenReason.VerificationFailed(failure)))
        else -> Result.failure(failure!!)
      }
    }

    val identity = verification.getOrThrow()
    if (!identity.emailVerified) {
      return Result.success(SsoLoginResult.EmailNotVerified)
    }

    val subject =
      when (val s = ProviderSubject.create(identity.subject)) {
        is ValidationResult.Valid -> s.value
        is ValidationResult.Invalid -> return Result.success(SsoLoginResult.InvalidToken(InvalidTokenReason.UnusableSubject(s.error)))
      }
    val email =
      when (val e = EmailAddress.create(identity.email)) {
        is ValidationResult.Valid -> e.value
        is ValidationResult.Invalid -> return Result.success(SsoLoginResult.InvalidToken(InvalidTokenReason.UnusableEmail(e.error)))
      }

    val nameCandidates =
      listOf(
        NameCandidate("token name claim", identity.name),
        NameCandidate("client-supplied name", clientProvidedName),
      )

    suspend fun attemptSignIn(): Result<SsoLoginResult> =
      runSsoSignIn(
        provider,
        subject,
        email,
        nameCandidates,
        oldCookieToken,
        sessionExpiration,
        userAgent,
        initialIp,
      )

    /**
     * The one retry of a sign-in aborted by [firstFailure]. It calls
     * [attemptSignIn] exactly once — no failure of that call re-enters this
     * function — and reports any failure of it as a [Result.failure] carrying
     * [firstFailure] as a suppressed exception, so the violation that triggered
     * the retry reaches the 500 alongside whatever the retry itself hit.
     */
    suspend fun retryAfter(firstFailure: Exception): Result<SsoLoginResult> {
      logger.info("Retrying SSO sign-in after a concurrent first login [provider=${provider.wire}]", firstFailure)
      return try {
        attemptSignIn()
      } catch (retryFailure: Exception) {
        retryFailure.addSuppressed(firstFailure)
        Result.failure(retryFailure)
      }
    }

    // The whole transaction aborts on a UNIQUE(provider,subject) or
    // users_email_unique_active_idx violation from a concurrent first login; an
    // in-transaction re-read is impossible (the transaction is aborted), so we
    // retry the entire block once. After the winner commits, the second attempt
    // resolves deterministically as a returning login. `retryAfter` never
    // re-enters itself, so a violation thrown by the retry itself is reported
    // rather than retried again — the retry is exactly once.
    return try {
      attemptSignIn()
    } catch (e: ConstraintViolationException) {
      retryAfter(e)
    } catch (e: DuplicateEmailException) {
      retryAfter(e)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * One transactional sign-in attempt. A `23505`-derived violation
   * ([ConstraintViolationException] or [DuplicateEmailException]) propagates so
   * [loginWithSso] can retry the whole block; every other DAO failure is
   * rethrown to abort and surface as a 500.
   */
  private suspend fun runSsoSignIn(
    provider: AuthProvider,
    subject: ProviderSubject,
    email: EmailAddress,
    nameCandidates: List<NameCandidate>,
    oldCookieToken: String?,
    sessionExpiration: Duration,
    userAgent: String?,
    initialIp: String?,
  ): Result<SsoLoginResult> =
    database.withConnection { session ->
      when (val resolution = resolveOrProvisionUser(session, provider, subject, email, nameCandidates)) {
        is UserResolution.Disabled -> {
          Result.success(SsoLoginResult.AccountDisabled)
        }

        is UserResolution.LinkBlocked -> {
          Result.success(SsoLoginResult.LinkBlockedUnverifiedEmail)
        }

        is UserResolution.Resolved -> {
          val token =
            mintSession(
              session = session,
              user = resolution.user,
              loginMethod = provider.loginMethod,
              oldCookieToken = oldCookieToken,
              sessionExpiration = sessionExpiration,
              userAgent = userAgent,
              initialIp = initialIp,
            )
          Result.success(SsoLoginResult.Success(resolution.user, token))
        }
      }
    }

  /** The user an SSO sign-in resolves to, or a non-resolved signal. */
  private sealed interface UserResolution {
    data class Resolved(
      val user: User,
    ) : UserResolution

    data object Disabled : UserResolution

    /** An email-matched target exists but its email is unverified — link refused. */
    data object LinkBlocked : UserResolution
  }

  /**
   * Resolves the federated identity to its user within the open transaction:
   * a returning login (existing `(provider, subject)` row), a link onto an
   * active email-matched user, or a freshly created user. The link is refused
   * — [UserResolution.LinkBlocked] — only when the matched user both HAS a
   * password credential and its `emailVerifiedAt` is null: `passwordHash !=
   * null` is exactly "this account was created via [register]", the only path
   * that can plant an attacker-chosen, never-verified credential ahead of the
   * victim's SSO login. This blocks the *unverified* pre-hijacking case only —
   * once the victim clicks the mail [register] sent them the match verifies,
   * the gate passes, and the attacker's password survives the link; see
   * [SsoLoginResult.LinkBlockedUnverifiedEmail]. A match with no password
   * credential can only itself be a prior SSO provisioning
   * (`resolveOrProvisionUser` is the sole other creator, and it never sets
   * one) — always linkable regardless of `emailVerifiedAt`. The gate itself
   * looks only at `passwordHash`, so this reasoning holds even though (as of
   * RFC 113) this code path *does* mark `emailVerifiedAt` — via
   * [markVerifiedIfEmailMatches], guarded to fire only when the candidate's
   * current email still matches the provider-verified token email. Without
   * the passwordHash distinction, two providers racing to provision the SAME
   * brand-new email would wrongly block the loser's retry against the
   * winner's freshly (and unavoidably unverified-at-the-DB-row-level)
   * created user. A `23505`-derived violation
   * ([ConstraintViolationException]/[DuplicateEmailException]) propagates so
   * [loginWithSso] can retry the whole block; every other DAO failure is
   * rethrown to abort and surface as a 500.
   */
  private fun resolveOrProvisionUser(
    session: ed.unicoach.db.dao.SqlSession,
    provider: AuthProvider,
    subject: ProviderSubject,
    email: EmailAddress,
    nameCandidates: List<NameCandidate>,
  ): UserResolution {
    val existing = UserAuthIdentitiesDao.findByProviderAndSubject(session, provider, subject)
    val existingError = existing.exceptionOrNull()
    if (existingError != null && existingError !is NotFoundException) {
      throw existingError
    }

    if (existing.isSuccess) {
      // Returning login: load the identity's user across all soft-delete states.
      val resolved = UsersDao.findById(session, existing.getOrThrow().userId, SoftDeleteScope.ALL).getOrThrow()
      if (resolved.deletedAt != null) return UserResolution.Disabled
      return UserResolution.Resolved(markVerifiedIfEmailMatches(session, resolved, email))
    }

    // First sign-in for this subject: link to an active email match, else
    // create. The link is refused only for a password-holding match whose
    // email is unverified (see the KDoc); a passwordless match always links.
    // users_email_unique_active_idx forbids creating a second active user for
    // an already-taken email, so a refused match cannot fall back to
    // provisioning — rejection is the only sound outcome.
    val byEmail = UsersDao.findByEmail(session, email)
    val byEmailError = byEmail.exceptionOrNull()
    if (byEmailError != null && byEmailError !is NotFoundException) {
      throw byEmailError
    }

    val target =
      if (byEmail.isSuccess) {
        val matched = byEmail.getOrThrow()
        if (matched.passwordHash != null && matched.emailVerifiedAt == null) {
          return UserResolution.LinkBlocked
        }
        matched
      } else {
        UsersDao
          .create(
            session,
            NewUser(
              email = email,
              name = deriveName(nameCandidates, email),
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
          provider = provider,
          subject = subject,
          email = email,
          emailVerified = true,
        ),
      ).getOrThrow()
    return UserResolution.Resolved(markVerifiedIfEmailMatches(session, target, email))
  }

  /**
   * Marks [candidate] email-verified when, and only when, its current email
   * equals the provider-verified [email] from the token and the account is not
   * already verified, returning the (possibly updated) [User] to resolve to.
   * `loginWithSso` already hard-gates on `identity.emailVerified` before
   * resolution runs, so reaching this point means the provider asserted
   * [email]; the address guard is what stops that assertion from verifying a
   * *different* address a user has since moved to via [changeEmail] (which
   * resets `emailVerifiedAt` to null on address change) — the token still names
   * the old address, so an unguarded mark here would verify an address nobody
   * just proved.
   *
   * Verifying runs the same two steps `DbEmailVerifier.verify` does: mark, then
   * burn the account's outstanding verification tokens in this transaction.
   * [changeEmail] re-arms a token on every address change, so a user who moves
   * their address to their SSO address and then signs in with SSO would
   * otherwise leave that mailed link spendable for its full expiry against an
   * address that is already verified.
   */
  private fun markVerifiedIfEmailMatches(
    session: ed.unicoach.db.dao.SqlSession,
    candidate: User,
    email: EmailAddress,
  ): User {
    if (candidate.email != email || candidate.emailVerifiedAt != null) {
      return candidate
    }
    val verified = UsersDao.markEmailVerified(session, candidate.id).getOrThrow()
    VerificationTokensDao.consumeAllForUser(session, candidate.id).getOrThrow()
    return verified
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
    sessionExpiration: Duration,
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
          expiration = sessionExpiration,
          loginMethod = loginMethod,
        ),
      ).getOrThrow()
    return newToken
  }

  /** A name candidate and the source it came from, so a rejected one is identifiable in the log. */
  private data class NameCandidate(
    val source: String,
    val value: String?,
  )

  /**
   * Derives a [PersonName] for a newly provisioned SSO user from a fallback
   * chain of candidates (in order): the token's `name` claim, then the
   * client-supplied name (Apple route only; null on Google), then the email
   * local-part. Usability is whatever [PersonName] itself accepts, so the
   * blank/length rules live with the type and the schema constraint they
   * mirror; an unusable candidate falls through to the next rather than
   * aborting the transaction (a > 255-char claim is a latent 500 this chain
   * closes) and is logged, so a provider or client sending unusable names is
   * visible rather than silently absorbed. The candidate's own value is never
   * logged — it is user data; the source, its length, and the rule it broke
   * are what diagnose the sender, and the terminal failure below is bounded by
   * the same rule. The local-part is the last candidate, not a guaranteed one:
   * [EmailAddress] caps the whole address at 254 code points, but [PersonName]
   * counts UTF-16 units, so an astral local-part inside that cap can still
   * exceed 255 units and be rejected — leaving nothing to provision with. The
   * name is used only when provisioning a new user; an existing user is never
   * renamed.
   */
  private fun deriveName(
    candidates: List<NameCandidate>,
    email: EmailAddress,
  ): PersonName {
    val localPart = email.value.substringBefore('@')
    for (candidate in candidates + NameCandidate("email local-part", localPart)) {
      val value = candidate.value ?: continue
      when (val result = PersonName.create(value)) {
        is ValidationResult.Valid -> {
          return result.value
        }

        is ValidationResult.Invalid -> {
          logger.warn(
            "SSO name candidate rejected, falling through to the next source=[{}] length=[{}] error=[{}]",
            candidate.source,
            value.length,
            result.error,
          )
        }
      }
    }
    // Every candidate was rejected, each already logged above with its source
    // and the rule it broke; the local-part's value is user data and so is
    // reported by length only.
    throw IllegalStateException(
      "Could not derive a valid PersonName from SSO sign-in [emailLocalPartLength=${localPart.length}]",
    )
  }
}
