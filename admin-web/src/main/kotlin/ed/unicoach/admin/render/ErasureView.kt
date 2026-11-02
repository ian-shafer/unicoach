package ed.unicoach.admin.render

import ed.unicoach.db.models.TableErasure
import ed.unicoach.db.models.UserId
import kotlinx.html.MAIN
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/**
 * The operator's record of one erasure (RFC 266 §10): what the walk did, per
 * table, under the erased user's id. An empty [tables] is a re-run on an
 * account already erased, which is harmless and says so.
 */
fun MAIN.renderErasure(
  userId: UserId,
  tables: List<TableErasure>,
) {
  h1 { +"Erased user ${userId.asString}" }
  if (tables.isEmpty()) {
    p { +"Nothing was left to erase: this account was already erased." }
    return
  }
  table {
    tr {
      th { +"Table" }
      th { +"Moved" }
      th { +"Erased" }
      th { +"Deleted" }
    }
    tables.forEach { row ->
      tr {
        td { +row.table }
        td { +row.moved.toString() }
        td { +row.erased.toString() }
        td { +row.deleted.toString() }
      }
    }
  }
}
