import QuartzCore
import SwiftUI
import UIKit
import XCTest

// MARK: - The snapshot host (RFC 122)
//
// A `UIWindow`-based renderer for SwiftUI views. Every line below that looks
// like it could be simplified was bought with a real defect report; the
// comments say which, so the next person to "clean this up" finds the reason
// before the trap.
//
//   * `ImageRenderer` is BANNED. It does not rasterize `ScrollView` content
//     (it returns the scroll view's frame with nothing in it, which reads
//     exactly like a product defect) and it ignores a SwiftUI `.colorScheme`
//     override for asset-catalog colours (dark mode captured as white-on-white).
//     Both traps were hit in the RFC 118 run and cost two phantom defect
//     reports. Use `UIGraphicsImageRenderer`.
//   * Within `UIGraphicsImageRenderer`, the rasterizing call is
//     `drawHierarchy(in:afterScreenUpdates:)`, with `layer.render(in:)` only as
//     a fallback for a window that is not attached to a live scene. That
//     ordering is the opposite of what this file first said; `SnapshotHost.rasterize`
//     carries the defect (iOS 26 Liquid Glass nav chrome silently missing from
//     every LIGHT capture) that bought the reversal.
//   * Dark mode is `window.overrideUserInterfaceStyle`, NEVER
//     `.colorScheme(.dark)`: the SwiftUI modifier does not reach asset-catalog
//     colour resolution, the `UIWindow` trait does.
//   * The window must be built from a LIVE `UIWindowScene`
//     (`UIApplication.shared.connectedScenes`). A detached window is not
//     attached to a scene and `drawHierarchy(afterScreenUpdates:)` then returns
//     a blank image. The live scene is what makes the traits real AND what
//     makes the primary rasterizing path work at all.
//   * `RunLoop.current.run(until:)` is the settle. SwiftUI's async measurement
//     passes -- the `MarkdownView` width probe's preference-key round trip is
//     the known case -- have not converged at the end of `layoutIfNeeded()`.
//   * The calling XCTestCase must be `@MainActor`.

// MARK: Output directory

enum SnapshotOutput {
    /// The pinned canvas: iPhone 17 Pro at default Dynamic Type.
    static let deviceSize = CGSize(width: 402, height: 874)

    /// The raster scale of the corpus. 2x, not the simulator's own 3x: a 3x
    /// capture of the tall catalogue scene is a 1206x4500 bitmap, and the peak
    /// memory of a 32-image walk at that size gets the test process SIGKILLed
    /// by jetsam on a loaded machine (it did, three times, during the RFC 122
    /// build). 2x is still well past what a human reviewing a screen needs.
    static let captureScale: CGFloat = 2

    /// Width of the bleed margin on EACH side of the device-width container
    /// (see `SnapshotHost.renderBleedCanvas`).
    static let bleedMargin: CGFloat = 60

