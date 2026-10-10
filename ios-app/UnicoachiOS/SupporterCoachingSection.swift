import SwiftUI

/// The parent home's one billing surface (RFC 244): how THIS account's coaching
/// is paid for, in the two ways an account can pay for itself — its own
/// subscription, or the one-time free allowance every account gets.
///
/// It is the reachable door for `family/03.5`, because the parent's chat does
/// not exist yet: a parent can be told what they have before there is anything
/// here that spends it.
///
/// **Nothing is sold here.** There is no Subscribe button and no StoreKit
/// listener on a parent's scene: the parent's coach is `family/06`, and selling
/// access to a coach that is not built would be dishonest (RFC 244).
///
/// The bar is `DesignSystem/UsageMeter`, as-is, so there is ONE meter primitive
/// and ONE caption rule for both roles — the caption ("One-time free
/// allowance", or "Resets ⟨date⟩") is the primitive's own answer to
/// `resetsAt`. Each arm below hands it the date its own sentence admits, and
/// the arm is what carries it: the pairing was checked once, where the wire
/// became state, so no branch here can caption a subscription as a one-time
/// credit.
///
/// Layout only: which of the four states this is belongs to
/// `SupporterCoachingViewModel`, where this slice's tests can reach it.
struct SupporterCoachingSection: View {
    @ObservedObject var viewModel: SupporterCoachingViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text("Coaching")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)

            state
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The four states and the read in flight, switched exhaustively with no
    /// residual branch.
    @ViewBuilder
    private var state: some View {
        switch viewModel.state {
        case .loading:
            ProgressView()
                .progressViewStyle(.circular)
                .accessibilityIdentifier("supporterCoachingLoading")
        case .freeAllowanceAvailable(let usedPercent):
            sentence("You have a free allowance to start with.")
            // `resetsAt: nil` is the caption this state admits — a one-time
            // credit never resets — and the state carries no date to put here.
            UsageMeter(usedPercent: usedPercent, exhausted: false, resetsAt: nil)
        case .freeAllowanceSpent:
            // No bar: a one-time credit that is gone is a fact, not a
            // proportion, and a full red track under it says nothing the
            // sentence has not.
            sentence("You've used your free allowance.")
        case .ownSubscription(let usedPercent, let exhausted, let resetsAt):
            sentence("Your subscription.")
            UsageMeter(usedPercent: usedPercent, exhausted: exhausted, resetsAt: resetsAt)
        case .unavailable:
            // The student rail's own sentence, authored once and shared — never
            // a silent 0%, which is a claim about this parent's spend that no
            // read supports.
            CoachingUsageUnavailable()
        }
    }

    private func sentence(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("supporterCoachingLine")
    }
}

// MARK: - Previews

/// The canvas needs the section *loaded*, which on the real screen is
/// `SupporterHomeView`'s job — the `PaywallPreviewContainer` pattern, so no
/// preview renders a view model that has never read anything.
private struct SupporterCoachingPreviewContainer: View {
    @StateObject private var viewModel: SupporterCoachingViewModel

    init(reading: SupporterCoachingUsage) {
        _viewModel = StateObject(wrappedValue: SupporterCoachingViewModel(
            client: PreviewSupporterCoachingUsageClient(reading: reading)
        ))
    }

    var body: some View {
        SupporterCoachingSection(viewModel: viewModel)
            .task { await viewModel.load() }
            .padding(DSSpacing.lg)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color.dsBackground)
    }
}

private func supporterCoachingPreviewReading(
    kind: CoachingCoverageKind,
    usedPercent: Int,
    exhausted: Bool = false,
    resetsAt: Date? = nil
) -> SupporterCoachingUsage {
    SupporterCoachingUsage(
        usage: CoachingUsage(usedPercent: usedPercent, exhausted: exhausted, resetsAt: resetsAt),
        coverage: CoachingCoverage(kind: kind)
    )
}

#Preview("supporterCoaching free allowance - Light") {
    SupporterCoachingPreviewContainer(
        reading: supporterCoachingPreviewReading(kind: .freeAllowance, usedPercent: 18)
    )
    .preferredColorScheme(.light)
}

#Preview("supporterCoaching free allowance spent - Light") {
    SupporterCoachingPreviewContainer(
        reading: supporterCoachingPreviewReading(kind: .freeAllowance, usedPercent: 100, exhausted: true)
    )
    .preferredColorScheme(.light)
}

#Preview("supporterCoaching own subscription - Dark") {
    SupporterCoachingPreviewContainer(
        reading: supporterCoachingPreviewReading(
            kind: .ownSubscription,
            usedPercent: 64,
            resetsAt: Calendar.current.date(byAdding: .day, value: 12, to: Date()) ?? Date()
        )
    )
    .preferredColorScheme(.dark)
}
