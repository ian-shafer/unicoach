package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Observation

/**
 * A [CollegeListEntry] paired with the observations cited as support for it.
 * [CollegeListService.listForStudent] returns these directly (rather than a
 * sealed outcome, since a list has no "found/not found" branch to carry) so
 * the REST route handler never reaches past the service into the DAO/Database
 * layer to assemble the supporting-observations projection itself.
 */
data class CollegeListEntryWithSupport(
  val entry: CollegeListEntry,
  val supportingObservations: List<Observation>,
)
