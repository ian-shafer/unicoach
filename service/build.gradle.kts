plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":chat"))
  implementation(project(":college"))
  implementation(project(":email"))
  implementation(project(":queue"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(libs.typesafe.config)
  implementation(libs.java.jwt)
  implementation(libs.jwks.rsa)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  // The runtime logging backend, pulled into the test source set only so
  // LlmCallLogTest can capture the estimated-model WARN via a ListAppender
  // (web-common's precedent). RFC 108.
  testImplementation(libs.logback.classic)
}

tasks.test {
  useJUnitPlatform()
}
