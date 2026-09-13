# RFC 200 — No link to a grave

Brief 0009 "Say it once", slice `concise/10/no-link-to-a-grave`. Gate decision
D18 (Ian, 2026-09-11, verbatim): _"spec link rot. I'm okay saying something and
not linking if the link is dead."_

## The defect, and it is LIVE, not latent

RFC 196 gave the Family Cost Report a link to each college's own Common Data Set
filing. `AssuranceTierCopy.statementOf(VOLUNTARY_SELF_REPORT)` ends:

> "The college published this about itself. No one checks it. **We link to the
> college's own file so you can see it.**"

That sentence is byte-frozen against the immutable v19 prompt row
(`SystemPromptCatalogTest`) and cannot be reworded.

Brief 0009 recorded that the sentence is "vacuous on the page today and must
stay true the day a CDS figure reaches it". RFC 196 established that **for the
page**, and the brief generalised it to the whole surface. **That generalisation
is wrong, and this RFC leads with the correction.** Walked, not assumed:

- `AidPolicyWire` emits `AssuranceTierCopy.statementOf(figure.assurance)` and
  `putCitation(policy.source)` **into the same JSON object**
  (`AidPolicyWire.kt:171` and `:131`, re-derived from the landed file).
- An aid-policy figure's cell is `PublishedCell.SelfPublished`, whose source is
  `MoneySource.COMMON_DATA_SET` and whose tier is
  `AssuranceTier.VOLUNTARY_SELF_REPORT` (`PublishedCell.kt:108-114`).

So the coach is being handed that sentence beside a url **right now**, and for
fourteen filings that url is a 404. The promise is not vacuous; it is broken on
the chat surface today. The page is the surface that was fixed; the wire is the
surface that was measured and forgotten.

## What is already true, measured

Measured by `bin/cds-link-check` itself on **2026-09-13**, not estimated, and
not copied from an earlier report:

| Fact                                | Number                                     |
| ----------------------------------- | ------------------------------------------ |
| Rows in `db/seed/cds/*.csv`         | 5,048                                      |
| **Distinct `source_url` documents** | **441**                                    |
| `found` (2xx in either pass)        | 417 documents                              |
| **`not_found` (404/410 in BOTH)**   | **15 documents = 237 rows = 4.7% of rows** |
| `unknown` (no verdict; link KEPT)   | 9 documents = 141 rows                     |

**The brief's "5,048 urls" is a ROW count wearing a document count's clothes.**
One college's filing answers many facts, so probing per row would ask Princeton
the same question eleven times. Recorded as spec defect SD11 in brief 0009; this
RFC uses 441 and says which number is which everywhere it appears.

The fifteen `not_found`, from the committed file and not from memory: seven
expiring `public.boxcloud.com` signed links, plus `ir.princeton.edu`,
`oir.brown.edu`, `oira.jhu.edu`, `abpa.tamu.edu`, `uoia.asu.edu`,
`www.apsu.edu`, `www.usj.edu`, and one `drive.usercontent.google.com` download.

The nine with no verdict are seven `doc-*-sheets.googleusercontent.com` signed
exports answering 400, and **two** universities answering **403** to a script —
Akron and Williams. **A bot-blocked university is not a dead filing.** Calling
that "not found" would print OUR gap as the school's silence, which RFC 166 §6
forbids and which this brief has now protected three separate times (RFC 166,
RFC 182, here). They keep their links.

Note what moved between the design probe and the committed run: Johns Hopkins
answered 403 to the first sweep and 404 to both passes of the run that was
committed, and Virginia and Valparaiso — 403 in the probe — answered normally.
That is the corpus telling us the same thing the two-pass rule says: **one probe
is not evidence**, and a status is a fact with a DATE on it, not a property of a
college.

Two urls FLIPPED between the two probe shapes during design (Michigan State 404
→ 206, Saint Joseph 403 → 404). **One probe is not evidence**, which is the
whole reason the verdict requires agreement.

`source_documents` (RFC 170, `db/schema/0087.create-aid-form-requirements.sql`)
already stores `source_url TEXT NOT NULL` and a nullable `archive_url`.
`CdsSeedLoader` writes those rows. RFC 196's `CdsSeedLoaderTest` already sweeps
the committed seed, so a corpus-wide build-time assertion is a pattern that
exists and passes.

