package ed.unicoach.db.dao

import java.security.SecureRandom

/**
 * Session-metadata values tests need and should not each re-derive (RFC 265).
 *
 * It lives in `:db`'s test fixtures because the DAO, `:service` and
 * `:public-web` tests all write a value past the `sessions_metadata_size_check`
 * cap, and `:db`'s fixtures are the one home all three already consume.
 */
object SessionMetadataFixtures {
  /**
   * Letters past the metadata cap (256 KiB, measured by `pg_column_size` on the
   * value as stored), so a write carrying them is refused as too large.
   *
   * Random, from a CSPRNG, so nothing about the text is compressible: the cap is
   * measured after TOAST compression, and a repeated letter would measure small
   * and be stored.
   */
  fun textPastTheCap(): String {
    val random = SecureRandom()
    return buildString { repeat(LETTERS_PAST_THE_CAP) { append('a' + random.nextInt(26)) } }
  }

  /** Comfortably more than the cap's 262144 bytes. */
  private const val LETTERS_PAST_THE_CAP = 300_000
}
