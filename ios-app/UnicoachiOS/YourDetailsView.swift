import SwiftUI

/// The subject row's words. They live here, with the screen that asks the
/// question, because they name the domain — subjects, and unicoach's rule about
/// how many — and `DSSubjectsRow` takes them as parameters precisely so the
/// design system does not have to know either thing.
enum SubjectsRowCopy {
    static let add = "Add a subject"

    /// Said when the cap has taken the add affordance away, so the row explains
    /// its own missing control.
    ///
    /// The number is SPELLED FROM THE CAP rather than typed into the sentence:
    /// the copy and the rule it explains are then the same fact, and a changed
    /// `PublicIntendedSubjects.maxSubjects` cannot leave the word "three"
    /// behind. `en_US` is fixed because the rest of the sentence is English —
    /// a localised numeral inside English copy would read worse than either.
    static func cap(_ maxSubjects: Int) -> String {
        "That's \(spelled(maxSubjects)) — the most unicoach asks for. Remove one to swap it."
    }

    /// Said when the load-time probe could not reach the list. It does NOT say
    /// there is nothing to search: the add affordance is still there on
    /// purpose, and the picker refetches the list every time it opens, so
    /// trying again is exactly the right thing for a family to do.
    static let listUnavailable =
        "unicoach couldn't reach the subject list just now. What you've already chosen is shown above, "
            + "and opening the search again may well reach it."

    /// The row's own caption, above the chips. Same rule as [cap]: the number
    /// is spelled FROM the cap, never typed into the sentence.
    static func intro(_ maxSubjects: Int) -> String {
        "Up to \(spelled(maxSubjects)) subjects, in no order. Change or remove them whenever you like."
    }

    /// The picker sheet's caption. It lives here, with [cap] and [intro],
    /// because it states the same rule and must not be able to disagree with
    /// them.
    static func pickerIntro(_ maxSubjects: Int) -> String {
        "Search the subjects unicoach knows. Pick up to \(spelled(maxSubjects)) — there's no ranking, "
            + "and you can change them whenever you like."
    }

    private static func spelled(_ count: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .spellOut
        formatter.locale = Locale(identifier: "en_US")
        return formatter.string(from: NSNumber(value: count)) ?? String(count)
    }
}

/// The sharing control's words (RFC 251 §5). Beside `SubjectsRowCopy` because
/// they speak about the same answer; quoted verbatim from the RFC, whose gate
/// approved them as consent words.
enum SharingPermissionCopy {
    static let label = "Who can see them"
    static let grant = "Show them"
    static let refuse = "Keep them to me"

    /// Said with the control, before any grant can be written: what a yes
    /// reaches — links already sent included, because the report is read live
    /// on every load — and that the coach's use does not depend on it.
    ///
    /// Byte-identical to `SharingPermissionCopy.CONSENT_WORDS` in
    /// service/src/main/kotlin/ed/unicoach/coaching/SharingPermissionChatTool.kt,
    /// the words the coach asks in. Nothing checks that the two agree: edit one
    /// without the other and the two consent doors tell the student different
    /// things.
    static let caption =
        "Off until you say yes. If you say yes, people you share with can see the subjects you chose "
            + "and what graduates of them earn and owe — that includes anyone holding a Family Cost Report "
            + "link you've already sent. Your coach uses them either way, and you can change this whenever you like."

