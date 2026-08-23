import Foundation
import SwiftUI

/// Inline spans within a block. Foundation already ships a CommonMark-correct
/// inline parser, so this file is deliberately thin: it calls
/// `AttributedString(markdown:options:)` and then applies the two styles
/// SwiftUI declines to derive from Foundation's intents (RFC 118).
enum MarkdownInline {
    /// Converts one block's raw inline Markdown.
    ///
    /// Inline code is styled `.dsCode` outright rather than at "the block's own
    /// size" as RFC 118 first put it: a `Font` token is opaque — there is no
    /// readable point size to match — and every code-bearing block this renderer
    /// draws is body-sized, which is exactly what `.dsCode` is. A `codeFont`
    /// parameter was written and then removed: no call site varied it, so it was
    /// generality bought on speculation.
    static func attributed(_ markdown: InlineMarkdown) -> AttributedString {
        var string: AttributedString
        do {
            string = try AttributedString(markdown: markdown.source, options: .init(
                allowsExtendedAttributes: true,
                // Load-bearing: it keeps soft line breaks inside a paragraph
                // instead of collapsing them, and it refuses to interpret block
                // syntax — correct, because blocks are already ours by here.
                interpretedSyntax: .inlineOnlyPreservingWhitespace,
                failurePolicy: .returnPartiallyParsedIfPossible
            ))
        } catch {
            // A student must never lose text to a parse failure. Malformed
            // Markdown degrades to the characters the coach actually sent.
            //
            // Defensive, and known to be: with
            // `.returnPartiallyParsedIfPossible` Foundation does not throw for
            // any input we have found — `testMalformedLinkReturnsItsSourceText`
            // asserts the guarantee holds, but it exercises the success path,
            // not this branch. Kept because the alternative to an untaken catch
            // is an uncaught throw in front of a student, and deleting it would
            // trade a dead line for a crash.
            return AttributedString(markdown.source)
        }

        for run in string.runs {
            if run.inlinePresentationIntent?.contains(.code) == true {
                string[run.range].font = .dsCode
            }
            if run.link != nil {
                // TextPrimary + underline, **not** an accent: DESIGN.md §1
                // restricts the brand gradient to chrome and selection and §8
                // removed system blue app-wide, so a coloured link inside body
                // copy would be precisely the new visual language §8 forbids.
                string[run.range].foregroundColor = .dsTextPrimary
                string[run.range].underlineStyle = .single
            }
        }
        return string
    }

    /// One block's base style, stamped on only where the inline parser has not
    /// already spoken: `dsCode` on an inline-code span and `TextPrimary` +
    /// underline on a link are `attributed`'s decisions (DESIGN.md §6), and
    /// writing the block's own font over them would silently undo them.
    ///
    /// Attributes rather than `.font()` / `.foregroundStyle()` modifiers,
    /// because a stacked table row composes two differently-styled halves into
    /// ONE `AttributedString` for ONE `Text` (RFC 120) — a view modifier cannot
    /// style half a string. One implementation serves both callers so the two
    /// cannot drift the first time a style rule changes.
    static func styled(_ markdown: InlineMarkdown, font: Font, color: Color) -> AttributedString {
        var string = attributed(markdown)
        // Ranges are collected before anything is written: mutating an
        // `AttributedString` re-segments its runs, so writing inside the
        // `runs` loop would iterate a collection being reshaped underneath it.
        let ranges = string.runs.map { ($0.range, $0.font, $0.foregroundColor) }
        for (range, runFont, runColor) in ranges {
            if runFont == nil { string[range].font = font }
            if runColor == nil { string[range].foregroundColor = color }
        }
        return string
    }
}
