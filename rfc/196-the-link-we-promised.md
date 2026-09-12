# RFC 196 — The link we promised

Brief 0009 "Say it once", slice `concise/08/the-link-we-promised`. Gate decision
D15 (Ian, 2026-09-11, verbatim): _"We should link to our sources."_

## The defect

`AssuranceTierCopy.statementOf(VOLUNTARY_SELF_REPORT)` ends:

> "The college published this about itself. No one checks it. **We link to the
> college's own file so you can see it.**"

The Family Cost Report renders **no link at all** —
`grep -c href
public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`
is **0**. We make a promise in our own words, on the surface a logged-out parent
reads, and do not keep it. The sentence is byte-frozen against the immutable v19
prompt row (`SystemPromptCatalogTest`), so it cannot be reworded. The link has
to exist.

## What is already true, measured

- `source_documents` (RFC 170) stores `source_url TEXT NOT NULL` (CHECK `<> ''`)
  and `archive_url TEXT NULL`. **No table, column, migration or seeded row is
  added by this RFC**, and no prompt version is minted.
- `CdsCitation(collegeName, sourceYear, url, archiveUrl)` already carries both
  urls to every consumer, and `putCitation` already emits `url` and
  `archive_url` to CHAT for admissions, merit, borrowing and aid policy. **The
  chat side is not the gap.**
- The page is the gap, in two shapes:
  - **Merit** — `CostReportPage.meritBlock` holds the whole `CdsCitation` and
    prints only the label:
    `p("report-source") { +"Source: ${merit.source.citedAs}." }`. The url is in
    hand and thrown away at the point of render.
  - **Borrowing** — `BorrowingBlock.header` is a **flattened `String`**, built
    in the service by `blockHeader(coverage.figures.source)`. The citation is
    collapsed to prose before the page sees it, so the page cannot link it even
    if it wanted to. This is the RFC 177 defect shape: a fact read from the
    store and dropped a layer later.
  - **Aid policy** — not rendered on this page at all (`grep -n aidPolicy` in
    `CostReportPage.kt` is empty). It is a chat surface and already emits both
    urls through `putCitation`.

### Where the byte-frozen sentence can actually be rendered

Walked, not assumed. `VOLUNTARY_SELF_REPORT` is reachable only from
`MoneySource.COMMON_DATA_SET` (`PublishedCell.kt:111-113`,
`AssuranceTierClosureTest` pins that the Scorecard and both IPEDS surveys never
produce it).

| Surface                                                                                          | Can render the sentence?                                                                                                                     | Carries a url today?    |
| ------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| Coach payload (`CollegeCostChatTool`, `AidPolicyWire`, `BorrowingWire.objectOf`, `MeritAidWire`) | yes                                                                                                                                          | **yes** — `putCitation` |
| Family Cost Report page                                                                          | only through `blankFor`/`tierKindsSection`, over `CostField` statuses — and no `CostField` is CDS-filled, so **not today**                   | **no**                  |
| Fit-lens digest (`FitLensService.statusFields`)                                                  | reads the net-price row only, whose source is ranked by `CanonicalMoneyLoader.ORDERED_SOURCES` (CDS is excluded, `SOURCES_FILLED_ELSEWHERE`) | n/a                     |

**Brief 0009's acceptance criterion for this slice is vacuous on this page, and
could not have failed.** It reads "wherever the `VOLUNTARY_SELF_REPORT` sentence
can be rendered, a link to that college's own file must be rendered too". That
sentence cannot be rendered on the Family Cost Report at all, so the criterion
as written was satisfied by doing nothing. It was written against the surface
the brief imagined rather than the one that exists — the seventh pre-measurement
claim this brief has had to correct, and the first that was wrong about
behaviour rather than about a number (accepted by Ian at the RFC 196 gate).

**The real broken promise is one level down**: the page prints two self-reported
CDS attributions per school — merit and borrowing — with no link. That is what
this RFC fixes. The literal criterion is kept as a test, stated as an
**implication**: if the body contains the voluntary sentence, the body contains
a link. It is vacuous today and load-bearing the day a CDS figure reaches this
page, at which point a promise with no link fails a test rather than reaching a
family. A vacuous TEST that becomes live later is worth keeping; a vacuous CLAIM
is not, which is why this paragraph says plainly that the criterion could not
have failed.

