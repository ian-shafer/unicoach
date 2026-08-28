package ed.unicoach.college

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ingest CLI's argv grammar (RFC 139). Every refusal here is a previously
 * silent coercion: a repeated `--*-source` last-won, an empty one fell back to
 * the scratch path, and both would have written provenance the caller never
 * asked for.
 */
class IngestApplicationArgvTest {
  private val positional = arrayOf("institution.csv", "fields.csv", "aliases.json")

  private fun ok(vararg args: String): ArgvResult.Ok {
    val result = parseArgv(arrayOf(*positional, *args))
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
}
