package ed.unicoach.web

import com.google.protobuf.Message
import ed.unicoach.auth.MetadataWrite
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.db.session.FormDrafts
import ed.unicoach.db.session.SessionMetadata
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.recordSessionWrite
import io.ktor.server.application.ApplicationCall
import org.slf4j.LoggerFactory

// The draft rail (RFC 265, brief 0013 D4/D12): wherever a session exists, a
// signed-in form keeps what the family typed in it, so a validation error or a
// page navigation never costs them their typing.
//
// A form saves its draft when a POST did not succeed and clears it when one
// did — never both, so a POST costs at most one session UPDATE. A failed POST
// still renders straight back with the posted values; the draft is what lets
// those values survive the family leaving and coming back.

/**
 * One signed-in form's place in [FormDrafts]: the one mapping from a form to its
 * field. Each form declares its slot beside its page code, so a new form is a
 * new field in `session_metadata.proto` plus one of these.
 */
class FormSlot<D : Message>(
  /** The form's draft, or `null` when the field is unset. */
  val read: (FormDrafts) -> D?,
  val write: (FormDrafts.Builder, D) -> Unit,
  val clear: (FormDrafts.Builder) -> Unit,
)

/**
 * The draft [slot]'s form left in this caller's session, or `null`. A missing
 * draft is an ordinary form, never an error: guided, not gated.
 */
fun <D : Message> WebCaller.draftFor(slot: FormSlot<D>): D? = slot.read(session.metadata.drafts)

/** Why a failed POST's typing was not kept: the notice the page shows over it. */
enum class DraftNotKept {
  /** Past the session's metadata cap: the family can shorten it and try again. */
  TOO_LONG,

  /** Anything else — text the column cannot store, or a fault writing it. */
  NOT_SAVED,
}

/** What became of a [saveDraft], as the page that follows needs it. */
sealed interface DraftSave {
  data object Kept : DraftSave

  data class NotKept(
    val why: DraftNotKept,
  ) : DraftSave

  /** The session was revoked or deleted since this request resolved it. */
  data object SessionGone : DraftSave
}

/**
 * Keeps [draft] as [slot]'s form's draft, after a POST that did not succeed.
 *
 * BEST EFFORT. The POST's own answer — the refused form, rendered back with
 * everything the family typed — is what they came for, and the draft is a
 * side effect that lets that typing outlive the page. So no outcome of the
 * write, a fault included, replaces that answer: a fault is logged here and
 * comes back as [DraftSave.NotKept], and the page says the typing will not
 * survive leaving it. Only [DraftSave.SessionGone] changes the page, to the
 * sign-in form, as for any request on a revoked session.
 */
suspend fun <D : Message> ApplicationCall.saveDraft(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  slot: FormSlot<D>,
  draft: D,
): DraftSave {
  val outcome =
    writeDrafts(sessionMetadata, caller) { drafts -> slot.write(drafts, draft) }.getOrElse { cause ->
      draftLog.warn("draft for session [{}] not saved; the refused form rendered with the not-kept notice", caller.session.id.value, cause)
      return DraftSave.NotKept(DraftNotKept.NOT_SAVED)
    }
  return when (outcome) {
    is MetadataWrite.Written -> DraftSave.Kept
    MetadataWrite.TooLarge -> DraftSave.NotKept(DraftNotKept.TOO_LONG)
    MetadataWrite.Unstorable -> DraftSave.NotKept(DraftNotKept.NOT_SAVED)
    MetadataWrite.SessionGone -> DraftSave.SessionGone
  }
}

/**
 * Drops [slot]'s form's draft, after its POST succeeded. Writes nothing when
 * there is no draft to drop.
 *
 * BEST EFFORT, and returns nothing, because the change the POST made has
 * already committed and no outcome here may report it as anything else. A
 * session gone since the request resolved took its draft with it. A fault, or
 * a refusal that removing a field cannot cause, is logged and left: the cost
 * is a stale draft prefilled on the form's next GET, which the family can see
 * and correct, where a fault page would tell them a change that happened did
 * not.
 */
suspend fun <D : Message> ApplicationCall.clearDraft(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  slot: FormSlot<D>,
) {
  if (caller.draftFor(slot) == null) return
  val outcome =
    writeDrafts(sessionMetadata, caller) { drafts -> slot.clear(drafts) }.getOrElse { cause ->
      draftLog.warn(
        "draft for session [{}] not cleared after its POST succeeded; it stays until the next save",
        caller.session.id.value,
        cause,
      )
      return
    }
  when (outcome) {
    is MetadataWrite.Written, MetadataWrite.SessionGone -> {
      Unit
    }

    MetadataWrite.TooLarge, MetadataWrite.Unstorable -> {
      draftLog.error(
        "clearing a draft for session [{}] was refused as [{}]; removing a field cannot grow the metadata or add text to it",
        caller.session.id.value,
        outcome,
      )
    }
  }
}

/**
 * Applies [edit] to the stored drafts through [SessionMetadataService.update],
 * and makes a written row this request's caller.
 *
 * An edit that leaves no draft at all clears the `drafts` message too, so the
 * stored JSON says "no drafts" by omitting the field rather than by `{}`.
 */
private suspend fun ApplicationCall.writeDrafts(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  edit: (FormDrafts.Builder) -> Unit,
): Result<MetadataWrite> =
  sessionMetadata
    .update(caller.session) { stored -> stored.withDrafts(edit) }
    .onSuccess { outcome -> if (outcome is MetadataWrite.Written) recordSessionWrite(caller, outcome.session) }

private fun SessionMetadata.withDrafts(edit: (FormDrafts.Builder) -> Unit): SessionMetadata {
  val drafts = drafts.toBuilder().also(edit).build()
  val builder = toBuilder()
  return if (drafts == FormDrafts.getDefaultInstance()) builder.clearDrafts().build() else builder.setDrafts(drafts).build()
}

/** The draft rail's log: a draft write that failed is otherwise invisible, by design. */
private val draftLog = LoggerFactory.getLogger("ed.unicoach.web.FormDrafts")
