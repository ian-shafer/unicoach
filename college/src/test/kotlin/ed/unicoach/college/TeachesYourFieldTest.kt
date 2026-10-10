package ed.unicoach.college

import ed.unicoach.db.models.ProgrammeCensus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The label rule (RFC 245 A1), asserted where it lives: a pure function over two
 * sets, with no database, no page and no tool in the way.
 *
 * The three states it reads are the three [ProgrammeCensus] names, and the whole
 * point of the rule is that it keeps the last two APART: a school with no
 * programme census at all and a school whose census names no taxonomy subject
 * would collapse into one answer under any rule written over an empty list.
 *
 * The answer and the subjects that produced it are ONE value, so there is no
 * second function to keep in agreement with the first: a yes NAMES what matched
 * or it does not compile.
 */
class TeachesYourFieldTest {
  private val stored = setOf("nursing")

  /** A census that IS on file, naming these taxonomy subjects -- `census()` being the judged `{}`. */
  private fun census(vararg subjectSlugs: String) = ProgrammeCensus.OnFile(subjectSlugs.toList())

  /**
   * The ACTIVE taxonomy, as `CollegeQueryVocabulary.subjectTaxonomy` hands it over:
   * every slug these cases store, so judgeability is not what they are about.
   * The two cases that ARE about it name their own.
   */
  private val taxonomy =
    SubjectTaxonomy(setOf("nursing", "marine-biology", "mechanical-engineering", "biology", "literature"))

  @Test
  fun `no programme census at all is unknown, never a no`() {
    // SQL NULL. We hold nothing about this school's programs, so the honest
    // answer is that the question cannot be judged -- and the coach prompt turns
    // that into "say nothing either way".
    assertEquals(
      TeachesYourField.Unknown(TeachesYourField.Cause.NO_CENSUS),
      TeachesYourField.of(ProgrammeCensus.NotReported, stored, taxonomy),
      "the silence is the SCHOOL's, and the answer says so",
    )
  }

  @Test
  fun `a census that names no taxonomy subject is a judged no`() {
    // The EMPTY array, which is a fact and not an absence: the programs ARE
    // known, and none of them is any taxonomy subject. Reading it as unknown
    // would overstate how many schools we cannot judge.
    assertEquals(TeachesYourField.No, TeachesYourField.of(census(), stored, taxonomy))
  }

  @Test
  fun `a census naming other subjects is a no, and a no names no subject`() {
    // An empty array beside a no would read as a claim about the school, and
    // the type is what makes that unwritable: only a yes carries a list.
    assertEquals(TeachesYourField.No, TeachesYourField.of(census("biology", "literature"), stored, taxonomy))
  }

  @Test
  fun `one of three stored subjects overlapping is a yes naming only the one that matched`() {
    // A student may store up to three. The label says yes, and the list says
    // WHICH -- the answer a coach would otherwise have to guess at.
    val three = setOf("nursing", "marine-biology", "mechanical-engineering")
    val indexed = census("biology", "literature", "nursing")

    assertEquals(TeachesYourField.Yes(listOf("nursing")), TeachesYourField.of(indexed, three, taxonomy))
  }

  @Test
  fun `the list a yes names is every stored subject the school teaches, sorted`() {
    val three = setOf("nursing", "marine-biology", "mechanical-engineering")
    val indexed = census("nursing", "biology", "marine-biology")

    assertEquals(
      TeachesYourField.Yes(listOf("marine-biology", "nursing")),
      TeachesYourField.of(indexed, three, taxonomy),
      "one school's list reads the same from one call to the next",
    )
  }

