plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

application {
  // The declarative state applier (bin/state-apply forwards the world file path).
  mainClass.set("ed.unicoach.fixture.StateApplyApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  // RegistrationValidator today; positions the module to call services for
  // future resource types (RFC 138).
  implementation(project(":service"))
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)

  // Console logging for the applier (no logback.xml of its own; inherits the
  // Logback default config, like the college ingester).
  runtimeOnly(libs.logback.classic)

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