## Detailed Design

### D1 — the check runs at INGEST, in an operator's hand, and NEVER at render

`bin/cds-link-check` (stdlib python3, `getopt` grammar, all logging on stderr,
product = a file, empty stdout — the `bin/` conventions, sharing
`bin/pyfunctions.py` with `bin/fetch-cds-seed`) sweeps the distinct `source_url`
values of the committed seed and writes one reviewable, diffable file:

    db/seed/cds/source-url-status.csv   source_url,source_url_status,checked_on,pass_one,pass_two

A family must never wait on a college's web server, and a report must never be
slower because a school's site is down. There is **no network call anywhere on
the render path**, and a test asserts it rather than trusting it.

The two pass columns are kept so a verdict can be re-read later without
re-fetching: `404,404` and `403,403` are different facts about us, and a file
that recorded only the verdict would lose the difference.

### D2 — the verdict, and why `not_found` is narrow

| Verdict            | Rule                                  | Effect on the link |
| ------------------ | ------------------------------------- | ------------------ |
| `found`            | 2xx in EITHER pass                    | linked             |
| `not_found`        | 404 or 410 in **BOTH** passes         | **not linked**     |
| `unknown` + a date | anything else, or the passes disagree | linked             |
| `unknown`, no date | the url is ABSENT from the file       | linked             |

Two passes differ in the two dimensions that actually moved a verdict: pass one
is a HEAD as a generic agent (falling through to a ranged GET where the host
refuses the method rather than the file), pass two is a ranged GET as a browser.

**THREE states, not four, and the DATE carries the fourth distinction** (Ian, at
the gate). An earlier draft of this RFC had a fourth value for "we asked and got
no usable answer". It did no work: it behaves exactly like "we never asked" —
both KEEP the link — and the two are already told apart by the column beside it.
Two states distinguished only by another field is one state too many, and the
next reader has to ask which unknown to use.

    unknown + NO date  -- we never asked
    unknown + a date   -- we asked and got no usable answer
    found / not_found  -- we asked, and the date says when

So RFC 182's rule still holds — a check that quietly did not run must never look
like one that passed — because "never asked" and "asked and failed" remain
distinguishable in the data. The ingest summary prints the two counts
**separately**, so a BROKEN checker (many dated unknowns) cannot be mistaken for
stale data (many undated unknowns).

### D3 — the script REFUSES to write an implausible verdict

A checker that cannot reach the network must not mark everything dead. Two
refusals, both fatal, both writing nothing:

- **nothing `found`** — an offline laptop, a captive portal, blocked egress;
- **more than 20% `not_found`** (`DEAD_SHARE_CEILING`) — a hijacked resolver or
  an interception proxy answering 404 for everything is indistinguishable from a
  real 404 per request and obvious as a share.

The ceiling refusal names a **bounded sample** of the urls it would have called
dead, so the operator's next move is reading five lines rather than re-running a
441-document sweep.

**Certificates are verified**, and that belongs to this guard rather than to the
transport. An interception proxy answering 404 for everything is exactly the
failure these refusals exist to catch, and disabling verification invites the
thing we are defending against. A TLS fault is therefore what it honestly is —
no verdict, so `unknown`, so the link is KEPT. A college with an expired
certificate has not withdrawn its filing.

Writing nothing keeps every link, because an absent row loads as `unknown` with
no date — we never asked. That is the fail-closed direction: our gap costs us a
missing FACT, never a college a link it still deserves.

### D4 — the result is RECORDED on the row it is about

`source_documents` gains two columns and one Kotlin enum. **This is the DDL Ian
approves at the /ship gate** (chart non-negotiable 6, brief 0001 D10). No new
table, no new seeded row:

```sql
ALTER TABLE source_documents
  ADD COLUMN source_url_status TEXT NOT NULL DEFAULT 'unknown'
    CHECK (source_url_status IN ('unknown', 'found', 'not_found')),
  ADD COLUMN source_url_checked_on DATE NULL,
  ADD CONSTRAINT source_documents_checked_when_known
    CHECK (source_url_status = 'unknown' OR source_url_checked_on IS NOT NULL);
```

