# DISCOVER: the Markdown subset the iOS client renders

Ground truth for brief 0013. Every claim below is either a `path:line` citation
into the ROOT checkout `/Users/ian/Work/unicoach`, or an **empirical probe**: I
compiled the real `MarkdownBlock.swift` (it is Foundation-only) and ran Apple's
inline parser with the app's exact options, on this machine
(`swift 6.2.3, arm64-apple-macosx15.0`). Probe outputs are quoted verbatim and
labelled `PROBE`. Anything I could not verify is called out in the last section.

## 0. Read this first: there is no single parser to port

The renderer is **two stages by two different authors**:

1. **Blocks** — a hand-written, line-oriented parser owned by this repo:
   `ios-app/UnicoachiOS/Markdown/MarkdownBlock.swift:161`
   (`MarkdownBlock.parse`), parser struct at `:166-418`, line classifiers at
   `:450-641`.
2. **Inline spans** — _not_ this repo's code. Each block's text stays raw
   (`InlineMarkdown`, `MarkdownBlock.swift:45-50`) and is handed to Apple's
   Foundation parser at render time:
   `ios-app/UnicoachiOS/Markdown/MarkdownInline.swift:20-27`.

```swift
// MarkdownInline.swift:20-27 — the WHOLE inline implementation
string = try AttributedString(markdown: markdown.source, options: .init(
    allowsExtendedAttributes: true,
    interpretedSyntax: .inlineOnlyPreservingWhitespace,
    failurePolicy: .returnPartiallyParsedIfPossible
))
```

**Where common sense would be wrong — read this before writing a slice:**

- **"The web just needs the same Markdown library" is FALSE.** Half the subset
  is a bespoke parser with non-CommonMark behaviour (no indented code blocks, no
  nested blockquotes, flattened list nesting, streaming-partial tables), and the
  other half is Apple's closed-source `AttributedString(markdown:)` with GFM-ish
  extras (bare-URL autolinking, single-tilde strikethrough, `&amp;` decoding).
  No off-the-shelf JS/Kotlin Markdown renderer matches both out of the box.
- **"Two trailing spaces = hard break" is FALSE here.** The block parser trims
  every paragraph line (`MarkdownBlock.swift:197`, `:293`) before the inline
  stage ever sees it, so the trailing spaces are gone. PROBE:
  `"line one  \nline two"` -> `paragraph("line one\nline two")`. Every newline
  inside a paragraph is a soft break, and it IS drawn as a line break (see §3
  "soft breaks").
- **There is NO sanitisation anywhere.** See §6. Raw HTML survives as literal
  characters, and `javascript:` / `data:` URLs survive as real link attributes.
  On iOS that is harmless (SwiftUI draws characters, it does not execute them).
  On the web it is an XSS hole the moment anyone emits this into HTML.
- **A `MarkdownBlock` is not a tree.** List nesting is flattened to an integer
  `depth` (`MarkdownBlock.swift:63-66`, `:93`), and a blockquote is a flat list
  of inline lines (`:18`). Any web renderer that builds a tree will produce
  _more_ structure than iOS does; matching means flattening in the same way.

`bin/test` never compiles `ios-app` (`MarkdownBlock.swift:5-7`). The XCTest
suite runs only through `bin/test-ios` -> `xcodebuild test`
(`bin/test-ios:110`).

## 1. The block model (the exact output type)

```swift
// MarkdownBlock.swift:13-28
enum MarkdownBlock: Equatable {
    case heading(level: HeadingLevel, text: InlineMarkdown)  // h1...h6, :59-61
    case paragraph(InlineMarkdown)
    case list(MarkdownList)
    case quote([InlineMarkdown])   // one entry per `>` line, `>` stripped
    case code(String)              // VERBATIM; info string parsed and DISCARDED
    case table(MarkdownTable)
    case rule
}
```

Supporting types: `MarkdownList` / `Item{depth, marker, text}` with
`Marker = bullet | ordered(ordinal:) | task(done:)`
(`MarkdownBlock.swift:67-106`), and `MarkdownTable{headers, alignments, rows}`
normalised rectangular in `init` (`:108-146`).

