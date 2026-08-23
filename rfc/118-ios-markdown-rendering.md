# RFC 118: Rendered Markdown in the iOS conversation view

## Summary

The coach's replies are Markdown. The iOS conversation view is not: it binds the
raw string straight into a SwiftUI `Text` (`ConversationView.swift:180`), so a
student reads `**Common App**` with the asterisks, `### Your next steps` with
the hashes, and — worst of all — a GFM table as a wall of unaligned pipes
wrapped across a 300pt bubble. Ian's instruction: make tables readable and show
bold/italic as bold/italic.

Nothing in this repo renders Markdown anywhere today — not `public-web` (which
has no chat surface), not `admin-web` (`CellRender.kt` emits HTML-escaped plain
text), not `web-common`, not `chat`. There is no Markdown dependency, no
Markdown CSS, and no RFC on the subject. This is the first one.

Note also what is **not** happening: no prompt asks the coach for Markdown. The
coach system prompt (`db/schema/0011.seed-coach-system-prompt.sql`) says only
"Be concise and concrete. Ask at most one focused question per reply." The
Markdown is Claude's own unprompted house style. That matters for scope — see
**Non-goals**.

## The shape of the fix

A **self-contained renderer inside `ios-app`, with no new dependency.** Three
small files under `UnicoachiOS/Markdown/`:

1. `MarkdownBlock.swift` — a pure-Swift block model and line-oriented parser. No
   SwiftUI, no UIKit. This is where all the logic lives, and it is therefore the
   part that is actually unit-testable in `UnicoachiOSTests` — which matters
   more than usual here, because `bin/test` never compiles `ios-app` and the
   XCTest suite is the only mechanical authority this change will ever face.
2. `MarkdownInline.swift` — spans within a block, via Foundation's own
   `AttributedString(markdown:options:)` at
   `interpretedSyntax: .inlineOnlyPreservingWhitespace`, plus the token styling
   Foundation does not apply.
3. `MarkdownView.swift` — the SwiftUI renderer, reading
   `DesignSystem/Theme.swift` tokens exclusively.

### Why hand-rolled rather than `swift-markdown-ui`

`swift-markdown-ui` is the obvious package and it is genuinely good, but it is
the wrong trade here on three counts.

- **The theming fights this design.** `DESIGN.md` §8 is emphatic — "extrapolate
  from tokens; invent no new visual language", no shadows, no fills, separation
  by 1pt hairline. Adopting the package means writing a full custom `Theme` that
  overrides essentially every block style anyway, and then still owning the gap
  where its rendering assumes elevation or a fill we do not have. We would write
  comparable code and inherit a dependency for it.
- **Testability points the other way.** A package renders SwiftUI; its output is
  only assertable through a simulator UI test, and `ios-app` has none. A parser
  we own returns values, so the interesting cases — a ragged table row, an
  unclosed fence mid-stream, an escaped pipe — become ordinary XCTest assertions
  in the suite that already exists.
- **Precedent.** `DESIGN.md` §4 records the same call being made about the
  typeface: SF Pro was chosen over a bundled geometric sans for "zero
  dependency, ships today". One SPM package is already in the project
  (GoogleSignIn) and it is there because Google's sign-in flow cannot be
  reimplemented. Markdown block layout can.

The parser is genuinely small because **the hard half is free**: inline spans
(`**bold**`, `_italic_`, `` `code` ``, `~~strike~~`, links) are handled by
Foundation's Markdown support, which is CommonMark-correct and already on the
device. Only the _block_ level — which lines group into a paragraph, a list, a
fence, a table — is ours, and that is a line-oriented state machine.

## Detailed Design

### The block model

