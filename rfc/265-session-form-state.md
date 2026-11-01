# RFC 265 — Session form state

**Slice:** `web/05/session-form-state` (brief 0013, _Unicoach on the web_).
**Status:** draft. **Base:** `main@02204093`. **Lane:** rfc.

## Executive Summary

The web gains one typed place to keep what a signed-in browser session carries
besides who it belongs to. That place is `sessions.metadata`, a JSONB column
that has existed since migration 0002 and that nothing has ever written. This
slice makes it real.

- **A committed `.proto` file is the schema of record** (brief 0013 D4b).
  `protoc` generates the message classes at build time. Protobuf's own
  `JsonFormat` writes them to the column as canonical proto3 JSON, so Postgres
  can still query it. Ian reviews every change to the file (D11, standing).
- **Form drafts, one typed message per form.** When a submission on a signed-in
  form fails, the form saves what the family typed into the session. It reads
  the draft back when the form renders and clears it when a submission succeeds
  (D4, D12). The one signed-in form that exists today, change email, is the
  first to use this; `web/06`–`08` each add their own message.
- **CSRF synchronizer tokens** (D22) live in the same message, and every `POST`
  a signed-in route accepts must echo the token. This composes with RFC 224's
  same-origin guard, whose own KDoc records that D22's tokens were still owed to
  this slice.
- **The size cap rises from 2,048 bytes to 256 KiB.** The largest draft on the
  roadmap measures 8,774 bytes. A draft too big for the cap fails loudly, and
  the family keeps every word on screen.

## Detailed Design

### The schema of record

`db/src/main/proto/unicoach/session/v1/session_metadata.proto`:

```proto
// The schema of record for sessions.metadata (brief 0013 D4b, RFC 265).
// Stored as canonical proto3 JSON in a JSONB column, so Postgres can query it.
// Ian reviews every change to this file, like DDL (brief 0013 D11, standing).
syntax = "proto3";

package unicoach.session.v1;

option java_package = "ed.unicoach.db.session";
option java_multiple_files = true;

// What a signed-in browser session carries besides who it belongs to.
message SessionMetadata {
  // The synchronizer token every state-changing request on this session must
  // echo (brief 0013 D22). 32 random bytes; base64 in the stored JSON.
  bytes csrf_token = 1;

  // What the family typed into a signed-in form and has not yet submitted
  // successfully (brief 0013 D4, D12).
  FormDrafts drafts = 2;
}

// One field per signed-in form, each its own message. A form identifies itself
// by the field it owns, so a new form is a change to this file.
//
// A draft holds what the browser posted, valid or not: a draft that could hold
// only valid input would lose the typing a validation error exists to give
// back. So a control the family types or picks is a string, as posted. A
// validation error is never stored; it is derived again from the values by the
// next submission.
message FormDrafts {
  ChangeEmailDraft change_email = 1;
}

// The change-email form (public-web, /account/email).
message ChangeEmailDraft {
  string new_email = 1;
}
```

Canonical proto3 JSON fixes the stored spelling, and SQL must match it:

- fields in lowerCamelCase (`csrfToken`, `drafts.changeEmail.newEmail`);
- `bytes` as standard base64;
- empty messages and default values omitted.

Example query: `metadata -> 'drafts' -> 'changeEmail' ->> 'newEmail'`.

**One message per form, one field per control (D-A, Ian).** Field names are
checked by the compiler, and each one reaches Ian under D11. Each form's message
lands in the slice that designs its controls: `web/06` onboarding, `web/07` your
details, `web/08` the college list. Values stay the strings the browser posted,
so a vocabulary such as `IncomeBand` keeps its one home in the Kotlin enum and
the schema CHECK. A hidden field our own page writes (an entry id, a `version`)
may be an integer, because only tampering can make it anything else.

**Errors are not stored (Ian).** An error is the validator's verdict on a value,
and a stored copy goes stale when a rule or its wording changes. Some errors are
not about the value at all: a taken address, or a list that moved. Those
describe one attempt and would be wrong if shown again later.

