import SwiftUI

/// Renders a coach reply's Markdown. Every dimension, colour and font here is a
/// token: DESIGN.md §0 makes the tokens the contract, and §8 forbids inventing
/// visual language — so blocks are separated by hairlines and whitespace, never
/// by a fill, a tint, or a shadow (RFC 118).
struct MarkdownView: View {
    let source: String

    var body: some View {
        // Parsed in the body rather than cached: parsing is O(n) over a few KB,
        // which is microseconds beside the layout pass this view is already
        // doing on every SSE delta.
        let blocks = MarkdownBlock.parse(source)
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func blockView(_ block: MarkdownBlock) -> some View {
        switch block {
        case .heading(let level, let text):
            headingView(level: level, text: text)
        case .paragraph(let text):
            InlineText(markdown: text)
        case .list(let list):
            listView(list)
        case .quote(let lines):
            quoteView(lines)
        case .code(let code):
            codeView(code)
        case .table(let table):
            TableView(table: table)
        case .rule:
            DSHairline()
                .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Headings

    /// The scale tops out at `dsTitle` deliberately: `dsDisplay` is a *screen*
    /// heading (DESIGN.md §4) and a coach reply is not a screen — an `#` inside
    /// a bubble that outranked the screen's own title would invert the app's
    /// hierarchy.
    private func headingView(level: HeadingLevel, text: InlineMarkdown) -> some View {
        // Switched, not `level <= 2`: a ternary partitions an unbounded range
        // and leaves h3…h6 indistinguishable to the reader. Every level the
        // type admits is named, so a third size cannot arrive silently.
        let font: Font
        switch level {
        case .h1, .h2: font = .dsTitle
        case .h3, .h4, .h5, .h6: font = .dsLabel
        }
        return InlineText(markdown: text, font: font)
            .padding(.top, DSSpacing.sm)
            .accessibilityAddTraits(.isHeader)
    }

    // MARK: - Lists

    private func listView(_ list: MarkdownList) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            ForEach(Array(list.items.enumerated()), id: \.offset) { _, item in
                ListItemRow(item: item)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private struct ListItemRow: View {
        let item: MarkdownList.Item
        @ScaledMetric private var markerWidth: CGFloat = DSMarkdown.markerWidth

        var body: some View {
            HStack(alignment: .firstTextBaseline, spacing: DSSpacing.xs) {
                marker
                    // A fixed leading column, so every item's text starts on
                    // the same line however wide its own marker is.
                    .frame(width: markerWidth, alignment: .leading)
                // `InlineText`, not a hand-built chain: this row is precisely
                // where `fixedSize` was missed when the chain was retyped per
                // site, and the item truncated with an ellipsis instead of
                // wrapping. The primitive carries it, so a sixth block type
                // cannot repeat the defect.
                InlineText(markdown: item.text)
            }
            // `DSSpacing.md` directly: a `DSMarkdown.nestIndent` alias for it
            // was indirection that named nothing new.
            .padding(.leading, DSSpacing.md * CGFloat(item.depth))
            .accessibilityElement(children: .combine)
        }

        /// A `switch` with no `if let`: the marker is one union, so there is
        /// no case where a checkbox and a bullet both have a claim on the column
        /// and the view has to pick a winner.
        @ViewBuilder
        private var marker: some View {
            switch item.marker {
            case .task(let done):
                Image(systemName: done ? "checkmark.circle" : "circle")
                    .foregroundStyle(Color.dsTextSecondary)
                    .accessibilityLabel(done ? "Done" : "To do")
            case .bullet:
                markerText("\u{2022}")
            case .ordered(let ordinal):
                // The "." is drawn here, not stored: which delimiter an
                // ordered list wears is this view's decision, and the model
                // keeps the ordinal a consumer might want back.
                markerText("\(ordinal).")
            }
        }

        private func markerText(_ glyph: String) -> some View {
            Text(glyph)
                .font(.dsBody)
                .foregroundStyle(Color.dsTextSecondary)
        }
    }

    // MARK: - Quote

    /// A leading hairline and a gutter — DESIGN.md §8's "separation by 1pt
    /// hairline", not the tinted wash every other Markdown renderer draws,
    /// which would be a fill this design does not have.
    private func quoteView(_ lines: [InlineMarkdown]) -> some View {
        HStack(alignment: .top, spacing: DSSpacing.sm) {
            DSHairline(axis: .vertical)
            VStack(alignment: .leading, spacing: DSSpacing.xs) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    InlineText(markdown: line, color: .dsTextSecondary)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Code

    /// Scrolls rather than wraps: a wrapped line of code loses the indentation
    /// that is carrying its structure. Outlined, never filled — §8 again.
    private func codeView(_ code: String) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Text(code)
                .font(.dsCode)
                .foregroundStyle(Color.dsTextPrimary)
                .padding(DSSpacing.sm)
        }
        .markdownContainer(outlined: true)
    }
}

// MARK: - Tables

/// The widest single-line width each column wants, keyed by column index.
/// Merged with `max` because every cell in a column reports independently: the
/// column's natural width is the widest of them, header included.
private struct TableColumnWidthKey: PreferenceKey {
    static let defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue()) { max($0, $1) }
    }
}

/// How much width the table is actually being given, read off the scroll view
/// itself rather than assumed from a device size.
private struct TableAvailableWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// The complaint that started RFC 118: a GFM table arriving as a wall of
/// unaligned pipes. A `Grid` gives real columns; the horizontal `ScrollView`
/// means a six-column table scrolls instead of truncating, while the two- and
/// three-column tables a coach actually sends fit and never scroll.
///
/// Column widths are **measured, decided, then applied definitely** rather than
/// left to a `minWidth`/`maxWidth` clamp. A clamp is not a width: inside a
/// horizontal scroll view nothing proposes one, so a cell that wraps reported a
/// one-line height and bled over the row beneath it. See `MarkdownTableLayout`
/// for the decision and `measuringLayer` for where the numbers come from.
private struct TableView: View {
    let table: MarkdownTable
    /// Scaled so the grid grows with Dynamic Type rather than clipping it.
    @ScaledMetric private var columnMaxWidth: CGFloat = DSMarkdown.columnMaxWidth
    /// Scaled with it, or the floor would stay put while the text grew and a
    /// short column would clip at accessibility sizes.
    @ScaledMetric private var columnMinWidth: CGFloat = DSMarkdown.columnMinWidth

    /// Filled by the hidden measuring pass below. Empty on the first layout,
    /// which `naturalWidths(count:)` handles rather than collapsing.
    @State private var measuredWidths: [Int: CGFloat] = [:]
    /// Zero until the scroll view has been laid out once.
    @State private var availableWidth: CGFloat = 0
    /// Hysteresis on the width probe. Named rather than spelled inline in the
    /// preference closure because it is a real layout-invalidation policy: this
    /// view re-renders on every SSE delta while a reply streams, and a width
    /// wobbling by a fraction of a point would re-decide the whole grid on each
    /// one for no visible difference.
    private static let availableWidthEpsilon: CGFloat = 0.5

    var body: some View {
        // Widths are decided ONCE per body, not per cell: two cells in the same
        // column resolving separately is how a grid goes ragged.
        let widths = resolvedWidths
        // The indicator is SHOWN, unlike the code block's. It is the only thing
        // telling a student that a column exists off-screen: a captured render
        // of the five-column fixture cut "Status" off the trailing edge with no
        // affordance whatsoever, which is silent data loss rather than a scroll.
        // A hidden indicator on a block whose content is *prose* is fine; on one
        // whose content is a *record* it is not.
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .topLeading, horizontalSpacing: DSSpacing.md, verticalSpacing: DSSpacing.sm) {
                GridRow {
                    ForEach(Array(table.headers.enumerated()), id: \.offset) { index, header in
                        cell(header, column: index, width: widths[index], font: .dsLabel, color: .dsTextPrimary)
                    }
                }
                rowSeparator
                ForEach(Array(table.rows.enumerated()), id: \.offset) { rowIndex, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { index, value in
                            cell(
                                value,
                                column: index,
                                width: widths[index],
                                font: .dsBody,
                                color: .dsTextPrimary,
                                // VoiceOver reads "College: Michigan" rather
                                // than an orphaned "Michigan" three columns
                                // from the header that explains it.
                                label: accessibilityLabel(column: index, value: value)
                            )
                        }
                    }
                    if rowIndex < table.rows.count - 1 { rowSeparator }
                }
            }
            .padding(DSSpacing.sm)
        }
        // Both probes hang off the scroll view as backgrounds: a background is
        // proposed its parent's size but never contributes to it, so neither
        // measurement can feed back into the layout it is measuring.
        .background(alignment: .topLeading) { measuringLayer }
        .background {
            GeometryReader { proxy in
                Color.clear.preference(key: TableAvailableWidthKey.self, value: proxy.size.width)
            }
        }
        .onPreferenceChange(TableColumnWidthKey.self) { measured in
            measuredWidths = measured
        }
        .onPreferenceChange(TableAvailableWidthKey.self) { width in
            // Sub-point noise is dropped rather than assigned. This view
            // re-renders on every SSE delta while a reply streams; a width that
            // wobbles by a fraction of a point would invalidate the layout on
            // each one for no visible difference.
            if abs(width - availableWidth) > Self.availableWidthEpsilon { availableWidth = width }
        }
        // Clipped to the bubble's own radius, so a scrolled table still reads
        // as contained rather than as content escaping the bubble. Not
        // outlined: the table's own header hairline already bounds it, and a
        // second rule around a scrolling grid reads as a nested box.
        .markdownContainer(outlined: false)
    }

    private var columnCount: Int { max(table.headers.count, 1) }

    /// The measured widths as a dense array, with the ceiling standing in for
    /// any column that has not reported yet.
    ///
    /// The ceiling, and not the floor, is the stand-in on purpose: for the one
    /// frame before the measuring pass lands, a cell at `columnMaxWidth` is
    /// exactly what this view drew before this change, whereas a cell at the
    /// floor is the collapsed-to-64pt grid that a zero-returning measuring pass
    /// produced during development. Failing towards the previous render is the
    /// cheaper mistake.
    private func naturalWidths(count: Int) -> [CGFloat] {
        (0 ..< count).map { measuredWidths[$0] ?? columnMaxWidth }
    }

    private var resolvedWidths: [CGFloat] {
        MarkdownTableLayout.columnWidths(
            natural: naturalWidths(count: columnCount),
            // The grid is inset inside the scroll view, so the width the
            // columns may occupy is the scroll view's less both insets —
            // forgetting them is how a table that "fits" scrolls by 16pt.
            available: availableWidth > 0 ? availableWidth - 2 * DSSpacing.sm : 0,
            spacing: DSSpacing.md,
            minimum: columnMinWidth,
            maximum: columnMaxWidth
        )
    }

    /// Every cell drawn once more, hidden, at its **single-line ideal** width,
    /// reporting that width for its column.
    ///
    /// `fixedSize()` is what makes this a measurement rather than a second
    /// layout: the text ignores whatever proposal this hidden layer is given,
    /// so what comes back cannot depend on the widths this view is in the
    /// middle of deciding. That is the guard against the classic
    /// preference → `@State` → layout → preference loop, which on this view
    /// would re-run on every SSE delta of a streaming reply.
    ///
    /// Measured in SwiftUI with the same `Font` tokens the real cells use,
    /// rather than by mapping a token onto a `UIFont` and calling
    /// `boundingRect` — that mapping is a second, silent copy of the type scale
    /// and would drift from `Theme.swift` the first time a token changed
    /// (DESIGN.md §0).
    private var measuringLayer: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(table.headers.enumerated()), id: \.offset) { index, header in
                measured(header, column: index, font: .dsLabel)
            }
            ForEach(Array(table.rows.enumerated()), id: \.offset) { _, row in
                ForEach(Array(row.enumerated()), id: \.offset) { index, value in
                    measured(value, column: index, font: .dsBody)
                }
            }
        }
        .hidden()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func measured(_ text: InlineMarkdown, column: Int, font: Font) -> some View {
        Text(MarkdownInline.attributed(text))
            .font(font)
            // `lineLimit(1)` with `fixedSize()`: the ideal width of the whole
            // string on one line, which is the number "does this column need to
            // wrap?" is asking about.
            .lineLimit(1)
            .fixedSize()
            .background {
                GeometryReader { proxy in
                    Color.clear.preference(
                        key: TableColumnWidthKey.self,
                        // Rounded up: a fractional shortfall is enough to wrap
                        // the last word of a cell that was measured to fit,
                        // which is the whole defect this change removes.
                        value: [column: proxy.size.width.rounded(.up)]
                    )
                }
            }
    }

    /// Rows are separated by the same 1pt `FieldBorder` hairline everything
    /// else in this design uses — no fills and no zebra striping (§8).
    private var rowSeparator: some View {
        DSHairline()
            .gridCellColumns(max(table.headers.count, 1))
    }

    /// `table.headers[column]` unguarded: `MarkdownTable` normalises every row
    /// to `headers.count` in its only initialiser, so a cell's column index is
    /// a header index by type rather than by the parser's good behaviour.
    private func accessibilityLabel(column: Int, value: InlineMarkdown) -> String {
        MarkdownAccessibility.cellLabel(header: table.headers[column], value: value)
    }

    private func cell(
        _ text: InlineMarkdown,
        column: Int,
        width: CGFloat,
        font: Font,
        color: Color,
        label: String? = nil
    ) -> some View {
        let alignment = table.alignments[column]
        return Text(MarkdownInline.attributed(text))
            .font(font)
            .foregroundStyle(color)
            .multilineTextAlignment(alignment.textAlignment)
            // Order is load-bearing. `fixedSize` is INSIDE a `frame(width:)`,
            // so the text is proposed a DEFINITE width and reports the height
            // of however many lines it wraps onto. With the old
            // `frame(minWidth:maxWidth:)` the proposal inside the horizontal
            // scroll view was unspecified, so the text reported its
            // single-line height, `fixedSize` pinned it, and the clamped width
            // then wrapped the text to two lines inside a one-line-tall row —
            // the second line drawing over the separator and the row below it.
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: width, alignment: alignment.frameAlignment)
            // A header cell has no header to prefix, so it speaks its own
            // rendered text — never the source, which is the `**` this RFC
            // exists to hide.
            .accessibilityLabel(label ?? MarkdownAccessibility.plain(text))
    }
}

