package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.CdsCoverage
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("ed.unicoach.college.IngestApplication")

private const val CDS_MERIT_FLAG = "--cds-merit"
private const val CDS_FACTORS_FLAG = "--cds-factors"
private const val CDS_DEADLINES_FLAG = "--cds-deadlines"

private val CDS_FLAGS = listOf(CDS_MERIT_FLAG, CDS_FACTORS_FLAG, CDS_DEADLINES_FLAG)

private const val USAGE =
  "Usage: ingest-colleges <institution.csv> <fields.csv> <aliases.json> " +
    "[--institution-source=ARG] [--fields-source=ARG] [--aliases-source=ARG] " +
    "--codebooks=codebooks.json [--codebooks-source=ARG] " +
    "[--subjects=subjects.json] [--subjects-source=ARG] " +
    "[--money-vocabulary=money-vocabulary.json] [--money-vocabulary-source=ARG] " +
    "[$CDS_MERIT_FLAG <merit-aid.csv> $CDS_FACTORS_FLAG <admission-factors.csv> " +
    "$CDS_DEADLINES_FLAG <deadlines.csv>] " +
    "[--hd=HD.csv --ic=IC.csv --adm=adm.csv --completions=C_A.csv --ic-ay=ic_ay.csv " +
    "--survey-year=YYYY] " +
    "[--hd-source=ARG] [--ic-source=ARG] [--adm-source=ARG] [--completions-source=ARG] " +
    "[--ic-ay-source=ARG] " +
    "[--sfa=sfa.csv --sfa-aid-year=YYYY] [--sfa-source=ARG]"

/** The recognized `--<name>=<value>` provenance flags, keyed by positional index. */
private val SOURCE_FLAGS = listOf("institution-source", "fields-source", "aliases-source")

/**
 * The generated published codebook (RFC 147). REQUIRED, and option-shaped
 * rather than a fourth positional.
 *
 * Required since migration 0067: `colleges.state` and `colleges.locale` are
 * foreign keys onto `us_states` and `nces_locales`, so a run that loaded no
 * codebook cannot write a single college row. It used to be optional — omitted
 * meant "no `codebooks` phase", the omit-vs-zero rule the IPEDS and CDS groups
 * follow — and that rule still holds for THEM, because their tables constrain
 * nothing. This one now names a precondition, so the binary states it instead
 * of leaving `bin/ingest-colleges` to be the only thing that knows.
 *
 * Still an OPTION, and still with no default inside the JVM: the artifact ships
 * via `installDist` and has no PROJECT_ROOT, so a deployed binary guessing a
 * repo layout is exactly the mistake the launcher exists to prevent. The
 * launcher decides WHICH file; the binary insists there is one.
 */
private const val CODEBOOKS_FLAG = "codebooks"

/**
 * The authored subject taxonomy (RFC 150). Optional and OPTION-shaped for
 * exactly [CODEBOOKS_FLAG]'s reasons: `bin/ingest-colleges` always supplies it,
 * defaulting to the repo copy, so the flag exists for the direct JVM invocation
 * and for a caller loading a taxonomy from somewhere else. Omitted, the run
 * loads no subjects at all and the search index's `subject_slugs` come out
 * empty — the omit-vs-zero rule every other group follows.
 */
private const val SUBJECTS_FLAG = "subjects"

/**
 * The authored money vocabulary (RFC 158). Optional and OPTION-shaped for
 * exactly [SUBJECTS_FLAG]'s reasons: `bin/ingest-colleges` always supplies
 * it, defaulting to the repo copy (`db/data/money-vocabulary.json`), so the
 * flag exists for the direct JVM invocation. Omitted, the run loads no
 * vocabulary FILE -- but the `canonical-money` phase still runs against the
 * vocabulary TABLES, which are its real precondition (P2).
 */
private const val MONEY_VOCABULARY_FLAG = "money-vocabulary"

/**
 * The three CDS admissions seed files (RFC 140), passed as NAMED flags rather
 * than trailing positionals so the file-to-table mapping is self-describing and
 * order-free -- a mis-ordered argv cannot silently load deadlines as merit aid.
 */
