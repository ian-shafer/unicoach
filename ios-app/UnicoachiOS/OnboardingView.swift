import SwiftUI

struct OnboardingView: View {
    @StateObject private var viewModel: OnboardingViewModel

    /// The signed-in user's name, for the reference's "WELCOME, KENDALL"
    /// overline. Non-optional because `UserAuthState.onboarding` always carries
    /// a `PublicUser`, whose `name` is itself non-optional.
    private let userName: String

    init(
        studentClient: StudentClientProtocol,
        moneyProfileClient: MoneyProfileClientProtocol,
        userName: String,
        onComplete: @escaping () -> Void,
        year: Int = Calendar.current.component(.year, from: Date())
    ) {
        _viewModel = StateObject(wrappedValue: OnboardingViewModel(
            studentClient: studentClient,
            moneyProfileClient: moneyProfileClient,
            onComplete: onComplete,
            year: year
        ))
        self.userName = userName
    }

    /// Snapshot seam: host a pre-seeded view model, the `AddCollegeView`
    /// convention. Precision and the two optional answers are view-model state,
    /// so a scene that shows the full-date shape, or both answers given, can
    /// only be composed here.
    init(viewModel: OnboardingViewModel, userName: String) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.userName = userName
    }

    var body: some View {
        VStack(spacing: 0) {
            BrandTopBar()

            ScrollView {
                // `md`, not the `lg` screen margin: this form is deliberately
                // short (RFC 163), and a 24pt gap between every one of its
                // eight blocks spends ~40pt of viewport on air alone.
                VStack(alignment: .leading, spacing: DSSpacing.md) {
                    Text("Welcome, \(userName)")
                        .dsOverlineStyle()
                        .foregroundStyle(Color.dsTextPrimary)

                    // Account → profile → coaching. Onboarding is the middle
                    // step: the account exists, the coaching starts once this
                    // form submits.
                    StepIndicator(count: 3, current: 1)

                    graduationSection

                    optionalSection

                    if let errorResponse = viewModel.errorResponse {
                        FormErrorBanner(errorResponse.message)
                    }

                    LoadingButton(
                        "Create Profile",
                        isLoading: viewModel.isLoading,
                        role: .primary,
                        accessibilityIdentifier: "createProfileButton",
                        accessibilityLabel: "Create Profile",
                        progressAccessibilityIdentifier: "loadingIndicator",
                        action: { Task { await viewModel.submit() } }
                    )
                }
                .padding(DSSpacing.lg)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dsBackground)
    }

    // MARK: - The one required answer

    private var graduationSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text("When do you graduate?")
                .font(.dsDisplay)
                .foregroundStyle(Color.dsTextPrimary)

            precisionPicker

            DSCaptioned("Sets your application deadlines and the award years I price against.") {
                VStack(spacing: DSControl.stackGap) {
                    yearRow
                    monthAndDay
                }
            }
        }
    }

    private var precisionPicker: some View {
        SegmentedSelector(
            options: [
                (tag: OnboardingViewModel.Precision.year, title: "Year"),
                (tag: OnboardingViewModel.Precision.yearMonth, title: "Year & Month"),
                (tag: OnboardingViewModel.Precision.full, title: "Full date"),
            ],
            selection: $viewModel.precision,
            accessibilityIdentifier: "precisionPicker"
        )
    }

    /// The graduation year as ONE menu row. It used to be one `OptionCard` per
    /// year — 13 cards, 976pt, taller than the screen, for a single answer
    /// (RFC 163). `OptionCard` is still the signature control everywhere it is
    /// apt; a 13-value closed vocabulary is not one of those places.
    ///
    /// The binding is `$viewModel.year` directly: the clamp that keeps Feb 30
    /// from existing lives in the view model's `didSet`, so this view cannot
    /// route around it and no setter needs to be remembered.
    private var yearRow: some View {
        DSPickerRow(
            label: "Year",
            options: Array(viewModel.yearRange),
            selection: $viewModel.year,
            title: { String(format: "%04d", $0) },
            accessibilityIdentifier: "yearPicker",
            accessibilityLabel: "Graduation year"
        )
    }

    /// Month and day, shown only at the precisions that use them.
    ///
    /// A `switch` over the whole of `Precision`, not `!= .year` / `== .full`:
    /// those comparisons compile against a fourth case and silently show it the
    /// wrong rows, where this fails the build and asks what the new precision
    /// means.
    @ViewBuilder
    private var monthAndDay: some View {
        switch viewModel.precision {
        case .year:
            EmptyView()
        case .yearMonth:
            monthRow
        case .full:
            monthRow
            dayRow
        }
    }

    private var monthRow: some View {
        DSPickerRow(
            label: "Month",
            options: Array(OnboardingViewModel.monthRange),
            selection: $viewModel.month,
            title: monthName,
            accessibilityIdentifier: "monthPicker"
        )
    }

    private var dayRow: some View {
        DSPickerRow(
            label: "Day",
            options: Array(viewModel.dayRange),
            selection: $viewModel.day,
            title: { "\($0)" },
            accessibilityIdentifier: "dayPicker"
        )
    }


    // MARK: - The two optional answers

    /// Optional means UNANSWERED, not declined: there is no "prefer not to say"
    /// here, because a decline is permanent in this codebase and sign-up is the
    /// wrong moment to take one (RFC 163). Leaving a row alone costs nothing and
    /// the coach can ask again.
    private var optionalSection: some View {
        VStack(alignment: .leading, spacing: DSSpacing.md) {
            Text("Optional — you can add these any time")
                .dsOverlineStyle()
                .foregroundStyle(Color.dsTextPrimary)

            Text("Answer now or later; just ask me in chat. I'll work without them.")
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)

            DSCaptioned("Public colleges publish two prices. Knowing your state lets me show the one you'd "
                + "actually pay — a median $6,300 a year difference.") {
                stateRow
            }

            DSCaptioned("Net price varies a lot by income. With a bracket I can show your family's figure "
                + "instead of the all-family average — about $1,376 a year for a middle bracket.") {
                incomeRow
            }
        }
    }

    private var stateRow: some View {
        DSPickerRow(
            label: "State of residence",
            options: ResidencyStates.offered,
            selection: $viewModel.residencyState,
            title: \.name,
            accessibilityIdentifier: "statePicker",
            accessibilityLabel: "State of residence"
        )
    }

    private var incomeRow: some View {
        DSPickerRow(
            label: "Household income",
            options: IncomeBand.allCases,
            selection: $viewModel.incomeBand,
            // The server's own bracket copy, verbatim: the picker restates the
            // coach's words rather than minting a second wording.
            title: \.bracket,
            accessibilityIdentifier: "incomePicker",
            accessibilityLabel: "Household income bracket"
        )
    }


    /// The month's name in the user's own calendar and locale — a reading, not
    /// arithmetic, which is why this is `Calendar.current` where the view
    /// model's date maths is explicitly Gregorian.
    private func monthName(_ month: Int) -> String {
        let symbols = Calendar.current.monthSymbols
        guard OnboardingViewModel.monthRange.contains(month), month <= symbols.count else {
            return "\(month)"
        }
        return symbols[month - 1]
    }
}

