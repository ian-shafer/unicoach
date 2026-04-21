plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ktlint)
}

allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")

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
}
