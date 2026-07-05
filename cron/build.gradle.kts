plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

application {
  mainClass.set("ed.unicoach.cron.ApplicationKt")
}

dependencies {
  implementation(project(":common"))
  implementation(project(":db"))
  implementation(project(":queue"))
  implementation(libs.cron.utils)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.api)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)
  compileOnly(libs.postgresql)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.hikaricp)
  testImplementation(libs.postgresql)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
