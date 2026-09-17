# RFC 225 — The TypeScript rail

Slice `web/03/the-typescript-rail`, brief 0013 "Unicoach on the web". Lane A
(design-bearing). Base `main@8bca6fc0`.

## Why

Every line of client-side TypeScript this product will ever ship needs a build
path: sources, a type check, a lint, a test runner, a bundler, a name the page
can reference, and a gate that refuses a broken or stale bundle.
`web/04/the-chat` is the first real consumer and is hard enough without
inventing the toolchain inside it.

The slice has **no door**. No user reaches anything new. It is stated that way
in the spec and it is stated that way here; the evidence is a passing gate and a
hashed asset served at the right URL.

## What the repo has today (verified in this worktree)

- `deno.json` has exactly one key, `fmt` (lineWidth 80, proseWrap always, six
  excludes). No tasks, no lint config, no `deno.lock`, no `node_modules`.
- **Zero TypeScript in the tree.** This RFC adds the first.
- `deno 2.7.12` / TypeScript 5.9.2; `deno bundle` is STABLE. `--platform`
  defaults to `deno`, so `browser` must be passed. `deno bundle` does NOT
  type-check. Remote and npm imports resolve by default.
- public-web serves `staticResources("/", "static")` from the CLASSPATH
  (`Routing.kt:160`), registered last. The only asset is `static/site.css`.
  Packaging is stock `processResources`; no content hashing anywhere.
- The content-security policy lives in `:web-common`
  (`ContentSecurityPolicy.kt:30`), is ENFORCING, and reads
  `default-src 'self'; script-src 'self'; style-src 'self'; ...` with no
  `'unsafe-inline'`, no `'unsafe-eval'`, no nonce and no hash source. It is
  installed at the root of `routing {}` so it rides pages, the static mount and
  the error pages.
- `siteLayout(pageTitle, width: ShellWidth, noindex, content)` builds the one
  `<head>`. There is **no `<script>` tag anywhere in the repo** before this
  slice.
- `bin/format -c` is the single lint gate; its deno half formats
  `("**/*.md" ".claude" ".prime")` from `bin/common:109`.
- `bin/shell-tests` runs a DECLARED array of harnesses; `bin/gate-scope`
  (RFC 214) classifies the staged set and `fatal`s BY NAME on any
  `bin/*scripts-tests` it does not know.

## Decisions

**D-A — TypeScript lives inside the module, at `public-web/src/main/ts/`.** Not
a top-level `web-ts/`. `bin/gate-scope` classifies a staged set that lies
entirely inside Gradle modules' `src/` trees as `module-source`; a top-level
directory it does not name falls to `full`, so every future TypeScript-only
commit would pay the whole gate forever. Gradle ignores the directory (it is not
a source set), so nothing else changes.

**D-B — the bundle is a COMMITTED artifact, and the gate proves it is not
stale.** `bin/build-web-assets` writes
`public-web/src/main/resources/static/js/main-<hash>.js`, which stock
`processResources` then packages, so `installDist` and `bin/deploy` need no Deno
and the Gradle build gains no new external tool. The cost of a committed build
output is staleness, and that cost is paid by `-c`: `bin/build-web-assets -c`
rebuilds into a temporary directory and compares, exiting 3 when the committed
bytes differ from what the sources produce. The new harness runs it, so a commit
that edits TypeScript and forgets to rebuild fails the gate by name rather than
shipping last week's behaviour.

**D-C — the name reaches the page through a manifest, never a literal.**
`bin/build-web-assets` also writes
`public-web/src/main/resources/web-assets.properties` — on the classpath,
**outside** `static/`, so it is not itself served. A Kotlin `WebAssets` object
reads it once at class-init and exposes `WebAssets.MAIN`. A missing file, a
missing key, a value that is not a content-addressed name, or a value naming a
resource that is not on the classpath throws with a message naming
`bin/build-web-assets`. The page renders `src = WebAssets.MAIN`. No string in
Kotlin can drift from the artifact, because no string in Kotlin names it.

