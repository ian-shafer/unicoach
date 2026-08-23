import Foundation

/// A block-level element of a coach reply. Deliberately **Foundation-only** —
/// no SwiftUI, no UIKit — because this is the half of Markdown rendering that
/// carries logic, and `bin/test` never compiles `ios-app`: the XCTest suite is
/// the only mechanical authority this code will ever face, so everything that
/// can be an assertable value rather than a view is one (RFC 118).
///
/// Inline spans (`**bold**`, `` `code` ``, links) are **not** modelled here.
/// They stay as unparsed Markdown — an `InlineMarkdown` — and are converted at
/// render time by `MarkdownInline`, which keeps this model `Equatable` and
/// cheap to diff across the hundreds of SSE deltas a single reply arrives in.
enum MarkdownBlock: Equatable {
    case heading(level: HeadingLevel, text: InlineMarkdown)
    case paragraph(InlineMarkdown)
    case list(MarkdownList)
    /// The paragraph lines of a `>` run, `>` already stripped.
    case quote([InlineMarkdown])
    /// **Verbatim characters, and the one payload here that is a bare
    /// `String`** — see `InlineMarkdown`. The fence's info string
    /// (`` ```sh ``) is parsed off the opener and **discarded**: nothing in
    /// this design labels a code block, and a field no view reads is a field
    /// that goes stale unnoticed. Parsing it is still required — it is what
    /// keeps `sh` out of the code body.
    case code(String)
    case table(MarkdownTable)
    case rule
}

/// Markdown that still holds inline syntax: the renderer **must** put it
/// through `MarkdownInline.attributed` before any eye or ear receives it.
///
/// A one-field wrapper rather than a doc comment on a `String`, because this
/// model carries two exactly opposite contracts in what used to be the same
/// primitive: a paragraph's `**bold**` must be parsed away, and a code block's
/// `**bold**` must survive keystroke for keystroke. Interchangeable `String`s
/// let either mistake compile — a code body handed to `attributed` loses the
/// characters that are the whole point of a code block, and prose handed
/// straight to `Text` shows the syntax RFC 118 exists to hide. Now only one of
/// the two type-checks at each call site.
///
/// `ExpressibleByStringLiteral` so a *literal* — a fixture or an assertion,
/// where the contract is being stated, not passed on — stays readable; a
/// `String` *value* still has to be wrapped deliberately.
struct InlineMarkdown: Equatable, ExpressibleByStringLiteral {
    let source: String

    init(_ source: String) { self.source = source }
    init(stringLiteral value: StringLiteralType) { self.source = value }
}

/// The six levels Markdown has, and no seventh.
///
/// A raw `Int` made `0` and `42` representable and left the renderer resolving
/// them with a `level <= 2` ternary — a partition of an unbounded range in
/// which h3…h6 were indistinguishable and an out-of-range level silently drew
/// as a label. As a type, the parser's two constructors cannot build an
/// illegal value and the view has to name every level it draws.
enum HeadingLevel: Int, Equatable {
    case h1 = 1, h2, h3, h4, h5, h6
}

/// A run of list items. **Nesting is flattened into `depth`** rather than
/// recursed: a recursive type buys nothing a leading indent does not express,
/// and it would force both the parser and the renderer to carry a tree for a
/// structure the design draws as an indent.
struct MarkdownList: Equatable {
    let items: [Item]

    struct Item: Equatable {
        /// What draws in the leading column. **One union rather than the
        /// `isOrdered` / `marker` / `checked` trio it replaces**: those were
        /// three fields for one disjoint choice, so
        /// `isOrdered: true, marker: "\u{2022}", checked: true` compiled and
        /// contradicted itself twice, and the renderer resolved the conflict at
        /// runtime by ignoring two of the three. A task's checkbox *replaces*
        /// its bullet rather than joining it, so the type now says so.
        enum Marker: Equatable {
            case bullet
            /// The item's position in its run, resolved at parse time so the
            /// renderer never counts rows. A **number**, not the "3." it draws
            /// as: the delimiter convention is presentation, and fusing it in
            /// made every consumer that wants the ordinal re-parse a string
            /// this parser had just formatted.
            case ordered(ordinal: Int)
            /// GFM task list state. A separate case, not a `Bool?` beside a
            /// bullet: "not a task" is the *absence of this case*, not a third
            /// value of it.
            case task(done: Bool)
        }

