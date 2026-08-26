package ed.unicoach.fixture

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

/**
 * The declared desired state of one user (RFC 138). [name], [verified], and
 * [admin] are optional in the YAML; their documented defaults ([name] falls
 * back to the email local part) are applied at parse time by [WorldFile.load]
 * so the rest of the tool only ever sees fully resolved specs.
 */
data class UserSpec(
  val email: String,
  val password: String,
  val name: String? = null,
  val verified: Boolean = true,
  val admin: Boolean = false,
) {
  /** The declared name, or the documented default: the email local part. */
  val resolvedName: String
    get() = name ?: email.substringBefore('@')
}

/**
 * A parsed world file: the whole desired world, v1 supporting only `users:`.
 * An empty or absent `users:` list is a valid (empty) world.
 */
data class WorldFile(
  val users: List<UserSpec> = emptyList(),
) {
  companion object {
    // Strict by construction: FAIL_ON_UNKNOWN_PROPERTIES stays at its Jackson
    // default (enabled), so a typo like `verifed:` is a parse error, never a
    // silently different world.
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /**
     * Parses [file] as a v1 world file. Throws on unknown keys, missing
     * required fields (`email`, `password`), or malformed YAML. A whitespace-
     * only file parses as the empty world.
     */
    fun load(file: File): WorldFile = parse(file.readText())

    /** Parses YAML [text] with the same strictness as [load]. */
    fun parse(text: String): WorldFile {
      if (text.isBlank()) return WorldFile()
      return mapper.readValue(text, WorldFile::class.java) ?: WorldFile()
    }
  }
}