**Ian's own revision at the gate, in three parts.** The column is
`source_url_status`, not `..._liveness`: "liveness" beside "found" reads oddly,
and `found`/`not_found` mirror the HTTP codes they are derived from — 404 IS Not
Found — so the mapping is obvious to whoever debugs this later. The states are
three, not four (D2). And the constraint is **stronger** than the biconditional
this RFC first proposed: **you cannot claim to know without saying when you
looked.** `found` and `not_found` both REQUIRE a date; `unknown` may carry one
or not, and which it carries is the "never asked" / "asked and failed"
distinction.

`TEXT` + `CHECK IN (...)` plus exactly one
`enum class SourceUrlStatus(val value: String)` with a `fromValue` companion in
`db/.../models` — the schema convention for an own enumeration, never invented
integer codes. A per-table `CHECK` rather than a DOMAIN: unlike `money_source`,
this list describes ONE column on ONE table and nothing else in the schema asks
the question.

`checked_on` is a DATE and not a boolean because staleness is the thing an
operator will need to see; the freshness of the file is a fact about US.

### D5 — ONE predicate, in the type that holds the url

`CdsCitation` gains `sourceUrlStatus: SourceUrlStatus` — a required constructor
parameter, no default, so every construction site states it. `linkTarget()` is
unchanged (it still FAULTS on a non-web scheme, RFC 196 D5a). Beside it:

```kotlin
/** Whether we are still willing to point a family at this filing. The ONE place that decides. */
val linksToFile: Boolean
  get() =
    when (sourceUrlStatus) {
      SourceUrlStatus.FOUND, SourceUrlStatus.UNKNOWN -> true
      SourceUrlStatus.NOT_FOUND -> false
    }

/** The href for a filing we still point at, or null where we do not. */
fun linkTargetOrNull(): String? = if (linksToFile) linkTarget() else null
```

**An exhaustive `when` with no `else`, and that is the whole point** (Tier 2
review). Written as `sourceUrlStatus != NOT_FOUND` — which is how this first
shipped — a fourth status would LINK by default, silently, which is the exact
opposite of the fail-closed rule this RFC exists to enforce. Written as a
`when`, a fourth status fails to compile here, at the seam every surface funnels
through. The same rule holds for the date requirement, which lives once on the
enum as `requiresCheckedOn` rather than as two `==` tests that could disagree.

`linksToFile` is checked BEFORE the scheme, so a dead url that is also poisoned
answers "no link" rather than faulting a page over a link nobody will render.
That ordering is deliberate and it is tested.

### D6 — the page says the citation and drops the anchor

`CostReportPage.sourceLink` gains the one branch:

```kotlin
private fun FlowContent.sourceLink(citation: CdsCitation) {
  val target = citation.linkTargetOrNull()
  if (target == null) {
    +citation.citedAs
  } else {
    a(href = target) {
      rel = "noreferrer"
      +citation.citedAs
    }
  }
}
```

**The same words either way.** A family still learns whose claim it is, in which
cycle, from which document; only the anchor goes (D18, and brief 0009 D1 — no
figure, status, tier, cited year or publisher becomes unreachable, and a
restatement may go).

### D7 — the byte-frozen sentence is not SAID about a filing we did not link

The frozen string is split in place, byte for byte, and the whole third sentence
is DROPPED for an unlinkable filing. It is never reworded:

```kotlin
/** Whether the surface rendering this tier's sentence is actually offering the college's file. */
enum class FilingLink { LINKED, NOT_LINKED }

/** The frozen self-report copy, minus the promise -- a DELETION of a whole sentence, never a reword. */
const val SELF_REPORT_WITHOUT_LINK = "The college published this about itself. No one checks it."

/** The promise RFC 196 kept. Said only where the link is actually rendered. */
const val SELF_REPORT_LINK_PROMISE = " We link to the college's own file so you can see it."

fun statementOf(tier: AssuranceTier, filing: FilingLink): String = when (tier) { ...
  AssuranceTier.VOLUNTARY_SELF_REPORT -> when (filing) {
    FilingLink.LINKED -> SELF_REPORT_WITHOUT_LINK + SELF_REPORT_LINK_PROMISE
    FilingLink.NOT_LINKED -> SELF_REPORT_WITHOUT_LINK
  }
}
```

