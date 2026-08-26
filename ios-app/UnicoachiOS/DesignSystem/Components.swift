import SwiftUI

// MARK: - Button styles

/// The primary control: a `ControlFill` box at the shared 64pt/16pt control
/// rhythm. The fill inverts between colour schemes (near-black label-on-white in
/// dark), which is deliberate — see `Color.dsControlFill`.
///
/// It is emphatically **not** the brand gradient or `brandAccent`: white on
/// `#EE7330` is 2.95:1 (DESIGN.md §6), so the brand colour is chrome and
/// selection only, never a large tappable surface.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    // The 64pt control height is a measured value, not a raw one: scaling it
    // relative to .headline (the label's text style) keeps the box around the
    // label at larger Dynamic Type sizes instead of clipping it.
    @ScaledMetric(relativeTo: .headline) private var height: CGFloat = DSControl.height

    init() {}

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsControlOnFill)
            // Intrinsic horizontal inset so the control has breathing room even in
            // content-sized containers (e.g. ContentUnavailableView actions); the
            // maxWidth below still stretches it edge-to-edge in full-width forms.
            .padding(.horizontal, DSSpacing.lg)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(Color.dsControlFill)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return DSOpacity.disabled }
        return isPressed ? DSOpacity.pressed : DSOpacity.enabled
    }
}

/// The destructive control. Same 64pt/16pt box as `PrimaryButtonStyle`, but
/// outlined rather than filled: this design communicates depth by border alone
/// (DESIGN.md §3), so the tinted wash the style used to carry is gone and the
/// `dsError` semantics live in the label and the hairline.
struct DestructiveButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    @ScaledMetric(relativeTo: .headline) private var height: CGFloat = DSControl.height

    init() {}

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsError)
            // Intrinsic horizontal inset so the control has breathing room even in
            // content-sized containers; the maxWidth below still stretches it
            // edge-to-edge in full-width forms.
            .padding(.horizontal, DSSpacing.lg)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                    .stroke(Color.dsError, lineWidth: DSControl.borderWidth)
            )
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return DSOpacity.disabled }
        return isPressed ? DSOpacity.pressed : DSOpacity.enabled
    }
}

// MARK: - LoadingButton

enum LoadingButtonRole {
    case primary
    case destructive
}

struct LoadingButton: View {
    private let title: String
    private let isLoading: Bool
    private let role: LoadingButtonRole
    private let accessibilityIdentifier: String?
    private let accessibilityLabelText: String?
    private let progressAccessibilityIdentifier: String?
    private let action: () -> Void

    init(
        _ title: String,
        isLoading: Bool,
        role: LoadingButtonRole = .primary,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        progressAccessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isLoading = isLoading
        self.role = role
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.progressAccessibilityIdentifier = progressAccessibilityIdentifier
        self.action = action
    }

    var body: some View {
        styledButton
            .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
            .modifier(OptionalLabel(label: accessibilityLabelText))
    }

    @ViewBuilder
    private var styledButton: some View {
        let button = Button(action: action) {
            if isLoading {
                progressView
            } else {
                Text(title)
            }
        }
        .disabled(isLoading)

        switch role {
        case .primary:
            button.buttonStyle(PrimaryButtonStyle())
        case .destructive:
            button.buttonStyle(DestructiveButtonStyle())
        }
    }

    /// The spinner is tinted for the box it sits in: the primary control is a
    /// near-black (light) / white (dark) fill, where an untinted system spinner
    /// all but disappears; the destructive control is an outlined surface.
    private var spinnerTint: Color {
        switch role {
        case .primary: return Color.dsControlOnFill
        case .destructive: return Color.dsError
        }
    }

    @ViewBuilder
    private var progressView: some View {
        if let progressAccessibilityIdentifier {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(spinnerTint)
                .accessibilityIdentifier(progressAccessibilityIdentifier)
        } else {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(spinnerTint)
        }
    }
}

private struct OptionalIdentifier: ViewModifier {
    let identifier: String?

    func body(content: Content) -> some View {
        if let identifier {
            content.accessibilityIdentifier(identifier)
        } else {
            content
        }
    }
}

