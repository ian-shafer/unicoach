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
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)

  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.ktor.server.test.host)
}