"At startup" is a property of the CALL SITE, not of the object, and saying it
does not make it so. A Kotlin `object` initialises on first TOUCH, and nothing
touched `WebAssets` until a page rendered — so a broken or stale manifest booted
GREEN and failed the first visitor, including on the branded 503, which renders
the same shell. `publicWebModule` therefore READS the bundle url while it
installs, before `configureRequestLogging`, and logs it. That one line is what
makes the claim true, for the daemon and for every `testApplication` boot alike.

The read goes through an injectable resolver, `bundleUrl: () -> String`,
defaulted to `{ WebAssets.MAIN }` so no call site has to remember it. The
default is what the daemon and every other test boot, and the seam is what makes
the claim TESTABLE as behaviour: a module booted with a resolver that throws
must fail at `startApplication()`, before any request. The claim used to be
asserted by a test that read `Application.kt` and looked for the name
`WebAssets.MAIN` in the function body — static analysis of source text, which
proves nothing about what the boot does.

The six refusals are DOMAIN STATES, not prose.
`mainFrom(manifest,
resourceExists)` is total and returns a sealed
`MainBundleResolution` — `Resolved`, `Absent`, `Unreadable`, `NoMainKey`,
`UnexpectedKeys`, `NotContentAddressed`, `NotPackaged`. The tests then assert
the VARIANT rather than substring-matching a message, which is the
`VerifyEmailResult` precedent applied to the same shape of question.

`Unreadable` and `UnexpectedKeys` exist because the first two states that were
NOT in the model were the two the model was for. A manifest that is present and
unparseable — a corrupt `\u` escape, an I/O fault — threw out of the loader as a
raw exception that named nothing and suggested nothing, past every message that
names the build script. And a manifest carrying a SURPLUS key was accepted
silently, although the file is generated and carries exactly one: a second key
is drift, and drift in a generated file is the thing `-c` exists to refuse.

The refusal also has to SURVIVE the crash. A `when` that flattened every variant
into `error("...")` would make a boot failure distinguishable only by substring
again — the same loss the sealed type was introduced to stop, one layer down. So
the single boundary, `bundleUrlOf`, throws `UnusableWebAssetManifest`, which
CARRIES the resolution; the message is DERIVED from the variant in one place,
and the test asserts the variant the exception holds rather than the sentence it
prints.

**D-D — the hash is content-addressed and the js directory is an ALLOWLIST.**
The name is `main-<first 12 hex of the sha256 of the bundle bytes>.js`; the 12
is `BUNDLE_HASH_HEX_LENGTH` in `bin/build-web-assets` and
`WebAssets.BUNDLE_HASH_HEX_LENGTH` in Kotlin, and the directory is
`BUNDLE_DIRECTORY` in both. A rebuild is byte-identical for identical sources
(asserted in the harness). The Kotlin pattern that asserts that shape is BUILT
from the manifest's `main` key and those two names rather than spelling any of
them a second time.

`<static>/js` is BUILD-OWNED, and the sweep over it is an allowlist: everything
that is not this build's bundle is reported by `-c` and deleted by a normal run.
It was a `main-*.js` staleness glob, which is a denylist — a `vendor.js` or a
`main.js.bak` parked in that directory was never deleted, never reported as
drift, and yet was packaged by `processResources` and SERVED. A guard that
answers "no drift" about a directory it only half reads is the failure this gate
exists to prevent. The cost is stated plainly in the help text: a file a human
parks there is removed by the next build.

**D-E — `--no-remote --no-npm` on EVERY step that resolves imports, plus
`--platform browser --format esm --minify` on the bundle.** `browser` because
the default is `deno`. `esm` and therefore `<script type="module">`.
`deno bundle` does not type-check, so `deno check` is its own step before it —
and it carries `--no-remote --no-npm` too, because `deno bundle` only ever walks
`main.ts`'s import graph, so a source nothing imports would have its remote
import FETCHED by an unflagged check step. `deno test` carries them for the same
reason.

What the two flags buy, stated precisely: they scope the SOURCE GRAPH the rail
will accept. No import in this repo's TypeScript may come from the network, so
no `deno.lock` is minted and no unreviewed third-party code is bundled. They are
NOT a claim that the build PROCESS is offline — `deno bundle` downloads its own
esbuild backend from `registry.npmjs.org` once per machine, into the Deno cache,
and `--no-npm` does not stop it. A COLD machine therefore needs network for the
first build; every later build is offline because the cache is warm. Warm a
fresh CI image with `nix develop -c bin/build-web-assets -c`.

