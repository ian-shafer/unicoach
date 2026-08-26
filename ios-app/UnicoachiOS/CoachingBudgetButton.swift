import SwiftUI

/// What the composer's budget control says, as a pure value derived from the
/// meter — so the three states and their exact words are decided (and tested)
/// without rendering a view, in the shape `PaywallCopy` already established
/// (RFC 123).
///
/// | state          | label             | colour            |
/// | -------------- | ----------------- | ----------------- |
/// | a reading      | `62% left`        | `dsTextSecondary` |
/// | exhausted      | `Out of coaching` | `dsError`         |
/// | no reading yet | *(nothing)*       | —                 |
///
/// **`Glance`, not `Reading`.** `Reading<Value>` is this module's
/// load-lifecycle wrapper (`UsageReading`, `ProductReading`), so
/// `CoachingBudgetReading` read as `Reading<CoachingBudget>`, which this is not:
/// it is the finished glance the control shows, lifecycle already resolved into
/// `.noReading`. (`Gauge` was the other candidate and collides with SwiftUI's
/// own type.)
///
/// **The free tier needs no case of its own.** `resetsAt == nil` is a lifetime
/// credit that never resets, and neither the ring nor this label says anything
/// about time — they are a *quantity* — so both read correctly with no special
/// handling at all. The distinction is the sheet's to make, and it makes it in
/// words.
enum CoachingBudgetGlance: Equatable {
    /// A load in flight, or a refresh that failed. Not "full", and not "empty":
    /// the ring draws its bare track and the label says nothing at all, because
    /// a number invented here sits next to the send button at the exact moment
    /// the decision to send is made.
    case noReading
    case remaining(percent: Int)
    case exhausted

    /// Composed from the two answers the view model already publishes, never
    /// from `usedPercent` re-read here: `exhausted` is the server's own block
    /// condition and outranks the arithmetic — a budget the server has spent
    /// reads as spent whatever the percentage rounds to.
    ///
    /// The percentage is **clamped to `CoachingUsage.percentRange` on the way
    /// in**, by that type's own clamp rather than by a second transcription of
    /// the bounds, and the reason it is applied here rather than left to the
    /// ring: the ring already clamps its own sweep; the label does not, so a
    /// server cap broken past 100% used would draw an empty ring beside the
    /// words "-5% left" — the ring/label contradiction this type exists to
    /// prevent, arriving through the one field they do not share. Clamped once,
    /// at the door, both readings come from the same number.
    init(remainingPercent: Int?, budget: CoachingBudget) {
        switch (budget, remainingPercent) {
        case (.spent, _):
            self = .exhausted
        case (.unknown, _), (_, nil):
            self = .noReading
        case (.open, .some(let remaining)):
            self = .remaining(percent: CoachingUsage.clamped(percent: remaining))
        }
    }

    /// The words beside the ring, or `nil` when the honest answer is silence.
    var label: String? {
        switch self {
        case .noReading:
            return nil
        case .remaining(let percent):
            return String(localized: "\(percent)% left")
        case .exhausted:
            return String(localized: "Out of coaching")
        }
    }

    /// Whether the label is stated in `dsError` — the one thing exhaustion
    /// changes. The ring itself is **not** repainted: `UsageMeter` set that rule
    /// and DESIGN.md §6 makes error UI outlined, not a tinted wash.
    ///
    /// A `switch` and not `self == .exhausted`, like every other accessor here:
    /// this is what picks `dsError` over `dsTextSecondary`, so a case added to
    /// this enum should have to state its colour rather than inherit the
    /// ordinary one by falling off an equality test.
    var isExhausted: Bool {
        switch self {
        case .exhausted:
            return true
        case .noReading, .remaining:
            return false
        }
    }

    /// The ring's sweep. `.exhausted` is drawn as an empty ring regardless of
    /// what the percentage rounded to, so the control and the words beside it
    /// cannot contradict each other.
    var ringRemainingPercent: Int? {
        switch self {
        case .noReading:
            return nil
        case .remaining(let percent):
            return percent
        case .exhausted:
            return 0
        }
    }

    /// What VoiceOver says as the element's **value**. The percentage is spoken
    /// here and only here: the label is "Coaching budget", so a reading is never
    /// announced twice (the rule `UsageMeter` follows).
    var accessibilityValue: String {
        switch self {
        case .noReading:
            return String(localized: "Not loaded yet")
        case .remaining(let percent):
            return String(localized: "\(percent) percent remaining")
        case .exhausted:
            return String(localized: "No coaching remaining")
        }
    }
}

