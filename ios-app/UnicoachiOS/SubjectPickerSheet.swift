import SwiftUI

/// The searchable list `DSSubjectsRow` opens (RFC 205 §5/§7). It is the app
/// half of that control: the design system owns the row, the chips and the cap;
/// this sheet owns where subjects come from.
///
/// A subject already chosen is shown and **not** tappable, with "Added" beside
/// it. The server refuses a duplicate with a 400, and a row that could only
/// produce an error the student cannot act on is not a choice.
struct SubjectPickerSheet: View {
    @StateObject private var viewModel: SubjectPickerViewModel
    /// The slugs already on the answer, so this list never offers one twice.
    private let chosen: Set<String>
    private let onSelect: (PublicSubject) -> Void
    private let onDismiss: () -> Void

    init(
        client: SubjectsClientProtocol,
        chosen: Set<String>,
        onSelect: @escaping (PublicSubject) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: SubjectPickerViewModel(client: client))
        self.chosen = chosen
        self.onSelect = onSelect
        self.onDismiss = onDismiss
    }

    var body: some View {
        DSSheetScroll {
            Text("What do you want to study?")
                .font(.dsTitle)
                .foregroundStyle(Color.dsTextPrimary)

            Text(SubjectsRowCopy.pickerIntro(PublicIntendedSubjects.maxSubjects))
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .fixedSize(horizontal: false, vertical: true)

            searchField

            results

            DSTextButton(
                "Done",
                role: .secondary,
                accessibilityIdentifier: "subjectPickerDoneButton",
                accessibilityLabel: "Done",
                action: onDismiss
            )
        }
        .task { await viewModel.load() }
    }

    private var searchField: some View {
        TextField("Search subjects", text: $viewModel.query)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextPrimary)
            .textInputAutocapitalization(.never)
            .disableAutocorrection(true)
            .padding(.horizontal, DSControl.textInset)
            .dsControlBox(minHeight: DSControl.height)
            .accessibilityIdentifier("subjectPickerSearchField")
            .accessibilityLabel("Search subjects")
    }

    @ViewBuilder
    private var results: some View {
        switch viewModel.state {
        case .searching:
            ProgressView()
                .progressViewStyle(.circular)
                .tint(Color.dsTextPrimary)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("subjectPickerLoading")
                .accessibilityLabel("Searching subjects")
        case .results(let subjects):
            VStack(spacing: DSControl.stackGap) {
                ForEach(subjects) { subject in
                    row(subject)
                }
            }
        case .empty:
            // A term that is not a subject is refused with a reason, never
            // mapped to something near it (RFC 205 §5, D15's surviving half).
            message(
                "That's not a subject on the list. Try another word — or ask your coach, "
                    + "who can say what unicoach does know.",
                identifier: "subjectPickerEmpty"
            )
        case .failed(let error):
            message(error.message, identifier: "subjectPickerFailed")
        }
    }

    /// One result. **Not an `OptionCard`**: that control carries a radio
    /// circle, which says "one of these", and this list adds one of up to three
    /// to a set. A plain control-shaped row says what this actually is.
    private func row(_ subject: PublicSubject) -> some View {
        let isChosen = chosen.contains(subject.slug)
        return Button(action: { onSelect(subject) }) {
            HStack(spacing: DSSpacing.md) {
                Text(subject.name)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                if isChosen {
                    Text("Added")
                        .font(.dsCaption)
                        .foregroundStyle(Color.dsTextSecondary)
                }
            }
            .padding(.horizontal, DSControl.textInset)
            .padding(.vertical, DSSpacing.sm)
            .dsControlBox(minHeight: DSControl.tapTarget)
            .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isChosen)
        .accessibilityLabel(isChosen ? "\(subject.name), already added" : subject.name)
    }

    private func message(_ text: String, identifier: String) -> some View {
        Text(text)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier(identifier)
    }
}