## 2. BLOCK-level checklist (what the web SSR must match)

Supported:

- [x] **ATX headings `#`..`######`** — `MarkdownBlock.swift:468-481`. Levels 1-6
      only; a 7th `#` is **not** a heading, it stays a paragraph (`:473`, test
      `MarkdownParserTests.swift:26`). A space after the hashes is **required**,
      so `#firstgen` is prose (`:477`, test `:30`). Trailing `#`s are stripped
      (`:479`, test `:36`).
- [x] **Setext headings** — `Title\n===` -> h1, `Title\n---` -> h2, and only
      when a paragraph is open (`:230-236`, `:484-488`, test `:60`). The
      underline must be ALL one character with no spaces, so `- - -` is a rule
      (PROBE: `"Title\n- - -"` -> `paragraph` + `rule`).
- [x] **Paragraphs**, split on blank lines, lines joined with `"\n"`
      (`:391-397`, tests `:40`, `:47`).
- [x] **Thematic break / `<hr>`** — 3+ of `-`, `*` or `_`, interior spaces
      ignored (`:490-495`, test `:54`). `___` is a rule (PROBE).
- [x] **Blockquote** — a run of `>` lines; one leading `>` and one optional
      space removed per line (`:245-253`). A blank line ENDS the quote and a
      following `>` run is a **second** `.quote` block (PROBE).
- [x] **Fenced code** —
      ``or `~~~`, 3+ chars, closer must be >= opener length
      (`:450-466`, tests `:266-307`). Info string (``
      `sh`) is parsed off and **DISCARDED** (`:19-24`,`:454`). Body is verbatim,
      keeps its own indentation, including the opener's indent (PROBE:`"`\n
      code\n`"`->`code("
      code")`). An **unclosed fence yields the partial
      block** — deliberate, for streaming (`:302-321`, test`:290`).
- [x] **Bullet lists** — markers `-`, `*`, `+`, each needing a following space
      (`:505-508`, test `:87`).
- [x] **Ordered lists** — `1.` and `1)` both (`:512`, PROBE). Ordinals are
      RESOLVED AT PARSE TIME: a non-1 start is honoured and repeated `1.`
      renders 1,2,3 (`:343-351`, tests `:101`, `:111`). The web must renumber
      the same way, not lean on `<ol>` defaults.
- [x] **GFM task list items** — `- [ ]` / `- [x]` (case-insensitive), the
      checkbox REPLACES the bullet or number (`:526-532`, `:355-361`, test
      `:144`). Rendered as SF Symbols `circle` / `checkmark.circle`
      (`MarkdownView.swift:104-107`), plain-text as U+2610 / U+2611
      (`MarkdownBlock.swift:783`).
- [x] **List nesting, UNBOUNDED depth, flattened** — depth comes from an indent
      STACK, not `indent / 2`, so 2-space and 4-space nesting both give depth 1
      (`:369-382`, tests `:122`, `:135`). PROBE: 2/4/6-space nesting gives
      depths 0,1,2,3. Rendered as `padding(.leading, DSSpacing.md * depth)`
      (`MarkdownView.swift:94`) — a flat row list with an indent, **not** nested
      `<ul>`s.
- [x] **Loose lists** — a blank line does NOT end a list (`:199-212`, tests
      `:205`, `:219`); a bullet run followed by an ordered run IS two lists
      (`:326-334`, PROBE).
- [x] **Lazy continuation lines** — a line indented past the innermost marker
      joins the item above, joined with `"\n"` (`:279-285`, `:102-104`, tests
      `:151`, `:163`). An UNINDENTED plain line ends the list (`:287-294`, test
      `:175`).
- [x] **GFM tables** — header row + delimiter row required; a pipe line alone is
      a paragraph (`:255-264`, `:616-640`, test `:399`). Per-column alignment
      `:---`, `:---:`, `---:`, default leading (`:591-614`, test `:342`).
      Leading/trailing pipes optional (`:584-589`, test `:351`). `\|` escapes a
      pipe inside a cell (`:548-574`, test `:378`). Ragged rows are **padded and
      truncated to `headers.count`** (`:125-135`, test `:362`). Header +
      delimiter with **zero rows is a valid empty table** (streaming, test
      `:387`). Table ends at a blank line or a pipe-less line (`:630-638`).

