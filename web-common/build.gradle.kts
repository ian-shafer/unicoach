plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
  // RegisteredRoutes: the Ktor route-tree walker the route-coverage guards in
  // rest-server and public-web share.
  `java-test-fixtures`
}

dependencies {
  api(libs.typesafe.config) // Config in RequestLoggingConfig.from()'s signature
  api(libs.ktor.server.core) // Application/Route receivers in public signatures
  implementation(libs.ktor.server.call.logging) // CallLogging, referenced only in-body

  testFixturesApi(libs.ktor.server.core)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.logback.classic)
  testImplementation(libs.ktor.server.content.negotiation)
  testImplementation(libs.ktor.serialization.jackson)
}

tasks.withType<Test> {
  useJUnitPlatform()
}
