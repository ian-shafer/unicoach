plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

// Kotlin lint/format lives entirely in `bin/format` (the nix dev-shell `ktlint`),
// not in Gradle — one formatting avenue for the repo. `bin/format` rewrites,
// `bin/format -c` checks; the pre-commit hook runs the check. Deliberately no
// ktlint-gradle plugin here, so there is only ever one ktlint and nothing to keep
// in version-sync.
allprojects {
  repositories {
    mavenCentral()
  }
}

// Forward health marker environment variables to JVM system properties for all
// daemon subprojects. Applied centrally to avoid duplicating the block in each
// module's build.gradle.kts.
subprojects {
  plugins.withId("application") {
    tasks.named<JavaExec>("run") {
      systemProperty("run.dir", providers.environmentVariable("RUN_DIR").getOrElse(""))
      systemProperty("service.name", providers.environmentVariable("SERVICE_NAME").getOrElse(""))
      systemProperty("health.nonce", providers.environmentVariable("HEALTH_NONCE").getOrElse(""))
    }
  }

  // Hermetic tests: pin AppConfig's local-overlay base (the `unicoach.config.dir`
  // system property, highest precedence in AppConfig.overlayFile) to an in-build
  // directory that never holds `unicoach/local.conf`. Without this, a developer's
  // sanctioned secrets overlay at ~/.config/unicoach/local.conf (RFC 46) bleeds
  // into the test JVM and can swap the packaged "log" chat provider for a live
  // one, breaking tests that assert against the echo stub. AppConfigTest still
  // overrides this per-test via System.setProperty at runtime.
  tasks.withType<Test>().configureEach {
    systemProperty(
      "unicoach.config.dir",
      layout.buildDirectory
        .dir("test-config-overlay")
        .get()
        .asFile.path,
    )

    // One test database per module (RFC 259). bin/test copies the migrated test
    // DB once per selected module and names the copies in
    // UNICOACH_TEST_DATABASES ("module=database,..."); each test JVM gets its own
    // as POSTGRES_DB, so modules can run in parallel without wiping each other's
    // tables. Set as the ENVIRONMENT variable, not a system property, so every
    // reader sees the copy -- AppConfig, a bare HOCON resolve(), System.getenv,
    // a child process.
    //
    // With no map (a plain ./gradlew), nothing is set and the JVM inherits the
    // one shared test DB, exactly as before; bin/test is the only caller that
    // passes --parallel. With a map that does not name this module, the task
    // REFUSES TO RUN: running it on the inherited database beside parallel
    // siblings is the interference the copies exist to prevent.
    //
    // The refusal is at EXECUTION, not configuration. Several modules configure
    // their Test tasks eagerly (`tasks.withType<Test> { }`), so every module's
    // test task is configured even when bin/test selected one module; refusing
    // here at configuration would fail a run over modules nobody asked for.
    val moduleDatabases = testModuleDatabases()
    if (moduleDatabases != null) {
      val database = moduleDatabases[project.name]
      if (database != null) {
        environment("POSTGRES_DB", database)
      } else {
        val module = project.name
        doFirst {
          throw GradleException(
            "UNICOACH_TEST_DATABASES names no test database for module [$module]: " +
              "bin/test copies one per module it runs, and this one was not among them.",
          )
        }
      }
    }
  }
}

// UNICOACH_TEST_DATABASES ("module=database,...") parsed into a map, or null when
// unset. A malformed entry is refused rather than skipped: a skipped entry would
// surface later as the "names no test database" refusal above, about a module
// that WAS named.
fun testModuleDatabases(): Map<String, String>? {
  val raw = providers.environmentVariable("UNICOACH_TEST_DATABASES").orNull ?: return null
  return raw
    .split(",")
    .filter { it.isNotEmpty() }
    .associate { entry ->
      val parts = entry.split("=", limit = 2)
      if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        throw GradleException("UNICOACH_TEST_DATABASES entry [$entry] is not module=database")
      }
      parts[0] to parts[1]
    }
}
