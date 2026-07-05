package ed.unicoach.coaching.synthesis

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.TokenUsage
import ed.unicoach.chat.chat
import ed.unicoach.coaching.ConvoContent
import ed.unicoach.common.util.truncateForLog
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdvisoryLockDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SynthesisRunsDao
import ed.unicoach.db.dao.SystemPromptsDao
import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SystemPrompt
import ed.unicoach.db.models.latestUpdatedAt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The per-student synthesis pass (RFC 93): reflects over a student's accumulated
 * model — distilled active claims, their college list, and calendar context — and
 * writes coach-owned commitments. Runs only in the queue worker; never on the
 * chat request path (RFC 43).
 *
 * The pass is three phases — a read transaction, the LLM call **outside any
 * transaction**, then a write transaction — mirroring [ExtractionService][ed.unicoach.coaching.extraction.ExtractionService]:
 * the multi-second provider call never pins a pooled connection or holds the
 * student advisory lock. Each transaction takes the student lock; it is released
 * between phases and re-acquired for the write. The student's synthesis freshness
 * marker (latest applied `synthesis_runs.created_at`) makes at-least-once
 * delivery cheap and no-ops a lost race.
 *
 * A plain `class` (not `open`): tests inject a fake [ChatProvider] rather than
 * override a method. [clock] is the sole date input the pass needs (the timing
 * lens reasons over "today"); the freshness gate and every lifecycle timestamp
 * are DB-clocked through `NOW()` defaults and `lastAppliedAt`.
 */
