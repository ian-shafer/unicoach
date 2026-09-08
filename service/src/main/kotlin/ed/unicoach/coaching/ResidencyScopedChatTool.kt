package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject

/**
 * A [StudentScopedChatTool] that resolves the family's state of residency, hands
 * it to a chat-free `:college` tool, and merges the residency offer into what
 * comes back (RFC 169 D5/D6).
 *
 * The two search doors — `search_colleges` and `similar_colleges` — were
 * byte-identical adapters. That is the whole of what they share and the whole of
 * what this class holds: one read, one call, one merge. The wire definition
 * stays each tool's own, and no residency INPUT field is added on either, because
 * the state comes from `money_profiles` and must not be something the model can
 * contradict.
 *
 * It is a base class rather than two copies for a reason narrower than "they
 * looked alike": both tools must be on the SAME ruler for the same family, or a
 * search and a peer list about one school disagree about which price they ranked.
 * One implementation is how that stops being a thing to remember.
 *
 * [readProfile] is a SEAM. It defaults to the real service, and a test overrides
 * it to reach the one branch a Postgres-backed test cannot ask for on demand:
 * a profile read that FAILS, which must leave the search on the net-price ruler
 * rather than turning a working search into an error. It is a function-typed
 * parameter and nothing more — no interface, no registry, no wiring.
 */
abstract class ResidencyScopedChatTool(
  moneyProfiles: MoneyProfileService,
  private val readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : StudentScopedChatTool() {
  /**
   * Runs the wrapped `:college` tool with the family's residency — a two-letter
   * code, or null when it is not on file for any reason.
   */
  protected abstract suspend fun search(
    input: JsonObject,
    familyResidencyState: String?,
  ): JsonObject

  final override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val residency = Residency.forStudent(studentId, readProfile)
    return addResidencyOffer(search(input, residency.familyResidencyState), residency)
  }
}