**D-F — the CSP does not change.** An external same-origin script is already
allowed by `script-src 'self'`. `ContentSecurityPolicyTest`'s pinned directive
list is untouched. That is the point of loading the bundle as a file rather than
inlining it.

**D-G — the render-package source sweep is DELETED, not weakened.** RFC 210's
sweep read every Kotlin file in the render package and failed on any `script`
element, inline `style`, `style=` attribute, inline handler or `unsafe { }`
block. It was static analysis of source text: it never ran the application, and
it fails on text rather than on what a client receives. The operator rule this
run is built on is that no test is better than a bad test, and a test never does
static analysis — so the sweep and its whole apparatus (the file walk, the
comment stripper, the pattern list, the build's source-directory system
property) are removed, and a future run must not re-add them.

What it protected against is now proved from the WIRE instead:
`CspConformanceTest` fetches every public route, extracts every script, style,
inline handler and `style=` attribute from the response HTML, and evaluates each
against the ACTUAL policy string with a small evaluator. It carries a negative
self-test — a synthetic page with an inline script must be reported BLOCKED — so
an evaluator that silently approves everything fails instead of passing. A CSP
violation is invisible server-side; this is the test that stands in for a real
browser.

The evaluator models ONLY the policy this application serves: `'unsafe-inline'`
for inline code, `'self'` for a same-origin file, and no host list, wildcard,
nonce or hash. An external resource is therefore same-origin or blocked — so a
future host-source policy fails LOUDLY here rather than being half-graded by an
evaluator pretending to understand it.

Its answer is a sealed `CspVerdict`, and `Blocked` carries VALUES — the
directive, the source that is missing (`UnsafeInline` / `Self` / `Origin(...)` /
`Unnameable`) and the resource — not a formatted English sentence the self-test
parses back out. A test that greps `"inline script"` out of a message breaks on
a reword and passes on a refusal that has silently become the wrong one; the
variant survives both. Prose is built only by `describe()`, and only for
assertion messages.

A page resource is a sealed TRIPLE — `Inline` / `External` / `Unreadable` — and
each holds the code or the URL, not a sentence about it. `Unreadable` is the
important one. The extractor used to DROP a `<link rel=stylesheet>` whose `href`
it could not read and a `<script>` with neither body nor `src`, so "nothing
blocked" and "nothing read" were the same green run; and its attribute patterns
accepted DOUBLE-quoted values only, so a single-quoted `onclick` or an unquoted
`src` — both valid HTML — were invisible to a guard whose whole job is seeing
them. All three quoting forms are now read, an element whose URL cannot be read
is REPORTED and always graded blocked, and the negative self-test page carries a
single-quoted handler and an unquoted `src` so the widening is asserted rather
than claimed.

An `External` with a blank or non-site-root URL is blocked rather than approved
by `'self'` — the same hole, one level down from the nullable-URL record that
made "an external resource with no URL" constructible in the first place.

A full HTML parser would read all of this for free, and jsoup was considered and
DECLINED: it is in no build file and no version catalog in this repo, and adding
a dependency is not this slice's decision. Widening the patterns and refusing to
drop what they cannot read buys the same safety inside the slice.

The list of routes the evaluator walks is DERIVED from the public route table
rather than hand-copied beside it, so dropping a page from that table removes it
from "evaluated" too instead of leaving the coverage guard green over coverage
that is gone.

**D-H — one trivial progressive enhancement: "Back to top" on a long page.** The
script inserts a back-to-top link once the document is taller than two
viewports. With JavaScript disabled nothing is inserted and every page is
exactly what it is today — asserted by a test that the SERVER renders no
back-to-top markup. It is deliberately the smallest real thing: it touches no
money copy, no tokenized report, and no form.

The markup that enhancement inserts is NOT asserted from Kotlin. The link's
class and its label live in `backToTop.ts`, they exist only once a browser has
run the script, and no server response contains them — so a Kotlin test about
them can only read another artifact's TEXT. The page suites assert a property of
the RESPONSE instead: exactly one script element, external, with `src` equal to
the manifest's bundle url. Everything the enhancement itself does — the link
appears, does not duplicate, is removed again — is owned by the Deno tests,
which drive the real functions against a fake document.

