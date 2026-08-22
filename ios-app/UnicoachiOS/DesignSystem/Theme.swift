import SwiftUI

// MARK: - Color tokens

extension Color {
    static let brandAccent = Color("BrandAccent", bundle: .main)
    static let brandOnAccent = Color("BrandOnAccent", bundle: .main)
    static let dsBackground = Color("Background", bundle: .main)
    static let dsSurface = Color("Surface", bundle: .main)
    static let dsTextPrimary = Color("TextPrimary", bundle: .main)
    static let dsTextSecondary = Color("TextSecondary", bundle: .main)
    static let dsError = Color("Error", bundle: .main)
    static let dsFieldBorder = Color("FieldBorder", bundle: .main)
}

// MARK: - Typography tokens

extension Font {
    static let dsTitleXL = Font.system(.largeTitle, design: .default).weight(.bold)
    static let dsTitle = Font.system(.title2, design: .default).weight(.semibold)
    static let dsBody = Font.system(.body, design: .default).weight(.regular)
    static let dsLabel = Font.system(.subheadline, design: .default).weight(.medium)
    static let dsCaption = Font.system(.caption, design: .default).weight(.regular)
    static let dsButton = Font.system(.headline, design: .default).weight(.semibold)
}

// MARK: - Spacing, radius, and opacity tokens

enum DSSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 40
}

enum DSRadius {
    static let field: CGFloat = 10
    static let button: CGFloat = 12
}

/// Opacity tokens for interactive state feedback, shared by the design system's
/// button styles and by wrapped UIKit controls that must dim to match them.
/// Held here rather than in each style so a wrapped control (which cannot use a
/// `ButtonStyle`) dims identically by construction instead of by a copied
/// literal.
enum DSOpacity {
    static let enabled: Double = 1.0
    static let pressed: Double = 0.8
    static let disabled: Double = 0.5
}
