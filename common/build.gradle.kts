plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
  `java-library`
}

dependencies {
  implementation(libs.argon2)
  implementation(libs.java.jwt)
  implementation(libs.kotlinx.coroutines.core)

  api(libs.typesafe.config)

  testImplementation(libs.kotlin.test.junit5)
}