        /// 0 for a top-level item, 1 for its children, and so on.
        let depth: Int
        let marker: Marker
        let text: InlineMarkdown

        /// A wrapped continuation line belongs to the item above it. `let`
        /// throughout and a named transition, because a parsed block is
        /// finished data: the item is replaced by a new value rather than
        /// reached into and edited, which is the shape `MarkdownTable` ten
        /// lines below already had.
        func continuing(with line: String) -> Item {
            Item(depth: depth, marker: marker, text: InlineMarkdown(text.source + "\n" + line))
        }
    }
}

struct MarkdownTable: Equatable {
    /// Cells are inline Markdown: a coach's `| **Draft** |` is bold text, and
    /// the renderer parses it like any other prose.
    let headers: [InlineMarkdown]
    /// Exactly `headers.count` entries — see `init`.
    let alignments: [Alignment]
    /// Every row has exactly `headers.count` cells — see `init`.
    let rows: [[InlineMarkdown]]

    /// The only way to build a table, and it **normalises**: `alignments` and
    /// every row are padded and truncated to `headers.count` here — GFM's own
    /// ragged-row rule — so the grid is rectangular for *every value of the
    /// type* rather than for the ones the parser remembered to pad. That is
    /// what lets the renderer subscript a column unguarded; "rectangular by
    /// construction" as a doc comment left three `indices.contains` checks
    /// standing in the view, and a ragged table only has to be built once,
    /// anywhere, for one of them to be the thing that saves it.
    init(headers: [InlineMarkdown], alignments: [Alignment], rows: [[InlineMarkdown]]) {
        self.headers = headers
        self.alignments = MarkdownTable.fit(alignments, to: headers.count, pad: .leading)
        self.rows = rows.map { MarkdownTable.fit($0, to: headers.count, pad: "") }
    }

    private static func fit<Element>(_ values: [Element], to count: Int, pad: Element) -> [Element] {
        values.count >= count
            ? Array(values.prefix(count))
            : values + Array(repeating: pad, count: count - values.count)
    }

    /// Column alignment. Named rather than reusing SwiftUI's `Alignment` so
    /// this file stays free of SwiftUI; `MarkdownView` maps it across.
    /// GFM's unspecified default is `leading`, so there is no `none` case —
    /// an absent `:` is not a third state, it is the default.
    enum Alignment: Equatable {
        case leading
        case center
        case trailing
    }
}

// MARK: - Parsing

extension MarkdownBlock {
    /// A single line-oriented pass over `source`.
    ///
    /// Three behaviours here are **streaming requirements, not edge cases**,
    /// because this runs against a half-written message on every delta:
    /// an unclosed fence yields the code block so far (otherwise a long code
    /// block reads as garbled paragraphs and then snaps); a header + delimiter
    /// with no body rows yields a zero-row table that grows downward (otherwise
    /// pipe soup becomes a table mid-read); and a trailing partial line is
    /// parsed as whatever it currently looks like. Reflow as the stream lands
    /// beats a full-message relayout at the moment the student starts reading.
    static func parse(_ source: String) -> [MarkdownBlock] {
        var parser = Parser(source: source)
        return parser.run()
    }

    private struct Parser {
        let lines: [String]
        var blocks: [MarkdownBlock] = []

        /// Open paragraph lines. Held rather than emitted because a following
        /// `---` retroactively turns them into a setext heading.
        var paragraph: [String] = []
        var quote: [String] = []
        var listItems: [MarkdownList.Item] = []
        var listIsOrdered = false
        /// Indent columns of the currently open list, innermost last. A stack
        /// rather than "indent / 2" so a document nested with four spaces and
        /// one nested with two both produce depth 1 — the depth that matters is
        /// the item's position in the hierarchy, not its column.
        var listIndents: [Int] = []
        /// Next ordinal per depth, so `3.` `3.` `3.` renders 3, 4, 5 — what
        /// every Markdown renderer does, and what a stream that has only
        /// delivered the first digit of each line needs.
        var ordinals: [Int: Int] = [:]