## Detailed Design

### D1 — the link target is `source_url`; `archive_url` is not rendered here

The sentence promises _"the college's own file"_. That is `source_url`.
`archive_url` is our mirror. It is **not** offered beside it on the page, and
never instead of it: a second link doubles the chrome on a parent-facing page
for a fallback nobody asked for, and the page's job is to keep the sentence we
said. Nothing becomes unreachable (brief 0009 D1) — `archive_url` is not
reachable on this page today either, and remains on the wire for every chat
consumer through `putCitation`.

### D2 — link TEXT is `citedAs`; the url is only ever the target

`CdsCitation`'s own KDoc: the url is _"wildly heterogeneous — PDFs,
spreadsheets, signed CDN links over a kilobyte long"_. Measured over the four
seed files: **5,048 rows, longest `source_url` 1,744 characters**, 5,031 https
and 17 http. It is never link text. The link text is the string the page already
prints — `CdsCitation.citedAs`, e.g. _"Amherst College's 2024-25 Common Data
Set"_ — so **the page gains a link and zero words**. The word ceiling does not
move (see Tests).

### D3 — `BorrowingBlock` carries the CITATION, not a prose header

RFC 190's guarantee is that the figures are UNREACHABLE without their
attribution — a property of the type, not a convention. This RFC keeps that
guarantee and **tightens** it: the unit of attribution becomes the citation
itself rather than a string derived from it.

```kotlin
data class BorrowingBlock(
  /** Who is claiming every figure below, in which cycle, from which document -- and WHERE that document is. */
  val citation: CdsCitation,
  /** The figures, said without re-naming the claimant. */
  val sentences: List<String>,
) {
  /** The whole attribution sentence, for a surface that cannot carry a link. */
  val header: String get() = BorrowingWire.blockHeader(citation)
}
```

A surface that CAN carry a link renders `citation` as the anchor and
`BorrowingWire.HEADER_TAIL` after it. That constant is **not** mirrored on the
record: it is the same for every block, so a pass-through property here would be
a rendering parameter riding on one filing's data.

`BorrowingWire.blockHeader(source) = "${source.citedAs}${HEADER_TAIL}"`, with
`HEADER_TAIL` the existing trailing clause, byte for byte. There is ONE
composition of that sentence, so the linked page form and the flat chat form
cannot drift; a test pins the identity.

The type is **not loosened**: `blockOf` remains the only way to reach
`sentences`, and a caller still cannot hold the figures without the claim they
are made under. It is strictly harder to render them unattributed than before,
because the attribution is now a typed citation rather than an opaque string.

### D4 — ONE link shape, two call sites

One private helper in the page, not two inline renders, so a third CDS block
cannot be added later with a different link shape:

```kotlin
private fun FlowContent.sourceLink(citation: CdsCitation) {
  a(href = citation.linkTarget()) {
    rel = "noreferrer"
    +citation.citedAs
  }
}

// borrowingParagraphs
p {
  sourceLink(block.citation)
  +"${BorrowingWire.HEADER_TAIL}."
}

// meritBlock
p("report-source") {
  +"Source: "
  sourceLink(merit.source)
  +"."
}
```

The target is `citation.linkTarget()`, never `citation.url` — see D5a. **This is
a convention, not a type guarantee, and it is stated as one**: `CdsCitation.url`
is a public property, so `a(href = citation.url)` still compiles and would
render exactly the url `linkTarget()` refuses. The type cannot carry the rule,
so a source sweep does: a test asserts that `CostReportPage.kt` opens exactly
ONE anchor, that it targets `citation.linkTarget()`, and that the file never
reaches for a raw `.url` at all. Scoped to that file, because the site chrome,
the home page and the verify-email page all open anchors legitimately.

The page authors no money copy: `citedAs` and `HEADER_TAIL` are the domain's own
strings, unchanged. `"Source: "` is the page chrome it already printed.

### D5 — the parent is logged out, and the token must not leak

