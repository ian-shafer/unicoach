package ed.unicoach.email

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogOnlyEmailProviderTest {
  @Test
  fun `id is the wire identity log`() {
    assertEquals("log", LogOnlyEmailProvider().id)
  }

  @Test
  fun `send returns Sent with a non-null providerMessageId`() =
    runTest {
      val provider = LogOnlyEmailProvider()
      val outbound =
        OutboundEmail(
          from = address("from@unicoach.app"),
          to = address("to@example.com"),
          subject = subject("Hello"),
          body = body("World"),
        )

      val result = provider.send(outbound)

      assertTrue(result is ProviderResult.Sent)
      assertNotNull(result.providerMessageId)
    }

  private fun address(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun subject(value: String): EmailSubject = (EmailSubject.create(value) as ValidationResult.Valid).value

  private fun body(value: String): EmailBody = (EmailBody.create(value) as ValidationResult.Valid).value
}