internal data class CdsArgs(
  val meritAidCsv: File,
  val admissionFactorsCsv: File,
  val deadlinesCsv: File,
) {
  /**
   * The provenance spelling of the same three files (RFC 148). The CDS flags
   * name files the shell already split into their own argv slots, so the path
   * the caller typed IS the file's path — there is no `--*-source` partner to
   * carry a different original argument.
   */
  val sources: CdsSources
    get() =
      CdsSources(
        meritAid = SourceFile(meritAidCsv, meritAidCsv.path),
        admissionFactors = SourceFile(admissionFactorsCsv, admissionFactorsCsv.path),
        deadlines = SourceFile(deadlinesCsv, deadlinesCsv.path),
      )
}

/**
 * The optional IPEDS file flags (RFC 144, gate-2 D19; extended by RFC 161 with
 * `ic-ay`). All five — plus [SURVEY_YEAR_FLAG] — are required together or not
 * at all: a partial group is a usage error, never a silent partial load.
 */
private val IPEDS_FILE_FLAGS = listOf("hd", "ic", "adm", "completions", "ic-ay")

/** Each IPEDS file's optional provenance partner, exactly as the Scorecard trio has. */
private val IPEDS_SOURCE_FLAGS = IPEDS_FILE_FLAGS.map { "$it-source" }

/**
 * The survey year is EXPLICIT, never derived from a filename: a derived year is
 * a silent coercion, and this value is stamped on every row the IPEDS phases
 * write.
 */
private const val SURVEY_YEAR_FLAG = "survey-year"

/**
 * The survey-year domain, owned by [IpedsLoader] with the rest of the 0055
 * mirrors: one declaration, so a CHECK change cannot leave the argv refusal
 * disagreeing with the loader.
 */
private val SURVEY_YEAR_RANGE = IpedsLoader.YEAR_RANGE

/**
 * The optional IPEDS SFA group (RFC 162): the survey file and the START year
 * of the aid year it publishes, required together or not at all. Its OWN
 * group rather than two more members of the IPEDS one, because it is a
 * different survey on a different YEAR FAMILY -- `SFA2223` is aid year
 * 2022-23, published in the 2023-24 collection -- and folding it in would
 * break every existing five-flag invocation.
 */
private const val SFA_FILE_FLAG = "sfa"

/** The SFA file's provenance partner, exactly as every other source has. */
private const val SFA_SOURCE_FLAG = "$SFA_FILE_FLAG-source"

/**
 * The START year of the file's own aid year (SFA2223 -> 2022). EXPLICIT, never
 * derived from a filename, for [SURVEY_YEAR_FLAG]'s reason -- and a different
 * number from it: reusing `--survey-year` would stamp the collection year on
 * rows whose figures are the aid year before it.
 */
private const val SFA_AID_YEAR_FLAG = "$SFA_FILE_FLAG-aid-year"

private val SFA_FLAGS = listOf(SFA_FILE_FLAG, SFA_AID_YEAR_FLAG)

private val KNOWN_FLAGS =
  SOURCE_FLAGS + IPEDS_FILE_FLAGS + IPEDS_SOURCE_FLAGS + SURVEY_YEAR_FLAG +
    CODEBOOKS_FLAG + "$CODEBOOKS_FLAG-source" + SUBJECTS_FLAG + "$SUBJECTS_FLAG-source" +
    MONEY_VOCABULARY_FLAG + "$MONEY_VOCABULARY_FLAG-source" + SFA_FLAGS + SFA_SOURCE_FLAG

/**
 * The argv grammar's outcome (RFC 139, extended for the CDS group in RFC 140
 * and the IPEDS group in RFC 144): either the resolved sources or a usage
 * refusal. Split out of [main] so the refusals — a repeated flag, an empty
 * flag value, a wrong positional count, a partial CDS or IPEDS group — are
 * directly testable rather than reachable only through `exitProcess`.
 */
