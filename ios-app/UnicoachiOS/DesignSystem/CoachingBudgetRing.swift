import SwiftUI

/// The coaching budget as a small ring: a hairline track with an arc over it
/// whose sweep is the fraction **remaining**, starting at twelve o'clock and
/// running clockwise (RFC 123).
///
/// Remaining rather than used — the opposite of `UsageMeter`, deliberately.
/// This one sits on the composer's control row beside the send button, where
/// the question a student is asking is "can I send this?", and a ring that
/// *fills* as coaching is spent would answer the other one. It depletes, which
/// is the direction the word the label uses ("left") leads a reader to expect.
///
/// - **Colour: `brandAccent`**, the sanctioned small indicator fill
///   (DESIGN.md §1, §6) — the same one `UsageMeter`'s bar, `OptionCard`'s radio
///   and `SegmentedSelector`'s selection already take. Not `DSGradient.brand`:
///   the gradient is chrome only, and on the brand gradient this arc could not
///   have been the accent at all — `#EE732F` on `#EE7330` is not a contrast
///   question but an invisibility one. In the composer, on `dsSurface`, that
///   problem does not arise.
/// - **Flat**: no shadow, no fill inside the ring. Depth is border alone
///   (DESIGN.md §3).
/// - **Exhaustion does not repaint it.** `UsageMeter` set that rule and it
///   holds here: an empty ring *is* nothing remaining, drawn as nothing, and
///   the words beside it are what change. DESIGN.md §6 makes error UI outlined,
///   not a tinted wash.
///
/// It takes a plain optional percentage, never a wire model or a view model:
/// `DesignSystem/` is a layer of primitives and must not depend on the API
/// layer or on the subscription feature, exactly as `UsageMeter` does not.
///
/// `nil` — a load in flight, or a refresh that failed — draws the **track
/// alone**. Not a spinner: a control that spins on every cold launch reads as
/// the app working rather than as the budget being unknown, and an empty groove
/// for a beat is the quieter lie-free answer.
///
/// It carries no accessibility of its own — **not even an identifier**. The
/// ring and the label beside it are one element to VoiceOver, and that element
/// is `CoachingBudgetButton`'s — the same "one element, not four" rule
/// `UsageMeter` follows, and the reason the percentage is never spoken twice.
/// The button applies `.accessibilityElement(children: .ignore)`, so an
/// identifier here would address no element a UI test could ever reach: dead
/// weight that reads as a query seam which does not exist.
struct CoachingBudgetRing: View {
    /// 0...100 remaining, or `nil` for no reading yet.
    let remainingPercent: Int?

    /// `@ScaledMetric` so the ring grows with Dynamic Type like `OptionCard`'s
    /// radio, rather than shrinking into a dot beside text that has doubled.
    @ScaledMetric(relativeTo: .caption) private var diameter: CGFloat = DSControl.budgetRingDiameter
    @ScaledMetric(relativeTo: .caption) private var arcWidth: CGFloat = DSControl.budgetRingWidth

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)

            if let fraction {
                Circle()
                    .trim(from: 0, to: fraction)
                    // `.butt` rather than `.round`: a rounded cap overhangs the
                    // trim by half the line width, so a nearly-spent budget
                    // would still draw a visible stub of arc — the one reading
                    // that must not be overstated.
                    .stroke(Color.brandAccent, style: StrokeStyle(lineWidth: arcWidth, lineCap: .butt))
                    // `trim` starts at three o'clock and runs clockwise; the
                    // quarter-turn back is what puts the start at twelve.
                    .rotationEffect(.degrees(-90))
                    // Inset by half the arc's width so the stroke — which
                    // straddles the path — stays inside the track rather than
                    // spilling past it.
                    .padding(arcWidth / 2)
            }
        }
        .frame(width: diameter, height: diameter)
        // Driven off the drawn `fraction`, not off `remainingPercent`: the
        // clamp is what the arc actually travels to, so two out-of-range
        // percentages that both clamp to a full ring are not a change and must
        // not restart the sweep.
        //
        // The timing, the first-reading rule and Reduce Motion all live in the
        // modifier, which the label beside this ring applies too — the two only
        // read as one movement if neither of them states the rules itself.
        .dsBudgetChange(value: fraction, hasReading: fraction != nil)
    }

    /// The drawn sweep. `DSFraction.clamped(percent:)` is the **only** bound
    /// applied — the same one `UsageMeter`'s fill takes, written once — so no
    /// arithmetic here can paint past twelve o'clock even if the server's
    /// 0...100 guarantee is ever broken.
    private var fraction: CGFloat? {
        guard let remainingPercent else { return nil }
        return DSFraction.clamped(percent: remainingPercent)
    }
}

// MARK: - Previews

/// Every state the ring has, at one glance: a healthy budget, a low one, an
/// exhausted one, and no reading at all — which is the one that must draw the
/// bare track rather than a full ring.
private var coachingBudgetRingPreview: some View {
    HStack(spacing: DSSpacing.lg) {
        CoachingBudgetRing(remainingPercent: 62)
        CoachingBudgetRing(remainingPercent: 8)
        CoachingBudgetRing(remainingPercent: 0)
        CoachingBudgetRing(remainingPercent: nil)
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsSurface)
}

#Preview("coachingBudgetRing - Light") {
    coachingBudgetRingPreview
        .preferredColorScheme(.light)
}

#Preview("coachingBudgetRing - Dark") {
    coachingBudgetRingPreview
        .preferredColorScheme(.dark)
}
