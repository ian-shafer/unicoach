package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * Surface id for a [CollegeProgram] row. Lives alongside the model rather than in
 * its own file because `college_programs` is bulk-upserted reference data with no
 * standalone id-keyed read path.
 */
@JvmInline
value class CollegeProgramId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * A CIP program offering for one institution, from the `college_programs`
 * reference table (RFC 67). `cipCode` is a 6-digit CIP string; `credentialLevel`
 * is the Scorecard CREDLEV (always present in source, hence non-null).
 */
data class CollegeProgram(
  override val id: CollegeProgramId,
  val collegeId: CollegeId,
  val cipCode: String,
  val cipTitle: String,
  val credentialLevel: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeProgramId>,
  Created,
  Updated
