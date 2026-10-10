package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.db.models.IntendedSubjectAnswer
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A [StudentScopedChatTool] that resolves the two facts about this student a
 * search answer depends on — the family's state of residency (RFC 169 D5/D6)
 * and the student's own stored field of study (RFC 245 A2) — hands both to a
 * chat-free `:college` tool, and merges both offers into what comes back.
 *
 * It was `ResidencyScopedChatTool` until RFC 245. The rename is the whole of
 * what changed in its job: the two search doors — `search_colleges` and
 * `similar_colleges` — were byte-identical adapters, and this class is still
 * one read per fact, one call, one merge. The wire definition stays each tool's
 * own, and NEITHER fact is an input field on either tool, because both come from
 * stored answers and must not be something the model can contradict.
 *
 * It is a base class rather than two copies for a reason narrower than "they
 * looked alike": both tools must be on the SAME ruler and carry the SAME label
 * for the same student, or a search and a peer list about one school disagree
 * about which price they ranked and about whether it teaches the subject.
 *
 * [readProfile] and [readAnswer] are the two reads, and they are the WHOLE of
 * what this class takes: one dependency, one injection point. Each is also the
 * SEAM a test overrides to reach the branch a Postgres-backed test cannot ask
 * for on demand — a read that FAILS, which must leave the search working, ranked
 * on the net price with no field label, rather than turning it into an error.
 *
 * Neither is defaulted, here or in a subclass. The real services are named once
 * at the one place that owns the wiring, which writes each `::getForStudent`
 * itself; a default anywhere on this path would be a second, unreachable way to
 * build the same read, and a subclass could default differently with nothing to
 * catch it.
 */
abstract class SearchScopedChatTool(
  private val readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult>,
  private val readAnswer: suspend (StudentId) -> Result<IntendedSubjectAnswer>,
) : StudentScopedChatTool() {
  /**
   * Runs the wrapped `:college` tool with the family's residency — a two-letter
   * code, or null when it is not on file for any reason — and the student's
   * stored subject slugs, which are EMPTY for every state but a stored answer.
   *
   * Both travel as plain data: `:college` sees a code and a set of words, never
   * a money profile and never an [IntendedSubjectAnswer].
   */
  protected abstract suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
    storedSubjectSlugs: Set<String>,
  ): JsonObject

  final override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val residency = Residency.forStudent(studentId, name, readProfile)
    val field = IntendedField.forStudent(studentId, name, readAnswer)
    return addSearchOffers(
      search(input, residency.familyResidencyState, field.storedSubjectSlugs),
      residency,
      field,
    )
  }
}

/**
 * Merges both offers into a tool result object without the tool having to
 * rebuild it — the two search tools return whole objects built in `:college`,
 * which knows nothing about a money profile or an intended-subject answer (RFC
 * 67 keeps `:college` free of both).
 *
 * ONE merge for both, rather than two passes that each copy the object: the
 * result is copied once, and the two emitters each decide for themselves
 * whether they have anything to say.
 *
 * An error object is passed through untouched: an invitation attached to a
 * refusal is an invitation the model would read as part of the failure. The
 * test is asked of the owner of the refusal envelope, never re-derived from a
 * literal key here.
 */
internal fun addSearchOffers(
  result: JsonObject,
  residency: Residency,
  field: IntendedField,
): JsonObject {
  if (StudentScopedChatTool.isToolRefusal(result)) return result
  return buildJsonObject {
    result.forEach { (key, value) -> put(key, value) }
    putOffer(residency)
    putIntendedSubjectOffer(field)
  }
}
