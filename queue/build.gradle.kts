plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  compileOnly(libs.postgresql)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.hikaricp)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
