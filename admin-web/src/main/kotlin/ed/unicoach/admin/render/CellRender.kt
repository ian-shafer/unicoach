package ed.unicoach.admin.render

import ed.unicoach.admin.DisplayConfig
import ed.unicoach.admin.engine.FieldType
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The render-time context for the four display conventions of RFC 79: the
 * timezone all datetimes render in, the configured glyphs, and the predicate
 * deciding whether an entity slug is a registered admin resource (so an id cell
 * links only to a page that exists). Constructed once in `adminModule` and
 * threaded into the render functions; the render layer never holds the registry,
 * only this object.
 */
data class AdminDisplay(
  val zone: ZoneId,
  val idLinkGlyph: String,
  val boolTrueGlyph: String,
  val boolFalseGlyph: String,
  val idTailChars: Int,
  val copyGlyph: String,
  val isSupported: (slug: String) -> Boolean,
)

/**
 * Builds the render-time [AdminDisplay] from a parsed [DisplayConfig] plus the
 * entity-support predicate. Keeping the field-by-field copy here — next to both
 * types — means a new [DisplayConfig] field is carried into [AdminDisplay] in one
 * place rather than silently omitted at the `adminModule` wiring site.
 */
fun DisplayConfig.toAdminDisplay(isSupported: (slug: String) -> Boolean): AdminDisplay =
  AdminDisplay(
    zone = timezone,
    idLinkGlyph = idLinkGlyph,
    boolTrueGlyph = boolTrueGlyph,
    boolFalseGlyph = boolFalseGlyph,
    idTailChars = idTailChars,
    copyGlyph = copyGlyph,
    isSupported = isSupported,
  )

/** `MMM d, yyyy` in [Locale.ENGLISH] — e.g. `Jan 3, 2026`. */
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/**
 * Full ISO-8601 offset datetime — e.g. `2026-01-03T06:15:30.123-08:00`. The
 * hover `title` for a timestamp cell: the same instant as [DATE_FORMAT] but at
 * full precision and carrying the configured zone's offset, so the exact moment
 * stays unambiguous behind the day-granular visible text.
 */
private val TITLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/**
 * The per-nesting-level indent for [FieldType.JSON] cells: two spaces. Narrower
 * than `prettyPrint`'s 4-space default so deeply nested values consume less
 * horizontal width per level, directly serving the no-horizontal-scroll goal.
 */
private const val JSON_INDENT_UNIT = "  "

private val cellRenderLog = LoggerFactory.getLogger("ed.unicoach.admin.CellRender")

/**
 * Renders the typed value only — no entity link. The single place the datetime
 * and boolean conventions live.
 *
 * - [FieldType.TIMESTAMP]: the source instant formatted as `MMM d, yyyy` in
 *   [AdminDisplay.zone], carrying the same instant as a full ISO-8601 offset
 *   datetime in [AdminDisplay.zone] as a hover `title`. A blank value renders
 *   nothing; a value that does not parse as an [Instant] renders its raw text
 *   (defensive — never throws).
 * - [FieldType.BOOL]: the configured true glyph in `bool-true` when the value is
 *   `"true"`, the configured false glyph in `bool-false` when it is `"false"`.
 *   A blank value renders nothing. (`cells()` always stringifies bools as
 *   `"true"`/`"false"`.) Any other value is surfaced as raw text rather than
 *   masked as false, so an unexpected value is visible.
 * - [FieldType.JSON]: the value parsed and re-emitted as a recursively
 *   syntax-highlighted, wrapping tree inside a `pre.json-pretty` element. A blank
 *   value renders nothing; a value that does not parse as JSON is logged at WARN
 *   and surfaced as raw text (defensive — never throws).
 * - [FieldType.UUID]: a compacted UUID id (RFC 83) — an ellipsis plus the last
 *   [AdminDisplay.idTailChars] characters, with the full value carried in a hover
 *   `title` and a click-to-copy button. A blank value renders nothing.
 * - all other types: the raw text.
 */
fun FlowContent.renderValue(
  value: String,
  type: FieldType,
  display: AdminDisplay,
) {
  if (value.isBlank()) return
  when (type) {
    FieldType.TIMESTAMP -> renderTimestampValue(value, display)
    FieldType.BOOL -> renderBoolValue(value, display)
    FieldType.JSON -> renderJsonValue(value)
    FieldType.UUID -> renderIdValue(value, display)
    FieldType.TEXT, FieldType.MULTILINE, FieldType.INT, FieldType.ENUM -> +value
  }
}