`statementOf(tier, LINKED)` is byte-identical to what shipped before this RFC,
so the v19 prompt pin does not move and no prompt version is minted. Three tests
hold the shape: the LINKED string equals the frozen literal written out in the
test, the NOT_LINKED form is a **strict prefix** of it, and the promise sentence
appears in exactly one place in the source set, counted as the SENTENCE rather
than as one spelling of a call (brief 0009 P7). A prefix assertion is what makes
a reword unable to pass — a reworded sentence is not a prefix of the frozen one.

**The second parameter is REQUIRED, and that is the design** (Tier 1 review).
This RFC first shipped a second entry point,
`statementOfUnlinkableSelfReport()`, and defended it as "a convention, not a
type guarantee" — RFC 196 D4's phrasing, borrowed for a case that did not need
to settle for it. The reviewer was right: the promise sentence was reachable
from four more callers holding no filing at all, and one fact was being decided
in three places. Making `filing` a required parameter turned all five production
call sites into compile errors that had to be answered one at a time. The four
that hold no CDS filing pass `LINKED` and say why in a comment: their tiers'
sentences promise no link, so the parameter cannot make them lie.
`statementOfUnlinkableSelfReport()` is deleted — ONE function decides.

### D8 — the wire chooses from the citation it already holds

`AidPolicyWire.putAssurance` takes the citation as well as the figure, so the
tier sentence cannot be chosen without the filing in hand (RFC 190's shape: the
attribution travels with the figure, rather than being looked up in a side map
that a renderer can forget). And `putCitation` stops handing the coach a url we
know is dead:

```kotlin
fun JsonObjectBuilder.putCitation(citation: CdsCitation) {
  put("cited_as", citation.citedAs)
  if (citation.linksToFile) {
    put("url", citation.url)
  } else {
    put(SOURCE_FILE_NOTE_KEY, SOURCE_FILE_NOT_FOUND_NOTE)
  }
  citation.archiveUrl?.let { put("archive_url", it) }
}
```

Both the page and the wire ask `linksToFile` — the D5 predicate — so the anchor
and the promise cannot disagree. `putAssurance` asks the same property rather
than re-deriving `NOT_FOUND` itself; that re-derivation was a Tier 1 finding and
it is gone.

`SOURCE_FILE_NOT_FOUND_NOTE` = _"The college's own file for this filing is no
longer online, so there is nothing to link."_ — a new, unfrozen sentence.
Omitting the key silently would leave the model free to invent a link; saying it
is what makes "we cannot link this one" a fact the coach can state.

### D9 — `archive_url` is still not offered in words, and RFC 170's comment is wrong

RFC 196 D1 decided `archive_url` is not rendered on the page. This RFC does not
offer it in words either, and the reason is a correction worth recording: the
seeded `archive_url` values point at **`collegedata.fyi`**, a third-party
corpus, not at a mirror this project owns. `source_documents.archive_url`'s
column comment calls it "our mirror". That phrasing is inaccurate, it is RFC
170's to own, and it is **not fixed here** — recorded as an open item for
/chart. Offering a family "our copy" of something we do not host would be a
worse promise than the one this RFC is repairing.

### D10 — the file is a SNAPSHOT, and it says so

Nothing in this RFC re-runs the check automatically. An operator runs
`bin/cds-link-check` and commits the diff, exactly as `bin/fetch-cds-seed` is
run. The spec left re-run frequency to design, and the answer is **manual, for
now**: a snapshot that goes stale and admits it (`checked_on` is on every row)
beats a scheduler nobody asked for. A periodic re-check is a separate slice, and
this RFC does not smuggle one in.

### D11 — what the ingest run SAYS, and one known gap

`IngestApplication` logs the check as one line, and the two unknowns are never
summed:

    CDS link check: [documents=] [found=] [not_found=] [unknown_never_asked=] [unknown_no_answer=] [status_rows=]

