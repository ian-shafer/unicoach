import SwiftUI

/// "Your details" (RFC 171): the two facts that change every number unicoach
/// shows a family — household income band and state of residence — visible and
/// changeable without talking to the coach.
///
/// **Never a gate.** It is reached from the drawer and is never interposed:
/// no onboarding form, no blocking, no completion meter. A family that answers
/// nothing here loses nothing (RFC 171 §8).
///
/// Each control writes on its own, immediately, and the server's response
/// replaces the screen's state — so there is no Save button and no dirty state
/// to lose (§3).
struct YourDetailsView: View {
    @StateObject private var viewModel: YourDetailsViewModel
    /// The footer's one link. A closure rather than a `NavigationLink` here
    /// because the destination belongs to `AuthenticatedRootView`'s stack: the
    /// college list keeps its own screen and is not duplicated into this one
    /// (RFC 171 §8, D3).
    private let onMyColleges: () -> Void

    init(
        moneyProfileClient: MoneyProfileClientProtocol,
        vocabularyClient: VocabularyClientProtocol,
        onProfileRequired: @escaping () -> Void,
        onMyColleges: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: YourDetailsViewModel(
            moneyProfileClient: moneyProfileClient,
            vocabularyClient: vocabularyClient,
            onProfileRequired: onProfileRequired
        ))
        self.onMyColleges = onMyColleges
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
            .navigationTitle("Your details")
            .navigationBarTitleDisplayMode(.inline)
            .task { await viewModel.load() }
            // The receipt is spoken as well as shown: a family using VoiceOver
            // gets the same confirmation as one reading it, and a change that
            // announced nothing would leave them unsure the tap landed.
            .onChange(of: viewModel.savedField) { _, field in
                guard field != nil else { return }
                AccessibilityNotification.Announcement("Saved").post()
            }
            .alert(item: $viewModel.actionError) { error in
                Alert(
                    title: Text("Something went wrong"),
                    message: Text(error.message),
                    dismissButton: .default(Text("OK"))
                )
            }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            loadingView
        case .loaded:
            form
        case .failed(let error):
            failedView(error)
        }
    }

    // MARK: - States

    private var loadingView: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .tint(Color.dsTextPrimary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("yourDetailsLoading")
            .accessibilityLabel("Loading your details")
    }

    private func failedView(_ error: ErrorResponse) -> some View {
        ErrorView(
            title: "Something went wrong",
            description: error.message,
            systemImage: "exclamationmark.triangle",
            retryAction: { Task { await viewModel.load() } }
        )
        .accessibilityIdentifier("yourDetailsFailed")
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                intro

                section(.income) {
                    DSPickerRow(
                        label: "Household income",
                        options: viewModel.incomeOptions,
                        selection: incomeSelection,
                        title: \.label,
                        accessibilityIdentifier: "detailsIncomePicker",
                        accessibilityLabel: "Household income bracket"
                    )
                }

                section(.residency) {
                    DSPickerRow(
                        label: "State or territory",
                        options: viewModel.residencyOptions,
                        selection: residencySelection,
                        title: \.label,
                        accessibilityIdentifier: "detailsResidencyPicker",
                        accessibilityLabel: "State or territory of residence"
                    )
                }

                if viewModel.vocabularySource == .fallback {
                    fallbackNote
                }

                footer
            }
            .padding(DSSpacing.lg)
        }
    }

    /// What this screen is for, said once at the top: the two answers below are
    /// what every price unicoach shows a family follows from.
    private var intro: some View {
        Text("What unicoach knows about your family's money. Change any of it here — "
            + "every price it shows you follows from these two answers.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Shown only when a picker fell back to the shipped list: the menu is
    /// narrower than the server's, and a family must be told before they
    /// conclude their territory is not offered at all.
    private var fallbackNote: some View {
        Text("unicoach couldn't reach its full list just now, so these menus may be "
            + "incomplete. Pull the screen again later, or ask your coach.")
            .font(.dsCaption)
            .foregroundStyle(Color.dsTextSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("detailsVocabularyFallbackNote")
    }

    /// One field: its name, what the profile currently says, the receipt, the
    /// picker, and the two undo controls — in that order, so the state a family
    /// is changing is read before the control that changes it.
    @ViewBuilder
    private func section(_ field: MoneyProfileField, @ViewBuilder picker: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            header(field)
            answerLine(field)
            picker()

            // Both controls stay fully live on a declined field: answering it
            // outright is one tap, and so is returning it to unanswered. Never
            // a one-way door (RFC 171 §4, D5) — which is why neither is ever
            // disabled, only hidden where it would be a no-op.
            if viewModel.canDecline(field) {
                declineControl(field)
            }

            if viewModel.canRemove(field) {
                removeControl(field)
            }
        }
    }

    private func header(_ field: MoneyProfileField) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
            Text(field.title)
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextPrimary)
            Spacer(minLength: 0)
            if viewModel.savedField == field {
                savedReceipt(field)
            }
        }
    }

    private func savedReceipt(_ field: MoneyProfileField) -> some View {
        Text("Saved")
            .font(.dsCaption)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier(field.savedReceiptIdentifier)
    }

    /// What the profile currently says about the field. Nothing at all before
    /// the profile is loaded: there is no answer to read yet, and an empty line
    /// would say there is one and that it is blank.
    @ViewBuilder
    private func answerLine(_ field: MoneyProfileField) -> some View {
        if let answer = viewModel.answerText(field) {
            Text(answer)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier(field.answerTextIdentifier)
        }
    }

    private func declineControl(_ field: MoneyProfileField) -> some View {
        DSCaptioned("The coach stops asking about this.") {
            DSTextButton(
                "Prefer not to say",
                accessibilityIdentifier: field.declineButtonIdentifier,
                accessibilityLabel: "Prefer not to say",
                action: { Task { await viewModel.decline(field) } }
            )
        }
    }

    private func removeControl(_ field: MoneyProfileField) -> some View {
        DSCaptioned("Returns it to not answered. The coach may invite it again.") {
            DSTextButton(
                "Remove my answer",
                accessibilityIdentifier: field.removeButtonIdentifier,
                accessibilityLabel: "Remove my answer",
                action: { Task { await viewModel.remove(field) } }
            )
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            DSHairline()

            DSTextButton(
                "My colleges",
                accessibilityIdentifier: "detailsMyCollegesButton",
                accessibilityLabel: "My colleges",
                action: onMyColleges
            )
        }
    }

    // MARK: - Picker bindings

    /// The picker writes as it is changed, and the write is the whole
    /// interaction: there is no Save button to arm. Selecting the leading
    /// "Select" row is **not** a clear — clearing has its own control, and its
    /// own caption saying what it costs — so a `nil` selection writes nothing.
    ///
    /// One generic binding rather than one per field, so that rule has a single
    /// home. The key path keeps the read live, so no value is captured.
    private func selection<Value: MoneyProfileFieldValue & Hashable>(
        _ selected: KeyPath<YourDetailsViewModel, DetailOption<Value>?>,
        write: @escaping (DetailOption<Value>) async -> Void
    ) -> Binding<DetailOption<Value>?> {
        Binding(
            get: { viewModel[keyPath: selected] },
            set: { option in
                guard let option else { return }
                Task { await write(option) }
            }
        )
    }

    private var incomeSelection: Binding<DetailOption<IncomeBand>?> {
        selection(\.selectedIncome) { await viewModel.answer(income: $0) }
    }

    private var residencySelection: Binding<DetailOption<ResidencyState>?> {
        selection(\.selectedResidency) { await viewModel.answer(residency: $0) }
    }
}

