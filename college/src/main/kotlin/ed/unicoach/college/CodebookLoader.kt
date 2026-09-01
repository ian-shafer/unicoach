package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodeColumn
import ed.unicoach.db.dao.CodeColumns
import ed.unicoach.db.dao.CodebookTable
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewCodebookSource
import ed.unicoach.db.models.NewFootballConference
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewReligiousAffiliation
import ed.unicoach.db.models.NewUsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Loads the generated published codebook (`db/data/codebooks.json`, RFC 147)
 * into the ten reference tables plus `codebook_sources`.
 *
 * Like [CdsSeedLoader] this is a deliberately dumb typed reader: ALL
 * interpretation — the caret repair, the label parsing, the slug rule, the
 * sentinel policy — lives in `bin/fetch-codebooks`, where a human reviews the
 * generated diff (D38/D39). The loader's job is to refuse anything that means
 * the file and the schema disagree, and to say loudly what changed:
 *
 * - The file is verified layer by layer before any write, exactly as the
 *   curated aliases are: an unknown domain, a missing or unexpected key, a
 *   wrongly-typed value is fatal ([InvalidCodebookFileException]).
 * - A declared `null_sentinel` that is also present as a code is fatal
 *   ([SentinelAsCodeException], D41) — the generator asserts this too, and it is
 *   repeated here because this is where the file and the stored columns meet.
 * - A stored row the incoming file no longer carries is DELETED only if nothing
 *   refers to it; a referenced row is fatal
 *   ([ReferencedCodebookRowException]), never a silent orphaning of
 *   `college_ipeds.rel_affil = 71`.
 * - The digest each domain declares is compared against the digest the previous
 *   load stored, and any change is reported loudly (the drift guard — the whole
 *   reason `codebook_sources` exists).
 *
 * Everything runs in ONE transaction ([Database.withConnection]), so a fatal
 * rolls the whole codebook back rather than leaving nine domains loaded and one
 * refused.
 *
 * D46's unknown-code report is deliberately NOT part of [load]: it reads the
 * columns the ingest is still filling, so it is a separate, read-only
 * [reportUnknownCodes] the ingest calls after its row phases. Reporting from
 * inside the load would count the PREVIOUS run's `college_ipeds` — and, on a
 * fresh database, nothing at all.
 */