internal sealed interface ArgvResult {
  /**
   * The three Scorecard sources, in `SOURCE_FLAGS` order, each paired with the
   * caller's original argument, plus the optional IPEDS group ([ipeds] is null
   * when none of its flags were given).
   */
  data class Ok(
    val sources: List<SourceFile>,
    val cds: CdsArgs? = null,
    val ipeds: IpedsSources? = null,
    /** The optional IPEDS SFA group (RFC 162), null when none of its flags were given. */
    val sfa: SfaSources? = null,
    /** The generated codebook (RFC 147). Required since 0067, so never null. */
    val codebooks: SourceFile,
    /** The authored subject taxonomy (RFC 150), null when `--subjects` was omitted. */
    val subjects: SourceFile? = null,
    /** The authored money vocabulary (RFC 158), null when `--money-vocabulary` was omitted. */
    val moneyVocabulary: SourceFile? = null,
  ) : ArgvResult

  /** A grammar violation: [message] is logged and the process exits 2. */
  data class Usage(
    val message: String,
  ) : ArgvResult
}

/**
 * Parses `<institution.csv> <fields.csv> <aliases.json>` plus the optional
 * `--<name>=<value>` provenance flags (RFC 139), the optional `--cds-* <path>`
 * seed group (RFC 140), and the optional IPEDS group (RFC 144). Every deviation
 * is a refusal, never a silent coercion: an unknown or valueless flag, a
 * REPEATED flag (last-wins would write provenance the caller never asked for), a
 * flag whose value is blank (present-but-empty is a wrong value, not an absent
 * flag), a positional count other than [SOURCE_FLAGS]`.size`, a PARTIAL CDS
 * group (one table's cycle without its siblings would skew the coverage report),
 * a PARTIAL IPEDS group, an IPEDS `--*-source` naming a file that was not
 * supplied, or a `--survey-year` that is not a plausible year. File existence is
 * checked by the caller; this function touches no disk.
 *
 * Two flag spellings coexist because their sources differ: the `=`-joined flags
 * carry a caller argument that may contain anything, while the CDS flags name
 * files the shell already split into their own argv slots.
 */
