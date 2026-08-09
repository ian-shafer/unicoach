plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.api)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.java.jwt)
  implementation(libs.typesafe.config)

  // Shared transport-seam fake and signed fixtures (RFC 107 shape). The
  // testFixtures source set does not inherit main's `implementation` deps, so
  // the fixtures declare their own: the scripted transport references the seam
  // types, and the fixture builders sign real ES256 JWSes and build JSON.
  testFixturesImplementation(testFixtures(project(":common")))
  testFixturesImplementation(libs.kotlinx.serialization.json)
  testFixturesImplementation(libs.java.jwt)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
