plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(libs.aws.sesv2)
  compileOnly(libs.postgresql)

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
