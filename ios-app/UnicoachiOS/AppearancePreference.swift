import SwiftUI

/// The student's colour-scheme preference, persisted in `UserDefaults` through
/// `@AppStorage` and applied once at the root scene so it covers every auth
/// state — the login screen and the verification screen included, not just the
/// authenticated tree.
///
/// `system` is the default and maps to `nil`, which is SwiftUI's own "follow the
/// device" and not a third colour scheme: the app must not freeze whatever the
/// device happened to be set to when the preference was first written.
enum AppearancePreference: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    /// The `UserDefaults` key, held here so the scene that reads it and the
    /// screen that writes it cannot drift apart.
    static let storageKey = "appearancePreference"

    var id: String { rawValue }

    /// What `.preferredColorScheme` should be given. `nil` means "follow the
    /// device".
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var title: String {
        switch self {
        case .system: return String(localized: "System")
        case .light: return String(localized: "Light")
        case .dark: return String(localized: "Dark")
        }
    }
}
