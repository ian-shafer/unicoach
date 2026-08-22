import SwiftUI
import XCTest
@testable import UnicoachiOS

class AppearancePreferenceTests: XCTestCase {
    func testSystemFollowsTheDevice() {
        // nil, not a third scheme: the app must not freeze the device's current
        // appearance into the preference.
        XCTAssertNil(AppearancePreference.system.colorScheme)
    }

    func testLightAndDarkMapToTheirSchemes() {
        XCTAssertEqual(AppearancePreference.light.colorScheme, .light)
        XCTAssertEqual(AppearancePreference.dark.colorScheme, .dark)
    }

    func testRawValuesRoundTrip() {
        // @AppStorage persists the raw value, so a renamed case would silently
        // reset every existing user to the default.
        for preference in AppearancePreference.allCases {
            XCTAssertEqual(AppearancePreference(rawValue: preference.rawValue), preference)
        }
    }

    func testAllCasesAreOfferedInOrder() {
        XCTAssertEqual(AppearancePreference.allCases, [.system, .light, .dark])
    }

    func testEveryCaseHasATitle() {
        for preference in AppearancePreference.allCases {
            XCTAssertFalse(preference.title.isEmpty)
        }
    }

    func testStoredDefaultIsSystem() {
        // The scene reads this key with a `.system` default; an unset store must
        // therefore decode to nothing rather than to some other case.
        let defaults = UserDefaults(suiteName: #function)!
        defaults.removePersistentDomain(forName: #function)

        XCTAssertNil(defaults.string(forKey: AppearancePreference.storageKey))
    }
}
