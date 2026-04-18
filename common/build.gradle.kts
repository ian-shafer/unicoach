plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

dependencies {
  implementation(libs.argon2)
  implementation(libs.java.jwt)
  implementation(libs.kotlinx.coroutines.core)

  api(libs.typesafe.config)
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test.junit5)
}
