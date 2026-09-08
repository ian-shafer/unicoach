package ed.unicoach.coaching

import ed.unicoach.coaching.costs.PrecisionOffer
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UsStateCodes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The family's state of residency, as the two search tools need it (RFC 169 D5)
 * — a two-letter USPS code when it is on file, and null in every other case.
 *
 * Search used to have no way to know it at all: `CollegeChatTool` and
 * `SimilarCollegesChatTool` were plain `DelegatingChatTool`s, so no student id
 * reached them, while the coach's own system context already said "state of
 * residency: answered (WA)". The residency was in the room; it was not in the
 * call.
 *
 * Every state that is not [OnFile] resolves to the net-price ruler, which is
 * search's behaviour column for column before this RFC — that is what makes the
 * whole feature configuration-free to roll back. But they are NOT one state:
 * [NotAsked] is the only one the offer is made in, and [Unavailable] carries WHY
 * it is unavailable, because "the family declined" and "we could not read the
 * profile" are different facts and the second one is not something to tell a
 * family we know.
 */
internal sealed interface Residency {
  /**
   * The USPS code when it is on file, and null in every other case — what selects
   * the ruler.
   *
   * Spelled as every other site spells it: `CollegeQuery`, both tool `execute`
   * signatures and `PriceRuler.of` all say `familyResidencyState`, and a bare
   * `state` beside a `college.state` in the same payload is the one word this
   * codebase cannot afford to leave ambiguous.
   */
  val familyResidencyState: String?

  /** True ONLY for [NotAsked]: the offer is keyed on the question never having been put. */
  val offerable: Boolean

  /** The family said where they live. There is a state, and nothing left to offer. */
  data class OnFile(
    override val familyResidencyState: String,
  ) : Residency {
    override val offerable: Boolean get() = false
  }

  /**
   * Nobody has asked yet — no money profile at all, or one whose residency is
   * still [AnswerStatus.UNANSWERED]. The one state the offer is made in.
   */
  data object NotAsked : Residency {
    override val familyResidencyState: String? get() = null

    override val offerable: Boolean get() = true
  }

  /**
   * We cannot use a state and must not ask again. The CAUSE travels with it,
   * because the three are different facts and only one of them is a decision the
   * family made.
   */
  data class Unavailable(
    val cause: Cause,
  ) : Residency {
    override val familyResidencyState: String? get() = null

    override val offerable: Boolean get() = false
  }

  /** WHY a residency is unusable — three facts that used to be one. */
  enum class Cause {
    /** The family said they would rather not say. A closed topic, permanently. */
    DECLINED,

    /**
     * The money-profile read FAULTED. We do not know what the family said, which
     * is not the same as knowing they have said nothing — and the copy on the
     * page must not claim it is.
     */
    READ_FAILED,

    /**
     * A stored profile says ANSWERED while holding no usable state: either no
     * state at all, or one the published USPS vocabulary does not contain. Both
     * violate `money_profiles`' own CHECK, so both are CORRUPTION — logged as
     * such, and never allowed to fail a search.
     */
    CORRUPT_PROFILE,
  }

  companion object {
    private val logger = LoggerFactory.getLogger(Residency::class.java)

    /**
     * Reads the residency for [studentId]. Never fails: every unknown, and every
     * fault, is the net-price ruler.
     *
     * [readProfile] is a SEAM, not a framework. The [Cause.READ_FAILED] branch is
     * the one a Postgres-backed test cannot reach on demand, and it is the branch
     * that decides whether a database fault turns a working search into an error
     * or into the behaviour that shipped for a year. A function-typed parameter
     * makes it testable and adds nothing else.
     */
    suspend fun forStudent(
      studentId: StudentId,
      readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult>,
    ): Residency =
      readProfile(studentId).fold(
        onSuccess = ::forProfile,
        onFailure = { error -> onReadFailure(studentId, error) },
      )

    /** The residency a profile read ANSWERS with — one altitude, no fault handling. */
    private fun forProfile(outcome: GetMoneyProfileResult): Residency =
      when (outcome) {
        // No profile row at all is not a decline: nobody has asked yet.
        is GetMoneyProfileResult.NotFound -> {
          NotAsked
        }

        is GetMoneyProfileResult.Found -> {
          val profile = outcome.profile
          when (profile.residencyStatus) {
            AnswerStatus.ANSWERED -> onAnswered(profile.studentId, profile.residencyState)
            AnswerStatus.UNANSWERED -> NotAsked
            AnswerStatus.DECLINED -> Unavailable(Cause.DECLINED)
          }
        }
      }

    /**
     * An ANSWERED residency, which must carry a usable state.
     *
     * `money_profiles` has a CHECK tying the two, and the published USPS set is
     * the vocabulary every writer goes through, so neither failure can happen
     * through a supported path. Both DO happen if a row is edited around them,
     * and this is the one read that would otherwise pass the bad value on: a
     * state outside the vocabulary reaches `PriceRuler.Published`, whose `init`
     * throws — turning EVERY search by that family into a tool failure instead of
     * the documented fall back to the net-price ruler. So it is caught here,
     * logged as the corruption it is, and degraded exactly as its sibling is.
     */
    private fun onAnswered(
      studentId: StudentId,
      storedState: String?,
    ): Residency {
      val state = storedState?.let(UsStateCodes::parse)
      if (state == null) {
        logger.error(
          "money_profiles row for student [{}] says residency is answered but holds [{}], which is not a " +
            "published USPS code -- the search runs on the net-price ruler; the row violates its own CHECK",
          studentId.asString,
          storedState,
        )
        return Unavailable(Cause.CORRUPT_PROFILE)
      }
      return OnFile(state)
    }

    /** A money-profile read that FAULTED: logged with the throwable, and never a failed search. */
    private fun onReadFailure(
      studentId: StudentId,
      error: Throwable,
    ): Residency {
      logger.warn(
        "a residency read for the search tools failed for student [{}]: the search runs on the net-price " +
          "ruler, which is what it did before a residency-correct one existed",
        studentId.asString,
        error,
      )
      return Unavailable(Cause.READ_FAILED)
    }
  }
}

