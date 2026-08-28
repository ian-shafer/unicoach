package ed.unicoach.db.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstitutionControlTest {
  @Test
  fun `the control vocabulary has one home`() {
    // The three IPEDS codes and the phrase each one is said in. These strings
    // reach the model on both tool paths (RFC 143), so pin them here rather
    // than in either consumer's test.
    assertEquals("public", InstitutionControl.labelFor(1))
    assertEquals("private_nonprofit", InstitutionControl.labelFor(2))
    assertEquals("private_for_profit", InstitutionControl.labelFor(3))
  }

  @Test
  fun `fromCode round-trips every member and rejects a code outside the vocabulary`() {
    for (control in InstitutionControl.entries) {
      assertEquals(control, InstitutionControl.fromCode(control.code))
    }
    assertNull(InstitutionControl.fromCode(4))
    assertNull(InstitutionControl.fromCode(0))
  }

  @Test
  fun `an unrecognised code keeps the raw value visible`() {
    // Never guessed onto a known label and never dropped: a source that has
    // extended its vocabulary shows up as an unknown carrying its own code,
    // which is what makes the extension findable at the wire.
    assertEquals("unknown (control [4])", InstitutionControl.labelFor(4))
    assertEquals("unknown (control [-1])", InstitutionControl.labelFor(-1))
  }
}