        init(source: String) {
            // `omittingEmptySubsequences: false` matters: blank lines are the
            // block separator, so dropping them would merge every paragraph.
            lines = source.split(separator: "\n", omittingEmptySubsequences: false)
                .map { $0.hasSuffix("\r") ? String($0.dropLast()) : String($0) }
        }

        mutating func run() -> [MarkdownBlock] {
            var index = 0
            while index < lines.count {
                let line = lines[index]
                let trimmed = line.trimmingCharacters(in: .whitespaces)

                if trimmed.isEmpty {
                    // A blank line makes a list *loose*, it does not end it —
                    // CommonMark's rule, and the shape a coach reply routinely
                    // takes when it double-spaces its numbered steps. Flushing
                    // the list here restarted `1.` at every gap and dropped a
                    // re-indented child back to depth 0. Every branch that opens
                    // a genuinely different block — heading, fence, rule, quote,
                    // table, paragraph — already flushes the list itself, so the
                    // list still ends the moment something else really starts.
                    flushParagraph()
                    flushQuote()
                    index += 1
                    continue
                }

                if let fence = MarkdownBlock.fenceOpener(trimmed) {
                    flushAll()
                    index = consumeFence(from: index, fence: fence)
                    continue
                }

                if let heading = MarkdownBlock.atxHeading(trimmed) {
                    flushAll()
                    blocks.append(heading)
                    index += 1
                    continue
                }

                // Setext before thematic break: `---` under a paragraph
                // underlines it, and only a `---` with nothing above it is a
                // rule. Getting this backwards silently eats the heading.
                if !paragraph.isEmpty, let level = MarkdownBlock.setextLevel(trimmed) {
                    let text = paragraph.joined(separator: " ")
                    paragraph.removeAll()
                    blocks.append(.heading(level: level, text: InlineMarkdown(text)))
                    index += 1
                    continue
                }

                if MarkdownBlock.isThematicBreak(trimmed) {
                    flushAll()
                    blocks.append(.rule)
                    index += 1
                    continue
                }

                if trimmed.hasPrefix(">") {
                    flushParagraph()
                    flushList()
                    var rest = String(trimmed.dropFirst())
                    if rest.hasPrefix(" ") { rest.removeFirst() }
                    quote.append(rest)
                    index += 1
                    continue
                }

                // A table only exists if the delimiter row is already here; a
                // pipe-bearing line on its own is a paragraph, per GFM.
                if index + 1 < lines.count,
                   MarkdownBlock.containsUnescapedPipe(line),
                   let table = MarkdownBlock.parseTable(lines, from: index) {
                    flushAll()
                    blocks.append(.table(table.table))
                    index = table.nextIndex
                    continue
                }

                if let item = MarkdownBlock.listItem(line) {
                    flushParagraph()
                    flushQuote()
                    append(item)
                    index += 1
                    continue
                }

                // A line indented past the innermost list marker continues that
                // item. This is the commonest real shape in a coach reply — a
                // bullet whose text wraps — and treating it as a new block split
                // the list in two and redrew the tail as body prose at the
                // margin, which is CommonMark-wrong and reads as a rendering bug.
                if !listItems.isEmpty,
                   line.prefix(while: { $0 == " " || $0 == "\t" }).count > (listIndents.last ?? 0) {
                    listItems[listItems.count - 1] =
                        listItems[listItems.count - 1].continuing(with: trimmed)
                    index += 1
                    continue
                }

                // Anything else continues (or opens) a paragraph. An
                // *unindented* plain line under a list ends the list rather than
                // lazily continuing its last item: the student's eye reads it as
                // a new paragraph, and that is what the renderer should draw.
                flushList()
                flushQuote()
                paragraph.append(trimmed)
                index += 1
            }
            flushAll()
            return blocks
        }

        // MARK: Fences