// MARK: - Accessibility identifiers

extension MoneyProfileField {
    /// Every identifier this screen puts on a control, spelled out rather than
    /// assembled from the raw value plus a suffix: a grep for
    /// `incomeDeclineButton` must find the line that produces it, which an
    /// interpolated identifier defeats.
    var savedReceiptIdentifier: String {
        switch self {
        case .income: return "incomeSavedReceipt"
        case .residency: return "residencySavedReceipt"
        }
    }

    var answerTextIdentifier: String {
        switch self {
        case .income: return "incomeAnswerText"
        case .residency: return "residencyAnswerText"
        }
    }

    var declineButtonIdentifier: String {
        switch self {
        case .income: return "incomeDeclineButton"
        case .residency: return "residencyDeclineButton"
        }
    }

    var removeButtonIdentifier: String {
        switch self {
        case .income: return "incomeRemoveButton"
        case .residency: return "residencyRemoveButton"
        }
    }
}

// MARK: - Previews

private final class YourDetailsPreviewMoneyProfileClient: MoneyProfileClientProtocol, @unchecked Sendable {
    func fetch() async throws -> PublicMoneyProfile? {
        PublicMoneyProfile(
            incomeBandStatus: AnswerStatus.answered.rawValue,
            incomeBand: IncomeBand.k48To75k.rawValue,
            residencyStatus: AnswerStatus.declined.rawValue,
            residencyState: nil,
            livingPlanStatus: AnswerStatus.unanswered.rawValue,
            livingPlan: nil,
            version: 3,
            createdAt: Date(),
            updatedAt: Date()
        )
    }

    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile {
        PublicMoneyProfile.answering(request, createdAt: Date(), updatedAt: Date())
    }
}

private final class YourDetailsPreviewVocabularyClient: VocabularyClientProtocol, @unchecked Sendable {
    func fetch() async throws -> VocabulariesResponse {
        VocabulariesResponse(
            version: "preview",
            vocabularies: [
                VocabulariesResponse.Name.incomeBands.rawValue: PublicVocabulary(
                    entries: IncomeBand.allCases.map {
                        VocabularyEntry(value: $0.rawValue, label: $0.bracket)
                    }
                ),
                VocabulariesResponse.Name.residencyStates.rawValue: PublicVocabulary(
                    entries: ResidencyStates.offered.map {
                        VocabularyEntry(value: $0.code, label: $0.name)
                    }
                ),
            ]
        )
    }
}

@MainActor private var yourDetailsPreview: some View {
    NavigationStack {
        YourDetailsView(
            moneyProfileClient: YourDetailsPreviewMoneyProfileClient(),
            vocabularyClient: YourDetailsPreviewVocabularyClient(),
            onProfileRequired: {},
            onMyColleges: {}
        )
    }
}

#Preview("yourDetails - Light") {
    yourDetailsPreview
        .preferredColorScheme(.light)
}

#Preview("yourDetails - Dark") {
    yourDetailsPreview
        .preferredColorScheme(.dark)
}
