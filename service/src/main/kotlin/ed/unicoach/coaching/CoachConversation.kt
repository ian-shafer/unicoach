package ed.unicoach.coaching

import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatRole
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.usDate
import ed.unicoach.error.FieldError
import java.time.LocalDate

// What every coach's turn shares OUTSIDE the provider loop (RFC 258): how a
// message is validated, how a new conversation is named, how the pinned prompt
// is resolved, how replay history is read, and the date block. They moved out of `CoachingService` verbatim when
// the supporter's coach arrived, so the two coaches cannot drift on any of them.

/** The message refusal every coach answers, or null for a usable message: blank, or over the length cap. */
internal fun coachMessageError(message: String): FieldError? {
  val trimmed = message.trim()
  return when {
    trimmed.isBlank() -> {
      FieldError(CoachingService.MESSAGE_FIELD, "Message must not be blank")
    }

    message.length > CoachingService.MESSAGE_MAX_LENGTH -> {
      FieldError(CoachingService.MESSAGE_FIELD, "Message exceeds ${CoachingService.MESSAGE_MAX_LENGTH} characters")
    }

    else -> {
      null
    }
  }
}

/**
 * Derives a name from the first message: collapse whitespace, truncate to
 * NAME_DERIVATION_MAX, trim. Callers pass a message [coachMessageError] has
 * already accepted, which is non-blank, and NAME_DERIVATION_MAX is under
 * [ConvoName]'s own bound — so an invalid derived name is a broken caller
 * contract, and fails loudly with the rule it broke rather than being renamed.
 */
internal fun convoNameFrom(message: String): ConvoName {
  val collapsed = message.trim().replace(Regex("\\s+"), " ")
  val truncated = collapsed.take(CoachingService.NAME_DERIVATION_MAX).trim()
  return when (val result = ConvoName.create(truncated)) {
    is ValidationResult.Valid -> result.value
    is ValidationResult.Invalid -> error("derived convo name [$truncated] is invalid: ${result.error}")
  }
}

/**
 * The pinned `system_prompts` row a turn runs under. Each coach passes its own
 * configured [name] and [version]; a missing pin is a deployment defect, not a
 * caller's error, so it throws.
 */
internal fun pinnedSystemPrompt(
  session: SqlSession,
  name: String,
  version: String,
): SystemPrompt =
  SystemPromptsDao.findByNameAndVersion(session, name, version).getOrElse {
    throw IllegalStateException("system prompt not found for name=[$name] version=[$version]", it)
  }

/**
 * Visible exchanges as ordered chat messages: USER then ASSISTANT per
 * exchange, text-only. Cross-turn replay stays text-only (thinking and tool
 * plumbing are not replayed); a tool excursion collapses to its user text and
 * final answer via [ConvoProjection.visibleExchanges]. Both content sides come
 * from the joined generic call log (RFC 106): the user input from
 * [VisibleExchange.userContent], the coach answer from [VisibleExchange.finalContent].
 */
internal fun visibleHistory(
  session: SqlSession,
  convoId: ConvoId,
): List<ChatMessage> {
  val exchanges = ConvoProjection.visibleExchanges(ConvosDao.listTurns(session, convoId).getOrThrow())
  return buildList {
    for (exchange in exchanges) {
      add(ChatMessage.text(ChatRole.USER, ConvoContent.renderText(exchange.userContent)))
      add(ChatMessage.text(ChatRole.ASSISTANT, ConvoContent.renderText(exchange.finalContent)))
    }
  }
}

/**
 * The date block every system text carries (RFC 186): today, the student's
 * expected high-school graduation date at the precision it was recorded, and
 * the year of school those two work out to.
 *
 * Two lines with no trailing terminator, like every other block here -- the
 * assembler owns the blank line that separates them.
 *
 * Both dates are written in the ONE shape a human reads (RFC 227, brief 0010
 * D21). Today used to be ISO here, deliberately, so the model had one
 * unambiguous form to repeat. RFC 227 changes it: the prompt now TELLS the
 * coach to say "Sep 12, 2026", and a block that states a rule and then
 * demonstrates the opposite two lines above is the self-contradicting copy
 * RFC 187's review caught. One shape in the prompt prose; the tool payloads
 * keep ISO on the wire, and the prompt says so.
 */
internal fun renderDateBlock(
  today: LocalDate,
  graduation: PartialDate,
): String =
  "Today's date is ${UsDate.of(today)} ($COACH_ZONE_LABEL — the timezone College Board and ACT publish and " +
    "enforce their deadlines in).\n" +
    "The student expects to finish high school ${renderGraduationPhrase(graduation)}; ${HighSchoolYear.of(today, graduation).clause}"

/**
 * The stored graduation date in words, at its STORED precision, saying so
 * when it is short (RFC 186): a month we never recorded is a month the coach
 * must never state, and the surest way to stop it inventing one is to tell it
 * the month is not there.
 *
 * RFC 186 wrote this day before month ("on 15 June 2028") to avoid a "06/15"
 * the model could read either way. RFC 227 (brief 0010 D21) replaces it with
 * "on Jun 15, 2028": a named month is just as unambiguous as a leading day,
 * and it is the shape a US family writes. The SHAPE now comes from the one
 * seam, [PartialDate.usDate]; the WORDS below stay here, because "the day is
 * not recorded" is a claim about OUR gap and the seam renders dates, not
 * claims.
 */
private fun renderGraduationPhrase(graduation: PartialDate): String =
  // On the SEALED type, not on its nullable month/day projections: the
  // precision cases and the type's variants are the same three cases, so the
  // compiler is what refuses a fourth precision that silently rendered as
  // "only the year is recorded".
  when (graduation) {
    is PartialDate.FullDate -> {
      "on ${graduation.usDate()}"
    }

    is PartialDate.YearAndMonth -> {
      "in ${graduation.usDate()} (the day is not recorded)"
    }

    is PartialDate.YearOnly -> {
      "in ${graduation.usDate()} (only the year is recorded — no month, no day)"
    }
  }