Explicitly NOT supported (all verified):

- [ ] **Indented (4-space) code blocks** — PROBE: `"    let x = 1"` -> a normal
      `paragraph` with the indent stripped. Common sense says these work. They
      do not.
- [ ] **Nested blockquotes** — PROBE: `"> outer\n> > inner"` -> one `.quote`
      whose second line is the literal text `"> inner"`. The inner `>` is drawn
      as a character.
- [ ] **Any block structure inside a blockquote** — PROBE: `"> - one\n> - two"`
      -> `.quote(["- one", "- two"])`; `"> ## Title"` -> `.quote(["## Title"])`.
      The `-` and `##` are literal text.
- [ ] **HTML blocks** — PROBE: `"<div>\nhi\n</div>"` -> one `paragraph` holding
      those characters.
- [ ] **Link reference definitions / reference links** — PROBE: `[x][ref]` plus
      `[ref]: https://...` renders literally as `[x][ref] [ref]: <link>`.
- [ ] **Footnotes** — PROBE: `text[^1]` and `[^1]: note` are literal text.
- [ ] **Images as blocks** — see §3; an image degrades to its alt text.
- [ ] **Headings h7+, YAML front matter, definition lists, admonitions, math,
      mermaid, code-block language labels** (the info string is thrown away by
      design, `MarkdownBlock.swift:19-24`).

## 3. INLINE-level checklist (Foundation, not this repo)

Only two attributes are applied by repo code (`MarkdownInline.swift:42-54`):
`.dsCode` font on an inline-code run, and `dsTextPrimary` + single underline on
a link run. Everything else is whatever Foundation's `inlinePresentationIntent`
says, and SwiftUI derives bold/italic/strikethrough from those intents.

PROBE results (`AttributedString(markdown:options:)` with the app's exact
options; `intent=` is the raw `InlinePresentationIntent` value —
`emphasized=1, stronglyEmphasized=2, code=4, strikethrough=32, softBreak=64,
lineBreak=128, inlineHTML=256, blockHTML=512`):

```text
bold                 **x**                  -> intent=2                 (test MarkdownParserTests.swift:419)
italic               _x_ / *x*              -> intent=1                 (test :425)
bold+italic          ***both***             -> intent=3
strikethrough        ~~gone~~  AND  ~one~   -> intent=32 (SINGLE tilde counts!) (test :431)
inline code          `bin/test`             -> intent=4, font .dsCode   (test :437)
inline link          [t](https://e.com)     -> link=..., underline .single, colour dsTextPrimary (test :447)
bare URL autolink    "go to https://e.com"  -> link=https://e.com       (NO test)
bare www autolink    "visit www.example.com"-> link=http://www.example.com
angle autolink       <https://e.com>        -> link=https://e.com
bare email           "a@b.com"              -> link=mailto:a@b.com
link with title      [x](https://e "Title") -> link only; title DROPPED
empty link text      [](https://e.com)      -> renders NOTHING at all
image                ![alt](https://x.png)  -> characters "alt", imageURL attr set, NOT drawn
image inside link    [![alt](i.png)](u)     -> "alt" with the LINK only; imageURL LOST
bold inside link     [**b** link](u)        -> one run, link only — the BOLD IS LOST
raw inline HTML      <b>x</b>, <br>, <script>  -> LITERAL characters, intent=256
escapes              \*not italic\*         -> literal "*not italic*"
HTML entities        &amp; &copy;           -> DECODED to "&" "©"
intraword underscore snake_case_word        -> left alone, no emphasis
backslash + newline  "a\\\nb"               -> intent=128 (lineBreak)
unclosed emphasis    "**unclosed bold"      -> literal characters
malformed link       "[unclosed]("          -> its own source text  (test :456)
```

Key inline consequences for the web:

- **Bare URLs, `www.` hosts and bare emails autolink.** Most JS Markdown
  renderers do not do this by default (GFM autolink extension required).
