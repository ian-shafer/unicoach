package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AuthIdentity
import ed.unicoach.db.models.AuthIdentityId
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.NewAuthIdentity
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Append-only DAO for `user_auth_identities`. Composes [Creatable] for the
 * single write; [findByProviderAndSubject] and [listByUser] are bespoke reads
 * (not `findById`/`list` shapes), so they stay outside the capability
 * interfaces. There is no update/delete surface — the table is append-only.
 */
object UserAuthIdentitiesDao : Creatable<NewAuthIdentity, AuthIdentity> {
  /**
   * Reconstructs the [AuthIdentity] from its columns. The DB `CHECK`s and the
   * write path already guarantee valid persisted values, so any reconstruction
   * failure here is row corruption, surfaced as a [CorruptPersistedValueException]
   * (a [ed.unicoach.error.PermanentError]) carrying the offending raw value and
   * the structured [ValidationError]. Crucially this is NOT a
   * [ConstraintViolationException] — that exception is the in-transaction
   * `23505`/`23514` retry signal `loginWithGoogle` recovers from, and a corrupt
   * row must not be mistaken for a concurrency race.
   */
  private fun mapIdentity(rs: ResultSet): AuthIdentity {
    val rawEmail = rs.getString("email")
    val email =
      when (val result = EmailAddress.create(rawEmail)) {
        is ValidationResult.Valid -> result.value
        is ValidationResult.Invalid -> throw CorruptPersistedValueException(rawEmail, result.error)
      }
    val rawSubject = rs.getString("subject")
    val subject =
      when (val result = ProviderSubject.create(rawSubject)) {
        is ValidationResult.Valid -> result.value
        is ValidationResult.Invalid -> throw CorruptPersistedValueException(rawSubject, result.error)
      }
    val rawProvider = rs.getString("provider")
    val provider =
      AuthProvider.fromWire(rawProvider)
        ?: throw CorruptPersistedValueException(
          rawProvider,
          ValidationError.InvalidFormat(expected = "a known AuthProvider wire value"),
        )
    return AuthIdentity(
      id = AuthIdentityId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      provider = provider,
      subject = subject,
      email = email,
      emailVerified = rs.getBoolean("email_verified"),
      createdAt = rs.getInstant("created_at"),
    )
  }

  override fun create(
    session: SqlSession,
    input: NewAuthIdentity,
  ): Result<AuthIdentity> =
    session.insertReturning(
      table = "user_auth_identities",
      columns =
        linkedMapOf<String, Bind>(
          "user_id" to { stmt, i -> stmt.setObject(i, input.userId.value) },
          "provider" to { stmt, i -> stmt.setString(i, input.provider.wire) },
          "subject" to { stmt, i -> stmt.setString(i, input.subject.value) },
          "email" to { stmt, i -> stmt.setString(i, input.email.value) },
          "email_verified" to { stmt, i -> stmt.setBoolean(i, input.emailVerified) },
        ),
      map = ::mapIdentity,
      mapError = ::mapCreateError,
    )

  /**
   * A `(provider, subject)` collision (the unique index) or a CHECK violation is
   * a [ConstraintViolationException] — the in-transaction signal `loginWithGoogle`
   * retries on. Everything else falls through to [mapDatabaseError].
   */
  private fun mapCreateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23505", "23514" -> ConstraintViolationException(e)
      else -> mapDatabaseError(e)
    }

  /**
   * Resolves a federated identity by its stable `(provider, subject)` key.
   * [NotFoundException] when absent. Unaffected by `users` soft-delete — the
   * identity row persists; user-state checks are the caller's responsibility.
   */
  fun findByProviderAndSubject(
    session: SqlSession,
    provider: AuthProvider,
    subject: ProviderSubject,
  ): Result<AuthIdentity> =
    session.queryOne(
      "SELECT * FROM user_auth_identities WHERE provider = ? AND subject = ?",
      bind = { stmt ->
        stmt.setString(1, provider.wire)
        stmt.setString(2, subject.value)
      },
      map = ::mapIdentity,
    )

  /** All identities linked to a user, oldest-first; empty for an unknown user. */
  fun listByUser(
    session: SqlSession,
    userId: UserId,
  ): Result<List<AuthIdentity>> =
    session.queryList(
      "SELECT * FROM user_auth_identities WHERE user_id = ? ORDER BY created_at, id",
      bind = { it.setObject(1, userId.value) },
      map = ::mapIdentity,
    )
}
