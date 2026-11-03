package ed.unicoach.admin.render

import ed.unicoach.account.ErasureConfirmation
import ed.unicoach.db.models.TableErasure
import ed.unicoach.db.models.UserId
import kotlinx.html.MAIN
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr

/** The one line the erase page says about the person being told (RFC 267 §4). */
enum class ErasureNote(
  val text: String,
) {
  ENQUEUED("Confirmation email queued."),
  NO_VERIFIED_ADDRESS("No verified address; no email sent."),
  ALREADY_ERASED("Already erased; no email sent."),
  ;

  companion object {
    fun of(confirmation: ErasureConfirmation): ErasureNote =
      when (confirmation) {
        ErasureConfirmation.ENQUEUED -> ENQUEUED
        ErasureConfirmation.NO_VERIFIED_ADDRESS -> NO_VERIFIED_ADDRESS
      }
  }
}

/**
 * The operator's record of one erasure (RFC 266 §10): what the walk did, per
 * table, under the erased user's id, and whether the person is told. An
 * account already erased is the same page with an empty table.
 */
fun MAIN.renderErasure(
  userId: UserId,
  tables: List<TableErasure>,
  note: ErasureNote,
) {
  h1 { +"Erased user ${userId.asString}" }
  p { +note.text }
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