The rule takes a `PageMetrics` record — `{ documentHeight, viewportHeight }` —
and not two positional numbers. Both inputs are plain heights, so a positional
pair lets a swapped call invert the rule and still type-check; a field name
cannot be swapped by accident, and no test can catch what the compiler now
rejects outright.

The decision is not made once at load, either. `sync(document)` applies the rule
idempotently in BOTH directions — it inserts the link when the page deserves one
and has none, and removes it when the page no longer does — and `mount` calls
`sync` once and then on every `resize` of the document's view. A rotation halves
the document height and doubles the viewport, so the answer at load is not the
answer for the rest of the page's life. No debounce, because `sync` does nothing
unless the answer changed, and no teardown API, because nothing in the page
unmounts.

`sync` and `mount` ARE tested, against a ~70-line fake document written in the
test file. The earlier position — that faking a `Document` would only test the
fake — was wrong about which risk is larger: with no test at all, deleting the
removal branch, inserting the link twice, or never registering the resize
listener were all silent. Each of those now fails a named test. The fake's own
comment says what it fakes (`body.appendChild`, `documentElement.scrollHeight`,
`defaultView.innerHeight`/`addEventListener`, `querySelector`, `createElement`)
and what it therefore CANNOT prove: no CSS engine, so `querySelector` matches
one exact class selector; no layout, so no height is ever real; no event loop,
so the click handler is recorded and never fired. Those claims stay where they
already are — the served-page tests, and the Kotlin test that pins the link's
class and label to the PACKAGED bundle. No DOM library and no dependency: the
rail still runs `deno test --no-remote --no-npm` with a six-line `assert`
helper.

The threshold itself is a named export, `VIEWPORTS_BEFORE_BACK_TO_TOP`, and the
wiring logs ONE `console.warn` per document when the metrics are unmeasurable —
once, not once per resize, because a warning that repeats forever is a warning
nobody reads. The rule stays pure; the warn lives in `sync`.

**D-I — `bin/format` formats TypeScript too.** `MARKDOWN_FMT_PATHS` in
`bin/common` is renamed `DENO_FMT_PATHS` and gains `public-web/src/main/ts`.
`deno fmt` handles `.ts` and `.md` alike, so the one existing format gate keeps
being the one format gate. Per D8 the rail owns check, lint, test and bundle —
formatting was never in that list and does not move.

**D-K — the hashed bundle is cacheable forever; nothing else is.** The bundle is
served with `Cache-Control: public, max-age=31536000, immutable`, where the
number is DERIVED at the header boundary from
`IMMUTABLE_BUNDLE_MAX_AGE =
365.days` — the header states a duration, and a bare
`31536000` in the source states a unit nobody can check by reading it. The
header is set route-scoped on the static mount and keyed on the request path
being `WebAssets.MAIN`. A hashed name that no cache header exploits is a cost
with no benefit: content addressing is exactly what makes a year-long immutable
cache SAFE, because a new build is a new URL (D-D), so a browser that never
revalidates is holding the only bytes that URL will ever have, and a deploy
cannot leave a visitor running last week's code.

It is therefore deliberately NOT applied to `/site.css` or to any other static
path. Those names are stable and their bytes change in place, so an immutable
year on them would pin stale content onto returning visitors with no way to
recall it. Both halves are asserted: the bundle HAS that exact value, and
`/site.css` carries no `Cache-Control` at all.

**D-J — a new harness, declared in both places.** `bin/web-assets-scripts-tests`
is added to `bin/shell-tests`' HARNESSES array AND to `bin/gate-scope`'s known
set; the `module-source` row's harness list becomes
`scripts-tests web-assets-scripts-tests`, unconditionally. Unconditional and not
path-conditional: a TypeScript edit and a Kotlin edit both land in that scope,
the harness costs seconds, and a conditional map is one more correctness claim
to get wrong.

**Nothing here touches a database table or the session metadata schema.** D11
(Ian reviews every session-metadata schema change) and brief 0001 D10 (Ian
approves every new table) do not fire; no DDL is proposed.

## Detailed Design

### Sources

