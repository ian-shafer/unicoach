# RFC 120: Rendered Markdown should never hide content behind a horizontal scroll

## Summary

RFC 118 gave the iOS conversation view a Markdown renderer. Two of its blocks —
the table and the fenced code block — answer "this content is wider than the
bubble" with a **horizontal `ScrollView`**. That was the wrong answer, and this
RFC replaces it.

Tables that fit keep their grid. Tables that do not become **one block per
row**. Code blocks **wrap** instead of scrolling. Afterwards `ios-app` contains
no horizontal scroll view at all.

## Why RFC 118 got this wrong

Worth stating plainly, because the failure was in the reasoning rather than in
the code.

**The claim was circular.** When the scroll view was challenged, the defence
offered was that "the horizontal `ScrollView` with a shown indicator is the
design's existing, deliberate answer to overflow." It is not. `DESIGN.md` says
nothing about overflow or scrolling; the single sentence describing a scrolling
table is line 392 of §8.1, which **RFC 118 itself wrote**. A decision from one
RFC was cited back as design authority a week later — exactly the anchoring
`CLAUDE.md` warns about ("Don't get anchored on RFC prose").

**The design's actual instinct is the opposite.** The one place `DESIGN.md`
reasons about scrolling at all is §7, on the slide-over menu: _"A menu that
scrolls has no determinate height and hides its own footer behind a gesture."_
That is an argument against hiding content behind a scroll, and it applies with
more force inside a chat bubble than it does to a menu.

**There is no precedent for it.** Every other `ScrollView` in `ios-app` — login,
registration, settings, onboarding, the conversation thread — is a vertical,
full-screen page scroller. The only two horizontal ones in the app are the two
RFC 118 added.

**And it is a poor iOS pattern on its own merits.** A horizontal scroller nested
inside the vertically-scrolling thread competes with the thread's own pan and
with the interactive back-swipe, and its only affordance is an indicator that
fades. The steady state is a table silently hiding a column.

RFC 118 already recorded the symptom — a captured render cut the "Status" column
off with no affordance — and "fixed" it by turning the scroll indicator on. That
treated the symptom. This RFC treats the cause.

## Detailed Design

### The fit decision

`MarkdownTableLayout` already computes everything needed. Today its rule 4 —
"even at `minimum` the columns do not fit" — shrugs and returns scroll-width
widths. That branch is precisely the "a grid is not honest here" signal, so it
becomes the **stacking** signal instead.

The function's return type changes from `[CGFloat]` to a decision:

```swift
enum MarkdownTableLayout {
    enum Layout: Equatable {
        case grid([CGFloat])   // definite width per column
        case stacked           // one block per row
    }

    static func layout(
        natural: [CGFloat],
        available: CGFloat?,
        spacing: CGFloat,
        minimum: CGFloat,
        maximum: CGFloat
    ) -> Layout
}
```

Rules 1–3 are unchanged and still return `.grid`: clamp into
`minimum ... maximum`, never stretch, and when the table overruns take the
deficit as a waterfall from the column with the most slack. Rule 4 returns
`.stacked`.

The width is also **withheld until the measuring pass has reported**. Before it
lands, every column's natural width is the ceiling standing in for "not measured
yet", and against those stand-ins a real `available` stacks any table of three
or more columns for one frame — so the call site passes `nil` until
`measuredWidths` is non-empty. `available` is an **optional**, not a `<= 0`
sentinel: "nobody has measured yet" and "this container really is 0pt wide" are
different states and only the first should answer as if the table fits. A table
that visibly reassembles itself is worse than one briefly drawn at ceiling
widths and clipped. Recorded here because it is a layout policy of the same
weight as `OfferedWidth` below, not a detail of the call site.

Keeping this a **pure function** is the point. `bin/test` compiles no Swift, so
XCTest is this change's only mechanical authority, and the fit decision is the
part most worth pinning.

### `columnMinWidth` becomes a real threshold

`DSMarkdown.columnMinWidth` is 64. As a hard floor that was harmless; as the
**grid/stack threshold** it is now load-bearing, and 64 is too permissive — a
captured render at that width hyphenated "Michigan" into "Mi-chigan". A column
narrow enough to hyphenate an ordinary word is not a column.

So the floor is raised to the narrowest width at which a typical two-word cell
does not hyphenate, **set from a captured render rather than guessed**.

The render says **88**. Ordinary cells — "Not started", "Public (CC)", "In
progress", "Michigan" — were drawn at `dsBody` in candidate widths from 64 to
104. At 64 "Michigan" hyphenated into "Mi-chigan" and "In progress" broke a word
across lines; between 72 and 86 nothing hyphenated but every two-word cell
wrapped onto a second line, which is still not a column; 88 is the narrowest
width at which all of them set on one line. 88 was preferred to the 96 this RFC
guessed at because a lower threshold keeps the grid — the better layout when it
is honest — for more tables.

