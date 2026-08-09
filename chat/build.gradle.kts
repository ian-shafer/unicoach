plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

dependencies {
  implementation(project(":common"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)

  // Shared transport-seam fakes and recorded wire captures (RFC 107). The
  // testFixtures source set does not inherit main's `implementation` deps, so
  // the fake declares its own: it references Flow and JsonObject directly.
  testFixturesImplementation(testFixtures(project(":common")))
  testFixturesImplementation(libs.kotlinx.coroutines.core)
  testFixturesImplementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
