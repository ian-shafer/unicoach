import SwiftUI

/// The list half of Settings' "Family & Supporters" section (RFC 249): who is
/// on the other end of the caller's links, and a two-tap disconnect for each.
///
/// The student's voice also states, ONCE, what every connected adult can see
/// and what stays private — the served ceiling, not a per-row copy, because
/// every adult has the same ceiling today.
struct FamilyConnectionsSection: View {
    @StateObject private var viewModel: FamilyConnectionsViewModel

    /// The side as handed in THIS render. A supporter's roster is the home's,
    /// so it is read from here: `@StateObject` keeps only the side it was
    /// first built with, and a refreshed home would otherwise never show.
    private let side: FamilyConnectionsViewModel.Side

    /// The row whose confirmation opens already expanded. The
    /// `SupporterSubjectSwitcher` `openOnAppear` convention: the app always
    /// passes `nil`, and the snapshot catalogue photographs it open.
    private let confirmingOnAppear: UUID?

    init(side: FamilyConnectionsViewModel.Side, confirmingOnAppear: UUID? = nil) {
        _viewModel = StateObject(wrappedValue: FamilyConnectionsViewModel(side: side))
        self.side = side
        self.confirmingOnAppear = confirmingOnAppear
    }

    /// Whose Settings this is — the viewer, not the person on any row.
    private var callerIsStudent: Bool {
        switch side {
        case .student: return true
        case .supporter: return false
        }
    }

