package ed.unicoach.college

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ingest CLI's argv grammar (RFC 139). Every refusal here is a previously
 * silent coercion: a repeated `--*-source` last-won, an empty one fell back to
 * the scratch path, and both would have written provenance the caller never
 * asked for.
 */
class IngestApplicationArgvTest {
  private val positional = arrayOf("institution.csv", "fields.csv", "aliases.json")

  /**
   * The codebook flag every `ok(...)` call supplies unless it names its own.
   * `--codebooks` is REQUIRED since migration 0067 — `colleges.state` and
   * `colleges.locale` reference the codebook tables — so a valid argv always
   * carries one, and a test about some OTHER part of the grammar should not
   * have to restate that.
   */
  private val codebooksFlag = "--codebooks=db/data/codebooks.json"

  private fun ok(vararg args: String): ArgvResult.Ok {
    val withCodebooks = if (args.any { it.startsWith("--codebooks=") }) args else arrayOf(codebooksFlag, *args)
    val result = parseArgv(arrayOf(*positional, *withCodebooks))
    assertTrue(result is ArgvResult.Ok, "expected Ok, got $result")
    return result
  }

  private fun usage(vararg args: String): String {
    val result = parseArgv(arrayOf(*args))
    assertTrue(result is ArgvResult.Usage, "expected Usage, got $result")
    return result.message
  }

  @Test
  fun `an omitted flag means the positional path IS the original argument`() {
    val sources = ok().sources
    assertEquals(listOf("institution.csv", "fields.csv", "aliases.json"), sources.map { it.sourceArg })
  }

  @Test
  fun `each flag carries its own source's original argument`() {
    val sources =
      ok(
        "--institution-source=s3://snap/institution.csv",
        "--aliases-source=s3://snap/aliases.json",
      ).sources
    assertEquals(
      listOf("s3://snap/institution.csv", "fields.csv", "s3://snap/aliases.json"),
      sources.map { it.sourceArg },
    )
  }

  @Test
  fun `a repeated flag is refused, never last-wins`() {
    val message = usage(*positional, "--fields-source=a", "--fields-source=b")
    assertTrue(message.contains("--fields-source"), message)
    assertTrue(message.contains("more than once"), message)
  }

  @Test
  fun `an empty flag value is refused, never treated as an absent flag`() {
    val message = usage(*positional, "--institution-source=")
    assertTrue(message.contains("--institution-source"), message)
    assertTrue(message.contains("non-empty"), message)
  }

  @Test
  fun `an unknown or valueless flag is refused`() {
    assertTrue(usage(*positional, "--institution-src=x").contains("Unknown or malformed"))
    assertTrue(usage(*positional, "--fields-source").contains("Unknown or malformed"))
  }

  @Test
  fun `the positional count is exactly the number of sources`() {
    assertTrue(usage("institution.csv", "fields.csv").isNotEmpty())
    assertTrue(usage(*positional, "extra.json").isNotEmpty())
  }

  // ---------------------------------------------------------------------------
  // The optional IPEDS group (RFC 144): all-or-nothing, never a partial load
  // ---------------------------------------------------------------------------

  private val ipedsGroup =
    arrayOf(
      "--hd=HD2023.csv",
      "--ic=IC2023.csv",
      "--adm=adm2023.csv",
      "--completions=C2023_a.csv",
      "--ic-ay=ic2023_ay.csv",
      "--survey-year=2023",
    )

  @Test
  fun `a run with no IPEDS flags parses exactly as before, with no IPEDS group`() {
    assertNull(ok().ipeds, "absent must stay absent, never an empty-but-present group")
  }

  @Test
  fun `the full IPEDS group parses into the five files and the explicit survey year`() {
    val ipeds = assertNotNull(ok(*ipedsGroup).ipeds)
    assertEquals(
      listOf("HD2023.csv", "IC2023.csv", "adm2023.csv", "C2023_a.csv", "ic2023_ay.csv"),
      ipeds.files.map { it.file.path },
    )
    assertEquals("ic2023_ay.csv", ipeds.icAy.file.path, "IC_AY is the group's fifth member (RFC 161)")
    assertEquals(2023, ipeds.surveyYear)
    // With no --*-source partners, each positional path IS the original argument.
    assertEquals(
      listOf("HD2023.csv", "IC2023.csv", "adm2023.csv", "C2023_a.csv", "ic2023_ay.csv"),
      ipeds.files.map { it.sourceArg },
    )
  }