```swift
// Markdown that still holds inline syntax, and MUST be run through
// `MarkdownInline.attributed` before it is drawn or spoken. A one-field
// wrapper, not a doc comment on a `String`, because this model carries two
// opposite contracts: a paragraph's `**bold**` must be parsed away and a code
// block's must survive verbatim. Only one of the two now type-checks per site.
struct InlineMarkdown: Equatable, ExpressibleByStringLiteral {
    let source: String
}

enum HeadingLevel: Int, Equatable { case h1 = 1, h2, h3, h4, h5, h6 }

enum MarkdownBlock: Equatable {
    case heading(level: HeadingLevel, text: InlineMarkdown)
    case paragraph(InlineMarkdown)
    case list(MarkdownList)
    case quote([InlineMarkdown])         // paragraph lines of a `>` run
    case code(String)                    // VERBATIM; info string parsed off, then dropped
    case table(MarkdownTable)
    case rule
}

struct MarkdownList: Equatable {
    let items: [Item]
    struct Item: Equatable {
        // One union for one disjoint choice: a task's checkbox *replaces* its
        // bullet, so an `isOrdered` flag beside a `marker` string beside a
        // `checked: Bool?` would be three fields able to contradict each other.
        enum Marker: Equatable {
            case bullet
            case ordered(ordinal: Int)   // resolved at parse time; the view draws the "."
            case task(done: Bool)        // GFM task list
        }
        let depth: Int                   // nesting, from leading indent
        let marker: Marker
        let text: InlineMarkdown
        // `let` throughout: a parsed block is finished data. The one transition
        // an item has — a wrapped continuation line — is named and returns a
        // copy rather than being an in-place `+=` in the parser.
        func continuing(with line: String) -> Item
    }
}

struct MarkdownTable: Equatable {
    let headers: [InlineMarkdown]
    let alignments: [Alignment]          // from `:---`, `:---:`, `---:`
    let rows: [[InlineMarkdown]]         // exactly headers.count cells each
    // The only initialiser pads and truncates both, so rectangularity is a
    // property of the *type* rather than of the parser's good behaviour —
    // which is what lets the renderer subscript a column with no guard.
}
```

A fence's info string (`` ```sh ``) is **parsed and discarded**: parsing it is
what keeps `sh` out of the code body, but nothing in this design labels a code
block, and a modelled field no view reads is one that goes stale unnoticed.

Two deliberate choices. **Nested lists are flattened into `depth`-tagged items**
rather than recursed: a recursive list type buys nothing a leading indent does
not, and it makes both the parser and the renderer materially simpler. **Block
content stays as unparsed inline Markdown** — an `InlineMarkdown` — converted to
`AttributedString` at render time, so the model stays `Equatable` and cheap to
diff, and every block type gets inline styling for free rather than each
carrying its own span array. The wrapper is what keeps that "convert at render
time" rule mechanical: `code` is the one payload that is a bare `String`,
because it is the one that must never be parsed, and the two can no longer be
passed to each other's call sites.

### The parser

`MarkdownBlock.parse(_ source: String) -> [MarkdownBlock]`, a single pass over
lines. Recognised, in precedence order: fenced code (`` ``` `` / `~~~`), ATX
heading (`#`–`######`), thematic break, blockquote run, table, list item, blank
line, paragraph continuation.

A **blank line inside a list makes it loose, not finished** — CommonMark's rule,
and the shape a coach reply takes whenever it double-spaces its numbered steps.
The blank-line branch therefore closes only the open paragraph and quote run;
every branch that opens a genuinely different block already closes the list, so
the list still ends the moment something else really starts.

The **table rule** is GFM's: a header line containing an unescaped `|`,
immediately followed by a delimiter line of `-`/`:`/`|`/space with the same
column count. Cells split on unescaped `|` only, so `\|` inside a cell survives.
Rows shorter than the header are padded with empty cells and longer ones
truncated, which is what GFM specifies and what keeps the grid rectangular.

Three **streaming-partial** behaviours are requirements, not edge cases, because
this parser runs on every SSE delta against a half-written message:

- An **unclosed fence** renders as a code block of what has arrived so far.
  Otherwise a long code block appears as garbled paragraphs until its final
  backticks land, then snaps.
- A **header + delimiter with no body rows yet** is a table with zero rows — it
  draws the header and grows downward. The alternative is a pipe-soup paragraph
  that becomes a table mid-stream.
- A **trailing partial line** is simply parsed as what it currently looks like.
  Reflow as the stream lands is expected and acceptable; a "render plain text
  until complete, then swap" design was considered and rejected because the swap
  is a jarring full-message relayout at exactly the moment the student starts
  reading.

Parsing is O(n) per delta and so O(n²) over a message; at Markdown's scale (a
few KB, a few hundred deltas) that is microseconds and not worth caching.