private struct OptionalLabel: ViewModifier {
    let label: String?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(label)
        } else {
            content
        }
    }
}

// MARK: - DSTextButton

/// How much weight a text button carries. Both are the same plain, centred
/// text control — the distinction is only ever colour, so it is a role rather
/// than a colour parameter no call site could get wrong in an interesting way.
enum DSTextButtonRole {
    /// The action the screen is offering: `dsTextPrimary`.
    case primary
    /// A way out of the screen — "Done", "Not now": `dsTextSecondary`, so it
    /// sits behind the action above it without becoming invisible.
    case secondary
}

/// The plain, centred text button: no fill, no border, full width, `dsLabel`
/// type. It is what the subscription surfaces use for everything that is not
/// the one filled `PrimaryButtonStyle` control — Restore Purchases, Manage
/// subscription, "Not now", "Done".
///
/// **Not `brandAccent`.** `#EE732F` on white is 2.95:1 (DESIGN.md §6), so the
/// brand colour is chrome and selection only and never a text control's
/// foreground. That reasoning is stated **here, once**: it was previously
/// re-argued in a comment at each hand-rolled copy, which is exactly how four
/// copies of one rule become three copies and an exception.
///
/// **The 44pt target is not optional.** The hand-rolled copies this replaces
/// disagreed about whether they carried `minHeight: DSControl.tapTarget` —
/// `dsLabel` alone is roughly half of it — so two of the four were below the
/// platform's minimum tap target while looking identical to the two that were
/// not. A primitive that always applies the floor is the only version of this
/// control that cannot drift back.
///
/// `loadingTitle` is for the one site that has work to report (Restore, mid
/// `AppStore.sync()`): the title swaps and the control disables, rather than a
/// spinner replacing the words — this is a secondary action, and a text button
/// that becomes a spinner reads as the screen having lost a control.
struct DSTextButton: View {
    private let title: String
    private let role: DSTextButtonRole
    private let isLoading: Bool
    private let loadingTitle: String?
    private let accessibilityIdentifier: String
    private let accessibilityLabelText: String
    private let accessibilityHintText: String?
    private let action: () -> Void

    init(
        _ title: String,
        role: DSTextButtonRole = .primary,
        isLoading: Bool = false,
        loadingTitle: String? = nil,
        accessibilityIdentifier: String,
        accessibilityLabel: String,
        accessibilityHint: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.role = role
        self.isLoading = isLoading
        self.loadingTitle = loadingTitle
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.accessibilityHintText = accessibilityHint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(isLoading ? (loadingTitle ?? title) : title)
                .font(.dsLabel)
                .foregroundStyle(foreground)
                // Both the width and the 44pt floor, then a rectangular
                // content shape over them: the glyphs are a fraction of the
                // box, and without the shape only the letters are tappable.
                .frame(maxWidth: .infinity, minHeight: DSControl.tapTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Work in flight is the one state that takes the tap away: the title
        // has already changed to say so, and a second tap would start the same
        // work twice.
        .disabled(isLoading)
        .accessibilityIdentifier(accessibilityIdentifier)
        // The label is spoken from here, so a swapped `loadingTitle` never
        // changes what VoiceOver calls the control.
        .accessibilityLabel(accessibilityLabelText)
        .modifier(OptionalHint(hint: accessibilityHintText))
    }

    private var foreground: Color {
        switch role {
        case .primary: return Color.dsTextPrimary
        case .secondary: return Color.dsTextSecondary
        }
    }
}

private struct OptionalHint: ViewModifier {
    let hint: String?