/// The arithmetic behind a table's column widths, with **no SwiftUI in it**.
///
/// This exists as a value-returning function for the same reason the parser
/// does: `bin/test` never compiles `ios-app`, so an XCTest assertion is the
/// only mechanical authority this feature can have, and a rule buried in a
/// `View.body` has none. The measuring pass and the drawing are the parts a
/// test cannot reach; the *decision* is not, so it lives here.
///
/// The defect it exists to prevent (RFC 118 follow-up): inside
/// `ScrollView(.horizontal)` the width proposal is **unspecified**, so a `Text`
/// reports its single-line ideal height, `fixedSize(vertical:)` pins that
/// height, and a `maxWidth` clamp then forces the text to wrap to two lines
/// inside a one-line-tall row — the second line drawing over the separator and
/// the row beneath it. The cure is a *definite* width per column, and a
/// definite width has to be computed from somewhere. Here.
enum MarkdownTableLayout {
    /// Definite widths for `natural.count` columns.
    ///
    /// - Parameters:
    ///   - natural: each column's single-line ideal width (its widest cell,
    ///     header included), as measured by the view.
    ///   - available: width the table may occupy before it has to scroll. Pass
    ///     `<= 0` when it is not known yet (the first layout pass, or an
    ///     unspecified proposal): the columns then take their natural widths
    ///     and the horizontal `ScrollView` absorbs any overflow, which is the
    ///     same answer as "it fits" and so cannot oscillate.
    ///   - spacing: the gutter *between* columns; `n - 1` of them.
    ///   - minimum: floor, so a one-word column ("Yes") keeps a usable width.
    ///   - maximum: ceiling, so a prose column wraps instead of monopolising
    ///     the row.
    ///
    /// Rules, in order:
    ///   1. Every column is clamped into `minimum ... maximum` first. A column
    ///      is never *stretched* to fill leftover room — a two-column table
    ///      pulled out to the bubble's full width reads as a layout accident,
    ///      and the pre-existing render that Ian is happy with is content-sized.
    ///   2. If the clamped widths fit `available`, they are the answer.
    ///   3. Otherwise the table SHRINKS TO FIT rather than scrolling. Flagged
    ///      honestly: this is layout *policy*, and the row-height defect did not
    ///      ask for it — definite clamped-natural widths alone cure the bleed,
    ///      and the scroll view with a shown indicator was already the design's
    ///      answer to overflow. It is here because without it Ian's two-column
    ///      table overruns the bubble by ~8pt and scrolls for the sake of eight
    ///      points, which reads as broken. Kept deliberately, not accidentally;
    ///      delete this branch (and `available`, `spacing`, and the width probe
    ///      that feeds them) if that trade is ever judged the wrong one.
    ///
    ///      The deficit is taken as a **waterfall**: the column with
    ///      the most slack above `minimum` pays first and is exhausted down to
    ///      the floor before the next-widest is touched at all. Slack is the
    ///      budget for shrinking, and the column holding the most of it is the
    ///      one whose text least needs the pixels — a wide prose column
    ///      reflows gracefully where a narrow label column does not. Spending
    ///      the deficit where it is cheapest is what "shrink to fit" should
    ///      mean.
    ///
    ///      Explicitly **not** proportional, which is what this shipped as
    ///      first and what the second captured render caught: sharing an 8pt
    ///      deficit by slack took 7pt from a 220pt column and 1pt from an 85pt
    ///      one — but the 85pt column was sitting exactly at its natural width,
    ///      so that single point wrapped "Public (CC)" onto a second line for
    ///      nothing. Proportional shrink takes a little from every column, and
    ///      a column at its natural width wraps the instant you take anything
    ///      at all.
    ///   4. If even all-columns-at-`minimum` does not fit, the *clamped* widths
    ///      are returned and the table scrolls. Shrinking is only ever worth
    ///      the legibility it costs if it removes the scroll; once the table is
    ///      going to scroll anyway, squeezing buys nothing and charges for it —
    ///      a captured five-column render squeezed to the floor hyphenated
    ///      "Michigan" into "Mi-chigan" **and** still scrolled. The shown
    ///      scroll indicator is the affordance for the off-screen column.
    static func columnWidths(
        natural: [CGFloat],
        available: CGFloat,
        spacing: CGFloat,
        minimum: CGFloat,
        maximum: CGFloat
    ) -> [CGFloat] {
        guard !natural.isEmpty else { return [] }
        // `max(minimum, ...)` last so a `maximum` mistakenly below `minimum`
        // degrades to the floor rather than to something narrower than either.
        let clamped = natural.map { Swift.max(minimum, Swift.min(maximum, $0)) }
        let gutters = spacing * CGFloat(natural.count - 1)
        let content = available - gutters
        guard available > 0 else { return clamped }

        let total = clamped.reduce(0, +)
        guard total > content else { return clamped }

        // `<=`, mirroring rule 2's `total > content`: a table that seats
        // exactly at its floors DOES fit, and shrinking it removes the scroll,
        // which is the whole justification for shrinking. Spelling this `<`
        // made the same predicate mean "fits" twelve lines up and "does not
        // fit" here, and silently scrolled a table that would have seated
        // perfectly.
        let floors = minimum * CGFloat(natural.count)
        guard floors <= content else { return clamped }

        // Widest slack first, exhausted before the next column is touched. A
        // column already at the floor has no slack, so it is never reached and
        // never squeezed — and neither is a column the deficit runs out before.
        //
        // Ties break on column index rather than on whatever order a
        // dictionary or a sort happened to produce. Two equally slack columns
        // must always yield the same grid: a table whose layout depended on
        // hash order would redraw differently on each SSE delta of the same
        // reply.
        let order = clamped.indices.sorted { first, second in
            let slack = (clamped[first] - minimum, clamped[second] - minimum)
            return slack.0 == slack.1 ? first < second : slack.0 > slack.1
        }
        var widths = clamped
        // `floors < content` above guarantees the total slack exceeds the
        // deficit, so this always reaches zero before it runs out of columns.
        var remaining = total - content
        for index in order where remaining > 0 {
            let paid = Swift.min(widths[index] - minimum, remaining)
            widths[index] -= paid
            remaining -= paid
        }
        return widths
    }
}