`unknown_never_asked` is an unknown with NO date, `unknown_no_answer` is an
unknown WITH one, and `status_rows` is the sidecar's own row count — so "441
documents / 0 status rows" is visible at a glance and a BROKEN checker cannot
read as a stale sidecar.

**The gap, stated rather than hidden.** `CdsSeedLoader.load` finds the sidecar
beside the seed csvs it was handed; it is not a fifth `--cds-*` flag, because
that would change the provenance digest set and the seven-source contract
`CdsSources` carries. An ingest run from the repo's own `db/seed/cds/` paths
picks it up. An ingest that stages the four seed files into a scratch directory
WITHOUT the sidecar records "never asked" for every document — fail-open, and
LOUD (`unknown_never_asked=441` in the summary), never silent. Adding the flag
is a follow-up, not a hole this RFC papers over.

### D11 — rollback is WHOLE

Reverting the code reverts the behaviour: the column defaults to `unknown`,
which keeps every link, and the seed sidecar is inert without the loader that
reads it. There is no half state where a moved rule lives in neither home.

### D12 — what the loader REFUSES, and one type that mirrors the constraint

Widened after Tier 0 review, which was right to ask where these came from: the
implementation refuses more than the plan named, and the extra refusals earn
their place.

`CdsSeedLoader` carries **located** defects for the sidecar, in the shape it
already uses for every other seed file — a bad header, a value that is none of
the three states, a verdict with no day, a day that is not a date, a duplicate
url, and two rows claiming different states for one url. Each names the file and
the line. The alternative is a silent `unknown`, which is the one outcome this
RFC exists to remove: a corpus defect that reads exactly like "we never asked".

`NewSourceDocument` carries an `init { require(...) }` mirroring
`source_documents_checked_when_known`, so the illegal pair — a `found` or
`not_found` with no day — is unconstructable in Kotlin and not merely rejected
by Postgres. The database is the authority; this is the same rule stated where a
caller meets it, which turns a constraint violation at ingest into a
compile-time shape and a unit-testable refusal.

## Files Modified

| File                                                                                                                             | Change                                                                                        |
| -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `bin/cds-link-check`                                                                                                             | new: the two-pass ingest-time sweep, its refusals, and the sidecar it writes (D1-D3).         |
| `db/seed/cds/source-url-status.csv`                                                                                              | new, generated: 441 documents, 417 found / 15 not_found / 9 unknown, `checked_on` 2026-09-13. |
| `db/schema/record-source-url-status.sql` + `db/schema/ORDER`                                                                     | new migration: the two columns and the paired CHECK (D4). No number — RFC 180.                |
| `db/src/main/kotlin/ed/unicoach/db/models/SourceUrlStatus.kt`                                                                    | new: the own enumeration, `value` + `fromValue` (D4).                                         |
| `db/src/main/kotlin/ed/unicoach/db/models/SourceDocument.kt` (+ `New...`)                                                        | the two fields on the record the loader writes and the dao reads.                             |
| `db/src/main/kotlin/ed/unicoach/db/dao/SourceDocumentsDao.kt`                                                                    | write and read the two columns.                                                               |
| `db/src/main/kotlin/ed/unicoach/db/dao/{AidPolicyDao,BorrowingDao,CdsAdmissionsDao}.kt`                                          | select the liveness alongside `source_url`/`archive_url` so the citation can be built.        |
| `college/src/main/kotlin/ed/unicoach/college/CdsSeedLoader.kt`                                                                   | read the sidecar, join it by url, default `not_checked` for an absent url.                    |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CdsCitation.kt`                                                         | `sourceUrlStatus` field; `linkTargetOrNull()`; `putCitation`'s NOT_FOUND arm (D5, D8).        |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/AssuranceTierCopy.kt`                                              | the split constants and `statementOfUnlinkableSelfReport()` (D7).                             |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/AidPolicyWire.kt`                                                            | `putAssurance` takes the citation and chooses the sentence (D8).                              |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/{BorrowingAtGraduation,AidPolicyPractice}.kt`, `CollegeAdmissionsService.kt` | pass `liveness` at the four construction sites.                                               |
| `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`                                                            | `sourceLink`'s one branch: the citation as text where the file is NOT_FOUND (D6).             |
| tests + fixtures                                                                                                                 | see Tests.                                                                                    |