    func body(content: Content) -> some View {
        if let hint {
            content.accessibilityHint(hint)
        } else {
            content
        }
    }
}

// MARK: - DSSheetScroll

/// The scaffold every full-screen sheet in this app is built on: a scrolling,
/// leading-aligned column at the screen margin over `dsBackground`.
///
/// Extracted because `PaywallView` and `SubscriptionView` had it transcribed
/// line for line — the `ScrollView`, the `DSSpacing.lg` column, the
/// `DSSpacing.lg` padding, both `frame`s and the background — and five
/// modifiers copied between two files is five chances for one sheet to be
/// margined or aligned unlike the other while both look deliberate.
///
/// It is the **container only**. A sheet's own `.task`, `.onChange` and
/// `dismiss` stay at the call site: what a screen loads and when it leaves are
/// that screen's rules, not the scaffold's, and hoisting them here would make
/// this a base class in a struct's clothing.
struct DSSheetScroll<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                content
            }
            .padding(DSSpacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

// MARK: - CircularIconButton

struct CircularIconButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    init() {}

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.dsButton)
            .foregroundStyle(Color.dsControlOnFill)
            // Symmetric padding around the intrinsic glyph; combined with
            // .clipShape(Circle()) this yields a circle sized from its content
            // (no fixed point size or raw diameter), satisfying the module's
            // token-only and Dynamic-Type invariants.
            .padding(DSSpacing.sm)
            // ControlFill, not brandAccent: an arrow on `#EE7330` is 2.95:1
            // (DESIGN.md §6), and the brand colour is chrome, not a control fill.
            .background(Color.dsControlFill)
            .clipShape(Circle())
            .opacity(opacity(isPressed: configuration.isPressed))
    }

    private func opacity(isPressed: Bool) -> Double {
        if !isEnabled { return DSOpacity.disabled }
        return isPressed ? DSOpacity.pressed : DSOpacity.enabled
    }
}

struct CircularIconButton: View {
    private let systemImage: String
    private let isLoading: Bool
    private let accessibilityIdentifier: String?
    private let accessibilityLabelText: String?
    private let progressAccessibilityIdentifier: String?
    private let action: () -> Void

    init(
        systemImage: String,
        isLoading: Bool,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        progressAccessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.isLoading = isLoading
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.progressAccessibilityIdentifier = progressAccessibilityIdentifier
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            if isLoading {
                progressView
            } else {
                Image(systemName: systemImage)
            }
        }
        .disabled(isLoading)
        .buttonStyle(CircularIconButtonStyle())
        .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
        .modifier(OptionalLabel(label: accessibilityLabelText))
    }

    @ViewBuilder
    private var progressView: some View {
        if let progressAccessibilityIdentifier {
            ProgressView()
                .progressViewStyle(.circular)
                // ProgressView ignores the style's foregroundStyle; .tint colors
                // the spinner so it reads against the ControlFill circle.
                .tint(Color.dsControlOnFill)
                .accessibilityIdentifier(progressAccessibilityIdentifier)
        } else {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsControlOnFill)
        }
    }
}

// MARK: - LabeledField

struct LabeledField<Value: Hashable>: View {
    private let label: String
    private let text: Binding<String>
    private let isSecure: Bool
    private let error: String?
    private let focus: FocusState<Value?>.Binding
    private let equals: Value
    private let keyboardType: UIKeyboardType
    private let autocapitalization: TextInputAutocapitalization
    private let disableAutocorrection: Bool
    private let submitLabel: SubmitLabel
    private let accessibilityIdentifier: String?
    private let accessibilityLabelText: String?
    private let onSubmit: () -> Void

    @ScaledMetric(relativeTo: .body) private var height: CGFloat = DSControl.height

    init(
        _ label: String,
        text: Binding<String>,
        isSecure: Bool = false,
        error: String? = nil,
        focus: FocusState<Value?>.Binding,
        equals: Value,
        keyboardType: UIKeyboardType = .default,
        autocapitalization: TextInputAutocapitalization = .never,
        disableAutocorrection: Bool = true,
        submitLabel: SubmitLabel = .next,
        accessibilityIdentifier: String? = nil,
        accessibilityLabel: String? = nil,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.label = label
        self.text = text
        self.isSecure = isSecure
        self.error = error
        self.focus = focus
        self.equals = equals
        self.keyboardType = keyboardType
        self.autocapitalization = autocapitalization
        self.disableAutocorrection = disableAutocorrection
        self.submitLabel = submitLabel
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(label)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)

