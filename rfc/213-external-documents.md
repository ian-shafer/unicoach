# RFC 213 — external documents: one home for a URL, and whether it resolves

**Status:** proposed **Slice:** `test/05.1/external-documents` (brief
`product/0010-when-do-i-test`, D26) **Relates to:** RFC 170 D13
(`source_documents`), RFC 200 (link rot), RFC 208 (the state programmes). The
college-keyed `source_documents` is left exactly as it is; folding it in is
`test/05.2/one-home-for-every-citation`.

## Summary

A citation in this repo is a bare `TEXT` url column on the fact row, copied from
table to table. Four live tables carry such columns today (measured below), and
the one attempt to de-duplicate them — `source_documents` — is
`NOT NULL REFERENCES colleges(id)`, so it cannot hold a citation about a
jurisdiction at all.

This RFC adds ONE table. `external_documents` holds a URL and whether that URL
still resolves, and nothing else. `admission_test_state_programmes` stops
carrying two url columns and carries two foreign keys into it instead: the
state's own page, and the archived address where one was needed. The 43 state
urls are swept by the existing link checker at seed time, so the link-rot
columns mean something on the day the table lands.

**Nothing a family sees changes.** The coach's words about the state programmes
are exactly the words RFC 208 landed. This slice changes storage, not a
sentence.

## What a "capture" is to this product, and why it is not modelled

An archived address is **the URL a human read when the publisher's own page
would not open for us**. New Hampshire's and Arizona's education departments
return 403 to every client we have, so the row was read through the Wayback
Machine and that address was written down. It is HOW the content was reached. It
is not a kind of object this product reasons about.

So an archived address is simply another row in `external_documents` — cited
like any other, link-checked like any other — pointed at by a second, nullable
foreign key on the row that needed it.

**Considered and rejected** (all three were drafted, and all three were cut):

- **A `document_captures` edge table** — one row per snapshot, carrying
  `captured_on`, joining a capture document to the document it is a snapshot of.
  Rejected: it is a table for a many-to-one relationship the owner row already
  expresses with a nullable column, and, more importantly, it gives a
  first-class name to a concept the product does not have.
- **A self-reference on `external_documents`** (`captures_document_id` pointing
  at another row of the same table). Rejected for the same reason, one table
  smaller: a cheaper spelling of a concept we do not want.
- **A join table `admission_test_state_programme_documents`, with a `claims`
  array and a `read_from` column.** These existed ONLY to let one row cite two
  documents and say which claim each backs. We are not doing that, so they have
  no work to do. The one candidate case, Utah, does not need it: Utah shipped
  from a single parent fact sheet that carries every claim its row makes, and
  that remains true. No second Utah document is fetched, cited, or given an
  invented address.

**No capture date, and no date read out of a URL.** No field carries one and
none is added. The coach says we hold an archived copy, offers both links, and
must not present the copy as what the live page says today — the RFC 208
wording, unchanged. A Wayback address contains a timestamp; parsing a date out
of a URL string would be inventing a field, and this RFC does not do it.

## What is actually there (measured 2026-09-14, worktree `bba0927a`)

- **`source_documents` cannot be the home.**
  `college_id UUID NOT NULL
  REFERENCES colleges(id)`,
  `source money_source NOT NULL` (a four-value money publisher domain),
  `academic_year NOT NULL`, natural key `(college_id, source, academic_year)`. A
  state statute has no college, no money publisher and no academic year.
- **Row counts.** Every reachable migrated database holds **0**
  `source_documents` rows: the table is created by migration and filled by the
  ingest run, which no local database has run. Production is not reachable from
  a worktree — UNKNOWN. The committed seed is the real measure:
  `db/seed/cds/source-url-status.csv` has **441** distinct urls (417 `found`, 15
  `not_found`, 9 `unknown`, all nine DATED), and the four CDS fact CSVs hold
  5,048 rows over **446** distinct `(unit_id, source_year)` pairs.
- **446 pairs but 441 urls, so one document is cited by several colleges and the
  college-keyed table stores it repeatedly.** Four urls are shared: one Colorado
  page across two years, one Fairleigh Dickinson file across two units, and two
  University of Washington files across three campuses. Keying
  `external_documents` by URL is what stops that repetition.