  @Test
  fun `each IPEDS file carries its own original argument when a source flag is given`() {
    val ipeds =
      assertNotNull(
        ok(
          *ipedsGroup,
          "--hd-source=s3://snap/HD2023.csv",
          "--completions-source=s3://snap/C2023_a.csv",
          "--ic-ay-source=s3://snap/ic2023_ay.csv",
        ).ipeds,
      )
    assertEquals(
      listOf(
        "s3://snap/HD2023.csv",
        "IC2023.csv",
        "adm2023.csv",
        "s3://snap/C2023_a.csv",
        "s3://snap/ic2023_ay.csv",
      ),
      ipeds.files.map { it.sourceArg },
    )
  }

  @Test
  fun `a partial IPEDS group is refused, naming what is missing`() {
    val message = usage(*positional, "--hd=HD2023.csv")
    assertTrue(message.contains("all-or-nothing"), message)
    assertTrue(message.contains("--ic"), message)
    assertTrue(message.contains("--ic-ay"), message)
    assertTrue(message.contains("--survey-year"), message)
  }

  @Test
  fun `omitting only IC_AY is refused, never loaded as four fifths of the group`() {
    // The RFC 161 addition is inside the same all-or-nothing group, not beside
    // it: a run that quietly skipped the charges file would leave the canonical
    // fill with no IPEDS source and silently serve the Scorecard's collapsed
    // in-state price again.
    val message =
      usage(
        *positional,
        "--hd=HD2023.csv",
        "--ic=IC2023.csv",
        "--adm=adm.csv",
        "--completions=CA.csv",
        "--survey-year=2023",
      )
    assertTrue(message.contains("all-or-nothing"), message)
    assertTrue(message.contains("--ic-ay"), message)
  }

  @Test
  fun `an IC_AY source flag without its file is refused, never silently ignored`() {
    val message = usage(*positional, "--ic-ay-source=s3://snap/ic2023_ay.csv")
    assertTrue(message.contains("--ic-ay-source"), message)
    assertTrue(message.contains("was not supplied"), message)
  }

  @Test
  fun `omitting only the survey year is refused, never derived from a filename`() {
    val message =
      usage(
        *positional,
        "--hd=HD2023.csv",
        "--ic=IC2023.csv",
        "--adm=adm.csv",
        "--completions=CA.csv",
        "--ic-ay=ic_ay.csv",
      )
    assertTrue(message.contains("--survey-year"), message)
  }

  @Test
  fun `a non-numeric or implausible survey year is refused`() {
    assertTrue(usage(*positional, *ipedsGroupWithYear("twenty-23")).contains("--survey-year"))
    assertTrue(usage(*positional, *ipedsGroupWithYear("23")).contains("--survey-year"))
  }

  @Test
  fun `a repeated or blank IPEDS flag is refused like any other`() {
    assertTrue(usage(*positional, *ipedsGroup, "--hd=other.csv").contains("more than once"))
    assertTrue(usage(*positional, "--hd=").contains("non-empty"))
  }

  @Test
  fun `an IPEDS source flag without its file is refused, never silently ignored`() {
    val message = usage(*positional, "--hd-source=s3://snap/HD2023.csv")
    assertTrue(message.contains("--hd-source"), message)
    assertTrue(message.contains("was not supplied"), message)
  }

  // ---------------------------------------------------------------------------
  // The existence probe's payload: role + the caller's ORIGINAL argument
  // ---------------------------------------------------------------------------

  @Test
  fun `every source is paired with its role and the caller's original argument, not just a scratch path`() {
    // bin/ingest-colleges downloads an s3:// source into a mktemp dir, so a
    // "not found" naming only the resolved path names a file the operator never
    // typed — and with seven candidates it does not say which option failed.
    val parsed =
      ok(
        *ipedsGroup,
        "--hd-source=s3://snap/HD2023.csv",
        "--institution-source=s3://snap/institution.csv",
      )
    val named = namedSources(parsed)
    assertEquals(
      listOf("institution", "fields", "aliases", "hd", "ic", "adm", "completions", "ic-ay", "codebooks"),
      named.map { it.first },
      "every file the run reads is named by the flag the operator typed",
    )
    assertEquals("s3://snap/institution.csv", named.first { it.first == "institution" }.second.sourceArg)
    assertEquals("s3://snap/HD2023.csv", named.first { it.first == "hd" }.second.sourceArg)
    assertEquals(
      "HD2023.csv",
      named
        .first { it.first == "hd" }
        .second.file.path,
    )
  }

  @Test
  fun `a run with no IPEDS group names only the Scorecard sources and the codebook`() {
    assertEquals(listOf("institution", "fields", "aliases", "codebooks"), namedSources(ok()).map { it.first })
  }

  // ---------------------------------------------------------------------------
  // The generated codebook (RFC 147): REQUIRED since 0067, with its own
  // provenance partner
  // ---------------------------------------------------------------------------

