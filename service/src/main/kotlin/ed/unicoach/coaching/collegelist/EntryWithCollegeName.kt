package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry

/**
 * A [CollegeListEntry] paired with its college's display name.
 * [CollegeListService.listActiveWithNames] returns these directly (rather
 * than a sealed outcome, since a list has no "found/not found" branch to
 * carry) so the chat tool never reaches past the service into the
 * DAO/Database layer to resolve the name itself.
 */
data class EntryWithCollegeName(
  val entry: CollegeListEntry,
  val collegeName: String,
)
