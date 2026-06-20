plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktor)
  application
}

application {
  mainClass.set("ed.unicoach.web.ApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.cio)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