            inputField
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .keyboardType(keyboardType)
                .textInputAutocapitalization(autocapitalization)
                .disableAutocorrection(disableAutocorrection)
                .submitLabel(submitLabel)
                .focused(focus, equals: equals)
                .onSubmit(onSubmit)
                // 20pt leading inset and the 64pt control height (DESIGN.md §5),
                // so a field and a button read as the same kind of object.
                .padding(.horizontal, DSControl.textInset)
                .frame(maxWidth: .infinity, minHeight: height, alignment: .leading)
                .background(Color.dsSurface)
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                        .stroke(error == nil ? Color.dsFieldBorder : Color.dsError, lineWidth: DSControl.borderWidth)
                )
                .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
                .modifier(OptionalLabel(label: accessibilityLabelText))

            if let error {
                FieldErrorText(error)
            }
        }
    }

    @ViewBuilder
    private var inputField: some View {
        if isSecure {
            SecureField(label, text: text)
        } else {
            TextField(label, text: text)
        }
    }
}

// MARK: - OptionCard

/// The reference's signature control: a 64pt, 16pt-radius outlined card with a
/// leading radio circle and a large `dsOption` label (DESIGN.md §5). Selection
/// is carried by the radio's `brandAccent` fill and by the card's border
/// darkening — never by a filled card, because a saturated tappable surface is
/// exactly what the palette forbids (§6).
struct OptionCard: View {
    private let title: String
    private let isSelected: Bool
    private let accessibilityIdentifier: String?
    private let action: () -> Void

    @ScaledMetric(relativeTo: .title3) private var height: CGFloat = DSControl.height
    @ScaledMetric(relativeTo: .title3) private var radioDiameter: CGFloat = DSControl.radioDiameter