- **Six tables reference `source_documents`**: `college_merit_aid`,
  `college_admission_factors`, `college_deadlines` and `aid_form_requirements`
  `NOT NULL`; `cohort_money_stats` and `cohort_population_counts` `NULL` plus
  `CHECK (source <> 'common_data_set' OR source_document_id IS NOT NULL)`. That
  CDS rule lives ONLY in the schema — there is no Kotlin `require` for it, and
  `CanonicalMoneyDaoTest.kt:511` pins the constraint NAME. Nothing here changes;
  it is recorded so `test/05.2` inherits the measurement.
- **D26's "eight tables" is four.** Only `source_documents`, `policy_parameters`
  (`source_name` + `source_url`), `admission_test_administrations`
  (`dates_source_url` + `score_release_source_url`) and
  `admission_test_state_programmes` carry their own url columns. The three CDS
  tables lost theirs in `0087:184-197`, and `aid_policy_facts` was dropped
  outright (`0087:305`). So `test/05.2` has three carriers to move plus
  `source_documents` to fold in — materially smaller than written.
- **The state corpus.** `programmes.csv`: 36 rows, one reading session
  (`checked_on=2026-09-14`, `expires_on=2027-07-31`). Seven rows carry an
  `archive_url` — AZ, DE, HI, KS, NH, OK, WY. Six of the seven are plain
  `http://web.archive.org/...`; only NH's is https. 43 distinct urls in total.
- **`bin/cds-link-check` is general.** Its CDS shape is a name, a docstring and
  a default path. It globs `*.csv` in ONE directory, needs a column literally
  named `source_url`, excludes the file named `source-url-status.csv` from its
  own glob, writes `source_url,source_url_status,checked_on,pass_one,pass_two`,
  reads no college id and no year, and never touches the database. A 403 maps to
  `unknown` with a date — "asked, no usable answer" — never to `not_found`. Its
  two refusal bars (`DEAD_SHARE_CEILING = 0.20` as a SHARE, and `found == 0`)
  are far from binding on 43 urls with three known-403 pages.

## Detailed Design

### 1. `external_documents`

```sql
CREATE DOMAIN link_status AS TEXT
    CONSTRAINT link_status_check
        CHECK (VALUE IN ('unknown', 'found', 'not_found'));

CREATE TABLE external_documents (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    url            TEXT        NOT NULL UNIQUE,
    url_status     link_status NOT NULL DEFAULT 'unknown',
    url_checked_on DATE        NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT external_documents_url_nonempty_check
        CHECK (url <> ''),
    CONSTRAINT external_documents_url_shape_check
        CHECK (url ~ '^https?://'),
    CONSTRAINT external_documents_checked_when_known
        CHECK (url_status = 'unknown' OR url_checked_on IS NOT NULL)
);
```

- **The URL is the natural key.** One published document, one row, whoever cites
  it — which is the de-duplication `source_documents` attempts and misses for
  the four shared CDS urls above.
- **No `type`, no `publisher`.** Format is derivable from the fetch and belongs
  to whatever fetches; genre is a judgement that varies by reader. The publisher
  is already known to the FACT (RFC 177 derived attribution per figure), so a
  publisher column here would be a second copy that can disagree with the first
  — and `source_documents.source` shows where that leads: it is typed
  `money_source`, which is exactly why that table cannot hold a state citation.
