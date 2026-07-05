plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

application {
  mainClass.set("ed.unicoach.worker.ApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":queue"))
  implementation(project(":service"))
  // Direct dependency (Gradle `implementation` is non-transitive) to construct the
  // CollegeSearchService for the fit-lens handler (RFC 98); :service depends on
  // :college via implementation, so the type is not otherwise on the classpath.
  implementation(project(":college"))
  implementation(project(":net"))
  // Direct dependency (was only transitive via :service) to build the
  // ChatProvider for extraction and bring chat.conf onto the classpath (RFC 66).
  implementation(project(":chat"))
  // Direct dependency (Gradle `implementation` is non-transitive) to construct the
  // EmailService/provider and the EmailSendHandler, and bring email.conf onto the
  // classpath — the worker is the sole transmitter of outbound email (RFC 96).
  implementation(project(":email"))

  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test.junit5)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