        /// Consumes a fenced block starting at `index`, returning the index of
        /// the line after it. An unterminated fence consumes the rest of the
        /// input — that is the streaming case, and it is the whole point.
        mutating func consumeFence(from index: Int, fence: Fence) -> Int {
            var body: [String] = []
            var cursor = index + 1
            while cursor < lines.count {
                let candidate = lines[cursor].trimmingCharacters(in: .whitespaces)
                if MarkdownBlock.isFenceCloser(candidate, fence: fence) {
                    cursor += 1
                    blocks.append(.code(body.joined(separator: "\n")))
                    return cursor
                }
                // Verbatim: a fence's content keeps its own indentation, which
                // is usually the only thing making the code readable.
                body.append(lines[cursor])
                cursor += 1
            }
            blocks.append(.code(body.joined(separator: "\n")))
            return cursor
        }

        // MARK: Accumulators

        mutating func append(_ parsed: ListItemLine) {
            let isOrdered = parsed.marker != .bullet
            if !listItems.isEmpty, listIsOrdered != isOrdered {
                // A bullet run and an ordered run are different lists even with
                // no blank line between them. This stays *parser-local* state:
                // it is where the run split is decided, and storing it on the
                // emitted list as well would be a second, contradictable copy.
                flushList()
            }
            listIsOrdered = isOrdered

            let depth = depthFor(indent: parsed.indent)
            // Numbering is this layer's job, not the indent stack's: a depth
            // that has just closed its children must not resume their counter.
            resetOrdinals(deeperThan: depth)
            let glyph: MarkdownList.Item.Marker
            switch parsed.marker {
            case .ordered(let ordinal):
                // The first item at a depth sets the start; the rest count on
                // from it, so a non-1 start is honoured and a source that
                // writes `1.` three times still renders 1, 2, 3. The ordinal is
                // consumed even for a task item, whose checkbox replaces the
                // number, so `1. [ ]` / `2. x` does not renumber the tail.
                let resolved = ordinals[depth] ?? ordinal
                ordinals[depth] = resolved + 1
                glyph = .ordered(ordinal: resolved)
            case .bullet:
                glyph = .bullet
            }
            listItems.append(MarkdownList.Item(
                depth: depth,
                // A checkbox is the item's whole leading column, so it is the
                // marker rather than a flag beside one.
                marker: parsed.checked.map { .task(done: $0) } ?? glyph,
                text: InlineMarkdown(parsed.text)
            ))
        }

        /// Maps a leading indent onto a depth via the stack of indents this
        /// list has actually used. **Owns the indent stack and nothing else** —
        /// it used to also nil out `ordinals` entries, which is numbering state
        /// belonging to the marker layer, and a query-shaped name quietly
        /// mutating a second thing is how an ordinal bug becomes unreproducible.
        mutating func depthFor(indent: Int) -> Int {
            if listIndents.isEmpty {
                listIndents = [indent]
                return 0
            }
            if indent > listIndents[listIndents.count - 1] {
                listIndents.append(indent)
                return listIndents.count - 1
            }
            while listIndents.count > 1, indent < listIndents[listIndents.count - 1] {
                listIndents.removeLast()
            }
            return listIndents.count - 1
        }

        /// A depth that has just been opened, or reopened after its children
        /// closed, starts counting again: `1.` under a nested list that has
        /// already run to `3.` is a fresh sublist, not its continuation.
        mutating func resetOrdinals(deeperThan depth: Int) {
            ordinals = ordinals.filter { $0.key <= depth }
        }

        mutating func flushParagraph() {
            guard !paragraph.isEmpty else { return }
            // Joined with "\n", not " ": `.inlineOnlyPreservingWhitespace`
            // keeps soft breaks, and a coach reply's line breaks are meaning.
            blocks.append(.paragraph(InlineMarkdown(paragraph.joined(separator: "\n"))))
            paragraph.removeAll()
        }

        mutating func flushQuote() {
            guard !quote.isEmpty else { return }
            blocks.append(.quote(quote.map { InlineMarkdown($0) }))
            quote.removeAll()
        }

        mutating func flushList() {
            guard !listItems.isEmpty else { return }
            blocks.append(.list(MarkdownList(items: listItems)))
            listItems.removeAll()
            listIndents.removeAll()
            ordinals.removeAll()
        }

        mutating func flushAll() {
            flushParagraph()
            flushQuote()
            flushList()
        }
    }

