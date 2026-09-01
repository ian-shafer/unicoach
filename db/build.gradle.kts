plugins {
  alias(libs.plugins.kotlin.jvm)
  // CodebookReferenceFixture: the us_states / nces_locales / ipeds_regions rows
  // that migration 0067 made a precondition of inserting ANY college, shared
  // with every module whose tests insert one (:college, :service, :admin-web).
  `java-test-fixtures`
}

dependencies {
  implementation(project(":common"))
  implementation(libs.postgresql)
  implementation(libs.hikaricp)
  implementation(libs.kotlinx.coroutines.core)

  // CodebookReferenceFixture derives its rows from db/data/codebooks.json —
  // the file the ingest loads — rather than re-typing the vocabulary.
  testFixturesImplementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
  useJUnitPlatform()
}