- **A single `~x~` is strikethrough.** GFM proper needs two.
- **Images render as alt text only.** `imageURL` is set by Foundation but
  `MarkdownInline` never reads it (`MarkdownInline.swift:42-54` handles only
  `.code` and `.link`), and no view draws it. A web client that renders `<img>`
  would show MORE than iOS.
- **Emphasis inside a link is lost** (PROBE `bold-in-link`). A web renderer
  keeping `<strong>` inside `<a>` diverges.
- **Soft breaks are preserved and drawn.**
  `interpretedSyntax:
  .inlineOnlyPreservingWhitespace` keeps them
  (`MarkdownInline.swift:22-25`) and paragraphs are joined with `"\n"` for
  exactly that reason (`MarkdownBlock.swift:393-395`, test `:47`). So the web
  needs `white-space: pre-wrap` or explicit `<br>` per newline — a default HTML
  paragraph would collapse them.
- **A parse failure never loses text**: the `catch` returns the raw source
  (`MarkdownInline.swift:28-40`).

## 4. Link handling and styling

- Links get `foregroundColor = .dsTextPrimary` and `underlineStyle = .single` —
  deliberately **not** an accent colour (`MarkdownInline.swift:46-53`, DESIGN.md
  §1/§8, test `MarkdownParserTests.swift:447`).
- **There is no tap handler, no `openURL` environment override, and no URL
  allow-list anywhere in the app** — verified by
  `grep -rn 'openURL|UIApplication.shared.open' --include=*.swift ios-app`
  returning **nothing**. Link activation is whatever SwiftUI's `Text` does with
  a link attribute (system browser). So there is no iOS precedent for in-app
  routing of `/colleges/123`-style relative links; a relative URL is kept
  verbatim as a link (PROBE `relative-link`).

## 5. How unsupported syntax is treated (one rule)

**It degrades to literal characters; nothing is ever dropped or escaped.**
Unknown block syntax becomes a `paragraph` holding the raw text; unknown inline
syntax stays as its own characters; a throw from the inline parser returns the
raw source. The only things deliberately REMOVED are: the fence info string
(`MarkdownBlock.swift:454`), heading `#`s, one `>` per quote line, list markers,
table pipes, and the `[ ]`/`[x]` checkbox token. The only thing that renders to
**nothing** is a `.rule` in plain-text copy (`:732-733`) and an empty-text link.

## 6. Sanitisation: NONE — loudest flag in this report

- No HTML escaping, no tag stripping, no URL scheme filter, no link rel/target
  policy exists in `ios-app/UnicoachiOS/Markdown/**` (whole directory read; 3
  files, 796 + 80 + 918 lines).
- PROBE: `[click](javascript:alert(1))` keeps `link=javascript:alert(1)`;
  `[click](data:text/html,<script>...)` keeps the `data:` URL.
- PROBE: `<script>alert(1)</script>` survives as literal characters with
  `inlineHTML` intent.

The iOS client is safe because SwiftUI renders characters. **A web SSR that
serialises this model to HTML must escape all text nodes and filter link schemes
to `http`/`https`/`mailto` (and decide on relative links) — otherwise coach
output, which is LLM-generated and therefore attacker-influencable through user
input, is a stored-XSS vector.** This is new policy the web must invent; there
is nothing to copy from iOS.

## 7. Reusable parser spec / test corpus

Yes — there is a real corpus, and it is the best thing the web can reuse:

- **`ios-app/UnicoachiOSTests/MarkdownParserTests.swift`** — 73 `func test`
  methods (`grep -c '    func test'`). Sections: Blocks `:14-256`, Inline
  `:417-464`, Accessibility `:466-496`, Degenerate input `:498-517`, Table
  layout decision `:519-739`, worst-case fixture `:740`. The block and inline
  tests (roughly `:14-517`) are directly translatable to a web test suite: each
  is `parse(source) == expected blocks`.
