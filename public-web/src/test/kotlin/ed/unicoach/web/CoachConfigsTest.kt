package ed.unicoach.web

import com.typesafe.config.ConfigFactory
import ed.unicoach.coaching.UnpricedModelsException
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The in-process coach's boot readers (RFC 262 §3), over the server's OWN conf
 * list with one value overridden — the shape an operator's `COACHING_MODEL`
 * env override takes.
 *
 * [CoachConfigs.read] takes a `Config` and nothing else, so a refusal from it is
 * by construction a refusal before any pool or provider exists; `startServer`
 * calls it before `Database(...)`.
 */
class CoachConfigsTest {
  /** Test 33: an unpriced coaching model fails the boot rather than metering every turn at the default rate. */
  @Test
  fun `an unpriced coaching model refuses the coach config read`() {
    val shipped = AppConfig.load(*PUBLIC_WEB_CONF_RESOURCES).getOrThrow()
    val overridden =
      ConfigFactory
        .parseString("""coaching.model = "unpriced-test-model"""")
        .withFallback(shipped)
        .resolve()

    val refusal = assertFailsWith<UnpricedModelsException> { CoachConfigs.read(overridden) }
    assertEquals(listOf("unpriced-test-model"), refusal.missingModels)
  }
}
