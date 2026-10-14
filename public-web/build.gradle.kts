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
  // RFC 224: registration enqueues a SEND_EMAIL verification job through
  // EmailVerificationService, so public-web is an enqueue-only queue producer.
  // It constructs a QueueService and never an EmailService — the worker sends.
  implementation(project(":queue"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  // RFC 254: a test selects queued email jobs by EmailTemplate, the enum that
  // owns the template names, rather than by a hand-typed string.
  testImplementation(project(":email"))
  // The one home of the UC San Diego Scorecard row (RFC 157), shared with the
  // :service cost tests that seed the same row.
  testImplementation(testFixtures(project(":service")))
  // RFC 209's ONE `students` INSERT (StudentRowFixture), the same dependency
  // :admin-web, :rest-server, :service and :college already carry.
  testImplementation(testFixtures(project(":db")))
  // RFC 251: the figure-comparison word list (FIGURE_COMPARISON_WORDS) the
  // report page is now checked against, and the field-of-study registry the
  // page's program fixtures are built from.
  testImplementation(testFixtures(project(":college")))
  // The Ktor route-tree walker the route-coverage guard reads (RegisteredRoutes).
  testImplementation(testFixtures(project(":web-common")))
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