/**
 * The [FieldType.JSON] body: the cell string parsed and re-emitted as a recursive
 * syntax-highlighted tree inside a `pre.json-pretty` element. The `<pre>` wraps to
 * the available width (see `Layout.STYLES`), and each token — key, string, number,
 * boolean, null, punctuation — carries a `json-*` class for coloring. A value that
 * does not parse as JSON is logged at WARN and surfaced as raw text (defensive —
 * never throws, emits no `<pre>`), mirroring the [renderTimestampValue] fallback.
 */
private fun FlowContent.renderJsonValue(value: String) {
  val root =
    runCatching { Json.parseToJsonElement(value) }
      .getOrElse { error ->
        cellRenderLog.warn("Unparseable JSON value rendered as raw text: [{}]", value, error)
        +value
        return
      }
  pre("json-pretty") { renderJsonElement(root, "") }
}

/**
 * Dispatches a parsed [JsonElement] to the renderer for its subtype, carrying the
 * current [indent] (the accumulated leading whitespace for this nesting level)
 * down to the container renderers. A [JsonNull] renders `null` in a `json-null`
 * span; a [JsonPrimitive] delegates to [renderJsonPrimitive]; a [JsonArray] and
 * [JsonObject] delegate to their renderers. Because the parsed root may itself be
 * a scalar or null, a bare-scalar cell value renders through this same dispatch.
 */
private fun FlowContent.renderJsonElement(
  element: JsonElement,
  indent: String,
) {
  when (element) {
    is JsonNull -> span("json-null") { +"null" }
    is JsonPrimitive -> renderJsonPrimitive(element)
    is JsonArray -> renderJsonArray(element, indent)
    is JsonObject -> renderJsonObject(element, indent)
  }
}

/**
 * Renders a non-null [JsonPrimitive]: a string ([JsonPrimitive.isString]) in a
 * `json-string` span using [JsonPrimitive.toString], which supplies the
 * JSON-escaped, quoted form directly (no hand-rolled escaping); the two boolean
 * literals in a `json-bool` span; every other primitive (numbers) in a
 * `json-number` span.
 */
private fun FlowContent.renderJsonPrimitive(value: JsonPrimitive) {
  when {
    value.isString -> span("json-string") { +value.toString() }
    value.content == "true" || value.content == "false" -> span("json-bool") { +value.content }
    else -> span("json-number") { +value.content }
  }
}

/**
 * The shared container skeleton for [renderJsonArray] and [renderJsonObject] —
 * the traversal both bracket-delimited containers share, differing only in their
 * bracket glyphs and per-entry payload. Emits [openBracket]/[closeBracket] in
 * `json-punct` spans; an empty container ([count] == 0) renders
 * `[openBracket][closeBracket]` inline on one line. Otherwise emits [count]
 * entries, each on its own line at `indent` + [JSON_INDENT_UNIT] via [renderEntry] (given that entry's index and
 * the deeper `inner` indent), with inter-entry `,` in `json-punct` spans and the
 * closing bracket back at [indent]. The newline/indentation between entries are
 * plain text nodes, not part of any token span.
 */
private fun FlowContent.renderJsonContainer(
  openBracket: String,
  closeBracket: String,
  indent: String,
  count: Int,
  renderEntry: FlowContent.(index: Int, inner: String) -> Unit,
) {
  if (count == 0) {
    span("json-punct") { +"$openBracket$closeBracket" }
    return
  }
  val inner = indent + JSON_INDENT_UNIT
  span("json-punct") { +openBracket }
  for (index in 0 until count) {
    +"\n$inner"
    renderEntry(index, inner)
    if (index != count - 1) span("json-punct") { +"," }
  }
  +"\n$indent"
  span("json-punct") { +closeBracket }
}

/**
 * Renders a [JsonArray]: the `[`/`]` brackets in `json-punct` spans, one element
 * per line at `indent` + [JSON_INDENT_UNIT], each recursing through
 * [renderJsonElement] at the deeper indent, with inter-element `,` in `json-punct`
 * spans. An empty array renders `[]` inline on one line.
 */
private fun FlowContent.renderJsonArray(
  array: JsonArray,
  indent: String,
) {
  renderJsonContainer("[", "]", indent, array.size) { index, inner ->
    renderJsonElement(array[index], inner)
  }
}

/**
 * Renders a [JsonObject]: the `{`/`}` braces in `json-punct` spans, one
 * `"key": value` entry per line at `indent` + [JSON_INDENT_UNIT] — the key in a
 * `json-key` span, the `:` and inter-entry `,` in `json-punct` spans, the value
 * via [renderJsonElement] at the deeper indent. An empty object renders `{}`
 * inline on one line. The space after `:` and the newline/indentation between
 * entries are plain text nodes, not part of any token span.
 */