```
public-web/src/main/ts/
  main.ts            entry point; wires the enhancements, nothing else
  backToTop.ts       the one enhancement: pure logic + a DOM mount
  backToTop_test.ts  deno test: the pure rule, plus sync/mount against a fake document
```

`main.ts` is the only bundle entry. It does no work at import time beyond
calling each enhancement's `mount(document)`; each `mount` is a no-op when its
host element is absent, so a page that lacks the feature costs nothing and
throws nothing.

The root `deno.json` keeps exactly one key, `fmt`, and gains one exclude:
`public-web/src/main/resources/static/js`, because generated bytes are not
formatted and formatting them would make every check-only run report drift that
no source edit caused. Everything else the rail needs is a claim about ONE
directory, so it lives in a NEW `public-web/src/main/ts/deno.json`:
`compilerOptions.lib` of `["esnext", "dom", "dom.iterable", "deno.ns"]` — the
bundle runs in a BROWSER and Deno's default libs declare no `Document`, while
`deno.ns` stays because the rail's own tests are `Deno.test` — and the `lint`
rules, the recommended tag set. A `dom` lib set in the ROOT config would hand
every future `deno check` anywhere in this repo browser globals it has no
business seeing.

`bin/build-web-assets` passes that file with `--config` on all four deno steps,
so the rail never depends on deno discovering a config by walking up from the
caller's working directory; the path is derived from `-s`, and a `-s` without a
`deno.json` is refused as a bad option value. The lint block carries no
`include`: measured on deno 2.7.12, `deno lint <path>` OVERRIDES the config's
`include`, so an `include` here would restate a scope nothing reads. RFC 225's
first draft of the harness claimed the opposite — that `include` is what scopes
the lint step and that a differently-shaped temp tree would silently lint
nothing. That claim was wrong; the fixtures still mirror the repo layout, but
the reason is the config copy the build now requires.

### `bin/build-web-assets`

```
bin/build-web-assets [-s <ts dir>] [-o <static dir>] [-m <manifest file>] [-c] [-h]
```

`getopts`, short options only, per `bin/` convention. `-c` is check-only.
Logging — every line of it — goes to stderr through `bin/functions`'
`log-info`/`log-error`/`fatal`; **stdout is empty on a normal run**, because the
product of the run is files on disk. `-h` prints usage on stdout and exits 0.
Usage errors take the reserved codes (10/11/20/21/22); `-c` drift exits **3**,
the `EXIT_SEED_WOULD_CHANGE` precedent. That code is declared as
`EXIT_BUNDLE_WOULD_CHANGE` in `bin/functions`, beside every other reserved code
and beside the seed one it copies — not in the two scripts that read it — and it
is DERIVED from `EXIT_SEED_WOULD_CHANGE` rather than restating the literal `3`.
The sameness is a claim the comment makes, and a second literal would let the
seed code be renumbered alone and leave the claim quietly false.

`-s` may contain `*.ts` and `deno.json`, recursively, and NOTHING else —
anything else is `EXIT_INVALID_ARG_VALUE` naming the offending path. A `.js` or
`.mjs` left under the source tree is never type-checked and never linted, yet an
import of it would be bundled and shipped; the rail's whole claim is that every
byte it ships was checked, so a file it cannot check may not be in the tree it
builds from.

`-o` says WHERE the static tree is, never what it is CALLED. The served URL is
`/js/<name>` because Ktor mounts the classpath directory `static` at `/`, and
`WebAssets` looks the packaged resource up under that same one name. Neither
reads this option, so a `-o` whose last segment is not `static` would build a
manifest nothing serves — it is refused with `EXIT_INVALID_ARG_VALUE`.

One entry point, `main.ts`, stays the decision; there is no multi-entry mode.
The entry NAME is nevertheless derived once, from the entry file, and the
manifest key, the bundle prefix and the stale-bundle glob are all built from it,
because four hand-written copies of one string are three chances to change the
wrong subset.

Steps, in order, aborting on the first failure. Each of the four deno steps
carries `--config <ts dir>/deno.json`, so none of them depends on config
discovery from the caller's working directory:

1. `deno check --no-remote --no-npm` the entry and every source under `-s`.
2. `deno lint` the same directory.
3. `deno test --no-remote --no-npm` the same directory.
4. `deno bundle --platform browser --format esm --minify --no-remote --no-npm`
   into a temp file.
