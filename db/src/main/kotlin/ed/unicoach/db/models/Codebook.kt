package ed.unicoach.db.models

// The published-codebook reference rows (RFC 147): one input type per table
// created by `0060.create-codebook-reference-tables.sql`, plus the provenance
// row that records where each domain came from.
//
// They live in ONE file, against the models directory's one-type-per-file
// habit, because they are one artifact: eleven shapes of a single generated
// file (`db/data/codebooks.json`) that are always added, changed and read
// together. Splitting them would spread one codebook across eleven files whose
// only relationship is this comment.
//
// Every value here is PUBLISHED data — `labelRaw` is the publisher's string
// byte for byte, and the parsed columns beside it are produced by
// `bin/fetch-codebooks`, where a human reviews the diff (D38/D39). Nothing in
// this file is authored at ingest time; the one authored column in the whole
// codebook is [NewUsState.jurisdictionKind], authored in the generator.
//
// None of these carry `id`, `createdAt` or `updatedAt`: each table's natural
// key IS its primary key, and the timestamps are DB-managed.

/** One `ipeds_regions` row: HD `OBEREG`, the code `colleges.region` stores. */
data class NewIpedsRegion(
  val slug: String,
  val code: Int,
  val name: String,
  val labelRaw: String,
)

/**
 * One `us_states` row, keyed by the postal code `colleges.state` stores.
 * [ipedsRegion] is the region SLUG (the FK), parsed out of the OBEREG label's
 * trailing state list; [jurisdictionKind] is the codebook's one authored value.
 */
data class NewUsState(
  val uspsCode: String,
  val name: String,
  val jurisdictionKind: JurisdictionKind,
  val ipedsRegion: String,
)

/**
 * One `nces_locales` row: HD `LOCALE`, the code `colleges.locale` stores.
 * [type] and [detail] are the parsed halves of "Rural: Fringe".
 *
 * There is no `definition_raw`: RFC 147's D37 reserved one for NCES Exhibit A,
 * which is a separate web document rather than a member of the published
 * archive, so the column would have been NULL on every row. See `0060`.
 */
data class NewNcesLocale(
  val slug: String,
  val code: Int,
  val type: String,
  val detail: String,
  val name: String,
  val labelRaw: String,
)

/**
 * One `carnegie_2021_basic_classes` row: HD `C21BASIC`, the code
 * `college_ipeds.carnegie_basic` stores. [degreeLevel]/[qualifier] are the
 * parsed halves of "Doctoral Universities: Very High Research Activity", both
 * null for the published rows that carry no such structure.
 */
data class NewCarnegieBasicClass(
  val slug: String,
  val code: Int,
  val degreeLevel: String?,
  val qualifier: String?,
  val name: String,
  val labelRaw: String,
)

/**
 * One `carnegie_2021_size_settings` row: HD `C21SZSET`, the code
 * `college_ipeds.carnegie_size` stores, parsed into the three things the label
 * says — "Four-year, small, highly residential".
 */
data class NewCarnegieSizeSetting(
  val slug: String,
  val code: Int,
  val years: Int?,
  val size: String?,
  val residentialCharacter: String?,
  val name: String,
  val labelRaw: String,
)

/** One `religious_affiliations` row: IC `RELAFFIL`, the code `college_ipeds.rel_affil` stores. */
data class NewReligiousAffiliation(
  val slug: String,
  val code: Int,
  val name: String,
  val labelRaw: String,
)

/**
 * One `athletic_associations` row, built from an IC `ASSOC1..6` VARIABLE label
 * (D40). [code] is the ASSOC ordinal `college_ipeds.athletic_assoc` stores, and
 * [sourceVariable] is the variable it came from (`assoc1`..`assoc6`).
 */
data class NewAthleticAssociation(
  val slug: String,
  val code: Int,
  val sourceVariable: String,
  val name: String,
  val labelRaw: String,
)

/**
 * One `football_conferences` row: IC `CONFNO1` — football only, because that is
 * the variable `college_ipeds.football_conf` stores and the four CONFNO sets do
 * NOT agree (see the migration header).
 */
data class NewFootballConference(
  val slug: String,
  val code: Int,
  val name: String,
  val labelRaw: String,
)

/** One `admission_test_policies` row: ADM `ADMCON7`, the code `college_ipeds.test_policy` stores. */
data class NewAdmissionTestPolicy(
  val slug: String,
  val code: Int,
  val name: String,
  val labelRaw: String,
)

/**
 * One `cip_codes` row: a six-digit C_A `CIPCODE` with its published [title].
 * The two-digit family is `code.take(2)` — derived at read time, never stored.
 */
data class NewCipCode(
  val code: String,
  val title: String,
  val labelRaw: String,
)

/**
 * One `codebook_sources` row: which artifact, at which digest and vintage, a
 * domain's rows came from, plus the absence codes it declares (D41). The
 * digest is what the drift guard compares on every load.
 */
data class NewCodebookSource(
  val domain: String,
  val source: String,
  val sourceFile: String,
  val sourceSha256: String,
  val sourceVintageYear: Int,
  val nullSentinels: List<Int>,
)
