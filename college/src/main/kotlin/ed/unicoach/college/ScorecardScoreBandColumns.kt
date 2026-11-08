package ed.unicoach.college

/**
 * The College Scorecard's admissions score-band columns (RFC 195), the
 * publisher's own names: the ONE list of them. The ingest reads each, and the
 * tests holding the band term to the publisher's dictionary iterate [VARIABLES],
 * so a further band column is held to that term by being added here.
 */
object ScorecardScoreBandColumns {
  const val SATVR25 = "SATVR25"
  const val SATVR75 = "SATVR75"
  const val SATMT25 = "SATMT25"
  const val SATMT75 = "SATMT75"
  const val ACTCM25 = "ACTCM25"
  const val ACTCM75 = "ACTCM75"
  const val SAT_AVG = "SAT_AVG"

  /** Every score-band column the ingest reads. */
  val VARIABLES: List<String> = listOf(SATVR25, SATVR75, SATMT25, SATMT75, ACTCM25, ACTCM75, SAT_AVG)
}