    // MARK: - Line classification

    /// An open fence: the delimiter and run length a closer has to match.
    /// The info string is **not** carried — see `MarkdownBlock.code`.
    struct Fence {
        let delimiter: Character
        let length: Int
    }

    /// One source line recognised as a list item, before `Parser` resolves its
    /// depth and its ordinal. Distinct from `MarkdownList.Item`, which is the
    /// finished value: this carries the raw indent column, that carries a depth.
    struct ListItemLine {
        /// A bullet has no ordinal; a numbered item always has one. Two fields
        /// — a `Bool` beside an `Int?` — let a bullet carry a number and a
        /// numbered item carry none, and `append` read the two independently.
        /// Named for the same union the model calls `Marker`, so parser and
        /// model speak one vocabulary for one concept.
        enum Marker: Equatable {
            case bullet
            case ordered(ordinal: Int)
        }

        let indent: Int
        let marker: Marker
        let text: String
        /// GFM task list state; `nil` when the item is not a task at all.
        let checked: Bool?
    }

    static func fenceOpener(_ trimmed: String) -> Fence? {
        guard let first = trimmed.first, first == "`" || first == "~" else { return nil }
        let run = trimmed.prefix { $0 == first }
        guard run.count >= 3 else { return nil }
        let info = trimmed.dropFirst(run.count).trimmingCharacters(in: .whitespaces)
        // A backtick inside the info string is not an info string at all
        // (CommonMark), and in practice it means an inline-code line.
        if first == "`", info.contains("`") { return nil }
        return Fence(delimiter: first, length: run.count)
    }

    static func isFenceCloser(_ trimmed: String, fence: Fence) -> Bool {
        guard let first = trimmed.first, first == fence.delimiter else { return false }
        let run = trimmed.prefix { $0 == fence.delimiter }
        guard run.count >= fence.length else { return false }
        return trimmed.dropFirst(run.count).trimmingCharacters(in: .whitespaces).isEmpty
    }

    static func atxHeading(_ trimmed: String) -> MarkdownBlock? {
        guard trimmed.hasPrefix("#") else { return nil }
        let hashes = trimmed.prefix { $0 == "#" }
        // The type is the range check: a seventh `#` has no `HeadingLevel`,
        // so it stays a paragraph rather than becoming an h7 nothing draws.
        guard let level = HeadingLevel(rawValue: hashes.count) else { return nil }
        let rest = trimmed.dropFirst(hashes.count)
        // The space is what separates a heading from a `#hashtag`, and losing
        // it turns every hashtag in a coach reply into an h1.
        guard rest.first == " " || rest.isEmpty else { return nil }
        var text = rest.trimmingCharacters(in: .whitespaces)
        while text.hasSuffix("#") { text.removeLast() }
        return .heading(level: level, text: InlineMarkdown(text.trimmingCharacters(in: .whitespaces)))
    }

    /// The level a setext underline confers, or `nil` if the line is not one.
    static func setextLevel(_ trimmed: String) -> HeadingLevel? {
        guard let first = trimmed.first, first == "=" || first == "-" else { return nil }
        guard trimmed.allSatisfy({ $0 == first }) else { return nil }
        return first == "=" ? .h1 : .h2
    }

    static func isThematicBreak(_ trimmed: String) -> Bool {
        let stripped = trimmed.filter { !$0.isWhitespace }
        guard stripped.count >= 3, let first = stripped.first else { return false }
        guard first == "-" || first == "*" || first == "_" else { return false }
        return stripped.allSatisfy { $0 == first }
    }

