package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CollegeId

/*
 * The read plumbing every Common Data Set DAO shares (RFC 170, 175).
 *
 * One home for the same reason SourceDocumentJoin is one: AidPolicyDao and
 * BorrowingDao ask two different questions OF THE SAME FILING, and each had
 * its own copy of this shape. The copies had already drifted -- a rename fixed
 * one KDoc and left the other saying something that is not English -- which is
 * what duplicated plumbing does before it does anything worse.
 */

/**
 * One row of a long-format CDS read: the filing it came from, and what it says.
 *
 * [fact] is the caller's OWN sealed vocabulary, because that is the half the
 * two reads do not share: an aid-policy row is a statistic, a headcount or a
 * form, and a borrowing row is an average, a borrower count or a class size.
 * The citation four are identical, and are held here.
 */
internal data class CitedFact<F>(
  val collegeId: CollegeId,
  val academicYear: AcademicYear,
  val sourceUrl: String,
  val archiveUrl: String?,
  val fact: F,
)

/**
 * One fact per key, REFUSING a duplicate.
 *
 * `associate` keeps the last row silently, and a duplicate key here means the
 * read matched a row it did not mean to -- a second measure over another
 * cohort, say -- which would then be spoken as the figure the caller asked
 * for. The narrowing in the query is the first defence; this is the one that
 * says so when the narrowing is wrong.
 */
internal fun <T, K> singleByKey(
  facts: List<T>,
  key: (T) -> K,
  axis: String,
  location: String,
): Map<K, T> {
  val byKey = facts.groupBy(key)
  // The duplicated ROWS, not only the key they collided on: a duplicate means
  // the query matched a row it did not mean to, and the next question is always
  // "which of the two is the intruder?" -- unanswerable from a key alone, and
  // the fact types are data classes whose own `toString` is the evidence.
  val duplicated = byKey.filterValues { it.size > 1 }
  if (duplicated.isNotEmpty()) {
    throw corruptValue(
      duplicated.entries.joinToString { (duplicatedKey, rows) -> "[$duplicatedKey] x[${rows.size}] = [$rows]" },
      "one row per [$axis]",
      location,
    )
  }
  return byKey.mapValues { (_, rows) -> rows.single() }
}

/**
 * A stored population slug as its domain type. A slug no enum reads is a
 * schema/enum drift, not a row to skip: it is raised as the located
 * [corruptValue] so it stays inside the Result channel with its PermanentError
 * marker and names the column that holds it.
 */
internal fun decodeCohortPopulation(
  name: String,
  row: String,
): CohortPopulation =
  CohortPopulation.fromValue(name)
    ?: throw corruptValue(name, "CohortPopulation", "cohort_population_counts.population ($row)")
