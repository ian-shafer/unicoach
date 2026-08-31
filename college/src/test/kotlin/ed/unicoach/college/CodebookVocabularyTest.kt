package ed.unicoach.college

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The three Kotlin enums that carry a published closed set — [NcesLocaleType],
 * [NcesLocaleDetail] and [AdmissionTestPolicy] — held to `db/data/codebooks.json`
 * itself (RFC 147).
 *
 * Only these three are enums. Everything else the codebook publishes is looked
 * up in the LOADED reference tables through [Codebook], because it is either
 * large (1,710 CIP codes), or a vocabulary the publisher revises (regions,
 * Carnegie classes, conferences). An enum is justified exactly where the set is
 * small AND structural — the two halves of a locale label, and a three-valued
 * test policy — and this test is the price of that judgement: the enum is only
 * safe while it still MATCHES, so the file is the assertion, not a comment
 * saying it was checked once.
 *
 * The file is read directly rather than through the loader or the database:
 * this is a statement about the SOURCE OF TRUTH and the Kotlin declarations,
 * with nothing in between that could make them agree.
 */
class CodebookVocabularyTest {
  private val codebook: JsonObject =
    Json.parseToJsonElement(CodebookFixture.COMMITTED_FILE.readText()).jsonObject

  private fun codes(domain: String) = codebook[domain]!!.jsonObject["codes"]!!.jsonArray.map { it.jsonObject }

  @Test
  fun `NcesLocaleType is exactly the type words the file publishes`() {
    val published = codes("nces_locale").map { it["type"]!!.jsonPrimitive.content }.distinct().sorted()
    assertEquals(published, NcesLocaleType.WORDS.sorted())
    assertTrue(published.isNotEmpty(), "the domain must actually publish types, or this test is vacuous")
  }

  @Test
  fun `NcesLocaleDetail is exactly the detail words the file publishes`() {
    val published = codes("nces_locale").map { it["detail"]!!.jsonPrimitive.content }.distinct().sorted()
    assertEquals(published, NcesLocaleDetail.WORDS.sorted())
    assertTrue(published.isNotEmpty(), "the domain must actually publish details, or this test is vacuous")
  }

  @Test
  fun `every published locale is one type-detail pairing, and only the published pairings exist`() {
    // The enums are the two AXES; the pairings are data. A cross product would
    // be 24 locales and the publisher defines 12, which is exactly why
    // CollegeQueryVocabulary refuses an unpublished pairing instead of
    // returning an empty match for it.
    val pairs =
      codes("nces_locale").map {
        NcesLocaleType.fromWord(it["type"]!!.jsonPrimitive.content) to
          NcesLocaleDetail.fromWord(it["detail"]!!.jsonPrimitive.content)
      }
    assertTrue(pairs.none { it.first == null || it.second == null }, "every published half must be named by an enum")
    assertEquals(pairs.size, pairs.distinct().size, "a pairing must identify exactly one locale")
    assertTrue(
      pairs.size < NcesLocaleType.entries.size * NcesLocaleDetail.entries.size,
      "the pairings are a subset of the cross product; if they were not, the refusal would be dead code",
    )
  }

  @Test
  fun `AdmissionTestPolicy is exactly the slug-to-code mapping the file publishes`() {
    // Not 1/2/3: the published codes are 1, 3 and 5. Writing them down from the
    // file is the whole point -- an enum that guessed consecutive codes would
    // silently mis-read every stored test policy.
    val published =
      codes("admission_test_policy").associate {
        it["slug"]!!.jsonPrimitive.content to
          it["code"]!!.jsonPrimitive.content.toInt()
      }
    assertEquals(published, AdmissionTestPolicy.entries.associate { it.slug to it.code })
  }

  @Test
  fun `a Codebook built from the file resolves every published word both ways`() {
    // The round trip the boundary depends on, over the REAL vocabulary: slug ->
    // code -> slug for every region, and code -> parsed halves for every locale.
    val regions = codes("ipeds_region")
    val locales = codes("nces_locale")
    val book =
      Codebook(
        regions.map {
          ed.unicoach.db.models.NewIpedsRegion(
            slug = it["slug"]!!.jsonPrimitive.content,
            code = it["code"]!!.jsonPrimitive.content.toInt(),
            name = it["name"]!!.jsonPrimitive.content,
            labelRaw = it["label_raw"]!!.jsonPrimitive.content,
          )
        },
        locales.map {
          ed.unicoach.db.models.NewNcesLocale(
            slug = it["slug"]!!.jsonPrimitive.content,
            code = it["code"]!!.jsonPrimitive.content.toInt(),
            type = it["type"]!!.jsonPrimitive.content,
            detail = it["detail"]!!.jsonPrimitive.content,
            name = it["name"]!!.jsonPrimitive.content,
            labelRaw = it["label_raw"]!!.jsonPrimitive.content,
          )
        },
      )

    for (region in regions) {
      val slug = region["slug"]!!.jsonPrimitive.content
      val code = region["code"]!!.jsonPrimitive.content.toInt()
      assertEquals(code, book.regionCode(slug))
      assertEquals(slug, book.regionSlug(code))
    }
    for (locale in locales) {
      val code = locale["code"]!!.jsonPrimitive.content.toInt()
      val resolved = book.locale(code)!!
      assertEquals(locale["type"]!!.jsonPrimitive.content, resolved.type.word)
      assertEquals(locale["detail"]!!.jsonPrimitive.content, resolved.detail.word)
      assertTrue(book.localeCodes(resolved.type, resolved.detail) == listOf(code))
      assertTrue(book.localeCodes(resolved.type).contains(code))
    }
  }

  @Test
  fun `a loaded row whose halves no enum names is a loud construction failure`() {
    // The divergence the Codebook constructor's error() exists for: a reference
    // table that has grown a type or detail the Kotlin enum does not name. It
    // must FAIL, not degrade — `Codebook.loadOrEmpty` rethrows an
    // IllegalStateException for exactly this reason, because swallowing it would
    // silently disable the whole region and locale vocabulary at boot.
    val thrown =
      assertFailsWith<IllegalStateException> {
        Codebook(
          emptyList(),
          listOf(
            ed.unicoach.db.models.NewNcesLocale(
              slug = "megalopolis-vast",
              code = 99,
              type = "megalopolis",
              detail = "large",
              name = "Megalopolis: Vast",
              labelRaw = "Megalopolis: Vast",
            ),
          ),
        )
      }
    assertTrue(thrown.message!!.contains("megalopolis"), thrown.message!!)
    assertTrue(thrown.message!!.contains("NcesLocaleType"), thrown.message!!)
  }
}