### Reading and writing at the DAO boundary

- `Session.metadata` and `NewSession.metadata` change from `String?` to
  `SessionMetadata`, which is non-null. A SQL `NULL` reads as
  `SessionMetadata.getDefaultInstance()`. Proto3 does not distinguish "absent"
  from "default", so a nullable Kotlin type would represent one state twice.
- **No backfill.** Every production caller passes `null` today (the two
  `AuthService` mint sites), and the only non-null writes are
  `SessionsDaoTest`'s own. No production row has ever held a value.
- One codec object in `:db`, `SessionMetadataJson`, owns both directions:
  `JsonFormat.printer().omittingInsignificantWhitespace()` and the strict
  `JsonFormat.parser()`. Its writer, `printOrNull`, returns `null` for the
  default instance, so every write binds SQL `NULL` for "nothing set". The
  column has one spelling for that state, never `NULL` on some rows and `{}` on
  others.
- **The parser is strict (D-E, Ian: "We don't have users").** An unknown field,
  or JSON that is not a `SessionMetadata`, is row corruption. It surfaces as
  `CorruptPersistedValueException`, like every other DAO's corrupt read. A later
  change that removes a field while sessions still hold it clears those rows in
  its own migration.
  - The exception names the row and withholds the value. The column is
    `pii.jsonb` and holds the CSRF token. The parser's own message can quote the
    stored JSON, so its failure is chained as a `WithheldMessageException`. That
    keeps the parser exception's type and stack and drops its text.
- New `SessionsDao.writeMetadata(session, id, currentVersion, metadata)`:

  ```sql
  UPDATE sessions
  SET version = ?, metadata = ?::jsonb
  WHERE id = ? AND version = ? AND is_revoked = false
  RETURNING *
  ```

  It never touches `expires_at`. A `23514` naming `sessions_metadata_size_check`
  maps to a typed `SessionMetadataTooLargeException`, distinguished by
  constraint name. It carries the session id and the refused value's printed
  size, and never the driver's DETAIL, which is the whole row. `create` and
  `remintToken` map that constraint the same way. Every other failure keeps the
  DAO's existing mapping. **The cap has one home: the CHECK.** Kotlin never
  restates the number; it reads the database's refusal.

### The size cap: measured

The slice requires the cap to be measured against the largest form in scope.
That form is `web/08`'s college-list add/edit form, not the money form the spec
named. Its `reasons` field is CHECKed at 2,048 characters
(`college_list_entries_reasons_length_check`), while the money form is four
short picker values. The measurement used `pg_column_size`, the function the
CHECK itself uses. Each case is one session holding a CSRF token plus a draft
for each of the three planned forms, with every control at its longest valid
value:

| `reasons` content                       | `pg_column_size` (JSONB) |
| --------------------------------------- | ------------------------ |
| empty (all other fields at max)         | 582 bytes                |
| 2,048 ASCII characters                  | 2,630 bytes              |
| 2,048 four-byte characters (worst case) | **8,774 bytes**          |

The new limit is **262,144 bytes (256 KiB)**, about 30× the worst case (Ian, RFC
265 approval). No form on the roadmap comes near it, so a later form does not
reopen the migration. Today's 2,048 bytes cannot hold even one ASCII `reasons`
draft. Each later form slice records its own draft's worst case in its own RFC.

### The migration (DDL)

`db/schema/raise-sessions-metadata-cap.sql`, appended to `db/schema/ORDER`:

```sql
-- sessions.metadata now holds a typed SessionMetadata (session_metadata.proto)
-- as canonical proto3 JSON: a CSRF token and signed-in form drafts (RFC 265,
-- brief 0013 D4/D4b/D22). The 2048-byte cap predates any writer; the largest
-- planned draft measures 8774 bytes, and the cap rises to 256 KiB (RFC 265,
-- "The size cap: measured"). Column type unchanged: JSONB stays queryable.
ALTER TABLE sessions
  DROP CONSTRAINT sessions_metadata_size_check,
  ADD CONSTRAINT sessions_metadata_size_check
    CHECK (metadata IS NULL OR pg_column_size(metadata) <= 262144);
```