internal fun parseArgv(args: Array<String>): ArgvResult {
  val positional = mutableListOf<String>()
  val flags = mutableMapOf<String, String>()
  val cdsArgs = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    if (arg in CDS_FLAGS) {
      val value = args.getOrNull(i + 1) ?: return ArgvResult.Usage("Option [$arg] requires a value. $USAGE")
      if (value.isBlank()) {
        return ArgvResult.Usage("Option [$arg] must have a non-empty value. $USAGE")
      }
      if (cdsArgs.put(arg, value) != null) {
        return ArgvResult.Usage("Option [$arg] was given more than once. $USAGE")
      }
      i += 2
      continue
    }
    i += 1
    // Only `--` spellings are options here: the CDS group's single-dash flags
    // are consumed by bin/ingest-colleges' getopts and never reach the JVM, so
    // a leading `-` at this layer can only be part of a file path.
    if (!arg.startsWith("--")) {
      positional += arg
      continue
    }
    val eq = arg.indexOf('=')
    val name = if (eq >= 0) arg.substring(2, eq) else arg.substring(2)
    if (eq < 0 || name !in KNOWN_FLAGS) {
      return ArgvResult.Usage("Unknown or malformed option [$arg]. $USAGE")
    }
    if (name in flags) {
      return ArgvResult.Usage("Option [--$name] was given more than once. $USAGE")
    }
    val value = arg.substring(eq + 1)
    if (value.isBlank()) {
      return ArgvResult.Usage("Option [--$name] must have a non-empty value. $USAGE")
    }
    flags[name] = value
  }
  if (positional.size != SOURCE_FLAGS.size) {
    return ArgvResult.Usage(USAGE)
  }
  if (cdsArgs.isNotEmpty() && cdsArgs.size != CDS_FLAGS.size) {
    return ArgvResult.Usage(
      "The CDS options are all-or-nothing: pass [${CDS_FLAGS.joinToString("] [")}] together, or none. $USAGE",
    )
  }
  val ipeds =
    when (val group = parseIpedsGroup(flags)) {
      is FlagGroup.Invalid -> return ArgvResult.Usage(group.message)
      is FlagGroup.Absent -> null
      is FlagGroup.Present -> group.sources
    }
  val sfa =
    when (val group = parseSfaGroup(flags)) {
      is FlagGroup.Invalid -> return ArgvResult.Usage(group.message)
      is FlagGroup.Absent -> null
      is FlagGroup.Present -> group.sources
    }
  // The codebook is REQUIRED since migration 0067 (see [CODEBOOKS_FLAG]): a run
  // without one cannot write a college row, so it is refused here rather than
  // discovered as a foreign-key violation. This subsumes the dangling
  // `--codebooks-source` refusal that used to live here — a provenance flag can
  // no longer name a codebook that was not supplied, because there is always
  // one.
  val codebooksSourceFlag = "$CODEBOOKS_FLAG-source"
  val codebooksPath =
    flags[CODEBOOKS_FLAG]
      ?: return ArgvResult.Usage(
        "Option [--$CODEBOOKS_FLAG] is required: `colleges.state` and `colleges.locale` reference the " +
          "published codebook tables, so an ingest without one can write no college row. $USAGE",
      )
  // Same refusal, same reason, for the taxonomy (RFC 150).
  val subjectsSourceFlag = "$SUBJECTS_FLAG-source"
  if (subjectsSourceFlag in flags && SUBJECTS_FLAG !in flags) {
    return ArgvResult.Usage(
      "Option [--$subjectsSourceFlag] names a provenance source for a subject file that was not supplied. $USAGE",
    )
  }
  // Same refusal for the money vocabulary (RFC 158): a provenance source with
  // no file to describe is a caller mistake, never a defaulted load.
  val moneyVocabularySourceFlag = "$MONEY_VOCABULARY_FLAG-source"
  if (moneyVocabularySourceFlag in flags && MONEY_VOCABULARY_FLAG !in flags) {
    return ArgvResult.Usage(
      "Option [--$moneyVocabularySourceFlag] names a provenance source for a money vocabulary file " +
        "that was not supplied. $USAGE",
    )
  }
  return ArgvResult.Ok(
    sources =
      positional.mapIndexed { i, path ->
        val file = File(path)
        SourceFile(file = file, sourceArg = flags[SOURCE_FLAGS[i]] ?: file.path)
      },
    cds =
      cdsArgs.takeIf { it.isNotEmpty() }?.let { group ->
        CdsArgs(
          meritAidCsv = File(group.getValue(CDS_MERIT_FLAG)),
          admissionFactorsCsv = File(group.getValue(CDS_FACTORS_FLAG)),
          deadlinesCsv = File(group.getValue(CDS_DEADLINES_FLAG)),
        )
      },
    ipeds = ipeds,
    sfa = sfa,
    codebooks =
      File(codebooksPath).let { file ->
        SourceFile(file = file, sourceArg = flags[codebooksSourceFlag] ?: file.path)
      },
    subjects =
      flags[SUBJECTS_FLAG]?.let { path ->
        val file = File(path)
        SourceFile(file = file, sourceArg = flags[subjectsSourceFlag] ?: file.path)
      },
    moneyVocabulary =
      flags[MONEY_VOCABULARY_FLAG]?.let { path ->
        val file = File(path)
        SourceFile(file = file, sourceArg = flags[moneyVocabularySourceFlag] ?: file.path)
      },
  )
}

/**
 * The three outcomes of reading ONE optional, all-or-nothing flag group out of
 * [parseArgv]'s flag map. Generic in the sources it yields, so every survey
 * group reads through one type rather than growing a sealed hierarchy of its
 * own.
 */
private sealed interface FlagGroup<out T> {
  data object Absent : FlagGroup<Nothing>

  data class Present<T>(
    val sources: T,
  ) : FlagGroup<T>

  data class Invalid(
    val message: String,
  ) : FlagGroup<Nothing>
}

/**
 * Reads one optional survey group all-or-nothing: presence is judged on
 * [fileFlags] AND [yearFlag] together, so omitting any one of them is a refusal
 * that names exactly which are missing rather than a run that quietly loads a
 * subset with a fabricated year; a dangling `--*-source` names a provenance
 * source for a file that was never supplied; and the year must be plausible,
 * because it is stamped on every row the group writes.
 *
 * ONE reader for every group: the rule is the argv grammar's, not any one
 * survey's, so a second survey cannot arrive with its own refusal wording.
 * [label] names the group in each refusal, and [build] is the only
 * survey-specific part -- it turns the group's files and year into its sources.
 */
