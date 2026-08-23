# RFC 125: Copyable messages in the iOS conversation view

## Summary

A student cannot get a coach reply out of the app. The bubble is a composed
`VStack` of `Text` views with no selection, no menu, and no share affordance, so
the essay-outline table the coach just produced can be read and nothing else —
not pasted into Notes, not mailed to a parent, not dropped into the Common App
draft it was written for. Ian's instruction: make message text copyable,
preserving the original Markdown, with a choice between copying as-is and
copying as Markdown.

Two things make this cheap. The raw source is already at hand —
`ConversationView.swift:197` renders
`MarkdownView(source: turn.coachMessage?
.content ?? turn.coachStreamingText)`,
so the exact string to put on the pasteboard for "as Markdown" is the string the
view was handed. And the rendered-characters conversion already exists for one
span: `MarkdownAccessibility.plain(_:)` turns an `InlineMarkdown` into what the
eye sees, which is the basis for "as-is".

What does not exist is the block-level half of that conversion. `plain(_:)`
handles a span; a message is a document of headings, lists, quotes, code and
tables. So the one genuinely new thing here is a **pure function from
`[MarkdownBlock]` to a plain-text string** — which is also, conveniently, the
only part of this change that a test can reach.

## Detailed Design

Three pieces, in increasing order of risk:

1. **`MarkdownPlainText`** — a pure, SwiftUI-free renderer from parsed blocks to
   plain text. Lives in `MarkdownBlock.swift` beside the parser and the model,
   deliberately: it is the parser's inverse-ish partner, it needs no new target
   membership, and **it therefore requires no `project.pbxproj` edit**. Adding a
   file to that project by hand has already cost this repo a silent object-ID
   collision once (RFC 121 run); a 60-line enum does not justify paying that
   risk again.
2. **A `.contextMenu` on both bubbles**, plus matching accessibility custom
   actions.
3. Nothing at all inside `MarkdownView`'s layout. See **Layout safety**.

### Why a context menu and not `.textSelection(.enabled)`

`.textSelection(.enabled)` is the obvious reach and it is wrong here on three
counts, each independently sufficient:

- **It selects the wrong unit.** The modifier makes each `Text` individually
  selectable. A coach reply is 5–20 separate `Text` views (a heading, four list
  rows each with its own marker `Text`, every table cell); selection does not
  cross them. The student would get one list item, or one cell, and never the
  message. The single most likely thing a student wants — _the whole reply_ — is
  the one thing this cannot express.
- **It loses the Markdown by construction.** A selection yields rendered
  characters. There is no path from it to "copy as Markdown", so the feature Ian
  actually asked for would still need the menu.
- **It fights the gestures the screen already owns.** Text selection claims the
  long-press-and-drag that the thread's vertical `ScrollView` and the drawer's
  edge gesture also want. A `.contextMenu` claims long-press _without_ drag, is
  what `ConversationListView.swift:147` already uses for row actions, and so is
  the gesture vocabulary this app has already taught.

The cost is discoverability — a menu is invisible until pressed — and
granularity: whole message only, no partial copy. Both are accepted. Whole-
message copy is the overwhelmingly common intent, and the alternative delivers
partial copy of the wrong partitions.

### The menu

**Coach bubble** — two actions:

| Label              | Puts on the pasteboard             |
| ------------------ | ---------------------------------- |
| `Copy`             | `MarkdownPlainText.render(source)` |
| `Copy as Markdown` | the raw `source` string, verbatim  |

Bare `Copy` is the **rendered** text, taking Ian's "as-is" at its word: as-is
means as-seen. A student copying a reply into a message to a parent wants the
words, not `**Common App**`. The student who wants the syntax is by definition
the student who knows what Markdown is, and can find the second item.

**Student bubble** — one action, `Copy`, of `turn.userMessage.content`. The
student's turn is an _utterance_ rendered as plain `Text` (RFC 118); its raw
string and its rendered string are the same string, so a second menu item would
be two labels for one behaviour. Symmetry of _affordance_ — long-press any
bubble, get Copy — matters; symmetry of _menu length_ does not.

### `MarkdownPlainText` rules

Blocks joined by `\n\n`; the result trimmed of trailing newlines.

| Block     | Rendering                                                                            |
| --------- | ------------------------------------------------------------------------------------ |
| heading   | the plain inline text, no `#`                                                        |
| paragraph | the plain inline text                                                                |
| list      | one line per item, `•`, `1.`, `☑`/`☐` by marker; two spaces of indent per nest level |
| quote     | one line per line, no `>`                                                            |
| code      | the code verbatim, no fences                                                         |
| table     | one line per row, cells separated by a **tab**, header row first                     |
| rule      | omitted                                                                              |

The table rule is the only real judgement call. A tab-separated row pastes into
Notes, Numbers, Sheets and Mail as an actual table, which is what a student
copying a "Reach / Match / Safety" grid wants; ASCII-art alignment would look
right only in a monospaced destination and wrong everywhere else. The `☑`/`☐`
glyphs mirror the `checkmark.circle`/`circle` images the view draws, keeping the
promise that plain text is what the eye saw.

