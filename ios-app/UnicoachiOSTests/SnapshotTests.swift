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
        // The URLs `render` reported writing — not names re-derived here, which
        // is how a filename convention comes to have two owners.
        var written: [URL] = []
        var compared: [SnapshotBaseline.Comparison] = []
        // Scenes with no baseline (new since it was captured) and scenes whose
        // baseline is on disk but unusable. Kept APART: the first is ordinary
        // and the second is infrastructure failure that would otherwise shrink
        // the compared-scene count the report prints as its headline evidence.
        var newScenes: [String] = []
        var unreadable: [(url: URL, error: Error?)] = []

        for scene in SnapshotCatalogue.scenes {
            for dark in [false, true] {
                // One autorelease pool per capture. A retina capture and its
                // PNG encoding are tens of megabytes of autoreleased
                // CoreGraphics memory, and without draining them per iteration
                // the test process is killed part way through the walk (it was,
                // twice, during the RFC 122 build) with no message beyond
                // xcodebuild's "Restarting after unexpected exit".
                try autoreleasepool {
                let id = SnapshotSceneID(sceneName: scene.name, dark: dark)
                // Constructed fresh per mode: a scene closure seeds view models,
                // and re-using one across two renders would show the second the
                // first's settled state.
                let view = SnapshotAsync.resolve(scene.content)
                // `render` writes the PNG and returns the URL it used, so the
                // read-back below cannot address a different file; it throws if
                // the write failed, at the line that failed.
                let (image, writtenURL) = try SnapshotHost.render(
                    id: id,
                    size: scene.size,
                    settle: scene.settle,
                    into: directory
                ) { view }

                let raster = try XCTUnwrap(
                    SnapshotRaster(image),
                    "[\(id.corpusName)]: the capture produced no decodable bitmap"
                )
                // A UNIFORM image is the exact symptom of the ImageRenderer trap
                // and of a view that never laid out.
                XCTAssertGreaterThan(
                    raster.distinctColourCount(), 1,
                    "[\(id.corpusName)] is blank: one distinct pixel value in the whole capture"
                )
                written.append(writtenURL)

                // ONLY under -b. Re-decoding costs a second full-resolution
                // image inside the one loop whose peak memory got the test
                // process SIGKILLed twice during the RFC 122 build, so an
                // ordinary capture run must not pay for a comparison it never
                // makes.
                if let baseline {
                    // Compare PNG TO PNG, not in-memory-capture to PNG. The
                    // capture's colour space and the one the PNG codec
                    // round-trips through are not the same, and that asymmetry
                    // alone puts a ±1 floor under EVERY scene's max delta —
                    // measured: 46 of 46 scenes reported max delta 1 comparing
                    // a corpus against a second capture of the same tree, while
                    // the PNG files themselves were byte-identical for 45 of
                    // them. That floor would drown the sub-tolerance signal
                    // this report exists to carry (RFC 130). Re-decoding the
                    // file just written is what makes both sides of the
                    // comparison travel the same path.
                    //
                    // NOT `?? image`: falling back to the in-memory capture
                    // would silently reinstate exactly that floor and print it
                    // on all 46 rows as though it were data, with the run still
                    // green. This loop wrote the file a moment ago, so failing
                    // to read it back is a defect, not a condition — and the
                    // read is `try`, not `try?`, so the NSError (domain, code
                    // and path) travels up rather than being flattened into a
                    // nil this message could not explain.
                    let writtenData = try Data(contentsOf: writtenURL)
                    let decodedPNG = try XCTUnwrap(
                        UIImage(data: writtenData),
                        "[\(id.corpusName)]: the PNG this loop just wrote to [\(writtenURL.path)] "
                            + "was read back but did not decode as an image. The baseline comparison must be "
                            + "PNG-to-PNG (RFC 130); comparing the in-memory capture instead "
                            + "would put a ±1/255 codec floor under every scene and report it "
                            + "as drift."
                    )
                    switch try SnapshotBaseline.compare(
                        image: decodedPNG,
                        id: id,
                        baseline: baseline,
                        writingDiffsTo: directory
                    ) {
                    case let .compared(comparison):
                        // EVERY compared scene is kept, not only the moved ones:
                        // the sub-tolerance figures of a scene that did not move
                        // are the point of the report (RFC 130).
                        compared.append(comparison)
                    case .missing:
                        newScenes.append(id.corpusName)
                    case let .unreadable(url, error):
                        unreadable.append((url, error))
                    case let .captureUndecodable(undecodable):
                        // NOT collected with the unreadable baselines: the file
                        // at the baseline path is not the thing that failed,
                        // and a message naming it would send the operator to
                        // re-capture a baseline that is fine.
                        XCTFail("[\(undecodable.corpusName)]: the PNG THIS RUN just captured and "
                            + "wrote decoded as an image but produced no bitmap, so the baseline "
                            + "comparison could not be performed. The baseline is not implicated; "
                            + "this is a capture-side failure.")
                    }
                }
                }
            }
        }

        // 4. The corpus exists on disk. `xcodebuild test` exits 0 when zero
        // tests run, so an EMPTY corpus has to be asserted DIRECTLY — the
        // per-file loop below iterates zero times and would pass in silence.
        // That loop is now the weaker of the two checks: `render` throws on a
        // failed write, so it only catches a file removed since.
        XCTAssertFalse(
            written.isEmpty,
            "no scene rendered: the catalogue is empty, or the walk never ran"
        )
        for url in written {
            XCTAssertTrue(
                FileManager.default.fileExists(atPath: url.path),
                "corpus file missing: [\(url.path)]"
            )
        }
        print("SNAPSHOT: wrote \(written.count) images to \(directory.path)")

        if SnapshotOutput.baselineDirectory != nil {
            reportUnusableBaselines(unreadable, newScenes: newScenes)
            reportBaseline(compared)
        }
    }

    /// The two ways a scene can be absent from the comparison, told apart.
    ///
    /// A new scene is ordinary and is printed. An UNREADABLE baseline fails the
    /// test: the drift verdict never fails (RFC 122), but this is not drift —
    /// it is a baseline the operator believes was compared and was not, and it
    /// silently shrinks the compared count the next line prints as evidence.
    private func reportUnusableBaselines(
        _ unreadable: [(url: URL, error: Error?)], newScenes: [String]
    ) {
        if !newScenes.isEmpty {
            print("SNAPSHOT BASELINE: \(newScenes.count) scene(s) have no baseline and were not "
                + "compared (new since the baseline was captured): "
                + newScenes.sorted().map { "[\($0)]" }.joined(separator: " "))
        }
        for entry in unreadable {
            let reason = entry.error.map { "\($0)" } ?? "the file exists but did not decode into a bitmap"
            XCTFail("the baseline at [\(entry.url.path)] could not be read: \(reason). "
                + "This scene is NOT in the compared count below; a corrupt baseline must not "
                + "quietly reduce the evidence the report is trusted for.")
        }
    }

    /// The baseline verdict (RFC 122: reports, never fails — "which screens did
    /// my change move" is worth more in review than a pass/fail).
    ///
    /// Four separable jobs, each named: the rule that was applied, the movers,
    /// the caveat on how the figures were measured, and the evidence table.
    private func reportBaseline(_ compared: [SnapshotBaseline.Comparison]) {
        printBaselineRule()
        printMovedScenes(compared.filter(\.moved), outOf: compared.count)
        printDownscaleCaveat()
        printEvidenceTable(compared)
    }

    /// The rule names BOTH halves it applied, the per-channel tolerance and the
    /// moved fraction, because printing only the fraction let the line be read
    /// as a claim of byte equality — which the corpus does not make and cannot
    /// keep (RFC 130).
    private func printBaselineRule() {
        print("SNAPSHOT BASELINE RULE: a pixel MOVED when its R, G or B differs by more than "
            + "\(Int(SnapshotBaseline.epsilon))/255; a scene MOVED when more than "
            + SnapshotBaseline.threshold.percentText() + " of its pixels did. "
            + "Byte equality is NOT the contract — never diff the corpus with md5/cmp (RFC 130).")
    }

    /// The movers, loudest first, each naming the overlay to open.
    private func printMovedScenes(_ moved: [SnapshotBaseline.Comparison], outOf compared: Int) {
        print("SNAPSHOT BASELINE: \(compared) scene(s) compared, \(moved.count) moved under that rule.")
        for comparison in moved.sorted(by: { $0.movedFraction > $1.movedFraction }) {
            print(comparison.movedLine)
        }
    }

    /// THE CAVEAT, stated where the figures are read: every number in the table
    /// is measured on `SnapshotRaster`'s analysis copy (longest side capped at
    /// 1024 — a memory constraint inherited from RFC 122), not on the
    /// full-resolution PNG a human opens. The divisor is `ceil(longest / 1024)`,
    /// which across this corpus takes THREE values, not two:
    ///
    ///   * 1 — no downscale at all, so these figures ARE full resolution:
    ///     `coaching-budget-strip` (402x400pt = 804x800px) and
    ///     `usage-meter-strip` (402x480pt = 804x960px), both under 1024 on
    ///     their longest side;
    ///   * 2 — every device-sized scene (402x874pt = 804x1748px) and the other
    ///     short strips;
    ///   * 3 — the two TALL scenes: `conversation-markdown-worstcase`
    ///     (402x1400pt = 804x2800px) and `design-system-catalogue`
    ///     (402x1500pt = 804x3000px).
    ///
    /// So a pixel COUNT is not comparable between scenes, and how much a stray
    /// subpixel can average away depends on which scene printed the row; deltas
    /// of 2 do survive it. The repeatability assertion in
    /// `SnapshotRepeatabilityTests` deliberately does not downscale.
    private func printDownscaleCaveat() {
        print("SNAPSHOT BASELINE: figures are measured on the 1024-capped analysis downscale, "
            + "not on the full-resolution PNG — per-scene pixel counts are therefore NOT "
            + "comparable between scenes of different heights.")
    }

    /// Every scene, moved or not, with what sits UNDER the tolerance.
    ///
    /// UNFILTERED ON PURPOSE. The report's second job is to make a SYSTEMATIC
    /// sub-tolerance shift recognisable, and the only shape that gives it away
    /// is "most of the corpus is suddenly at delta 4" — a table filtered to the
    /// interesting rows shows a handful of scenes at delta 4 and reads as
    /// noise. The rows that say 0 are the control group that makes the others
    /// mean something.
    private func printEvidenceTable(_ compared: [SnapshotBaseline.Comparison]) {
        print("SNAPSHOT BASELINE: per-scene detail (max per-channel delta / pixels differing at all).")
        for comparison in compared.sorted(by: SnapshotBaseline.Comparison.byLoudestDrift) {
            print(comparison.reportLine)
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
                try autoreleasepool {
                let mode = dark ? "dark" : "light"
                let view = SnapshotAsync.resolve(scene.content)
                // UNWRAPPED, never defaulted: nil is "the margin was not
                // measured", and defaulting it to 0 would report an unchecked
                // scene as a clean one — the same "a number that looks like a
                // measurement and is not" defect RFC 130 exists to end.
                let fraction = try XCTUnwrap(
                    SnapshotHost.bleedFraction(
                        dark: dark,
                        contentWidth: scene.size.width,
                        height: scene.size.height,
                        settle: scene.settle
                    ) { view },
                    "\(scene.name)-\(mode): the bleed canvas could not be measured (it did not "
                        + "decode, or its margin was too thin to scan), so this scene was NEVER "
                        + "CHECKED for overdraw"
                )
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
    func testBleedDetectorFiresOnAnOverflowingView() throws {
        let fraction = try XCTUnwrap(
            SnapshotHost.bleedFraction(dark: false) {
                AnyView(
                    // 900pt of content in a 402pt container, unclipped: SwiftUI
                    // does not clip by default, so this must reach the margin.
                    HStack(spacing: 0) {
                        Color.blue.frame(width: 900, height: 200)
                    }
                    .frame(width: SnapshotOutput.deviceSize.width, alignment: .leading)
                )
            },
            "the negative control's own canvas could not be measured, so this test proves nothing "
                + "about the detector either way"
        )
        print(String(format: "SNAPSHOT BLEED negative control: %.2f%% of margin pixels overdrawn "
                     + "(tolerance %.2f%%)", fraction * 100, Self.bleedTolerance * 100))
        XCTAssertGreaterThan(
            fraction, Self.bleedTolerance,
            "the bleed detector did not fire on a view that overflows its container by 500pt; "
                + "the detector is broken and the bleed assertion is worthless"
        )
    }

    /// THE SUB-TOLERANCE FIGURES, on synthesized rasters with a KNOWN delta —
    /// the case the whole of item 1 exists for. Every difference here is BELOW
    /// `epsilon`, so the moved fraction is 0 and the verdict is "nothing
    /// moved"; the maximum delta and the differing-pixel count are the only
    /// signal that anything happened at all. A token edited from `#6E6E6E` to
    /// `#6A6A6A` looks exactly like this.
    ///
    /// The deltas are DERIVED from `SnapshotBaseline.epsilon`, never typed as
    /// literals: a hand-written `4` stops meaning "under the tolerance" the
    /// moment the tolerance moves, and the test would keep passing while
    /// asserting the opposite of its own name.
    func testComparatorReportsSubToleranceDrift() throws {
        let base = Self.flatRaster(rgb: (Self.baseLevel, Self.baseLevel, Self.baseLevel))
        XCTAssertLessThan(Self.underDelta, Int(SnapshotBaseline.epsilon),
                          "this test's 'under the tolerance' delta must be under the tolerance")
        var bytes = base.bytes
        // 25 pixels moved on the red channel by less than the tolerance.
        for index in 0 ..< 25 { bytes[index * 4] = Self.baseLevel - UInt8(Self.underDelta) }
        let drifted = SnapshotRaster(width: base.width, height: base.height, bytes: bytes)

        let comparison = SnapshotBaseline.compare(baseline: base, capture: drifted, id: Self.syntheticID)
        let measured = try XCTUnwrap(comparison.measured, "same-size rasters ARE comparable")
        XCTAssertEqual(measured.drift.maxDelta, Self.underDelta,
                       "the maximum per-channel delta is the largest single move")
        XCTAssertEqual(measured.drift.differingPixels, 25, "every pixel that differs AT ALL is counted")
        XCTAssertEqual(measured.drift.pixelCount, base.width * base.height)
        XCTAssertEqual(measured.movedFraction, 0,
                       "a delta under the tolerance does not clear it, so nothing MOVED")
        XCTAssertTrue(measured.mask.isEmpty, "and nothing is painted into the diff overlay")
    }

    /// The same figures when part of the drift DOES clear the tolerance: the
    /// moved fraction counts only the pixels past `epsilon`, while the two new
    /// figures count everything, so the report cannot be read as either one
    /// alone. Alpha is excluded from both, exactly as in the moved rule.
    func testComparatorSeparatesMovedPixelsFromPixelsThatMerelyDiffer() throws {
        let base = Self.flatRaster(rgb: (Self.baseLevel, Self.baseLevel, Self.baseLevel))
        XCTAssertGreaterThan(Self.overDelta, Int(SnapshotBaseline.epsilon),
                             "this test's 'over the tolerance' delta must be over the tolerance")
        var bytes = base.bytes
        for index in 0 ..< 25 { bytes[index * 4] = Self.baseLevel - UInt8(Self.underDelta) }   // under
        for index in 25 ..< 35 { bytes[index * 4 + 2] = Self.baseLevel + UInt8(Self.overDelta) } // over
        for index in 35 ..< 40 { bytes[index * 4 + 3] = 0 }                                     // alpha, ignored
        let drifted = SnapshotRaster(width: base.width, height: base.height, bytes: bytes)

        let comparison = SnapshotBaseline.compare(baseline: base, capture: drifted, id: Self.syntheticID)
        let measured = try XCTUnwrap(comparison.measured, "same-size rasters ARE comparable")
        XCTAssertEqual(measured.drift.maxDelta, Self.overDelta)
        XCTAssertEqual(measured.drift.differingPixels, 35,
                       "the 5 alpha-only pixels are not a colour difference")
        XCTAssertEqual(measured.mask.moved.filter { $0 }.count, 10,
                       "only the 10 over-tolerance pixels are overlaid")
        XCTAssertEqual(measured.mask.size, base.size, "the mask indexes the rasters that were compared")
        XCTAssertEqual(measured.movedFraction, 10.0 / Double(base.width * base.height), accuracy: 1e-12)
    }

    /// A RESIZED SCENE MEASURES NOTHING, and the type says so: `.notComparable`
    /// carries the two disagreeing sizes and has no figures and no mask to
    /// invent. Filling them in at their maxima (the shape this carried two
    /// revisions ago) printed `max delta 255/255` in the evidence table beside
    /// real measurements, where it reads as a catastrophic colour change rather
    /// than as "not measured"; the revision after that made the figures
    /// optional, which left the resized case holding an EMPTY mask that the
    /// overlay writer would still accept.
    func testComparatorReportsNoDriftFiguresForAResizedScene() throws {
        let base = Self.flatRaster(rgb: (Self.baseLevel, Self.baseLevel, Self.baseLevel))
        let taller = Self.flatRaster(rgb: (Self.baseLevel, Self.baseLevel, Self.baseLevel), size: 32)

        let comparison = SnapshotBaseline.compare(baseline: base, capture: taller, id: Self.syntheticID)
        XCTAssertEqual(comparison.movedFraction, 1, "a resized scene is still a total move")
        XCTAssertTrue(comparison.moved)
        XCTAssertNil(comparison.measured, "nothing was measured per pixel, so nothing may be reported")
        guard case let .notComparable(baselineSize, captureSize) = comparison.outcome else {
            return XCTFail("a size mismatch must be the notComparable case: \(comparison.outcome)")
        }
        XCTAssertEqual(baselineSize, base.size)
        XCTAssertEqual(captureSize, taller.size)
        XCTAssertTrue(
            comparison.reportLine.contains("NOT MEASURED"),
            "the evidence row must read as not-measured, not as a measurement: \(comparison.reportLine)"
        )
        XCTAssertTrue(
            comparison.reportLine.contains("64x64 -> 32x32"),
            "the row must say WHICH sizes disagreed — the code has both and used to discard them: "
                + comparison.reportLine
        )
        XCTAssertFalse(
            comparison.reportLine.contains("255/255"),
            "an invented maximum in the evidence table is indistinguishable from a real one"
        )
    }

    /// THE PIN FOR THE OVERLAY THAT MUST NOT BE WRITTEN, at the image boundary
    /// where the defect lived.
    ///
    /// A resized scene moves totally, so it reaches the "should I write a diff
    /// overlay" decision. It has no mask. Written the old way — a `[Bool]`
    /// mask beside loose `width`/`height` — the overlay writer built a
    /// `CGContext` from the BASELINE's dimensions over a byte buffer decoded
    /// from the CAPTURE, which is a read past the end of that buffer whenever
    /// the baseline is the taller of the two, and a red-free `.diff.png` for
    /// the scene that changed the most whenever it is the shorter. The baseline
    /// here is deliberately the TALLER one, i.e. the out-of-bounds direction.
    ///
    /// It cannot happen now because the overlay is only reachable from
    /// `.measured`, which by construction has both rasters at one size. This
    /// test is the executable statement of that: no overlay file, no overlay
    /// named in the operator's line, and the process survives the call.
    func testAResizedSceneWritesNoDiffOverlay() throws {
        let (baselineDirectory, outputDirectory) = try Self.makeComparisonDirectories()
        let id = SnapshotSceneID(sceneName: "resized-scene", dark: false)
        let baselineImage = Self.solidImage(width: 40, height: 120)
        try XCTUnwrap(baselineImage.pngData()).write(to: id.url(in: baselineDirectory))

        let lookup = try SnapshotBaseline.compare(
            image: Self.solidImage(width: 40, height: 60),
            id: id,
            baseline: baselineDirectory,
            writingDiffsTo: outputDirectory
        )

        guard case let .compared(comparison) = lookup else {
            return XCTFail("a baseline that exists and decodes must produce a comparison: \(lookup)")
        }
        XCTAssertTrue(comparison.moved, "a resized scene is a total move")
        XCTAssertNil(comparison.measured, "and nothing about it was measured per pixel")
        XCTAssertNil(comparison.overlayURL, "so no overlay was written")
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: id.diffURL(in: outputDirectory).path),
            "a resized scene must leave no .diff.png: the only overlay it could produce is painted "
                + "from the wrong buffer and carries no red at all"
        )
        XCTAssertFalse(
            comparison.movedLine.contains("diff overlay"),
            "and the operator must not be sent to a file that was never written: \(comparison.movedLine)"
        )
    }

    /// The same boundary when the rasters DO agree: an overlay is written, and
    /// the comparison names the file that was written rather than composing a
    /// second spelling of `<scene>-<mode>.diff.png`.
    func testAMovedSceneWritesTheOverlayItsSceneIDNames() throws {
        let (baselineDirectory, outputDirectory) = try Self.makeComparisonDirectories()
        let id = SnapshotSceneID(sceneName: "moved-scene", dark: true)
        try XCTUnwrap(Self.solidImage(width: 40, height: 60, white: 0).pngData())
            .write(to: id.url(in: baselineDirectory))

        let lookup = try SnapshotBaseline.compare(
            image: Self.solidImage(width: 40, height: 60, white: 1),
            id: id,
            baseline: baselineDirectory,
            writingDiffsTo: outputDirectory
        )

        guard case let .compared(comparison) = lookup else {
            return XCTFail("a baseline that exists and decodes must produce a comparison: \(lookup)")
        }
        XCTAssertTrue(comparison.moved, "black against white is every pixel past the tolerance")
        XCTAssertEqual(comparison.overlayURL, id.diffURL(in: outputDirectory),
                       "the overlay must be the URL SnapshotSceneID names, not one composed here")
        XCTAssertTrue(FileManager.default.fileExists(atPath: id.diffURL(in: outputDirectory).path))
        XCTAssertTrue(comparison.movedLine.contains("[moved-scene-dark.diff.png]"),
                      "the operator's line names the file on disk: \(comparison.movedLine)")
    }

    /// A NEW SCENE AND A CORRUPT BASELINE ARE DIFFERENT ANSWERS. They used to
    /// be one `nil` and the walk dropped both in silence, which quietly shrinks
    /// the compared-scene count the report prints as its headline evidence.
    func testAMissingBaselineAndAnUnreadableOneAreToldApart() throws {
        let (baselineDirectory, outputDirectory) = try Self.makeComparisonDirectories()
        let capture = Self.solidImage(width: 40, height: 60)

        let absent = SnapshotSceneID(sceneName: "brand-new-scene", dark: false)
        switch try SnapshotBaseline.compare(
            image: capture, id: absent, baseline: baselineDirectory, writingDiffsTo: outputDirectory
        ) {
        case let .missing(url): XCTAssertEqual(url, absent.url(in: baselineDirectory))
        case let other: XCTFail("a scene with no baseline file is missing, not \(other)")
        }

        let corrupt = SnapshotSceneID(sceneName: "corrupt-baseline", dark: false)
        try Data("this is not a PNG".utf8).write(to: corrupt.url(in: baselineDirectory))
        switch try SnapshotBaseline.compare(
            image: capture, id: corrupt, baseline: baselineDirectory, writingDiffsTo: outputDirectory
        ) {
        case let .unreadable(url, _): XCTAssertEqual(url, corrupt.url(in: baselineDirectory))
        case let other: XCTFail("a baseline that exists but does not decode is unreadable, not \(other)")
        }
    }

    /// A temp baseline directory and a temp output directory, both removed at
    /// teardown.
    private static func makeComparisonDirectories() throws -> (baseline: URL, output: URL) {
        let root = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("snapshot-compare-\(UUID().uuidString)", isDirectory: true)
        let baseline = root.appendingPathComponent("baseline", isDirectory: true)
        let output = root.appendingPathComponent("output", isDirectory: true)
        for directory in [baseline, output] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
        return (baseline, output)
    }

    /// An opaque grey image at scale 1, so its pixel dimensions are exactly the
    /// points asked for and the comparator sees the size this test intends.
    private static func solidImage(width: Int, height: Int, white: CGFloat = 0.5) -> UIImage {
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = 1
        format.opaque = true
        let size = CGSize(width: width, height: height)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(white: white, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
    }

    /// The corpus entry the synthesized-raster tests compare under. The rule is
    /// about bytes, not about which scene they came from, but `compare` takes
    /// an ID so that no production path can name a comparison freehand.
    private static let syntheticID = SnapshotSceneID(sceneName: "synthetic", dark: false)

    /// The mid-grey the synthesized rasters sit at, far enough from both ends
    /// that `baseLevel ± delta` cannot clip.
    private static let baseLevel: UInt8 = 110
    /// A delta STRICTLY UNDER the tolerance, and one comfortably over it, both
    /// derived from `SnapshotBaseline.epsilon` (see the first test above). The
    /// over-delta is clamped so it cannot run past 255 if epsilon is widened.
    private static var underDelta: Int { max(1, Int(SnapshotBaseline.epsilon) / 2) }
    private static var overDelta: Int {
        min(255 - Int(baseLevel), Int(SnapshotBaseline.epsilon) * 5)
    }

    /// An opaque flat colour, 64x64: small enough that `SnapshotRaster`'s
    /// analysis downscale never engages, so the bytes the assertions reason
    /// about are the bytes written here.
    private static func flatRaster(rgb: (UInt8, UInt8, UInt8), size: Int = 64) -> SnapshotRaster {
        var bytes = [UInt8](repeating: 255, count: size * size * 4)
        for index in 0 ..< (size * size) {
            bytes[index * 4] = rgb.0
            bytes[index * 4 + 1] = rgb.1
            bytes[index * 4 + 2] = rgb.2
        }
        return SnapshotRaster(width: size, height: size, bytes: bytes)
    }

    /// Antialiasing at the container's own edge, and legitimate shadows, put a
    /// handful of pixels into the margin on scenes that are otherwise correct.
    /// The tolerance is a fraction of the margin, not zero, for that reason.
    private static let bleedTolerance = 0.002
}


// MARK: - The corpus's reproducibility contract (RFC 130)

/// THE CONTRACT, EXECUTED — in a class of its OWN, deliberately.
///
/// `bin/snapshot-ios` runs `-only-testing:UnicoachiOSTests/SnapshotTests`,
/// the class that PRODUCES the corpus. An assertion about the platform's
/// wobble living in that class would mean a future OS widening the wobble past
/// `epsilon` kills corpus capture outright — and does it under a message about
/// scene registration, which is what `bin/snapshot-ios` prints when zero PNGs
/// appear. That contradicts this RFC's own "no failing build". Here it is a
/// failed assertion in `bin/test-ios` (which runs every class) and capture
/// still works.
@MainActor
final class SnapshotRepeatabilityTests: XCTestCase {
    /// One scene, captured TWICE IN THIS PROCESS, must be equal *under the
    /// epsilon rule* — and that is deliberately not the same statement as "the
    /// two PNGs are identical". A byte comparison is the exact claim RFC 130
    /// retires: it is what produced the RFC 128 false report of a
    /// non-deterministic corpus.
    ///
    /// WHY THIS SCENE. `ConversationListView` is the only VIEW in the corpus
    /// with a visible toolbar ITEM, so its four captures are the only ones
    /// whose compose-button glass can wobble at all — light included, which is
    /// why this comment does not say "dark is the only scene that wobbles". The
    /// two DARK captures are where the quantisation actually lands a step apart
    /// (±2/255 on blue, ~0.4% of the frame); the light pair is the same wobble
    /// at an amplitude that rounds away, not immunity. Note that a NAVIGATION
    /// BAR is not enough: EIGHT other captures render one (`settings-populated`
    /// and the three conversation scenes each carry an inline
    /// `.navigationTitle`) and every one of them is byte-identical run to run,
    /// so asserting on one of those would pass without ever touching the
    /// mechanism this test exists to watch.
    ///
    /// Between the two dark candidates the EMPTY variant is chosen because
    /// `conversation-list-populated-dark` is the OTHER scene RFC 130 measured as
    /// flaking (6 distinct PNGs in 6 runs) and it carries a seeded list with
    /// relative timestamps: the empty one has no list, no rows and no
    /// timestamps, so nothing but the toolbar can move it and a failure here
    /// has exactly one meaning.
    ///
    /// The second assertion is the early warning: the observed maximum delta
    /// must be STRICTLY BELOW `epsilon`, so if a future OS widens the wobble
    /// toward the tolerance this fails while there is still headroom, instead
    /// of the corpus quietly becoming untrustworthy at the boundary.
    func testOneSceneCapturedTwiceIsEqualUnderTheEpsilonRule() throws {
        let scene = try Self.repeatabilityScene()
        // BOTH sides round-trip through PNG, because the corpus is PNG files —
        // that is what the contract is about, and RFC 130's own finding is that
        // the encode/decode round trip changes the numbers (an in-memory
        // capture measured against a decoded PNG reports a ±1/255 floor that is
        // the codec, not the platform). Comparing two in-memory captures would
        // assert a promise the corpus does not make.
        let first = try Self.captureRoundTrippedThroughPNG(scene)
        let second = try Self.captureRoundTrippedThroughPNG(scene)
        let comparison = SnapshotBaseline.compare(
            baseline: first, capture: second, id: Self.repeatabilityID
        )
        let name = Self.repeatabilityID.corpusName
        let measured = try XCTUnwrap(
            comparison.measured,
            "[\(name)]: two captures of one scene came out at different raster sizes"
        )
        let drift = measured.drift
        print("SNAPSHOT REPEATABILITY: [\(name)] captured twice — "
            + "max delta \(drift.maxDelta)/255 (tolerance \(Int(SnapshotBaseline.epsilon))), "
            + "\(drift.differingPixels) of \(drift.pixelCount) px differ at all "
            + "(\(drift.differingFraction.percentText())), moved \(measured.movedFraction.percentText())")
        XCTAssertEqual(
            measured.movedFraction, 0,
            "[\(name)]: two captures of the SAME scene in one process disagree under "
                + "the epsilon rule — \(measured.movedFraction.percentText()) of pixels moved by "
                + "more than \(SnapshotBaseline.epsilon)/255 (max delta \(drift.maxDelta)). "
                + "That is a real capture defect, not the known glass quantisation."
        )
        XCTAssertLessThan(
            drift.maxDelta, Int(SnapshotBaseline.epsilon),
            "[\(name)]: the platform's capture-to-capture wobble has reached "
                + "\(drift.maxDelta)/255, at or past the \(SnapshotBaseline.epsilon)/255 tolerance the "
                + "corpus comparison is built on. The verdict still reads 'no scene moved' but the "
                + "headroom is gone; widen epsilon deliberately, or find what grew."
        )
    }

    /// THE PIN FOR THE ROUND TRIP ITSELF. Nothing else in the suite fails if
    /// the corpus walk reverts to measuring the in-memory capture against a
    /// decoded PNG — the ±1/255 codec floor that would reinstate looks like
    /// data, so every row of the report would quietly become noise and the run
    /// would stay green.
    ///
    /// So: PNG-to-PNG of ONE capture is the IDENTITY comparison (delta 0), and
    /// capture-to-PNG of that same capture is NOT. Two decodes of the same
    /// bytes cannot differ; the in-memory image and its own encoded PNG travel
    /// different colour paths and do.
    ///
    /// If the second assertion ever fails, the two paths have converged on this
    /// OS. That is good news, not a defect — but confirm it across scenes
    /// before deleting the round trip, because the floor it removes is
    /// invisible in a green run.
    func testPNGRoundTripIsTheIdentityComparisonAndCaptureVsPNGIsNot() throws {
        let scene = try Self.repeatabilityScene()
        let image = Self.captureOnce(scene)
        let data = try XCTUnwrap(image.pngData(), "the capture produced no PNG data")
        let inMemory = try XCTUnwrap(
            SnapshotRaster(image, maxDimension: SnapshotRaster.noDownscale),
            "the in-memory capture produced no decodable bitmap"
        )
        let decoded = try Self.raster(ofPNG: data)
        let decodedAgain = try Self.raster(ofPNG: data)

        let samePath = SnapshotBaseline.compare(
            baseline: decoded, capture: decodedAgain, id: Self.repeatabilityID
        )
        let sameDrift = try XCTUnwrap(samePath.drift)
        XCTAssertEqual(
            sameDrift.maxDelta, 0,
            "[png-to-png]: decoding the same PNG bytes twice must be the identity comparison; "
                + "a non-zero delta here means the comparator itself is not deterministic"
        )

        let crossPath = SnapshotBaseline.compare(
            baseline: decoded, capture: inMemory, id: Self.repeatabilityID
        )
        let crossDrift = try XCTUnwrap(crossPath.drift)
        XCTAssertGreaterThan(
            crossDrift.maxDelta, 0,
            "[capture-to-png]: the in-memory capture and its own encoded PNG compared EQUAL. "
                + "That is the asymmetry RFC 130's PNG-to-PNG round trip exists to remove — if it "
                + "is genuinely gone on this OS the round trip is redundant, but verify across "
                + "scenes before removing it: the floor it hides is invisible in a green run."
        )
        print("SNAPSHOT ROUND TRIP: png-to-png max delta \(sameDrift.maxDelta)/255, "
            + "capture-to-png max delta \(crossDrift.maxDelta)/255")
    }

    /// THE CORPUS ENTRY THIS CLASS IS ABOUT, and the ONE owner of both facts
    /// in it: which scene, and which colour scheme. The scheme used to be
    /// stated here for the label and again at the capture call, so flipping one
    /// would have left the report confidently mislabelled while still passing.
    private static let repeatabilityID = SnapshotSceneID(
        sceneName: "conversation-list-empty", dark: true
    )

    private static func repeatabilityScene() throws -> SnapshotScene {
        try XCTUnwrap(
            SnapshotCatalogue.scenes.first { $0.name == repeatabilityID.sceneName },
            "[\(repeatabilityID.sceneName)] is gone from the catalogue; this test must be "
                + "re-pointed at another scene WITH A GLASS TOOLBAR BUTTON, or it asserts nothing"
        )
    }

    private static func captureOnce(_ scene: SnapshotScene) -> UIImage {
        // Fresh per capture, exactly as the corpus walk builds it: reusing one
        // view would show the second capture the first's settled state and make
        // this test agree with itself for the wrong reason.
        let view = SnapshotAsync.resolve(scene.content)
        return SnapshotHost.capture(
            size: scene.size,
            dark: repeatabilityID.dark,
            settle: scene.settle,
            scale: SnapshotOutput.captureScale
        ) { view }
    }

    private static func captureRoundTrippedThroughPNG(_ scene: SnapshotScene) throws -> SnapshotRaster {
        let image = captureOnce(scene)
        let data = try XCTUnwrap(
            image.pngData(), "[\(repeatabilityID.corpusName)]: the capture produced no PNG data"
        )
        return try raster(ofPNG: data)
    }

    private static func raster(ofPNG data: Data) throws -> SnapshotRaster {
        let decoded = try XCTUnwrap(UIImage(data: data), "the PNG data did not decode")
        // FULL resolution: the corpus comparison downscales for memory, and
        // averaging 2x2 blocks is exactly what would hide a 1-of-255 shift.
        // This assertion is about the platform's real output.
        return try XCTUnwrap(
            SnapshotRaster(decoded, maxDimension: SnapshotRaster.noDownscale),
            "the decoded PNG produced no decodable bitmap"
        )
    }
}

// MARK: - The corpus filename has ONE owner

/// `SnapshotSceneID` is the only place a corpus filename is composed, and
/// `SnapshotHost.render` reports the URL it actually wrote. These pin that
/// agreement: if the two ever diverge, the walk's read-back would address a
/// file that is not the one it just wrote and the PNG-to-PNG comparison would
/// silently degrade rather than fail.
@MainActor
final class SnapshotSceneIDTests: XCTestCase {
    func testTheCorpusNameIsSceneThenMode() {
        XCTAssertEqual(SnapshotSceneID(sceneName: "widget", dark: false).corpusName, "widget-light")
        XCTAssertEqual(SnapshotSceneID(sceneName: "widget", dark: true).corpusName, "widget-dark")
    }

    /// BOTH filenames, pinned. The capture URL is what the walk reads back and
    /// the diff URL is what the report sends the operator to open, so a second
    /// spelling of either is a silent miss rather than a failure.
    func testBothCorpusURLsAreDerivedFromTheCorpusName() {
        let id = SnapshotSceneID(sceneName: "widget", dark: true)
        let directory = URL(fileURLWithPath: "/tmp/corpus", isDirectory: true)
        XCTAssertEqual(id.url(in: directory).lastPathComponent, "widget-dark.png")
        XCTAssertEqual(id.diffURL(in: directory).lastPathComponent, "widget-dark.diff.png")
        XCTAssertEqual(id.url(in: directory).deletingLastPathComponent(),
                       id.diffURL(in: directory).deletingLastPathComponent(),
                       "the capture and its overlay live in the directory they were given")
        XCTAssertTrue(id.diffURL(in: directory).lastPathComponent.hasPrefix(id.corpusName),
                      "the overlay is the corpus name plus a suffix, not a separately built name")
    }

    func testRenderWritesExactlyTheURLTheSceneIDNames() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("snapshot-name-agreement-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }

        let id = SnapshotSceneID(sceneName: "name-agreement", dark: true)
        let (_, url) = try SnapshotHost.render(
            id: id,
            size: CGSize(width: 60, height: 60),
            settle: 0.05,
            into: directory
        ) { AnyView(Color.blue) }

        XCTAssertEqual(url, id.url(in: directory),
                       "render must write the URL SnapshotSceneID names, not one of its own")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        // The read-back the corpus walk depends on, on the URL render reported.
        let data = try XCTUnwrap(try? Data(contentsOf: url), "the written PNG could not be read back")
        XCTAssertNotNil(UIImage(data: data), "the written PNG did not decode")
    }
}
