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
    /// Fill for primary / SSO controls. Inverts between colour schemes
    /// (near-black in light, white in dark) — the same inversion Sign in with
    /// Apple performs, so the pairing reads as native. DESIGN.md §2/§2.1.
    static let dsControlFill = Color("ControlFill", bundle: .main)
    /// Label colour on `dsControlFill`; inverts with it.
    static let dsControlOnFill = Color("ControlOnFill", bundle: .main)
    /// Text on a `brandAccent` fill. Black in **both** schemes, because the
    /// accent is the same in both: black on `#EE7330` is 7.13:1 while white is
    /// 2.95:1 (DESIGN.md §6). The white `uni.COACH` wordmark is the single
    /// sanctioned exception and uses `brandOnAccent`, not this.
    static let dsOnBrandAccent = Color("OnBrandAccent", bundle: .main)
}

// MARK: - Gradient tokens

/// The brand gradient (DESIGN.md §1). It is chrome and selection accent only —
/// never a large tappable surface, because white on `#EE7330` is 2.95:1 (§6).
/// Direction is part of the token, not the call site: leading→trailing for
/// horizontal chrome, topLeading→bottomTrailing for the circular logo mark.
enum DSGradient {
    /// `#EE7330`. Deliberately not recoloured for dark: the pair sits at 6.6:1
    /// on `#0E0E10`, better than its 2.95:1 on white, so both modes share it.
    static let start = Color("BrandGradientStart", bundle: .main)
    /// `#E94577`.
    static let end = Color("BrandGradientEnd", bundle: .main)

    /// Horizontal chrome (top bar).
    static let brand = LinearGradient(
        colors: [start, end],
        startPoint: .leading,
        endPoint: .trailing
    )

    /// The circular logo mark.
    static let brandDiagonal = LinearGradient(
        colors: [start, end],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

// MARK: - Typography tokens

extension Font {
    /// Screen headings on branded screens ("When will you graduate?").
    static let dsDisplay = Font.system(.largeTitle, design: .default).weight(.heavy)
    static let dsTitleXL = Font.system(.largeTitle, design: .default).weight(.bold)
    static let dsTitle = Font.system(.title2, design: .default).weight(.bold)
    static let dsBody = Font.system(.body, design: .default).weight(.regular)
    static let dsLabel = Font.system(.subheadline, design: .default).weight(.medium)
    static let dsCaption = Font.system(.caption, design: .default).weight(.regular)
    static let dsButton = Font.system(.headline, design: .default).weight(.semibold)
    /// Small uppercase eyebrow ("WELCOME, KENDALL"). Apply `.dsOverlineTracking`
    /// and `.textCase(.uppercase)` with it — see `Text.dsOverlineStyle()`.
    static let dsOverline = Font.system(.caption, design: .default).weight(.semibold)
    /// Option-card labels, far larger than list text normally is.
    static let dsOption = Font.system(.title3, design: .default).weight(.bold)

    /// The logo mark's `U`, sized from the circle that contains it. The mark is
    /// artwork rather than copy, so it scales with its container instead of
    /// with Dynamic Type — which is why this is the one size-taking token.
    static func dsLogoGlyph(diameter: CGFloat) -> Font {
        .system(size: diameter * DSLogo.glyphFraction, weight: .heavy, design: .default)
    }
}

/// Proportions of the circular logo mark (DESIGN.md §5).
enum DSLogo {
    /// The mark's diameter as a fraction of its container's width, on login.
    static let widthFraction: CGFloat = 0.62
    /// The `U`'s cap height as a fraction of the mark's diameter.
    static let glyphFraction: CGFloat = 0.62
}

/// Tracking that belongs with `Font.dsOverline` (~0.08em of a 12pt caption).
/// Held next to the font token so the pair cannot drift apart.
enum DSTracking {
    static let overline: CGFloat = 0.96
}

extension Text {
    /// The complete overline treatment: token font, uppercase, and tracking.
    /// A view applies this rather than restating the three parts.
    func dsOverlineStyle() -> some View {
        self
            .font(.dsOverline)
            .textCase(.uppercase)
            .tracking(DSTracking.overline)
    }
}

// MARK: - Spacing, radius, and opacity tokens

enum DSSpacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    /// Screen horizontal margin (DESIGN.md §3).
    static let lg: CGFloat = 24
    static let xl: CGFloat = 40
}

enum DSRadius {
    /// One radius for buttons, fields, and option cards (DESIGN.md §3).
    /// Emphatically not a capsule: a capsule at 64pt would be 32.
    static let control: CGFloat = 16
}

/// The control rhythm shared by buttons, fields, and option cards.
enum DSControl {
    /// Chunky by iOS standards (44–50pt is the norm) — measured off the
    /// reference at 64.
    static let height: CGFloat = 64
    /// Vertical gap between stacked controls.
    static let stackGap: CGFloat = 12
    /// Hairline width. Borders carry the layout; there are no shadows and no
    /// elevation anywhere in this design.
    static let borderWidth: CGFloat = 1
    /// Leading text inset inside a field.
    static let textInset: CGFloat = 20
    /// Diameter of an option card's radio circle.
    static let radioDiameter: CGFloat = 22
    /// Content height of the branded top bar (excluding the status bar it
    /// extends under).
    static let topBarHeight: CGFloat = 44
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
