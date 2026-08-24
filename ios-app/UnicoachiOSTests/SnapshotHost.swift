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

// MARK: Scene identity — the ONE owner of a corpus filename

/// One corpus entry: a scene and the colour scheme it was captured in.
///
/// THE ONE OWNER of the `<scene>-<light|dark>` name and of the URLs derived
/// from it. Before RFC 130's review the convention was re-typed in three places
/// — `SnapshotHost.render` composed the filename privately, and `SnapshotTests`
/// composed it again to write and a THIRD time to read the file back — so a
/// rename in one of them would have left the read-back silently missing its
/// file and degrading to the very comparison it exists to remove. Derive names
/// here or not at all; `SnapshotSceneIDTests` pins the agreement — both
/// filenames, and that `SnapshotHost.render` writes exactly the URL this type
/// names.
struct SnapshotSceneID: Equatable {
    /// The catalogue scene's own name, e.g. `conversation-list-empty` — NOT the
    /// corpus filename, which adds the mode. Spelled `sceneName` rather than
    /// `scene` because `SnapshotScene` is a real type in this target and a
    /// member called `scene` reads as one.
    let sceneName: String
    let dark: Bool

    var mode: String { dark ? "dark" : "light" }

    /// The corpus identity, e.g. `conversation-list-empty-dark`. This is what
    /// the baseline lookup, the diff overlay and the report all key on, and it
    /// is deliberately NOT called `name`: it is not the scene's name.
    var corpusName: String { "\(sceneName)-\(mode)" }

    /// The corpus PNG for this entry inside `directory`.
    func url(in directory: URL) -> URL {
        directory.appendingPathComponent("\(corpusName).png")
    }

    /// The red-overlay diff written for this entry when it moved. The ONLY
    /// place `.diff.png` is spelled: the report names the overlay by carrying
    /// the URL this produced, never by composing the filename a second time.
    func diffURL(in directory: URL) -> URL {
        directory.appendingPathComponent("\(corpusName).diff.png")
    }
}

/// What can go wrong writing one corpus entry or its diff overlay.
/// `pngEncodingFailed` and `overlayGeometryMismatch` are DEFECTS — the image
/// came from this type's own renderer, and the mask from its own comparator.
/// `writeFailed` may be an operator CONDITION: the directory can come from
/// `UNICOACH_SNAPSHOT_DIR`, so a missing parent or a permissions problem
/// reaches here too. The underlying error is carried verbatim because it is
/// what tells the two apart.
enum SnapshotHostError: Error, CustomStringConvertible {
    case pngEncodingFailed(URL)
    /// The moved mask and the raster it would be painted over disagree about
    /// their dimensions. Its OWN case, carrying BOTH sizes: this is the guard
    /// standing over the defect RFC 130's overlay path was rewritten to close
    /// (a `CGContext` sized from one raster over another's bytes), and it used
    /// to be reported as `pngEncodingFailed` — "produced no PNG data" — which
    /// names neither what happened nor either of the two numbers that explain
    /// it, both of which are in scope at the throw.
    case overlayGeometryMismatch(URL, mask: RasterSize, capture: RasterSize)
    case writeFailed(URL, underlying: Error)

    var description: String {
        switch self {
        case let .pngEncodingFailed(url):
            return "the capture for [\(url.lastPathComponent)] produced no PNG data"
        case let .overlayGeometryMismatch(url, mask, capture):
            return "the diff overlay for [\(url.lastPathComponent)] cannot be painted: the moved "
                + "mask is \(mask) and the capture it would be painted over is \(capture)"
        case let .writeFailed(url, underlying):
            return "could not write the capture to [\(url.path)]: \(underlying)"
        }
    }
}

// MARK: Percentages

extension Double {
    /// A fraction in 0...1 rendered as a percentage, for operator-facing output.
    /// Every `* 100` in the snapshot reports used to be written by hand at the
    /// point of printing; this is that conversion with one owner.
    func percentText(decimals: Int = 3) -> String {
        String(format: "%.\(decimals)f%%", self * 100)
    }
}

// MARK: Raster helpers

/// A raster's dimensions as ONE value. Width and height used to travel as two
/// loose arguments, which is how a `CGContext` came to be sized from one raster
/// over another raster's bytes; as a pair they compare in a single `==` and
/// print themselves for the operator.
struct RasterSize: Equatable, CustomStringConvertible {
    let width: Int
    let height: Int