5. `sha256` the bundle bytes; take the first `BUNDLE_HASH_HEX_LENGTH` hex
   characters.
6. Write `<static>/js/<entry>-<hash>.js`.
7. Write the manifest: `<entry>=/js/<entry>-<hash>.js`.
8. Delete everything else in `<static>/js` (D-D).

That ORDER is the guarantee: every intermediate state is serveable. The write
used to delete before it wrote the manifest, so a run that died between the two
left a manifest naming a file that was no longer there — a tree that boots and
then 404s its own script. Written this way, a run that dies part-way leaves
either the old pair intact or the new bundle plus a superset of files, and never
a manifest pointing at nothing.

Under `-c`, steps 1–5 run identically, nothing is written at all, and the script
compares — reporting to stderr every file that differs and exiting 3.

### `WebAssets`

`public-web/src/main/kotlin/ed/unicoach/web/assets/WebAssets.kt`: an `object`
that loads `/web-assets.properties` from the classpath at init, requires the
`main` key, requires the value to match a pattern built from that key, and
requires the named resource to be on the classpath under the one
`STATIC_RESOURCE_PACKAGE` name that `Routing.kt`'s `staticResources` mount also
reads. Any failure is an `IllegalStateException` whose message names
`bin/build-web-assets`. It fails at startup — because `publicWebModule` reads
`MAIN` as it installs (D-C) — and not at the first page view.

The refusals are a total FUNCTION,
`internal fun mainFrom(manifest: ManifestRead, resourceExists: (String) -> Boolean):
MainBundleResolution`,
and `MAIN` is
`bundleUrlOf(mainFrom(readManifest(), ::classpathResourceExists))`. The seam
exists because an `object` initialises once per JVM: a test that only reads
`MAIN` can prove a good manifest works and can never prove a bad one is refused.
Returning a variant rather than throwing means those tests assert the STATE, not
a message.

Its input is the READ OUTCOME and not a nullable `Properties`:
`ManifestRead.Missing | Unreadable(cause) | Entries(Map<String, String>)`. A
nullable `Properties` could say "absent" and "these entries" and had no way to
say "present and unparseable", so that third state escaped as an exception
instead. `java.util.Properties` now stops inside the private `readManifest()` —
it is the loader's type, not this module's API — and `mainFrom`,
`MainBundleResolution`, `ManifestRead` and `UnusableWebAssetManifest` are all
`internal`, which the test source set can still see.

Two facts the shell script and the Kotlin both know are NAMED on both sides
rather than spelled: `BUNDLE_HASH_HEX_LENGTH` (12) and `BUNDLE_DIRECTORY`
(`js`). Neither language reads the other's constant — they cannot — so each
states the fact once and a test asserts the shape, which is the most two
languages can do about one number.

### The page

In `siteLayout`'s `head`, after the stylesheet:

```kotlin
script {
  type = "module"
  src = WebAssets.MAIN
}
```

`type="module"` implies defer, so no `defer` attribute is written and nothing
blocks parsing. Every page gets it, because every page is a candidate host for a
future enhancement and a per-page opt-in is a second thing to keep in sync.

## Files Modified