class CodebookLoader(
  private val database: Database,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  // ---------------------------------------------------------------------------
  // Failures — all fatal, all named
  // ---------------------------------------------------------------------------

  /**
   * The codebook file disagrees with this loader: a bad shape, an unknown
   * domain, a missing key, a wrongly-typed value, or a cross-check the file
   * fails against itself. The file is GENERATED repo data, so this is a
   * generator or review error and aborts the run before anything is written —
   * the [CollegeScorecardLoader.InvalidAliasFileException] contract.
   */
  class InvalidCodebookFileException(
    val fileName: String,
    val detail: String,
    val domain: String? = null,
    val entryIndex: Int? = null,
    val entry: String? = null,
    cause: Throwable? = null,
  ) : RuntimeException(
      "codebook file [$fileName]" +
        (domain?.let { " domain [$it]" } ?: "") +
        (entryIndex?.let { " entry [$it]" } ?: "") +
        " is invalid: $detail" +
        (entry?.let { " (entry: $it)" } ?: ""),
      cause,
    )

  /**
   * A domain declares a code as a NULL sentinel and also ships it as a codebook
   * row (D41). Loading it would give a stored NULL two meanings — "unknown" and
   * "this labelled thing" — so the load refuses.
   */
  class SentinelAsCodeException(
    val domain: String,
    val sentinels: List<Int>,
  ) : RuntimeException(
      "codebook domain [$domain] declares null_sentinel(s) $sentinels that are ALSO published as codes; " +
        "a sentinel is absence, not a value — nothing was written",
    )

  /**
   * The incoming codebook dropped a row that stored data still refers to. The
   * delete is refused: a `college_ipeds.rel_affil` pointing at a religion the
   * codebook no longer defines is a real fact about the corpus, and silently
   * removing its label would turn a coded column into an unexplainable number.
   */
  class ReferencedCodebookRowException(
    val domain: String,
    val key: String,
    val code: Int?,
    val references: List<String>,
  ) : RuntimeException(
      "codebook domain [$domain] no longer publishes row [$key]" +
        (code?.let { " (code $it)" } ?: "") +
        ", but it is still referenced by ${references.joinToString(", ")}; " +
        "the row was NOT deleted and nothing was written",
    )

  /**
   * The codebook declares a published archive whose bytes, ON DISK, hash to
   * something else. The file was hand-edited, or the archive was replaced —
   * either way it is not what it says it is, and a codebook that cannot be
   * traced to its publisher is exactly the thing RFC 147 exists to prevent.
   */
  class CodebookSlugRekeyException(
    val domain: String,
    val key: String,
    val code: Int?,
    val constraint: String,
    cause: Throwable,
  ) : RuntimeException(
      "codebook domain [$domain] publishes code [${code ?: "?"}] under slug [$key], but a DIFFERENT stored slug " +
        "already holds that code (violated [$constraint]). The upsert conflicts on the slug, so it cannot move a " +
        "code from one slug to another in a single pass. Land the re-key in two steps: a load that drops the old " +
        "slug (nothing may still reference it), then this one. Nothing was written",
      cause,
    )

  class ArtifactDigestMismatchException(
    val fileName: String,
    val mismatches: List<ArtifactCheck>,
  ) : RuntimeException(
      "codebook file [$fileName] declares source artifact digest(s) that the artifact on disk does not match: " +
        mismatches.joinToString("; ") {
          "[${it.domain}] ${it.sourceFile} at [${it.path}] hashes [${it.actual}], codebook declares [${it.declared}]"
        } + "; nothing was written",
    )

  // ---------------------------------------------------------------------------
  // Results
  // ---------------------------------------------------------------------------

  /** One domain's load disposition: the upsert split, deletions, and the resulting row count. */
  data class DomainSummary(
    val domain: String,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val deleted: Int,
    val rows: Int,
  )

  /**
   * One domain whose DECLARED artifact digest differs from the one the previous
   * load stored. [stored] is null the first time a domain is loaded, and a first
   * load is NOT drift — a database that has never held the domain has nothing to
   * have drifted from — so the two are separated at the point of reporting.
   *
   * This compares two DECLARATIONS. What the artifact on disk actually hashes to
   * is [ArtifactCheck]'s question, and the two are reported side by side so no
   * reader mistakes one for the other.
   */
  data class Drift(
    val domain: String,
    val stored: String?,
    val incoming: String,
  ) {
    /** True when a previous load stored a digest for this domain. */
    val isDrift: Boolean get() = stored != null
  }

  /**
   * The verdict on one domain's ARTIFACT: the published archive the codebook
   * says it came from, hashed off disk and compared to the digest the codebook
   * declares.
   *
   * The drift guard alone could never see a hand-edited `codebooks.json` that
   * left its digest strings untouched — the digest would agree with itself. This
   * is what makes `codebook_sources.source_sha256` mean something at load time
   * rather than only in `bin/scripts-tests`. [ABSENT] is a real, reported state:
   * a deployed `installDist` has no `db/seed/` beside it, and saying so is the
   * point — silence would read as verification.
   */
  enum class ArtifactStatus {
    VERIFIED,
    MISMATCH,
    ABSENT,
  }

  /** One domain's artifact verdict, naming the file it looked for and where. */
  data class ArtifactCheck(
    val domain: String,
    val sourceFile: String,
    val status: ArtifactStatus,
    val declared: String,
    val actual: String?,
    val path: String?,
  )

  /** Every domain's counts, its artifact verdict, and the drift the run detected. */
  data class LoadResult(
    val domains: List<DomainSummary>,
    val drift: List<Drift>,
    val artifacts: List<ArtifactCheck> = emptyList(),
  ) {
    val totalRows: Int get() = domains.sumOf { it.rows }

    /** Only the domains a PREVIOUS load had a digest for; a first load is not drift. */
    val changed: List<Drift> get() = drift.filter { it.isDrift }

    /** The domains this database is seeing for the first time. */
    val firstLoads: List<Drift> get() = drift.filterNot { it.isDrift }

    /** The operator-facing block: one line per domain, then the two verdicts. */
    fun render(): String =
      buildString {
        appendLine("codebooks: ${domains.size} domains, $totalRows rows")
        for (d in domains) {
          appendLine(
            "  ${d.domain}: ${d.rows} rows — ${d.inserted} inserted, ${d.changed} changed, " +
              "${d.unchanged} unchanged, ${d.deleted} deleted",
          )
        }
        appendLine(renderArtifacts())
        if (changed.isNotEmpty()) {
          appendLine("  SOURCE DIGEST DRIFT in ${changed.size} domain(s):")
          appendLine(
            changed.joinToString("\n") { d ->
              "    ${d.domain}: stored [${d.stored}] -> incoming [${d.incoming}]"
            },
          )
        }
        if (firstLoads.isNotEmpty()) {
          appendLine("  source digests: first load for ${firstLoads.size} domain(s) — nothing to have drifted from")
        }
        if (changed.isEmpty() && firstLoads.isEmpty()) {
          appendLine("  source digests: unchanged for every domain")
        }
        // trimEnd, not a conditional append: every branch above owns its own line.
      }.trimEnd()

    /**
     * The artifact verdict, which is deliberately explicit about ABSENT: a
     * guard that says nothing when it could not check is a guard a reader will
     * believe ran.
     */
    private fun renderArtifacts(): String {
      if (artifacts.isEmpty()) {
        return "  source artifacts: not checked (no artifact directory beside the codebook)"
      }
      val byStatus = artifacts.groupBy { it.status }
      val mismatched = byStatus[ArtifactStatus.MISMATCH].orEmpty()
      val absent = byStatus[ArtifactStatus.ABSENT].orEmpty()
      val verified = byStatus[ArtifactStatus.VERIFIED].orEmpty()
      return buildString {
        append(
          "  source artifacts: ${verified.size} verified, ${mismatched.size} MISMATCHED, " +
            "${absent.size} absent (of ${artifacts.size} domain(s))",
        )
        for (check in mismatched) {
          append("\n    MISMATCH ${check.domain}: [${check.sourceFile}] hashes [${check.actual}], codebook declares [${check.declared}]")
        }
        for (check in absent) {
          append("\n    absent   ${check.domain}: [${check.sourceFile}] not found; declared digest [${check.declared}] is UNVERIFIED")
        }
      }
    }
  }

  /**
   * One column's stored codes that no codebook row explains (D46), with the row
   * count behind each. A REPORT, never a failure: `colleges.locale`'s range
   * check admits codes IPEDS never emits, and tightening it into a real foreign
   * key is RFC 148's job, on the ingest that fills the column.
   */
  data class UnknownCodes(
    val column: CodeColumn,
    val domain: String,
    val counts: Map<String, Int>,
  ) {
    val distinctCodes: Int get() = counts.size
    val rows: Int get() = counts.values.sum()
  }

  /** Every column's unknown codes, including the columns that had none. */
  data class UnknownCodeReport(
    val columns: List<UnknownCodes>,
  ) {
    val withUnknowns: List<UnknownCodes> get() = columns.filter { it.counts.isNotEmpty() }

    fun render(): String =
      buildString {
        if (withUnknowns.isEmpty()) {
          append("codebook coverage: every stored code in ${columns.size} column(s) has a codebook row")
          return@buildString
        }
        appendLine(
          "codebook coverage: ${withUnknowns.size} of ${columns.size} column(s) store codes with NO codebook row",
        )
        append(
          withUnknowns.joinToString("\n") { u ->
            "  ${u.column} (${u.domain}): ${u.distinctCodes} unknown code(s) over ${u.rows} row(s) — " +
              u.counts.entries
                .sortedBy { it.key }
                .joinToString(", ") { (code, n) -> "$code=$n" }
          },
        )
      }
  }

  // ---------------------------------------------------------------------------
  // Load
  // ---------------------------------------------------------------------------

  /** Parses [source] and loads it; the convenience form for a direct call. */
  suspend fun load(source: SourceFile): LoadResult = load(withContext(ioDispatcher) { parse(source) })

  /**
   * Loads an already-parsed codebook in ONE transaction: upsert every domain in
   * declaration order (`us_states.ipeds_region` is a real FK, so regions land
   * first), then delete the rows the file dropped in REVERSE order, refusing any
   * that is still referenced, then rewrite each domain's provenance row.
   *
   * The drift comparison reads `codebook_sources` BEFORE the provenance
   * rewrite — after it, every digest would trivially agree with itself.
   */
  suspend fun load(parsed: ParsedCodebook): LoadResult =
    database.withConnection { session ->
      val storedDigests = CodebooksDao.storedSourceDigests(session).getOrThrow()
      val drift =
        parsed.domains.mapNotNull { domain ->
          val stored = storedDigests[domain.domain.key]
          if (stored == domain.source.sourceSha256) {
            null
          } else {
            Drift(domain.domain.key, stored, domain.source.sourceSha256)
          }
        }
      val upserts = parsed.domains.associate { it.domain to upsertDomain(session, it) }
      // Deletions run in reverse declaration order for the same reason upserts
      // run forward: a region can only go once the states that point at it have.
      val deletions =
        parsed.domains
          .reversed()
          .associate { it.domain to deleteDropped(session, it) }
      for (domain in parsed.domains) {
        CodebooksDao.upsertSource(session, domain.source).getOrThrow()
      }
      val result =
        LoadResult(
          artifacts = parsed.artifacts,
          domains =
            parsed.domains.map { domain ->
              val tally = upserts.getValue(domain.domain)
              DomainSummary(
                domain = domain.domain.key,
                inserted = tally.inserted,
                changed = tally.changed,
                unchanged = tally.unchanged,
                deleted = deletions.getValue(domain.domain),
                rows = CodebooksDao.rowCount(session, domain.domain.table).getOrThrow(),
              )
            },
          drift = drift,
        )
      logResult(result)
      result
    }

  /**
   * D46's report, read-only and separate from [load]: every distinct value
   * stored in a code column that no codebook row explains, with the number of
   * rows carrying it. Called by the ingest AFTER its row phases, so it measures
   * the snapshot the run just loaded rather than the previous one.
   */
  suspend fun reportUnknownCodes(): UnknownCodeReport =
    database.withConnection { session ->
      val report =
        UnknownCodeReport(
          Domain.entries.flatMap { domain ->
            val known = knownValues(session, domain)
            domain.references.map { reference ->
              val stored = CodebooksDao.storedCodeCounts(session, reference.column).getOrThrow()
              UnknownCodes(
                column = reference.column,
                domain = domain.key,
                counts = stored.filterKeys { it !in known.getValue(reference.by) },
              )
            }
          },
        )
      if (report.withUnknowns.isEmpty()) {
        logger.info("Codebook coverage: every stored code has a codebook row [columns={}]", report.columns.size)
      } else {
        for (unknown in report.withUnknowns) {
          logger.warn(
            "Codebook coverage: [{}] stores [{}] code(s) with no [{}] row over [{}] row(s) [codes={}]",
            unknown.column,
            unknown.distinctCodes,
            unknown.domain,
            unknown.rows,
            unknown.counts.toSortedMap(),
          )
        }
      }
      report
    }

  /**
   * Upserts one domain's rows, tallying the three-way [UpsertOutcome].
   *
   * Every slug-keyed table also carries `code SMALLINT NOT NULL UNIQUE`, and the
   * upsert's conflict target is the SLUG. So a regeneration that RE-KEYS a row —
   * same published code, new slug, which is what a label rewording produces —
   * inserts a new slug and collides on `<table>_code_key`, a constraint the
   * `ON CONFLICT (slug)` arbiter does not cover. The transaction rolls back
   * cleanly either way; what is not acceptable is handing the operator a generic
   * constraint violation for a situation with a specific answer, so it is named
   * here.
   */
  private fun upsertDomain(
    session: SqlSession,
    domain: ParsedDomain,
  ): Tally {
    val tally = Tally()
    for (entry in domain.entries) {
      try {
        tally.record(entry.upsert(session).getOrThrow())
      } catch (e: ConstraintViolationException) {
        val constraint = e.constraint
        if (constraint != null && constraint.endsWith("_code_key")) {
          throw CodebookSlugRekeyException(domain.domain.key, entry.key, entry.code, constraint, e)
        }
        throw e
      }
    }
    return tally
  }

  /**
   * Deletes the rows the incoming file no longer publishes — but only after
   * proving nothing refers to them. A referenced row is
   * [ReferencedCodebookRowException]: fatal, naming the row and every column
   * still pointing at it.
   */
  private fun deleteDropped(
    session: SqlSession,
    domain: ParsedDomain,
  ): Int {
    val incoming = domain.entries.map { it.key }.toSet()
    val dropped = CodebooksDao.storedRows(session, domain.domain.table).getOrThrow().filter { it.key !in incoming }
    if (dropped.isEmpty()) return 0
    val storedByColumn =
      domain.references.associateWith { CodebooksDao.storedCodeCounts(session, it.column).getOrThrow() }
    for (row in dropped) {
      val references =
        domain.references.mapNotNull { reference ->
          val value = if (reference.by == Referent.KEY) row.key else row.code?.toString()
          val rows = value?.let { storedByColumn.getValue(reference)[it] }
          rows?.let { "${reference.column} ($it row(s))" }
        }
      if (references.isNotEmpty()) {
        throw ReferencedCodebookRowException(domain.domain.key, row.key, row.code, references)
      }
      CodebooksDao.deleteRow(session, domain.domain.table, row.key).getOrThrow()
    }
    return dropped.size
  }

  /**
   * A domain's currently-stored keys and codes, as the text both the report and
   * the refusal compare against — one read per domain rather than one per
   * referring column.
   */
  private fun knownValues(
    session: SqlSession,
    domain: Domain,
  ): Map<Referent, Set<String>> {
    val rows = CodebooksDao.storedRows(session, domain.table).getOrThrow()
    return mapOf(
      Referent.KEY to rows.map { it.key }.toSet(),
      Referent.CODE to rows.mapNotNull { it.code?.toString() }.toSet(),
    )
  }

  private fun logResult(result: LoadResult) {
    for (domain in result.domains) {
      logger.info(
        "Codebook domain [{}]: [rows={}] [inserted={}] [changed={}] [unchanged={}] [deleted={}]",
        domain.domain,
        domain.rows,
        domain.inserted,
        domain.changed,
        domain.unchanged,
        domain.deleted,
      )
    }
    // The ARTIFACT verdict first: it is the only one that compared the codebook
    // to something other than another declaration, and ABSENT is said out loud
    // rather than passed over as if it were verification.
    val absent = result.artifacts.filter { it.status == ArtifactStatus.ABSENT }
    when {
      result.artifacts.isEmpty() -> {
        logger.warn("Codebook source artifacts NOT CHECKED: no artifact directory beside the codebook file")
      }

      absent.isEmpty() -> {
        logger.info("Codebook source artifacts verified on disk for all [{}] domains", result.artifacts.size)
      }

      else -> {
        logger.warn(
          "Codebook source artifacts absent for [{}] of [{}] domain(s) [files={}]: their declared digests are UNVERIFIED",
          absent.size,
          result.artifacts.size,
          absent.map { it.sourceFile }.distinct(),
        )
      }
    }
    // Drift is the reason codebook_sources exists, so it is stated on EVERY run
    // — including the quiet "nothing moved" verdict, which is the evidence the
    // guard actually ran. A FIRST load is not drift and does not get the warning:
    // a database that has never held the domain has nothing to have drifted from.
    for (first in result.firstLoads) {
      logger.info("Codebook domain [{}] loaded for the first time [incoming={}]", first.domain, first.incoming)
    }
    if (result.changed.isEmpty()) {
      logger.info(
        "Codebook source digests unchanged for all [{}] previously-loaded domain(s)",
        result.domains.size - result.firstLoads.size,
      )
    } else {
      for (drift in result.changed) {
        logger.warn(
          "CODEBOOK SOURCE DRIFT [domain={}] [stored={}] [incoming={}]: the loaded rows now come from a " +
            "DIFFERENT published artifact than the ones already stored",
          drift.domain,
          drift.stored,
          drift.incoming,
        )
      }
    }
  }

  private class Tally {
    var inserted = 0
    var changed = 0
    var unchanged = 0

    fun record(outcome: UpsertOutcome) {
      when (outcome) {
        UpsertOutcome.INSERTED -> inserted++
        UpsertOutcome.CHANGED -> changed++
        UpsertOutcome.UNCHANGED -> unchanged++
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Parsing — every layer verified, nothing cast through
  // ---------------------------------------------------------------------------

  /**
   * Reads and verifies the whole codebook file without touching the database,
   * so the ingest can fail on a malformed codebook BEFORE its first phase
   * commits — the up-front assertion every other source in the run already gets.
   *
   * Blocking file read; the caller places it on its IO dispatcher.
   */
  fun parse(source: SourceFile): ParsedCodebook {
    val name = source.file.path
    val root =
      try {
        Json.parseToJsonElement(source.file.readText())
      } catch (e: SerializationException) {
        throw InvalidCodebookFileException(name, "not valid JSON (${e.message})", cause = e)
      }
    val obj =
      root as? JsonObject
        ?: throw InvalidCodebookFileException(name, "the top level must be a JSON object keyed by domain")
    val expected = Domain.entries.map { it.key }.toSet()
    if (obj.keys != expected) {
      val unknown = obj.keys - expected
      val missing = expected - obj.keys
      throw InvalidCodebookFileException(
        name,
        "the domains must be exactly ${expected.sorted()}" +
          (if (unknown.isEmpty()) "" else "; unknown domain(s) ${unknown.sorted()}") +
          (if (missing.isEmpty()) "" else "; missing domain(s) ${missing.sorted()}"),
      )
    }
    val parsed = Domain.entries.map { domain -> parseDomain(name, domain, obj.getValue(domain.key)) }
    assertRegionMembershipAgrees(name, parsed)
    val artifacts = verifyArtifacts(source.file, parsed)
    return ParsedCodebook(fileName = name, domains = parsed, artifacts = artifacts)
  }

  /**
   * Hashes each domain's PUBLISHED ARTIFACT off disk and compares it to the
   * digest the codebook declares (RFC 147, `codebook_sources.source_sha256`).
   *
   * Without this the only integrity guard at load time compared a declaration to
   * a declaration: a hand-edited `codebooks.json` that left the digest strings
   * alone would load in silence, which makes the digest column decorative. The
   * artifacts are the committed the `.zip` files under `db/seed/codebooks/`, found relative to the
   * codebook file itself (`db/data/codebooks.json` -> `../seed/codebooks`) so no
   * new flag has to be threaded through the ingest, and looked for beside the
   * file too, which is where a regeneration into a temp directory puts them.
   *
   * A MISMATCH is FATAL: the file claims to have been generated from an archive
   * that is sitting right there and is not the one it names. ABSENT is not — a
   * deployed `installDist` carries no `db/seed/` — but it is REPORTED per domain
   * rather than passed over, because a guard that stays quiet when it could not
   * run is a guard everybody assumes ran.
   */
  private fun verifyArtifacts(
    codebookFile: File,
    domains: List<ParsedDomain>,
  ): List<ArtifactCheck> {
    val dir = codebookFile.absoluteFile.parentFile ?: return emptyList()
    val candidates = listOf(File(dir.parentFile, "seed/codebooks"), dir)
    val digests = mutableMapOf<String, Pair<String, String>?>()
    val checks =
      domains.map { domain ->
        val fileName = domain.source.sourceFile
        val found =
          digests.getOrPut(fileName) {
            candidates
              .map { File(it, fileName) }
              .firstOrNull { it.isFile }
              ?.let { it.path to sha256Of(it) }
          }
        when {
          found == null -> {
            ArtifactCheck(domain.domain.key, fileName, ArtifactStatus.ABSENT, domain.source.sourceSha256, null, null)
          }

          found.second == domain.source.sourceSha256 -> {
            ArtifactCheck(
              domain.domain.key,
              fileName,
              ArtifactStatus.VERIFIED,
              domain.source.sourceSha256,
              found.second,
              found.first,
            )
          }

          else -> {
            ArtifactCheck(
              domain.domain.key,
              fileName,
              ArtifactStatus.MISMATCH,
              domain.source.sourceSha256,
              found.second,
              found.first,
            )
          }
        }
      }
    val mismatched = checks.filter { it.status == ArtifactStatus.MISMATCH }
    if (mismatched.isNotEmpty()) {
      throw ArtifactDigestMismatchException(codebookFile.path, mismatched)
    }
    return checks
  }

  /** Streamed, so a 30 MB archive is never held in memory to be hashed. */
  private fun sha256Of(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun parseDomain(
    file: String,
    domain: Domain,
    element: JsonElement,
  ): ParsedDomain {
    fun invalid(detail: String): Nothing = throw InvalidCodebookFileException(file, detail, domain = domain.key)

    val obj = element as? JsonObject ?: invalid("a domain must be a JSON object")
    if (obj.keys != DOMAIN_KEYS) {
      invalid(
        "a domain's keys must be exactly ${DOMAIN_KEYS.sorted()}; got ${obj.keys.sorted()}",
      )
    }
    val sentinels =
      (obj.getValue("null_sentinels") as? JsonArray ?: invalid("null_sentinels must be a JSON array"))
        .map { intOf(it) ?: invalid("every null_sentinel must be a JSON integer; got [$it]") }
    val source =
      NewCodebookSource(
        domain = domain.key,
        source = stringOf(obj.getValue("source")) ?: invalid("source must be a JSON string"),
        sourceFile = stringOf(obj.getValue("source_file")) ?: invalid("source_file must be a JSON string"),
        sourceSha256 = stringOf(obj.getValue("source_sha256")) ?: invalid("source_sha256 must be a JSON string"),
        sourceVintageYear =
          intOf(obj.getValue("source_vintage_year")) ?: invalid("source_vintage_year must be a JSON integer"),
        nullSentinels = sentinels,
      )
    val codes = obj.getValue("codes") as? JsonArray ?: invalid("codes must be a JSON array")
    if (codes.isEmpty()) invalid("a domain must publish at least one code")
    val entries = codes.mapIndexed { index, code -> parseEntry(file, domain, index, code) }
    val duplicates =
      entries
        .groupingBy { it.key }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicates.isNotEmpty()) invalid("duplicate key(s) $duplicates")
    // D41, restated at the DB boundary: a code that is also declared absent
    // would make a stored NULL mean two different things.
    val sentinelCodes =
      entries
        .mapNotNull { it.code }
        .filter { it in sentinels }
        .distinct()
        .sorted()
    if (sentinelCodes.isNotEmpty()) throw SentinelAsCodeException(domain.key, sentinelCodes)
    return ParsedDomain(domain = domain, source = source, entries = entries)
  }

  /**
   * One code entry, verified against the domain's exact key set and mapped to
   * its typed row model. The upsert is captured as a closure per entry so the
   * eleven table-specific DAO calls stay type-checked at the point the row is
   * built, instead of being re-dispatched later off a stringly-typed shape.
   */
  private fun parseEntry(
    file: String,
    domain: Domain,
    index: Int,
    element: JsonElement,
  ): Entry {
    fun invalid(detail: String): Nothing =
      throw InvalidCodebookFileException(file, detail, domain = domain.key, entryIndex = index, entry = element.toString())

    val obj = element as? JsonObject ?: invalid("an entry must be a JSON object")
    val unknown = obj.keys - domain.entryKeys
    val missing = domain.requiredEntryKeys - obj.keys
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
      invalid(
        "an entry's keys must be within ${domain.entryKeys.sorted()} and include " +
          "${domain.requiredEntryKeys.sorted()}" +
          (if (unknown.isEmpty()) "" else "; unknown key(s) ${unknown.sorted()}") +
          (if (missing.isEmpty()) "" else "; missing key(s) ${missing.sorted()}"),
      )
    }

    fun str(key: String): String = stringOf(obj.getValue(key)) ?: invalid("[$key] must be a JSON string")

    fun strOrNull(key: String): String? =
      obj[key]?.takeIf { it !is JsonNull }?.let {
        stringOf(it)
          ?: invalid("[$key] must be a JSON string or null")
      }

    fun int(key: String): Int = intOf(obj.getValue(key)) ?: invalid("[$key] must be a JSON integer")

    fun intOrNull(key: String): Int? =
      obj[key]?.takeIf { it !is JsonNull }?.let {
        intOf(it)
          ?: invalid("[$key] must be a JSON integer or null")
      }

    return when (domain) {
      Domain.IPEDS_REGION -> {
        val row = NewIpedsRegion(slug = str("slug"), code = int("code"), name = str("name"), labelRaw = str("label_raw"))
        // member_states is not a column: it IS us_states.ipeds_region, and the
        // two are cross-checked once the whole file is parsed.
        val members =
          (obj.getValue("member_states") as? JsonArray ?: invalid("member_states must be a JSON array"))
            .map { stringOf(it) ?: invalid("every member state must be a JSON string; got [$it]") }
        Entry(key = row.slug, code = row.code, memberStates = members) { CodebooksDao.upsertIpedsRegion(it, row) }
      }

      Domain.US_STATES -> {
        val row =
          NewUsState(
            uspsCode = str("code"),
            name = str("name"),
            // The one AUTHORED value in the codebook, so it is an own
            // enumeration with a Kotlin enum (CLAUDE.md): a value the enum does
            // not name is a generator/code divergence, refused here by name
            // rather than 200 lines later as a CHECK violation.
            jurisdictionKind =
              JurisdictionKind.fromValue(str("jurisdiction_kind"))
                ?: invalid("[jurisdiction_kind] must be one of ${JurisdictionKind.VALUES}; got [${str("jurisdiction_kind")}]"),
            ipedsRegion = str("ipeds_region"),
          )
        Entry(key = row.uspsCode, code = null, region = row.ipedsRegion) { CodebooksDao.upsertUsState(it, row) }
      }

      Domain.NCES_LOCALE -> {
        val row =
          NewNcesLocale(
            slug = str("slug"),
            code = int("code"),
            type = str("type"),
            detail = str("detail"),
            name = str("name"),
            labelRaw = str("label_raw"),
          )
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertNcesLocale(it, row) }
      }

      Domain.CARNEGIE_2021_BASIC -> {
        val row =
          NewCarnegieBasicClass(
            slug = str("slug"),
            code = int("code"),
            degreeLevel = strOrNull("degree_level"),
            qualifier = strOrNull("qualifier"),
            name = str("name"),
            labelRaw = str("label_raw"),
          )
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertCarnegieBasicClass(it, row) }
      }

      Domain.CARNEGIE_2021_SIZE_SETTING -> {
        val row =
          NewCarnegieSizeSetting(
            slug = str("slug"),
            code = int("code"),
            years = intOrNull("years"),
            size = strOrNull("size"),
            residentialCharacter = strOrNull("residential_character"),
            name = str("name"),
            labelRaw = str("label_raw"),
          )
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertCarnegieSizeSetting(it, row) }
      }

      Domain.RELIGIOUS_AFFILIATION -> {
        val row =
          NewReligiousAffiliation(slug = str("slug"), code = int("code"), name = str("name"), labelRaw = str("label_raw"))
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertReligiousAffiliation(it, row) }
      }

      Domain.ATHLETIC_ASSOCIATION -> {
        val row =
          NewAthleticAssociation(
            slug = str("slug"),
            code = int("code"),
            sourceVariable = str("source_variable"),
            name = str("name"),
            labelRaw = str("label_raw"),
          )
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertAthleticAssociation(it, row) }
      }

      Domain.FOOTBALL_CONFERENCE -> {
        val row =
          NewFootballConference(slug = str("slug"), code = int("code"), name = str("name"), labelRaw = str("label_raw"))
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertFootballConference(it, row) }
      }

      Domain.ADMISSION_TEST_POLICY -> {
        val row =
          NewAdmissionTestPolicy(slug = str("slug"), code = int("code"), name = str("name"), labelRaw = str("label_raw"))
        Entry(key = row.slug, code = row.code) { CodebooksDao.upsertAdmissionTestPolicy(it, row) }
      }

      Domain.CIP_CODE -> {
        val row = NewCipCode(code = str("code"), title = str("name"), labelRaw = str("label_raw"))
        // cip_family is DERIVED (left(code, 2)) and so is not stored — but the
        // generator emits it, and a family disagreeing with its own code means
        // the two were produced from different rows.
        val family = str("cip_family")
        if (family != row.code.take(2)) {
          invalid("cip_family [$family] disagrees with code [${row.code}]")
        }
        Entry(key = row.code, code = null) { CodebooksDao.upsertCipCode(it, row) }
      }
    }
  }

  /**
   * The region membership the OBEREG labels carry must be exactly the membership
   * `us_states` declares, in BOTH directions (D39). The generator asserts this
   * against the artifact; asserting it again over the parsed file is what makes
   * a hand-edited `codebooks.json` fail here instead of loading two disagreeing
   * views of the same fact.
   */
  private fun assertRegionMembershipAgrees(
    file: String,
    domains: List<ParsedDomain>,
  ) {
    val regions = domains.first { it.domain == Domain.IPEDS_REGION }
    val states = domains.first { it.domain == Domain.US_STATES }
    val fromRegions = regions.entries.flatMap { entry -> entry.memberStates.map { it to entry.key } }.toMap()
    val fromStates = states.entries.associate { it.key to (it.region ?: "") }
    if (fromRegions != fromStates) {
      val disagreeing =
        (fromRegions.keys + fromStates.keys)
          .filter { fromRegions[it] != fromStates[it] }
          .sorted()
      throw InvalidCodebookFileException(
        file,
        "the OBEREG membership and us_states.ipeds_region disagree for $disagreeing " +
          "(region says ${disagreeing.map { fromRegions[it] }}, state says ${disagreeing.map { fromStates[it] }})",
      )
    }
  }

  /**
   * A JSON INTEGER, refusing the quoted form: kotlinx models `1` and `"1"` as
   * the same class differing only in `isString`, and a typo'd quote in generated
   * data must fail rather than be coerced (the curated-aliases rule).
   */
  private fun intOf(element: JsonElement): Int? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

  companion object {
    private val logger = LoggerFactory.getLogger(CodebookLoader::class.java)

    /** The keys every domain object carries, exactly. */
    private val DOMAIN_KEYS =
      setOf("source", "source_file", "source_sha256", "source_vintage_year", "null_sentinels", "codes")
  }

  /** Which half of a codebook row a storing column holds: its natural key, or its published code. */
  enum class Referent {
    KEY,
    CODE,
  }

  /** One column that refers to a codebook domain, and by which half of the row. */
  data class CodebookReference(
    val column: CodeColumn,
    val by: Referent,
  )

  /**
   * The ten codebook domains: the `codebooks.json` key, the table each loads into,
   * the entry keys each publishes, and the columns that refer to it.
   *
   * DECLARATION ORDER IS LOAD ORDER — `us_states.ipeds_region` is a real foreign
   * key, so regions are upserted before states and deleted after them.
   *
   * [references] is the one place that says which stored column speaks which
   * codebook. Both D46's report and the delete refusal read it, so a column and
   * its codebook can never be paired one way in the report and another way in the
   * guard. Each column is [CodeColumns]' own named constant — the SQL allowlist
   * and this map are then literally the same objects, not two lists that agree.
   */
  enum class Domain(
    val key: String,
    val table: CodebookTable,
    val requiredEntryKeys: Set<String>,
    val references: List<CodebookReference>,
    /** Optional entry keys, i.e. those the publisher ships only sometimes. */
    val optionalEntryKeys: Set<String> = emptySet(),
  ) {
    IPEDS_REGION(
      key = "ipeds_region",
      table = CodebookTable.IPEDS_REGIONS,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw", "member_states"),
      references =
        listOf(
          CodebookReference(CodeColumns.COLLEGES_REGION, Referent.CODE),
          CodebookReference(CodeColumns.US_STATES_IPEDS_REGION, Referent.KEY),
        ),
    ),
    US_STATES(
      key = "us_states",
      table = CodebookTable.US_STATES,
      requiredEntryKeys = setOf("code", "name", "label_raw", "jurisdiction_kind", "ipeds_region"),
      references =
        listOf(
          CodebookReference(CodeColumns.COLLEGES_STATE, Referent.KEY),
          // The search index too, since 0067: the codebooks phase runs BEFORE
          // the search-index rebuild, so a state dropped from the published
          // codebook is still held by the STALE index at the moment the delete
          // runs. Without this the delete would be refused by
          // `college_search_index_state_fkey` as a raw FK error rather than by
          // the named refusal above.
          CodebookReference(CodeColumns.COLLEGE_SEARCH_INDEX_STATE, Referent.KEY),
        ),
    ),
    NCES_LOCALE(
      key = "nces_locale",
      table = CodebookTable.NCES_LOCALES,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw", "type", "detail"),
      references = listOf(CodebookReference(CodeColumns.COLLEGES_LOCALE, Referent.CODE)),
    ),
    CARNEGIE_2021_BASIC(
      key = "carnegie_2021_basic",
      table = CodebookTable.CARNEGIE_2021_BASIC_CLASSES,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw", "degree_level", "qualifier"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_CARNEGIE_BASIC, Referent.CODE)),
    ),
    CARNEGIE_2021_SIZE_SETTING(
      key = "carnegie_2021_size_setting",
      table = CodebookTable.CARNEGIE_2021_SIZE_SETTINGS,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw", "years", "size", "residential_character"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_CARNEGIE_SIZE, Referent.CODE)),
    ),
    RELIGIOUS_AFFILIATION(
      key = "religious_affiliation",
      table = CodebookTable.RELIGIOUS_AFFILIATIONS,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_REL_AFFIL, Referent.CODE)),
    ),
    ATHLETIC_ASSOCIATION(
      key = "athletic_association",
      table = CodebookTable.ATHLETIC_ASSOCIATIONS,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw", "source_variable"),
      references =
        listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_ATHLETIC_ASSOC, Referent.CODE)),
    ),
    FOOTBALL_CONFERENCE(
      key = "football_conference",
      table = CodebookTable.FOOTBALL_CONFERENCES,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_FOOTBALL_CONF, Referent.CODE)),
    ),
    ADMISSION_TEST_POLICY(
      key = "admission_test_policy",
      table = CodebookTable.ADMISSION_TEST_POLICIES,
      requiredEntryKeys = setOf("code", "slug", "name", "label_raw"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_IPEDS_TEST_POLICY, Referent.CODE)),
    ),
    CIP_CODE(
      key = "cip_code",
      table = CodebookTable.CIP_CODES,
      requiredEntryKeys = setOf("code", "name", "label_raw", "cip_family"),
      references = listOf(CodebookReference(CodeColumns.COLLEGE_PROGRAMS_CENSUS_CIP_CODE, Referent.KEY)),
    ),
    ;

    /** Every key an entry MAY carry. */
    val entryKeys: Set<String> get() = requiredEntryKeys + optionalEntryKeys
  }

  /**
   * One verified code entry: its natural key, its published code (null where the
   * key IS the code) and the typed DAO call that writes it.
   *
   * The write rides as a closure because the ten tables have ten different row
   * types and ten different DAO functions: building the closure where the typed
   * row is built keeps every field type-checked, and leaves the load loop with
   * nothing to dispatch on.
   */
  class Entry(
    val key: String,
    val code: Int?,
    /** OBEREG membership, for the region/state cross-check. Empty for every other domain. */
    val memberStates: List<String> = emptyList(),
    /** `us_states.ipeds_region`, for the same cross-check. Null for every other domain. */
    val region: String? = null,
    val upsert: (SqlSession) -> Result<UpsertOutcome>,
  )

  /** One parsed domain: its provenance row and its verified entries. */
  data class ParsedDomain(
    val domain: Domain,
    val source: NewCodebookSource,
    val entries: List<Entry>,
  ) {
    val references: List<CodebookReference> get() = domain.references
  }

  /** A whole verified codebook file, ready to load. */
  data class ParsedCodebook(
    val fileName: String,
    val domains: List<ParsedDomain>,
    /** Per-domain artifact verdicts; empty only when the file has no directory. */
    val artifacts: List<ArtifactCheck> = emptyList(),
  )
}