    /// Resolved once per test-process run; creating it also CLEARS it, so a
    /// scene deleted from the catalogue cannot leave a stale PNG behind
    /// pretending to be current.
    static let directory: URL = {
        let url = resolveDirectory()
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// The baseline corpus to compare against, when `-b` was passed to
    /// bin/snapshot-ios. Same two-name lookup as the output directory.
    static var baselineDirectory: URL? {
        guard let path = environment("UNICOACH_SNAPSHOT_BASELINE_DIR"), !path.isEmpty else { return nil }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    /// EMPIRICAL FINDING of the RFC 122 run, and the same one `bin/snapshot-ios`
    /// records at its `export`: `xcodebuild` forwards a variable named
    /// `TEST_RUNNER_<NAME>` from ITS OWN environment into the test process as
    /// `<NAME>`, with the prefix STRIPPED. A bare `<NAME>` exported beside it
    /// does NOT cross into the runner. So the name this process actually sees
    /// is the BARE one, and `bin/snapshot-ios` is what must export the prefixed
    /// form.
    ///
    /// Both names are read anyway, because the stripping is `xcodebuild`
    /// behaviour rather than a contract: an Xcode version that passed the
    /// prefixed name straight through, or a human running the Test action with
    /// `TEST_RUNNER_...` set by hand, would otherwise silently write the corpus
    /// to the default directory instead. They carry the same value when both
    /// arrive, so the order between them does not matter.
    static func environment(_ name: String) -> String? {
        let env = ProcessInfo.processInfo.environment
        if let bare = env[name], !bare.isEmpty { return bare }
        if let prefixed = env["TEST_RUNNER_" + name], !prefixed.isEmpty { return prefixed }
        return nil
    }

    private static func resolveDirectory() -> URL {
        if let explicit = environment("UNICOACH_SNAPSHOT_DIR"), !explicit.isEmpty {
            return URL(fileURLWithPath: explicit, isDirectory: true)
        }
        // No environment at all (an Xcode Test-action run): derive a findable
        // path from this source file. #filePath is
        // <repo>/ios-app/UnicoachiOSTests/SnapshotHost.swift.
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // UnicoachiOSTests
            .deletingLastPathComponent() // ios-app
            .deletingLastPathComponent() // <repo>
        return repoRoot
            .appendingPathComponent("ios-app/build/snapshots/latest", isDirectory: true)
    }
}

// MARK: Raster helpers

/// A decoded RGBA bitmap, so the assertions below reason about pixels rather
/// than about `UIImage`.
struct SnapshotRaster {
    let width: Int
    let height: Int
    /// Row-major RGBA, 4 bytes per pixel.
    let bytes: [UInt8]

    /// - Parameter maxDimension: the analysis is done on a DOWNSCALED copy
    ///   whose longest side is at most this. A retina capture of the tall
    ///   catalogue scene is 1206x4500, and decoding that at full size (21MB per
    ///   raster, twice per scene) is what killed the test process mid-run
    ///   during the RFC 122 build. Nothing the assertions ask -- "is this
    ///   uniform", "did this move" -- needs retina detail; the PNG that a human
    ///   looks at is written at full resolution regardless.
    init?(_ image: UIImage, maxDimension: Int = 1024) {
        guard let cgImage = image.cgImage else { return nil }
        // Locals, not the stored properties: referencing `self.width` inside
        // the withUnsafeMutableBytes closure captures a partly-initialized
        // `self` and the compiler rejects it.
        let longest = max(cgImage.width, cgImage.height)
        let divisor = max(1, Int((Double(longest) / Double(maxDimension)).rounded(.up)))
        let w = max(1, cgImage.width / divisor)
        let h = max(1, cgImage.height / divisor)
        var buffer = [UInt8](repeating: 0, count: w * h * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.premultipliedLast.rawValue
        let drawn: Bool = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(
                data: raw.baseAddress,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: w * 4,
                space: space,
                bitmapInfo: info
            ) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        width = w
        height = h
        bytes = buffer
    }

    func pixel(x: Int, y: Int) -> (UInt8, UInt8, UInt8, UInt8) {
        let i = (y * width + x) * 4
        return (bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3])
    }

    /// The count of distinct RGBA values, capped at `limit` so a busy image
    /// stops counting early. A UNIFORM image (count == 1) is the exact symptom
    /// of the `ImageRenderer` trap and of a view that never laid out.
    func distinctColourCount(limit: Int = 2) -> Int {
        var seen = Set<UInt32>()
        var i = 0
        while i < bytes.count {
            let value = UInt32(bytes[i]) << 24 | UInt32(bytes[i + 1]) << 16
                | UInt32(bytes[i + 2]) << 8 | UInt32(bytes[i + 3])
            seen.insert(value)
            if seen.count >= limit { return seen.count }
            i += 4
        }
        return seen.count
    }
}

// MARK: The host

@MainActor
enum SnapshotHost {
    /// Renders `content` into a window of exactly `size`, writes
    /// `<dir>/<name>-<light|dark>.png`, and returns the image.
    @discardableResult
    static func render(
        name: String,
        dark: Bool,
        size: CGSize = SnapshotOutput.deviceSize,
        settle: TimeInterval = 0.4,
        into directory: URL = SnapshotOutput.directory,
        content: () -> AnyView
    ) -> UIImage {
        let image = capture(size: size, dark: dark, settle: settle, scale: SnapshotOutput.captureScale) { content() }
        let url = directory.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png")
        try? image.pngData()?.write(to: url)
        return image
    }

    /// THE BLEED CANVAS (RFC 122 §3, declared as an experiment).
    ///
    /// The content is given a container of exactly the device width, but the
    /// WINDOW is wider (device width + 2 * margin) and filled with a
    /// distinctive backdrop colour. `UIView.clipsToBounds` is false by default
    /// and SwiftUI does not clip by default either, so a subview that lays out
    /// wider than its container draws into that margin -- and a scan of the
    /// margin for a non-backdrop pixel is a mechanical detector for the defect
    /// class that recurred twice (a table drawing wider than its bubble, a
    /// column running off the trailing edge).
    ///
    /// Returns the fraction of margin pixels that are not the backdrop colour.
    static func bleedFraction(
        dark: Bool,
        contentWidth: CGFloat = SnapshotOutput.deviceSize.width,
        height: CGFloat = SnapshotOutput.deviceSize.height,
        margin: CGFloat = SnapshotOutput.bleedMargin,
        settle: TimeInterval = 0.4,
        writeTo url: URL? = nil,
        content: () -> AnyView
    ) -> Double {
        let backdrop = Color(red: 1, green: 0, blue: 1) // magenta: appears in no asset
        let canvas = CGSize(width: contentWidth + 2 * margin, height: height)
        let inner = content()
        let image = capture(size: canvas, dark: dark, settle: settle, scale: 1) {
            AnyView(
                ZStack {
                    backdrop.ignoresSafeArea()
                    inner
                        .frame(width: contentWidth, height: height)
                }
                .frame(width: canvas.width, height: canvas.height)
            )
        }
        if let url { try? image.pngData()?.write(to: url) }
        // No downscale here: the bleed pass already renders at scale 1, so the
        // bitmap is small, and a downscale would blend content INTO the margin
        // it is scanning.
        guard let raster = SnapshotRaster(image, maxDimension: 8192) else { return 0 }
        let scale = CGFloat(raster.width) / canvas.width
        let marginPixels = Int((margin * scale).rounded(.down))
        guard marginPixels > 2 else { return 0 }
        // Inset by one pixel on every edge: the outermost row/column carries
        // the renderer's own antialiasing seam against the window edge, which
        // is not overdraw by anybody's view.
        var offenders = 0
        var total = 0
        for y in 1 ..< (raster.height - 1) {
            for x in 1 ..< marginPixels {
                for candidate in [x, raster.width - 1 - x] {
                    total += 1
                    let (r, g, b, _) = raster.pixel(x: candidate, y: y)
                    // Magenta with a generous tolerance; anything else in the
                    // margin came from the content.
                    if !(r > 200 && g < 60 && b > 200) { offenders += 1 }
                }
            }
        }
        return total == 0 ? 0 : Double(offenders) / Double(total)
    }

    /// The settle, in short slices with a CoreAnimation flush after each.
    /// A single `RunLoop.run(until:)` is not equivalent: the flush is what
    /// commits the layer tree of an OFFSCREEN window, whose CADisplayLink never
    /// fires, and without it a NavigationStack's content appearance (and so the
    /// `.task` that seeds it) can sit pending for the whole settle.
    private static func spin(_ seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.02))
            CATransaction.flush()
        }
    }

    /// The proven capture recipe. See the file header for why each line is here.
    /// - Parameter scale: the raster scale. 0 means the device's own (retina),
    ///   which is what the corpus wants. The bleed pass passes 1: a margin scan
    ///   needs no retina detail and a 9x smaller bitmap is what keeps the test
    ///   process inside the simulator's memory budget on the tall scenes.
    static func capture(
        size: CGSize,
        dark: Bool,
        settle: TimeInterval,
        scale: CGFloat = 0,
        content: () -> AnyView
    ) -> UIImage {
        // A LIVE UIWindowScene. A detached UIWindow gets no real traits.
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(origin: .zero, size: size)
        // Dark mode is a WINDOW TRAIT, never `.colorScheme(.dark)`.
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let hosting = UIHostingController(rootView: content())
        hosting.view.frame = window.bounds
        window.rootViewController = hosting
        window.makeKeyAndVisible()
        // Drive the appearance cycle by hand. Setting a rootViewController on a
        // window created this way leaves the hosting controller's appearance
        // transition half-open (UIKit logs "Unbalanced calls to begin/end
        // appearance transitions"), and SwiftUI fires `.task`/`onAppear` off
        // that cycle -- without this, every view that seeds itself in `.task`
        // (ConversationView's history load, ConversationListView's list load)
        // captures its LOADING SPINNER forever, no matter how long the settle.
        hosting.beginAppearanceTransition(true, animated: false)
        hosting.endAppearanceTransition()
        window.layoutIfNeeded()
        hosting.view.setNeedsLayout()
        hosting.view.layoutIfNeeded()
        // The settle: SwiftUI async measurement (the MarkdownView width probe)
        // has not converged at the end of layoutIfNeeded().
        spin(settle)

        // A SECOND layout pass, after the settle. Whatever the settle let
        // through -- an `ObservableObject` publish from a view's own `.task`
        // (ConversationView's history load is the case that caught this) -- has
        // marked the hierarchy dirty but not necessarily laid it out or
        // displayed it, and `layer.render(in:)` draws what has been COMMITTED,
        // not what is pending. Without this the conversation screens capture
        // their loading spinner even though the fetch demonstrably returned.
        hosting.view.setNeedsLayout()
        hosting.view.layoutIfNeeded()
        spin(0.1)

        // drawHierarchy(afterScreenUpdates:) FIRST, layer.render(in:) only as a
        // fallback. See `rasterize` for why the preference is this way round --
        // it is the RFC 122 nav-chrome defect, and it is the opposite of what
        // this file said before that defect was diagnosed.
        let image = rasterize(window: window, scale: scale)
        window.isHidden = true
        window.rootViewController = nil
        return image
    }

    /// THE RASTERIZER, and the one place the two capture paths are weighed.
    ///
    /// `drawHierarchy(in:afterScreenUpdates: true)` is the PRIMARY path.
    /// `layer.render(in:)` walks the layer tree synchronously and does not
    /// evaluate CoreAnimation compositing filters or the off-tree rendering
    /// that iOS 26's Liquid Glass navigation chrome is built from, so it drops
    /// exactly that chrome -- and it drops it QUIETLY:
    ///
    ///   * a `.navigationTitle` renders as its raw white mask. On a dark
    ///     capture white is the right answer and the title looks perfect; on
    ///     the light capture of the SAME scene it is white-on-white and the
    ///     title is simply gone. Two captures of one view disagreeing about
    ///     whether a title exists is precisely the phantom defect report this
    ///     harness was built to stop, and it made `-b` baseline drift noise.
    ///   * a toolbar button loses its glass capsule, and a multi-layer symbol
    ///     (`square.and.pencil`) loses the layers that are drawn through the
    ///     filter -- the square survives, the pencil does not, so the button
    ///     reads as an empty rounded box.
    ///
    /// The window here IS attached to a live `UIWindowScene` and is key and
    /// visible, which is what `drawHierarchy` needs; the historical "it returns
    /// a blank image" finding was about a DETACHED window. Because that is a
    /// property of the environment rather than of this code, a uniform result
    /// is treated as that failure and falls back to `layer.render(in:)` --
    /// chrome-less pixels beat no pixels, and the blank assertion in
    /// SnapshotTests still has the last word.
    ///
    /// `ImageRenderer` remains BANNED on both counts in the file header.
    private static func rasterize(window: UIWindow, scale: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = scale
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: format)
        var drewHierarchy = false
        let image = renderer.image { _ in
            drewHierarchy = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        if drewHierarchy, let raster = SnapshotRaster(image), raster.distinctColourCount() > 1 {
            return image
        }
        return renderer.image { context in
            window.layer.render(in: context.cgContext)
        }
    }
}