The report route already sets `Referrer-Policy: no-referrer` explicitly _"so the
token stays out of the `Referer` of any link a parent follows from the page"_
(`Routing.kt:190-193`, pinned by `CostReportRoutingTest`). That header was
written for exactly this moment. `rel = "noreferrer"` is added on the anchor as
well — the header is on the ROUTE and the link is on the PAGE, and a page
rendered from a second route later would otherwise lose the guarantee silently.
A regression test asserts both.

### D5a — the scheme is asserted, and a violation FAULTS

`href` is the one attribute where escaping does not save a logged-out parent:
`javascript:` in an `href` is live whatever `kotlinx.html` does to the attribute
value, and `VerifyEmailPage`'s "no parsing or scheme validation" precedent is
about a url this service minted, not one read out of a seeded corpus.

**What counts as a web url** is `isWebUrl` in `:common`. The scheme question is
domain-agnostic and every `href` in this repo asks it — `PolicySource.url` and
`VerifyEmailPage`'s `openInAppUrl` are the two other sites that would otherwise
re-invent it — while **what a violation MEANS** is the domain type's, so the
located fault stays on `CdsCitation` and the predicate does not.

`CdsCitation.linkTarget()` asserts the scheme and throws
`UnlinkableSourceUrlException` — **located** (it names the college and the cycle
whose filing is unlinkable, the shape this repo uses for an unmapped source
code) and **typed**. It reaches a family as the branded 503 the report route
already renders for a read fault: the page stops rather than showing a link we
are not willing to stand behind.

Neither softer answer is available. A silent drop would leave us saying "we link
to the college's own file" beside no link, which is the exact defect this RFC
exists to end. Rendering it anyway would put an executable url in front of a
parent. **The alternative to this check is not "a missing link" — it is an
executable one.**

Failing closed costs nothing: all 5,048 rows of the committed seed are
`http`/`https`, so a row with any other scheme is evidence the corpus is wrong
or poisoned, not a case to degrade gracefully. The comparison is
case-**insensitive** — a scheme is case-insensitive in RFC 3986, so `HTTPS://`
is a link that works and refusing it would stop a page over nothing; the looser
comparison also catches `JavaScript:`, which a strict one would let through. The
url itself is handed over **verbatim**.

The check is **not** applied to `putCitation`: the wire hands the url to a
language model as data, where no scheme is executable, and a coach answer is not
the place to discover a bad seed row. The check is on the LINK path, which is
the path that can hurt somebody.

### D6 — a missing document is OUR gap

`source_url` is `NOT NULL` and a `CdsCitation` only exists where the join found
a `source_documents` row, so a rendered CDS attribution always has a url. Where
no document exists there is no citation, no merit block and no borrowing block —
the page says nothing about our own corpus coverage (RFC 166 §6), exactly as
today. There is no state in which the page states a CDS attribution it cannot
link.

## Files Modified