    init(
        _ title: String,
        isSelected: Bool,
        accessibilityIdentifier: String? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.isSelected = isSelected
        self.accessibilityIdentifier = accessibilityIdentifier
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: DSSpacing.md) {
                radio
                Text(title)
                    .font(.dsOption)
                    .foregroundStyle(Color.dsTextPrimary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, DSControl.textInset)
            .frame(maxWidth: .infinity, minHeight: height, alignment: .leading)
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                    .stroke(borderColor, lineWidth: DSControl.borderWidth)
            )
            .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
    }

    /// The selected card's border darkens to `TextPrimary`; unselected cards
    /// keep the layout-carrying `FieldBorder` hairline.
    private var borderColor: Color {
        isSelected ? Color.dsTextPrimary : Color.dsFieldBorder
    }

    private var radio: some View {
        ZStack {
            Circle()
                .strokeBorder(isSelected ? Color.brandAccent : Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                .frame(width: radioDiameter, height: radioDiameter)

            if isSelected {
                Circle()
                    .fill(Color.brandAccent)
                    // Inset by two hairlines so the ring stays visible around
                    // the fill, as it does in the reference.
                    .frame(
                        width: radioDiameter - DSControl.borderWidth * 4,
                        height: radioDiameter - DSControl.borderWidth * 4
                    )
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - SegmentedSelector

/// A segmented choice in the motif's vocabulary, replacing SwiftUI's
/// `.pickerStyle(.segmented)` — which renders a stock grey capsule that
/// contradicts both §2 ("no grey fills; separation is by border, not by tint")
/// and §3 ("emphatically not a capsule").
///
/// One outlined `DSRadius.control` container holding N segments; the selected
/// segment carries a `brandAccent` fill with a **black** label. Black, not
/// white: this is the selection accent §1 sanctions, and black on `#EE7330` is
/// 7.13:1 where white is 2.95:1 (§6).
struct SegmentedSelector<Tag: Hashable>: View {
    private let options: [(tag: Tag, title: String)]
    private let selection: Binding<Tag>
    private let accessibilityIdentifier: String?

    @ScaledMetric(relativeTo: .subheadline) private var height: CGFloat = DSControl.height

    init(
        options: [(tag: Tag, title: String)],
        selection: Binding<Tag>,
        accessibilityIdentifier: String? = nil
    ) {
        self.options = options
        self.selection = selection
        self.accessibilityIdentifier = accessibilityIdentifier
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.tag) { option in
                segment(option)
            }
        }
        .padding(DSSpacing.xs)
        .frame(maxWidth: .infinity, minHeight: height)
        .background(Color.dsSurface)
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
        )
        .modifier(OptionalIdentifier(identifier: accessibilityIdentifier))
    }

    private func segment(_ option: (tag: Tag, title: String)) -> some View {
        let isSelected = selection.wrappedValue == option.tag
        return Button {
            selection.wrappedValue = option.tag
        } label: {
            Text(option.title)
                .font(.dsLabel)
                .foregroundStyle(isSelected ? Color.dsOnBrandAccent : Color.dsTextPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(isSelected ? Color.brandAccent : Color.clear)
                // The inner radius is the outer one less the container inset, so
                // the selected segment's corners stay concentric with the box.
                .clipShape(RoundedRectangle(cornerRadius: DSRadius.control - DSSpacing.xs, style: .continuous))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - BrandTopBar

/// Full-bleed brand chrome: the gradient runs edge to edge and extends under
/// the status bar, carrying the `uni.COACH` wordmark leading-aligned in white
/// (DESIGN.md §5). This replaces the stock `.navigationTitle` bar on branded
/// screens.
///
/// The white wordmark on the gradient is 2.95:1 and is the **single sanctioned
/// exception** to §6 — it is a logotype, not copy. Nothing else may follow it
/// onto the gradient: any other text there must be black, or the gradient
/// darkened for that surface.
///
/// An optional **leading accessory** may precede the wordmark — the slide-over
/// menu button at the root of the authenticated tree (DESIGN.md §7). It is a
/// generic slot rather than a baked-in button so the bar stays a chrome
/// primitive; screens without one keep calling `BrandTopBar()`.
struct BrandTopBar<LeadingAccessory: View>: View {
    private let leadingAccessory: LeadingAccessory

    init(@ViewBuilder leadingAccessory: () -> LeadingAccessory) {
        self.leadingAccessory = leadingAccessory()
    }

    var body: some View {
        HStack(spacing: DSSpacing.md) {
            leadingAccessory
            Text("uni.COACH")
                .font(.dsButton)
                .foregroundStyle(Color.brandOnAccent)
                // Artwork, not copy: capped growth, and it may never wrap or
                // hyphenate. Shrink-to-fit is the backstop on a narrow device,
                // so it is never truncated either. The accessibility label and
                // the header trait are deliberately NOT reduced with it.
                .lineLimit(1)
                .minimumScaleFactor(DSLogo.wordmarkMinScale)
                .dynamicTypeSize(...DSLogo.wordmarkMaxDynamicTypeSize)
                .accessibilityAddTraits(.isHeader)
                .accessibilityLabel("uni.COACH")
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DSSpacing.lg)
        .frame(maxWidth: .infinity, minHeight: DSControl.topBarHeight)
        .background(DSGradient.brand.ignoresSafeArea(edges: .top))
    }
}

extension BrandTopBar where LeadingAccessory == EmptyView {
    /// The bar with no accessory: chrome only.
    init() {
        self.init(leadingAccessory: { EmptyView() })
    }
}

/// A control sized for the top bar's gradient. The glyph is **black**, not the
/// wordmark's white: white on `#EE7330` is 2.95:1 and the logotype is the one
/// sanctioned exception (DESIGN.md §6), so anything that follows it onto the
/// gradient takes `dsOnBrandAccent` — the same rule the segmented control's
/// selected label follows. The box is the bar's own content height, which is
/// also the platform's minimum tap target.
struct BrandTopBarButton: View {
    private let systemImage: String
    private let accessibilityIdentifier: String
    private let accessibilityLabelText: String
    private let action: () -> Void

    init(
        systemImage: String,
        accessibilityIdentifier: String,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.accessibilityIdentifier = accessibilityIdentifier
        self.accessibilityLabelText = accessibilityLabel
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.dsTopBarGlyph)
                .foregroundStyle(Color.dsOnBrandAccent)
                .frame(minWidth: DSControl.topBarHeight, minHeight: DSControl.topBarHeight)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier)
        .accessibilityLabel(accessibilityLabelText)
    }
}

// MARK: - StepIndicator

/// N circles joined by a 1pt rail, the current step filled (DESIGN.md §5).
struct StepIndicator: View {
    private let count: Int
    private let current: Int

    @ScaledMetric(relativeTo: .caption) private var diameter: CGFloat = 12

    /// - Parameters:
    ///   - count: total number of steps; values below 1 render nothing.
    ///   - current: zero-based index of the step in progress.
    init(count: Int, current: Int) {
        self.count = count
        self.current = current
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0 ..< max(count, 0), id: \.self) { index in
                if index > 0 { rail }
                circle(isCurrent: index == current)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(current + 1) of \(count)")
    }

    private var rail: some View {
        Rectangle()
            .fill(Color.dsTextPrimary)
            .frame(height: DSControl.borderWidth)
    }

    @ViewBuilder
    private func circle(isCurrent: Bool) -> some View {
        ZStack {
            Circle()
                .fill(Color.dsBackground)
            Circle()
                .strokeBorder(Color.dsTextPrimary, lineWidth: DSControl.borderWidth)
            if isCurrent {
                Circle()
                    .fill(Color.dsTextPrimary)
                    .padding(DSSpacing.xs / 2)
            }
        }
        .frame(width: diameter, height: diameter)
    }
}

// MARK: - LogoMark

/// The circular brand mark: a topLeading→bottomTrailing gradient carrying a
/// heavy white `U`, sized as a fraction of its container's width (DESIGN.md §5).
///
/// The mark is artwork, not copy, so its glyph scales with the circle rather
/// than with Dynamic Type — the one place a size-taking font token is correct.
struct LogoMark: View {
    private let widthFraction: CGFloat

    init(widthFraction: CGFloat = DSLogo.widthFraction) {
        self.widthFraction = widthFraction
    }

    var body: some View {
        Circle()
            .fill(DSGradient.brandDiagonal)
            .overlay {
                GeometryReader { proxy in
                    Text("U")
                        .font(.dsLogoGlyph(diameter: proxy.size.width))
                        .foregroundStyle(Color.brandOnAccent)
                        .frame(width: proxy.size.width, height: proxy.size.height)
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .containerRelativeFrame(.horizontal) { width, _ in width * widthFraction }
            .accessibilityHidden(true)
    }
}

// MARK: - DSOutlinedCard

/// The outlined-card chrome (DESIGN.md §2/§3): `dsSurface` fill, continuous
/// `DSRadius.control` corners, `dsFieldBorder` hairline. The drawer-row /
/// conversation-card shape, owned once so a radius or border change is an
/// edit, not a grep. Padding and frame stay at the call site — cards differ
/// in their insets, never in their chrome.
struct DSOutlinedCardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Color.dsSurface)
            .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                    .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
            )
    }
}

extension View {
    /// Applies the outlined-card chrome — see ``DSOutlinedCardModifier``.
    func dsOutlinedCard() -> some View { modifier(DSOutlinedCardModifier()) }
}

// MARK: - DSBudgetChange

/// How the coaching budget travels to a new reading — the ring's sweep and the
/// words beside it — as one rule owned in one place.
///
/// There are **three** rules here, and they are inseparable, which is why they
/// are a modifier rather than three lines copied into two files:
///
/// 1. **One timing.** `DSMotion.budgetChange`, applied by this modifier rather
///    than passed in, so no call site can hand the ring one curve and the
///    label another — the arc gliding while "95% left" snapped to "93% left"
///    is the exact contradiction `CoachingBudgetGlance` exists to prevent,
///    arriving through timing instead of through arithmetic.
/// 2. **The first reading does not animate.** A ring that winds itself up from
///    empty on every cold launch reads as a progress bar — the app loading —
///    rather than as a budget, and it makes the one moment the number is being
///    consulted (before sending) the one moment it is still moving. Every
///    reading *after* it is a quantity that fell while the student was reading
///    a reply, and that is what the animation is for. `hasSettled` is `@State`
///    on the modifier, so it is a property of this instance's drawing history
///    and no call site has to know whether the control it is placing has been
///    on screen before.
/// 3. **Reduce Motion is honoured.** SwiftUI does **not** suppress an explicit
///    `.animation(_:value:)` under `accessibilityReduceMotion`; it only drops
///    its own implicit transitions. This is the app's first deliberately slow
///    animation — near a second, against ~0.2s for a tap's feedback — so a
///    student who has asked the system for less motion must get the new number
///    immediately rather than a glide plus rolling digits.
///
/// `hasReading` and not `value != nil`: what counts as "there is something on
/// screen to move" belongs to the control (a drawn fraction for the ring, a
/// label for the button), and this type must not have to know either shape.
struct DSBudgetChangeModifier<V: Equatable>: ViewModifier {
    /// What travelling means here: the ring passes its clamped fraction, the
    /// button its whole glance. A change in this is the animated event.
    let value: V
    /// Whether `value` currently represents a reading at all — the input to
    /// rule 2, kept at the call site because only it knows.
    let hasReading: Bool

    /// Whether a reading has ever been on screen, and therefore whether the
    /// next change is a *change* rather than the meter arriving.
    @State private var hasSettled = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            // `.animation(_:value:)` reads this on the same body evaluation the
            // change arrives in, and `onChange` below fires only after it — so
            // the no-reading -> first-reading step runs unanimated by
            // construction rather than by a call site remembering to wrap it.
            .animation(hasSettled && !reduceMotion ? DSMotion.budgetChange : nil, value: value)
            .onChange(of: value) { _, _ in
                if hasReading { hasSettled = true }
            }
            // A control that is already showing a reading when it first appears
            // (a re-entered screen, a preview, a snapshot scene) has had its
            // first one too — without this it would animate from empty the
            // first time that reading changed.
            .onAppear {
                if hasReading { hasSettled = true }
            }
    }
}

