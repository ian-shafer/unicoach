plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktor)
  application
}

application {
  mainClass.set("io.ktor.server.netty.EngineMain")
  applicationDefaultJvmArgs = listOf("-Dconfig.resource=rest-server.conf")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":service"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events("failed")
  }
}