    private var state: FamilyConnectionsViewModel.State {
        switch side {
        case .student:
            return viewModel.state
        case .supporter(let family):
            return .loaded(FamilyConnectionsViewModel.roster(of: family))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text(callerIsStudent ? "People connected to you" : "Students you support")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityAddTraits(.isHeader)

            switch state {
            case .loading:
                ProgressView()
                    .tint(Color.dsTextPrimary)
                    .accessibilityIdentifier("familyConnectionsLoading")
            case .failed:
                loadFailure
            case .loaded(let roster):
                loaded(roster)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Here rather than on Settings, so the student's list reloads every
        // time Settings is pushed. A supporter's `load()` reads nothing.
        .task { await viewModel.load() }
    }

    @ViewBuilder
    private func loaded(_ roster: FamilyConnectionsViewModel.Roster) -> some View {
        if roster.connections.isEmpty {
            emptyList
        } else {
            rows(roster.connections)
            switch roster.voice {
            case .student(let ceiling, let costReportLinkLive):
                studentNotes(ceiling, costReportLinkLive: costReportLinkLive)
            case .supporter:
                EmptyView()
            }
        }
    }

    private var emptyList: some View {
        Text(callerIsStudent ? "No one is connected yet." : "No students connected yet.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("familyConnectionsEmpty")
    }

    private func rows(_ connections: [FamilyConnectionsViewModel.Connection]) -> some View {
        VStack(spacing: DSControl.stackGap) {
            ForEach(connections) { connection in
                FamilyConnectionRow(
                    connection: connection,
                    callerIsStudent: callerIsStudent,
                    rowState: viewModel.rowStates[connection.linkId],
                    confirmingOnAppear: connection.linkId == confirmingOnAppear,
                    onDisconnect: { Task { await viewModel.disconnect(connection.linkId) } }
                )
            }
        }
    }

    @ViewBuilder
    private func studentNotes(_ ceiling: SupporterCeiling, costReportLinkLive: Bool) -> some View {
        ceilingList("What everyone here can see", ceiling.canSee)
        ceilingList("What stays private", ceiling.staysPrivate)
        if costReportLinkLive {
            costReportLinkNote
        }
    }

    private var costReportLinkNote: some View {
        Text("Disconnecting does not turn off your Family Cost Report link. Anyone with that link can still open it — ask your coach to turn it off.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("costReportLinkNote")
    }

    private func ceilingList(_ title: String, _ items: [String]) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(title)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextPrimary)
            ForEach(items, id: \.self) { item in
                HStack(alignment: .firstTextBaseline, spacing: DSSpacing.sm) {
                    Text("•")
                    Text(item)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var loadFailure: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("Couldn't load who is connected to you.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("familyConnectionsError")
            DSTextButton(
                "Try again",
                role: .secondary,
                accessibilityIdentifier: "familyConnectionsRetry",
                accessibilityLabel: "Try again",
                action: { Task { await viewModel.load() } }
            )
            .fixedSize()
        }
    }
}

/// One person, with `Disconnect` and — after the first tap — an inline
/// confirmation in the row. An in-view disclosure rather than a stock
/// `confirmationDialog`, the `SupporterSubjectSwitcher` reason: a system dialog
/// renders in a window the snapshot rasterizer cannot reach.
private struct FamilyConnectionRow: View {
    let connection: FamilyConnectionsViewModel.Connection
    /// Whose Settings this row is on — the viewer, not the person on the row.
    let callerIsStudent: Bool
    let rowState: FamilyConnectionsViewModel.RowState?
    let onDisconnect: () -> Void

    @State private var isConfirming: Bool

    init(
        connection: FamilyConnectionsViewModel.Connection,
        callerIsStudent: Bool,
        rowState: FamilyConnectionsViewModel.RowState?,
        confirmingOnAppear: Bool,
        onDisconnect: @escaping () -> Void
    ) {
        self.connection = connection
        self.callerIsStudent = callerIsStudent
        self.rowState = rowState
        self.onDisconnect = onDisconnect
        _isConfirming = State(initialValue: confirmingOnAppear)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            HStack(alignment: .center, spacing: DSSpacing.md) {
                identity
                Spacer(minLength: 0)
                if !isConfirming {
                    disconnectButton
                }
            }
            if isConfirming {
                confirmation
            }
            if rowState == .failed {
                failureLine
            }
        }
        .padding(.horizontal, DSControl.textInset)
        .padding(.vertical, DSSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dsOutlinedCard()
        .accessibilityIdentifier("familyConnectionRow")
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text(connection.name)
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextPrimary)
                .accessibilityIdentifier("familyConnectionName")
            if !connection.alsoConnected.isEmpty {
                Text("Also supporting \(connection.name): \(connection.alsoConnected.joined(separator: ", "))")
                    .font(.dsCaption)
                    .foregroundStyle(Color.dsTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("familyConnectionAlso")
            }
        }
    }

    private var disconnectButton: some View {
        DSTextButton(
            "Disconnect",
            role: .secondary,
            isLoading: rowState == .disconnecting,
            loadingTitle: "Disconnecting…",
            accessibilityIdentifier: "disconnectButton",
            accessibilityLabel: "Disconnect \(connection.name)",
            action: { withAnimation { isConfirming = true } }
        )
        .fixedSize()
    }

    private var failureLine: some View {
        Text("Disconnecting didn't work. Try again.")
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("disconnectError")
    }

    private var prompt: String {
        if callerIsStudent {
            return "Disconnect \(connection.name)? They lose access right away. Your account and theirs stay as they are."
        }
        return "Disconnect from \(connection.name)? You lose access right away. Your account and theirs stay as they are."
    }

    private var confirmation: some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            Text(prompt)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("disconnectPrompt")

            LoadingButton(
                "Disconnect",
                isLoading: false,
                role: .destructive,
                accessibilityIdentifier: "disconnectConfirmButton",
                accessibilityLabel: "Disconnect \(connection.name)",
                action: {
                    withAnimation { isConfirming = false }
                    onDisconnect()
                }
            )

            DSTextButton(
                "Cancel",
                role: .secondary,
                accessibilityIdentifier: "disconnectCancelButton",
                accessibilityLabel: "Cancel",
                action: { withAnimation { isConfirming = false } }
            )
        }
    }
}