Nothing here is lossy in a way that matters, because the lossless option is one
menu item away — that is the whole point of offering both.

### Accessibility

VoiceOver never gets a long press, so a context-menu-only feature is invisible
to it. Every action is therefore _also_ published to the rotor, with the same
labels. This is the standard pairing and the instruction calls for it
explicitly.

The SwiftUI spelling is `.accessibilityActions { … }`, not
`.accessibilityCustomAction` — the latter is UIKit's
`UIAccessibilityCustomAction` and has no SwiftUI modifier of that name; the
SwiftUI forms are `.accessibilityAction(named:)` and the ViewBuilder
`.accessibilityActions {}`. The builder form is the one that accepts a
`ForEach`, so the menu and the rotor are generated from the **same** array of
actions and cannot drift into disagreeing about what the bubble offers. The
bubble is deliberately _not_ collapsed into a single accessibility element to
carry them: `.accessibilityElement(children: .combine)` would flatten the
table's per-cell labels, trading a working table reading for a tidier action
host. Whether the rotor actually surfaces both actions is not something XCTest
can answer, so it is on the manual list below.

### Layout safety

`MarkdownView` has shipped two layout bugs of one shape: a `Text` in an `HStack`
inside an indefinite-width parent truncating with an ellipsis (RFC 118's list
row), and a `maxWidth` clamp with no definite width letting a wrapped table cell
bleed into the next row (RFC 120). Both came from adding view structure inside
the renderer.

This change adds **no view structure inside `MarkdownView` at all.** The
`.contextMenu` and `.accessibilityCustomAction` modifiers attach to the bubble
in `ConversationView.messageBubble`, outside the renderer, and neither modifier
participates in layout: `contextMenu` renders a platform popover from a snapshot
of the view it decorates and proposes it nothing. No new `HStack`, no new
`Text`, no `frame`, no `fixedSize`. `MarkdownView.swift` is not modified.

That is a deliberate constraint on the implementation, not merely a description
of it: any diff that touches `MarkdownView`'s body is out of scope for this RFC.

## Non-goals

- **Share sheet / `ShareLink`.** Copy is the instruction; share is a different
  affordance with its own decisions (what activity types, what filename for an
  exported reply). It can be a later RFC and would slot into the same menu.
- **Partial / cross-block selection.** Rejected above.
- **Copying a whole conversation.** Message-level only.
- **Copying while streaming.** The menu is live on a streaming bubble and copies
  whatever has arrived, because suppressing it would require a state check for
  no benefit — a half-copied reply is the student's own choice and re-copying
  costs one long press.
- **Any change to `MarkdownView`, the parser, or the wire format.**

## Files Modified

| File                                               | Change                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift` | **New** `enum MarkdownPlainText`: `render(_ blocks:)`, a `render(_ source:)` convenience that parses first, and the private `text(of:)` / `lines(of:)` / `field(of:)` / `line(of:)` / `trimmingTrailingWhitespace(_:)` helpers. No change to the parser or model.                                                                           |
| `ios-app/UnicoachiOS/MessageCopy.swift`            | **New.** `enum CopyMenu` (`.utterance(text:)`, `.document(source:)`), `struct CopyAction` (private init; `Reading` derives title and identifier; deferred `makeText`; `text: String?`; `@discardableResult copy() -> String?`), and `View.copyMenu(_:)` backed by `CopyMenuModifier`. The app's **only** `import UIKit`-for-`UIPasteboard`. |
| `ios-app/UnicoachiOS/ConversationView.swift`       | `messageBubble` gains a `menu: CopyMenu` parameter and applies `.copyMenu(menu)`. Call sites in `turnView` pass `.utterance` and `.document`. No `import UIKit`.                                                                                                                                                                            |
| `ios-app/UnicoachiOSTests/MessageCopyTests.swift`  | **New** test file: `MarkdownPlainTextTests`, `CopyActionTests`, `CopyMenuTests`, `CopyActionPasteboardTests`.                                                                                                                                                                                                                               |
| `ios-app/UnicoachiOS.xcodeproj/project.pbxproj`    | Register the new production file (app target) and the new test file (test target). Fresh 24-hex object IDs, each grepped against the whole file before writing.                                                                                                                                                                             |

`MarkdownView.swift` is **byte-identical**. That is the property the whole
layout-safety argument rests on, and it is checked rather than asserted — see
**Tests**.

### Where each piece lives, and why

`MarkdownPlainText` sits beside the parser because it renders a `MarkdownBlock`:
it is the model read in the opposite direction, and would be the right rendering
if nothing in the app ever copied anything. `MessageCopy.swift` is the
_affordance_ — what may be copied, how it reaches the pasteboard, and how it
mounts on a view. Splitting them that way is what keeps `ConversationView` free
of both: the bubble draws a border and a radius and knows nothing about copying.

## Implementation Plan

1. `MarkdownPlainText` in `MarkdownBlock.swift`, per the rules table. Pure
   functions over the existing model; `InlineMarkdown` spans go through
   `MarkdownAccessibility.plain(_:)` so there is exactly one definition of
   "rendered characters" in the app. A block that renders to nothing is dropped
   rather than joined, or it leaves the doubled blank line the separator exists
   to prevent — the parser really does emit them (`##`, an empty fence). Table
   cells are stripped of interior tabs and newlines, since a tab inside a cell
   would forge an extra column in the spreadsheet the format was chosen for.