class SynthesisService(
  private val database: Database,
  private val chatProvider: ChatProvider,
  private val config: SynthesisConfig,
  private val clock: Clock = Clock.systemUTC(),
) {
  private val logger = LoggerFactory.getLogger(SynthesisService::class.java)

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Runs one synthesis pass over [studentId]. Returns a sealed [SynthesisResult]
   * the handler maps to a `JobResult`.
   */
  suspend fun synthesize(studentId: StudentId): SynthesisResult {
    val readout =
      try {
        readPhase(studentId)
      } catch (e: Exception) {
        logger.warn("synthesis read phase failed for student=[{}]", studentId.asString, e)
        return SynthesisResult.TransientFailure("read phase: ${e.message}", e)
      }

    return when (readout) {
      is ReadPhase.NoOp -> SynthesisResult.Success
      is ReadPhase.Ready -> runLlmAndWrite(studentId, readout)
    }
  }

  // ---------------------------------------------------------------------------
  // Read phase
  // ---------------------------------------------------------------------------

  private suspend fun readPhase(studentId: StudentId): ReadPhase =
    database.withConnection { session ->
      val studentResult = StudentsDao.findById(session, studentId, SoftDeleteScope.ALL)
      // A genuinely nonexistent student is a fast no-op success, not a retriable
      // failure: retrying the pass cannot conjure the row, so all maxAttempts would
      // be wasted. Same terminal treatment as the soft-deleted case below.
      if (studentResult.exceptionOrNull() is NotFoundException) return@withConnection ReadPhase.NoOp
      val student = studentResult.getOrThrow()
      // Soft-deleted student: skip as a no-op success (no run appended).
      if (student.deletedAt != null) return@withConnection ReadPhase.NoOp

      AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

      val lastAppliedAt = SynthesisRunsDao.lastAppliedAt(session, studentId).getOrThrow()

      val activeClaims = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
      // Nothing distilled to reflect on.
      if (activeClaims.isEmpty()) return@withConnection ReadPhase.NoOp

      val listEntries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()

      // Freshness gate (read-phase-only): the newest model mutation across active
      // claims and list entries. If nothing changed since the last applied pass,
      // no-op before any LLM call.
      val freshness = (activeClaims + listEntries).latestUpdatedAt()
      if (lastAppliedAt != null && freshness != null && !freshness.isAfter(lastAppliedAt)) {
        return@withConnection ReadPhase.NoOp
      }

      val openCommitments = CommitmentsDao.listOpenByStudent(session, studentId).getOrThrow()
      // Open set saturated: do not pile up.
      if (openCommitments.size >= config.maxOpenCommitments) return@withConnection ReadPhase.NoOp

      val prompt =
        SystemPromptsDao
          .findByNameAndVersion(session, config.promptName, config.promptVersion)
          .getOrThrow()

      ReadPhase.Ready(
        student = student,
        lastAppliedAt = lastAppliedAt,
        activeClaims = activeClaims,
        listEntries = listEntries,
        openCommitments = openCommitments,
        prompt = prompt,
      )
    }

  // ---------------------------------------------------------------------------
  // LLM call (no transaction) + write phase
  // ---------------------------------------------------------------------------

  private suspend fun runLlmAndWrite(
    studentId: StudentId,
    ready: ReadPhase.Ready,
  ): SynthesisResult {
    val request =
      ChatRequest(
        model = config.model,
        system = ready.prompt.body,
        messages = buildPromptMessages(ready),
        maxTokens = config.maxTokens,
      )

    val terminal =
      try {
        chatProvider.chat(request)
      } catch (e: Exception) {
        logger.warn("synthesis provider call failed for student=[{}]", studentId.asString, e)
        return SynthesisResult.TransientFailure("provider call: ${e.message}", e)
      }

    return when (terminal) {
      // No billed, usable call: nothing to account, no run row.
      is ChatEvent.Rejected -> {
        SynthesisResult.TransientFailure("provider rejected: ${terminal.reason}")
      }

      is ChatEvent.TransientFailure -> {
        SynthesisResult.TransientFailure("provider transient: ${terminal.reason}")
      }

      is ChatEvent.Completed -> {
        handleCompleted(studentId, ready, terminal)
      }
    }
  }

  /**
   * Handles a billed `Completed` terminal: parses the output and either applies it
   * (write phase) or, on any parse failure, records the billed spend as a `failed`
   * run so the token ledger is never dropped.
   */
  private suspend fun handleCompleted(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    terminal: ChatEvent.Completed,
  ): SynthesisResult {
    val usage = terminal.response.usage
    val modelResolved = terminal.response.modelResolved
    // A Completed call is billed regardless of what the JSON contains.
    val raw = ConvoContent.renderText(terminal.response.content)
    return when (val parsed = parseOutput(raw)) {
      is ParseResult.Failure -> {
        logger.warn(
          "unparseable synthesis output for student=[{}]: [{}]; raw=[{}]",
          studentId.asString,
          parsed.failure,
          truncateForLog(raw),
        )
        writeFailedRun(studentId, ready, usage, modelResolved)
        SynthesisResult.TransientFailure("unparseable synthesis output: ${parsed.failure}")
      }

      is ParseResult.Parsed -> {
        writePhase(studentId, ready, parsed.output, usage, modelResolved)
      }
    }
  }

  /**
   * Writes a `failed` synthesis_run carrying the call's token usage (the spend is
   * recorded); the freshness marker does not advance. Re-checks the freshness
   * marker under the held write lock so a concurrent same-student pass that
   * already applied does not draw a duplicate `failed` row.
   */
  private suspend fun writeFailedRun(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    usage: TokenUsage,
    modelResolved: String?,
  ) {
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()
        if (lostRace(session, studentId, ready.lastAppliedAt)) return@withConnection
        appendRun(session, studentId, ready.prompt.id, SynthesisOutcome.FAILED, 0, 0, usage, modelResolved)
      }
    } catch (e: Exception) {
      // A DB failure here must not overwrite the "unparseable synthesis output"
      // diagnostic the caller is about to surface with a generic worker retry.
      logger.warn("synthesis failed-run write failed for student=[{}]", studentId.asString, e)
    }
  }

  private suspend fun writePhase(
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ParsedOutput,
    usage: TokenUsage,
    modelResolved: String?,
  ): SynthesisResult =
    try {
      database.withConnection { session ->
        AdvisoryLockDao.lockStudent(session, studentId).getOrThrow()

        // Lost-race no-op: an applied run appeared since the read-phase snapshot.
        if (lostRace(session, studentId, ready.lastAppliedAt)) {
          return@withConnection SynthesisResult.Success
        }

        // Re-load the active claim set under the held lock; supports and stale-drop
        // key on this fresh set.
        val freshActive = ClaimsDao.listActiveByStudent(session, studentId).getOrThrow()
        val activeIds = freshActive.map { it.id }.toSet()

        applyWrites(session, studentId, ready, parsed, activeIds, usage, modelResolved)
      }
    } catch (e: Exception) {
      logger.warn("synthesis write phase failed for student=[{}]", studentId.asString, e)
      SynthesisResult.TransientFailure("write phase: ${e.message}", e)
    }

  /**
   * Inserts up to `maxNewCommitmentsPerRun` proposed commitments (each with its
   * still-active support links), stale-drops open commitments whose entire
   * non-empty support set is inactive, and appends the `applied` run advancing
   * the freshness marker.
   */
  private fun applyWrites(
    session: SqlSession,
    studentId: StudentId,
    ready: ReadPhase.Ready,
    parsed: ParsedOutput,
    activeIds: Set<ClaimId>,
    usage: TokenUsage,
    modelResolved: String?,
  ): SynthesisResult {
    val commitmentsWritten = createProposedCommitments(session, studentId, parsed, activeIds)
    val commitmentsDropped = staleDropOpenCommitments(session, ready.openCommitments, activeIds)
    appendRun(session, studentId, ready.prompt.id, SynthesisOutcome.APPLIED, commitmentsWritten, commitmentsDropped, usage, modelResolved)
    return SynthesisResult.Success
  }

  /**
   * Creates up to `maxNewCommitmentsPerRun` proposed commitments, linking each to
   * its support claims filtered to the fresh active set. Returns the count created.
   */
  private fun createProposedCommitments(
    session: SqlSession,
    studentId: StudentId,
    parsed: ParsedOutput,
    activeIds: Set<ClaimId>,
  ): Int {
    var commitmentsWritten = 0
    for (spec in parsed.commitments.take(config.maxNewCommitmentsPerRun)) {
      val created =
        CommitmentsDao
          .create(
            session,
            NewCommitment(
              studentId = studentId,
              lens = spec.lens,
              disclosure = spec.disclosure,
              statement = spec.statement,
              triggerAt = spec.triggerAt,
            ),
          ).getOrThrow()
      for (claimId in spec.supports) {
        if (claimId in activeIds) {
          CommitmentSupportDao.link(session, created.id, claimId).getOrThrow()
        }
      }
      commitmentsWritten++
    }
    return commitmentsWritten
  }

  /**
   * Stale-drops each open commitment whose support set is non-empty and wholly
   * inactive (it has lost its basis); a commitment with no support is never
   * dropped. Returns the count dropped.
   */
  private fun staleDropOpenCommitments(
    session: SqlSession,
    openCommitments: List<Commitment>,
    activeIds: Set<ClaimId>,
  ): Int {
    var commitmentsDropped = 0
    for (open in openCommitments) {
      val support = CommitmentSupportDao.listClaimsForCommitment(session, open.id).getOrThrow()
      if (support.isNotEmpty() && support.none { it.id in activeIds }) {
        CommitmentsDao.drop(session, open.id, "stale_basis").getOrThrow()
        commitmentsDropped++
      }
    }
    return commitmentsDropped
  }

  /** Whether an applied run has appeared since the read-phase [snapshot] (a concurrent pass won). */
  private fun lostRace(
    session: SqlSession,
    studentId: StudentId,
    snapshot: Instant?,
  ): Boolean {
    val current = SynthesisRunsDao.lastAppliedAt(session, studentId).getOrThrow()
    return when {
      current == null -> false
      snapshot == null -> true
      else -> current.isAfter(snapshot)
    }
  }

  private fun appendRun(
    session: SqlSession,
    studentId: StudentId,
    systemPromptId: ed.unicoach.db.models.SystemPromptId,
    outcome: SynthesisOutcome,
    commitmentsWritten: Int,
    commitmentsDropped: Int,
    usage: TokenUsage,
    modelResolved: String?,
  ) {
    SynthesisRunsDao
      .append(
        session,
        NewSynthesisRun(
          studentId = studentId,
          outcome = outcome,
          systemPromptId = systemPromptId,
          provider = chatProvider.id,
          modelResolved = modelResolved,
          commitmentsWritten = commitmentsWritten,
          commitmentsDropped = commitmentsDropped,
          inputTokens = usage.inputTokens,
          outputTokens = usage.outputTokens,
          cacheReadTokens = usage.cacheReadTokens,
          cacheWriteTokens = usage.cacheWriteTokens,
        ),
      ).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // Prompt assembly
  // ---------------------------------------------------------------------------

  private fun buildPromptMessages(ready: ReadPhase.Ready): List<ChatMessage> {
    val today = Instant.now(clock)
    val context =
      buildString {
        appendLine("# Today")
        appendLine(today.toString())
        appendLine()
        appendLine("# Student")
        appendLine("expectedHighSchoolGraduation=${ready.student.expectedHighSchoolGraduationDate.toIso()}")
        appendLine()
        appendLine("# Active claims")
        if (ready.activeClaims.isEmpty()) {
          appendLine("(none)")
        } else {
          for (claim in ready.activeClaims.take(config.maxClaims)) {
            appendLine(
              "- id=${claim.id.asString} kind=${claim.kind.value} topic=${claim.topic.value} confidence=${claim.confidence}: ${claim.statement}",
            )
          }
        }
        appendLine()
        appendLine("# College list")
        if (ready.listEntries.isEmpty()) {
          appendLine("(none)")
        } else {
          for (entry in ready.listEntries) {
            appendLine(
              "- collegeId=${entry.collegeId.asString} status=${entry.status.value} updatedAt=${entry.updatedAt}",
            )
          }
        }
        appendLine()
        appendLine("# Open commitments (do not restate)")
        if (ready.openCommitments.isEmpty()) {
          appendLine("(none)")
        } else {
          for (commitment in ready.openCommitments) {
            appendLine("- ${commitment.statement}")
          }
        }
      }
    return listOf(ChatMessage.text(ChatRole.USER, context))
  }

  // ---------------------------------------------------------------------------
  // Output parsing
  // ---------------------------------------------------------------------------

  /**
   * Parses the strict-JSON commitments document via the JSON element DSL (the
   * `service` module has no kotlinx-serialization compiler plugin). Returns a
   * [ParseResult.Failure] naming the offending field on any structural, type, or
   * enum-membership failure.
   */
  private fun parseOutput(raw: String): ParseResult {
    val root =
      try {
        json.parseToJsonElement(raw.trim()) as? JsonObject
          ?: return ParseResult.Failure(ParseFailure.NotAnObject)
      } catch (e: Exception) {
        return ParseResult.Failure(ParseFailure.MalformedJson(e.message))
      }

    val commitments = mutableListOf<CommitmentSpec>()
    // Distinguish "key absent" (a legitimately empty proposal set → empty array)
    // from "key present but not an array" (a structurally malformed output). The
    // latter is a BadField failure so it routes to writeFailedRun as a FAILED run,
    // never a silent zero-commitments APPLIED run. Keeps parseOutput total.
    val commitmentsElement = root["commitments"]
    val commitmentsArray =
      when (commitmentsElement) {
        null -> JsonArray(emptyList())
        is JsonArray -> commitmentsElement
        else -> return ParseResult.Failure(ParseFailure.BadField("commitments", "not an array"))
      }
    for (element in commitmentsArray) {
      val obj = element as? JsonObject ?: return ParseResult.Failure(ParseFailure.BadField("commitments[]", "not an object"))
      // Read each field via a safe cast to JsonPrimitive: a JsonObject/JsonArray
      // where a scalar is expected returns null (a BadField), never throws (`.jsonPrimitive`
      // would). Keeps parseOutput total so a malformed Completed reaches writeFailedRun.
      val lensRaw = (obj["lens"] as? JsonPrimitive)?.contentOrNull
      val lens =
        lensRaw?.let { CommitmentLens.fromValue(it) }
          ?: return ParseResult.Failure(ParseFailure.BadField("lens", lensRaw ?: "missing"))
      val disclosureRaw = (obj["disclosure"] as? JsonPrimitive)?.contentOrNull
      val disclosure =
        disclosureRaw?.let { CommitmentDisclosure.fromValue(it) }
          ?: return ParseResult.Failure(ParseFailure.BadField("disclosure", disclosureRaw ?: "missing"))
      val statement =
        (obj["statement"] as? JsonPrimitive)?.takeIf { it.isString }?.content
          ?: return ParseResult.Failure(ParseFailure.BadField("statement", "missing or non-string"))
      if (statement.isBlank()) return ParseResult.Failure(ParseFailure.BadField("statement", "blank"))
      val triggerAt =
        when (val rawTrigger = (obj["triggerAt"] as? JsonPrimitive)?.contentOrNull) {
          null -> {
            null
          }

          else -> {
            parseTriggerAt(rawTrigger)
              ?: return ParseResult.Failure(ParseFailure.BadField("triggerAt", rawTrigger))
          }
        }
      val supports =
        obj["supports"]?.let { it as? JsonArray }?.let { arr ->
          arr.map { s ->
            val idRaw = (s as? JsonPrimitive)?.contentOrNull
            runCatching { ClaimId(java.util.UUID.fromString(idRaw)) }.getOrNull()
              ?: return ParseResult.Failure(ParseFailure.BadField("supports[]", idRaw ?: "null"))
          }
        } ?: emptyList()
      commitments.add(CommitmentSpec(lens, disclosure, statement, triggerAt, supports))
    }

    return ParseResult.Parsed(ParsedOutput(commitments))
  }

  /** Accepts an ISO-8601 instant, or a bare ISO date (interpreted as start-of-day UTC). */
  private fun parseTriggerAt(raw: String): Instant? =
    runCatching { Instant.parse(raw) }.getOrNull()
      ?: runCatching {
        java.time.LocalDate
          .parse(raw)
          .atStartOfDay(java.time.ZoneOffset.UTC)
          .toInstant()
      }.getOrNull()

  // ---------------------------------------------------------------------------
  // Internal carriers
  // ---------------------------------------------------------------------------

  private sealed interface ReadPhase {
    data object NoOp : ReadPhase

    data class Ready(
      val student: Student,
      val lastAppliedAt: Instant?,
      val activeClaims: List<Claim>,
      val listEntries: List<CollegeListEntry>,
      val openCommitments: List<Commitment>,
      val prompt: SystemPrompt,
    ) : ReadPhase
  }

  private data class ParsedOutput(
    val commitments: List<CommitmentSpec>,
  )

  private data class CommitmentSpec(
    val lens: CommitmentLens,
    val disclosure: CommitmentDisclosure,
    val statement: String,
    val triggerAt: Instant?,
    val supports: List<ClaimId>,
  )

  private sealed interface ParseResult {
    data class Parsed(
      val output: ParsedOutput,
    ) : ParseResult

    data class Failure(
      val failure: ParseFailure,
    ) : ParseResult
  }

  private sealed interface ParseFailure {
    data object NotAnObject : ParseFailure {
      override fun toString(): String = "root is not a JSON object"
    }

    data class MalformedJson(
      val detail: String?,
    ) : ParseFailure {
      override fun toString(): String = "malformed JSON: [$detail]"
    }

    data class BadField(
      val field: String,
      val value: String,
    ) : ParseFailure {
      override fun toString(): String = "field [$field]=[$value]"
    }
  }
}