private fun <T> parseFlagGroup(
  flags: Map<String, String>,
  label: String,
  fileFlags: List<String>,
  yearFlag: String,
  build: (files: Map<String, SourceFile>, year: Int) -> T,
): FlagGroup<T> {
  val groupFlags = fileFlags + yearFlag
  val given = groupFlags.filter { it in flags }
  if (given.isEmpty()) {
    val danglingSources = fileFlags.map { "$it-source" }.filter { it in flags }
    if (danglingSources.isNotEmpty()) {
      return FlagGroup.Invalid(
        "Option(s) ${danglingSources.map { "--$it" }} name a provenance source for an [$label] file " +
          "that was not supplied. $USAGE",
      )
    }
    return FlagGroup.Absent
  }
  val missing = groupFlags.filterNot { it in flags }
  if (missing.isNotEmpty()) {
    return FlagGroup.Invalid(
      "The [$label] options are all-or-nothing: given ${given.map { "--$it" }}, " +
        "option(s) ${missing.map { "--$it" }} are also required. $USAGE",
    )
  }
  val year = flags.getValue(yearFlag).toIntOrNull()
  if (year == null || year !in SURVEY_YEAR_RANGE) {
    return FlagGroup.Invalid(
      "Option [--$yearFlag] must be a year in [$SURVEY_YEAR_RANGE], " +
        "got [${flags.getValue(yearFlag)}]. $USAGE",
    )
  }
  // Keyed by the FLAG the operator typed, never by position: each group is
  // assembled by name in its own [build], so reordering [fileFlags] -- which is
  // also the argv grammar and the provenance order -- cannot silently load the
  // ADM file as the IC one.
  val files =
    fileFlags.associateWith { flag ->
      val file = File(flags.getValue(flag))
      SourceFile(file = file, sourceArg = flags["$flag-source"] ?: file.path)
    }
  return FlagGroup.Present(build(files, year))
}

/**
 * The IPEDS institutional-characteristics group (RFC 144): the four files and
 * the collection year they were published in.
 */
private fun parseIpedsGroup(flags: Map<String, String>): FlagGroup<IpedsSources> =
  parseFlagGroup(flags, "IPEDS", IPEDS_FILE_FLAGS, SURVEY_YEAR_FLAG) { files, year ->
    IpedsSources(
      hd = files.getValue("hd"),
      ic = files.getValue("ic"),
      adm = files.getValue("adm"),
      completions = files.getValue("completions"),
      icAy = files.getValue("ic-ay"),
      surveyYear = year,
    )
  }

/**
 * The IPEDS SFA group (RFC 162): one file and the START year of its own aid
 * year -- a different number from the IPEDS collection year, which is why it is
 * its own group and not four more members of that one.
 */
private fun parseSfaGroup(flags: Map<String, String>): FlagGroup<SfaSources> =
  parseFlagGroup(flags, "SFA", listOf(SFA_FILE_FLAG), SFA_AID_YEAR_FLAG) { files, year ->
    SfaSources(survey = files.getValue(SFA_FILE_FLAG), aidYearStart = year)
  }

/**
 * Every file the run will read, each paired with the ROLE it fills — the flag
 * the operator typed, minus its punctuation. Pure (it touches no disk) so the
 * pairing is directly testable, unlike [requireExistingFiles], which exits the
 * process.
 */
