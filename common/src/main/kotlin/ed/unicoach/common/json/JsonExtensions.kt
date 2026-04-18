package ed.unicoach.common.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Decodes a [JsonObject] into a typed data class via [Json.decodeFromJsonElement].
 * The target type [T] must be annotated with [@Serializable][kotlinx.serialization.Serializable].
 */
inline fun <reified T> JsonObject.deserialize(): T = Json.decodeFromJsonElement(this)

/**
 * Encodes a [@Serializable][kotlinx.serialization.Serializable] object into a [JsonObject]
 * via [Json.encodeToJsonElement].
 */
inline fun <reified T> T.asJson(): JsonObject = Json.encodeToJsonElement(this) as JsonObject