### Inline spans

`MarkdownInline.attributed(_ markdown: InlineMarkdown) -> AttributedString`:

```swift
AttributedString(markdown: source, options: .init(
    allowsExtendedAttributes: true,
    interpretedSyntax: .inlineOnlyPreservingWhitespace,
    failurePolicy: .returnPartiallyParsedIfPossible))
```

`.inlineOnlyPreservingWhitespace` is the load-bearing option: it keeps soft line
breaks inside a paragraph instead of collapsing them, and it refuses to
interpret block syntax — which is correct, because blocks are already ours by
the time this is called. On a throw, fall back to a plain `AttributedString` of
the source; a student must never lose text to a parse failure.

Foundation gives bold, italic, strikethrough and links their intents but SwiftUI
does **not** style two of them, so the function post-processes runs:

- **Inline code** (`inlinePresentationIntent` contains `.code`) gets a
  monospaced font at the block's own size, via a new `Font.dsCode` token.
- **Links** get `dsTextPrimary` with an underline — _not_ an accent colour.
  `DESIGN.md` §1 restricts the brand gradient to chrome and selection, §8
  removed system blue app-wide, and an orange link inside body copy would be
  exactly the new visual language §8 forbids. Underline is the affordance.

### The renderer

`MarkdownView(source: String)` renders `parse(source)` into a
`VStack(alignment: .leading, spacing: DSSpacing.sm)`, one view per block:

| Block     | Treatment                                                                                                                                                                                                                                                                   |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| heading   | `dsTitle` (`.h1`/`.h2`) / `dsLabel` (`.h3`…`.h6`), chosen by an exhaustive `switch` over `HeadingLevel`, `TextPrimary`, extra `DSSpacing.sm` above. The type scale tops out at `dsTitle` deliberately: `dsDisplay` is a _screen_ heading and a coach reply is not a screen. |
| paragraph | `dsBody`, `TextPrimary`.                                                                                                                                                                                                                                                    |
| list      | Marker in `TextSecondary` at a fixed leading column, text in `dsBody`; indent `DSSpacing.md × depth`. Task items use SF Symbols `circle` / `checkmark.circle`.                                                                                                              |
| quote     | A 1pt `FieldBorder` rule at the leading edge with `DSSpacing.sm` of gutter; text `dsBody` / `TextSecondary`. Border, not a tinted wash — §8.                                                                                                                                |
| code      | `dsCode` in a `DSRadius.control` box with a 1pt `FieldBorder` hairline, no fill, horizontally scrollable so nothing wraps mid-token.                                                                                                                                        |
| table     | See below.                                                                                                                                                                                                                                                                  |
| rule      | 1pt `FieldBorder` `Divider`, full width.                                                                                                                                                                                                                                    |

### Tables — the actual complaint

Rendered as a `Grid` (iOS 16+; the target is 17) inside a
`ScrollView(.horizontal)`:

- **Header row** in `dsLabel`, separated from the body by a 1pt `FieldBorder`
  divider; body rows separated by the same hairline. No fills, no zebra striping
  — §8's "separation by hairline" applied to a grid.
- **Column widths are content-sized with a floor and a ceiling**, so a one-word
  column does not collapse to nothing and a prose column does not monopolise the
  row; a cell that exceeds the ceiling wraps.