/**
 * The invitation to put the family's state on file, RFC 145's `precision_offer`
 * shape applied to search (RFC 169 D6).
 *
 * Search is UPSTREAM of the cost tool's own offer — that one needs a college
 * list, and this one runs before the family has any list at all — so the
 * unknown-residency case here is the LOAD-BEARING one, not a corner. The offer
 * is therefore made on the first search, and it never withholds a row, never
 * blocks a result and never re-asks after a decline.
 *
 * The key is OMITTED entirely when there is nothing to offer, so its presence
 * stays meaningful to the model — the same rule the cost tool's array follows.
 */
internal object ResidencyOffer {
  /** The wire key on a search result. */
  const val KEY = "residency_offer"

  /**
   * The `money_profiles` field this offer would fill, read from
   * [PrecisionOffer.RESIDENCY] rather than re-typed: that enum owns the wire
   * name `update_money_profile` accepts, and a second copy here is a second
   * thing to rename when it moves.
   */
  val FIELD: String = PrecisionOffer.RESIDENCY.field

  /**
   * The copy, no wider than what the answer buys: a state on file makes the
   * ranking a price this family would actually pay. It does not promise aid, an
   * after-aid number, or a figure for a school that publishes none.
   *
   * It also does NOT restate what this ranking is on. The page it rides carries
   * `price_ruler.note`, which says the basis in the one wording every surface
   * uses; a second account of the same fact beside it, in different words, is
   * the drift `PriceRuler.describe` exists to prevent. This object's job is the
   * INVITATION.
   */
  const val OFFER =
    "If the student shares the state they live in (record it with ${MoneyProfileChatTool.TOOL_NAME}), " +
      "searches can rank on the price this family would actually be charged at each school -- the " +
      "out-of-state published price at a public school outside their state -- instead of the ranking " +
      "named above. Offer it; never require it, and never hold back a result waiting for it."

  /**
   * The wire key carrying WHY a residency could not be used, when the reason is
   * not "nobody asked".
   *
   * `price_ruler.note` says what the ranking IS and what would change it, and
   * deliberately asserts nothing about this family — `:college` cannot tell a
   * fault from a silence. This key is where the layer that CAN tells the truth
   * about it: a read that faulted is not a family with no state on file, and a
   * page that said so was stating something it did not know.
   */
  const val UNAVAILABLE_KEY = "residency_unavailable"

  /** The sentence for each cause that is worth saying out loud; null when there is nothing to add. */
  fun noteFor(cause: Residency.Cause): String? =
    when (cause) {
      // A decline is a decision the family made and a closed topic. Repeating it
      // back on every result would be re-raising it in all but name.
      Residency.Cause.DECLINED -> {
        null
      }

      Residency.Cause.READ_FAILED -> {
        "This family's state of residency could not be read on this request, so the ranking above is the " +
          "net-price one. Do not tell them it is not on file -- we do not know whether it is."
      }

      Residency.Cause.CORRUPT_PROFILE -> {
        "This family's stored state of residency could not be used, so the ranking above is the net-price " +
          "one. Do not tell them it is not on file; if they want a residency-correct ranking, ask them to " +
          "state it again."
      }
    }
}

/**
 * Writes whatever a residency has to say on a result: the invitation when nobody
 * has asked, the reason when we hold no usable state and the reason is worth
 * saying, and nothing at all otherwise.
 *
 * A top-level emitter on the `putResidencyTiers` precedent, and named for what it
 * PUTS rather than repeating its own object's name at the call site.
 */
internal fun JsonObjectBuilder.putOffer(residency: Residency) {
  if (residency.offerable) {
    putJsonObject(ResidencyOffer.KEY) {
      put("field", ResidencyOffer.FIELD)
      put("offer", ResidencyOffer.OFFER)
    }
    return
  }
  (residency as? Residency.Unavailable)?.let { unavailable ->
    ResidencyOffer.noteFor(unavailable.cause)?.let { put(ResidencyOffer.UNAVAILABLE_KEY, it) }
  }
}

/**
 * Merges [ResidencyOffer] into a tool result object without the tool having to
 * rebuild it — the two search tools return whole objects built in `:college`,
 * which knows nothing about a money profile (RFC 67 keeps `:college` free of
 * `:chat` and of the profile).
 *
 * An error object is passed through untouched: an invitation attached to a
 * refusal is an invitation the model would read as part of the failure.
 */
internal fun addResidencyOffer(
  result: JsonObject,
  residency: Residency,
): JsonObject {
  // Asked of the owner of the refusal envelope, never re-derived from a literal
  // key here: an invitation attached to a failure reads as part of the failure,
  // and a second unlinked copy of that test is how a change to the envelope
  // quietly stops being recognised.
  if (StudentScopedChatTool.isToolRefusal(result)) return result
  return buildJsonObject {
    result.forEach { (key, value) -> put(key, value) }
    putOffer(residency)
  }
}