This is one number doing the job it was always named for, not a new concept.

### Stacked rows

A stacked table is a `VStack` of one block per row, separated by the same 1pt
`DSHairline` the grid uses. Per row:

- The **first column's value** is the row's heading — it is the identifying
  field ("University of Maine at Presque Isle"), rendered as inline Markdown at
  `dsBody`/`TextPrimary`.
- Each **remaining column** is one line: its header, then its value.

The header travels with **every** value, column 0 included — visually for the
remaining columns, and through an accessibility label for the heading. On screen
the heading titles the block and a "School: " prefix would be noise; in the
audio channel there is no such context, and `children: .combine` would otherwise
open a stacked row with a bare "Yes" or "Nov 1" — the orphaned-cell reading the
grid path labels its way out of. So the heading carries
`MarkdownAccessibility.cellLabel(header:value:)` exactly as a grid cell does.

Each of those lines is built as **one `AttributedString` in one `Text`** — the
header run styled `dsCaption`/`TextSecondary`, the value run
`dsBody`/`TextPrimary` — rather than as an `HStack` of two `Text`s.

That is deliberate and is the lesson of this feature's two worst bugs. An
`HStack` of texts inside an indefinite-width parent is exactly what truncated
list items with an ellipsis, and a `maxWidth` clamp with no definite width is
exactly what made a wrapped cell bleed into the row below. A single `Text` has
neither failure mode: it wraps correctly under any proposal, with no
measurement, no `fixedSize`, and no frame arithmetic.

Per-column alignment (`:---:`) is honoured in `.grid` and ignored in `.stacked`,
where it has no meaning.

### The table must be clamped to the width it is offered

**Added during implementation, and load-bearing.** Deleting the `ScrollView`
also deleted the only thing that was clamping the table to the width it was
given, and that turned the width probe into a feedback loop with a stable wrong
answer: the five-column grid overran to 520pt, a flexible
`frame(maxWidth: .infinity)` grows to an oversized child rather than clipping
it, so the probe reported 520 as the width "available", `layout` concluded the
grid fitted, and the whole reply drew wider than the screen with "Status" cut
off — the exact defect this RFC exists to remove, reintroduced by its own fix.
The first capture of the implementation showed it.

The cure is a small `Layout`, `OfferedWidth`, that reports the width it is
proposed and hands that width to its child — falling back to the child's own
ideal width when the proposal is unspecified, since there is then no offer to
clamp to. SwiftUI has no stock spelling of this; the only container that clamps
is the `ScrollView` being removed. Every other candidate (a sibling probe in a
`VStack`, a background `GeometryReader`) measures the same overflowing subtree,
because a stack proposes its own final width to a flexible child.

### Code blocks wrap

`codeView`'s `ScrollView(.horizontal, showsIndicators: false)` goes. The text
wraps inside the existing outlined `DSRadius.control` container, drawn through
the shared `InlineText` primitive — which is where the wrap chain (a definite
full width to wrap into, `fixedSize` vertically) is spelled — over an
`AttributedString` built from the verbatim payload, never markdown-parsed.

`showsIndicators: false` made this the worse of the two offenders: code could
run off the edge with **no affordance at all**. Wrapping loses the horizontal
alignment of a long line, which is a real cost — but it is a smaller cost than
content that cannot be reached, and it is what GitHub and every chat client do.
Coach replies contain code rarely and briefly.

### What this fixes for free

Stacked rows are naturally **one accessibility element per row**, which is what
RFC 118's Accessibility section wanted and could not get from `Grid` (combining
a `GridRow` risks breaking column alignment). The open item stands for `.grid`
and is resolved for `.stacked` — the case where a row has the most cells and the
per-cell reading was worst.

## Non-goals

- **The grid itself.** Tables that fit are good and are not touched.
- **The vertical thread scroll.** Only horizontal scrolling is at issue.
- **Markdown parsing.** No parser change; this is presentation only.
- **A general responsive-table component.** Two layouts, chosen by one
  measurement, for one renderer.

## Files Modified

| File                                                 | Change                                                                                                               |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `ios-app/UnicoachiOS/Markdown/MarkdownView.swift`    | `MarkdownTableLayout` returns a `Layout`; `StackedTableView`; `OfferedWidth`; both `ScrollView(.horizontal)` deleted |
| `ios-app/UnicoachiOS/Markdown/MarkdownInline.swift`  | `styled(_:font:color:)` — the one run-stamping implementation both callers share                                     |
| `ios-app/UnicoachiOS/DesignSystem/Theme.swift`       | `DSMarkdown.columnMinWidth` raised and redocumented as the grid/stack threshold                                      |
| `ios-app/UnicoachiOSTests/MarkdownParserTests.swift` | layout-decision tests; the bounds tripwire updated                                                                   |
| `ios-app/DESIGN.md`                                  | §8.1 "Rendered Markdown" rewritten: no horizontal scrolling anywhere                                                 |

