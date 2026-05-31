plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
}
