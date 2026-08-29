package ed.unicoach.db.models

/**
 * The RFC 140 launch-set coverage report, computed from the DB after a CDS
 * ingest: distinct-college counts per fact group over the three CDS reference
 * tables, plus the student-listed colleges (`college_list_entries`) the corpus
 * misses entirely.
 */
data class CdsCoverage(
  /** Colleges with at least one CDS fact row in any of the three tables. */
  val launchSetCount: Int,
  val meritAidCount: Int,
  val admissionFactorsCount: Int,
  /** Colleges with at least one round row (the reliable offered flags). */
  val deadlinesFlagsCount: Int,
  /**
   * Colleges with at least one COMPLETE month+day on some round. A month-only
   * date ("closes in March") is valid stored CDS data but is not a concrete
   * date and does not count here.
   */
  val deadlinesWithDateCount: Int,
  /** Names of actively student-listed colleges with no CDS row at all. */
  val studentListedMissing: List<String>,
)
