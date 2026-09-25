package ed.unicoach.coaching.aid

import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.SourceUrlStatus
import java.time.LocalDate

/**
 * THE FOUR JURISDICTIONS WE TRIED TO READ AND COULD NOT (brief 0012 D9).
 *
 * `fafsa_filing_deadlines` holds no row for Arkansas, Delaware, North Carolina
 * or New Hampshire, and the reason is the same in all four cases: the agency's
 * own page is a JavaScript application or refuses every client we have, so no
 * human could read a deadline off it during the `fafsa/02` harvest
 * (`product/0012-fafsa-support/discover/state-deadlines-a.md`,
 * `-b.md`). A row invented from a secondary aggregator is the one thing that
 * corpus refuses to contain.
 *
 * THIS IS A CITATION FOR "HERE IS THE PAGE WE TRIED", AND NOT FOR A DEADLINE.
 * No date is claimed for these four, which is D9's whole point: a family gets
 * the agency's name, the page we opened and the day we opened it, so it can
 * finish the job we could not. The page it names IS a
 * [FafsaProcessCopy.FafsaDocument] -- a publisher, a title, an https url,
 * guarded in one place -- plus the two facts a deadline document does not
 * carry: the day WE looked, and what the address answered when we asked.
 *
 * IT IS A STATEMENT ABOUT TODAY'S SEED, so a jurisdiction named here that also
 * holds live rows is an entry the seed has overtaken. [FafsaDeadlineService]
 * WARNS the operator and serves the rows; it does not refuse the read, because
 * a served jurisdiction never reaches the gap list, so nothing can render the
 * contradiction, and refusing would answer a data FIX with a blank page for all
 * fifty-one.
 */
object FafsaUnreadableAgencies {
  /** The day a person opened every page below and got no deadline from it. */
  val CHECKED_ON: LocalDate = LocalDate.parse("2026-09-14")

  /**
   * One agency whose own page we could not read a deadline from.
   *
   * It CARRIES a [FafsaProcessCopy.FafsaDocument] rather than re-typing its
   * three fields: "a published page we cite" is one concept with one guard, so
   * an entry that names no agency and links no https page does not construct,
   * and that rule has exactly one home to be edited in.
   */
  data class UnreadableAgency(
    /** The page we opened, in the one type this package has for a page we cite. */
    val document: FafsaProcessCopy.FafsaDocument,
    /**
     * What `bin/check-link` answered for this page in the run that wrote the
     * entry -- the EVIDENCE, per entry, in the same vocabulary a swept document
     * records.
     *
     * NO DEFAULT. The unmeasured answer must not be the anchor-rendering one:
     * a fifth entry for a page that is actually gone would otherwise inherit
     * "link it" because nobody remembered a flag. Whether the verdict earns an
     * anchor is [ed.unicoach.db.models.SourceUrlStatus.rendersAsAnchor]'s rule
     * and is asked at the page, not decided here -- a 403 is a state agency
     * refusing our client, which is the same refusal that stopped the harvest,
     * and not a dead page.
     */
    val urlStatus: SourceUrlStatus,
    /** The day a person opened [document] and got no deadline out of it. */
    val checkedOn: LocalDate = CHECKED_ON,
  ) {
    // THREE DELEGATES AND NOT `agency.document.x` AT EVERY CALL SITE: an entry
    // IS the page we tried, so a caller asking who published it or where it is
    // should not have to reach through the field that holds it. [document]
    // stays public for the one caller that needs the page WHOLE -- the /fafsa
    // gap row builds its citation out of it.
    val publisher: String get() = document.publisher

    val title: String get() = document.title

    val url: String get() = document.url
  }

  /**
   * The four, keyed by the jurisdiction they belong to.
   *
   * Each url was swept with `nix develop -c bin/check-link` while this corpus
   * was written (RFC 238), and each entry STATES what that sweep answered:
   * Arkansas 200, Delaware 403, North Carolina 200, New Hampshire 403. Two 403s
   * and no 404s, so all four still earn an anchor -- a 403 is recorded as "we
   * asked and got no usable answer", which is the honest reading of the same
   * refusal that stopped the harvest reading the page in the first place.
   */
  val BY_JURISDICTION: Map<DeadlineJurisdiction.State, UnreadableAgency> =
    listOf(
      "AR" to
        UnreadableAgency(
          FafsaProcessCopy.FafsaDocument(
            publisher = "Arkansas Division of Higher Education",
            title = "Financial Aid",
            url = "https://adhe.edu/students-parents/financial-aid",
          ),
          // 200: the page served, and a person still got no deadline out of it.
          urlStatus = SourceUrlStatus.FOUND,
        ),
      "DE" to
        UnreadableAgency(
          FafsaProcessCopy.FafsaDocument(
            publisher = "Delaware Higher Education Office",
            title = "Higher Education",
            url = "https://education.delaware.gov/students-family/higher-education/",
          ),
          // 403: the agency refuses our client, which is not a dead page.
          urlStatus = SourceUrlStatus.UNKNOWN,
        ),
      "NC" to
        UnreadableAgency(
          FafsaProcessCopy.FafsaDocument(
            publisher = "College Foundation of North Carolina",
            title = "Next NC Scholarship",
            url = "https://www.cfnc.org/pay-for-college/next-nc-scholarship/",
          ),
          // 200.
          urlStatus = SourceUrlStatus.FOUND,
        ),
      "NH" to
        UnreadableAgency(
          FafsaProcessCopy.FafsaDocument(
            publisher = "New Hampshire Department of Education",
            title = "Higher Education in New Hampshire",
            url = "https://www.education.nh.gov/pathways-education/higher-education-new-hampshire",
          ),
          // 403.
          urlStatus = SourceUrlStatus.UNKNOWN,
        ),
    ).let(::keyedByJurisdiction)

  /**
   * The entries keyed by the jurisdiction each one belongs to, REFUSING a
   * duplicate.
   *
   * `associate` keeps the LAST pair for a repeated key and says nothing, so a
   * fifth entry re-keying `"AR"` would silently replace Arkansas's page with it
   * and send a family to another state's agency. The permitted shape is exactly
   * one entry per jurisdiction, so it is asserted rather than assumed -- the
   * lesson `CdsRead.singleByKey` already wrote down for a read.
   *
   * A FUNCTION AND NOT AN `init` BLOCK, so the refusal is reachable by a test
   * with two entries of its own instead of only by shipping a broken corpus.
   */
  internal fun keyedByJurisdiction(entries: List<Pair<String, UnreadableAgency>>): Map<DeadlineJurisdiction.State, UnreadableAgency> {
    val keyed =
      entries.map { (code, agency) ->
        // The code goes through the SAME membership rule every served
        // jurisdiction does, so a typo here is a boot-time failure naming the
        // code rather than a jurisdiction no door can key.
        val jurisdiction =
          DeadlineJurisdiction.State.of(code)
            ?: error("[$code] is not a published USPS code, so no jurisdiction can carry its unreadable agency")
        jurisdiction to agency
      }
    val byJurisdiction = keyed.toMap()
    require(byJurisdiction.size == keyed.size) {
      "two unreadable-agency entries claim one jurisdiction: [${keyed.map { it.first.uspsCode }.sorted()}]"
    }
    return byJurisdiction
  }

  /** The agency we could not read for [jurisdiction], or null where we did read one. */
  fun of(jurisdiction: DeadlineJurisdiction.State): UnreadableAgency? = BY_JURISDICTION[jurisdiction]
}