    var description: String { "\(width)x\(height)" }
}

/// The MOVED pixels together with the raster they index, in row-major order.
/// The geometry travels WITH the mask so an overlay cannot be painted at
/// dimensions the mask was not built for.
struct MovedMask {
    let size: RasterSize
    let moved: [Bool]

    var isEmpty: Bool { !moved.contains(true) }
}

/// A decoded RGBA bitmap, so the assertions below reason about pixels rather
/// than about `UIImage`.
struct SnapshotRaster {
    /// Pass this as `maxDimension` to mean DO NOT DOWNSCALE. It is a cap, not a
    /// size: the tallest capture in the corpus is the 402x1500pt catalogue
    /// scene at `captureScale` 2, i.e. 804x3000 px, so 8192 leaves the divisor
    /// at `ceil(3000 / 8192)` = 1 and the bytes are the ones the renderer
    /// produced. Spelled as a name because a bare `8192` at a call site reads
    /// as a resolution choice rather than as the opt-out it is.
    static let noDownscale = 8192

    let width: Int
    let height: Int
    /// Row-major RGBA, 4 bytes per pixel.
    let bytes: [UInt8]

    var size: RasterSize { RasterSize(width: width, height: height) }

    /// - Parameter maxDimension: the analysis is done on a DOWNSCALED copy
    ///   whose longest side is at most this. The corpus captures the tall
    ///   catalogue scene at `captureScale` 2, i.e. 804x3000 (9.6MB per RGBA
    ///   raster, twice per scene) — and it was the 3x form the harness started
    ///   with, 1206x4500 at 21MB, that killed the test process mid-run during
    ///   the RFC 122 build. At 1024 the divisor for that scene is
    ///   ceil(3000/1024) = 3. Nothing the assertions ask — "is this uniform",
    ///   "did this move" — needs retina detail; the PNG that a human looks at
    ///   is written at full resolution regardless.
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

