plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))

  testImplementation(libs.kotlin.test.junit5)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
