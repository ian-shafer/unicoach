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
    /// The slide-over menu's scrim. Deliberately **not** built from an
    /// inverting token: a scrim dims in both schemes, and `dsTextPrimary` — white
    /// in dark mode — would brighten the screen it is supposed to push back.
    /// The literal lives here, in the token layer, so no view carries one.
    static let dsScrim = Color.black.opacity(DSOpacity.scrim)
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
    /// Glyphs in the brand top bar. A symbol set at `dsButton` — the wordmark's
    /// own size — reads weedy beside a bold wordmark, because a line symbol
    /// carries far less ink than text at the same point size. Sized up and kept
    /// as a token so the bar's glyphs cannot drift apart. The 44pt tap target is
    /// independent of this and lives in `BrandTopBarButton`.
    static let dsTopBarGlyph = Font.system(.title2, design: .default).weight(.semibold)

    /// Fenced and inline code in a rendered coach reply. Monospaced because
    /// code's alignment *is* its meaning, at `.body` so it sits at the same
    /// optical size as the prose around it and grows with Dynamic Type like
    /// every other token here.
    static let dsCode = Font.system(.body, design: .monospaced)

    /// The logo mark's `U`, sized from the circle that contains it. The mark is
    /// artwork rather than copy, so it scales with its container instead of
    /// with Dynamic Type — which is why this is the one size-taking token.
    static func dsLogoGlyph(diameter: CGFloat) -> Font {
        .system(size: diameter * DSLogo.glyphFraction, weight: .heavy, design: .default)
    }
}

/// Proportions of the brand artwork (DESIGN.md §5) — the circular logo mark and
/// the `uni.COACH` wordmark.
enum DSLogo {
    /// The mark's diameter as a fraction of its container's width, on login.
    static let widthFraction: CGFloat = 0.62
    /// The `U`'s cap height as a fraction of the mark's diameter.
    static let glyphFraction: CGFloat = 0.62

    /// Ceiling on the wordmark's Dynamic Type growth. The wordmark is a
    /// **logotype — artwork, not copy** — the same argument that sizes the logo
    /// mark's `U` from its circle rather than from the type scale. Uncapped it
    /// hyphenated to "uni.-COACH" at accessibility sizes and tripled the top
    /// bar's height on every branded screen, taking a third of the screen from
    /// the reader who has least to spare.
    ///
    /// A ceiling rather than a fixed size: the wordmark still responds across
    /// the standard range, it just does not follow text into the accessibility
    /// range. Controls in the bar are **not** capped — a control that refuses
    /// to grow is an accessibility regression. VoiceOver is unaffected: the
    /// label and the header trait are untouched.
    static let wordmarkMaxDynamicTypeSize: DynamicTypeSize = .xxxLarge

    /// Backstop so a narrow device shrinks the wordmark rather than truncating
    /// it. It must never wrap, and it must never show an ellipsis either.
    static let wordmarkMinScale: CGFloat = 0.75
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
    /// Height of the coaching-usage meter's track. A meter is not a control:
    /// it is read, never tapped, so it is far shorter than `height` while
    /// keeping the same 16pt radius as everything else that reads as a
    /// container. A token rather than a literal so the one magic number in the
    /// meter lives with the rest of the rhythm.
    static let meterHeight: CGFloat = 14
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
    /// The slide-over menu's scrim. A **dim, not a shadow** — this design has no
    /// elevation (DESIGN.md §3); the scrim exists to push the covered screen
    /// back, and the drawer is still separated from it by a hairline.
    static let scrim: Double = 0.45
}

/// Rendered Markdown in a coach reply (DESIGN.md §8.1). Only the measurements
/// that Markdown adds and the existing tokens cannot express — everything else
/// in `MarkdownView` reads `DSSpacing` / `DSRadius` / `DSControl` directly.
enum DSMarkdown {
    /// Leading gutter reserved for a list marker. Fixed so every item's text
    /// starts on one column: sizing it to the marker would step "9." and "10."
    /// apart and make a long list read as ragged. Applied as a `@ScaledMetric`
    /// so a two-digit marker at accessibility sizes still fits.
    static let markerWidth: CGFloat = 24
    /// The grid/stack threshold: the narrowest a table column may be and still
    /// read as a column. A table that cannot give every column this much is
    /// drawn as one block per row instead (RFC 120), so this number decides
    /// which of the two layouts a table gets — it is not merely a floor.
    ///
    /// Set from a captured render, not from taste
    /// (`.scratch/ship/rfc-120/artifacts/threshold-sweep*.png`): ordinary cells
    /// — "Not started", "Public (CC)", "In progress", "Michigan" — drawn at
    /// `dsBody` in candidate widths from 64 to 104. At 64 "Michigan" hyphenated
    /// into "Mi-chigan" and "In progress" broke a word across lines; at 72–86
    /// nothing hyphenated but every two-word cell wrapped onto a second line,
    /// which is a column too narrow to be one. 88 is the narrowest width where
    /// all of them set on a single line, and it was preferred to 96 because a
    /// lower threshold keeps a grid — the better layout when it is honest — for
    /// more tables.
    ///
    /// It was 64 when it was only a floor. Raising it is why the five-column
    /// fixture now stacks rather than squeezing to a hyphenated grid.
    static let columnMinWidth: CGFloat = 88
    /// Ceiling on a table column, so a prose column wraps instead of
    /// monopolising the row and pushing every other column off screen.
    /// A `@ScaledMetric` at the call site: the grid must grow with text rather
    /// than clip it.
    static let columnMaxWidth: CGFloat = 220
}

/// The slide-over menu (DESIGN.md §7).
enum DSMenu {
    /// The drawer's width as a fraction of the screen, expressed proportionally
    /// so it survives device sizes rather than being transcribed from one.
    /// Enough of the scrim stays visible to be tappable and to read as "the app
    /// is still behind this".
    static let widthFraction: CGFloat = 0.78

    /// How many recent conversations the drawer lists. The drawer is a fast
    /// switcher for the common case, not a browser: a menu that scrolls hides
    /// its own footer behind a gesture and has no determinate height. Past this
    /// handful, "All conversations" is the complete surface, one tap away.
    static let recentLimit = 3
}

// MARK: - Date rendering

extension Date {
    /// The one rendering of a user-visible calendar date. The meter's
    /// "Resets ⟨date⟩", the paywall's "It resets ⟨date⟩" and the subscription
    /// status line's "renews ⟨date⟩" can appear on the same screen for the same
    /// instant, so they must say it the same way — which they only do if the
    /// style is written down once rather than transcribed into each call site.
    var dsCalendarDate: String {
        formatted(date: .abbreviated, time: .omitted)
    }
}
