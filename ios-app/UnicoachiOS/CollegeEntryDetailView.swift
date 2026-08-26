import SwiftUI

/// One college-list entry (RFC 137): restatus via `SegmentedSelector`, edit
/// the reasons, and read — never write — the conversational citations backing
/// it. Save is the screen's one filled control, enabled only when something
/// changed; it updates through the list's own view model, then pops.
struct CollegeEntryDetailView: View {
    @ObservedObject private var viewModel: CollegeListViewModel
    private let entry: CollegeListEntry

    @State private var status: CollegeListStatus
    @State private var reasons: String
    @State private var isSaving = false
    @Environment(\.dismiss) private var dismiss

    /// The server's `reasons` cap, mirrored client-side (RFC 91's CHECK).
    private static let reasonsCap = 2048

    init(viewModel: CollegeListViewModel, entry: CollegeListEntry) {
        self.viewModel = viewModel
        self.entry = entry
        _status = State(initialValue: entry.status)
        _reasons = State(initialValue: entry.reasons ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DSSpacing.lg) {
                statusSection
                reasonsSection
                if !entry.supportingObservations.isEmpty {
                    observationsSection
                }
                saveButton
            }
            .padding(DSSpacing.lg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
        .navigationTitle(entry.collegeName)
        .navigationBarTitleDisplayMode(.inline)
        .alert(item: $viewModel.actionError) { error in
            Alert(
                title: Text("Something went wrong"),
                message: Text(error.message),
                dismissButton: .default(Text("OK"))
            )
        }
    }

    // MARK: - Sections

    private var statusSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("Status")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)
            SegmentedSelector(
                options: CollegeListStatus.allCases.map { (tag: $0, title: $0.displayName) },
                selection: $status,
                accessibilityIdentifier: "statusSelector"
            )
        }
    }

    /// The reasons editor: a multiline field in the `LabeledField` box
    /// vocabulary (label above, `dsSurface` fill, hairline border). Not
    /// `LabeledField` itself, which is a single-line control.
    private var reasonsSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("Why it's on your list")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextSecondary)
            TextField("Add your reasons", text: $reasons, axis: .vertical)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .lineLimit(3...8)
                .padding(.horizontal, DSControl.textInset)
                .padding(.vertical, DSSpacing.md)
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .dsOutlinedCard()
                .onChange(of: reasons) { _, newValue in
                    if newValue.count > Self.reasonsCap {
                        reasons = String(newValue.prefix(Self.reasonsCap))
                    }
                }
                .accessibilityIdentifier("reasonsField")
                .accessibilityLabel("Why it's on your list")
        }
    }

    /// Read-only provenance (RFC 91/136): the quotes that put this college on
    /// the list. Displayed, never written, from this screen.
    private var observationsSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            Text("From your conversations")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextSecondary)
            ForEach(entry.supportingObservations, id: \.id) { observation in
                Text("\u{201C}\(observation.quote)\u{201D}")
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)
                    .padding(DSSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .dsOutlinedCard()
                    .accessibilityIdentifier("supportingObservation")
            }
        }
    }

    private var saveButton: some View {
        Button {
            Task { await save() }
        } label: {
            if isSaving {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(Color.dsControlOnFill)
            } else {
                Text("Save")
            }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(!isDirty || isSaving)
        .accessibilityIdentifier("saveEntryButton")
        .accessibilityLabel("Save")
    }

    // MARK: - Behaviour

    /// Whitespace-only reasons are "no reasons": the server rejects an empty
    /// string outright, so the emptied field must be sent as nil.
    ///
    /// Trimming is an **emptiness test only** — the non-empty branch returns
    /// the UNTRIMMED text on purpose. Returning `trimmed` would silently
    /// rewrite what the student typed (and shift `isDirty`); the server
    /// accepts interior/edge whitespace, so their text is sent verbatim.
    private var normalizedReasons: String? {
        let trimmed = reasons.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : reasons
    }

    private var isDirty: Bool {
        status != entry.status || normalizedReasons != entry.reasons
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }
        // A stale entry pops too: this screen's `entry` snapshot can only ever
        // resend the stale version, so the reloaded list underneath — not this
        // screen — is where the student re-applies. The standard alert still
        // names the problem over the fresh list. Only `.failed` (a refusal the
        // list did not move for) keeps the screen up for a retry.
        switch await viewModel.update(entry: entry, status: status, reasons: normalizedReasons) {
        case .saved, .staleEntry:
            dismiss()
        case .failed:
            break // the alert names the problem; the screen stays up for a retry
        }
    }
}
