package ed.unicoach.coaching.testing

import ed.unicoach.db.dao.AdmissionTestStateProgrammeDao
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.nonMoneyAnswerStatus
import ed.unicoach.db.models.AdmissionTestStateProgramme
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.StudentId
import java.time.LocalDate

/**
 * What this student's OWN state does about the school-day SAT or ACT (RFC 208
 * section 5) -- one value, and every sentence the coach reads about it is a
 * function of this value.
 *
 * The four arms are four genuinely different facts and a family hears four
 * different sentences. Only one of them says anything about a state's
 * programme: [NotHeld] and [StateUnknown] are statements about OUR data and
 * about what we were told, and [Expired] is a statement about a READING of a
 * state page. None of them is ever spoken as "the state runs nothing" -- that
 * claim needs a state page saying so, which these pages mostly do not (RFC 208
 * section 2), and a wrong one tells a family to pay for a Saturday they did not
 * need.
 */
sealed interface SchoolDayProgramme {
  /** We hold a current reading for this student's state, and it is this row. */
  data class Held(
    val row: AdmissionTestStateProgramme,
  ) : SchoolDayProgramme

  /**
   * We hold a reading for this state and no longer believe it.
   *
   * PER STATE AND NEVER PER CORPUS: one lapsed state must not withhold every
   * other state's current record, which is why this arm names the state it is
   * about and the day its reading lapsed rather than being a property of the
   * read as a whole.
   */
  data class Expired(
    val state: String,
    val expiresOn: LocalDate,
  ) : SchoolDayProgramme

  /**
   * We hold no row for this state at all -- a gap in OUR data.
   *
   * It is NOT the state running nothing, and nothing built from this arm may
   * say so.
   */
  data class NotHeld(
    val state: String,
  ) : SchoolDayProgramme

  /** We do not know which state this student lives in, and [reason] is which of the three ways that happens. */
  data class StateUnknown(
    val reason: ResidencyGap,
  ) : SchoolDayProgramme
}

/**
 * WHY we do not know the student's state (brief 0010 D10).
 *
 * Three arms and not one boolean, because the coach owes a different sentence
 * to a family that declined the question than to one that was never asked it --
 * and in none of the three does the coach ask for the state HERE: the residency
 * invitation belongs to the cost conversation, and nothing in this answer waits
 * on it.
 */
enum class ResidencyGap {
  /** The student has no money profile at all: they have never touched a money topic. */
  NO_PROFILE,

  /** There is a profile and the residency question has not been put to them. */
  UNANSWERED,

  /** They were asked and chose not to say. It is never asked again. */
  DECLINED,
}

/**
 * The state-programme half of the admission-test timeline read (RFC 208 section
 * 5): the student's stored residency state, and the newest row we hold for it.
 *
 * TWO STATEMENTS, whatever the size of the corpus -- the active money profile
 * and [AdmissionTestStateProgrammeDao.listNewestPerState], which is one
 * `DISTINCT ON` over the whole table. They are counted into
 * [TestTimelineService.READ_STATEMENTS], which is asserted.
 *
 * IT ASKS THE STUDENT FOR NOTHING. The state is INFERRED from
 * `money_profiles.residency_state`, which the cost conversation already
 * collects; where it is absent this read says which kind of absence it is and
 * the coach carries on (D10).
 *
 * TODAY IS A PARAMETER AND NOT A CLOCK. The service owns the coach's zone
 * ([ed.unicoach.coaching.coachToday]), so handing this reader an
 * `InstantSource` would give it a second place to decide what day it is --
 * which is how RFC 186 shipped a test that passed for the wrong reason.
 */
object SchoolDayTestingReader {
  /**
   * The programme for [studentId], as of [today].
   *
   * Expiry is decided HERE and per state, because the value the renderer
   * receives must already be the answer: a renderer that received a row plus an
   * expiry date would be one `if` away from speaking a reading we no longer
   * believe.
   */
  fun read(
    session: SqlSession,
    studentId: StudentId,
    today: LocalDate,
  ): SchoolDayProgramme {
    // A student who has never touched a money topic has no row, and an absent
    // row is not a failed read -- `CoachingService.activeMoneyProfile`'s rule,
    // copied deliberately: any OTHER failure is a database fault and must fail
    // the turn rather than silently become "we do not know your state".
    val profile =
      MoneyProfilesDao
        .findActiveByStudent(session, studentId)
        .getOrElse { e -> if (e is NotFoundException) null else throw e }
        ?: return SchoolDayProgramme.StateUnknown(ResidencyGap.NO_PROFILE)

    val state =
      when (profile.residencyStatus) {
        AnswerStatus.UNANSWERED -> {
          return SchoolDayProgramme.StateUnknown(ResidencyGap.UNANSWERED)
        }

        AnswerStatus.DECLINED -> {
          return SchoolDayProgramme.StateUnknown(ResidencyGap.DECLINED)
        }

        // The schema's value-iff-answered CHECK guarantees the value is present
        // here, so a null is row corruption and is refused, LOCATED. Reading it
        // as "unanswered" would be this read inventing an answer status the
        // store does not hold -- and it is the tri-state the whole D10 sentence
        // turns on.
        AnswerStatus.ANSWERED -> {
          checkNotNull(profile.residencyState) {
            "money_profiles row for student [${studentId.value}] is answered for residency and carries no state"
          }
        }

        // `undecided` is the INTENDED-SUBJECT answer (RFC 205), and the
        // `answer_status` domain this column names is
        // `answer_status_or_undecided` MINUS that value (RFC 209) -- so the
        // database cannot hold it here and reading it is row corruption.
        // Refused in the words [ed.unicoach.db.dao.nonMoneyAnswerStatus] states
        // once for every money read, naming the cell. It is NOT a residency
        // gap: `StateUnknown` says a family has not told us their state, and
        // saying that over a corrupt cell would report a gap the family does
        // not have and invite the coach to ask again.
        AnswerStatus.UNDECIDED -> {
          throw nonMoneyAnswerStatus(AnswerStatus.UNDECIDED, "money_profiles.residency_status")
        }
      }

    val row =
      AdmissionTestStateProgrammeDao
        .listNewestPerState(session)
        .getOrThrow()
        .find { it.state == state }
        ?: return SchoolDayProgramme.NotHeld(state)

    // AFTER the day, not on it: `expires_on` is the last day the reading is
    // believed, which is the same boundary the calendar's own expiry uses.
    return if (today.isAfter(row.expiresOn)) {
      SchoolDayProgramme.Expired(state, row.expiresOn)
    } else {
      SchoolDayProgramme.Held(row)
    }
  }
}
