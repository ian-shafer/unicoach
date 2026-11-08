package ed.unicoach.db.models

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Surface id for a [CollegeTuitionExchange] row. */
@JvmInline
value class CollegeTuitionExchangeId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/** One school's terms for one regional tuition exchange (RFC 277), as a row is written. */
data class NewCollegeTuitionExchange(
  val collegeId: CollegeId,
  val exchange: TuitionExchange,
  val maxPercentOfResidentTuition: Int,
  val selection: ExchangeSelection,
  val separateApplication: SeparateApplication,
  val minimumUnweightedGpa: BigDecimal?,
  val percentOfEligibleOffered: Int?,
  val applyBy: LocalDate?,
  val sourceUrl: String,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)

/**
 * One school's terms for one regional tuition exchange (RFC 277), as read.
 *
 * [memberStates] is the exchange's own membership, joined on the read: who may
 * use it is a fact about the exchange and is stored once.
 */
data class CollegeTuitionExchange(
  val id: CollegeTuitionExchangeId,
  val collegeId: CollegeId,
  val exchange: TuitionExchange,
  val memberStates: Set<String>,
  val maxPercentOfResidentTuition: Int,
  val selection: ExchangeSelection,
  val separateApplication: SeparateApplication,
  val minimumUnweightedGpa: BigDecimal?,
  val percentOfEligibleOffered: Int?,
  val applyBy: LocalDate?,
  val sourceUrl: String,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)
