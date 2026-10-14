package ed.unicoach.db.dao

import ed.unicoach.db.models.LinkSide
import java.util.UUID

/**
 * The column a [LinkSide] names, and the id it binds, on every two-party family
 * table (`supporter_student_links`, `supporter_link_requests`) — a fixed DAO
 * identifier, never caller data. The side is the only way to reach either, so
 * `"student_id"` with a supporter's id is not something a call site can write.
 */
internal val LinkSide.column: String
  get() =
    when (this) {
      is LinkSide.Student -> "student_id"
      is LinkSide.Supporter -> "supporter_id"
    }

internal val LinkSide.boundId: UUID
  get() =
    when (this) {
      is LinkSide.Student -> id.value
      is LinkSide.Supporter -> id.value
    }
