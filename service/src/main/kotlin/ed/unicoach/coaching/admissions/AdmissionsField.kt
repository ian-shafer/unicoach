package ed.unicoach.coaching.admissions

/**
 * The wire vocabulary of `college_admissions_profile` (RFC 148): the section
 * keys the tool renders, which are also the names `data_availability` uses to
 * say a school does not report that section. One home for both, on the
 * [ed.unicoach.coaching.costs.CostField] precedent, so `data_availability` can
 * never name a section the tool does not render.
 *
 * Declaration order IS the wire order (RFC 145): the coach reaches for money
 * first, then what the school weighs, then when it is all due.
 *
 * [MERIT_AID] reads its key from [MeritAidWire], the section's own home, because
 * `college_cost_profile` renders that same sub-object under that same key: two
 * independently typed literals could drift and only one of the two tools would
 * move.
 */
enum class AdmissionsField(
  val wireName: String,
) {
  MERIT_AID(MeritAidWire.KEY),
  ADMISSION_FACTORS("admission_factors"),
  DEADLINES("deadlines"),
}