  @Test
  fun `a run with no codebook flag is refused, not defaulted`() {
    val message = usage(*positional)
    assertTrue(message.contains("--codebooks"), message)
    assertTrue(message.contains("required"), message)
    // And the refusal states WHY, so the caller is not left guessing which
    // repo file the binary wanted: `bin/ingest-colleges` decides the path,
    // this layer only insists there is one.
    assertTrue(message.contains("colleges.state"), message)
  }

  @Test
  fun `the codebook flag carries the path, and its source flag the original argument`() {
    assertEquals("db/data/codebooks.json", ok("--codebooks=db/data/codebooks.json").codebooks.file.path)
    // Without a partner, the path IS the original argument.
    assertEquals("db/data/codebooks.json", ok("--codebooks=db/data/codebooks.json").codebooks.sourceArg)
    val remote = ok("--codebooks=/tmp/scratch/codebooks.json", "--codebooks-source=s3://snap/codebooks.json")
    assertEquals("/tmp/scratch/codebooks.json", remote.codebooks.file.path)
    assertEquals("s3://snap/codebooks.json", remote.codebooks.sourceArg)
  }

  @Test
  fun `a codebook source flag without its file is refused as a missing codebook`() {
    // Before 0067 this was its own refusal ("a provenance source for a codebook
    // that was not supplied"). It cannot be any more: the codebook itself is
    // required, so the missing-file refusal fires first and says the same thing
    // more directly. One refusal, not two spellings of one.
    val message = usage(*positional, "--codebooks-source=s3://snap/codebooks.json")
    assertTrue(message.contains("--codebooks"), message)
    assertTrue(message.contains("required"), message)
  }

  @Test
  fun `a repeated or blank codebook flag is refused like any other`() {
    assertTrue(usage(*positional, "--codebooks=a.json", "--codebooks=b.json").contains("more than once"))
    assertTrue(usage(*positional, "--codebooks=").contains("non-empty"))
  }

  @Test
  fun `the codebook joins the existence probe under its own role`() {
    val named = namedSources(ok("--codebooks=db/data/codebooks.json"))
    assertEquals(listOf("institution", "fields", "aliases", "codebooks"), named.map { it.first })
  }

  // ---------------------------------------------------------------------------
  // The authored subject taxonomy (RFC 150): the same shape, deliberately
  // ---------------------------------------------------------------------------

  @Test
  fun `a run with no subjects flag has no subject source`() {
    assertNull(ok().subjects, "absent must stay absent — no fabricated repo default at this layer")
  }

  @Test
  fun `the subjects flag carries the path, and its source flag the original argument`() {
    assertEquals("db/data/subjects.json", ok("--subjects=db/data/subjects.json").subjects?.file?.path)
    assertEquals("db/data/subjects.json", ok("--subjects=db/data/subjects.json").subjects?.sourceArg)
    val remote = ok("--subjects=/tmp/scratch/subjects.json", "--subjects-source=s3://snap/subjects.json")
    assertEquals("/tmp/scratch/subjects.json", remote.subjects?.file?.path)
    assertEquals("s3://snap/subjects.json", remote.subjects?.sourceArg)
  }

  @Test
  fun `a subjects source flag without its file is refused, never silently ignored`() {
    val message = usage(*positional, codebooksFlag, "--subjects-source=s3://snap/subjects.json")
    assertTrue(message.contains("--subjects-source"), message)
    assertTrue(message.contains("was not supplied"), message)
  }

  @Test
  fun `a repeated or blank subjects flag is refused like any other`() {
    assertTrue(usage(*positional, "--subjects=a.json", "--subjects=b.json").contains("more than once"))
    assertTrue(usage(*positional, "--subjects=").contains("non-empty"))
  }

  @Test
  fun `the subject file joins the existence probe under its own role`() {
    val named = namedSources(ok("--codebooks=db/data/codebooks.json", "--subjects=db/data/subjects.json"))
    assertEquals(listOf("institution", "fields", "aliases", "codebooks", "subjects"), named.map { it.first })
  }

  // ---------------------------------------------------------------------------
  // The authored money vocabulary (RFC 158): the subjects shape, deliberately
  // ---------------------------------------------------------------------------

  @Test
  fun `a run with no money-vocabulary flag has no vocabulary source`() {
    assertNull(ok().moneyVocabulary, "absent must stay absent — no fabricated repo default at this layer")
  }

  @Test
  fun `the money-vocabulary flag carries the path, and its source flag the original argument`() {
    val remote =
      ok(
        "--money-vocabulary=/tmp/scratch/money-vocabulary.json",
        "--money-vocabulary-source=s3://snap/money-vocabulary.json",
      )
    assertEquals("/tmp/scratch/money-vocabulary.json", remote.moneyVocabulary?.file?.path)
    assertEquals("s3://snap/money-vocabulary.json", remote.moneyVocabulary?.sourceArg)
    assertEquals(
      "db/data/money-vocabulary.json",
      ok("--money-vocabulary=db/data/money-vocabulary.json").moneyVocabulary?.sourceArg,
    )
  }