/// The coaching budget on the composer's control row: the ring, the label, and
/// the tap that opens the subscription sheet (RFC 123).
///
/// **Why the composer.** This is where the budget is *spent*, so the reading
/// sits next to the act that consumes it and is on screen at the exact moment
/// the decision to send is made. It is also the one piece of chrome that
/// appears on every conversation screen — the root and every pushed
/// conversation alike — where `BrandTopBar` exists only at the root, so a ring
/// there would vanish on every push.
///
/// **One element, one tap.** The ring and the label are a single accessibility
/// element with the `.isButton` trait, like `UsageMeter`'s single element, and a
/// `DSControl.tapTarget` `contentShape` guarantees the 44pt target whatever the
/// glyph's drawn size — the ring itself is deliberately small.
///
/// **It is never disabled.** The composer is disabled while blocked or
/// streaming; this is not, because a student who has just been blocked needs
/// exactly this door, and the sheet is a read-only explanation the rest of the
/// time.
///
/// Layout only: what to say is `viewModel.budgetGlance`, and it is never
/// re-derived here.
struct CoachingBudgetButton: View {
    @ObservedObject var viewModel: SubscriptionViewModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // The gap scales with its neighbours. Both of them already do — the
            // ring is `@ScaledMetric` and the label is `dsCaption` — so a fixed
            // 4pt between them closes to nothing at `.accessibility3`, where
            // the ring and "62% left" all but touch.
            HStack(spacing: ringLabelGap) {
                CoachingBudgetRing(remainingPercent: glance.ringRemainingPercent)

                if let label = glance.label {
                    Text(label)
                        .font(.dsCaption)
                        .foregroundStyle(glance.isExhausted ? Color.dsError : Color.dsTextSecondary)
                        // The digits roll rather than snap, on the ring's own
                        // timing: "95% left" becoming "93% left" is the same
                        // event as the arc retreating, and the two reading as
                        // one movement is the whole point of a token they
                        // share. `value:` is the number itself, so the
                        // transition knows the reading went *down* and rolls
                        // that way.
                        .contentTransition(.numericText(value: numericValue))
                        // The label yields its width to the send button under
                        // large Dynamic Type: the send control is the one thing
                        // on this row that must never be squeezed.
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            // Both dimensions, not just the height: in the no-reading state
            // there is no label, and the ring alone is `budgetRingDiameter`
            // wide — a 44pt-tall, 18pt-wide target is not a 44pt target.
            .frame(minWidth: DSControl.tapTarget, minHeight: DSControl.tapTarget, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // The same modifier the ring inside it applies, which is what makes the
        // arc and the words one movement rather than two that happen to have
        // been given the same number. Keyed on the whole glance because the
        // colour is part of the change too — an exhausted budget turns the
        // words `dsError` at the moment the arc reaches empty.
        .dsBudgetChange(value: glance, hasReading: glance.label != nil)
        // One element, not two: split, VoiceOver reads the ring and then the
        // percentage that is already in the value. `children: .ignore` is also
        // why neither the ring nor the label carries an identifier of its own:
        // under it there is no child element left to address, so such an
        // identifier is unreachable from a UI test and reads as a query seam
        // that does not exist. The one identifier is this element's.
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("coachingBudgetButton")
        .accessibilityLabel("Coaching budget")
        .accessibilityValue(glance.accessibilityValue)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens your subscription")
    }

    /// Relative to `.caption`, the label's own text style and the ring's, so
    /// all three grow together.
    @ScaledMetric(relativeTo: .caption) private var ringLabelGap: CGFloat = DSSpacing.xs

    private var glance: CoachingBudgetGlance {
        viewModel.budgetGlance
    }

    /// What `.numericText` counts *to*. `ringRemainingPercent` rather than a
    /// second reading of the percentage, so the digits and the arc travel to
    /// the same number — including `.exhausted`, which the glance draws as 0
    /// whatever the arithmetic rounded to. `.noReading` has no label to
    /// transition at all, so its value is never used.
    private var numericValue: Double {
        Double(glance.ringRemainingPercent ?? 0)
    }
}

// MARK: - Previews

/// The canvas needs the meter *loaded*, which on the real screen is
/// `AuthenticatedRootView`'s job — so the container does it here rather than
/// each preview constructing a view model that has never fetched anything.
private struct CoachingBudgetButtonPreviewContainer: View {
    @StateObject private var viewModel: SubscriptionViewModel

    init(usage: CoachingUsage) {
        _viewModel = StateObject(wrappedValue: SubscriptionViewModel(
            usageClient: PreviewCoachingUsageClient(usage: usage),
            store: PreviewSubscriptionStore(),
            recorder: PreviewTransactionRecorder()
        ))
    }

    var body: some View {
        CoachingBudgetButton(viewModel: viewModel, action: {})
            .task { await viewModel.refreshUsage() }
    }
}

/// A healthy budget, a low one, and a spent one. The fourth state — no reading
/// at all — is the container that never refreshes, below.
@MainActor private var coachingBudgetButtonPreview: some View {
    VStack(alignment: .leading, spacing: DSSpacing.md) {
        CoachingBudgetButtonPreviewContainer(usage: CoachingUsage(usedPercent: 38, exhausted: false, resetsAt: nil))
        CoachingBudgetButtonPreviewContainer(usage: CoachingUsage(usedPercent: 92, exhausted: false, resetsAt: Date()))
        CoachingBudgetButtonPreviewContainer(usage: CoachingUsage(usedPercent: 100, exhausted: true, resetsAt: Date()))
        CoachingBudgetButton(
            viewModel: SubscriptionViewModel(
                usageClient: PreviewCoachingUsageClient(),
                store: PreviewSubscriptionStore(),
                recorder: PreviewTransactionRecorder()
            ),
            action: {}
        )
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    .background(Color.dsSurface)
}

#Preview("coachingBudgetButton - Light") {
    coachingBudgetButtonPreview
        .preferredColorScheme(.light)
}

#Preview("coachingBudgetButton - Dark") {
    coachingBudgetButtonPreview
        .preferredColorScheme(.dark)
}