| File                                                               | Change                                                 |
| ------------------------------------------------------------------ | ------------------------------------------------------ |
| `public-web/src/main/ts/main.ts`                                   | NEW — bundle entry                                     |
| `public-web/src/main/ts/backToTop.ts`                              | NEW — the one enhancement                              |
| `public-web/src/main/ts/backToTop_test.ts`                         | NEW — deno test                                        |
| `public-web/src/main/resources/static/js/main-<hash>.js`           | NEW — generated, committed                             |
| `public-web/src/main/resources/web-assets.properties`              | NEW — generated, committed, not served                 |
| `public-web/src/main/kotlin/ed/unicoach/web/assets/WebAssets.kt`   | NEW                                                    |
| `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`      | the one `<script type="module">`                       |
| `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`        | D-C: the install reads the injectable `bundleUrl`      |
| `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`            | D-K: the hashed bundle's immutable cache header        |
| `public-web/src/main/resources/static/site.css`                    | the back-to-top link's rule                            |
| `public-web/src/test/kotlin/ed/unicoach/web/ShellMechanismTest.kt` | D-G: RFC 210's source sweep removed                    |
| `public-web/build.gradle.kts`                                      | the sweep's source-directory system property removed   |
| `public-web/src/test/kotlin/ed/unicoach/web/StaticAssetsTest.kt`   | the bundle is served, typed, and policed               |
| `public-web/src/test/kotlin/ed/unicoach/web/WebAssetsTest.kt`      | NEW                                                    |
| `public-web/src/test/kotlin/ed/unicoach/web/FakeEmailVerifier.kt`  | the shared page assertion, and the `bundleUrl` seam    |
| `public-web/src/test/kotlin/ed/unicoach/web/CspConformanceTest.kt` | NEW — served-bytes CSP evaluation                      |
| `bin/build-web-assets`                                             | NEW                                                    |
| `bin/web-assets-scripts-tests`                                     | NEW                                                    |
| `bin/shell-tests`                                                  | declare the harness                                    |
| `bin/gate-scope`                                                   | know the harness; `module-source` runs it; the comment |
| `bin/common`                                                       | `MARKDOWN_FMT_PATHS` -> `DENO_FMT_PATHS`, + the ts dir |
| `bin/format`, `bin/prompt-seed-scripts-tests`                      | follow the rename                                      |
| `deno.json`                                                        | keep only `fmt`; exclude the generated js              |
| `public-web/src/main/ts/deno.json`                                 | NEW — the rail's `compilerOptions` and lint rules      |
| `bin/functions`                                                    | `EXIT_BUNDLE_WOULD_CHANGE`, derived from its precedent |

## Implementation Plan

1. `deno.json`, then the `MARKDOWN_FMT_PATHS` -> `DENO_FMT_PATHS` rename across
   every file that names the array — `bin/common`, `bin/format`,
   `bin/prompt-seed-scripts-tests`, and a comment in `bin/gate-scope` — so the
   formatter covers the sources before they exist. `bin/pre-commit` reaches the
   array only through `bin/format` and needs no edit.
2. `bin/build-web-assets` with its full option grammar and stderr-only logging.
3. The TypeScript sources and their deno test.
4. Run the build; commit the generated bundle and manifest.
5. `WebAssets`, the `Layout.kt` script tag, the site.css rule.
6. `bin/web-assets-scripts-tests`; declare it in `bin/shell-tests` and
   `bin/gate-scope`.
7. The Kotlin tests, including the CSP conformance test, and the DELETION of RFC
   210's render-package source sweep (D-G).
8. `nix develop -c bin/web-assets-scripts-tests`, then
   `nix develop -c bin/test`.

## Why some tests were deleted rather than repaired

The rule, stated once: **not tests are better than bad tests, and a test never
does static analysis.** A test must exercise BEHAVIOUR — the bytes a client
receives, a script's exit code, a value a function returns. A test that reads
source text, or another artifact's text, and pattern-matches it is not a test of
anything; it passes when the code is wrong in a way the pattern does not spell,
and fails when the code is right and the prose moved.

Five tests written for this slice were of that kind and are GONE, not weakened:
the `WebAssetsTest` case that read `Application.kt` looking for the name
`WebAssets.MAIN` (and the `publicWebModuleBody()` reader under it), the shared
`KotlinSource.kt` extraction built to serve it, the `Layout.kt` exact-equality
script pin with its two cases, the `BackToTopMarkup` object that grepped the
minified bundle for a class and a label, and the `ShellMechanismTest` case that
joined those bundle strings to the stylesheet. Each claim they made either moved
to a test of behaviour — the boot now fails for a resolver that throws; the
response now has to carry exactly one external script whose `src` is the
manifest's url — or was already covered by `CspConformanceTest` over the served
bytes and by the Deno tests over the real functions. Where a claim was not worth
a behavioural test, it is simply not tested, and that is the intended outcome.

This paragraph exists so a later run does not "restore coverage" by re-adding
them.

RFC 210's render-package sweep met the same rule and is DELETED in this slice
(D-G), apparatus and all — the file walk, the comment stripper, the pattern
list, and the source-directory system property in `public-web/build.gradle.kts`.
What it guarded is now proved from the served bytes by `CspConformanceTest`,
which walks Ktor's routing tree, fetches every registered page, and grades the
resources in each response against the real policy. The one gap is a render file
no route serves yet; the moment a route serves it, the policy test covers it.

