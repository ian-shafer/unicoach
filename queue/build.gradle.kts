plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  compileOnly(libs.postgresql)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(testFixtures(project(":db")))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.hikaricp)
  // The dead-letter alert is a log line (RFC 267 D-C); QueueWorkerTest reads it through a ListAppender.
  testImplementation(libs.logback.classic)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
