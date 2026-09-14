package ed.unicoach.coaching

import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId

/**
 * The student's stored expected high-school graduation date, read the ONE way
 * every coach-facing surface has to read it (RFC 186, RFC 197).
 *
 * TWO RULES, and both used to be typed out twice.
 *
 * [SoftDeleteScope.ALL] and not the default `ACTIVE`: the foreign key guarantees
 * the ROW, not that `deleted_at` is still null, and a student soft-deleted
 * between the route resolving them and this read is the boundary's refusal to
 * make, not a corrupt-database fault.
 *
 * A MISSING ROW IS AN [IllegalStateException] and not an absence: the bare
 * `NotFoundException("Record not found")` would be answered to the caller as a
 * 404 and logged at debug -- a corrupt database reported as an ordinary miss.
 * Naming the row makes it the 500 it is, with the student in the message and the
 * DAO's exception as the cause.
 *
 * [whileDoing] is what the caller was in the middle of, and the reason this is
 * one function rather than two: the second copy of it differed from the first
 * only in that clause, and its own KDoc said so.
 */
internal fun graduationDateOf(
  session: SqlSession,
  studentId: StudentId,
  whileDoing: String,
): PartialDate =
  StudentsDao
    .findById(session, studentId, SoftDeleteScope.ALL)
    .getOrElse { cause ->
      if (cause is NotFoundException) {
        throw IllegalStateException("no students row for student [${studentId.value}] while $whileDoing", cause)
      }
      throw cause
    }.expectedHighSchoolGraduationDate