- **http is accepted, https preferred** (Ian, at the RFC 208 gate: "https only —
  why? We should prefer https, but not block insecure"). Six of the seven
  archived addresses we already hold are plain http; an https-only CHECK would
  refuse data we have.
- **The RFC 200 link-rot pair moves here in meaning, not in name.** The column
  is `url`, so the pair is `url_status` / `url_checked_on`. Three states, not
  four: `unknown` with NO date is "we never asked", `unknown` WITH a date is "we
  asked and got no usable answer", `found`/`not_found` is "we asked, and the
  date says when". The CHECK is what keeps the first two distinguishable, which
  is RFC 182's rule that a check which quietly did not run must never look like
  one that passed.
- **A DOMAIN, not a per-table CHECK.** Two tables now ask this question.
  `source_documents` keeps its own CHECK until `test/05.2` folds it in, and that
  slice converts it to this domain rather than writing a third copy. The Kotlin
  half is the EXISTING `SourceUrlStatus` enum, reused rather than duplicated;
  renaming it is a `test/05.2` job because the rename touches `CdsRead`,
  `CdsSeedLoader` and a schema pin test for no behaviour.
- **No expiry column.** Expiry is a property of a READING of a document — the
  owner's `expires_on` — not of the document.

### 2. The state programmes reference it, with two foreign keys

```sql
ALTER TABLE admission_test_state_programmes
    ADD COLUMN source_document_id  UUID NULL REFERENCES external_documents(id),
    ADD COLUMN archive_document_id UUID NULL REFERENCES external_documents(id);

-- backfill, in SQL, from the columns about to be dropped
INSERT INTO external_documents (url)
SELECT DISTINCT source_url FROM admission_test_state_programmes
UNION
SELECT DISTINCT archive_url FROM admission_test_state_programmes
WHERE archive_url IS NOT NULL
ON CONFLICT (url) DO NOTHING;

UPDATE admission_test_state_programmes p
SET source_document_id = s.id,
    archive_document_id = a.id
FROM external_documents s
LEFT JOIN external_documents a ON TRUE   -- see Implementation Plan for the exact form
WHERE s.url = p.source_url;

ALTER TABLE admission_test_state_programmes
    ALTER COLUMN source_document_id SET NOT NULL,
    ADD CONSTRAINT admission_test_state_programmes_archive_is_not_the_source_check
        CHECK (archive_document_id IS NULL
               OR archive_document_id <> source_document_id),
    DROP COLUMN source_url,
    DROP COLUMN archive_url;
```

The landed shape, normalised, and nothing more: one row cites one page, with an
optional archived address.

- **The two may NOT be equal.** An archived address that IS the source is not an
  archive of anything; it is the same link twice, and a coach told to "offer
  both links" would print one url as two. The CHECK refuses it.
- **An archive without a source is impossible by construction.**
  `source_document_id` is `NOT NULL`, so there is no row that has an archived
  address and no page it is an archive OF. That is the same guarantee the old
  `source_url NOT NULL` gave, kept rather than downgraded.
- **The backfill is the migration's own work**, read out of the columns it is
  about to drop. It does not depend on a CSV, a script or a fetch, so a database
  migrated today and a database migrated next year get identical rows.
- **No existing citation loses a guarantee.** `source_url NOT NULL` becomes
  `source_document_id NOT NULL REFERENCES external_documents(id)` — a stronger
  constraint, because the url is now url-shaped by CHECK in one place instead of
  by a CHECK per table.
- **The old columns go.** Two ways to cite a document on one table is the defect
  D26 exists to end rather than to double.

### 3. `bin/check-link` — new shared tooling, specified in full

**Why a new script, and why inside a product slice.** `bin/cds-link-check` takes
`-s <seed-dir>` and harvests a column literally named `source_url` out of every
CSV in it. That interface is why the previous draft of this RFC had to FABRICATE
a csv file of urls to get 43 addresses checked. Handing a link checker a
directory is the defect; a link checker should be handed URLs. So this slice
adds `bin/check-link`, which takes URLs and prints HTTP status codes, and it
adds it here because this slice is its first caller — a script with no caller is
unreviewable, and a separate run would land tooling nothing exercises.

**`check-link` fetches. It does not judge.** The `found` / `not_found` /
`unknown` vocabulary is RFC 200's, and it is a caller's reading of the codes.
Nothing in this script knows those three words.

#### Usage

    check-link [-f FILE] [-u AGENT] [-c N] [-t N] [-h] [URL...]

      URL...    one or more urls to check
      -f FILE   read urls from FILE, one per line; "-f -" reads standard input
      -u AGENT  user agent to send (default: a current Chrome string)
      -c N      concurrent requests, 1-64 (default: 16)
      -t N      per-request timeout in seconds, 1-300 (default: 30)
      -h        this help, on stdout

#### Interface, decided

1. **Exactly ONE input source per run.** Positional urls, or `-f FILE`, or
   standard input. Supplying positionals AND `-f` is a usage error
   (`EXIT_INVALID_ARG_VALUE`, 22) naming both, not a silent merge or a silent
   precedence. Two sources of truth about what to check is the same defect this
   whole RFC is about.
2. **Standard input is `-f -`, or the default when nothing else is given** — and
   only when stdin is NOT a terminal. `check-link` typed bare at a prompt does
   not hang: with no positionals, no `-f`, and a tty on stdin it fatals with
   `EXIT_MISSING_REQUIRED_ARG` (21) and prints the three input forms. A script
   that waits forever for input nobody is typing looks like a hung network
   probe, which is the one thing this tool must never be mistaken for.
3. **Line handling, and duplicates.** Leading and trailing whitespace is
   stripped; a blank line is skipped; a line whose first non-space character is
   `#` is skipped as a comment. **Duplicates produce one output line per INPUT
   line, in input order, but only ONE request per distinct url.** A caller zips
   the output back against the list it supplied, so dropping a line would
   silently misalign every line after it; and a host is asked once however many
   times the caller listed the address. That is deliberately different from
   `cds-link-check`, whose product is a per-DOCUMENT committed file and which
   de-duplicates 5,048 fact rows down to 441 documents.
4. **A url with no scheme, or a scheme that is not http(s), is refused BEFORE
   any request**, with `EXIT_INVALID_ARG_VALUE` (22), naming the offending line
   and its number, and nothing is written to stdout. Three reasons the scheme is
   REQUIRED rather than assumed: prepending `https://` is us inventing part of
   an address a human was meant to supply; `example.com:8080/x` parses as a
   scheme named `example.com`, so a schemeless input cannot be validated, only
   guessed at; and `external_documents` carries `CHECK (url ~ '^https?://')`, so
   accepting one would make the checker and the table disagree about what a url
   is. A `mailto:`, an `ftp://` or an empty scheme is a caller mistake, not a
   fact about the web, and `bin/` already separates the two by exit code. **http
   stays accepted and https preferred**: the requirement is that the scheme is
   STATED, not that it is secure, and six of our seven archived addresses are
   plain `http://web.archive.org/...`.

#### Output

5. **One line per input line, `<status><TAB><url>`, no header, on stdout.**
   stdout carries nothing else — no progress, no summary, no warning — per the
   `bin/` rule. A tab makes `cut -f1` and `awk '$1 >= 400'` work, and a url
   never contains one.
6. **Input order, always.** It is the only order a caller can zip back against
   its own list without parsing anything.
7. **No HTTP status at all prints `000`** — a timeout, a DNS failure, a TLS
   fault, a refused connection. It is curl's `%{http_code}` convention, and it
   keeps the first column a fixed-width number, so a caller's numeric test never
   has to special-case a word. The lost detail is not lost: the TYPE and the
   bounded reason of the fault are logged to **stderr**, one warning per url, in
   the `cds-link-check` `bounded_reason` form. A word (`ERR`, `TIMEOUT`) was
   rejected because it breaks every numeric parse and invites a vocabulary — and
   a vocabulary is the caller's job, per the line above.
8. **Redirects are followed by default and the FINAL status is reported**; `-r`
   reports the first status and follows nothing. Following is the default
   because a 301 is how a healthy site says "we moved", and a caller that gets
   `301` for half a corpus learns nothing. The redirect is not hidden: each hop
   is logged to stderr as `301 -> <target>`, so a 301 into a parking domain is
   visible to the human reading the run, and `-r` makes it visible to a machine.
9. **One pass: `HEAD`, falling through to a ranged `GET` where the host refuses
   the METHOD (400, 403, 405, 501).** One pass, one answer, no verdict: a caller
   that wants two readings runs the script twice and decides, which is exactly
   the judgement this script refuses to make.

#### The default user agent is a browser string, and that is a product decision

`check-link` sends **a current Chrome user-agent string by default**, held as
one named constant with this reasoning above it, and `-u AGENT` overrides it.

We are not crawling. The question this script answers is **"can a family open
this link?"**, and a family opens it in a browser. A script-shaped agent answers
a different question, and answers ours wrongly: New Hampshire's and Arizona's
education departments return 403 to scripts, and their pages are NOT gone — an
honest-but-script agent would record a live page as unreachable, which is a
worse answer than the one a family actually experiences.

What makes that defensible rather than rude is the SHAPE of the work: one
request per url we already cite, at most two concurrent per host, run by hand a
couple of times a year. That is not load and it is not a crawl.

`-u` is strictly better than the `-b` boolean it replaces: a flag named "act
like a browser" bakes two identities into the script, where `-u` lets a caller
send any one and leaves the choice visible in the command that produced the
result. An empty `-u` value is refused with `EXIT_INVALID_ARG_VALUE` (22) rather
than silently falling back to the default, and `-h` prints the default so nobody
has to read the source to learn what was sent. The constant does not chase
Chrome's version number; servers rarely read it.

#### Specified, then cut, so nobody re-proposes them cold

- **`-b` (probe as a browser)** — cut by amendment. Its only would-be caller was
  `cds-link-check`, which this RFC explicitly does not convert, and `-u` covers
  the case better.
- **`-r` (report the first status, do not follow redirects)** — cut by
  amendment. It serves an "are our stored addresses still canonical?" audit that
  nobody is writing. Redirects are followed and the final status is reported;
  each hop is logged to stderr.
- **A third output column naming the final url after a redirect** — raised and
  not taken. The argument FOR it is real and is recorded here: a `200` printed
  against the address you supplied hides that the page now lives somewhere else,
  and the hop log is on stderr where a machine cannot read it. It is one column
  on the day a caller needs it.

Each of these is one line of work the day something needs it. A flag with no
caller is a concept the product does not have yet — the same test that removed
the captures table, the publisher column and the document type from this design.

#### Behaviour

10. **`-c` and `-t`, the letters `cds-link-check` already uses**: concurrency
    1-64 default 16, per-request timeout 1-300 default 30, both validated after
    `getopt` with `EXIT_INVALID_ARG_VALUE` on a non-integer or an out-of-range
    value.
11. **It sends the Chrome string above (or `-u`), and it holds at most 2
    concurrent requests per host** regardless of `-c`. 43 addresses spread over
    40 domains is not a load, but a caller who hands it 400 urls on one host
    should not need to know to lower `-c`. No inter-request delay: a per-host
    cap is politeness, a sleep is a throttle nobody asked for.
12. **Statuses are the PRODUCT, so a 404 is a successful run.** Exit 0 whenever
    every input line got an output line, `000` lines included. The script itself
    fails only on: a usage mistake (the reserved band 10, 11, 20, 21, 22), an
    unreadable or non-file `-f` path (22), and no input at all (21). A network
    fault on one url never changes the exit code, because a caller checking 43
    addresses wants 43 answers. The consequence is stated rather than left
    implicit: a machine with no egress returns all `000` and exit 0, so **a
    caller must apply its own plausibility bar** — which is precisely what
    `cds-link-check`'s `DEAD_SHARE_CEILING` and `found == 0` refusals are, and
    what the seed script below does. That keeps RFC 182's rule where it belongs:
    a check that could not run must not look like one that passed, and the
    script that can tell the difference is the one with the expectations.
13. **stdlib-only Python 3, grammar by the `getopt` module**, per CLAUDE.md's
    `bin/` rule, through `bin/pyfunctions.py`. One change is needed there:
    `parse_short_options` today FATALS on any positional
    (`EXIT_UNEXPECTED_ARG`), because its three callers declare none. It gains an
    opt-in `allow_positional=False` parameter and returns the operands when
    asked. The alternative — `check-link` calling `getopt.getopt` itself and
    repeating the five post-checks — is the hand-rolled second parser the house
    rule exists to prevent. Existing callers are untouched by the default.

#### What happens to `bin/cds-link-check`

14. **It stays exactly as it is in this slice, and does not become a caller.**
    Its product is a reviewed, committed CSV whose 441 rows are RFC 200's result
    and another brief's work; making it call `check-link` means either
    re-running 441 live fetches to prove the file is unchanged, or landing an
    unverified rewrite of a corpus this slice does not own. **The cost, stated:
    two probing implementations exist until someone does it** — roughly 60 lines
    of `probe`/`first_pass`/`second_pass` duplicated in spirit — and
    `cds-link-check` keeps the two-pass logic, the three-state verdict and the
    refusal thresholds that no longer have to live in the fetcher. Converting it
    belongs with `test/05.2/one-home-for-every-citation`, which already opens
    that corpus.
15. **The rename is NOT in scope.** `cds-link-check`'s name lies the moment a
    second corpus exists, and renaming it is a two-sided edit:
    `CdsLinkCheckSidecarPinTest` reads the script's TEXT and pins its output
    filename and header against `CdsSeedLoader`'s constants. It buys this slice
    nothing, so it is said here and left.
16. **Tests, and they do not touch the network.** `bin/scripts-tests` (run by
    `bin/shell-tests` under `bin/test check`, i.e. the hook), using the fixture
    HTTP server already in that file — `python3 -m http.server` bound to
    127.0.0.1 on an ephemeral port — for every status path: 200, 404, a 405 that
    forces the GET fall-through, a 301 followed to a 200 and the same 301 under
    `-r`. **The `000` path is tested against 127.0.0.1 on a CLOSED port** — an
    immediate connection refusal, no DNS, no egress — and the timeout path
    against a fixture route that sleeps, with `-t 1`. Nothing resolves a public
    name, so the gate cannot flake on somebody's DNS; this brief lost a land
    today to exactly that kind of flake. Plus the option-grammar band (10, 11,
    20, 21, 22), `-h` on stdout, stdout EMPTY on a usage error, and input order
    preserved with duplicates.

### 3a. The state corpus uses it, and the sweep stays out of `bin/test`

`bin/test-state-programme-seed` gains `-k` (check links). Without it the script
is what it is today: it regenerates the seed migration from committed files and
touches no network. With it, it hands `bin/check-link` the 43 distinct urls of
`programmes.csv` — source and archive alike, because an archived address is a
document like any other — reads back `<status><TAB><url>`, applies **its own**
reading of the codes, and writes one reviewable, committed file:

    db/seed/test-state-programmes/link-status.csv   (url, url_status, url_checked_on)

The reading is the caller's judgement, and it is the RFC 200 one: `2xx` is
`found`; `404` and `410` are `not_found`; everything else — a 403, a 5xx, a
`000` — is `unknown` WITH the date we asked, which is "asked and got no usable
answer" and keeps the link. The three departments that refuse every client we
have therefore record as dated unknowns, never as dead. The script refuses to
overwrite the committed file when nothing answered at all (`found == 0`), for
the reason in decision 12 above.

The generated migration sets `url_status` and `url_checked_on` from that file. A
url absent from it stays `unknown` with NO date — the truthful "never asked".

**The status is recorded, and this slice speaks none of it.** RFC 200's rule
that `not_found` drops an anchor is a COPY decision about the Common Data Set
citation; applying it to the state answer would change a family-facing sentence
this slice is told to leave alone. Whether a dead state link stops being offered
is the owner's call, in its own slice.

### 4. Kotlin, and what stays byte-identical

- New `:db` model `ExternalDocument` / `ExternalDocumentId` and
  `ExternalDocumentsDao.upsert(url, status, checkedOn)`, keyed by url, on the
  `SourceDocumentsDao` pattern (write-shaped input,
  `ON CONFLICT (url) DO UPDATE`).
- `AdmissionTestStateProgramme` KEEPS its `sourceUrl: String` and
  `archiveUrl: String?` fields. The DAO's one read
  (`SELECT DISTINCT ON (state) ... ORDER BY state, school_year DESC,
  checked_on DESC`)
  joins `external_documents` twice — an inner join for the source, a left join
  for the archive — and hands the service the same two strings it hands it
  today.
- Therefore `AdmissionTestTimelineChatTool`'s payload keys `source_url` and
  `archive_url`, its `why` sentences, `SchoolDayCopy`, the tool DESCRIPTION and
  `prompts/coach-system-prompt.md` are **untouched**. No prompt edit means
  `bin/prompt-seed` cuts no version at land, and this run cannot collide with a
  sibling run over the prompt file.

## Files Modified

**New**

- `db/schema/create-external-documents.sql` — the domain and the table.
- `db/schema/adopt-external-documents-for-state-programmes.sql` — the two
  foreign keys, the backfill, the CHECK, the two dropped columns.
- `db/schema/seed-external-document-link-status-2026.sql` — GENERATED from the
  swept file. Never hand-edited.
- `db/seed/test-state-programmes/link-status.csv` — the swept, reviewed,
  committed file (`url`, `url_status`, `url_checked_on`).
- `bin/check-link` — new shared tooling, specified in section 3.
- `db/src/main/kotlin/ed/unicoach/db/models/ExternalDocument.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/ExternalDocumentsDao.kt`
- The tests listed below.

**Changed**

- `db/schema/ORDER` — three lines, placed by `ship-order` at land.
- `bin/test-state-programme-seed` — gains `-k` (sweep the links through
  `bin/check-link` and write `link-status.csv`), reads that file, emits the
  link-status seed, and emits future programme seeds in the two-foreign-key
  shape. Without `-k` it touches no network.
- `bin/pyfunctions.py` — `parse_short_options` gains an opt-in
  `allow_positional=False`; existing callers unchanged.
- `bin/scripts-tests` — a `bin/check-link` section, on the existing fixture HTTP
  server.
- `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeDao.kt` —
  two joins; the returned row shape is unchanged.
- `db/seed/test-state-programmes/PROVENANCE.json` — its note said a capture date
  would become a field in THIS slice. It now records that the field was decided
  against rather than deferred, and why, so the committed corpus does not carry
  a forward reference to work nobody is doing.
- Seed/DAO tests touched by the new column shape.

**Deliberately unchanged:** every `service/` file, the tool payload, the coach
prompt, and `service.conf`.

## Implementation Plan

1. `create-external-documents.sql` + `ORDER`; `nix develop -c bin/db-reset`
   green.
2. `ExternalDocument` model + `ExternalDocumentsDao` + their DAO tests.
3. `adopt-external-documents-for-state-programmes.sql`. The `UPDATE` is written
   as two statements — one setting `source_document_id` from a join on
   `source_url`, one setting `archive_document_id` from a join on `archive_url`
   where it is not null — rather than the sketch's single join, which is written
   above only to show the intent.
4. `AdmissionTestStateProgrammeDao` joins; the model is unchanged, so the
   service, the tool and the copy are untouched. Confirm by `git diff --stat`
   that `service/` and `prompts/` are absent from the run's diff.
5. `bin/check-link` + its `bin/scripts-tests` section + the `pyfunctions`
   widening, written and tested BEFORE it has a caller's data depending on it.
6. `bin/test-state-programme-seed -k`; review the 43 swept lines by eye; commit
   `link-status.csv`; regenerate the link-status seed migration.
7. `nix develop -c bin/test` (no module, no `-f`), then `bin/format -c`, then
   the hook.

## Tests

`:db`

- `ExternalDocumentsDaoTest` — upsert by url is idempotent and updates the
  status pair; `''`, `mailto:`, `ftp://` and a bare hostname are refused;
  `found` with no date is refused; `unknown` with NO date is accepted (never
  asked) and `unknown` WITH a date is accepted (asked, no usable answer) and the
  two are distinguishable by a query.
- `AdmissionTestStateProgrammeDaoTest` (extended) — a programme with no source
  document is refused; an archive document equal to the source is refused; an
  archive document with no source cannot be expressed; deleting a cited document
  fails; the read returns the same `sourceUrl` / `archiveUrl` strings the
  pre-migration read returned, for the same seeded row.
- `AdmissionTestStateProgrammeSeedTest` (extended, and its guard stays OUTSIDE
  the files this slice rewrites) — all 36 rows have a source document; exactly
  the seven expected states have an archive document; every url in
  `link-status.csv` is one of the 43 distinct urls of `programmes.csv` and every
  `found` / `not_found` row carries a date (so a hand-edit or a stale sweep
  fails the build, in the file, where the fix is).
- Deliberately unlike cases (brief 0010 D22), so no test is fitted to one
  student or one state: a state with a source and no archive (29 of 36), a state
  read through an archive (NH), an expired reading, a state we hold nothing for,
  and a student with no residency state at all — each still renders, and none
  gains or loses a link.

`:service`

- `AdmissionTestTimelineChatToolTest` — unchanged assertions must pass
  unchanged. `source_url` and `archive_url` keep their keys, their values and
  their null-when-absent behaviour, and no sentence gains a date. That the
  existing suite passes without edits is the evidence that this slice changed
  storage and not a sentence.

`bin/scripts-tests` (run by the hook)

- The `bin/check-link` section described in section 3, decision 16: every status
  path against a fixture server on 127.0.0.1, `000` against a closed port, the
  timeout against a sleeping route, the option-grammar band, `-h` on stdout,
  stdout empty on a usage error, and input order preserved through duplicates.
  No assertion resolves a public name.