    static func listItem(_ line: String) -> ListItemLine? {
        let indent = line.prefix { $0 == " " || $0 == "\t" }.count
        let body = line.dropFirst(indent)
        guard let first = body.first else { return nil }

        var marker = ListItemLine.Marker.bullet
        var rest: Substring

        if first == "-" || first == "*" || first == "+" {
            rest = body.dropFirst()
            // "- " is a list item; "---" is a rule and "*bold*" is a paragraph.
            guard rest.first == " " else { return nil }
        } else if first.isNumber {
            let digits = body.prefix { $0.isNumber }
            let afterDigits = body.dropFirst(digits.count)
            guard let delimiter = afterDigits.first, delimiter == "." || delimiter == ")" else { return nil }
            rest = afterDigits.dropFirst()
            guard rest.first == " " else { return nil }
            // An ordinal too large for `Int` restarts at 1 rather than becoming
            // a `nil` some later branch has to reinterpret.
            marker = .ordered(ordinal: Int(digits) ?? 1)
        } else {
            return nil
        }

        var text = rest.trimmingCharacters(in: .whitespaces)
        var checked: Bool?
        // GFM task list. `nil` rather than `false` when absent, because "not a
        // task" and "an unfinished task" draw differently.
        if text.hasPrefix("[ ] ") || text == "[ ]" {
            checked = false
            text = String(text.dropFirst(3)).trimmingCharacters(in: .whitespaces)
        } else if text.lowercased().hasPrefix("[x] ") || text.lowercased() == "[x]" {
            checked = true
            text = String(text.dropFirst(3)).trimmingCharacters(in: .whitespaces)
        }
        return ListItemLine(indent: indent, marker: marker, text: text, checked: checked)
    }

    // MARK: - Tables

    /// **The one place backslash escaping is decided.** Splits a row on
    /// unescaped `|` only, so `\|` survives inside a cell — the one piece of
    /// Markdown escaping that shows up in real replies (a column of shell
    /// pipelines or option lists). No trimming and no edge-pipe rule: those are
    /// `splitRow`'s presentation concerns, not the escaping rule.
    ///
    /// `containsUnescapedPipe` is defined in terms of this rather than
    /// re-walking the line, because two readings of one rule can disagree — and
    /// these two already did in spirit, one skipping `\x` and the other
    /// re-emitting its backslash.
    static func rawCells(_ line: String) -> [String] {
        var cells: [String] = []
        var current = ""
        var escaped = false
        for character in line {
            if escaped {
                // `\|` means a literal pipe; any other `\x` keeps its backslash,
                // because Markdown escaping beyond the delimiter is the inline
                // layer's business, not ours.
                current.append(character == "|" ? "|" : "\\")
                if character != "|" { current.append(character) }
                escaped = false
            } else if character == "\\" {
                escaped = true
            } else if character == "|" {
                cells.append(current)
                current = ""
            } else {
                current.append(character)
            }
        }
        // A trailing lone backslash is a literal one; the stream may simply not
        // have delivered the character it escapes yet.
        if escaped { current.append("\\") }
        cells.append(current)
        return cells
    }

    /// A row splits into more than one cell exactly when it holds a delimiter.
    static func containsUnescapedPipe(_ line: String) -> Bool {
        rawCells(line).count > 1
    }

    /// `rawCells` plus the two presentation rules GFM adds: cells are trimmed,
    /// and the empty cells produced by optional leading/trailing pipes are
    /// dropped so `| a | b |` and `a | b` are the same table.
    static func splitRow(_ line: String) -> [String] {
        var cells = rawCells(line)
        if let first = cells.first, first.trimmingCharacters(in: .whitespaces).isEmpty { cells.removeFirst() }
        if let last = cells.last, last.trimmingCharacters(in: .whitespaces).isEmpty { cells.removeLast() }
        return cells.map { $0.trimmingCharacters(in: .whitespaces) }
    }