- **Horizontal scrolling only when it overflows.** A 2–3 column table on a 393pt
  phone fits and never scrolls; a 6-column one scrolls rather than truncating.
  The scroll view is clipped to the bubble's radius so a scrolled table reads as
  contained.
- **Alignment** from the GFM delimiter row is honoured per column.

**The coach's bubble drops its trailing `Spacer(minLength: DSSpacing.xl)`
entirely and always takes the full content width. The student's keeps its
leading spacer and stays inset.** (Ian's call at approval.)

Two reasons it is unconditional rather than "full width when the content is
wide". First, it says the right thing: the coach's turn is a **document** — it
may carry headings, a table, a code block — while the student's is an
**utterance**, and width is the honest expression of that asymmetry. Second, and
decisively, a content-conditional width **resizes the bubble mid-stream**: the
reply opens as a paragraph at inset width and then jumps wider the moment a
table's delimiter row arrives, three deltas later. Nothing about that is worth
the 40pt.

The turn is still distinguished exactly as `DESIGN.md` §8.1 specifies — by
alignment and by hairline weight (`TextPrimary` for the student, `FieldBorder`
for the coach), never by fill.

### Where it plugs in

`ConversationView.messageBubble` becomes **generic in its content** — it takes a
`@ViewBuilder content:` and draws chrome only. The coach's call site passes
`MarkdownView`; **the student's passes a plain `Text`.** Students type prose,
not Markdown, and silently eating a student's `*` or reflowing their line breaks
would be a bug, not a feature. A `rendersMarkdown: Bool` beside `isUser` was the
first spelling and is not what landed: the two flags are always exact inverses,
so the call sites stated one fact twice and
`(isUser: true, rendersMarkdown:
true)` was a representable nonsense. The
bubble's own chrome — `Surface`, `DSRadius.control`, the hairline whose weight
distinguishes the turn — is untouched, as are both accessibility identifiers, so
`ConversationViewModelTests` and any identifier-based check keep working.

### Accessibility

Blocks are individually accessible in reading order. Each **table cell** is
labelled `"<header>: <value>"`, so VoiceOver reads "College: Michigan" rather
than an orphaned "Michigan" three columns from the header that explains it. Both
halves of that label are built from the **rendered** characters, never the
source: a bold cell announced as "star star Draft star star" would be this RFC's
own complaint reintroduced in the audio channel. Every other block gets this for
free — SwiftUI speaks the `AttributedString` the renderer already built — so the
table cell, the one site that overrides the label, is the one site that strips
the syntax itself (`MarkdownAccessibility`, covered by the suite because no test
compiles the view).

This started as "each table _row_ is one combined element", which is the nicer
reading and is not buildable here: SwiftUI's `Grid` only treats **direct**
`GridRow` children as rows, so wrapping one in
`.accessibilityElement(children: .combine)` yields a `ModifiedContent` that is
not reliably recognised as a grid row — it risks silently breaking the column
alignment that is the entire point of this change. Per-cell labelling fixes the
defect the row-combining was for (an orphaned value) at the cost of N VoiceOver
stops per row instead of one. Recorded as an open item, not a silent
substitution. Headings carry `.accessibilityAddTraits(.isHeader)`. Dynamic Type
is inherited throughout, since every font comes from a `Font.system(.textStyle)`
token; the table's column ceiling is a `@ScaledMetric` so the grid grows with
text instead of clipping it.

### Token additions

`Font.dsCode` — `.system(.body, design: .monospaced)` — for fenced and inline
code, plus an `enum DSMarkdown` carrying the three measurements Markdown adds
that no existing token expresses: `markerWidth` (the list marker's fixed leading
column, so "9." and "10." do not step the text apart), and `columnMinWidth` /
`columnMaxWidth` (the table column floor and ceiling this design asks for).
Everything else reads the existing `DSSpacing` / `DSRadius` / `DSControl` scale.
`DESIGN.md` is **living** (§0), so §8.1's extrapolation table gains a **Rendered
Markdown** row, its existing **Message bubbles** row is corrected to record that
the coach's turn is full-width while the student's stays inset, and §4 gains
`dsCode` — all in place, in this RFC's code commit.

## Non-goals

- **Changing the coach prompt.** Steering the model away from tables, or toward
  them, is a separate question with product consequences; this RFC makes what
  the coach already sends legible. Worth a later RFC either way.
- **Images, raw HTML, footnotes, and reference links.** Not emitted in practice;
  they degrade to their source text.
- **Markdown anywhere else.** `admin-web` and the conversation-list previews are
  untouched.
- **Selectable / copyable message text.** Real, wanted, and orthogonal — an open
  item, not a scope creep.

## Files Modified

| File                                                 | Change                                                                                                     |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift`   | **new** — block model + parser                                                                             |
| `ios-app/UnicoachiOS/Markdown/MarkdownInline.swift`  | **new** — inline `AttributedString` conversion + code/link run styling                                     |
| `ios-app/UnicoachiOS/Markdown/MarkdownView.swift`    | **new** — SwiftUI block renderer, incl. the table grid                                                     |
| `ios-app/UnicoachiOS/ConversationView.swift`         | coach bubbles render `MarkdownView` full-width; user bubbles unchanged; new `#Preview`                     |
| `ios-app/UnicoachiOS/DesignSystem/Theme.swift`       | add `Font.dsCode`                                                                                          |
| `ios-app/UnicoachiOS/DesignSystem/Components.swift`  | add `DSHairline` — §8's one separator, previously hand-built per call site                                 |
| `ios-app/UnicoachiOSTests/MarkdownParserTests.swift` | **new** — parser + inline unit tests                                                                       |
| `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`      | register the four new files (see below)                                                                    |
| `ios-app/DESIGN.md`                                  | §4 `dsCode`; §8's hairline rule names `DSHairline`; §8.1 "Rendered Markdown" row + bubble-width correction |

The project uses a **classic pbxproj with explicit file references**, not an
Xcode 16 synchronized group — there is no `PBXFileSystemSynchronizedRootGroup`
in the file. Each new source file therefore needs four entries (`PBXBuildFile`,
`PBXFileReference`, group child, `PBXSourcesBuildPhase`), and the new `Markdown`
group mirrors the existing `DesignSystem` group (`AAAA00010000000000000A01`).
Hand-assigned stable IDs are the file's convention and must be continued, not
replaced with random UUIDs.

## Implementation Plan

1. **Parser first, alone.** `MarkdownBlock.swift` plus
   `MarkdownParserTests.swift`, red-to-green, no SwiftUI involved. Register both
   in `project.pbxproj`.
2. **Inline conversion.** `MarkdownInline.swift` and its tests — bold/italic,
   inline code run styling, link attributes, the throwing fallback.
3. **Renderer.** `MarkdownView.swift`, block by block, paragraphs and headings
   before lists, lists before code and quotes, the table grid last.
4. **Wire in.** `ConversationView.messageBubble` gains `rendersMarkdown`; drop
   the coach bubble's trailing spacer; add `Font.dsCode`; add a `#Preview` whose
   fixture is a worst-case reply — h2, bold prose, a nested bullet list, a
   fenced block, and a 5-column table — in both colour schemes.
5. **`DESIGN.md`** updated to describe what was built.
6. **Visual gate.** `bin/screenshot-ios` cannot reach this screen: a bare launch
   only ever renders the first screen, and the conversation view sits behind a
   signed-in session. So the renders were captured instead by a **temporary**
   XCTest that hosts the view in a `UIWindow` (with `overrideUserInterfaceStyle`
   for dark) and snapshots it via `layer.render(in:)` — deleted before the code
   commit, and recorded here because it is the reproducible recipe for the next
   iOS change.

   Two traps, both hit: `ImageRenderer` does **not** rasterize `ScrollView`
   content (the table and code block came out blank, which reads exactly like a
   product defect), and it resolves asset-catalog colours through the trait
   collection rather than a SwiftUI `colorScheme` override, so dark mode
   captured as white-on-white. A hosted `UIWindow` has neither problem. Anyone
   using `ImageRenderer` as a visual gate here will otherwise file two phantom
   defects and miss the real one.

## Tests

All in `ios-app/UnicoachiOSTests/MarkdownParserTests.swift`, XCTest, no
simulator UI dependency.

**Blocks** — headings h1–h6 and a `#hashtag` that is _not_ a heading; paragraphs
split on blank lines; soft line breaks preserved within a paragraph; thematic
breaks (`---`, `***`); a `---` directly under a paragraph is a setext heading
boundary, not a rule.

**Lists** — bullet markers `-`/`*`/`+`; ordered lists with a non-1 start;
two-level nesting mapped to `depth`; task-list `[ ]` / `[x]`; a list interrupted
by a paragraph; a **wrapped** bullet and a wrapped ordered item each staying one
item (an indented line continues the innermost open item — the commonest real
shape in a coach reply); and, as its deliberate counterpart, an **unindented**
line under a list still ending the list. **Loose lists** — blank lines between
bullets keeping one list with its nesting intact, blank lines between ordered
items numbering 1, 2, 3 rather than 1, 1, 1 — and their counterpart, a blank
line followed by a heading, quote or fence still ending the list.

**Code** — an info string stripped from the body; `~~~` fences; an **unclosed
fence** yielding the partial block; indented content inside a fence preserved
verbatim.

**Tables** — the canonical 3-column case; per-column alignment from `:---:`;
leading/trailing pipes optional; **ragged rows** padded and truncated to the
header width; an **escaped `\|`** kept inside its cell; a **header+delimiter
with no rows**; a pipe-bearing line with no delimiter row treated as a
paragraph, not a table.

**Inline** — `**bold**`, `_italic_`, `` `code` `` carrying a monospaced font
run, `~~strike~~`, `[text](url)` carrying a link attribute, and a malformed
`[unclosed](` returning its source text rather than throwing.

**Degenerate** — empty string, whitespace only, and a 100 KB single-line message
(the contract maximum) parsing without pathological behaviour.

The repo gate `nix develop -c bin/test` compiles no Swift, so it will report
"green" regardless. The honest evidence for this change is the executed XCTest
count from

    xcodebuild test -project ios-app/UnicoachiOS.xcodeproj -scheme UnicoachiOS \
      -destination 'platform=iOS Simulator,name=iPhone 17 Pro'

plus the attached renders. Note that **`bin/build-ios` cannot supply that
count** — it ends in `build`, not `test`, and no `bin/` script runs
`UnicoachiOSTests`. An earlier draft of this section claimed otherwise.

## Open items

- **Stacked-card table fallback, now with evidence.** The captured render of the
  five-column fixture on a 393pt device cuts the last column ("Status") off the
  trailing edge. Showing the scroll indicator (which this RFC now does) is an
  affordance, not a fix: the student still has to discover a column by dragging
  a table they may not realise is draggable. One card per row with
  `header: value` pairs would show every field without a gesture. Deferred
  rather than dismissed — the scrolling grid is right for the two- and
  three-column tables a coach usually sends, and this wants judging against real
  replies rather than a fixture.
- **Per-row table VoiceOver.** Cells are labelled `"<header>: <value>"`
  individually; a five-column row is therefore five stops, not one. See
  **Accessibility**.
- **Selectable message text.** Wanted; currently neither plain nor rendered
  bubbles allow copy.
- **Prompting the coach's formatting.** Now that Markdown renders, it is worth
  deciding what we _want_ it to emit.
- **A UI-test harness.** The visual gate can only reach the first screen; the
  conversation view is only capturable via a signed-in simulator.
