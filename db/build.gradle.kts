plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktlint)
}

dependencies {
  implementation(project(":common"))
  implementation(libs.postgresql)
  implementation(libs.hikaricp)

  testImplementation(libs.kotlin.test.junit5)
}
