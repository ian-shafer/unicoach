package ed.unicoach

import ed.unicoach.email.EmailTemplate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EmailRenderersTest {
  @Test
  fun `every email template has exactly one renderer`() {
    val templates = emailRenderers("https://example.test/verify").map { it.template }

    assertEquals(EmailTemplate.entries.sorted(), templates.sorted())
  }
}
