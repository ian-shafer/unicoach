import SwiftUI
import UIKit
import XCTest
@testable import UnicoachiOS

/// The snapshot gate (RFC 122). `@MainActor` is required: the whole harness
/// hosts SwiftUI in a real `UIWindow`.
///
/// What has TEETH here, in order of authority:
///   1. every scene CONSTRUCTS and hosts without trapping -- the mechanical
///      reason the harness is committed rather than generated;
///   2. every render is non-blank;
///   3. nothing draws into the bleed margin (see the bleed experiment below);
///   4. the corpus files exist on disk at the end.
/// Everything past that is a human looking at the PNGs.
@MainActor
final class SnapshotTests: XCTestCase {
    /// The walk: render every scene light and dark, write the corpus, assert on
    /// each image, and report baseline drift when a baseline was given.
    func testCatalogueRendersNonBlankAndWritesCorpus() throws {
        let directory = SnapshotOutput.directory
        let baseline = SnapshotOutput.baselineDirectory
        var written: [String] = []
        var moved: [SnapshotBaseline.Result] = []

        for scene in SnapshotCatalogue.scenes {
            for dark in [false, true] {
                // One autorelease pool per capture. A retina capture and its
                // PNG encoding are tens of megabytes of autoreleased
                // CoreGraphics memory, and without draining them per iteration
                // the test process is killed part way through the walk (it was,
                // twice, during the RFC 122 build) with no message beyond
                // xcodebuild's "Restarting after unexpected exit".
                try autoreleasepool {
                let mode = dark ? "dark" : "light"
                // Constructed fresh per mode: a scene closure seeds view models,
                // and re-using one across two renders would show the second the
                // first's settled state.
                let view = SnapshotAsync.resolve(scene.content)
                let image = SnapshotHost.render(
                    name: scene.name,
                    dark: dark,
                    size: scene.size,
                    settle: scene.settle,
                    into: directory
                ) { view }

                let raster = try XCTUnwrap(
                    SnapshotRaster(image),
                    "\(scene.name)-\(mode): the capture produced no decodable bitmap"
                )
                // A UNIFORM image is the exact symptom of the ImageRenderer trap
                // and of a view that never laid out.
                XCTAssertGreaterThan(
                    raster.distinctColourCount(), 1,
                    "\(scene.name)-\(mode) is blank: one distinct pixel value in the whole capture"
                )
                written.append("\(scene.name)-\(mode).png")

                if let baseline,
                   let result = SnapshotBaseline.compare(
                       image: image,
                       name: "\(scene.name)-\(mode)",
                       baseline: baseline,
                       writingDiffsTo: directory
                   ), result.fraction > SnapshotBaseline.threshold {
                    moved.append(result)
                }
                }
            }
        }

        // 4. The corpus exists on disk. `xcodebuild test` exits 0 when zero
        // tests run, so the file check is what makes an empty corpus loud.
        for name in written {
            let url = directory.appendingPathComponent(name)
            XCTAssertTrue(
                FileManager.default.fileExists(atPath: url.path),
                "corpus file missing: \(url.path)"
            )
        }
        print("SNAPSHOT: wrote \(written.count) images to \(directory.path)")

        if SnapshotOutput.baselineDirectory != nil {
            // Reports; never fails. The question is "which screens did my
            // change move", which in review is worth more than a pass/fail.
            if moved.isEmpty {
                print("SNAPSHOT BASELINE: no scene moved beyond \(SnapshotBaseline.threshold * 100)% of pixels")
            } else {
                print("SNAPSHOT BASELINE: \(moved.count) scene(s) moved:")
                for result in moved.sorted(by: { $0.fraction > $1.fraction }) {
                    print(String(format: "  %@  %.3f%% of pixels (diff overlay: %@.diff.png)",
                                 result.name, result.fraction * 100, result.name))
                }
            }
        }
    }

    /// THE BLEED CANVAS (assertion 3), and the experiment's positive half.
    ///
    /// Each scene is hosted in a container of exactly the device width inside a
    /// wider magenta window; any non-magenta pixel in the margin is content
    /// drawing outside its container -- the defect class that recurred twice
    /// (a table wider than its bubble, a column running off the trailing edge).
    func testNothingDrawsIntoTheBleedMargin() throws {
        for scene in SnapshotCatalogue.scenes {
            for dark in [false, true] {
                autoreleasepool {
                let mode = dark ? "dark" : "light"
                let view = SnapshotAsync.resolve(scene.content)
                let fraction = SnapshotHost.bleedFraction(
                    dark: dark,
                    contentWidth: scene.size.width,
                    height: scene.size.height,
                    settle: scene.settle
                ) { view }
                XCTAssertLessThanOrEqual(
                    fraction, Self.bleedTolerance,
                    "\(scene.name)-\(mode) drew into the bleed margin "
                        + "(\(String(format: "%.3f", fraction * 100))% of margin pixels are not the backdrop)"
                )
                }
            }
        }
    }

    /// THE NEGATIVE CONTROL. A detector that can never fire cannot pass for a
    /// working one, so a deliberately overflowing view is asserted to FAIL the
    /// same check. If this ever goes green the bleed assertion above is
    /// meaningless and must be deleted rather than trusted.
    func testBleedDetectorFiresOnAnOverflowingView() {
        let fraction = SnapshotHost.bleedFraction(dark: false) {
            AnyView(
                // 900pt of content in a 402pt container, unclipped: SwiftUI
                // does not clip by default, so this must reach the margin.
                HStack(spacing: 0) {
                    Color.blue.frame(width: 900, height: 200)
                }
                .frame(width: SnapshotOutput.deviceSize.width, alignment: .leading)
            )
        }
        print(String(format: "SNAPSHOT BLEED negative control: %.2f%% of margin pixels overdrawn "
                     + "(tolerance %.2f%%)", fraction * 100, Self.bleedTolerance * 100))
        XCTAssertGreaterThan(
            fraction, Self.bleedTolerance,
            "the bleed detector did not fire on a view that overflows its container by 500pt; "
                + "the detector is broken and the bleed assertion is worthless"
        )
    }

    /// Antialiasing at the container's own edge, and legitimate shadows, put a
    /// handful of pixels into the margin on scenes that are otherwise correct.
    /// The tolerance is a fraction of the margin, not zero, for that reason.
    private static let bleedTolerance = 0.002
}