2. `MessageCopy.swift`, carrying the whole affordance:
   - `CopyMenu` — a case per kind of message, so the two menus that exist are
     the only two representable. An array parameter would admit an empty menu
     and duplicate-`id` entries, and a `ForEach` id collision silently collapses
     the coach's two items into one row.
   - `CopyAction` — **private** init, with `title` and `identifier` derived from
     a `Reading`. Left synthesised, the memberwise init would spell
     `CopyAction(title: "Copy as Markdown", identifier: "copyButton", …)`; the
     mismatch simply has no spelling now. Its text is **deferred** (`makeText`),
     because actions are built in a view body and an eager render would parse
     every visible reply on every SSE delta for a menu almost nobody opens.
     `text` is `String?` and `copy()` returns what landed, so "nothing to copy"
     is a state rather than an empty-string sentinel — and `copy()` never
     assigns `""`, which would not fail but _would_ wipe the student's
     clipboard. Whitespace-only counts as nothing, for all three factories.
   - `View.copyMenu(_:)` — one `@ViewBuilder` of buttons hosted by both
     `.contextMenu` and `.accessibilityActions`, so the menu and the rotor
     cannot drift. `Label(title, systemImage: "doc.on.doc")` and the
     `copyButton` / `copyMarkdownButton` identifiers follow
     `ConversationListView`'s existing context menu.
3. Wire `messageBubble` to take `menu: CopyMenu` and apply the modifier.
4. `MessageCopyTests.swift`, then register both new files in `project.pbxproj` —
   IDs generated with a uniqueness check against the whole file before writing,
   since a copied-and-incremented ID is a silent, lethal collision.
5. `nix develop -c bin/test` (unchanged by this RFC, but it is the repo gate),
   then the iOS suite, then the layout A/B and the manual pass below.

## Tests

**Mechanical (XCTest, `MessageCopyTests`)** — the only mechanical authority this
change can have, since `bin/test` does not compile `ios-app`:

- heading strips `#` and inline syntax; every level.
- paragraph with bold/italic/code/link spans yields the rendered characters.
- bullet, ordered and task lists; ordinals preserved; `☑`/`☐` by done-ness;
  nested items indented two spaces per level; a hard-wrapped item stays one
  line.
- quote lines carry no `>`; code block is verbatim with no fences.
- table: header row first, cells tab-separated, one line per row; a wrapped
  multi-word cell stays on one line; **a tab inside a cell cannot forge an extra
  column**.
- rule is omitted, and does not leave a doubled blank line; neither does any
  other block that renders to nothing (`##`).
- blocks joined by exactly one blank line; no trailing newline, and no trailing
  indent left behind by a code block whose last line is indented.
- `render("")` is `""`.
- the `worstCaseReply` fixture round-trips to a stable expected string.
- `CopyAction.rendered` is titled `Copy` and carries no `#` or `**`; `.markdown`
  is titled `Copy as Markdown` and is byte-identical to the source; `.verbatim`
  does not render the student's own `*`; the coach's two actions have distinct
  `id`s.
- `CopyMenu.utterance` offers one action, `.document` two, in order.
- copying puts the text on the pasteboard and returns it; a whitespace-only
  payload — for **all three** factories — returns `nil` and leaves the clipboard
  untouched.

**The layout claim, checked rather than asserted.** A throwaway
`UIHostingController` + `UIGraphicsImageRenderer` A/B harness rendered the
bubble chrome with and without `.copyMenu(...)` over `worstCaseReply`, at 320pt
and 375pt, light mode, `dynamicTypeSize .large`. Both pairs came back **byte-
identical** (0 differing pixels of 901,120 and 990,000; equal md5s). The harness
was deleted afterwards — it exists in the run archive, not in the repo. A bare
`bin/screenshot-ios` could not have done this: the app opens signed-out and
`ios-app` has no UI tests, so no launch reaches a coach bubble.

**Manual (simulator) — NOT performed; carried as open items.** Nothing in the
suite reaches a `contextMenu` popover or the VoiceOver rotor:

- long-press a coach bubble: two items, correct labels; long-press a student
  bubble: one `Copy`.
- long-press mid-thread does not scroll the thread or open the drawer.
- VoiceOver rotor exposes both actions on the coach bubble. This is the one with
  real risk: `.accessibilityActions` is applied to a container that is
  deliberately _not_ a single accessibility element, and whether the rotor
  surfaces the actions on such a container is not something XCTest can answer.