extension View {
    /// Animates this control to a new coaching-budget reading — see
    /// ``DSBudgetChangeModifier`` for the three rules it owns.
    func dsBudgetChange<V: Equatable>(value: V, hasReading: Bool) -> some View {
        modifier(DSBudgetChangeModifier(value: value, hasReading: hasReading))
    }
}

// MARK: - DSHairline

/// **The** separator. DESIGN.md §8 gives this design exactly one — a 1pt
/// `FieldBorder` rule — and no fills, tints or shadows to fall back on, which
/// makes the hairline the single most-repeated primitive in the app and the one
/// worth owning centrally. Hand-building `Rectangle().fill(...).frame(...)` per
/// site states the colour and the width again each time, so changing the
/// separator becomes a grep rather than an edit, and a site that gets it subtly
/// wrong looks intentional.
///
/// Always `accessibilityHidden`: a rule is punctuation, not content, and
/// VoiceOver should walk straight past it.
struct DSHairline: View {
    /// Which way the rule runs. The vertical variant is the blockquote gutter;
    /// the horizontal one is every other separator.
    enum Axis {
        case horizontal
        case vertical
    }

    var axis: Axis = .horizontal

    var body: some View {
        // One exhaustive switch rather than two independent `axis ==` tests: a
        // third axis would make both ternaries fall to `nil`, and a rule with
        // neither dimension fixed silently expands to fill its parent — a
        // layout bug with no compiler error in front of it.
        let thickness: (width: CGFloat?, height: CGFloat?)
        switch axis {
        case .horizontal: thickness = (nil, DSControl.borderWidth)
        case .vertical: thickness = (DSControl.borderWidth, nil)
        }
        return Rectangle()
            .fill(Color.dsFieldBorder)
            .frame(width: thickness.width, height: thickness.height)
            .accessibilityHidden(true)
    }
}

