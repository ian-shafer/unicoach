import SwiftUI

/// The inviter's door (RFC 232), spoken by BOTH sides of a link (RFC 241):
/// mint a shareable link and hand it to the system share sheet, so the act of
/// sharing IS the inviter's consent.
///
/// Extracted from `SettingsView` because the supporter home offers the same
/// action, and a second copy of the mint state machine is a second place for
/// "a failed mint must not leave the last token on screen" to be got right.
///
/// The control carries a [ClientRole] rather than a block of copy, because the
/// two sentences are the SAME fact said from two ends — the student asking for
/// help, the parent asking their child to join — and a call site handed two
/// free strings can pair a student's button with a parent's message.
struct SupporterInviteControl: View {
    let role: ClientRole
    let client: SupporterInviteClientProtocol

    /// What the control is showing. ONE value, so a stale link beside a fresh
    /// error — or a spinner beside either — is not representable. Three
    /// independent flags made those combinations legal and left their
    /// assignment order the only thing preventing them.
    @State private var state: InviteState = .idle

    /// The four states the invite control can be in (RFC 232).
    enum InviteState: Equatable {
        case idle
        case minting
        case minted(URL)
        case failed(String)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            LoadingButton(
                state == .minting ? "Preparing link…" : title,
                isLoading: state == .minting,
                role: .primary,
                accessibilityIdentifier: "inviteSupporterButton",
                accessibilityLabel: title,
                action: { Task { await mint() } }
            )

            switch state {
            case .idle, .minting:
                EmptyView()
            case let .minted(url):
                share(url)
            case let .failed(message):
                failure(message)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// What the button says. The student is asking an adult for support; the
    /// supporter is asking the student to join them.
    private var title: String {
        switch role {
        case .student: return "Invite a parent or supporter"
        case .supporter: return "Invite your student"
        }
    }

    /// The sentence that rides with the link. A parent forwarding the student's
    /// own words ("my college journey") to their child is the defect this
    /// exists to fix: the client knows which end it is, so it picks the right
    /// one.
    private func message(_ url: URL) -> String {
        switch role {
        case .student:
            return "Join me on unicoach to support my college journey: \(url.absoluteString)"
        case .supporter:
            return "I'm on unicoach to help with your college search. Join me here: \(url.absoluteString)"
        }
    }

    /// The state that FOLLOWS minting a link (RFC 232).
    ///
    /// It answers with a value rather than assigning three fields, so the
    /// caller has one assignment point and no intermediate combination exists.
    /// The previous link is gone the moment minting starts: a mint that fails
    /// must not leave the last token on screen, where it may already have been
    /// consumed.
    ///
    /// No relationship is sent. The server owns the product default, and the
    /// response reports the word it used — a literal here is the client
    /// asserting a vocabulary it does not own.
    ///
    /// A link the app cannot parse is a FAILURE, not silence: `URL(string:)`
    /// returning nil used to leave no share control, no message and no sign the
    /// tap had done anything at all.
    private func mintedState() async -> InviteState {
        do {
            let invite = try await client.createInvite(relationship: nil)
            guard let url = URL(string: invite.inviteUrl) else {
                return .failed(ErrorResponse.unexpected.message)
            }
            return .minted(url)
        } catch {
            return .failed((error as? ErrorResponse)?.message ?? ErrorResponse.unexpected.message)
        }
    }

    private func mint() async {
        state = .minting
        state = await mintedState()
    }

    /// The share sheet for a minted link.
    ///
    /// The URL travels as the ITEM so the share sheet still renders a tappable,
    /// previewable link, and the sentence rides with it as the message. A bare
    /// URL from an unknown sender is the exact context-free link a parent is
    /// told never to tap.
    private func share(_ url: URL) -> some View {
        ShareLink(
            item: url,
            subject: Text("Join me on unicoach"),
            message: Text(message(url))
        ) {
            Label("Share invite link", systemImage: "square.and.arrow.up")
        }
        .accessibilityIdentifier("shareInviteLink")
    }

    private func failure(_ message: String) -> some View {
        Text(message)
            .font(.dsBody)
            .foregroundStyle(Color.dsTextSecondary)
            .accessibilityIdentifier("inviteError")
    }
}

@MainActor private var supporterInviteControlPreview: some View {
    VStack(alignment: .leading, spacing: DSSpacing.lg) {
        SupporterInviteControl(role: .student, client: PreviewSupporterInviteClient())
        SupporterInviteControl(role: .supporter, client: PreviewSupporterInviteClient())
    }
    .padding(DSSpacing.lg)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(Color.dsBackground)
}

#Preview("supporterInviteControl - Light") {
    supporterInviteControlPreview
        .preferredColorScheme(.light)
}

#Preview("supporterInviteControl - Dark") {
    supporterInviteControlPreview
        .preferredColorScheme(.dark)
}