There is no table rewrite. Re-adding the CHECK scans `sessions` once to validate
it, and every row is `NULL`.

### The service seam: `SessionMetadataService` (`:service`)

```kotlin
sealed interface MetadataWrite {
  data class Written(val session: Session) : MetadataWrite
  /** The change would exceed the column's CHECK; nothing was written. */
  data object TooLarge : MetadataWrite
  /** The change holds text jsonb cannot store (a NUL); nothing was written. */
  data object Unstorable : MetadataWrite
  /** The session was revoked or deleted between read and write. */
  data object SessionGone : MetadataWrite
}

class SessionMetadataService(private val database: Database) {
  suspend fun update(
    seen: Session,
    change: (SessionMetadata) -> SessionMetadata,
  ): Result<MetadataWrite>
}
```

`update` applies `change` to the metadata the caller already read, then writes
it with the version it read. That is **one UPDATE in the common case**. On a
version conflict it re-reads the row, re-applies `change` to the fresh value and
tries again, up to three attempts in all. `change` is a pure function of the
stored value, so a concurrent write to a different form's draft is kept, not
overwritten.

`TooLarge` and `Unstorable` are outcomes because a family's input causes them.
Each is logged with the session id. The overflow line also carries the refused
size. Neither line carries the driver exception, whose message quotes the JSON.

**How draft writes interact with the session-expiry refresh.**

- `public-web` sessions have no sliding refresh. They live 30 days from mint
  (RFC 224).
- `rest-server`'s `SessionsDao.extendExpiry` slides a row to `NOW() + 7 days`
  and leaves `metadata` alone.
- A metadata write leaves `expires_at` alone, so saving a draft never keeps a
  session alive.
- Both statements bump `version`, so they serialize through the row's optimistic
  version. An iOS refresh that lands between a web read and its write costs the
  web one re-read and retry. Neither the draft nor the refresh is lost.

### `public-web`: the caller carries its session

`WebCaller` gains `session: Session`. Its KDoc said the row was not carried
because nothing read it; now something does. `resolveWebCaller` still reads once
per request and caches the result on `call.attributes`. After a metadata write,
the cached caller is replaced with the returned row, so a second write in the
same request does not start from a stale version.

`WebCaller.csrfToken` is non-null, and it is derived from `session` rather than
copied beside it. The constructor is private. `WebCaller.of` and `withSession`
are the only ways to build one, and both read the token from the row they are
given, so the token and the row cannot disagree.

### CSRF synchronizer tokens (D22, D-B)

- **Minted with the session (D-F).** Both `AuthService` mint sites create the
  row with a `SessionMetadata` that already holds 32 bytes from `SecureRandom`.
  `resolveWebCaller` treats a session whose token is not exactly
  `TokenGenerator.TOKEN_BYTES` (32) long as no caller. An empty token is a row
  minted before this deploy, which is signed out once; that is acceptable with
  no users. Any other size is a row no code wrote. The log line gives the size
  and never the bytes.
- **A re-minted row starts over.** `SessionsDao.remintToken` takes
  `newMetadata: SessionMetadata` and REPLACES the row's metadata. A row that
  changes hands, such as registration over an existing session, starts with a
  fresh token and no drafts, so the previous holder's drafts and token never
  become the new user's. `AuthService` mints a token at all three sites: two
  `create` calls and one `remintToken`.