// MARK: - FieldErrorText

struct FieldErrorText: View {
    private let message: String

    init(_ message: String) {
        self.message = message
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.xs) {
            Image(systemName: "exclamationmark.circle")
            Text(message)
        }
        .font(.dsCaption)
        .foregroundStyle(Color.dsError)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

// MARK: - FormErrorBanner

struct FormErrorBanner: View {
    private let message: String

    init(_ message: String) {
        self.message = message
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(message)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.dsCaption)
        .foregroundStyle(Color.dsError)
        .padding(DSSpacing.md)
        // Outlined, not washed: depth is communicated by border alone
        // (DESIGN.md §3), so the banner is a flat surface with a `dsError`
        // hairline at the shared control radius.
        .background(Color.dsSurface)
        .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
                .stroke(Color.dsError, lineWidth: DSControl.borderWidth)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

// MARK: - Previews

#Preview("Buttons - Light") {
    buttonPreview
        .preferredColorScheme(.light)
}

#Preview("Buttons - Dark") {
    buttonPreview
        .preferredColorScheme(.dark)
}

private var buttonPreview: some View {
    VStack(spacing: DSSpacing.md) {
        LoadingButton("Log In", isLoading: false, role: .primary) {}
        LoadingButton("Log In", isLoading: true, role: .primary) {}
        LoadingButton("Log In", isLoading: false, role: .primary) {}
            .disabled(true)
        LoadingButton("Log Out", isLoading: false, role: .destructive) {}
        LoadingButton("Log Out", isLoading: true, role: .destructive) {}

        HStack(spacing: DSSpacing.md) {
            CircularIconButton(systemImage: "arrow.up", isLoading: false) {}
            CircularIconButton(systemImage: "arrow.up", isLoading: true) {}
            CircularIconButton(systemImage: "arrow.up", isLoading: false) {}
                .disabled(true)
        }
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsBackground)
}

