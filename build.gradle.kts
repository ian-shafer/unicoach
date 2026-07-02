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
  }
}