    /// A raster built directly from bytes, for tests that need a bitmap with a
    /// KNOWN per-channel difference. It is not a convenience: writing two PNGs
    /// and reading them back would put a colour-space conversion between the
    /// bytes the test wrote and the bytes the comparator sees, so a unit test
    /// of a 1-of-255 delta would be measuring the codec instead of the rule.
    init(width: Int, height: Int, bytes: [UInt8]) {
        self.width = width
        self.height = height
        self.bytes = bytes
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
    /// Renders `content` into a window of exactly `size`, writes the corpus PNG
    /// for `id`, and returns BOTH the image and the URL it actually wrote.
    ///
    /// The URL is returned rather than left to the caller to re-derive: the
    /// caller reads that same file back (RFC 130's PNG-to-PNG comparison), and
    /// a second copy of the naming convention is how that read-back would come
    /// to miss. `SnapshotSceneID` owns the name; this returns what it used.
    ///
    /// It THROWS rather than swallowing the write: the file is load-bearing
    /// downstream, and a `try?` here would surface as a puzzling comparison
    /// failure an iteration later instead of at the line that failed.
    @discardableResult
    static func render(
        id: SnapshotSceneID,
        size: CGSize = SnapshotOutput.deviceSize,
        settle: TimeInterval = 0.4,
        into directory: URL = SnapshotOutput.directory,
        content: () -> AnyView
    ) throws -> (image: UIImage, url: URL) {
        let image = capture(size: size, dark: id.dark, settle: settle, scale: SnapshotOutput.captureScale) { content() }
        let url = id.url(in: directory)
        guard let data = image.pngData() else { throw SnapshotHostError.pngEncodingFailed(url) }
        do {
            try data.write(to: url)
        } catch {
            throw SnapshotHostError.writeFailed(url, underlying: error)
        }
        return (image, url)
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
    /// Returns the fraction of margin pixels that are not the backdrop colour,
    /// or `nil` when the margin could NOT BE MEASURED — the canvas did not
    /// decode, or the margin is too thin to scan. That is deliberately not 0:
    /// 0 means "nothing bled", a passing answer, and returning it for an
    /// unmeasured scene made a failed measurement indistinguishable from a
    /// clean one across all 46 assertions. Callers must unwrap rather than
    /// default it.
    static func bleedFraction(
        dark: Bool,
        contentWidth: CGFloat = SnapshotOutput.deviceSize.width,
        height: CGFloat = SnapshotOutput.deviceSize.height,
        margin: CGFloat = SnapshotOutput.bleedMargin,
        settle: TimeInterval = 0.4,
        content: () -> AnyView
    ) -> Double? {
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
        // No PNG is written here. This pass had a `writeTo:` parameter that
        // swallowed its write with `try?` — no caller ever passed it, and one
        // file cannot hold two write policies while `render`'s doc calls
        // swallowing a corpus write the defect. The bleed verdict is a number,
        // not a file.
        //
        // No downscale here: the bleed pass already renders at scale 1, so the
        // bitmap is small, and a downscale would blend content INTO the margin
        // it is scanning.
        guard let raster = SnapshotRaster(image, maxDimension: SnapshotRaster.noDownscale) else { return nil }
        let scale = CGFloat(raster.width) / canvas.width
        let marginPixels = Int((margin * scale).rounded(.down))
        guard marginPixels > 2 else { return nil }
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
        return total == 0 ? nil : Double(offenders) / Double(total)
    }

    /// The settle, in short slices with a CoreAnimation flush after each.
    /// A single `RunLoop.run(until:)` is not equivalent: the flush is what
    /// commits the layer tree of an OFFSCREEN window, whose CADisplayLink never
    /// fires, and without it a NavigationStack's content appearance (and so the
    /// `.task` that seeds it) can sit pending for the whole settle.
    ///
    /// Not private: any test that mounts a window through `mount` needs the
    /// same knowledge to let SwiftUI act, and a hand-rolled `RunLoop.run(until:)`
    /// beside it is the same defect written again.
    static func settle(_ seconds: TimeInterval) {
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
        let window = mount(content(), size: size, dark: dark)
        // The settle: SwiftUI async measurement (the MarkdownView width probe)
        // has not converged at the end of `mount`'s layout pass.
        SnapshotHost.settle(settle)

        // A SECOND layout pass, after the settle. Whatever the settle let
        // through -- an `ObservableObject` publish from a view's own `.task`
        // (ConversationView's history load is the case that caught this) -- has
        // marked the hierarchy dirty but not necessarily laid it out or
        // displayed it, and `layer.render(in:)` draws what has been COMMITTED,
        // not what is pending. Without this the conversation screens capture
        // their loading spinner even though the fetch demonstrably returned.
        window.rootViewController?.view.setNeedsLayout()
        window.rootViewController?.view.layoutIfNeeded()
        SnapshotHost.settle(0.1)

        // drawHierarchy(afterScreenUpdates:) FIRST, layer.render(in:) only as a
        // fallback. See `rasterize` for why the preference is this way round --
        // it is the RFC 122 nav-chrome defect, and it is the opposite of what
        // this file said before that defect was diagnosed.
        let image = rasterize(window: window, scale: scale)
        dismiss(window)
        return image
    }

    /// Puts a SwiftUI view on screen in a real window, laid out and ready to be
    /// driven — the prologue every hosted test needs and the capture path shares.
    /// Each line is load-bearing; see the file header.
    ///
    /// **The caller owns the returned window and must take it down**, which for
    /// a test means the first line after this one:
    ///
    /// ```swift
    /// let window = SnapshotHost.mount(AnyView(view))
    /// addTeardownBlock { @MainActor in SnapshotHost.dismiss(window) }
    /// ```
    ///
    /// It is key and visible: left standing it outlives the test that made it,
    /// the next `mount` stacks another key window on top of it, and from then on
    /// "the first responder" and "the window on screen" are questions with two
    /// answers. `capture` dismisses its own before returning the image.
    ///
    /// Not folded into `capture` any more because a hosted assertion (RFC 127's
    /// keyboard wiring asserts on the first responder, which only exists in a
    /// window like this) needs the mounting without the rasterizing, and a test
    /// that re-types this recipe drifts from it — the first copy had already
    /// lost the CoreAnimation flush and never dismissed its window.
    static func mount(
        _ content: AnyView,
        size: CGSize = SnapshotOutput.deviceSize,
        dark: Bool = false
    ) -> UIWindow {
        // A LIVE UIWindowScene. A detached UIWindow gets no real traits -- and
        // no first responder either.
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(origin: .zero, size: size)
        // Dark mode is a WINDOW TRAIT, never `.colorScheme(.dark)`.
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let hosting = UIHostingController(rootView: content)
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
        return window
    }

    /// Takes a mounted window down; see `mount` for whose job this is.
    static func dismiss(_ window: UIWindow) {
        window.isHidden = true
        window.rootViewController = nil
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

/// Reports which scenes MOVED against a previously captured corpus. DRIFT is
/// reported and never fails the test (RFC 122 §4: "the question it answers is
/// 'which screens did my change move'"). A comparison that could not be
/// PERFORMED is a different thing and does fail: a baseline PNG that exists and
/// cannot be decoded is a broken instrument, not drift, and dropping it would
/// shrink the compared-scene count the report presents as its evidence. A
/// MISSING baseline stays ordinary — a new scene has none by definition — and
/// is merely noted.
///
/// THE CONTRACT (RFC 130). **The corpus is reproducible to `epsilon` per
/// channel, not byte for byte.** Compare two corpora with `bin/snapshot-ios -b`
/// — never with `md5`, `cmp`, `git diff` or any other pixel-exact rule, which
/// asserts something the platform does not promise and this harness has never
/// claimed. The reason is a finding, not a fudge: iOS 26's Liquid Glass
/// toolbar backdrop is a compositing filter evaluated off-tree by the render
/// server, and its resolved colour quantises BISTABLY — two captures of an
/// unchanged tree land one 8-bit step apart (±2/255) on the compose button's
/// capsule.
///
/// THE DISCRIMINATOR IS THAT BUTTON, NOT CHROME AT LARGE. Twelve of the 46
/// captures render navigation chrome — six scenes wrapped in a
/// `NavigationStack` with an inline `.navigationTitle` (`settings-populated`,
/// the three conversation scenes and both conversation-list scenes) — and ten
/// of them are byte-identical run to run. `ConversationListView` is the only
/// view in the corpus with a visible toolbar ITEM, and in the two captures that
/// do wobble the bar, the title and the glyph strokes are all byte-stable:
/// every differing pixel lies inside the compose button's glass capsule. So
/// "has chrome" predicts nothing; "has a glass toolbar button" is what does,
/// and that is the rule to carry to the next screen that gains one.
///
/// The two conversation-list LIGHT captures have the same button and come out
/// byte-identical anyway: the same wobble, at an amplitude that rounds away.
/// Dark is where the quantisation grid is unlucky against a near-black
/// backdrop; light is LOWER AMPLITUDE, not immune, so do not write a byte
/// comparison "just for light".
///
/// A tolerance necessarily hides what sits under it, so a comparison also
/// carries `drift`: the maximum observed per-channel delta and how many pixels
/// differ at all. Max-delta 2 is the platform noise above, while max-delta 7
/// over a broad area is a real change hiding below the tolerance and would
/// otherwise be reported as "nothing moved".
enum SnapshotBaseline {
    /// A per-channel difference above this is a changed pixel. 8 is 4x the
    /// observed platform wobble; that headroom is what makes the verdict mean
    /// something, and `Drift.maxDelta` is what keeps the gap visible instead
    /// of silent.
    ///
    /// THIS CONSTANT IS THE AUTHORITY for the tolerance. `bin/snapshot-ios`
    /// deliberately states the RULE without the numeral and points here, so
    /// widening the tolerance cannot leave a shell help text quietly lying.
    static let epsilon: UInt8 = 8
    /// A scene whose changed fraction exceeds this gets a diff overlay written.
    static let threshold: Double = 0.001

    /// One scene's comparison: the corpus entry it is about, the OUTCOME of
    /// applying the rule to it, and the overlay that outcome caused to be
    /// written (when one was).
    struct Comparison {
        /// The corpus entry compared, NOT its rendered name: the report sorts
        /// and groups these, and "both DARK scenes moved" is a question a
        /// `<scene>-<mode>` concatenation cannot answer. It is also the ONE
        /// owner of the two filenames.
        let id: SnapshotSceneID
        let outcome: Outcome
        /// The diff overlay this comparison caused to be written, or nil when
        /// none was. CARRIED rather than recomposed: spelling `.diff.png` here
        /// would make `Comparison` a second owner of the convention
        /// `SnapshotSceneID` owns, and the operator is sent to this exact
        /// string. Non-nil means the file is on disk — `writeOverlay` throws
        /// rather than failing quietly, so there is no third state where the
        /// report names a file that was never written.
        let overlayURL: URL?

        init(id: SnapshotSceneID, outcome: Outcome, overlayURL: URL? = nil) {
            self.id = id
            self.outcome = outcome
            self.overlayURL = overlayURL
        }

        /// DISJOINT BY CONSTRUCTION. Either the two rasters were the same size
        /// and every figure below exists, or they were not and NOTHING was
        /// measured — in which case the verdict is a total move by definition
        /// rather than by count, there is no mask, and therefore no overlay is
        /// reachable. Before RFC 130's re-review these were one struct with an
        /// optional `drift` and a separate `[Bool]` mask, which admitted
        /// `movedFraction: 0.5, drift: nil` and — worse — let the resized case
        /// reach the overlay writer with an EMPTY mask and the baseline's
        /// dimensions over the capture's bytes.
        enum Outcome {
            case measured(Measured)
            case notComparable(baseline: RasterSize, capture: RasterSize)
        }

        /// The figures of a scene whose rasters WERE comparable pixel for
        /// pixel, and the mask of what moved.
        struct Measured {
            /// Fraction of pixels that moved, i.e. whose R, G or B differs by
            /// MORE than `epsilon`. This is the only figure the verdict is
            /// made of.
            let movedFraction: Double
            /// The sub-tolerance evidence: reported, never failed.
            let drift: Drift
            /// The moved pixels, carrying the raster they index. The only
            /// input an overlay can be painted from.
            let mask: MovedMask
        }

        /// The sub-tolerance figures of a scene whose rasters WERE comparable.
        struct Drift {
            /// The largest per-channel difference seen anywhere in the scene
            /// (0-255), whether or not it cleared `epsilon`. 0 means the two
            /// rasters are byte-identical.
            let maxDelta: Int
            /// How many pixels differ AT ALL — any of R, G, B off by 1 or more.
            /// Alpha is excluded, exactly as in the moved rule.
            let differingPixels: Int
            /// What the two counts above are out of, so a reader can size them.
            let pixelCount: Int

            var differingFraction: Double {
                pixelCount == 0 ? 0 : Double(differingPixels) / Double(pixelCount)
            }

            /// The evidence-table columns for this scene, at the widths
            /// `reportLine` aligns to. Not a general rendering: the field
            /// widths only mean anything inside that one table.
            var tableColumns: String {
                String(format: "max delta %3d/255  %8d of %8d px differ (%@)",
                       maxDelta, differingPixels, pixelCount,
                       differingFraction.percentText())
            }
        }

        /// The measured figures, or nil for a scene that was not comparable.
        var measured: Measured? {
            switch outcome {
            case let .measured(measured): return measured
            case .notComparable: return nil
            }
        }

        var drift: Drift? { measured?.drift }

        /// The verdict. A resized scene is a TOTAL move — the rasters cannot be
        /// compared, so the whole scene is treated as changed.
        var movedFraction: Double {
            switch outcome {
            case let .measured(measured): return measured.movedFraction
            case .notComparable: return 1
            }
        }

        var moved: Bool { movedFraction > SnapshotBaseline.threshold }

        /// Loudest first: highest max delta, ties broken by how much of the
        /// scene differs at all. A scene that could NOT be measured sorts to
        /// the top — "no figure exists" is the row a reader must not skim past.
        /// Named and owned here rather than written as a tuple expression
        /// inside the printing routine, where nothing could test it.
        static func byLoudestDrift(_ lhs: Comparison, _ rhs: Comparison) -> Bool {
            (lhs.drift?.maxDelta ?? Int.max, lhs.drift?.differingFraction ?? 1)
                > (rhs.drift?.maxDelta ?? Int.max, rhs.drift?.differingFraction ?? 1)
        }

        /// One row of the evidence table. Presentation lives here rather than in
        /// a format string at each call site, so the two places that print a
        /// comparison cannot drift into rendering the same fields differently.
        ///
        /// The name is padded in Swift rather than with a `%-40@` width:
        /// `String(format:)` honours a width on `%@` only through Foundation's
        /// object formatting, which is not a promise worth making a column
        /// layout depend on. The identifier is bracketed
        /// (`code-review-bracket-serialization`); the numeric columns are not,
        /// because brackets would break the alignment that makes the table
        /// scannable — that is the deliberate exemption for this one table.
        var reportLine: String {
            let bracketed = "[\(id.corpusName)]"
            let padded = bracketed.padding(
                toLength: max(42, bracketed.count), withPad: " ", startingAt: 0
            )
            switch outcome {
            case let .measured(measured):
                return "  \(padded)  \(measured.drift.tableColumns)  "
                    + "moved \(measured.movedFraction.percentText())"
            case let .notComparable(baselineSize, captureSize):
                // The two sizes, not just the fact that they differ: the
                // operator's next question is always "changed to what".
                return "  \(padded)  NOT MEASURED (raster \(baselineSize) -> \(captureSize); no "
                    + "per-pixel figure exists)  moved \(movedFraction.percentText())"
            }
        }

        /// The line for a scene that moved, naming the overlay to open — by
        /// `lastPathComponent` of the URL that was actually written, so the
        /// report cannot name a file the harness did not produce.
        var movedLine: String {
            let overlay = overlayURL.map { " (diff overlay: [\($0.lastPathComponent)])" } ?? ""
            return "  MOVED  [\(id.corpusName)]  \(movedFraction.percentText()) of pixels" + overlay
        }
    }

    /// Why a scene produced no comparison — or the comparison it produced.
    ///
    /// `missing` is the ORDINARY case: a scene that is new since the baseline
    /// was captured. `unreadable` is a corrupt, truncated or undecodable
    /// baseline PNG, which is INFRASTRUCTURE FAILURE and must be reported: both
    /// used to return the same `nil` and the walk dropped the scene silently,
    /// which now quietly shrinks the compared-scene count the report prints as
    /// its headline evidence.
    ///
    /// `captureUndecodable` is the THIRD subject, and it is not about the
    /// baseline at all: it is THIS RUN's own capture that produced no bitmap.
    /// It used to share `unreadable`'s branch, so the operator was told "the
    /// baseline at [<path>] could not be read" — a false diagnosis complete
    /// with a path, sending them to re-capture a baseline that was fine. It
    /// carries the scene rather than a URL because the failing thing is an
    /// in-memory image, and there is no file to name.
    enum BaselineLookup {
        case compared(Comparison)
        case missing(URL)
        case unreadable(URL, underlying: Error?)
        case captureUndecodable(SnapshotSceneID)
    }

    /// Compares `image` against the baseline PNG `id` names, writing the red
    /// overlay `id` names when it moved beyond threshold.
    ///
    /// It THROWS only for the overlay write, on the same policy as
    /// `SnapshotHost.render`: the report tells the operator to open that exact
    /// file, so failing to produce it is a defect at the line that failed
    /// rather than a puzzling absence later. A baseline that is missing or
    /// unreadable, or a capture that did not decode, is not a throw — it is the
    /// answer, and it is `BaselineLookup`'s job to say WHICH, and about which
    /// of the two files.
    static func compare(
        image: UIImage,
        id: SnapshotSceneID,
        baseline baselineDirectory: URL,
        writingDiffsTo directory: URL
    ) throws -> BaselineLookup {
        let baselineURL = id.url(in: baselineDirectory)
        guard FileManager.default.fileExists(atPath: baselineURL.path) else {
            return .missing(baselineURL)
        }
        let data: Data
        do {
            data = try Data(contentsOf: baselineURL)
        } catch {
            return .unreadable(baselineURL, underlying: error)
        }
        guard let baselineImage = UIImage(data: data),
              let baselineRaster = SnapshotRaster(baselineImage)
        else { return .unreadable(baselineURL, underlying: nil) }
        // NOT `unreadable`: the thing that failed here is the image this run
        // just captured, and naming `baselineURL` at this line is what sent the
        // operator to delete a good baseline.
        guard let captureRaster = SnapshotRaster(image) else {
            return .captureUndecodable(id)
        }

        let comparison = compare(baseline: baselineRaster, capture: captureRaster, id: id)
        // An overlay is reachable ONLY from the measured case, which is what
        // makes the mask and the bitmap it is painted onto the same size by
        // construction. No `!mask.isEmpty` guard: `moved` implies the mask has
        // moved pixels in it, because both come from the same count.
        guard case let .measured(measured) = comparison.outcome, comparison.moved else {
            return .compared(comparison)
        }
        let overlayURL = id.diffURL(in: directory)
        try writeOverlay(capture: captureRaster, mask: measured.mask, to: overlayURL)
        return .compared(Comparison(id: id, outcome: comparison.outcome, overlayURL: overlayURL))
    }

    /// THE RULE ITSELF, over two decoded rasters — pulled out of the image path
    /// so it can be unit-tested against synthesized bitmaps with a known delta,
    /// with no PNG codec or colour-space conversion in between.
    ///
    /// The two sides are NOT interchangeable, which is why they are named
    /// rather than `a`/`b`: `baseline` is the corpus already on disk and
    /// `capture` is what this run produced.
    ///
    /// One pass computes all three figures: the moved fraction (the verdict,
    /// unchanged — a channel must move by MORE than `epsilon`), and alongside
    /// it the maximum per-channel delta and the count of pixels differing at
    /// all. The mask is the moved pixels, for the red overlay.
    ///
    /// Rasters of DIFFERENT sizes yield `.notComparable` carrying both sizes.
    /// That case has no figures and no mask to invent, so nothing downstream
    /// can paint an overlay from it.
    static func compare(
        baseline: SnapshotRaster,
        capture: SnapshotRaster,
        id: SnapshotSceneID
    ) -> Comparison {
        guard baseline.size == capture.size else {
            return Comparison(
                id: id,
                outcome: .notComparable(baseline: baseline.size, capture: capture.size)
            )
        }
        let pixels = baseline.width * baseline.height
        var changed = 0
        var differing = 0
        var maxDelta = 0
        var mask = [Bool](repeating: false, count: pixels)
        for index in 0 ..< pixels {
            let i = index * 4
            var pixelMax = 0
            for channel in 0 ..< 3 {
                let delta = abs(Int(baseline.bytes[i + channel]) - Int(capture.bytes[i + channel]))
                if delta > pixelMax { pixelMax = delta }
            }
            if pixelMax > maxDelta { maxDelta = pixelMax }
            if pixelMax > 0 { differing += 1 }
            if pixelMax > Int(epsilon) {
                changed += 1
                mask[index] = true
            }
        }
        let measured = Comparison.Measured(
            movedFraction: pixels == 0 ? 0 : Double(changed) / Double(pixels),
            drift: Comparison.Drift(
                maxDelta: maxDelta, differingPixels: differing, pixelCount: pixels
            ),
            mask: MovedMask(size: baseline.size, moved: mask)
        )
        return Comparison(id: id, outcome: .measured(measured))
    }

    /// Paints the moved pixels red over the CAPTURE and writes the PNG.
    ///
    /// It takes the decoded capture raster rather than a `UIImage` plus loose
    /// `width`/`height`: those dimensions used to come from the BASELINE while
    /// the bytes came from the capture, which is an out-of-bounds read the
    /// moment the two differ. Here the geometry has one source and the mask
    /// carries its own, so a DIMENSION mismatch is a thrown defect rather than
    /// a bad read. What is not checked is the mask ARRAY's length against the
    /// size it declares — the write loop runs on `moved.count` — so a
    /// `MovedMask` built with a longer array than its `RasterSize` traps here
    /// rather than reading past the end of the buffer. Trapping is the floor
    /// this guard guarantees, not absence.
    ///
    /// Throws rather than swallowing: `Comparison.movedLine` sends the operator
    /// to this exact file.
    private static func writeOverlay(capture: SnapshotRaster, mask: MovedMask, to url: URL) throws {
        guard mask.size == capture.size else {
            throw SnapshotHostError.overlayGeometryMismatch(url, mask: mask.size, capture: capture.size)
        }
        var bytes = capture.bytes
        for index in 0 ..< mask.moved.count where mask.moved[index] {
            let i = index * 4
            bytes[i] = 255
            bytes[i + 1] = 0
            bytes[i + 2] = 0
            bytes[i + 3] = 255
        }
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.premultipliedLast.rawValue
        let overlay: UIImage? = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: capture.width,
                height: capture.height,
                bitsPerComponent: 8,
                bytesPerRow: capture.width * 4,
                space: space,
                bitmapInfo: info
            ), let cgImage = context.makeImage() else { return nil }
            return UIImage(cgImage: cgImage)
        }
        guard let data = overlay?.pngData() else { throw SnapshotHostError.pngEncodingFailed(url) }
        do {
            try data.write(to: url)
        } catch {
            throw SnapshotHostError.writeFailed(url, underlying: error)
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
