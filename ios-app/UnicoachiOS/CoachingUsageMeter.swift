import SwiftUI

/// The coaching meter: the reading when there is one, the unavailable line for
/// a load that finished with nothing, and the spinner while one is still in
/// flight — never an empty gap, which reads as a rendering bug rather than as a
/// failed fetch.
///
/// Shared by the Settings section (RFC 119) and the paywall (RFC 121), because
/// a second copy is a second place for the state order, the strings and the
/// accessibility identifiers to drift — and the two copies this replaces had
/// already drifted: they disagreed about what a missing reading renders.
///
/// Layout only, like `SubscriptionOffer`: the reading is the server's and the
/// three states are the view model's — one `UsageReading`, switched
/// exhaustively, so the spinner is an arm the state names rather than the
/// residue of two tests that did not match.
struct CoachingUsageMeter: View {
    @ObservedObject var viewModel: SubscriptionViewModel

    var body: some View {
        switch viewModel.usageReading {
        case .ready(let usage):
            UsageMeter(usedPercent: usage.usedPercent, exhausted: usage.exhausted, resetsAt: usage.resetsAt)
        case .unavailable:
            CoachingUsageUnavailable()
        case .loading:
            ProgressView()
                .progressViewStyle(.circular)
                .accessibilityIdentifier("subscriptionLoading")
        }
    }
}

/// What a finished load with no meter says: a heading with nothing under it
/// reads as a rendering bug, not as a failed fetch.
///
/// Its own view because the parent's home renders the same sentence for the
/// same reason (RFC 244) and holds no `SubscriptionViewModel` to reach the
/// wrapper above with. One sentence, one accessibility identifier, one home —
/// two copies would be two places for the words to drift, which is the defect
/// `CoachingUsageMeter` itself exists to have fixed.
struct CoachingUsageUnavailable: View {
    var body: some View {
        Text("Coaching usage is unavailable right now.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("coachingUsageUnavailable")
    }
}
