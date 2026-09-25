import SwiftUI

/// The debounced `GET /api/v1/colleges` name search (RFC 137), at both of its
/// readings: a STUDENT picking a college to add to their list, and a SUPPORTER
/// looking one up (RFC 241).
///
/// ONE screen, because it is one screen: the same field, the same five result
/// states, the same copy and the same accessibility identifiers — which the
/// snapshot gate and the UI tests read as a contract. A second transcription of
/// them is a second thing to keep in step, and the next copy change would land
/// on one of the two.
///
/// [Tap] is the whole difference, and it is a closed choice rather than an
/// optional closure because the dismissal belongs to THIS view: the add pops
/// itself back to the list, and a caller handed a `dismiss` would dismiss
/// ITSELF.
struct CollegeSearchView: View {
    /// What a result row offers — and, because the two readings differ in
    /// exactly that, what this screen is called.
    enum Tap {
        /// Tapping adds the college to the CALLER's own list with the
        /// server-default status and pops back (RFC 137). A duplicate add
        /// (409) surfaces the server's message inline.
        case addsToMyList
        /// Nothing: a row, not a button. A supporter has no college list of
        /// their own — `POST /api/v1/students/me/college-list` writes to the
        /// CALLER's list and could only answer `409 student_profile_required` —
        /// and adding to a linked student's list is `family/05`. A disabled
        /// button would be a control the screen is withholding; a card is a
        /// result.
        case browseOnly

        /// What this search is FOR, which is the one thing its two readings
        /// say differently — so it is said HERE, once, and every caller, every
        /// control that pushes the screen and every snapshot scene reads it. A
        /// `title:` parameter made one fact into three statements, one of them
        /// in the snapshot catalogue, where a stale copy photographs a screen
        /// production no longer shows.
        var title: String {
            switch self {
            case .addsToMyList: return "Add a college"
            case .browseOnly: return "Find a college"
            }
        }
    }

    @StateObject private var viewModel: AddCollegeViewModel
    @FocusState private var searchFocused: Bool?
    @Environment(\.dismiss) private var dismiss

    private let tap: Tap

    /// Whether the search field takes focus on first appearance — the RFC 127
    /// `focusesComposerOnAppear` convention. The app always wants it (this
    /// screen exists to type a name into); the snapshot host passes `false`,
    /// because a hosted focus raises the real simulator keyboard and its
    /// safe-area inset paints a dead band across the capture.
    private let focusesSearchOnAppear: Bool

    /// `client` must be the same instance the list reads from, so an add lands
    /// where the pop-back refresh will look; `CollegeListView` owns both
    /// dependencies and passes them through.
    ///
    /// `onProfileRequired` can only fire on the ADD: `CollegeRouteHandler`'s
    /// search arm resolves the user and has no student-profile gate at all, so
    /// a browse-only caller passes an empty closure to a door that cannot knock
    /// — never an escalation, because escalating a supporter into the student
    /// onboarding form is precisely the defect RFC 241 removes.
    init(
        client: CollegeListClientProtocol,
        tap: Tap,
        onProfileRequired: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: AddCollegeViewModel(
            client: client,
            onProfileRequired: onProfileRequired
        ))
        self.tap = tap
        focusesSearchOnAppear = true
    }

    /// Snapshot seam: host a pre-seeded view model (the catalogue's await-seed
    /// rule — typing into the debounced field is not a render).
    init(
        viewModel: AddCollegeViewModel,
        tap: Tap,
        focusesSearchOnAppear: Bool = true
    ) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.tap = tap
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
            // The add's refusal, said where the tap was made. A browse-only
            // search never sets it, because it never adds.
            if let addError = viewModel.addError {
                FieldErrorText(addError.message)
            }
            results
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.dsBackground)
        .navigationTitle(tap.title)
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

    /// The router over [Tap], and only that: which reading is this. What each
    /// reading DOES is one level down, so the two readings can be read side by
    /// side without reading the add.
    @ViewBuilder
    private func resultRow(_ college: CollegeSummary) -> some View {
        switch tap {
        case .addsToMyList: addableRow(college)
        case .browseOnly: browseRow(college)
        }
    }

    /// A row that adds to the CALLER's own list and pops back (RFC 137).
    private func addableRow(_ college: CollegeSummary) -> some View {
        Button {
            Task { await add(college) }
        } label: {
            resultCard(college)
                .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isAdding)
        .accessibilityIdentifier("collegeSearchResultRow")
        .accessibilityLabel("Add \(college.name)")
    }

    /// A row a supporter reads: a result, not a control (RFC 241).
    private func browseRow(_ college: CollegeSummary) -> some View {
        resultCard(college)
            .accessibilityIdentifier("collegeSearchResultRow")
    }

    /// Only a successful add dismisses. The inline refusal and the root
    /// escalation are already the view model's, and this screen has nothing to
    /// add to either.
    private func add(_ college: CollegeSummary) async {
        switch await viewModel.add(college) {
        case .added:
            dismiss()
        case .rejected, .profileRequired:
            break
        }
    }

    /// The name-and-place card inside a result, with no action attached: the
    /// student's screen wraps it in a `Button`, the supporter's shows it bare.
    private func resultCard(_ college: CollegeSummary) -> some View {
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
    }
}
