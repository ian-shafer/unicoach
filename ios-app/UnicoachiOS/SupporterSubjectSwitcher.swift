import SwiftUI

/// The working-student switcher (RFC 241): it renders the roster and REPORTS a
/// choice. It does not persist one — `SupporterHomeView` owns the store, so
/// this control has no reason to know a `UserDefaults` key exists.
///
/// An in-view disclosure rather than a stock `Menu`: a `Menu` renders its list
/// in a system window the snapshot rasterizer cannot reach, so the open state
/// would be the one shape of this control the gate could never photograph.
struct SupporterSubjectSwitcher: View {
    let roster: [LinkedStudent]
    let subject: LinkedStudent
    let onSelect: (LinkedStudent) -> Void

    @State private var isOpen: Bool

    /// [openOnAppear] is the `CollegeSearchView` `focusesSearchOnAppear`
    /// convention: the app always wants it closed, and the snapshot catalogue
    /// photographs it open.
    init(
        roster: [LinkedStudent],
        subject: LinkedStudent,
        openOnAppear: Bool = false,
        onSelect: @escaping (LinkedStudent) -> Void
    ) {
        self.roster = roster
        self.subject = subject
        self.onSelect = onSelect
        _isOpen = State(initialValue: openOnAppear)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DSControl.stackGap) {
            toggle
            if isOpen {
                options
            }
        }
    }

    /// One tap opens, one tap picks: two taps in total, which is the criterion.
    private var toggle: some View {
        Button {
            withAnimation { isOpen.toggle() }
        } label: {
            HStack(spacing: DSSpacing.md) {
                VStack(alignment: .leading, spacing: DSSpacing.xs) {
                    Text("Supporting")
                        .font(.dsCaption)
                        .foregroundStyle(Color.dsTextSecondary)
                    Text(subject.name)
                        .font(.dsLabel)
                        .foregroundStyle(Color.dsTextPrimary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: isOpen ? "chevron.up" : "chevron.down")
                    .font(.dsLabel)
                    .foregroundStyle(Color.dsTextSecondary)
            }
            .padding(.horizontal, DSControl.textInset)
            .padding(.vertical, DSSpacing.sm)
            .frame(maxWidth: .infinity, minHeight: DSControl.tapTarget)
            .dsOutlinedCard()
            .contentShape(RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("supporterSubjectSwitcher")
        .accessibilityLabel("Supporting \(subject.name). Choose a student")
        .accessibilityAddTraits(.isButton)
    }

    /// `OptionCard` is the design system's own pick-one-of-N control, and this
    /// list is one: selection in this app is carried by its radio and its
    /// border (DESIGN.md §5), never by an invented checkmark, and the box is
    /// the one `DSControlBoxModifier` owns.
    private var options: some View {
        VStack(spacing: DSControl.stackGap) {
            ForEach(roster) { student in
                OptionCard(
                    student.name,
                    isSelected: student.studentId == subject.studentId,
                    accessibilityIdentifier: "supporterSubjectOption"
                ) {
                    onSelect(student)
                    withAnimation { isOpen = false }
                }
            }
        }
        .accessibilityIdentifier("supporterSubjectOptions")
    }
}
