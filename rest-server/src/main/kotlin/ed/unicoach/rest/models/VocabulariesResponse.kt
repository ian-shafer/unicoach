package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonAnyGetter

/**
 * Body of `200 GET /api/v1/vocabularies` (RFC 165): the served closed
 * vocabularies, keyed by name, plus the content hash of exactly these bytes.
 *
 * [vocabularies] is a map rather than an array because a client asks for one
 * name ("give me the states"); the map preserves registration order on the wire,
 * and each vocabulary's entries are in DISPLAY order — the client sorts nothing.
 */
data class VocabulariesResponse(
  val version: String,
  val vocabularies: Map<String, PublicVocabulary>,
)

/** One published vocabulary on the wire: its entries, in display order. */
data class PublicVocabulary(
  val entries: List<PublicVocabularyEntry>,
)

/**
 * One entry: `value` (what the write path accepts) + `label` (what a person
 * reads), plus whatever extra keys its vocabulary declares.
 *
 * The extras are FLATTENED to the entry's top level by [JsonAnyGetter], so the
 * wire shows `jurisdictionKind` beside `value` rather than nested under an
 * `extras` object. One shape for every vocabulary, present and future, is the
 * whole point of the endpoint: a client that knows `value`/`label` renders a
 * list it has never seen before.
 */
data class PublicVocabularyEntry(
  val value: String,
  val label: String,
  @get:JsonAnyGetter val extras: Map<String, String>,
)
