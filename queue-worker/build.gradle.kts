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
  implementation(project(":net"))
  // Direct dependency (was only transitive via :service) to build the
  // ChatProvider for extraction and bring chat.conf onto the classpath (RFC 66).
  implementation(project(":chat"))

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