    static func delimiterAlignments(_ line: String) -> [MarkdownTable.Alignment]? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, trimmed.allSatisfy({ "-:| \t".contains($0) }) else { return nil }
        let cells = splitRow(trimmed)
        guard !cells.isEmpty else { return nil }
        var alignments: [MarkdownTable.Alignment] = []
        for cell in cells {
            let leading = cell.hasPrefix(":")
            let trailing = cell.hasSuffix(":")
            let dashes = cell.trimmingCharacters(in: CharacterSet(charactersIn: ":"))
            guard !dashes.isEmpty, dashes.allSatisfy({ $0 == "-" }) else { return nil }
            // All four states of the colon pair, named. `:---` is explicitly
            // left and a bare `---` is GFM's unspecified default, which is also
            // left — the same outcome, stated twice on purpose rather than
            // shared by falling off the end of an `if`.
            switch (leading, trailing) {
            case (true, true): alignments.append(.center)
            case (false, true): alignments.append(.trailing)
            case (true, false): alignments.append(.leading)
            case (false, false): alignments.append(.leading)
            }
        }
        return alignments
    }

    static func parseTable(_ lines: [String], from index: Int) -> (table: MarkdownTable, nextIndex: Int)? {
        let headers = splitRow(lines[index]).map { InlineMarkdown($0) }
        // A single-column table is legal GFM, and `splitRow` has already
        // dropped its edge pipes — so `| Status |` arrives here as one cell and
        // looks exactly like prose. The raw line is what still tells them
        // apart, which is why both tests are needed rather than either alone.
        guard headers.count > 1 || containsUnescapedPipe(lines[index]) else { return nil }
        guard index + 1 < lines.count,
              let alignments = delimiterAlignments(lines[index + 1]),
              alignments.count == headers.count
        else { return nil }

        var rows: [[InlineMarkdown]] = []
        var cursor = index + 2
        while cursor < lines.count {
            let line = lines[cursor]
            guard !line.trimmingCharacters(in: .whitespaces).isEmpty, containsUnescapedPipe(line) else { break }
            // Ragged rows are not padded here: `MarkdownTable.init` does it for
            // every value of the type, so a row arriving half-written mid-stream
            // is rectangular no matter which construction site produced it.
            rows.append(splitRow(line).map { InlineMarkdown($0) })
            cursor += 1
        }
        return (MarkdownTable(headers: headers, alignments: alignments, rows: rows), cursor)
    }
}

// MARK: - Plain text

/// The plain-text rendering of a parsed message: what the eye saw, with the
/// Markdown syntax gone. This is what the bubble's bare `Copy` action puts on
/// the pasteboard (RFC 125), while `Copy as Markdown` puts the untouched
/// source there and needs no code at all.
///
/// It lives here beside the parser rather than with the copy menu because it is
/// the parser's partner — the same model read in the opposite direction. What
/// it renders is a `MarkdownBlock`, and it would still be the right rendering
/// if nothing in the app ever copied anything; `MessageCopy.swift` is about an
/// affordance, and this is about the model.
///
/// Every inline span goes through `MarkdownAccessibility.plain`, which is the
/// app's single definition of "the rendered characters" — the same function
/// that decides what a VoiceOver student hears from a table cell. Two
/// definitions of that would drift the first time an inline rule changes, and
/// the copy a student pastes would stop matching the reply they read. That
/// call is the one thing in this file that reaches out of Foundation-only
/// code, and it is worth it for exactly that reason.
enum MarkdownPlainText {
    /// Parses `source` and renders it, for the common call site that holds the
    /// raw string a bubble was handed rather than its blocks.
    static func render(_ source: String) -> String {
        render(MarkdownBlock.parse(source))
    }

    /// Blocks separated by exactly one blank line, with no trailing newline.
    ///
    /// `compactMap` rather than `map`, because a thematic rule renders to
    /// *nothing at all* rather than to an empty string: an empty string would
    /// still take its place in the join and leave a doubled blank line where
    /// the `---` used to be.
    ///
    /// The trailing trim is for the code block, the one block whose payload can
    /// legitimately end in a newline — a fence closed on the line after the
    /// last statement. Nobody wants a pasted reply to end in blank lines.
    ///
    /// It drops trailing **whitespace**, not trailing newlines: a code block
    /// whose last line is indented ends in `"\n    "`, and a newline-only trim
    /// halts at the first space and leaves the indent dangling at the end of
    /// the paste.
    static func render(_ blocks: [MarkdownBlock]) -> String {
        let rendered = blocks
            .compactMap(text(of:))
            // A block that renders to nothing is dropped as surely as a rule
            // is. The parser really does produce them — a bare `##` is a
            // heading with empty text, an empty fence is `.code("")` — and left
            // in, each would still claim its place in the join and leave the
            // doubled blank line this separator exists to prevent.
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        return trimmingTrailingWhitespace(rendered)
    }