// MARK: Baseline comparison (`bin/snapshot-ios -b`)

/// Reports which scenes MOVED against a previously captured corpus. It reports;
/// it never fails the test (RFC 122 §4: "the question it answers is 'which
/// screens did my change move'").
enum SnapshotBaseline {
    /// A per-channel difference above this is a changed pixel.
    static let epsilon: UInt8 = 8
    /// A scene whose changed fraction exceeds this gets a diff overlay written.
    static let threshold: Double = 0.001

    struct Result {
        let name: String
        let fraction: Double
    }

    /// Compares `image` against `<baseline>/<name>.png`, writing
    /// `<dir>/<name>.diff.png` (a red overlay) when it moved beyond threshold.
    /// Returns nil when there is no baseline for this scene (a new scene).
    static func compare(
        image: UIImage,
        name: String,
        baseline baselineDirectory: URL,
        writingDiffsTo directory: URL
    ) -> Result? {
        let baselineURL = baselineDirectory.appendingPathComponent("\(name).png")
        guard let data = try? Data(contentsOf: baselineURL),
              let baselineImage = UIImage(data: data),
              let a = SnapshotRaster(baselineImage),
              let b = SnapshotRaster(image)
        else { return nil }
        guard a.width == b.width, a.height == b.height else {
            // A resized scene is a total move; no overlay is meaningful.
            return Result(name: name, fraction: 1)
        }
        var changed = 0
        var mask = [Bool](repeating: false, count: a.width * a.height)
        for index in 0 ..< (a.width * a.height) {
            let i = index * 4
            var moved = false
            for channel in 0 ..< 3 where !moved {
                let lhs = Int(a.bytes[i + channel])
                let rhs = Int(b.bytes[i + channel])
                if abs(lhs - rhs) > Int(epsilon) { moved = true }
            }
            if moved {
                changed += 1
                mask[index] = true
            }
        }
        let fraction = Double(changed) / Double(a.width * a.height)
        if fraction > threshold {
            writeOverlay(base: image, mask: mask, width: a.width, height: a.height,
                         to: directory.appendingPathComponent("\(name).diff.png"))
        }
        return Result(name: name, fraction: fraction)
    }