- **Enforced on the route, like access.** `postSignedIn` and `postVerified` are
  the registration wrappers that are already the access authority (RFC 224).
  They reach the check through `handleAccess`, the admission step all four
  wrappers share, which verifies the token for every method except `GET` and
  `HEAD`. Every session-bearing POST is covered by construction, including
  routes later slices add and the routes that call `handleAccess` directly
  (invite accept, report connect).
  - The token is read from the form field `_csrf`, or from the `X-CSRF-Token`
    header for the chat client's `fetch`.
  - Comparison is constant-time (`MessageDigest.isEqual`).
  - A missing or wrong token gets the same branded 403 page the same-origin
    guard renders. The refusal is logged at WARN with the method, the path, the
    session and the reason: `HEADER_REPEATED`, `NOT_A_FORM`,
    `FIELD_ABSENT_OR_REPEATED` or `MISMATCH`. A stale page and a request that
    never carried the form can then be counted apart. No token is logged. The
    chat stream gets that page too, sent before any stream opens, as the guard
    already answers it; the chat client shows its retriable failure for any body
    that is not its own notice.
- **Rendered once.** One `FormControls` helper writes the hidden `_csrf` input,
  and every form a signed-in page renders uses it: sign-out in the header,
  resend verification, change email and the chat composer. The chat client sends
  the composer's `_csrf` value as `X-CSRF-Token`.
- POSTs with no session (sign-in, registration, `POST /verify-email`) stay
  covered by the same-origin guard alone. There is no session to bind a token to
  (D5), which is the same line D12 draws for drafts.

### The draft rail (`public-web`)

```kotlin
/** One signed-in form's place in FormDrafts: the one mapping from a form to its field. */
class FormSlot<D : Message>(
  val read: (FormDrafts) -> D?,                       // null when the field is unset
  val write: (FormDrafts.Builder, D) -> Unit,
  val clear: (FormDrafts.Builder) -> Unit,
)
```

Each form declares its slot beside its page code, e.g. `CHANGE_EMAIL_DRAFT`, and
builds its own typed draft from the posted parameters. On an `ApplicationCall`
with a `WebCaller`:

- `draftFor(slot): D?` is the read-through. A GET renders the form from the
  draft when one exists, with the values only and no errors. Otherwise it
  renders from the stored domain values. A missing draft is an ordinary form,
  never an error (guided, not gated).
- `saveDraft(slot, draft): DraftSave` runs when a POST did not succeed. It
  returns `Kept`, `NotKept(why)` or `SessionGone`.
- `clearDraft(slot)` runs after the domain write succeeded. It writes nothing
  when there is no draft.

**Draft writes are best effort.** The POST's answer is what the family came for,
and the draft is a side effect of it. No outcome of a draft write replaces that
answer, a fault included:

- A fault in `saveDraft` is logged at WARN with the session and its cause. It
  returns `NotKept(NOT_SAVED)`, and the refused form renders as it would
  otherwise, with a notice.
- A fault in `clearDraft` is logged and left alone. The change has already
  committed, so the redirect still says it happened. The cost is a stale draft
  on the form's next GET, which the family can see and correct.

**A failed POST renders straight back, as it does today.** The route keeps its
current pattern (`Routing.kt`, change email). It renders the form in the POST
response with the posted values and the errors it already holds, at the status
it already uses. It does not redirect. Saving the draft is what lets the values
survive the family leaving and coming back, so a POST costs **at most one
session UPDATE**: a save on failure, a clear on success, never both.

**Overflow is loud and loses nothing.** When `saveDraft` returns
`NotKept(TOO_LONG)`, the response is the same failed-POST page, still holding
every posted value, with one notice above the form:

> Some of what you typed is too long for us to keep if you leave this page. It
> is all still here — shorten your longest answer, then save.

Any other `NotKept` (unstorable text, or a fault) is the same page with a notice
that asks for nothing:

> We could not keep what you typed if you leave this page. It is all still here.

`SessionGone` sends the family to sign-in, as any other request on a revoked
session does.

### The first user: change email (D-C)

The change-email form is the one form behind a session today, so D12 covers it.
It is also the rail's only production user, so the rail is tested end to end
through a real page.

- **GET** fills the address box from the draft when one exists.
- **POST, refused by `:service`** (`ValidationFailure`, `DuplicateEmail`): save
  `ChangeEmailDraft { new_email }` and render as today.