internal fun namedSources(parsed: ArgvResult.Ok): List<Pair<String, SourceFile>> {
  val scorecard = SOURCE_FLAGS.map { it.removeSuffix("-source") }.zip(parsed.sources)
  val ipeds = parsed.ipeds?.let { IPEDS_FILE_FLAGS.zip(it.files) } ?: emptyList()
  val sfa = parsed.sfa?.let { listOf(SFA_FILE_FLAG to it.survey) } ?: emptyList()
  // One spelling of the CDS file list ([CdsArgs.sources]), so the files this
  // checks for existence are exactly the ones the run digests.
  // Pairing by NAME, on [CdsSources] itself: the previous positional
  // `CDS_FLAGS.zip(sources.files)` had no size check, so a fourth CDS file
  // would have been dropped from the existence check with nothing failing --
  // the same silent-drop this commit removed from `logCdsRun`.
  val cds = parsed.cds?.sources?.namedFiles ?: emptyList()
  val codebooks = listOf(CODEBOOKS_FLAG to parsed.codebooks)
  val subjects = parsed.subjects?.let { listOf(SUBJECTS_FLAG to it) } ?: emptyList()
  val moneyVocabulary = parsed.moneyVocabulary?.let { listOf(MONEY_VOCABULARY_FLAG to it) } ?: emptyList()
  return scorecard + ipeds + sfa + cds + codebooks + subjects + moneyVocabulary
}

/** The filesystem probe, kept out of [parseArgv]: it exits the process, so it
 * is called where that effect is visible. */
private fun requireExistingFiles(parsed: ArgvResult.Ok) {
  for ((role, source) in namedSources(parsed)) {
    if (!source.file.isFile) {
      // The role and the caller's ORIGINAL argument, not just the resolved
      // path: bin/ingest-colleges downloads an s3:// source into a mktemp dir,
      // so the path alone names a file the operator never typed and — with
      // seven candidates — does not say which option failed.
      logger.error(
        "Source file not found [role={}] [path={}] [from={}]",
        role,
        source.file.path,
        source.sourceArg,
      )
      kotlin.system.exitProcess(2)
    }
  }
}

/**
 * Operational entry for the re-runnable college ingester: the College Scorecard
 * pair plus curated aliases (RFC 67, provenance + aliases RFC 139) and,
 * optionally, the three CDS admissions seed files (RFC 140). Reads the DB config
 * from the classpath `.conf` files (no new `college.conf`), takes the three
 * source paths from [args] (all three required — `bin/ingest-colleges` supplies
 * the repo default aliases path, so a missing aliases arg here is a loud usage
 * error, never a silently fabricated default), runs
 * [CollegeScorecardLoader.ingest] — which, when the CDS group was passed, loads
 * the seed as one of its phases, before provenance (RFC 148 D10) — prints the
 * human change summary, and reports the CDS numbers the run returned.
 *
 * Failure contract (RFC 139): a CSV missing a required column — or any other
 * failure — aborts with a non-zero exit and NO `college_index_build` row; the
 * build row and summary are success-path only. A failure AFTER a phase has
 * already committed cannot be rolled back, so it is reported loudly instead:
 * `PARTIAL INGEST` names the committed phases and says provenance was not
 * recorded (the ingest is idempotent — re-running completes it).
 *
 * The optional `--institution-source= / --fields-source= / --aliases-source=`
 * flags carry each source's ORIGINAL caller argument (a local path or `s3://`
 * URL) as explicit argv: `bin/ingest-colleges` downloads remote args to a
 * scratch path before the JVM sees them and passes the originals here, so the
 * provenance row records what the caller actually named. A flag left off means
 * the positional path IS the original argument (a direct local invocation); a
 * repeated flag, or one with an empty value, is a usage error (exit 2) — never
 * silently last-wins or defaulted, because the provenance row would then record
 * something the caller did not ask for.
 *
 * This is also the single owner of CDS run reporting: [CdsSeedLoader] returns
 * the per-table numbers and the coverage report through the ingest report, and
 * they are rendered here once — including the identities of the seed UNITIDs
 * that matched no college, at INFO, so recovering them never needs a second
 * ingest.
 *
 * The optional IPEDS group (`--hd/--ic/--adm/--completions/--survey-year`, RFC
 * 144) extends the same run rather than adding a second command (gate-2 D19):
 * given none of the five the run is exactly the RFC 139 one, and given any of
 * them all five are required. Each IPEDS file has the same `--*-source` partner
 * carrying its original argument, and `--survey-year` is explicit because a
 * year derived from a filename is a silent coercion.
 */
