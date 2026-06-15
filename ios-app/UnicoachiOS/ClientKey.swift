import Foundation

/// Resolves the client key from a baked `Info.plist` value. Pure and testable:
/// trims `infoValue`; a non-empty trimmed string is the key, while any other
/// input (nil, empty, or whitespace-only) yields `nil` — meaning no key is
/// baked in (e.g. a local build with a blank build setting), so no
/// `X-Unicoach-Client-Key` header is sent and the disabled local gate accepts
/// the request.
func resolveClientKey(_ infoValue: String?) -> String? {
    let trimmed = infoValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return trimmed.isEmpty ? nil : trimmed
}

/// Production entry point: reads the `UnicoachClientKey` key baked into the main
/// bundle's `Info.plist` at build time and resolves it through `resolveClientKey`.
func defaultClientKey() -> String? {
    let infoValue = Bundle.main.object(forInfoDictionaryKey: "UnicoachClientKey") as? String
    return resolveClientKey(infoValue)
}
