plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-test-fixtures`
}

dependencies {
  // DataSize, the shared bound type the notification verifier's MAX_JWS is
  // expressed in (RFC 112) — the same type SubscriptionService.MAX_JWS uses.
  implementation(project(":common"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.api)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.java.jwt)
  implementation(libs.typesafe.config)

  // Shared transport-seam fake and signed fixtures (RFC 107 shape). The
  // testFixtures source set does not inherit main's `implementation` deps, so
  // the fixtures declare their own: the scripted transport references the seam
  // types, and the fixture builders sign real ES256 JWSes and build JSON.
  testFixturesImplementation(testFixtures(project(":common")))
  testFixturesImplementation(libs.kotlinx.serialization.json)
  testFixturesImplementation(libs.java.jwt)
  testFixturesImplementation(libs.typesafe.config)
  // Test-only certificate minting (RFC 112): every AppleJwsVerifier case needs a
  // synthetic X.509 chain — a foreign root, a leaf without the marker OID, an
  // expired leaf — and nothing in the JDK's public API issues certificates.
  // Declared here alone, so it never reaches a main or runtime classpath.
  testFixturesImplementation(libs.bouncycastle.pkix)
  testFixturesImplementation(libs.bouncycastle.prov)

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