    /// The answer line while nothing is stored, so neither segment being
    /// selected reads as the default it is rather than as a blank.
    static let notAsked = "You haven't said yet, so nobody you share with sees them."
}

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

    /// Held so the picker sheet can be built when it is opened, rather than a
    /// list being fetched into this screen and handed down stale.
    private let subjectsClient: SubjectsClientProtocol

    init(
        moneyProfileClient: MoneyProfileClientProtocol,
        vocabularyClient: VocabularyClientProtocol,
        subjectsClient: SubjectsClientProtocol,
        intendedSubjectsClient: IntendedSubjectsClientProtocol,
        sharingPermissionsClient: SharingPermissionsClientProtocol,
        onProfileRequired: @escaping () -> Void,
        onMyColleges: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: YourDetailsViewModel(
            moneyProfileClient: moneyProfileClient,
            vocabularyClient: vocabularyClient,
            subjectsClient: subjectsClient,
            intendedSubjectsClient: intendedSubjectsClient,
            sharingPermissionsClient: sharingPermissionsClient,
            onProfileRequired: onProfileRequired
        ))
        self.subjectsClient = subjectsClient
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

                subjectsSection

                footer
            }
            .padding(DSSpacing.lg)
        }
    }

    /// What a student wants to study (RFC 205): one answer, given once, that
    /// later slices turn into what search shows and what the report can say.
    /// It is NOT money, so it sits under its own heading rather than inside the
    /// money block above — and it speaks no figure here.
    ///
    /// A decline or an "I don't know yet" set in CHAT lands here as itself, and
    /// every one of them is undoable with one tap.
    private var subjectsSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("What you want to study")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextPrimary)

            Text(SubjectsRowCopy.intro(PublicIntendedSubjects.maxSubjects))
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
                .fixedSize(horizontal: false, vertical: true)

            DSSubjectsRow(
                label: "Subjects",
                answer: viewModel.subjectsAnswer,
                availability: viewModel.subjectsAvailability,
                maxChips: PublicIntendedSubjects.maxSubjects,
                addTitle: SubjectsRowCopy.add,
                capNote: SubjectsRowCopy.cap,
                listUnavailableNote: SubjectsRowCopy.listUnavailable,
                onRemove: { chip in Task { await viewModel.removeSubject(chip) } },
                onUndecided: { Task { await viewModel.markSubjectsUndecided() } },
                onDecline: { Task { await viewModel.declineSubjects() } },
                picker: { dismiss in
                    SubjectPickerSheet(
                        client: subjectsClient,
                        chosen: viewModel.chosenSubjectSlugs,
                        onSelect: { subject in
                            // The sheet closes on the pick, and the write runs
                            // on. A sheet held open over a spinner would only
                            // hide the chips that are the receipt.
                            dismiss()
                            Task { await viewModel.addSubject(subject) }
                        },
                        onDismiss: dismiss
                    )
                }
            )

            sharingControl
        }
    }

    /// Whether people the student shares with may see these subjects (RFC 251
    /// §5). Shown whatever the subjects answer is: the permission is its own
    /// answer, and a yes given before any subject is chosen covers the ones
    /// chosen later.
    ///
    /// The tag is OPTIONAL so a student never asked sees neither segment
    /// selected, and the setter can only ever receive `granted` or `refused` —
    /// "never asked" cannot be written from here. The answer line sits above
    /// the control, as every field on this screen reads its state before the
    /// control that changes it.
    private var sharingControl: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text(SharingPermissionCopy.label)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)

            if viewModel.sharingAnswer == .notAsked {
                Text(SharingPermissionCopy.notAsked)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("sharingNotAskedText")
            }

            DSCaptioned(SharingPermissionCopy.caption) {
                SegmentedSelector(
                    options: [
                        (tag: SharingChoice?.some(.granted), title: SharingPermissionCopy.grant),
                        (tag: SharingChoice?.some(.refused), title: SharingPermissionCopy.refuse),
                    ],
                    selection: sharingSelection,
                    accessibilityIdentifier: "sharingSubjectsSelector"
                )
            }
        }
        .padding(.top, DSSpacing.sm)
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
    private func selection<Choice>(
        _ selected: KeyPath<YourDetailsViewModel, Choice?>,
        write: @escaping (Choice) async -> Void
    ) -> Binding<Choice?> {
        Binding(
            get: { viewModel[keyPath: selected] },
            set: { choice in
                guard let choice else { return }
                Task { await write(choice) }
            }
        )
    }

    private var incomeSelection: Binding<DetailOption<IncomeBand>?> {
        selection(\.selectedIncome) { await viewModel.answer(income: $0) }
    }

    private var residencySelection: Binding<DetailOption<ResidencyState>?> {
        selection(\.selectedResidency) { await viewModel.answer(residency: $0) }
    }

    /// Reads the stored answer, never a hoped-for one: a failed write leaves
    /// the segment on what the server holds.
    private var sharingSelection: Binding<SharingChoice?> {
        selection(\.sharingAnswer.choice) { await viewModel.answerSharing($0) }
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

private final class YourDetailsPreviewSubjectsClient: SubjectsClientProtocol, @unchecked Sendable {
    func search(query: String?) async throws -> [PublicSubject] {
        let subjects = [
            PublicSubject(slug: "nursing", name: "Nursing"),
            PublicSubject(slug: "marine-biology", name: "Marine biology"),
            PublicSubject(slug: "neuroscience", name: "Neuroscience"),
            PublicSubject(slug: "computer-science", name: "Computer science"),
        ]
        guard let query, !query.isEmpty else { return subjects }
        return subjects.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }
}

private final class YourDetailsPreviewIntendedSubjectsClient: IntendedSubjectsClientProtocol, @unchecked Sendable {
    func fetch() async throws -> PublicIntendedSubjects {
        PublicIntendedSubjects(
            status: AnswerStatus.answered.rawValue,
            subjects: [PublicSubject(slug: "nursing", name: "Nursing")]
        )
    }

    func update(_ request: UpdateIntendedSubjectsRequest) async throws -> PublicIntendedSubjects {
        try await fetch()
    }
}

@MainActor private var yourDetailsPreview: some View {
    NavigationStack {
        YourDetailsView(
            moneyProfileClient: YourDetailsPreviewMoneyProfileClient(),
            vocabularyClient: YourDetailsPreviewVocabularyClient(),
            subjectsClient: YourDetailsPreviewSubjectsClient(),
            intendedSubjectsClient: YourDetailsPreviewIntendedSubjectsClient(),
            sharingPermissionsClient: PreviewSharingPermissionsClient(),
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