  @Test
  fun `a money-vocabulary source flag without its file is refused, never silently ignored`() {
    val message = usage(*positional, codebooksFlag, "--money-vocabulary-source=s3://snap/money-vocabulary.json")
    assertTrue(message.contains("--money-vocabulary-source"), message)
    assertTrue(message.contains("was not supplied"), message)
  }

  @Test
  fun `a repeated or blank money-vocabulary flag is refused like any other`() {
    assertTrue(usage(*positional, "--money-vocabulary=a.json", "--money-vocabulary=b.json").contains("more than once"))
    assertTrue(usage(*positional, "--money-vocabulary=").contains("non-empty"))
  }

  @Test
  fun `the vocabulary file joins the existence probe under its own role`() {
    val named =
      namedSources(ok("--codebooks=db/data/codebooks.json", "--money-vocabulary=db/data/money-vocabulary.json"))
    assertEquals(listOf("institution", "fields", "aliases", "codebooks", "money-vocabulary"), named.map { it.first })
  }

  // ---------------------------------------------------------------------------
  // The optional SFA group (RFC 162): the IPEDS group's shape, its own year
  // ---------------------------------------------------------------------------

  private val sfaGroup = arrayOf("--sfa=sfa2223.csv", "--sfa-aid-year=2022")

  @Test
  fun `a run with no SFA flags parses exactly as before, with no SFA group`() {
    assertNull(ok().sfa, "absent must stay absent, never an empty-but-present group")
  }

  @Test
  fun `the full SFA group parses into the file and the explicit aid year`() {
    val sfa = assertNotNull(ok(*sfaGroup).sfa)
    assertEquals("sfa2223.csv", sfa.survey.file.path)
    // The START year of the FILE's own aid year, taken as given: SFA2223 is
    // 2022-23, and nothing derives that from the filename.
    assertEquals(2022, sfa.aidYearStart)
    assertEquals("sfa2223.csv", sfa.survey.sourceArg)
    val remote = assertNotNull(ok(*sfaGroup, "--sfa-source=s3://snap/sfa2223.csv").sfa)
    assertEquals("sfa2223.csv", remote.survey.file.path)
    assertEquals("s3://snap/sfa2223.csv", remote.survey.sourceArg)
  }

  @Test
  fun `a partial SFA group is refused, naming what is missing`() {
    val missingYear = usage(*positional, "--sfa=sfa2223.csv")
    assertTrue(missingYear.contains("all-or-nothing"), missingYear)
    assertTrue(missingYear.contains("--sfa-aid-year"), missingYear)
    // And the other way round: the year without the file is just as partial.
    val missingFile = usage(*positional, "--sfa-aid-year=2022")
    assertTrue(missingFile.contains("all-or-nothing"), missingFile)
    assertTrue(missingFile.contains("--sfa"), missingFile)
  }

  @Test
  fun `an SFA source flag without its file is refused, never silently ignored`() {
    val message = usage(*positional, codebooksFlag, "--sfa-source=s3://snap/sfa2223.csv")
    assertTrue(message.contains("--sfa-source"), message)
    assertTrue(message.contains("was not supplied"), message)
  }

  @Test
  fun `a non-numeric or implausible SFA aid year is refused`() {
    // The year is stamped on every staged cell and every cell's own aid year
    // is computed from it, so a garbage value must never reach the loader.
    assertTrue(usage(*positional, "--sfa=sfa2223.csv", "--sfa-aid-year=twenty-22").contains("--sfa-aid-year"))
    assertTrue(usage(*positional, "--sfa=sfa2223.csv", "--sfa-aid-year=22").contains("--sfa-aid-year"))
  }

  @Test
  fun `a repeated or blank SFA flag is refused like any other`() {
    assertTrue(usage(*positional, *sfaGroup, "--sfa=other.csv").contains("more than once"))
    assertTrue(usage(*positional, *sfaGroup, "--sfa-aid-year=2021").contains("more than once"))
    assertTrue(usage(*positional, "--sfa=").contains("non-empty"))
    assertTrue(usage(*positional, "--sfa-aid-year=").contains("non-empty"))
  }

  @Test
  fun `the SFA file joins the existence probe under its own role`() {
    val named = namedSources(ok(*sfaGroup))
    assertEquals(listOf("institution", "fields", "aliases", "sfa", "codebooks"), named.map { it.first })
  }

  private fun ipedsGroupWithYear(year: String): Array<String> =
    arrayOf(
      "--hd=HD.csv",
      "--ic=IC.csv",
      "--adm=adm.csv",
      "--completions=CA.csv",
      "--ic-ay=ic_ay.csv",
      "--survey-year=$year",
    )
}
