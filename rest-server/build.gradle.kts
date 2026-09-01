plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
  application
}

application {
  mainClass.set("ed.unicoach.rest.ApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":web-common"))
  implementation(project(":db"))
  implementation(project(":auth"))
  implementation(project(":service"))
  implementation(project(":chat"))
  // The composition root wires CollegeSearchTool into the ToolRegistry (RFC 94).
  implementation(project(":college"))
  // The App Store Server API client (RFC 110); AppStoreServerApiFactory builds
  // its HttpClient, which the composition root closes on ApplicationStopped —
  // hence ktor.client.core here. The CIO engine is the end-to-end tests' own
  // client (testImplementation inherits it).
  implementation(project(":appstore"))
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(project(":email"))
  implementation(project(":queue"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.auth.jwt)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.body.limit)
  implementation(libs.ktor.serialization.jackson)
  implementation(libs.jackson.datatype.jsr310)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  // Reads api-specs/openapi.yaml for the build guards that hold the published
  // contract to the Kotlin declarations it restates (see OpenApiSpec). Parsed as
  // a document, so each guard compares against its own schema path rather than
  // matching text anywhere in the file.
  testImplementation(libs.jackson.dataformat.yaml)
  // Transport-seam fakes and recorded wire captures shared from the provider
  // modules, so the end-to-end tests drive the real providers (RFC 107).
  // The published state/locale reference rows migration 0067 made a
  // precondition of inserting any college row.
  testImplementation(testFixtures(project(":db")))
  testImplementation(testFixtures(project(":chat")))
  testImplementation(testFixtures(project(":email")))
  testImplementation(testFixtures(project(":appstore")))
}

tasks.withType<Test> {
  useJUnitPlatform()
  // Gradle already knows where the repository root is, so hand the spec's
  // location to the OpenAPI build guards rather than having each test
  // rediscover it by walking up from an assumed working directory. One
  // definition of the path, in the build that owns paths.
  val openApiSpec = rootProject.file("api-specs/openapi.yaml")
  // The guards read the spec, so it is a real input to this task. Without
  // declaring it, a change to openapi.yaml alone leaves the task UP-TO-DATE and
  // the drift the guards exist to catch ships unchecked.
  inputs
    .file(openApiSpec)
    .withPropertyName("openApiSpec")
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
  systemProperty("unicoach.openapi.file", openApiSpec.path)
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
