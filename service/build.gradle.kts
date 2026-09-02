plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  // The UC San Diego Scorecard row RFC 157 argues from is needed by BOTH this
  // module's cost tests and :public-web's report-page test, so it has one home
  // here rather than being retyped in each (the :chat / :appstore precedent).
  `java-test-fixtures`
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":chat"))
  implementation(project(":appstore"))
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
  // The published state/locale reference rows migration 0067 made a
  // precondition of inserting any college row.
  testImplementation(testFixtures(project(":db")))
  // Transport-seam fake and signed App Store fixtures (RFC 110), so
  // SubscriptionServiceTest drives the real AppStoreServerApi.
  testImplementation(testFixtures(project(":appstore")))
  // The shared RFC 143 source-code guard (RFC 148 D9), hosted in the one module
  // :service and :college both already depend on.
  testImplementation(testFixtures(project(":chat")))
  // The real loaded codebook (RFC 147): the fit lens's query tool speaks the
  // same vocabulary as :college's search tool, so it is tested against the same
  // loaded reference tables rather than a hand-typed pair of rows.
  testImplementation(testFixtures(project(":college")))
  // The runtime logging backend, pulled into the test source set only so
  // LlmCallLogTest can capture the estimated-model WARN via a ListAppender
  // (web-common's precedent). RFC 108.
  testImplementation(libs.logback.classic)
}

tasks.test {
  useJUnitPlatform()
}
