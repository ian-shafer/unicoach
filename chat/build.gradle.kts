plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
}

dependencies {
  implementation(project(":common"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)

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