  @Test
  fun `a stored subject the taxonomy has retired reads unknown, never a judged no`() {
    // The state a student is LEFT in on purpose: a deprecated slug is refused
    // only as a NEW choice, so a student who already chose one keeps it. It is
    // in no school's census, because the index is built from the ACTIVE
    // taxonomy -- so a census that names none of their subjects has not been
    // shown to name none of them, and the honest answer is our silence.
    val heldRetired = setOf("nursing", "home-economics")

    assertEquals(
      TeachesYourField.Unknown(TeachesYourField.Cause.SUBJECT_NOT_JUDGEABLE),
      TeachesYourField.of(census("biology", "literature"), heldRetired, taxonomy),
      "the census could not be compared against home-economics at all, so `no` is a claim we cannot make",
    )
    // A judgeable subject that MATCHES still wins, and names only itself: the
    // retired one was never comparable, so it can neither add to nor remove
    // from what matched.
    assertEquals(
      TeachesYourField.Yes(listOf("nursing")),
      TeachesYourField.of(census("nursing", "biology"), heldRetired, taxonomy),
    )
  }

  @Test
  fun `a student left with no judgeable subject reads unknown, and is never unlabelled`() {
    // Every subject they hold is outside this taxonomy, so nothing of theirs
    // can be compared -- and that is OUR silence, said as our silence. An absent
    // key would render their page as the page of a student who told us nothing,
    // which is a claim about THEM made out of a gap in OURS.
    val onlyRetired = setOf("home-economics")
    val ours = TeachesYourField.Unknown(TeachesYourField.Cause.SUBJECT_NOT_JUDGEABLE)

    assertEquals(ours, TeachesYourField.of(census("nursing", "biology"), onlyRetired, taxonomy))
    assertEquals(ours, TeachesYourField.of(census(), onlyRetired, taxonomy))
    assertEquals(
      TeachesYourField.Unknown(TeachesYourField.Cause.NO_CENSUS),
      TeachesYourField.of(ProgrammeCensus.NotReported, onlyRetired, taxonomy),
      "a school that filed nothing is still the school's own silence",
    )

    // The same answer when the taxonomy itself never loaded, which is the state
    // `Codebook.loadOrEmpty` degrades a read fault to: an outage of ours is not
    // a student who stored nothing.
    assertEquals(ours, TeachesYourField.of(census("nursing"), stored, SubjectTaxonomy(emptySet())))
  }

  @Test
  fun `the ONE absence is a student who stored nothing`() {
    // D20, and now the only way to reach it: no question was asked, so the row
    // says nothing and the page is the one that shipped before this RFC.
    assertEquals(null, TeachesYourField.of(census("nursing"), emptySet(), taxonomy))
    assertEquals(null, TeachesYourField.of(census(), emptySet(), taxonomy))
    assertEquals(null, TeachesYourField.of(ProgrammeCensus.NotReported, emptySet(), taxonomy))
  }

  @Test
  fun `a yes naming nothing cannot be built`() {
    // A3's whole statement: "yes, but I cannot say which subject" is the answer
    // this surface exists to remove, so it is not a value anything can hold.
    // The rule above never reaches this -- it builds a yes only from an overlap
    // it has already found non-empty -- and the guard is what keeps a second
    // construction site honest.
    assertFailsWith<IllegalArgumentException> { TeachesYourField.Yes(emptyList()) }
  }

  @Test
  fun `the three words are the ones the wire carries`() {
    // The tool DESCRIPTION quotes these three, and the coach prompt names two of
    // them. A renamed constant that left the value alone would be invisible;
    // this is what makes the value itself the contract.
    assertEquals("yes", TeachesYourField.YES)
    assertEquals("no", TeachesYourField.NO)
    assertEquals("unknown", TeachesYourField.UNKNOWN)
    assertEquals(TeachesYourField.YES, TeachesYourField.Yes(listOf("nursing")).value)
    assertEquals(TeachesYourField.NO, TeachesYourField.No.value)
    assertEquals(TeachesYourField.UNKNOWN, TeachesYourField.Unknown(TeachesYourField.Cause.NO_CENSUS).value)
    assertEquals(
      TeachesYourField.UNKNOWN,
      TeachesYourField.Unknown(TeachesYourField.Cause.SUBJECT_NOT_JUDGEABLE).value,
      "both silences ride ONE wire word: a cause is not a fourth answer",
    )
  }
}
