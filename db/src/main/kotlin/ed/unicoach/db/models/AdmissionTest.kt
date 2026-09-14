package ed.unicoach.db.models

/**
 * The two national admission tests, backing `admission_test_administrations.test`
 * (RFC 197): our OWN enumeration, so the column is `TEXT` + `CHECK IN (...)` and
 * this is the one Kotlin home for it (CLAUDE.md schema conventions, the
 * [IncomeBand] / [ApplicationRound] precedent).
 *
 * IT CARRIES IDENTITY AND NOTHING ELSE. The stored code, and the test's name as
 * a family says it. Every OTHER word about a publisher -- the hour its deadlines
 * expire at, what its later deadline covers, who it is, where a family registers
 * -- is coach-facing English, so it lives in `AdmissionTestCopy` in `:service`
 * beside the surface that speaks it, on the `FigureStatusCopy` /
 * `MoneySourceCopy` / `AssuranceTierCopy` precedent. A `:db` enum that carried
 * sentences would put model-facing copy under the storage layer, where no
 * renderer owns it and no rendering test reaches it.
 *
 * Those words are also NOT columns of the table: they are facts about the TEST,
 * identical on all fifteen rows of a cycle, and a per-row copy is fifteen places
 * for one of them to drift.
 */
enum class AdmissionTest(
  /** The stored code, and the `CHECK IN ('sat', 'act')` the schema admits. */
  val value: String,
  /** The test's name as a family says it: "SAT", "ACT". Never the lower-case code. */
  val displayName: String,
) {
  SAT(value = "sat", displayName = "SAT"),
  ACT(value = "act", displayName = "ACT"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): AdmissionTest? = entries.find { it.value == value }
  }
}