    /// Trailing whitespace off the end of a string, leaving the interior alone.
    ///
    /// Hand-rolled because Foundation's `trimmingCharacters(in:)` is symmetric
    /// and would eat a deliberate leading indent on the first line of a code
    /// block. Written over `reversed()` rather than by index arithmetic so it
    /// is Unicode-correct on graphemes.
    private static func trimmingTrailingWhitespace(_ string: String) -> String {
        String(string.reversed().drop(while: \.isWhitespace).reversed())
    }

    /// One block's lines, or `nil` for a block that contributes nothing.
    private static func text(of block: MarkdownBlock) -> String? {
        switch block {
        case .heading(_, let text):
            // No `#`, and no level marker of any kind: the levels differ by
            // font on screen, and plain text has no font to differ by.
            return MarkdownAccessibility.plain(text)
        case .paragraph(let text):
            return MarkdownAccessibility.plain(text)
        case .list(let list):
            return list.items.map(line(of:)).joined(separator: "\n")
        case .quote(let lines):
            // The `>` is already off — the parser strips it — and putting it
            // back would be re-introducing syntax into the syntax-free half of
            // this feature. A quote in a coach reply is an aside, and it reads
            // as one without a marker.
            return lines.map { MarkdownAccessibility.plain($0) }.joined(separator: "\n")
        case .code(let code):
            // Verbatim, and no fences: a student copying a command wants the
            // command, and the fence is the one piece of Markdown that would
            // break the paste destination rather than merely clutter it.
            return code
        case .table(let table):
            return lines(of: table)
        case .rule:
            return nil
        }
    }

    /// A table as tab-separated rows, header first.
    ///
    /// Tabs because that is what pastes into Notes, Numbers, Sheets and Mail as
    /// an actual table; ASCII-art alignment would look right only in a
    /// monospaced destination and wrong everywhere else.
    private static func lines(of table: MarkdownTable) -> String {
        ([table.headers] + table.rows)
            .map { row in row.map(field(of:)).joined(separator: "\t") }
            .joined(separator: "\n")
    }

    /// One cell, safe to sit between tabs.
    ///
    /// The delimiter is the whole contract of a tab-separated row, so a cell
    /// that contains one has to lose it: `splitRow` trims only a cell's edges,
    /// so a tab inside an inline-code span survives the parse and would emit a
    /// phantom extra column — every following column in that row shifted by
    /// one, silently, in exactly the spreadsheet this format was chosen for. A
    /// newline would do the same to the row structure. Both collapse to a
    /// single space, which is what the eye saw anyway: the renderer draws a
    /// cell as one wrapped run of text, not as columns within a column.
    private static func field(of cell: InlineMarkdown) -> String {
        MarkdownAccessibility.plain(cell)
            .split(whereSeparator: { $0 == "\t" || $0.isNewline })
            .joined(separator: " ")
    }

    /// One list item: its indent, its marker, then its text.
    ///
    /// Two spaces per level rather than the source's own indent, because the
    /// model deliberately flattened nesting into a `depth` and the column a
    /// coach happened to type is not a thing this side of the parse still
    /// knows. The `\u{2611}` / `\u{2610}` glyphs mirror the filled and empty
    /// circles the view draws, keeping the promise that the plain text is what
    /// the eye saw.
    private static func line(of item: MarkdownList.Item) -> String {
        let marker: String
        switch item.marker {
        case .bullet:
            marker = "\u{2022} "
        case .ordered(let ordinal):
            // The ordinal the parser resolved, not the digit the coach typed:
            // a source that writes `1.` three times draws as 1, 2, 3, and the
            // copy has to say what the screen said.
            marker = "\(ordinal). "
        case .task(let done):
            marker = done ? "\u{2611} " : "\u{2610} "
        }
        // A hard-wrapped item is still ONE item — the parser folded its
        // continuation lines into this item's text with a soft break, and the
        // rule here is one line per item. Left as-is the tail would start at
        // column zero, unindented and unmarked, and read as a separate bullet's
        // worth of prose in whatever the student pasted it into.
        let text = MarkdownAccessibility.plain(item.text)
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .joined(separator: " ")
        return String(repeating: "  ", count: item.depth) + marker + text
    }
}