fun main(args: Array<String>) {
  val parsed =
    when (val result = parseArgv(args)) {
      is ArgvResult.Usage -> {
        logger.error(result.message)
        kotlin.system.exitProcess(2)
      }

      is ArgvResult.Ok -> {
        result
      }
    }
  requireExistingFiles(parsed)
  val (institution, fields, aliases) = parsed.sources
  val database = openDatabase()

  try {
    val report =
      runBlocking {
        CollegeScorecardLoader(database).ingest(
          institution = institution,
          fields = fields,
          aliasesFile = aliases,
          ipeds = parsed.ipeds,
          sfa = parsed.sfa,
          cds = parsed.cds?.sources,
          codebooks = parsed.codebooks,
          subjects = parsed.subjects,
          moneyVocabulary = parsed.moneyVocabulary,
        )
      }
    println(report.humanSummary())
    // The canonical-money summary (RFC 158, P11) goes to the LOG -- stderr,
    // per bin/ conventions -- naming the row counts and the per-status
    // breakdown, keyed by our status slugs. stdout keeps exactly the lines it
    // printed before this phase existed.
    logger.info(
      "canonical money: [{}] price_figures [{}] by source [{}]; [{}] cohort_money_stats [{}]; " +
        "[{}] cohort_population_counts [{}]; IPEDS SFA: [{}] staged cell(s) over [{}] college(s); " +
        "[{}] college(s), [{}] malformed row(s), [{}] row(s) without " +
        "a college, [{}] row(s) without a CONTROL (control-keyed cells skipped)",
      report.canonicalMoney.priceFigureRows,
      report.canonicalMoney.priceFigureStatusCounts.mapKeys { it.key.value },
      report.canonicalMoney.priceFigureSourceCounts.mapKeys { it.key.value },
      report.canonicalMoney.cohortMoneyStatRows,
      report.canonicalMoney.cohortMoneyStatStatusCounts.mapKeys { it.key.value },
      report.canonicalMoney.cohortPopulationCountRows,
      report.canonicalMoney.cohortPopulationCountStatusCounts.mapKeys { it.key.value },
      report.canonicalMoney.sfaCellsRead,
      report.canonicalMoney.sfaCollegesMatched,
      report.canonicalMoney.collegesMatched,
      report.canonicalMoney.rowsMalformed,
      report.canonicalMoney.rowsWithoutCollege,
      report.canonicalMoney.rowsWithoutControl,
    )
    val transientSkips =
      report.colleges.transientSkips + report.programs.transientSkips +
        (report.ipeds?.let { it.attributes.transientSkips + it.census.transientSkips + it.charges.transientSkips } ?: 0)
    if (transientSkips > 0) {
      logger.warn(
        "[{}] row(s) skipped on transient faults; re-running the ingest may recover them",
        transientSkips,
      )
    }
    // The CDS load itself ran INSIDE the ingest, before the provenance phase
    // (RFC 148 D10): this is the reporting half only, and it reads the result
    // the run recorded rather than performing a second, unrecorded load.
    report.cds?.let(::logCdsRun)
  } catch (e: MissingSourceColumnsException) {
    logger.error(
      "Ingest aborted before any write: source [{}] (from [{}]) is missing required column(s) [{}]",
      e.fileName,
      e.sourceArg,
      e.missing,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CdsSeedLoader.FormatException) {
    // Reached only from the up-front header assertion, which runs before the
    // first phase commits: a defect found once a phase HAS committed arrives as
    // the PartialIngestException below. So this abort can state the write
    // state, exactly as its siblings do.
    logger.error("Ingest aborted before any write: CDS seed defect [{}]", e.defect, e)
    kotlin.system.exitProcess(1)
  } catch (e: CodebookLoader.InvalidCodebookFileException) {
    logger.error(
      "Ingest aborted before any write: generated codebook [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CodebookLoader.SentinelAsCodeException) {
    logger.error(
      "Ingest aborted: codebook domain [{}] publishes its own null sentinel(s) {} as codes",
      e.domain,
      e.sentinels,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CodebookLoader.ReferencedCodebookRowException) {
    logger.error(
      "Ingest aborted: codebook domain [{}] dropped row [{}] (code [{}]) that is still referenced by {}",
      e.domain,
      e.key,
      e.code,
      e.references,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: MoneyVocabularyLoader.InvalidFileException) {
    logger.error(
      "Ingest aborted before any write: money vocabulary [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: SubjectLoader.InvalidFileException) {
    logger.error(
      "Ingest aborted before any write: subject taxonomy [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: SubjectLoader.UnmatchedCipPrefixException) {
    // NOT "before any write": this one is found inside the `subjects` phase,
    // against the CIP vocabulary the `codebooks` phase just committed. The
    // taxonomy itself is untouched — the check runs before the first subject
    // upsert — and the run reaches no build row.
    logger.error(
      "Ingest aborted: subject taxonomy [{}] has {} cip_prefix value(s) matching no cip_codes row: {}",
      e.fileName,
      e.unmatched.size,
      e.unmatched.joinToString(", ") { (slug, prefix) -> "[$slug] -> [$prefix]" },
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CollegeScorecardLoader.InvalidAliasFileException) {
    logger.error(
      "Ingest aborted before any write: curated aliases file [{}] is invalid: [{}]",
      e.fileName,
      e.detail,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: CollegeScorecardLoader.EmptyCodebookReferenceTablesException) {
    // NOT "before any write": the `codebooks` and `subjects` phases run BEFORE
    // the check and have already committed, so the phases that landed are named
    // exactly as the PARTIAL INGEST arm below names them. Without this arm the
    // one precondition 0067 added escaped as a bare stack trace while every
    // sibling refusal got a structured line.
    logger.error(
      "Ingest aborted before the institutions phase: codebook reference table(s) {} are EMPTY after " +
        "codebook [{}]; phases {} COMMITTED, no college_index_build row was written",
      e.emptyTables,
      e.codebookPath,
      e.committedPhases,
      e,
    )
    kotlin.system.exitProcess(1)
  } catch (e: PartialIngestException) {
    logger.error(
      "PARTIAL INGEST — phase [{}] FAILED, phases {} COMMITTED, no college_index_build row was written, " +
        "provenance was NOT recorded; re-run the ingest to complete it",
      e.failedPhase,
      e.committedPhases,
      e,
    )
    kotlin.system.exitProcess(1)
  } finally {
    database.close()
  }
}

private fun openDatabase(): Database {
  val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
  return Database(DatabaseConfig.from(config).getOrThrow())
}

private fun logCdsRun(result: CdsSeedLoader.LoadResult) {
  // Each summary carries its own table, so the name printed beside a count is
  // that count's name by construction -- never a positional pairing that a
  // reorder would quietly invert.
  for ((table, summary) in result.tableSummaries) {
    logCdsTable(table.logLabel, summary)
  }
  logCdsCoverage(result.coverage)
}

private fun logCdsTable(
  table: String,
  summary: CdsSeedLoader.TableSummary,
) {
  logger.info(
    "CDS ingest [{}]: [upserted={}] [changed={}] [unchanged={}] [skipped={}]",
    table,
    summary.upserted,
    summary.changed,
    summary.unchanged,
    summary.skipped,
  )
  if (summary.unmatchedIpedsUnitIds.isEmpty()) return
  logger.info(
    "CDS ingest [{}]: [skipped={}] seed rows have no college [ipeds_unit_ids={}]",
    table,
    summary.skipped,
    summary.unmatchedIpedsUnitIds,
  )
}

/**
 * The launch-set coverage report (RFC 140), emitted twice on purpose: as log
 * properties, so a run's D8 numbers can be read mechanically out of the JSON log
 * stream like every other CDS run number, and as the plain multi-line block, so
 * an operator run still ends with the figures in a readable shape.
 */
private fun logCdsCoverage(coverage: CdsCoverage) {
  logger.info(
    "CDS coverage: [launch_set={}] [merit_aid={}] [admission_factors={}] " +
      "[deadlines_flags={}] [deadlines_with_date={}] [student_listed_missing={}]",
    coverage.launchSetCount,
    coverage.meritAidCount,
    coverage.admissionFactorsCount,
    coverage.deadlinesFlagsCount,
    coverage.deadlinesWithDateCount,
    coverage.studentListedMissing,
  )
  println(CdsSeedLoader.render(coverage))
}
