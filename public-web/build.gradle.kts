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
  implementation(project(":web-common"))
  implementation(project(":db"))
  implementation(project(":auth"))
  // RFC 155 D-E: the Family Cost Report renders the SAME computation the coach
  // reads in chat (CollegeCostService), in-process behind the CostReportSource
  // port — the admin-web precedent for a web module depending on :service.
  implementation(project(":service"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  // The one home of the UC San Diego Scorecard row (RFC 157), shared with the
  // :service cost tests that seed the same row.
  testImplementation(testFixtures(project(":service")))
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
