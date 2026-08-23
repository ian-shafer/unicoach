import SwiftUI

/// The abstract "coaching used" meter — the one piece of new visual language
/// this feature needs, extrapolated from the tokens (DESIGN.md §8) rather than
/// invented:
///
/// - an outlined track: `DSRadius.control` with the same 1pt `dsFieldBorder`
///   hairline that separates every other surface;
/// - a `brandAccent` fill inset in it, proportional to `usedPercent` —
///   `brandAccent` as a selection/indicator fill is already sanctioned by
///   `OptionCard`'s radio and `SegmentedSelector`;
/// - flat: no shadow, no gradient. `DSGradient.brand` is chrome-only, and a
///   meter is not chrome.
///
/// **Exhaustion does not repaint the bar.** DESIGN.md §6 makes error UI
/// *outlined*, not a tinted wash, so the bar reads the same in both states and
/// only the words change.
///
/// It shows a percentage and a reset date, and never dollars, tokens, or the
/// budget ratio — the server sends none of them.
///
/// It takes plain values, never a wire model: `DesignSystem/` is a layer of
/// primitives and must not depend on the API layer or on the subscription
/// feature. The caller unpacks whatever it has.
struct UsageMeter: View {
    /// 0...100, as the server sends it — the cap is a server guarantee, not
    /// something this view re-derives.
    let usedPercent: Int
    /// The server's own block condition, carried explicitly so no client
    /// re-derives it from `usedPercent`.
    let exhausted: Bool
    /// `nil` is a lifetime allowance that never resets.
    let resetsAt: Date?

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            // The percentage is stated **here and only here**: a bar draws a
            // proportion but never says it, and a student reading "you are out"
            // deserves the number. It is not repeated into the caption, nor
            // into the accessibility value of a separate element.
            HStack {
                Text("Coaching used")
                    .foregroundStyle(Color.dsTextSecondary)
                Spacer(minLength: DSSpacing.sm)
                Text("\(usedPercent)%")
                    .foregroundStyle(exhausted ? Color.dsError : Color.dsTextPrimary)
            }
            .font(.dsCaption)

            track

            Text(caption)
                .font(.dsCaption)
                .foregroundStyle(exhausted ? Color.dsError : Color.dsTextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // One element, not four. Left split, VoiceOver reads the percentage
        // twice — once from the label row, once from the meter's own value.
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("coachingUsageMeter")
        .accessibilityLabel("Coaching used")
        .accessibilityValue("\(usedPercent) percent used")
        .accessibilityHint(caption)
    }

    private var track: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: DSRadius.control)
                    .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)

                RoundedRectangle(cornerRadius: DSRadius.control)
                    .fill(Color.brandAccent)
                    .padding(DSControl.borderWidth)
                    .frame(width: fillWidth(in: proxy.size.width))
            }
        }
        .frame(height: DSControl.meterHeight)
    }

    /// `usedPercent` is used as the server sends it — the 0...100 cap is a
    /// server guarantee, not something the client re-derives. The only bound is
    /// here, on the drawn fraction, so no arithmetic can paint outside the
    /// track.
    ///
    /// The fill is inset by the hairline on both sides, so a 0% meter is an
    /// empty outline and a 100% meter fills the track exactly.
    private func fillWidth(in width: CGFloat) -> CGFloat {
        let fraction = min(max(CGFloat(usedPercent) / 100, 0), 1)
        let inner = max(0, width - DSControl.borderWidth * 2)
        return inner * fraction + DSControl.borderWidth * 2
    }

    /// `resetsAt == nil` is the free tier: a lifetime credit that never resets,
    /// which is why the field stayed optional rather than being defaulted.
    private var caption: String {
        guard let resetsAt else {
            return "One-time free allowance"
        }
        return "Resets \(resetsAt.dsCalendarDate)"
    }
}

// MARK: - Previews

/// The canvas's "twelve days from now", said in days rather than as seconds
/// arithmetic against a magic `86_400` — and DST-correct into the bargain.
private func previewResetDate(inDays days: Int) -> Date {
    Calendar.current.date(byAdding: .day, value: days, to: Date()) ?? Date()
}

#Preview("usageMeter - Light") {
    VStack(spacing: DSSpacing.lg) {
        UsageMeter(usedPercent: 42, exhausted: false, resetsAt: nil)
        UsageMeter(usedPercent: 100, exhausted: true, resetsAt: nil)
        UsageMeter(usedPercent: 68, exhausted: false, resetsAt: previewResetDate(inDays: 12))
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsBackground)
    .preferredColorScheme(.light)
}

#Preview("usageMeter - Dark") {
    VStack(spacing: DSSpacing.lg) {
        UsageMeter(usedPercent: 42, exhausted: false, resetsAt: nil)
        UsageMeter(usedPercent: 100, exhausted: true, resetsAt: nil)
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.dsBackground)
    .preferredColorScheme(.dark)
}
