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

  // LogWarnCapture: the shared root-logger WARN capture used by :college and
  // :service tests, so the idiom lives once instead of in three private copies.
  testFixturesImplementation(libs.logback.classic)

  // AdmissionTestCalendarFixture reads db/seed/test-calendar/administrations.csv
  // with a real CSV reader rather than a hand-rolled split (RFC 197).
  testFixturesImplementation(libs.commons.csv)

  // AdmissionTestStateProgrammeFixture builds a row whose school year IS
  // :common's AcademicYear (RFC 208, 0087). `implementation` does not reach the
  // test-fixtures source set, and a fixture cannot construct a model type whose
  // own parameter type it cannot see.
  testFixturesImplementation(project(":common"))

  testImplementation(libs.kotlin.test.junit5)
  // CountingSqlSession: :db's own DAO tests assert the batching contract with the
  // same decorator its dependants do, rather than keeping a second copy of it.
  testImplementation(testFixtures(project(":db")))
}

tasks.test {
  useJUnitPlatform()
}
