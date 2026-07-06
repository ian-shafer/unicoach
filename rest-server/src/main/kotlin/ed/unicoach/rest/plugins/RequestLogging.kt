package ed.unicoach.rest.plugins

import ed.unicoach.rest.config.Detail
import ed.unicoach.rest.config.HeaderSelection
import ed.unicoach.rest.config.RequestLoggingConfig
import io.ktor.http.Headers
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The monotonic start stamp for a call, set in the `Setup` phase (before content
 * negotiation and any handler) so a call that later `406`s or `415`s still
 * carries it. Read on completion to compute a monotonic latency, avoiding Ktor's
 * wall-clock `processingTimeMillis`.
 */
val StartNanosKey = AttributeKey<Long>("RequestLogStartNanos")

/**
 * Query-param names whose value must be redacted from the logged `uri`. Stamped
 * route-scoped by [secretQueryParams]; absent (treated as empty) for routes that
 * do not opt in.
 */
val SecretQueryParamsKey = AttributeKey<Set<String>>("RequestLogSecretQueryParams")

/**
 * Logs one line per HTTP request at INFO — method, request URI, and the final
 * response status — self-diagnosing on failure. Installed first in `appModule`
 * so its interceptor wraps the whole pipeline and reports the status the client
 * actually received, including responses short-circuited by a gate (client-key,
 * email-verification) or rewritten by content negotiation (a `406` when `Accept`
 * matches no converter) or by StatusPages (a `415` rewritten to `400`).
 *
 * On an enriched line (see [RequestLoggingConfig.formatLogLine]) it attaches a
 * configurable set of request headers, request/response body sizes, and a
 * monotonic processing-time latency — so the failure is diagnosable from the log
 * alone, without per-status special-casing.
 *
 * [nanoTime] is injected so latency is unit-testable; production uses
 * `System::nanoTime`.
 */
fun Application.configureRequestLogging(
  config: RequestLoggingConfig,
  nanoTime: () -> Long = System::nanoTime,
) {
  intercept(ApplicationCallPipeline.Setup) {
    call.attributes.put(StartNanosKey, nanoTime())
  }

  install(CallLogging) {
    level = Level.INFO
    format { call ->
      // Fail-loud: the Setup interceptor stamps StartNanosKey unconditionally, so
      // its absence is a broken invariant, not a real 0ms — never a silent 0.
      val startNanos = call.attributes[StartNanosKey]
      val latency = (nanoTime() - startNanos).nanoseconds
      config.formatLogLine(
        method = call.request.httpMethod.value,
        uri = call.request.uri,
        status = call.response.status()?.value,
        requestHeaders = call.request.headers,
        responseHeaders = call.response.headers.allValues(),
        latency = latency,
        secretQueryParams = call.attributes.getOrNull(SecretQueryParamsKey) ?: emptySet(),
      )
    }
  }
}

/** Config for [SecretQueryParamsPlugin]: the query-param names to redact. */
class SecretQueryParamsConfig {
  var names: Set<String> = emptySet()
}

/**
 * Route-scoped plugin that stamps its configured [SecretQueryParamsConfig.names]
 * under [SecretQueryParamsKey] in the `Setup` phase — the same timing as the
 * app-wide start-nanos interceptor, before content negotiation and any handler,
 * so a pre-handler failure (`406`, `415`) on the route still carries the set.
 * Installed only on the routes that opt in via [secretQueryParams], so
 * non-opted-in routes never stamp.
 */
val SecretQueryParamsPlugin =
  createRouteScopedPlugin("SecretQueryParams", ::SecretQueryParamsConfig) {
    on(CallSetup) { call ->
      call.attributes.put(SecretQueryParamsKey, pluginConfig.names)
    }
  }

/**
 * Opt a route into query redaction: its matched calls stamp [names] under
 * [SecretQueryParamsKey] in the `Setup` phase (before the handler), so even a
 * pre-handler failure (`406`, `415`) on the route redacts those params. Additive
 * and route-scoped — routes that do not call this are untouched. Installs
 * [SecretQueryParamsPlugin] through the public `Route.install` seam, so no
 * unchecked cast to `ApplicationCallPipeline` is needed.
 */
fun Route.secretQueryParams(vararg names: String) {
  install(SecretQueryParamsPlugin) { this.names = names.toSet() }
}

/**
 * The whole of the diagnostic logic, pure so its guarantees are unit-testable
 * without a running server.
 *
 * A line is enriched when [detail] is `ALWAYS`, the response is absent
 * ([status] null), or the status is `>= 400`. A non-enriched line is the bare
 * `METHOD uri -> status` head. An enriched line appends, space-joined: the
 * request body size, one segment per selected request header, the response body
 * size, and the latency.
 *
 * [secretHeaders] is subtracted last, in both selection modes, by
 * case-insensitive name match — so widening verbosity (`headers="*"`) or listing
 * a secret in an allowlist can never emit its value.
 */