private fun FlowContent.renderJsonObject(
  obj: JsonObject,
  indent: String,
) {
  val entries = obj.entries.toList()
  renderJsonContainer("{", "}", indent, entries.size) { index, inner ->
    val (key, element) = entries[index]
    span("json-key") { +JsonPrimitive(key).toString() }
    span("json-punct") { +":" }
    +" "
    renderJsonElement(element, inner)
  }
}

/**
 * The [FieldType.UUID] body (RFC 83): a compacted UUID id rendered as
 *
 * 1. a `span` carrying the full UUID as a hover `title`, whose text is `…`
 *    (U+2026) followed by the last [AdminDisplay.idTailChars] characters when the
 *    value is longer than that width; otherwise the verbatim value with no
 *    ellipsis (a value no longer than the tail is shown whole rather than
 *    prefixed with a misleading ellipsis). The leading prefix is never rendered —
 *    in a UUIDv7 it is the millisecond timestamp shared by rows created close
 *    together, so the distinguishing entropy is the tail this keeps.
 * 2. a minimal copy `button` (`type="button"`, class `id-copy`, `data-full` =
 *    the full value, text = [AdminDisplay.copyGlyph]) backed by the single
 *    delegated listener in `Layout.SCRIPT`. `type="button"` keeps it from
 *    submitting any enclosing form (the embedded-panel field table abuts one).
 *
 * The full value stays reachable three ways: the `title`, the `data-full`
 * attribute, and (when `refSlug` names a supported resource) the trailing glyph
 * `href` that [renderRefLink] builds from the full value.
 */
private fun FlowContent.renderIdValue(
  value: String,
  display: AdminDisplay,
) {
  span {
    title = value
    +if (value.length > display.idTailChars) "…" + value.takeLast(display.idTailChars) else value
  }
  button(type = ButtonType.button, classes = "id-copy") {
    attributes["data-full"] = value
    +display.copyGlyph
  }
}

/**
 * The [FieldType.TIMESTAMP] body: the source instant formatted as `MMM d, yyyy`
 * in [AdminDisplay.zone], carrying the same instant as a full ISO-8601 offset
 * datetime in [AdminDisplay.zone] ([TITLE_FORMAT]) as a hover `title`. A value
 * that does not parse as an [Instant] is logged at WARN and surfaced as raw text
 * (defensive — never throws).
 */
private fun FlowContent.renderTimestampValue(
  value: String,
  display: AdminDisplay,
) {
  val parsed = runCatching { Instant.parse(value) }
  val instant = parsed.getOrNull()
  if (instant == null) {
    cellRenderLog.warn(
      "Unparseable TIMESTAMP value rendered as raw text: [{}]",
      value,
      parsed.exceptionOrNull(),
    )
    +value
  } else {
    val zoned = instant.atZone(display.zone)
    span {
      title = TITLE_FORMAT.format(zoned)
      +DATE_FORMAT.format(zoned)
    }
  }
}

/**
 * The [FieldType.BOOL] body: an allowlist of the two stringified bools. An
 * unexpected value is surfaced as raw text rather than masked as the false glyph.
 */
private fun FlowContent.renderBoolValue(
  value: String,
  display: AdminDisplay,
) {
  when (value) {
    "true" -> span("bool-true") { +display.boolTrueGlyph }
    "false" -> span("bool-false") { +display.boolFalseGlyph }
    else -> +value
  }
}

/**
 * Renders the trailing entity-reference link for a cell: when [refSlug] names a
 * registered admin resource ([AdminDisplay.isSupported]) and [value] is
 * non-blank, a leading non-breaking space followed by the configured link glyph,
 * hyperlinked to that entity's detail page (`/{refSlug}/{value}`). Otherwise
 * renders nothing (an unregistered slug or a blank value yields no link).
 */
fun FlowContent.renderRefLink(
  value: String,
  refSlug: String?,
  display: AdminDisplay,
) {
  if (refSlug == null || value.isBlank() || !display.isSupported(refSlug)) return
  // Non-breaking space as its own text node so the glyph never wraps alone.
  +" "
  a(href = "/$refSlug/$value", classes = "id-link") { +display.idLinkGlyph }
}

/**
 * The composite cell: the typed value followed by the entity-reference glyph
 * link. Every cell renders through this uniformly (RFC 79) — list rows, the
 * detail field table, and all edge-table cells alike. No cell wraps its value
 * text in a hyperlink; the trailing glyph is the sole link to the entity, so
 * navigation to a row's detail page is the primary-id column's own refSlug glyph.
 */
fun FlowContent.renderCell(
  value: String,
  type: FieldType,
  refSlug: String?,
  display: AdminDisplay,
) {
  renderValue(value, type, display)
  renderRefLink(value, refSlug, display)
}
