package ed.unicoach.db.session

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat

/**
 * The one codec between a [SessionMetadata] and the canonical proto3 JSON
 * `sessions.metadata` stores (RFC 265, brief 0013 D4b).
 *
 * Both directions live here so the stored spelling has one home: SQL that reads
 * the column (`metadata -> 'drafts' -> 'changeEmail' ->> 'newEmail'`) matches
 * what [printOrNull] writes because nothing else writes it.
 *
 * The parser is STRICT (D-E): no `ignoringUnknownFields`. An unknown field, or
 * JSON that is not a `SessionMetadata`, is a row no code wrote, and reading it
 * as the default would silently drop a family's draft along with the evidence.
 * A change that removes a field clears the rows holding it in its own
 * migration instead.
 */
object SessionMetadataJson {
  private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
  private val parser = JsonFormat.parser()

  /**
   * [metadata] as the column stores it: `null` — SQL `NULL` — for the default
   * instance, the canonical JSON otherwise. "Nothing set" has ONE spelling in
   * the column, the native absence the read side already maps back to the
   * default instance, rather than `NULL` on some rows and `{}` on others.
   */
  fun printOrNull(metadata: SessionMetadata): String? =
    if (metadata == SessionMetadata.getDefaultInstance()) null else printer.print(metadata)

  /** [json] as a [SessionMetadata]; throws [InvalidProtocolBufferException] for anything else. */
  fun parse(json: String): SessionMetadata = SessionMetadata.newBuilder().also { parser.merge(json, it) }.build()
}