- **POST, success:** clear the draft, then redirect as today.
- **A body that is not this form** (absent or repeated `email`) saves nothing.
  It is not a submission of the form.

### The build

- `flake.nix` adds `pkgs.protobuf` (protoc 34.1 at the pinned nixpkgs).
- `gradle/libs.versions.toml` adds `protobuf-java` and `protobuf-java-util` at
  the matching runtime version, 4.34.1, which is confirmed on Maven Central.
- A `:db` task, `generateProto`, runs `protoc --java_out` over `src/main/proto`
  into `build/generated/sources/proto/main/java`, which joins the main Java
  source set.
  - Inputs and outputs are declared, so Gradle caches the task.
  - `compileJava` and `compileKotlin` depend on it.
- **One version, checked (D-G).** `generateProto` runs `protoc --version` first.
  If the result is not the catalog's protobuf version, it fails the build and
  names both versions. A nixpkgs bump therefore cannot silently produce code the
  runtime refuses. The guard has no test of its own, because every build
  exercises it: the versions agree today, so an inverted comparison would fail
  every build. The mismatch branch was checked once by hand, by setting the
  catalog to 4.34.2.
- Generated sources are build output, never committed. The `.proto` is the
  reviewed artifact, and the Java is derived from it. `:db` exposes
  `protobuf-java` as `api`, because `Session.metadata`'s type is in its public
  surface.

## Files Modified

- `flake.nix`, `gradle/libs.versions.toml`, `db/build.gradle.kts`
- `db/src/main/proto/unicoach/session/v1/session_metadata.proto` (new)
- `db/schema/raise-sessions-metadata-cap.sql` (new), `db/schema/ORDER`
- `db/src/main/kotlin/ed/unicoach/db/models/{Session,NewSession}.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt`, `DaoExceptions.kt`
- `db/src/main/kotlin/ed/unicoach/db/session/SessionMetadataJson.kt` (new)
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` (mint sites),
  `SessionMetadataService.kt` (new), wired in `public-web`'s `Application.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/auth/{WebSession,RouteAccess}.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/FormDrafts.kt` (new: `FormSlot`
  and the rail)
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` (change email)
- `public-web/src/main/kotlin/ed/unicoach/web/render/{FormControls,Layout,ChangeEmailPage,AccountPage,VerifyEmailPage,ChatPage}.kt`,
  and any other render file that holds a signed-in form
- `public-web/src/main/ts/chat.ts` (send `X-CSRF-Token`)
- Tests beside each of the above

## Implementation Plan

1. **Build.** Add `protobuf` to `flake.nix` and the two libraries to the
   catalog, then add `generateProto` with its version check to `:db`. Verify
   with `nix develop -c ./gradlew :db:compileKotlin`, which generates and
   compiles `SessionMetadata`.
2. **Schema and codec.** The `.proto` exactly as approved, and the
   `SessionMetadataJson` codec.
3. **Migration and `:db`.** The migration plus its `ORDER` line. Retype
   `Session`/`NewSession`, the row mapper, `create` and `remintToken` (which now
   takes the replacement metadata). Add `writeMetadata` and the too-large
   mapping. Update `SessionsDaoTest`.
4. **`:service`.** Every `AuthService` path that creates or rebinds a session
   row mints a token: both `create` sites and `remintToken`.
   `SessionMetadataService` gets the bounded retry.
5. **CSRF in `public-web`.** `WebCaller.session`, the wrong-size-token refusal
   in `resolveWebCaller`, the token check in `handleAccess`, the hidden-field
   helper, and every signed-in form plus the chat client sending the token.
6. **Drafts.** `FormSlot`, the rail, and the change-email wiring, including the
   overflow notice.
7. **Verify.** `nix develop -c bin/test` unscoped, plus `bin/build-web-assets`
   for the chat client.

## Tests

Real database throughout, no mocks (project rule).

**`:db`**

- **Round trip.** A `SessionMetadata` with a token and a change-email draft is
  written and read back equal. The stored JSONB has the canonical spellings
  (`metadata->>'csrfToken'`, `metadata->'drafts'->'changeEmail'->>'newEmail'`).