## Tests

**Kotlin (`:public-web`)**

- `WebAssetsTest` — the manifest loads; the value matches the hashed-name shape;
  the named resource exists on the classpath. A hand-edited manifest fails. The
  six refusals are tested through `mainFrom` by asserting the returned
  `MainBundleResolution` variant, not through class initialisation and not by
  substring-matching a message: an `object` initialises once per JVM, so the
  refusals cannot be reached twice from one test JVM. The crash BOUNDARY is a
  separate claim, asserted by the `resolution` the thrown
  `UnusableWebAssetManifest` carries; and an exhaustive `when` naming the test
  that covers each variant makes an untested new variant a COMPILE error. That
  the refusal reaches the BOOT is a separate claim, and it is asserted as
  BEHAVIOUR: a module installed with a `bundleUrl` resolver that throws must
  fail at `startApplication()`, before any request is made. A module that read
  the manifest only when a page rendered would start cleanly and fail that test.
  The default resolver is what every other `:public-web` test boots.
- `StaticAssetsTest` — `GET /js/main-<hash>.js` (path read from `WebAssets`,
  never typed) returns 200, a JavaScript content type, and carries the policy
  header. A request for a bundle name that is not the current one 404s. D-K's
  two halves: that response carries the immutable year-long `Cache-Control`, and
  `/site.css` carries none.
- `ShellMechanismTest` — the source sweep is REMOVED (D-G). The test that
  remains is behavioural only: the served stylesheet's breakpoints, the fluid
  layout tokens, the policy header on every kind of response, and the
  immutable-bundle cache rule.
- `CspConformanceTest` — every public route's rendered HTML is evaluated against
  the real `CONTENT_SECURITY_POLICY`; nothing is blocked. Negative self-test: a
  synthetic page carrying an inline script IS reported blocked.
- `HomePageTest`, the legal page tests and `WhoPaysPageTest` — the RESPONSE
  carries exactly one script element, it is external, and its `src` is the url
  the manifest names. So the JavaScript-disabled page is what it was apart from
  that tag, and nothing asserts the enhancement's own markup from Kotlin.

**Deno** — `backToTop_test.ts`: the pure "should this appear?" rule, and
`sync`/`mount` driven against a small fake document — the link appears on a long
page, does not on a short one, is removed when a long page becomes short, is not
duplicated when `sync` runs twice, and a `resize` re-syncs. Plus the two states
that used to be silent: a document with no `documentElement` is a no-op, and an
unmeasurable page warns exactly once.

**Shell (`bin/web-assets-scripts-tests`)**

- `-h` prints usage to stdout, exit 0.
- Unknown option 10, option missing its value 11, unexpected positional 20, a
  `-s` that is not a directory 22.
- A normal run writes the bundle and the manifest and prints **nothing** to
  stdout; its diagnostics appear on stderr.
- Two runs over identical sources produce identical bytes and the same hash.
- Changing a source changes the hash, and the previous `main-*.js` is deleted.
- **`-c` against the committed tree exits 0** — the staleness gate.
- A deliberately broken TypeScript file (type error) fails the build with a
  non-zero exit and writes NO output. Same for a lint violation and for a
  failing deno test, each asserted separately so the three steps cannot be
  silently dropped.
- A source importing a remote URL fails, because `--no-remote` is passed.
- A `-s` directory with no `deno.json` is a usage error (22), not a build
  failure: the rail's lib set and lint rules travel with the sources.
- A `-s` directory carrying a file that is neither `*.ts` nor `deno.json` — a
  `.js` in a sub-directory — is a usage error (22) naming the file.
- A `-o` whose last path segment is not `static` is a usage error (22).
- A file in `<static>/js` that is not the bundle makes `-c` exit 3 naming it,
  and a normal run deletes it (D-D's allowlist).
- The three usage-error cases tail STDERR in their failure message, like every
  other case: an assertion that reports only an exit code says nothing about why
  a regression fired.
- The stream-discipline assertion builds its OWN tree, so it describes the run
  it names rather than whatever a neighbouring case left behind.
