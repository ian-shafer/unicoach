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

    /// Wraps rather than scrolls (RFC 120). A wrapped line of code loses the
    /// horizontal alignment that was carrying some of its structure, which is a
    /// real cost — but the alternative shipped as `showsIndicators: false`, so a
    /// long line ran off the trailing edge with no affordance at all and the
    /// text was simply unreachable. Losing an indent beats losing the line.
    /// Outlined, never filled — §8 again.
    ///
    /// Drawn through `InlineText` like every other span, because the wrap chain
    /// this needs — a definite full width to wrap into, `fixedSize` vertically
    /// so every wrapped line is reported — is exactly what that primitive
    /// exists to own, and retyping it is how a site loses half of it.
    /// `AttributedString(code)`, not `InlineMarkdown`: a fenced payload is
    /// verbatim, so its `*` and `_` must stay characters and never be parsed.
    private func codeView(_ code: String) -> some View {
        var string = AttributedString(code)
        string.font = .dsCode
        string.foregroundColor = .dsTextPrimary
        return InlineText(attributed: string)
            .padding(DSSpacing.sm)
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
/// unaligned pipes. A `Grid` gives real columns for the two- and three-column
/// tables a coach actually sends; a table whose columns cannot all stay legible
/// side by side is drawn as **one block per row** instead (RFC 120), so no
/// column is ever hidden behind a horizontal gesture.
///
/// The measuring pass and the width probe stay exactly where RFC 118 put them —
/// they are what DECIDES which of the two layouts this table gets. Only the
/// horizontal `ScrollView` is gone.
///
/// In the grid, column widths are **measured, decided, then applied definitely**
/// rather than left to a `minWidth`/`maxWidth` clamp. A clamp is not a width: if
/// nothing proposes one, a cell that wraps reports a one-line height and bleeds
/// over the row beneath it. See `MarkdownTableLayout` for the decision and
/// `measuringLayer` for where the numbers come from.
private struct TableView: View {
    let table: MarkdownTable
    /// Scaled so the grid grows with Dynamic Type rather than clipping it.
    @ScaledMetric private var columnMaxWidth: CGFloat = DSMarkdown.columnMaxWidth
    /// Scaled with it, or the threshold would stay put while the text grew and
    /// a table that only just fits at default sizes would keep its grid while
    /// its cells clipped at accessibility sizes.
    @ScaledMetric private var columnMinWidth: CGFloat = DSMarkdown.columnMinWidth

    /// Filled by the hidden measuring pass below. Empty on the first layout,
    /// which `resolvedLayout` handles rather than collapsing.
    @State private var measuredWidths: [Int: CGFloat] = [:]
    /// Zero until the view has been laid out once.
    @State private var availableWidth: CGFloat = 0
    /// Hysteresis on the width probe. Named rather than spelled inline in the
    /// preference closure because it is a real layout-invalidation policy: this
    /// view re-renders on every SSE delta while a reply streams, and a width
    /// wobbling by a fraction of a point would re-decide the whole table on
    /// each one for no visible difference.
    private static let availableWidthEpsilon: CGFloat = 0.5

    var body: some View {
        // `OfferedWidth`, not a plain `frame(maxWidth: .infinity)`: the width
        // probe below measures whatever this view reports, and a flexible frame
        // reports its CHILD's width when the child is wider than the proposal.
        //
        // That is a feedback loop with a stable wrong answer, and it was live in
        // the first capture of this change: the five-column grid overran at
        // 520pt, the probe reported 520 as the width "available", `layout`
        // concluded the grid fitted, and the whole reply drew wider than the
        // screen with "Status" cut off — the exact defect RFC 120 removes.
        // RFC 118 never saw it because a `ScrollView` clamps to its proposal
        // for free; that clamp is the one thing worth keeping from it.
        OfferedWidth {
            // The layout is decided ONCE per body, not per cell: two cells in
            // the same column resolving separately is how a grid goes ragged.
            layoutView(resolvedLayout)
        }
        // Both probes hang off the clamped container as backgrounds: a
        // background is proposed its parent's size but never contributes to it,
        // so neither measurement can feed back into the layout it is measuring.
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
        // Clipped to the bubble's own radius, so the table reads as contained
        // rather than as content escaping the bubble. Not outlined: the table's
        // own hairlines already bound it, and a second rule around a grid reads
        // as a nested box.
        .markdownContainer(outlined: false)
    }

    @ViewBuilder
    private func layoutView(_ layout: MarkdownTableLayout.Layout) -> some View {
        switch layout {
        case .grid(let widths): gridView(widths)
        case .stacked: StackedTableView(table: table)
        }
    }

    // MARK: Grid

    private func gridView(_ widths: [CGFloat]) -> some View {
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
                            // VoiceOver reads "College: Michigan" rather than
                            // an orphaned "Michigan" three columns from the
                            // header that explains it.
                            label: accessibilityLabel(column: index, value: value)
                        )
                    }
                }
                if !table.isLastRow(rowIndex) { rowSeparator }
            }
        }
        .padding(DSSpacing.sm)
    }

    private var columnCount: Int { max(table.headers.count, 1) }

    /// The measured widths as a dense array, with the ceiling standing in for
    /// any column that has not reported yet.
    ///
    /// The ceiling, and not the floor, is the stand-in on purpose: for the one
    /// frame before the measuring pass lands, a cell at `columnMaxWidth` is
    /// exactly what this view drew before this change, whereas a cell at the
    /// floor is the collapsed grid that a zero-returning measuring pass
    /// produced during development. Failing towards the previous render is the
    /// cheaper mistake.
    private func naturalWidths(count: Int) -> [CGFloat] {
        (0 ..< count).map { measuredWidths[$0] ?? columnMaxWidth }
    }

    /// The width the columns may occupy, or `nil` for "not known yet" — the
    /// unknown that `layout` answers `.grid` for.
    ///
    /// Withheld until the measuring pass has reported: against ceiling-wide
    /// stand-ins a real width stacks every table of three or more columns for
    /// one frame, and a table that visibly reassembles itself is worse than one
    /// briefly drawn at ceiling widths and clipped.
    ///
    /// The content is inset by `DSSpacing.sm` on both sides, so the columns get
    /// the container's width less both insets — forgetting them is how a table
    /// that "fits" overruns by 16pt.
    ///
    /// A property rather than a ternary in the `available:` argument, for the
    /// same reason `availableWidthEpsilon` has a name: these are three layout
    /// policies — a readiness gate, an unknown width, and the inset arithmetic
    /// — and a call site whose job is to call `layout` should not be carrying
    /// them unnamed.
    private var availableContentWidth: CGFloat? {
        guard !measuredWidths.isEmpty, availableWidth > 0 else { return nil }
        return availableWidth - 2 * DSSpacing.sm
    }

    private var resolvedLayout: MarkdownTableLayout.Layout {
        MarkdownTableLayout.layout(
            natural: naturalWidths(count: columnCount),
            available: availableContentWidth,
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
        // Stamped through `styled`, like the cell it stands in for: the
        // measuring pass must measure the string the grid actually draws, and
        // two spellings of "apply the base style" are two chances to drift.
        Text(MarkdownInline.styled(text, font: font, color: .dsTextPrimary))
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
        return Text(MarkdownInline.styled(text, font: font, color: color))
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

/// One block per row, separated by the same 1pt hairline the grid uses — no
/// fills, no cards, no second visual language (§8).
///
/// A type of its own rather than a branch of `TableView`: a stacked table is
/// laid out by the width it is given, so it needs none of the measuring pass,
/// the width probe or the column bounds that `TableView` exists to run. Two
/// renderings sharing one `self` is how that apparatus leaks into the layout
/// that explicitly has no widths.
///
/// The headers are not repeated as a row of their own here: in a stack the
/// header travels *with* each value, which is the only way a field stays
/// self-describing once the column that explained it is gone.
private struct StackedTableView: View {
    let table: MarkdownTable

    var body: some View {
        VStack(alignment: .leading, spacing: DSSpacing.sm) {
            ForEach(Array(table.rows.enumerated()), id: \.offset) { rowIndex, row in
                stackedRow(row)
                if !table.isLastRow(rowIndex) { DSHairline() }
            }
        }
        .padding(DSSpacing.sm)
    }

    /// A row as a heading and its remaining fields.
    ///
    /// `children: .combine` is the accessibility win RFC 118 wanted and could
    /// not have from `Grid`: VoiceOver speaks one element per row — "Michigan,
    /// Deadline Nov 1, Type EA" — instead of five cells the student has to
    /// swipe between and reassemble.
    private func stackedRow(_ row: [InlineMarkdown]) -> some View {
        VStack(alignment: .leading, spacing: DSSpacing.xs) {
            // The FIRST column's value heads the row: it is the identifying
            // field in every coach table seen so far ("University of Maine at
            // Presque Isle"), so it is what tells the student which record the
            // fields below belong to.
            if let heading = row.first {
                InlineText(markdown: heading)
                    // The header travels with column 0 too — just not visibly.
                    // On screen the heading titles the block and a "School: "
                    // prefix would be noise; in the audio channel there is no
                    // such context, and `children: .combine` would otherwise
                    // open the row with a bare "Yes" or "Nov 1" — the orphaned
                    // reading the grid path labels its way out of.
                    // `table.headers[0]` unguarded: a row is fitted to
                    // `headers.count`, so a row with a first cell has a first
                    // header.
                    .accessibilityLabel(
                        MarkdownAccessibility.cellLabel(header: table.headers[0], value: heading)
                    )
            }
            ForEach(Array(row.enumerated().dropFirst()), id: \.offset) { index, value in
                // ONE `AttributedString` in ONE `Text` — not an `HStack` of a
                // header `Text` and a value `Text`, and with no width frame of
                // its own. This is the lesson of this feature's two worst bugs:
                // an `HStack` of texts in an indefinite-width parent is what
                // truncated a list item with an ellipsis, and a `maxWidth`
                // clamp with no definite width is what made a wrapped cell
                // bleed into the row below. A single `Text` has neither failure
                // mode — it wraps correctly under any proposal, with no
                // measurement and no frame arithmetic.
                // `table.headers[index]` unguarded, exactly like the grid's
                // `accessibilityLabel(column:)`: `MarkdownTable`'s only
                // initialiser fits every row to `headers.count`, so a row index
                // is always a header index.
                InlineText(attributed: Self.fieldLine(header: table.headers[index], value: value))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// "Deadline  Nov 1" as one string: the header run at `dsCaption` /
    /// `TextSecondary`, the value at `dsBody` / `TextPrimary`. Type and colour
    /// carry the distinction, so no punctuation has to — and no second `Text`.
    private static func fieldLine(header: InlineMarkdown, value: InlineMarkdown) -> AttributedString {
        let value = MarkdownInline.styled(value, font: .dsBody, color: .dsTextPrimary)
        // A headerless column speaks its value alone rather than opening with a
        // stray separator — asked of the one predicate `MarkdownAccessibility`
        // answers for both channels, so "headerless" cannot come to mean two
        // things.
        guard MarkdownAccessibility.hasHeader(header) else { return value }
        var line = MarkdownInline.styled(header, font: .dsCaption, color: .dsTextSecondary)
        var separator = AttributedString(fieldSeparator)
        separator.font = .dsCaption
        separator.foregroundColor = .dsTextSecondary
        line.append(separator)
        line.append(value)
        return line
    }

    /// Wide enough to read as a gap between two fields rather than as one
    /// phrase, without inventing a colon the table never had.
    private static let fieldSeparator = "\u{2002}\u{2002}"
}

/// Takes the width it is OFFERED and gives its child that width, whatever the
/// child would have preferred.
///
/// SwiftUI has no built-in spelling of this. `frame(maxWidth: .infinity)` grows
/// to an oversized child, and the only stock container that clamps is a
/// `ScrollView` — which is precisely what RFC 120 removed. Without a clamp the
/// table's own overflow propagates up the stack and is then read back as the
/// space "available" to the table, so an oversized grid proves itself to fit.
///
/// The height is the child's own at the offered width, so a stacked table still
/// reports every line it wrapped onto. Anything the child still draws outside
/// those bounds is clipped by `markdownContainer`.
///
/// The `VStack` inside is not decoration: a `Layout` is handed one subview per view
/// its builder produced, and a container whose whole contract is "clamp my
/// child" has no way to SAY it has exactly one — a caller who adds a sibling
/// gets it silently dropped. Collapsing the builder's output here means the
/// layout below always has exactly one subview to clamp, so the arity is a
/// property of the type rather than of a `subviews.first`.
private struct OfferedWidth<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        OfferedWidthLayout {
            VStack(alignment: .leading, spacing: 0) { content }
        }
    }
}

/// The clamp itself. Reached only through `OfferedWidth` above, which is what
/// guarantees the `subviews.first` here is the only subview there is.
private struct OfferedWidthLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        // An unspecified proposal (a sizing pass, not a layout) falls back to
        // the child's own ideal width: there is no offer to clamp to, and
        // reporting zero would collapse the table for a frame.
        let width = proposal.width ?? child.sizeThatFits(proposal).width
        return CGSize(width: width, height: child.sizeThatFits(ProposedViewSize(width: width, height: proposal.height)).height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(
            at: CGPoint(x: bounds.minX, y: bounds.minY),
            anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: nil)
        )
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
/// The defect it exists to prevent (RFC 118 follow-up): with no definite width
/// proposed to it a `Text` reports its single-line ideal height,
/// `fixedSize(vertical:)` pins that height, and a `maxWidth` clamp then forces
/// the text to wrap to two lines inside a one-line-tall row — the second line
/// drawing over the separator and the row beneath it. The cure is a *definite*
/// width per column, and a definite width has to be computed from somewhere.
/// Here.
enum MarkdownTableLayout {
    /// How a table should be drawn: as a grid, or not as a grid at all.
    ///
    /// Returning a decision rather than widths is RFC 120's whole change. The
    /// old signature could only ever answer "here are the widths", so the case
    /// where no set of widths is honest — five columns that cannot all reach
    /// the floor — had to be answered with widths anyway and a horizontal
    /// `ScrollView` to absorb them. That hid a column behind a gesture. The
    /// type now admits the second answer, so the view can draw a different
    /// layout instead of drawing a bad grid.
    enum Layout: Equatable {
        /// One definite width per column, in column order.
        case grid([CGFloat])
        /// The columns cannot all be legible side by side; draw one block per
        /// row instead. Carries no widths on purpose — a stacked row is laid
        /// out by the parent's own width, which is the one width nobody has to
        /// measure.
        case stacked
    }

    /// Decides how `natural.count` columns should be drawn.
    ///
    /// - Parameters:
    ///   - natural: each column's single-line ideal width (its widest cell,
    ///     header included), as measured by the view.
    ///   - available: width the table may occupy, or `nil` when it is not known
    ///     yet (the first layout pass, or an unspecified proposal) — absence
    ///     rather than a `<= 0` sentinel, so a container genuinely offered 0pt
    ///     is a different value from one that has not been measured. The
    ///     columns then take their natural widths and the answer is `.grid`,
    ///     which is the same answer as "it fits" and so cannot flicker into a
    ///     stacked layout for one frame before flicking back.
    ///   - spacing: the gutter *between* columns; `n - 1` of them.
    ///   - minimum: the grid/stack threshold — the narrowest a column may be
    ///     and still read as a column. A table that cannot give every column
    ///     this much stacks.
    ///   - maximum: ceiling, so a prose column wraps instead of monopolising
    ///     the row.
    ///
    /// Rules, in order:
    ///   1. Every column is clamped into `minimum ... maximum` first. A column
    ///      is never *stretched* to fill leftover room — a two-column table
    ///      pulled out to the bubble's full width reads as a layout accident,
    ///      and the pre-existing render that Ian is happy with is content-sized.
    ///   2. If the clamped widths fit `available`, they are the answer.
    ///   3. Otherwise the table SHRINKS TO FIT. The deficit is taken as a
    ///      **waterfall**: the column with the most slack above `minimum` pays
    ///      first and is exhausted down to the floor before the next-widest is
    ///      touched at all. Slack is the budget for shrinking, and the column
    ///      holding the most of it is the one whose text least needs the
    ///      pixels — a wide prose column reflows gracefully where a narrow
    ///      label column does not. Spending the deficit where it is cheapest is
    ///      what "shrink to fit" should mean.
    ///
    ///      Explicitly **not** proportional, which is what this shipped as
    ///      first and what a captured render caught: sharing an 8pt deficit by
    ///      slack took 7pt from a 220pt column and 1pt from an 85pt one — but
    ///      the 85pt column was sitting exactly at its natural width, so that
    ///      single point wrapped "Public (CC)" onto a second line for nothing.
    ///      Proportional shrink takes a little from every column, and a column
    ///      at its natural width wraps the instant you take anything at all.
    ///   4. If even all-columns-at-`minimum` does not fit, there is no honest
    ///      grid and the table **stacks**. This is the branch RFC 120 changed:
    ///      it used to return the clamped widths and let a horizontal
    ///      `ScrollView` absorb them, which meant the steady state of a
    ///      five-column table was a column silently off-screen behind a fading
    ///      indicator. Squeezing instead is no better — a captured render
    ///      squeezed to a 64pt floor hyphenated "Michigan" into "Mi-chigan"
    ///      *and* still scrolled. A table that cannot be a grid should stop
    ///      pretending to be one.
    static func layout(
        natural: [CGFloat],
        available: CGFloat?,
        spacing: CGFloat,
        minimum: CGFloat,
        maximum: CGFloat
    ) -> Layout {
        guard !natural.isEmpty else { return .grid([]) }
        // `max(minimum, ...)` last so a `maximum` mistakenly below `minimum`
        // degrades to the floor rather than to something narrower than either.
        let clamped = natural.map { Swift.max(minimum, Swift.min(maximum, $0)) }
        // Only ABSENCE is the unknown. A container that really is 0pt wide
        // falls through into the arithmetic below and stacks, because there is
        // no honest grid at 0pt — that is the state the old `<= 0` sentinel
        // could not tell apart from "nobody has measured yet".
        guard let available else { return .grid(clamped) }
        let gutters = spacing * CGFloat(natural.count - 1)
        let content = available - gutters

        let total = clamped.reduce(0, +)
        guard total > content else { return .grid(clamped) }

        // `<=`, mirroring rule 2's `total > content`: a table that seats
        // exactly at its floors DOES fit, and shrinking it keeps it a grid,
        // which is the whole justification for shrinking. Spelling this `<`
        // made the same predicate mean "fits" twelve lines up and "does not
        // fit" here, and silently stacked a table that would have seated
        // perfectly.
        let floors = minimum * CGFloat(natural.count)
        guard floors <= content else { return .stacked }
        return .grid(shrunkToFit(clamped, content: content, minimum: minimum))
    }

    /// The deficit taken as a **waterfall**: the column with the most slack
    /// above `minimum` pays first and is exhausted down to the floor before the
    /// next-widest is touched at all.
    ///
    /// Ties break on column index rather than on whatever order a sort happened
    /// to produce. Two equally slack columns must always yield the same grid: a
    /// table whose layout depended on that order would redraw differently on
    /// each SSE delta of the same reply.
    ///
    /// Separate from `layout` because they answer different questions — which
    /// layout, and how the deficit is spent — and because "how it is spent" is
    /// then a named thing the suite can assert on its own.
    ///
    /// The caller's `floors <= content` guarantees the total slack covers the
    /// deficit, so this always reaches zero before it runs out of columns.
    /// A column already at the floor has no slack, so it is never squeezed —
    /// and neither is one the deficit runs out before.
    static func shrunkToFit(_ clamped: [CGFloat], content: CGFloat, minimum: CGFloat) -> [CGFloat] {
        let order = clamped.indices.sorted { first, second in
            let slack = (clamped[first] - minimum, clamped[second] - minimum)
            return slack.0 == slack.1 ? first < second : slack.0 > slack.1
        }
        var widths = clamped
        var remaining = clamped.reduce(0, +) - content
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
private extension MarkdownTable {
    /// A hairline goes BETWEEN rows and never after the last one. Stated once
    /// and asked by both layouts, so the grid and the stack cannot come to
    /// disagree about the trailing rule.
    func isLastRow(_ index: Int) -> Bool { index == rows.count - 1 }
}

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

    /// Whether a column has a header at all, judged on the RENDERED characters
    /// the eye and VoiceOver both receive. Asked by every surface that pairs a
    /// header with a value — the stacked row's `fieldLine` as well as
    /// `cellLabel` below — so "headerless" cannot come to mean two things.
    static func hasHeader(_ header: InlineMarkdown) -> Bool {
        !plain(header).isEmpty
    }

    /// "College: Michigan" — the header restores the meaning a cell loses when
    /// it is read three columns away from the row that explains it. A headerless
    /// column speaks the value alone rather than a stray ": ".
    static func cellLabel(header: InlineMarkdown, value: InlineMarkdown) -> String {
        guard hasHeader(header) else { return plain(value) }
        return "\(plain(header)): \(plain(value))"
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
    private let string: AttributedString

    /// One block's inline Markdown at one font and colour.
    ///
    /// Styled through the shared `MarkdownInline.styled` — attributes, not
    /// `.font()` / `.foregroundStyle()` modifiers — so that this initialiser
    /// and the composed one below produce the same kind of value: one
    /// already-styled string, drawn identically.
    init(markdown: InlineMarkdown, font: Font = .dsBody, color: Color = .dsTextPrimary) {
        string = MarkdownInline.styled(markdown, font: font, color: color)
    }

    /// A span already composed by its caller — a stacked table row's
    /// "header then value" line, whose two halves carry different tokens and so
    /// cannot be a single font. It is still ONE `Text`: see `stackedRow`.
    init(attributed string: AttributedString) {
        self.string = string
    }

    var body: some View {
        Text(string)
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
