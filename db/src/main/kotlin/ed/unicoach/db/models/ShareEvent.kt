package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `share_events` log (RFC 160): one immutable fact
 * about the Family Cost Report share surface, recorded in the same transaction
 * as the share mutation it describes. [shareId] is null exactly when [kind] is
 * [ShareEventKind.OPTED_OUT] (the DB CHECK holds the pairing).
 */
data class ShareEvent(
  override val id: ShareEventId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val shareId: CostReportShareId?,
  val kind: ShareEventKind,
) : Identifiable<ShareEventId>,
  Created