/// The dollar range each income band names, in the words the coach says it
/// aloud — copied verbatim from the server's `IncomeBand.bracket`, which is the
/// one home for this copy (RFC 142). One wording for one range: a paraphrase
/// here would give the same fact two vocabularies, and the picker's whole
/// premise is that it restates what the coach already says.
///
/// It lives at the UI boundary rather than on `IncomeBand` itself, which is
/// a **wire** vocabulary in `Models.swift`. Display copy on a DTO is how a wire
/// key ends up read aloud to a family.
extension IncomeBand {
    var bracket: String {
        switch self {
        case .under30k: return "$0 to $30,000"
        case .k30To48k: return "$30,001 to $48,000"
        case .k48To75k: return "$48,001 to $75,000"
        case .k75To110k: return "$75,001 to $110,000"
        case .over110k: return "$110,000 or more"
        }
    }
}

private final class OnboardingPreviewStudentClient: StudentClientProtocol, @unchecked Sendable {
    func createStudent(request: CreateStudentRequest) async throws -> PublicStudent {
        PublicStudent(id: UUID(), expectedHighSchoolGraduationDate: request.expectedHighSchoolGraduationDate, version: 1, createdAt: Date(), updatedAt: Date())
    }
    func fetchProfile() async throws -> PublicStudent? { nil }
}

private final class OnboardingPreviewMoneyProfileClient: MoneyProfileClientProtocol, @unchecked Sendable {
    /// Onboarding never reads: the screen always starts blank (RFC 163 §5).
    func fetch() async throws -> PublicMoneyProfile? { nil }

    func update(_ request: UpdateMoneyProfileRequest) async throws -> PublicMoneyProfile {
        PublicMoneyProfile.answering(request, createdAt: Date(), updatedAt: Date())
    }
}

@MainActor private var onboardingPreview: some View {
    OnboardingView(
        studentClient: OnboardingPreviewStudentClient(),
        moneyProfileClient: OnboardingPreviewMoneyProfileClient(),
        userName: "Kendall",
        onComplete: {},
        year: 2028
    )
}

#Preview("onboarding - Light") {
    onboardingPreview
        .preferredColorScheme(.light)
}

#Preview("onboarding - Dark") {
    onboardingPreview
        .preferredColorScheme(.dark)
}