    private static func writeOverlay(base: UIImage, mask: [Bool], width: Int, height: Int, to url: URL) {
        guard var raster = SnapshotRaster(base)?.bytes else { return }
        for index in 0 ..< min(mask.count, width * height) where mask[index] {
            let i = index * 4
            raster[i] = 255
            raster[i + 1] = 0
            raster[i + 2] = 0
            raster[i + 3] = 255
        }
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.premultipliedLast.rawValue
        raster.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: space,
                bitmapInfo: info
            ), let cgImage = context.makeImage() else { return }
            try? UIImage(cgImage: cgImage).pngData()?.write(to: url)
        }
    }
}

// MARK: Running a scene's async seeding from a SYNCHRONOUS test

/// Runs a `@MainActor` async closure to completion while the main run loop
/// keeps turning, and returns its value.
///
/// THE TRAP THIS EXISTS FOR, paid for in the RFC 122 run: the tests here are
/// deliberately SYNCHRONOUS. In an `async` `@MainActor` XCTestCase method the
/// test body itself holds the main actor for its whole duration, so any OTHER
/// main-actor job -- crucially, the `Task` a view's `.task` modifier creates --
/// cannot run while the harness is spinning the run loop inside `capture`. The
/// symptom is silent and reads exactly like a product defect: `ConversationView`
/// and `ConversationListView` capture their LOADING SPINNER, their client is
/// never called during the capture (the fetch fires after the test method
/// returns), and no amount of settle time helps.
///
/// From a synchronous test the main actor is free, the run loop drains its jobs,
/// and views that seed themselves in `.task` reach their loaded state.
@MainActor
enum SnapshotAsync {
    /// A one-slot box, at file scope because a generic function may not nest a
    /// type.
    final class Box<T>: @unchecked Sendable {
        var value: T?
        init() {}
    }

    static func resolve<T>(_ operation: @escaping @MainActor () async -> T) -> T {
        let box = Box<T>()
        Task { @MainActor in box.value = await operation() }
        let deadline = Date().addingTimeInterval(10)
        while box.value == nil, Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
        guard let value = box.value else {
            fatalError("SnapshotAsync.resolve timed out after 10s waiting for a scene's seeding")
        }
        return value
    }
}
