plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":chat"))
  implementation(project(":email"))
  implementation(project(":queue"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.slf4j.api)
  implementation(libs.typesafe.config)
  implementation(libs.java.jwt)
  implementation(libs.jwks.rsa)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
  useJUnitPlatform()
}
