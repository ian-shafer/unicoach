package ed.unicoach.admin.render

import ed.unicoach.admin.DisplayConfig
import ed.unicoach.admin.engine.FieldType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.span
import kotlinx.html.title
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
    isSupported = isSupported,
  )

/** `MMM d, yyyy` in [Locale.ENGLISH] — e.g. `Jan 3, 2026`. */
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

private val cellRenderLog = LoggerFactory.getLogger("ed.unicoach.admin.CellRender")

/**
 * Renders the typed value only — no entity link. The single place the datetime
 * and boolean conventions live.
 *
 * - [FieldType.TIMESTAMP]: the source instant formatted as `MMM d, yyyy` in
 *   [AdminDisplay.zone], carrying the verbatim source ISO string as a hover
 *   `title`. A blank value renders nothing; a value that does not parse as an
 *   [Instant] renders its raw text (defensive — never throws).
 * - [FieldType.BOOL]: the configured true glyph in `bool-true` when the value is
 *   `"true"`, the configured false glyph in `bool-false` when it is `"false"`.
 *   A blank value renders nothing. (`cells()` always stringifies bools as
 *   `"true"`/`"false"`.) Any other value is surfaced as raw text rather than
 *   masked as false, so an unexpected value is visible.
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
    FieldType.TEXT, FieldType.MULTILINE, FieldType.INT, FieldType.JSON, FieldType.ENUM -> +value
  }
}

/**
 * The [FieldType.TIMESTAMP] body: the source instant formatted as `MMM d, yyyy`
 * in [AdminDisplay.zone], carrying the verbatim source ISO string as a hover
 * `title`. A value that does not parse as an [Instant] is logged at WARN and
 * surfaced as raw text (defensive — never throws).
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
    span {
      title = value
      +DATE_FORMAT.format(instant.atZone(display.zone))
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
