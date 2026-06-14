import Foundation

/// The compile-time fallback backend the app targets when no URL is baked into
/// the bundle (e.g. the simulator/test default, or an empty/garbage build
/// setting). On a device this localhost points at the phone itself, so a real
/// on-device build MUST override `UNICOACH_BACKEND_URL` to a reachable host.
private let fallbackBackendURL = URL(string: "http://localhost:8080")!

/// Resolves the backend base URL from a baked `Info.plist` value, falling back
/// to localhost. Pure and testable: trims `infoValue`; if the trimmed string is
/// non-empty and parses to a valid absolute URL, that URL is returned; any other
/// input (nil, empty, whitespace-only, or an unparseable/relative value) yields
/// the localhost fallback rather than crashing.
func resolveBackendURL(_ infoValue: String?) -> URL {
    let trimmed = infoValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard !trimmed.isEmpty,
          let url = URL(string: trimmed),
          url.scheme != nil,
          url.host != nil else {
        return fallbackBackendURL
    }
    return url
}

/// Production entry point: reads the `UnicoachBackendURL` key baked into the main
/// bundle's `Info.plist` at build time and resolves it through `resolveBackendURL`.
func defaultBackendURL() -> URL {
    let infoValue = Bundle.main.object(forInfoDictionaryKey: "UnicoachBackendURL") as? String
    return resolveBackendURL(infoValue)
}
