plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-test-fixtures`
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":queue"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(libs.aws.sesv2)
  compileOnly(libs.postgresql)

  // Shared SesSendOperation-seam fakes and recorded SES shapes (RFC 107). The
  // testFixtures source set does not inherit main's `implementation` deps, so it
  // declares the ones the fakes reference directly.
  testFixturesImplementation(libs.aws.sesv2)
  testFixturesImplementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.postgresql)
  testImplementation(libs.hikaricp)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