- **`NULL` and default.** A `NULL` column reads as the default instance.
- **Strict parsing.** Stored JSON with an unknown field, or that is not a
  `SessionMetadata` (e.g. `"csrfToken": 7`), raises
  `CorruptPersistedValueException`.
- **Overflow.** A value past the cap returns `SessionMetadataTooLargeException`
  naming the session and the refused size. The row's previous metadata and
  version are unchanged. That is the "never silently truncated" half.
- **One spelling for nothing.** The default instance is stored as SQL `NULL` by
  `create`, `writeMetadata` and `remintToken`.
- **Withheld corruption.** A corrupt row's exception chains the parser failure
  by type, and no message in the chain quotes the stored value.
- **Writes are scoped.** `writeMetadata` never moves `expires_at`, and a stale
  version or a revoked row updates nothing.
- **Remint replaces.** `remintToken` replaces the row's metadata, so the
  previous holder's draft does not survive.

**`:service`**

- **Mint.** Both `create` paths make a session holding a 32-byte token, and two
  sessions never share one.
- **One UPDATE.** `update` costs one UPDATE when uncontended.
- **Retry.** A conflict from a real interleaved `extendExpiry` is retried. The
  retry keeps the new `expires_at` and still applies the change.
- **Outcomes.** Too large returns `TooLarge`; a NUL returns `Unstorable`;
  revoked returns `SessionGone`. None writes anything.

**`public-web`**

- **Token-less sessions.** A session row with `NULL` metadata resolves as no
  caller and is sent to sign-in, and so does one whose token is not the minted
  size.
- **Refusal log.** Each refusal's WARN names its reason, and no WARN carries a
  token.
- **Token enforcement.** Every session-bearing POST (sign-out, resend, change
  email, chat start and turn) is refused with 403 when the token is absent or
  wrong, and succeeds with it. Chat accepts the token as a header; the others as
  a form field. Sign-in and registration POSTs need no token.
- **Token rendering.** Every signed-in page that renders a form renders the
  hidden `_csrf` with the session's token. This is checked on the served pages,
  not by reading source.
- **Change email through the rail.**
  - A refused address is saved, and a later GET shows it in the box with no
    error.
  - A successful change clears the draft.
  - An over-cap address renders the failed-POST page with the address still in
    the box and the overflow notice, and stores nothing.
  - An address holding a NUL renders the failed-POST page with the not-saved
    notice, and stores nothing.
  - With the metadata writer's database unreachable, a refused address still
    renders the refused form with the typing and the not-saved notice, and a
    successful change still redirects.
  - A GET with no draft renders the ordinary empty form.
- **Chat client (`deno test`).** It sends `X-CSRF-Token` from the composer's
  `_csrf`.

## Decisions

- **D-A — one typed message per form, one field per control** (Ian). Values are
  strings as posted; integers only for hidden fields our page writes. Each
  form's message lands in its own slice.
- **Errors are not stored** (Ian). They are derived again by the next
  submission.
- **D-B — CSRF tokens land here**, in the same message. D22 placed them in this
  slice, and RFC 224's guard says they are still owed.
- **D-C — change email is the first user of the drafts.** It is the one
  signed-in form today, so D12 covers it, and it gives the rail a real page to
  be tested through.
- **D-D — the cap is 256 KiB** (Ian). The measured worst case is 8,774 bytes.
- **D-E — the parser is strict** (Ian: no users, no backward compatibility).
- **D-F — CSRF tokens are minted with the session.** A token-less session is not
  a web caller.
- **D-G — generated code is build output**, with a hard `protoc`/runtime version
  check.

## Rollback

Revert the commit. The migration's only effect is a looser CHECK, and the old
`String?` mapper reads any stored metadata as opaque text that nothing uses. If
the old cap is ever restored, clear drafts first:
`UPDATE sessions SET metadata = NULL WHERE pg_column_size(metadata) > 2048`.