| File                                                                             | Change                                                                                                                           |
| -------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `common/src/main/kotlin/ed/unicoach/common/util/WebUrl.kt`                       | new: `isWebUrl`, the domain-agnostic scheme predicate (D5a).                                                                     |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CdsCitation.kt`         | `linkTarget()` and `UnlinkableSourceUrlException` — the located fault (D5a).                                                     |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt`            | `BorrowingBlock` carries `citation: CdsCitation`; `header` derived; `HEADER_TAIL` extracted; `blockOf` builds from the citation. |
| `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`            | one private `sourceLink` helper; `meritBlock` and `borrowingParagraphs` render the citation through it.                          |
| `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`               | the surface walk, the link assertions, the source sweep, the poisoned-url route arm, the ratcheted ceiling.                      |
| `public-web/src/test/kotlin/ed/unicoach/web/FakeCostReportSource.kt`             | per-school `url`/`archiveUrl` on the merit and borrowing fixtures.                                                               |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/BorrowingWireTest.kt`        | new: header identity, the citation-carrying block, the unchanged payload.                                                        |
| `service/src/test/kotlin/ed/unicoach/coaching/admissions/CdsCitationLinkTest.kt` | new: both arms of the scheme check.                                                                                              |
| `college/src/test/kotlin/ed/unicoach/college/CdsSeedLoaderTest.kt`               | the committed-corpus guard on every `source_url`.                                                                                |

No migration, no DDL, no seeded row, no prompt edit.
`prompts/coach-system-prompt.md` is **not touched** (cross-session freeze for
rfc-192).

## Implementation Plan

1. `BorrowingWire`: extract `HEADER_TAIL`, re-express `blockHeader`, change
   `BorrowingBlock` to hold the citation, update `blockOf`. `objectOf` keeps
   calling `blockHeader` and its wire bytes do not change.
2. Fix every `BorrowingBlock` construction/read in main and test sources.
3. `CostReportPage`: the two link renders, with KDoc naming D15 and the
   byte-frozen sentence.
4. Tests below.
5. `CdsCitation.linkTarget()` and `UnlinkableSourceUrlException` (D5a).
6. Measure the three-school word count. Measured: **1,404** (from 1,401). The
   ceiling is ratcheted UP by three with the reason stated under Tests — and the
   reason is that the counter changed, not the page.

## Tests

Measured, executed counts — `CostReportPageTest` **64** tests (was 59),
`CdsCitationLinkTest` **4** (new), `BorrowingWireTest` **3** (new),
`CdsSeedLoaderTest` **31** (was 30). `@Test` counts in the sources match the
JUnit XML `tests=` attribute in each case — checked, because a rewritten Kotlin
test can compile, format and "pass" without ever running.

- **The surface walk (the criterion that matters).** Render the three verbose
  schools, strip **every anchor element** from the body, and assert that no
  citation shape (`<College>'s <cycle> Common Data Set`) survives. It walks the
  surface rather than naming merit and borrowing, so a third CDS block added
  later joins the test by being rendered, not by being remembered. Matching the
  citation SHAPE, not the words: the standing sentence "each school's own Common
  Data Set" cites no particular filing and is not a citation.
- **The promise, as an implication** — see the vacuity paragraph above. The same
  test asserts unconditionally that six `rel="noreferrer"` links render (three
  schools × merit + borrowing), so it is not vacuous end to end.
- **Each block links that school's own file, never our mirror** — per-school
  `source_url`s, and `archive_url` asserted absent from the page.
- **A poisoned url renders nothing at all** — a `javascript:` `source_url`
  answers `503 Service Unavailable` and the string `javascript:` is absent from
  the body. The unit arms live in `CdsCitationLinkTest`: `http`/`https`
  (including `HTTPS://`) link verbatim; `https:alert(1)`, `http:evil`,
  `" https://…"` and `JavaScript:alert(1)` fault with a located message.
- **Header identity** —
  `blockOf(c).header == c.citedAs + headerTail ==
  blockHeader(c)`. One
  sentence, one composition.
- **The chat payload is unchanged** — the header string and both urls are still
  in `BorrowingWire.objectOf`.
- **`WORD_CEILING` 1,401 → 1,404, ratcheted deliberately.** The three are **not
  words**: the link text is the string the page already printed, so a parent
  reads exactly what they read before. What moved is the INSTRUMENT —
  `visibleWordsOf` replaces every tag with a space, so the sentence-final `.`
  after each merit block's `</a>` is now its own token. Three schools, three
  stray full stops. The instrument was left alone rather than adjusted to
  flatter the number: re-baselining it would silently move every figure in the
  KDoc chain above it.
- **`sourceLink` is the page's only anchor** — a source sweep over
  `CostReportPage.kt`: exactly one `a(href`, targeting `linkTarget()`, and no
  raw `.url` anywhere in the file. The convention D4 cannot express in the type
  is asserted over the file instead of written in a comment.
- **Every committed `source_url` is linkable** — `CdsSeedLoaderTest` walks
  `db/seed/cds/*.csv` (5,048 rows) and fails the build on one this page could
  not render.
- Full `nix develop -c bin/test`, no module argument, no `-f`.

## Open item — link rot

5,048 urls, the longest 1,744 characters, pointing at colleges' own PDFs. Some
are already dead and more will die. We hold `archive_url` as a mirror and
deliberately do not render it (D1), which is right for the promise as worded —
the sentence says "the college's own file". A parent meeting a 404 is a real
outcome, and nobody has decided about it. **Named, not fixed.** It belongs to a
product decision, not to this RFC.

## Invariants

None declared (retired 2026-08-06).
