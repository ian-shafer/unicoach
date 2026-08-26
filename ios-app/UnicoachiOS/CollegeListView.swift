import SwiftUI

/// The student's college list (RFC 137): a pushed destination behind the
/// drawer's "My colleges" entry, the direct-manipulation counterpart to the
/// chat tool. Rows are the drawer's bordered card shape; row tap opens the
/// entry detail, the toolbar plus opens the add-college search.
struct CollegeListView: View {
    @StateObject private var viewModel: CollegeListViewModel
    /// Held here — not on the list view model — so this view can wire the add
    /// screen with the same client instance and escalation the list uses.
    private let client: CollegeListClientProtocol
    private let onProfileRequired: () -> Void

    /// Staged by a swipe Delete for the confirmation dialog — removal discards
    /// status and reasons, so it confirms (the `ConversationListView`
    /// precedent). `viewModel.remove` fires only on the dialog's confirm.
    @State private var pendingRemoval: CollegeListEntry?

    init(client: CollegeListClientProtocol, onProfileRequired: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: CollegeListViewModel(
            client: client, onProfileRequired: onProfileRequired
        ))
        self.client = client
        self.onProfileRequired = onProfileRequired
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dsBackground)
            .navigationTitle("My colleges")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        addCollege
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityIdentifier("addCollegeButton")
                    .accessibilityLabel("Add a college")
                }
            }
            // `refresh()` rather than `load()`: it falls back to a full load
            // on first appearance, and on every pop-back (from add or detail)
            // it re-reads without the `.loading` blink — which is also what
            // picks up an entry the chat tool moved meanwhile.
            .task { await viewModel.refresh() }
            .confirmationDialog(
                "Remove this college?",
                isPresented: removalDialogBinding,
                titleVisibility: .visible,
                presenting: pendingRemoval
            ) { entry in
                Button("Remove", role: .destructive) {
                    Task { await viewModel.remove(entry) }
                }
                .accessibilityIdentifier("removeConfirmButton")
                Button("Cancel", role: .cancel) {}
                    .accessibilityIdentifier("removeCancelButton")
            } message: { _ in
                Text("Its status and reasons are discarded.")
            }
            .alert(item: $viewModel.actionError) { error in
                Alert(
                    title: Text("Something went wrong"),
                    message: Text(error.message),
                    dismissButton: .default(Text("OK"))
                )
            }
    }

    private var removalDialogBinding: Binding<Bool> {
        Binding(
            get: { pendingRemoval != nil },
            set: { presented in
                if !presented { pendingRemoval = nil }
            }
        )
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading:
            loadingView
        case .loaded(let entries):
            entryList(entries)
        case .empty:
            emptyView
        case .failed(let error):
            ErrorView(
                title: "Something went wrong",
                description: error.message,
                systemImage: "exclamationmark.triangle",
                retryAction: { Task { await viewModel.load() } }
            )
            .accessibilityIdentifier("collegeListFailed")
        }
    }

    // MARK: - States

    private var loadingView: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .tint(Color.dsTextPrimary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("collegeListLoading")
            .accessibilityLabel("Loading your colleges")
    }

    private func entryList(_ entries: [CollegeListEntry]) -> some View {
        List(entries) { entry in
            NavigationLink {
                CollegeEntryDetailView(viewModel: viewModel, entry: entry)
            } label: {
                entryRow(entry)
            }
            .listRowSeparator(.hidden)
            .listRowBackground(Color.dsBackground)
            .listRowInsets(EdgeInsets(
                top: DSControl.stackGap / 2, leading: DSSpacing.lg,
                bottom: DSControl.stackGap / 2, trailing: DSSpacing.lg
            ))
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) {
                    pendingRemoval = entry
                } label: {
                    Label("Remove", systemImage: "trash")
                }
                .accessibilityIdentifier("removeButton")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.dsBackground)
    }

    private func entryRow(_ entry: CollegeListEntry) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            HStack(spacing: DSSpacing.sm) {
                Text(entry.collegeName)
                    .font(.dsBody)
                    .foregroundStyle(Color.dsTextPrimary)
                Spacer(minLength: DSSpacing.xs)
                statusPill(entry.status)
            }
            if let reasons = firstReasonsLine(entry) {
                Text(reasons)
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)
                    .lineLimit(1)
            }
        }
        .padding(DSSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dsOutlinedCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("collegeEntryRow")
    }

    /// The monochrome status pill: `dsOverline` uppercase inside a
    /// `dsFieldBorder` hairline capsule — no new colour roles (DESIGN.md §2).
    private func statusPill(_ status: CollegeListStatus) -> some View {
        Text(status.displayName)
            .dsOverlineStyle()
            .foregroundStyle(Color.dsTextPrimary)
            .padding(.horizontal, DSSpacing.sm)
            .padding(.vertical, DSSpacing.xs)
            .overlay(
                Capsule(style: .continuous)
                    .stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
            )
            .accessibilityLabel(status.displayName)
    }

    private func firstReasonsLine(_ entry: CollegeListEntry) -> String? {
        guard let reasons = entry.reasons?.trimmingCharacters(in: .whitespacesAndNewlines),
              !reasons.isEmpty else {
            return nil
        }
        return reasons.components(separatedBy: .newlines).first
    }

    private var emptyView: some View {
        VStack(spacing: DSSpacing.md) {
            Text("No colleges yet. Add one, or ask your coach.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .multilineTextAlignment(.center)
            NavigationLink {
                addCollege
            } label: {
                Text("Add a college")
                    .font(.dsButton)
                    .foregroundStyle(Color.dsControlOnFill)
                    .frame(maxWidth: .infinity, minHeight: DSControl.height)
                    .background(Color.dsControlFill)
                    .clipShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
            }
            .accessibilityIdentifier("emptyAddCollegeButton")
            .accessibilityLabel("Add a college")
        }
        .padding(DSSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("collegeListEmpty")
    }

    private var addCollege: some View {
        AddCollegeView(client: client, onProfileRequired: onProfileRequired)
    }
}