internal fun RequestLoggingConfig.formatLogLine(
  method: String,
  uri: String,
  status: Int?,
  requestHeaders: Headers,
  responseHeaders: Headers,
  latency: Duration,
  secretQueryParams: Set<String>,
): String {
  val safeUri = redactQuery(uri, secretQueryParams)
  val head = "$method $safeUri -> ${status?.toString() ?: "no-response"}"

  val enrich = detail == Detail.ALWAYS || status == null || status >= 400
  if (!enrich) return head

  val segments =
    buildList {
      add("body=[${resolveBodySize(requestHeaders)}]")
      addAll(selectHeaderSegments(requestHeaders))
      add("respBody=[${resolveBodySize(responseHeaders)}]")
      add("latency=[${latency.inWholeMilliseconds}ms]")
    }
  return (listOf(head) + segments).joinToString(" ")
}

/**
 * Interprets the declared body size of a request or response from its headers:
 * `Content-Length` -> `"${n}b"`; else `Transfer-Encoding: chunked` -> `"chunked"`;
 * else `"(none)"`. Shared by the `body=` and `respBody=` fields.
 */
internal fun resolveBodySize(headers: Headers): String {
  val contentLength = headers["Content-Length"]
  if (contentLength != null) return "${contentLength}b"
  val transferEncoding = headers["Transfer-Encoding"]
  if (transferEncoding != null && transferEncoding.split(",").any { it.trim().equals("chunked", ignoreCase = true) }) {
    return "chunked"
  }
  return "(none)"
}

/**
 * Renders the selected request-header segments, with the secret set subtracted
 * last by case-insensitive name match.
 *
 * - [HeaderSelection.All]: every sent header, `Name=[values-joined-by-comma]`.
 * - [HeaderSelection.Allowlist]: each configured name, `Name=[value]` or
 *   `Name=[(absent)]` when unsent.
 */
private fun RequestLoggingConfig.selectHeaderSegments(requestHeaders: Headers): List<String> {
  val selectedNames =
    when (val selection = headers) {
      is HeaderSelection.All -> requestHeaders.names()
      is HeaderSelection.Allowlist -> selection.names
    }
  // Single-source the security-critical secret subtraction: it lives here, once,
  // so it applies identically to both selection modes.
  return selectedNames
    .filter { it.lowercase() !in secretHeaders }
    .map { name -> renderHeaderSegment(requestHeaders, name) }
}

/**
 * Renders one `Name=[value]` segment for [name]: the request's comma-joined
 * values, or `(absent)` when the header was not sent.
 */
private fun renderHeaderSegment(
  requestHeaders: Headers,
  name: String,
): String {
  val values = requestHeaders.getAll(name)
  val rendered = if (values.isNullOrEmpty()) "(absent)" else values.joinToString(",")
  return "$name=[$rendered]"
}

/**
 * Redacts the value of every secret query param in [uri], editing the raw query
 * substring in place so no other character changes: an untouched segment is kept
 * byte-for-byte, and a redacted one becomes `name=[redacted]` (the original,
 * still-encoded name, bracketed per the file convention). Each segment's name is
 * URL-decoded only for the [secretQueryParams] membership test, so a
 * percent-encoded secret name cannot bypass redaction. A `uri` with no `?`, or an
 * empty [secretQueryParams], is returned verbatim.
 */
private fun redactQuery(
  uri: String,
  secretQueryParams: Set<String>,
): String {
  if (secretQueryParams.isEmpty()) return uri
  val queryStart = uri.indexOf('?')
  if (queryStart < 0) return uri

  val path = uri.substring(0, queryStart)
  val query = uri.substring(queryStart + 1)
  // Byte-stable string surgery, deliberately NOT a full reparse/re-encode
  // (parseQueryString): we split on `&` and take each segment's name via
  // `substringBefore("=")`, edit only the segments we redact, and leave every
  // other segment byte-for-byte identical in the log line — a re-encode
  // round-trip would rewrite untouched params (reorder, canonicalize `+`/`%20`)
  // and make the logged uri diverge from the wire. Accepted gap: a `;`-separated
  // query param is not matched. That is an obsolete, effectively-dead separator
  // convention that Ktor's own routing does not split on either, so no such param
  // reaches us; matching it would mean a heavier parse for zero real coverage,
  // and byte-stability is the priority.
  val redactedQuery =
    query
      .split("&")
      .joinToString("&") { segment ->
        val name = segment.substringBefore("=")
        // Decode the name before the membership test so a percent-encoded secret
        // name (e.g. `to%6Ben`) cannot bypass it and leak the value. Untouched
        // segments are still preserved byte-for-byte (no re-encode round-trip).
        if (name.decodeURLQueryComponent() in secretQueryParams) "$name=[redacted]" else segment
      }
  return "$path?$redactedQuery"
}
