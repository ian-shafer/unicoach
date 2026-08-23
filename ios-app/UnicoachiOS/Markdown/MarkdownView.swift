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

/// The complaint that started RFC 118: a GFM table arriving as a wall of
/// unaligned pipes. A `Grid` gives real columns; the horizontal `ScrollView`
/// means a six-column table scrolls instead of truncating, while the two- and
/// three-column tables a coach actually sends fit and never scroll.
private struct TableView: View {
    let table: MarkdownTable
    /// Scaled so the grid grows with Dynamic Type rather than clipping it.
    @ScaledMetric private var columnMaxWidth: CGFloat = DSMarkdown.columnMaxWidth

    var body: some View {
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
                        cell(header, column: index, font: .dsLabel, color: .dsTextPrimary)
                    }
                }
                rowSeparator
                ForEach(Array(table.rows.enumerated()), id: \.offset) { rowIndex, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { index, value in
                            cell(
                                value,
                                column: index,
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
        // Clipped to the bubble's own radius, so a scrolled table still reads
        // as contained rather than as content escaping the bubble. Not
        // outlined: the table's own header hairline already bounds it, and a
        // second rule around a scrolling grid reads as a nested box.
        .markdownContainer(outlined: false)
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
        font: Font,
        color: Color,
        label: String? = nil
    ) -> some View {
        let alignment = table.alignments[column]
        return Text(MarkdownInline.attributed(text))
            .font(font)
            .foregroundStyle(color)
            .multilineTextAlignment(alignment.textAlignment)
            // Floor and ceiling: a one-word column keeps a usable width, and a
            // prose column wraps instead of monopolising the row.
            .frame(
                minWidth: DSMarkdown.columnMinWidth,
                maxWidth: columnMaxWidth,
                alignment: alignment.frameAlignment
            )
            .fixedSize(horizontal: false, vertical: true)
            // A header cell has no header to prefix, so it speaks its own
            // rendered text — never the source, which is the `**` this RFC
            // exists to hide.
            .accessibilityLabel(label ?? MarkdownAccessibility.plain(text))
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