Not modified: `prompts/coach-system-prompt.md`. No prompt version is minted, no
copy is reworded, and nothing in the prompt file is touched.

## Implementation Plan

1. `bin/cds-link-check`, then RUN it and commit its output. The committed file
   is the script's product, never a hand-written table.
2. The migration, `SourceUrlStatus`, `SourceDocument`/`NewSourceDocument`,
   `SourceDocumentsDao`, and the three reading daos.
3. `CdsSeedLoader`: the sidecar read, the join, the absent-url default, and the
   header assertion it already applies to every seed file.
4. `CdsCitation`: the field, `linkTargetOrNull()`, `putCitation`'s NOT_FOUND
   arm; then the four construction sites.
5. `AssuranceTierCopy` split; `AidPolicyWire.putAssurance` takes the citation.
6. `CostReportPage.sourceLink`'s branch, and the widened source sweep.
7. Tests, then `nix develop -c bin/format`, then the gate.

## Tests

- **`CdsLinkCheckTest` arms in `bin/scripts-tests`** — `-h` to stdout and exit
  0; a normal run writes NOTHING to stdout; unknown option 10, option without
  value 11, positional 20, `-c banana` / `-c 0` / `-t 0` 22. The sweep itself
  runs against a **local fixture http server** and a fixture seed dir, so no
  test touches the public internet: one url served 200, one served 404, one
  served 403, one refusing HEAD. Asserts the verdicts, and asserts the 403 keeps
  its link. Plus the two refusals of D3: an all-404 fixture writes NOTHING and
  exits non-zero, and a fixture where every url is unreachable writes NOTHING —
  the guard this project cares most about, asserted by the ABSENCE of the file.
- **`CdsSeedLoaderTest`** — the sidecar header is asserted like every other seed
  header; a url present in the sidecar loads its verdict; a url ABSENT loads
  `unknown` with NO date; the committed sidecar covers every distinct
  `source_url` in the committed seed (441) and answers for nothing else. The
  corpus counts are MEASURED and asserted against the FILE rather than frozen as
  literals, so a legitimate re-run of the checker does not break the suite while
  a sidecar that stops covering the corpus still does.
- **`AssuranceTierCopyTest`** — `statementOf(VOLUNTARY_SELF_REPORT)` equals the
  frozen literal byte for byte; `statementOfUnlinkableSelfReport()` is a STRICT
  PREFIX of it; the promise sentence occurs in exactly one place in the
  `:service` main source set (counted as the SENTENCE, not one spelling of a
  call — brief 0009 P7).
- **`SystemPromptCatalogTest`** — unchanged and still green: the nine
  byte-frozen sentences are untouched and the v19 row is not re-cut.
- **`AidPolicyWireTest`** — a NOT_FOUND citation emits no `url` key, keeps
  `cited_as`, emits `source_file_note`, and its assurance statement does NOT
  contain the promise sentence; a FOUND citation is byte-identical to today's
  payload. This is the surface where the defect is live, so this is the test
  that proves the fix.
- **`CostReportPageTest`** — a NOT_FOUND merit citation and a NOT_FOUND
  borrowing citation each render `citedAs` as text with **zero** anchors on the
  page, and the page still names the college and the cycle; a FOUND citation is
  unchanged; an UNKNOWN citation keeps its link; the RFC 196 source sweep is
  widened to count the `href` ATTRIBUTE (not one call spelling) and to allow the
  text branch without allowing a second anchor.
- **No render-time network** — a source sweep over the render and read path
  asserting no http client type is named there, so "checked at ingest, never at
  render" is a tested property rather than a paragraph in an RFC.
- **`AssuranceTierClosureTest`** — still pins that `VOLUNTARY_SELF_REPORT` is
  reachable only from `MoneySource.COMMON_DATA_SET`. That closure is what makes
  the wire the only surface needing D7, so the claim is asserted rather than
  assumed.
