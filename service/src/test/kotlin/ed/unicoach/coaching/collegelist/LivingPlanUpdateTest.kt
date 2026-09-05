package ed.unicoach.coaching.collegelist

import ed.unicoach.db.models.LivingArrangement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins [LivingPlanUpdate] (RFC 164): the shared three-state fold, its
 * mutual-exclusion precondition, and the write rule the fold's value carries.
 *
 * No database: this is the vocabulary both wire boundaries speak, and the
 * mapping is pure.
 */
class LivingPlanUpdateTest {
  @Test
  fun `of folds a stated plan, a clear, and neither`() {
    assertEquals(
      LivingPlanUpdate.Set(LivingArrangement.ON_CAMPUS),
      LivingPlanUpdate.of(LivingArrangement.ON_CAMPUS, clear = false),
    )
    assertEquals(LivingPlanUpdate.Clear, LivingPlanUpdate.of(null, clear = true))
    assertEquals(LivingPlanUpdate.Keep, LivingPlanUpdate.of(null, clear = false))
  }

  @Test
  fun `of refuses a stated plan together with a clear`() {
    val thrown =
      assertFailsWith<IllegalArgumentException> {
        LivingPlanUpdate.of(LivingArrangement.ON_CAMPUS, clear = true)
      }
    // The fold must not pick a winner for the illegal pair; each wire boundary
    // refuses it first, in its own wording.
    assertEquals(true, thrown.message?.contains("ON_CAMPUS"))
    assertEquals(true, thrown.message?.contains("clear"))
  }

  @Test
  fun `resolveAgainst writes the plan, writes null, or leaves the stored value alone`() {
    val stored = LivingArrangement.WITH_FAMILY
    assertEquals(
      LivingArrangement.ON_CAMPUS,
      LivingPlanUpdate.Set(LivingArrangement.ON_CAMPUS).resolveAgainst(stored),
    )
    assertNull(LivingPlanUpdate.Clear.resolveAgainst(stored))
    assertEquals(stored, LivingPlanUpdate.Keep.resolveAgainst(stored))
    assertNull(LivingPlanUpdate.Keep.resolveAgainst(null))
  }
}