- **`ios-app/UnicoachiOS/Markdown/MarkdownView.swift:877-908`** —
  `MarkdownFixture.worstCaseReply`, the single canonical document exercising
  every block type (h2, bold/italic prose, nested bullets, task items, 5-column
  aligned table, `sh` fence with a line continuation, blockquote, link). Shared
  by the previews, `MarkdownParserTests.swift:740`,
  `MessageCopyTests.swift:235`, and the snapshot scene
  `conversation-markdown-worstcase`
  (`ios-app/UnicoachiOSTests/SnapshotScenes.swift:607`, RFC 122
  `rfc/122-ios-snapshot-gate.md:167`). Rendered PNGs exist at
  `ios-app/build/snapshots/latest/conversation-markdown-worstcase-{light,dark}.png`
  — use these as the visual target for the web.
- **`ios-app/UnicoachiOSTests/MessageCopyTests.swift`** — 35 tests over
  `MarkdownPlainText`, the second reader of the same model
  (`MarkdownBlock.swift:663-795`): the plain-text rendering used by the bubble's
  Copy action. If the web adds "copy as text", this is the exact spec (bullets
  U+2022, 2 spaces per depth, tables tab-separated, rules dropped).
- **Design intent prose**: `rfc/118-ios-markdown-rendering.md`,
  `rfc/120-markdown-no-horizontal-scroll.md`, `rfc/125-message-copy.md`. RFCs
  are historical (see CLAUDE.md); the code above wins on conflict.

There is **no** machine-readable spec file (no JSON/YAML corpus) — the tests are
Swift. A slice that wants a shared corpus must extract one.

## 8. Rendering decisions the web will be tempted to get wrong

- **Headings collapse to two sizes**: h1/h2 -> `dsTitle`, h3-h6 -> `dsLabel`
  (`MarkdownView.swift:54-58`). Six HTML heading sizes would not match.
- **Blockquote is a leading 1pt vertical hairline + secondary text colour**, no
  tinted background (`MarkdownView.swift:130-140`).
- **Code block is outlined, never filled, and WRAPS rather than scrolls**
  (`MarkdownView.swift:144-164`, `:859-867`; RFC 120).
- **Wide tables become one block per row** ("stacked"), never a horizontal
  scroller; the decision is a measured width algorithm in
  `MarkdownView.swift:203-540` with `MarkdownTableLayout` tests at
  `MarkdownParserTests.swift:519-739`. In the stacked form the first column
  becomes the block heading and every other cell is drawn as `Header: value`
  (`MarkdownView.swift:462-540`). **A web `<table>` with `overflow-x: auto` is
  the exact thing RFC 120 removed.**
- **Table cell VoiceOver labels are `"Header: value"` built from RENDERED
  characters** (`MarkdownView.swift:793-814`), so the web's a11y markup should
  do the same.
- Block spacing `DSSpacing.sm`, list-item spacing `DSSpacing.xs`, list indent
  `DSSpacing.md * depth`, fixed marker column `DSMarkdown.markerWidth`
  (`MarkdownView.swift:15`, `:67`, `:77`, `:94`).

## 9. Not verified / open

- **Foundation's parser is closed source and OS-version dependent.** My probes
  ran on macOS 15 / Swift 6.2.3, not on an iOS 17/18 device. The exact inline
  behaviour on an older iOS is **unverified**; treat the table in §3 as "what
  Apple's parser does today on this toolchain".
- **What SwiftUI actually does when a `javascript:` link is tapped** — not
  tested (no device run here). Assumed: system `openURL` refuses it.
- **Whether `imageURL` runs are visually distinguishable at all** — I confirmed
  no code reads the attribute; I did not render a screen to confirm nothing
  appears.
- **Streaming/SSE equivalence**: the parser's partial-input behaviour (unclosed
  fence, header-only table) is a _streaming_ requirement
  (`MarkdownBlock.swift:151-160`). Whether the web streams at all is out of my
  scope; if it does, it must reproduce these three behaviours.
- I read only `ios-app/UnicoachiOS/Markdown/**` plus the two test files and
  `bin/test-ios`. Any Markdown handling elsewhere (server-side, admin-web,
  public-web) was not surveyed — `grep` for `markdown` outside `ios-app` found
  only vendored `prettier` files under `var/`, so I believe there is none, but
  that is a negative result from one grep.
