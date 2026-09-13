package ed.unicoach.coaching

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Checked at ingest, never at render" is a TESTED property, not a paragraph in
 * an RFC (RFC 200 D1).
 *
 * RFC 200 records whether a college's own Common Data Set file is still served,
 * and it records it in `db/seed/cds/source-url-status.csv` at ingest, by an
 * operator running `bin/cds-link-check`. The tempting alternative -- ask the
 * school's web server while the page renders -- would make a family's Family
 * Cost Report as slow as the slowest university in their list, and would fail
 * their report when a school's site is down. There is no network call on the
 * READ or RENDER path, and that is asserted here rather than trusted.
 *
 * A SOURCE SWEEP, because the property is an absence: there is no call to
 * observe, and a runtime test would prove only that the fixtures it happened to
 * render made none.
 */
class RenderPathMakesNoNetworkCallTest {
  /**
   * The read and render path, whole: the coaching read models that build a
   * citation, the daos that read the recorded verdict, and the pages that
   * render it.
   *
   * Cross-module `File("../...")` is this repo's own shape for a sweep whose
   * subject lives elsewhere (`MoneyAttributionNamesNoPublisherTest` reads
   * `../public-web/src/main`, `ForbiddenCostArithmeticTest` reads
   * `../db/.../dao`). Gradle runs each module's tests from its own directory,
   * so the relative paths are stable.
   */
  private val sweptDirectories =
    listOf(
      File("src/main/kotlin/ed/unicoach/coaching"),
      File("../public-web/src/main"),
      File("../db/src/main/kotlin/ed/unicoach/db/dao"),
    )

  /**
   * A file that DOES speak http, outside the swept path -- the positive control.
   *
   * Without it this whole test could pass because the matcher matches nothing
   * anywhere: a sweep for an absence has to be shown capable of finding the
   * thing it is asserting is absent.
   */
  private val networkedControl = File("../chat/src/main/kotlin/ed/unicoach/chat/KtorAnthropicStreamTransport.kt")

  /**
   * The CLIENTS, named as the types and packages that open a connection --
   * every one this repo could plausibly reach for, not the one it uses today.
   *
   * Named as the THING (a client type, a transport package) rather than one
   * spelling of one call, for brief 0009 P7's reason: a ban on `client.get(`
   * is satisfied by `client.post(`. `io.ktor.client` catches every ktor client
   * API at the import, whatever the call is spelled.
   */
  private val clientMarkers =
    listOf(
      "io.ktor.client",
      "HttpClient",
      "java.net.http",
      "HttpURLConnection",
      "URLConnection",
      "okhttp",
      "OkHttp",
      "java.net.Socket",
      "URL(",
      "openStream(",
      "openConnection(",
    )

  /** Kotlin source under [directory], as CODE: comment lines are prose about the rule, not the rule being broken. */
  private fun codeFilesUnder(directory: File): List<Pair<File, String>> {
    assertTrue(directory.isDirectory, "the sweep must have a directory to read: [${directory.absolutePath}]")
    return directory
      .walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .map { file ->
        file to
          file
            .readLines()
            .filterNot { line ->
              val trimmed = line.trimStart()
              trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            }.joinToString("\n")
      }.toList()
  }

  @Test
  fun `no http client is named anywhere on the read or render path`() {
    val offenders =
      sweptDirectories
        .flatMap { codeFilesUnder(it) }
        .flatMap { (file, code) ->
          clientMarkers.filter { code.contains(it) }.map { "${file.path} names [$it]" }
        }

    assertEquals(
      emptyList(),
      offenders,
      "a family's report must never wait on a college's web server -- the verdict is recorded at ingest",
    )
  }

  /**
   * THE SWEEP IS NOT VACUOUS, proved two ways.
   *
   * A sweep that read nothing, or a matcher that matches nothing, would pass
   * the assertion above in silence. So: the files it claims to read are named
   * and asserted present, and the same matcher is run over a file that really
   * does open connections and is required to fire.
   */
  @Test
  fun `the sweep reads the files it claims to, with a matcher that can fire`() {
    val swept = sweptDirectories.flatMap { codeFilesUnder(it) }

    // The three surfaces RFC 200 touches, by name: the type holding the url,
    // the dao reading the recorded verdict, and the page rendering the anchor.
    listOf(
      "CdsCitation.kt",
      "AidPolicyWire.kt",
      "AidPolicyDao.kt",
      "CostReportPage.kt",
    ).forEach { name ->
      assertTrue(swept.any { it.first.name == name }, "the sweep did not read [$name]; it proves nothing about it")
    }
    // And it read them as CONTENT, not as empty strings.
    assertTrue(
      swept.single { it.first.name == "CostReportPage.kt" }.second.contains("private fun FlowContent.sourceLink("),
      "the sweep read [CostReportPage.kt] without its renderer in it",
    )

    // THE POSITIVE CONTROL: the same matcher, over a file that really does
    // speak http. If this stops firing, the matcher has stopped matching and
    // the assertion above means nothing.
    assertTrue(networkedControl.isFile, "the control file must exist: [${networkedControl.absolutePath}]")
    val controlCode = networkedControl.readText()
    assertTrue(
      clientMarkers.count { controlCode.contains(it) } >= 2,
      "the matcher failed to find an http client in a file that is nothing but one: [${networkedControl.name}]",
    )
  }
}
