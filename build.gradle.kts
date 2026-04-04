plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ktlint)
}

allprojects {
  apply(plugin = "org.jlleitschuh.gradle.ktlint")

  repositories {
    mavenCentral()
  }
}