## Implementation Plan

1. **The decision, first and alone.** Change `columnWidths` to `layout`
   returning `Layout`, keeping rules 1–3 byte-identical in behaviour. Update the
   existing tests to the new return type; add the `.stacked` cases. Red-to-green
   with no view work.
2. **Stacked rows.** The `VStack` + `DSHairline` row block, with each field line
   as one composed `AttributedString`.
3. **Delete the table's `ScrollView`**, and with it `showsIndicators`. The
   measurement pass and `TableAvailableWidthKey` STAY — they are what decides
   the fit.
4. **Clamp the table to the width it is offered** (added during implementation,
   and the load-bearing half of step 3): the `ScrollView` was the only thing
   bounding the probe, so without a replacement clamp the probe reads the
   table's own overflow back as "available" and an oversized grid proves itself
   to fit. Add `OfferedWidth` and wrap the table body in it.
5. **Wrap the code block**; delete its `ScrollView`.
6. **Set `columnMinWidth` from a render**, not from taste: capture the threshold
   candidates and pick the narrowest that does not hyphenate. Record the number
   and the reason in the token's doc comment.
7. **`DESIGN.md`** updated to describe what was built.
8. **Visual gate** (below).

## Tests

Pure-function tests in `MarkdownParserTests.swift`, XCTest, no simulator UI:

**The decision** — a table that fits returns `.grid` with clamped natural
widths; a table needing a small deficit returns `.grid` with the waterfall
applied; a table whose columns cannot all reach `minimum` returns `.stacked`;
the exact boundary (`floors == content`) returns `.grid`, not `.stacked`; an
unknown `available` (`nil`, the first layout pass) returns `.grid` rather than
flickering into `.stacked`, while a container genuinely 0pt wide stacks — the
two states the old sentinel could not tell apart.

**The shrink waterfall on its own** — `shrunkToFit` is a named function beside
the decision it serves, so "how the deficit is spent" is asserted directly: the
widest slack pays the whole deficit before the next column is touched, a column
is exhausted to the floor before the remainder spills, ties pay in column order,
and widths that already fit are paid nothing.

**Regression** — every rule 1–3 case from RFC 118 and the row-height fix still
holds under the new return type, including the waterfall case that a single
point of proportional shrink used to wrap.

**Bounds tripwire** — `testTheFixtureBoundsStillMatchTheShippedTokens` updated
to the new `columnMinWidth`, so a retuned token fails loudly instead of leaving
hand-worked arithmetic silently describing a grid we no longer ship.

Stacked _rendering_ is not unit-testable (it is views); it is verified in the
visual gate, which is stated here rather than papered over.

`OfferedWidth` is not unit-testable either, and for a harder reason:
`Layout.Subviews` cannot be constructed outside a live layout pass, so there is
nothing for XCTest to propose a width to. No fake unit test is invented for it.
The clamp is verified in the visual gate instead, by the render that catches its
absence exactly: the **five-column fixture**, which without the clamp draws
wider than the screen with its last column ("Status") cut off — the first
capture of this implementation is that render.

## Visual gate

`bin/screenshot-ios` cannot reach this screen — a bare launch stops at the first
screen and the conversation view sits behind a signed-in session. Renders are
captured by a **temporary** XCTest that hosts the view in a `UIWindow` (with
`overrideUserInterfaceStyle` for dark) and snapshots via `layer.render(in:)`,
deleted before the code commit.

Do **not** use `ImageRenderer`: it does not rasterize `ScrollView` content and
it ignores a SwiftUI `colorScheme` override for asset colours. Both traps were
hit in the RFC 118 run and cost two phantom defect reports.

Must be confirmed by eye, light and dark:

- Ian's two-column Maine table still renders as a **grid**, unchanged.
- The five-column fixture renders **stacked**, with every field visible and no
  gesture required to reach one.
- No table — grid or stacked — draws wider than the bubble it sits in: the
  bubble's trailing edge is the table's, not the far side of an overrun. This is
  the bullet that stands in for a unit test of `OfferedWidth`.
- No cell bleeds across a separator, and nothing truncates with an ellipsis.
- A long code line wraps inside its container rather than running off the edge.

## Open items

- **Which column heads a stacked row.** This RFC takes the first, which is the
  identifying field in every coach table seen so far. A table whose first column
  is not the identifier would read oddly; no evidence of one yet.
- **Hanging indent for wrapped code.** A continuation indent would preserve more
  structure than a plain wrap. Deferred — SwiftUI has no cheap hanging indent,
  and coach replies rarely carry code.
- **Per-row VoiceOver in `.grid`** remains as RFC 118 recorded it.
- Renders are one device width at default Dynamic Type; the `@ScaledMetric`
  bounds are still unverified at accessibility sizes.
