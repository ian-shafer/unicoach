plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

application {
  // The re-runnable Scorecard ingester (bin/ingest-colleges forwards CSV paths).
  mainClass.set("ed.unicoach.college.IngestApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(libs.commons.csv)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)

  // Console logging for the ingester (no logback.xml of its own; inherits the
  // Logback default config, like queue-worker's application entry).
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  // The shared RFC 143 source-code guard (RFC 148 D9). :chat is otherwise not a
  // :college dependency; only the test fixture is pulled in, never main code.
  testImplementation(testFixtures(project(":chat")))
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
