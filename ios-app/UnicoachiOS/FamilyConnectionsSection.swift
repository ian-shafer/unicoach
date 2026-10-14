import SwiftUI

/// The list half of Settings' "Family & Supporters" section (RFC 249): who is
/// on the other end of the caller's links, and a two-tap disconnect for each.
/// Above them, on the student's side, the adults asking to connect (RFC 254).
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

    /// Which confirmation opens already expanded. The
    /// `SupporterSubjectSwitcher` `openOnAppear` convention: the app always
    /// passes `nil`, and the snapshot catalogue photographs it open.
    enum ConfirmingOnAppear: Equatable {
        case disconnect(linkId: UUID)
        case decline(requestId: UUID)
    }

    private let confirmingOnAppear: ConfirmingOnAppear?

    init(side: FamilyConnectionsViewModel.Side, confirmingOnAppear: ConfirmingOnAppear? = nil) {
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

    /// A request is enough to show the list: a student whose only adult is
    /// still asking needs the request AND the ceiling it would grant.
    @ViewBuilder
    private func loaded(_ roster: FamilyConnectionsViewModel.Roster) -> some View {
        if roster.connections.isEmpty && roster.requests.isEmpty {
            emptyList
        } else {
            if !roster.requests.isEmpty {
                requestRows(roster.requests)
            }
            if !roster.connections.isEmpty {
                rows(roster.connections)
            }
            switch roster.voice {
            case .student(let ceiling, let costReportLinkLive, _):
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
                    confirmingOnAppear: confirmingOnAppear == .disconnect(linkId: connection.linkId),
                    onDisconnect: { Task { await viewModel.disconnect(connection.linkId) } }
                )
            }
        }
    }

    private func requestRows(_ requests: [SupporterRequest]) -> some View {
        VStack(spacing: DSControl.stackGap) {
            ForEach(requests) { request in
                FamilyRequestRow(
                    request: request,
                    requestState: viewModel.requestStates[request.requestId],
                    confirmingOnAppear: confirmingOnAppear == .decline(requestId: request.requestId),
                    onApprove: { Task { await viewModel.approve(request.requestId) } },
                    onDecline: { Task { await viewModel.decline(request.requestId) } }
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
            return "Disconnect \(connection.name)? They lose access right away. Your account and theirs stay as they are. We let them know the connection ended."
        }
        return "Disconnect from \(connection.name)? You lose access right away. Your account and theirs stay as they are. We let them know the connection ended."
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

/// One adult asking to connect (RFC 254), with their email so the student can
/// tell whether they know them. `Approve` is one tap; `Decline` asks again in
/// the row, the `FamilyConnectionRow` disclosure and for its reason.
private struct FamilyRequestRow: View {
    let request: SupporterRequest
    let requestState: FamilyConnectionsViewModel.RequestState?
    let onApprove: () -> Void
    let onDecline: () -> Void

    @State private var isConfirming: Bool

    init(
        request: SupporterRequest,
        requestState: FamilyConnectionsViewModel.RequestState?,
        confirmingOnAppear: Bool,
        onApprove: @escaping () -> Void,
        onDecline: @escaping () -> Void
    ) {
        self.request = request
        self.requestState = requestState
        self.onApprove = onApprove
        self.onDecline = onDecline
        _isConfirming = State(initialValue: confirmingOnAppear)
    }

    private var isAnswering: Bool {
        requestState?.isInFlight == true
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            identity
            if isConfirming {
                confirmation
            } else {
                answers
            }
            if case .failed(let answer) = requestState {
                failureLine(answer)
            }
        }
        .padding(.horizontal, DSControl.textInset)
        .padding(.vertical, DSSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .dsOutlinedCard()
        .accessibilityIdentifier("familyRequestRow")
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            Text("\(request.name) asked to connect")
                .font(.dsLabel)
                .foregroundStyle(Color.dsTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("familyRequestName")
            Text(request.email)
                .font(.dsCaption)
                .foregroundStyle(Color.dsTextSecondary)
                .accessibilityIdentifier("familyRequestEmail")
        }
    }

    private var answers: some View {
        HStack(spacing: DSSpacing.md) {
            DSTextButton(
                "Approve",
                isLoading: requestState == .inFlight(.approve),
                loadingTitle: "Approving…",
                accessibilityIdentifier: "approveRequestButton",
                accessibilityLabel: "Approve \(request.name)",
                action: onApprove
            )
            .disabled(isAnswering)
            DSTextButton(
                "Decline",
                role: .secondary,
                isLoading: requestState == .inFlight(.decline),
                loadingTitle: "Declining…",
                accessibilityIdentifier: "declineRequestButton",
                accessibilityLabel: "Decline \(request.name)",
                action: { withAnimation { isConfirming = true } }
            )
            .disabled(isAnswering)
        }
    }

    private func failureLine(_ answer: FamilyConnectionsViewModel.Answer) -> some View {
        let line: String
        switch answer {
        case .approve: line = "Approving didn't work. Try again."
        case .decline: line = "Declining didn't work. Try again."
        }
        return Text(line)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("requestAnswerError")
    }

    private var confirmation: some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            Text("Decline \(request.name)? They can't ask to connect with you again, and nothing of yours is shared with them. We don't send them a message.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("declinePrompt")

            LoadingButton(
                "Decline",
                isLoading: false,
                role: .destructive,
                accessibilityIdentifier: "declineConfirmButton",
                accessibilityLabel: "Decline \(request.name)",
                action: {
                    withAnimation { isConfirming = false }
                    onDecline()
                }
            )

            DSTextButton(
                "Cancel",
                role: .secondary,
                accessibilityIdentifier: "declineCancelButton",
                accessibilityLabel: "Cancel",
                action: { withAnimation { isConfirming = false } }
            )
        }
    }
}
