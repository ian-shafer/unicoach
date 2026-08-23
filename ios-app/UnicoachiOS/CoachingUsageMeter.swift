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
            // A finished load with no meter says so: a header with nothing
            // under it reads as a rendering bug, not as a failed fetch.
            Text("Coaching usage is unavailable right now.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("coachingUsageUnavailable")
        case .loading:
            ProgressView()
                .progressViewStyle(.circular)
                .accessibilityIdentifier("subscriptionLoading")
        }
    }
}
