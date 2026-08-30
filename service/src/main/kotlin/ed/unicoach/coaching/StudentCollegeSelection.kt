package ed.unicoach.coaching

import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.StudentId

/**
 * The read every student-scoped college tool starts from: the student's ACTIVE
 * college list, optionally narrowed to a subset of ids, with the `colleges`
 * rows for what was selected already batched in.
 *
 * One home for three rules `college_cost_profile` and
 * `college_admissions_profile` must answer identically, and which were
 * previously copied between them:
 *
 * - the split. A null [collegeIds] selects the whole active list; a supplied
 *   one is read once per distinct id and partitioned into ids that are on the
 *   list and ids that are not, so a stranger's id is REPORTED rather than
 *   failing the whole read (best-effort, never all-or-nothing).
 * - the batch. One `listByIds` for the whole answer, never one query per
 *   college.
 * - the invariant. A selected id with no `colleges` row is impossible -- the
 *   list-entry FK guarantees the row exists -- so it is an [error] naming the
 *   student and the id, not a silently dropped college.
 *
 * Internal to `:service`: this is the tool family's shared read scaffolding,
 * not a published API.
 */
internal class StudentCollegeSelection private constructor(
  private val studentId: StudentId,
  /** The ids to answer for, in the order the active list (or the caller's subset) gives them. */
  val selected: List<CollegeId>,
  /** The requested ids that are not on this student's active list, reported rather than answered. */
  val unknown: List<CollegeId>,
  private val entryById: Map<CollegeId, CollegeListEntry>,
  private val collegeById: Map<CollegeId, College>,
) {
  /** The `colleges` rows behind [selected] -- for reads over the answer as a whole (a run's data vintage). */
  val colleges: Collection<College> get() = collegeById.values

  /**
   * One result per selected college, in [selected]'s order, each built from its
   * `colleges` row and its list status. The place the two services would
   * otherwise each re-derive the same lookup pair.
   */
  fun <T> map(build: (College, CollegeListEntryStatus) -> T): List<T> =
    selected.map { id -> build(collegeOf(id), entryById.getValue(id).status) }

  private fun collegeOf(id: CollegeId): College =
    collegeById[id] ?: error(
      "invariant broken: active list entry references a college listByIds did not return " +
        "(the list-entry FK guarantees the colleges row exists): " +
        "student=[${studentId.value}] collegeId=[${id.value}]",
    )

  companion object {
    /** Reads the active list and the selected `colleges` rows on [session] -- two statements, whatever the list's size. */
    fun read(
      session: SqlSession,
      studentId: StudentId,
      collegeIds: List<CollegeId>?,
    ): StudentCollegeSelection {
      val entries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
      val entryById = entries.associateBy { it.collegeId }
      val (selected, unknown) =
        if (collegeIds == null) {
          entries.map { it.collegeId } to emptyList()
        } else {
          collegeIds.distinct().partition { it in entryById }
        }
      val collegeById =
        CollegesDao
          .listByIds(session, selected)
          .getOrThrow()
          .associateBy { it.id }
      return StudentCollegeSelection(studentId, selected, unknown, entryById, collegeById)
    }
  }
}
