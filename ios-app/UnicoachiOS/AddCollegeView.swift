import SwiftUI

/// The add-college search (RFC 137): a debounced name query, result rows with
/// a "City, ST" caption, and tap-to-add with the server-default status
/// (`considering`). A duplicate add (409) surfaces the server's message
/// inline; success pops back to the list, which refreshes on return.
struct AddCollegeView: View {
    @StateObject private var viewModel: AddCollegeViewModel
    @FocusState private var searchFocused: Bool?
    @Environment(\.dismiss) private var dismiss

    /// Whether the search field takes focus on first appearance — the RFC 127
    /// `focusesComposerOnAppear` convention. The app always wants it (this
    /// screen exists to type a name into); the snapshot host passes `false`,
    /// because a hosted focus raises the real simulator keyboard and its
    /// safe-area inset paints a dead band across the capture.
    private let focusesSearchOnAppear: Bool

    /// `client` must be the same instance the list reads from, so the add
    /// lands where the pop-back refresh will look; `CollegeListView` owns both
    /// dependencies and passes them through.
    init(client: CollegeListClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: AddCollegeViewModel(
            client: client,
            onProfileRequired: onProfileRequired
        ))
        focusesSearchOnAppear = true
    }

    /// Snapshot seam: host a pre-seeded view model (the catalogue's
    /// await-seed rule — typing into the debounced field is not a render).
    init(viewModel: AddCollegeViewModel, focusesSearchOnAppear: Bool = true) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.focusesSearchOnAppear = focusesSearchOnAppear
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            LabeledField(
                "College name",
                text: $viewModel.query,
                focus: $searchFocused,
                equals: true,
                autocapitalization: .words,
                submitLabel: .search,
                accessibilityIdentifier: "collegeSearchField",
                accessibilityLabel: "College name"
            )
            if let addError = viewModel.addError {
                FieldErrorText(addError.message)
            }
            results
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.dsBackground)
        .navigationTitle("Add a college")
        .navigationBarTitleDisplayMode(.inline)
        // Auto-focus: this screen exists to type a name into.
        .onAppear { if focusesSearchOnAppear { searchFocused = true } }
    }

    @ViewBuilder
    private var results: some View {
        switch viewModel.state {
        case .prompt:
            Text("Search by college name.")
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("collegeSearchPrompt")
        case .searching:
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("collegeSearchLoading")
        case .results(let colleges):
            resultList(colleges)
        case .empty:
            Text("No colleges match.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("collegeSearchEmpty")
        case .failed(let error):
            FormErrorBanner(error.message)
                .accessibilityIdentifier("collegeSearchFailed")
        }
    }

    private func resultList(_ colleges: [CollegeSummary]) -> some View {
        ScrollView {
            VStack(spacing: DSControl.stackGap) {
                ForEach(colleges) { college in
                    resultRow(college)
                }
            }
        }
    }

    private func resultRow(_ college: CollegeSummary) -> some View {
        Button {
            Task {
                switch await viewModel.add(college) {
                case .added:
                    dismiss()
                case .rejected, .profileRequired:
                    break // inline error / root escalation already handled by the view model
                }
            }
        } label: {
            VStack(alignment: .leading, spacing: DSSpacing.xs) {
                Text(college.name)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
                    .multilineTextAlignment(.leading)
                Text("\(college.city), \(college.state)")
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)
            }
            .padding(DSSpacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .dsOutlinedCard()
            .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isAdding)
        .accessibilityIdentifier("collegeSearchResultRow")
        .accessibilityLabel("Add \(college.name)")
    }
}
