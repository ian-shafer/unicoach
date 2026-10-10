package ed.unicoach.appstore

/**
 * A swap point at the [AppStoreTransport] seam, for a suite that boots its server
 * once and scripts the Apple conversation per test.
 *
 * Beside [ScriptedAppStoreTransport], which every such suite already uses to
 * build the scripts it installs here — the seam belongs to the module that
 * defines [AppStoreTransport], not to a copy in each suite that needs one.
 *
 * A CLASS and not an object: two suites in one JVM would otherwise swap each
 * other's script. Installing no script fails the call loudly rather than
 * answering it, [ScriptedAppStoreTransport]'s own posture — an Apple call a test
 * did not plan for is a test that wandered off its subject.
 */
class SwappableAppStoreTransport : AppStoreTransport {
  @Volatile
  var delegate: AppStoreTransport =
    AppStoreTransport { path, _ -> throw IllegalStateException("No App Store script installed for [$path]") }

  override suspend fun get(
    path: String,
    bearerToken: String,
  ): AppStoreTransportResponse = delegate.get(path, bearerToken)
}