private struct LabeledFieldPreviewHost: View {
    @FocusState private var focus: PreviewField?
    @State private var email = "user@example.com"
    @State private var password = ""

    enum PreviewField {
        case email, password
    }

    var body: some View {
        VStack(spacing: DSSpacing.md) {
            LabeledField(
                "Email",
                text: $email,
                focus: $focus,
                equals: .email,
                keyboardType: .emailAddress
            )
            LabeledField(
                "Password",
                text: $password,
                isSecure: true,
                error: "Password must be at least 8 characters",
                focus: $focus,
                equals: .password,
                submitLabel: .go
            )
            FieldErrorText("Email is already taken")
            FormErrorBanner("Invalid email or password")
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

#Preview("Fields - Light") {
    LabeledFieldPreviewHost()
        .preferredColorScheme(.light)
}

#Preview("Fields - Dark") {
    LabeledFieldPreviewHost()
        .preferredColorScheme(.dark)
}

// MARK: - Motif component previews

private struct MotifPreviewHost: View {
    @State private var selectedYear = 2029

    private let years = [2027, 2028, 2029, 2030]

    var body: some View {
        VStack(spacing: 0) {
            BrandTopBar()

            ScrollView {
                VStack(alignment: .leading, spacing: DSSpacing.lg) {
                    Text("Welcome, Kendall")
                        .dsOverlineStyle()
                        .foregroundStyle(Color.dsTextPrimary)

                    StepIndicator(count: 4, current: 0)

                    Text("When will you graduate?")
                        .font(.dsDisplay)
                        .foregroundStyle(Color.dsTextPrimary)

                    VStack(spacing: DSControl.stackGap) {
                        ForEach(years, id: \.self) { year in
                            OptionCard(String(year), isSelected: year == selectedYear) {
                                selectedYear = year
                            }
                        }
                    }

                    LogoMark()
                        .frame(maxWidth: .infinity)
                }
                .padding(DSSpacing.lg)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }
}

private var topBarPreview: some View {
    VStack(spacing: DSSpacing.lg) {
        BrandTopBar()
        BrandTopBar {
            BrandTopBarButton(
                systemImage: "line.3.horizontal",
                accessibilityIdentifier: "menuButton",
                accessibilityLabel: "Menu",
                action: {}
            )
        }
        Spacer()
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsBackground)
}

#Preview("TopBar - Light") {
    topBarPreview
        .preferredColorScheme(.light)
}

#Preview("TopBar - Dark") {
    topBarPreview
        .preferredColorScheme(.dark)
}

#Preview("Motif - Light") {
    MotifPreviewHost()
        .preferredColorScheme(.light)
}

#Preview("Motif - Dark") {
    MotifPreviewHost()
        .preferredColorScheme(.dark)
}