/// The model's alignment mapped across to SwiftUI's two spellings of it. Held
/// as properties on the enum rather than as twin lookups in the view: those
/// were the same bounds guard, the same default and the same case order written
/// twice, so a change to one silently moved the frame without the text.
private extension MarkdownTable.Alignment {
    var frameAlignment: Alignment {
        switch self {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }

    var textAlignment: TextAlignment {
        switch self {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }
}

// MARK: - Accessibility

/// Accessible text for the cells `MarkdownView` draws, built from the
/// **rendered** characters rather than the source.
///
/// This is the whole point of RFC 118 carried into the audio channel: a
/// VoiceOver student must not hear "star star Draft star star" for the bold
/// cell a sighted student now sees as bold. Every other block gets its
/// accessible text for free, because SwiftUI speaks the `AttributedString`
/// `InlineText` already renders; a table cell is the one site that overrides
/// the label, so it is the one site that has to strip the syntax itself.
///
/// Internal rather than private to `TableView` so the suite can assert it —
/// the view is not compiled by any test, and an accessibility label with no
/// mechanical authority is exactly what regressed here once already.
enum MarkdownAccessibility {
    /// The characters the eye sees, with the inline Markdown removed.
    static func plain(_ markdown: InlineMarkdown) -> String {
        String(MarkdownInline.attributed(markdown).characters)
    }

    /// "College: Michigan" — the header restores the meaning a cell loses when
    /// it is read three columns away from the row that explains it. A headerless
    /// column speaks the value alone rather than a stray ": ".
    static func cellLabel(header: InlineMarkdown, value: InlineMarkdown) -> String {
        let header = plain(header)
        let value = plain(value)
        return header.isEmpty ? value : "\(header): \(value)"
    }
}

// MARK: - Shared primitives

/// Every span of inline Markdown this renderer draws: attributed, tokenised,
/// full width and leading, and **allowed to wrap**.
///
/// One view rather than a chain retyped per block. `fixedSize` vertically is
/// the load-bearing part and the reason this exists: inside an `HStack` a
/// `Text` reports its single-line ideal width, is proposed less, and truncates
/// with an ellipsis instead of wrapping — a coach's list item silently losing
/// its tail. That is not hypothetical drift; it shipped on the list row while
/// the other four sites carried the modifier.
private struct InlineText: View {
    let markdown: InlineMarkdown
    var font: Font = .dsBody
    var color: Color = .dsTextPrimary

    var body: some View {
        Text(MarkdownInline.attributed(markdown))
            .font(font)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private extension View {
    /// A block container: clipped to the control radius, optionally outlined
    /// with the §8 hairline, **never filled**. The code block and the table
    /// share one container decision; spelling `RoundedRectangle` at each site
    /// let them agree by coincidence rather than by construction.
    func markdownContainer(outlined: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: DSRadius.control, style: .continuous)
        return clipShape(shape)
            .overlay {
                if outlined {
                    shape.stroke(Color.dsFieldBorder, lineWidth: DSControl.borderWidth)
                }
            }
    }
}


/// The worst-case coach reply: an h2, bold prose, a nested bullet list, a
/// fenced block, and a five-column table — every block type this renderer
/// draws, in one message. It lives in the app target rather than in the test
/// target because both the `#Preview`s below and `MarkdownParserTests` use it:
/// the fixture the eye checks and the fixture the suite checks must be the same
/// string, or the preview quietly stops exercising the hard case.
enum MarkdownFixture {
    static let worstCaseReply = """
    ## Your next steps

    You have **three deadlines** inside the next month, and the _Common App_ \
    essay is the one that gates the rest.

    - Applications
      - Michigan — supplement not started
      - Purdue — essay in draft
    - Financial aid
      - [x] FSA ID created
      - [ ] FAFSA submitted

    | College | Deadline | Type | Essays | Status |
    | --- | --- | :---: | ---: | --- |
    | Michigan | Nov 1 | EA | 2 | Draft |
    | Purdue | Nov 15 | EA | 1 | Not started |
    | Illinois | Dec 1 | RD | 0 | Submitted |

    Run this to check your list against the deadlines:

    ```sh
    bin/deadlines --within 30d \\
        --school michigan
    ```

    > Anything you submit early can still be revised until the deadline.

    See [the Common App checklist](https://example.com) when you start.
    """
}

#Preview("markdown - Light") {
    ScrollView { MarkdownView(source: MarkdownFixture.worstCaseReply).padding(DSSpacing.md) }
        .preferredColorScheme(.light)
}

#Preview("markdown - Dark") {
    ScrollView { MarkdownView(source: MarkdownFixture.worstCaseReply).padding(DSSpacing.md) }
        .preferredColorScheme(.dark)
}
