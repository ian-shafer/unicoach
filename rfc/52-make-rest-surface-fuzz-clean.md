# RFC 52: Make the REST surface fuzz-clean

## Executive Summary

`bin/test-fuzz` (the Schemathesis contract referee) exits non-zero today because
the rest-server and the committed `api-specs/openapi.yaml` disagree on the
rejection surface in seven ways. Divergences 1–5 are server defects (the server
violates the contract); divergence 6 is a contract gap (the server is correct
but the contract under-documents a real response); divergence 7 couples a sixth
server defect (`registerUser` password semantics) with a harness-configuration
gap (Schemathesis's `negative_data_rejection` check omits the legitimate `413`).
This RFC fixes the server defects and the contract gap, configures the referee
to accept the documented `413` as a negative-data rejection, then removes the
harness's defect-suppression scaffolding so it runs the full `--checks all`.

The seven divergences, each confirmed by running `bin/test-fuzz` and probing the
booted server directly:

1. **Type coercion** — `POST /api/v1/auth/register` with `{"name": false}`
   returns `201` with `name: "false"`. Jackson coerces a JSON boolean into the
   Kotlin `String` field.
2. **415-not-400** — `POST /api/v1/auth/login` with an unparseable JSON body
   (`null`) returns `415`, a status the contract's rejection set excludes.
3. **Unhandled validation** — `register` with a malformed email returns `500`:
   an unchecked `as ValidationResult.Valid` cast throws because
   `RegistrationValidator` never checks email format.
4. **Wrong error content-type** — the `415` body is `text/plain`, not the
   contract's `application/json` `ErrorResponse`.
5. **Missing `Allow`** — a non-`POST` verb on `/api/v1/auth/register` returns
   `405` with no RFC-9110 `Allow` header, because the route is a bare leaf
   `post()` lacking the `rejectUnsupportedMethods` wrapper its siblings carry.
6. **Undocumented `413`** — the application-scope 8 KiB `RequestBodyLimit` makes
   every route reject an oversized body with `413 payload_too_large`, but
   `api-specs/openapi.yaml` documents `413` only on the conversation write
   operations. An oversized generated body on an in-scope write operation makes
   Schemathesis fail `Undocumented HTTP status code: 413`. Unlike defects 1–5,
   the server is already conformant; the fix is in the contract.
7. **`registerUser` password semantics + negative-side `413`** —
   `RegistrationValidator` measures password length in UTF-16 code units
   (`String.length`) and tests character classes with Unicode-aware
   `isUpperCase`/`isLowerCase`/`isDigit`, while the contract's `password` schema
   is code-point `minLength: 8`/`maxLength: 128` plus an ASCII `pattern`
   `(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])`. An astral password of 7 code points / 10
   UTF-16 units (e.g. `򀣀򉶓jP3R񋧰`), or a Greek (`Ωωσσσσ11`) or Arabic-Indic-digit
   (`Aa٣٣٣٣٣٣`) password, satisfies the server but violates the schema, so the
   server returns `201` where the contract requires rejection — Schemathesis's
   `negative_data_rejection` flags it (the password half is a server defect).
   Separately, that same check's expected-status set
   (`400,401,403,404,405,406,409,422,428,5xx`) omits `413`, so an oversized
   negative body's legitimate `413` (the global `RequestBodyLimit`,
   divergence 6) is flagged as an accepted schema-violating request; this half
   is fixed in the referee's check configuration, not the server.

Defects 2 and 4 share one fix (see §"Defects 2 & 4"). The fixes touch four
production files, the OpenAPI contract, a new root `schemathesis.toml`, and the
harness, and were verified end-to-end to make `bin/test-fuzz` exit `0` across
multiple seeds — including the astral-password and oversized-object seeds — with
no new test regressions.

## Detailed Design

### Defect 1 — reject type-mismatched scalar fields (`Serialization.kt`)

Configure the Jackson mapper in `configureSerialization()` to refuse coercion of
mismatched JSON scalar shapes instead of stringifying them.

`disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)` governs only string-shaped
inputs flowing into numeric/boolean targets, so it does not reject the
boolean→`String` case (verified: `{"name": false}` is still coerced to
`"false"`); that case requires Jackson's `CoercionConfig` on the `Textual`
logical type:

```
coercionConfigFor(LogicalType.Textual)
  .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
  .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
  .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
```

Both controls are applied: the `CoercionConfig` rejects a JSON boolean/number
supplied for a `String` field (defect 1, e.g. `RegisterRequest.name`), and the
disabled `ALLOW_COERCION_OF_SCALARS` rejects a JSON string supplied for the
numeric/boolean fields elsewhere on the in-scope surface
(`UpdateStudentRequest.version: Int`), keeping the whole request surface strict
against scalar type-punning rather than fixing only the one field the current
seed happened to mutate.

**Error path (no new handler needed).** A failed coercion makes Jackson throw a
`MismatchedInputException` during request deserialization; Ktor's
`ContentNegotiation` request converter wraps that in `BadRequestException`,
which the existing `StatusPages` `exception<BadRequestException>` handler maps
to `400` `application/json` `{"code":"bad_request", ...}`. Confirmed against the
booted server: `{"name": false}` now returns `400`.

### Defects 2 & 4 — convert the opaque 415 to a 400 JSON ErrorResponse (`StatusPages.kt`)

Replace the `exception<UnsupportedMediaTypeException>` handler with a
`status(HttpStatusCode.UnsupportedMediaType)` handler that responds `400` with a
JSON `ErrorResponse`.

The existing `exception<UnsupportedMediaTypeException>` handler is dead code on
Ktor 3.4.2. Verified by patch-rebuild-probe: a `-d null` body (Content-Type
`application/json`) **and** a genuine `text/plain` body produce the _identical_
`415` + `text/plain` "Cannot transform this request's content to …" response,
and neither invokes that handler. The exception Ktor throws
(`CannotTransformContentToTypeException extends ContentTransformationException`,
which is **not** `UnsupportedMediaTypeException` and **not**
`BadRequestException`) never reaches a `StatusPages` exception handler at all —
if it did, the catch-all `exception<Throwable>` would have produced `500`.
Instead Ktor maps it to a `415` **response status**, which only a `status()`
handler intercepts. The replacement registers
`status(HttpStatusCode.UnsupportedMediaType)` to respond
`HttpStatusCode.BadRequest` with
`ErrorResponse(code = "bad_request", message = "Request body could not be
read as the expected application/json payload")`.

This single handler resolves both checks for the login case: the status becomes
`400` (in the contract's accepted rejection set
`400,401,403,404,405,406,409,422,428,5xx`, which excludes `415`), and the body
becomes `application/json`. Because this API accepts only `application/json`,
every body the JSON converter cannot read — JSON `null`, an unparseable payload,
or a non-JSON content type — is uniformly a client `400`; the `415`-status layer
cannot distinguish these (none arrives as a typed exception), so uniform mapping
is the only option. Responding `400` from a `status(415)` handler does not
recurse (there is no `status(400)` handler). The now-unused
`io.ktor.server.plugins.UnsupportedMediaTypeException` import is removed.

This changes one deliberately-asserted behaviour: a `text/plain` POST that
previously returned `415` now returns `400`. The existing assertion in
`AuthRoutingTest` is updated accordingly (see Tests).

### Defect 3 — validate email format up front (`RegistrationValidator.kt`)

Make `RegistrationValidator.validate` reject a malformed email by delegating to
`EmailAddress.create`, replacing the blank-only check: when
`EmailAddress.create(input.email)` is `ValidationResult.Invalid`, append a
`FieldError("email", "Email must be a valid email address")`.

`EmailAddress.create` (`common`) already encodes the validity rule (non-blank,
interior `@`) and returns `Invalid` for both blank and malformed input, so this
single check subsumes the prior `isBlank` check. A `ValidationFailure` is
returned as a `400` with a field error by the existing
`respondRegisterValidationFailure` path.

This also makes the unchecked
`(EmailAddress.create(email) as ValidationResult.Valid)` cast in
`AuthService.register` total: it is reached only after validation has confirmed
the email is `Valid`, so the `ClassCastException` that produced the `500` can no
longer occur. `PersonName.create` carries no equivalent hazard — it rejects only
blank, which the existing name check already guarantees. `service` already
depends on `common`, so the two added imports introduce no new module edge.

### Defect 5 — carry the `Allow` header on `/register` (`AuthRoutes.kt`)

Replace the bare leaf `post("/register")` with a `route("/register")` block that
holds `post { handleRegister() }` and
`rejectUnsupportedMethods(HttpMethod.Post)`, matching the `/login`, `/me`, and
`/logout` siblings.

`rejectUnsupportedMethods(HttpMethod.Post)` (`Routing.kt`) appends `Allow: POST`
and responds `405` for any other verb. The `405` body stays `text/plain` (as for
every sibling); the contract documents no `405` response content, so
Schemathesis's `unsupported_method` check validates only the status and the
`Allow` header, both now correct.

### Contract gap — document the global `413` on the write operations (`api-specs/openapi.yaml`)

Add a `413` `payload_too_large` `ErrorResponse` response to the five
body-bearing non-conversation write operations in `api-specs/openapi.yaml`:
`registerUser`, `loginUser`, `createStudent`, `updateStudentMe`, and
`logoutUser`.

The server installs `RequestBodyLimit` once at application scope, so every route
rejects a body over the configured `server.requestSize.maxSize` (8 KiB) limit
with `413` `ErrorResponse(code = "payload_too_large", ...)` before content
negotiation runs (`rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md`,
`StatusPages.kt`). The contract already models this `413` on the conversation
write operations but omits it elsewhere, so an oversized generated body on a
fuzz-exercised write operation makes Schemathesis fail
`Undocumented HTTP status
code: 413`. The four in-scope fuzz write operations
(`registerUser`, `loginUser`, `createStudent`, `updateStudentMe`) are the ones
that trip the referee; `logoutUser` carries no fuzz coverage
(`--exclude-operation-id
logoutUser`) but takes a body subject to the same
limit, so it is documented for contract fidelity.

Each new `413` entry mirrors the existing conversation modelling exactly —
`description: Request body exceeds the configured size limit` and
`content.application/json.schema: $ref '#/components/schemas/ErrorResponse'` —
placed alongside the operation's other rejection responses. The `ErrorResponse`
component already exists, so no schema component or `$ref` target is added, and
no server change accompanies this: the server is already conformant.

### Harness — run the full check set and a dependency-free port guard (`bin/test-fuzz`)

Three changes make the harness assert conformance rather than document defects:

1. **Drop the `unsupported_method` suppression.** Remove
   `--exclude-checks unsupported_method` from the Schemathesis invocation so the
   full `--checks all` runs (only `ignored_auth` remains excluded — it is a
   harness artifact of static cookie injection, unchanged here). Defect 5's fix
   makes this check pass.
2. **Delete the defect documentation.** Remove the `KNOWN SERVER DEFECTS`
   comment block before the invocation and the corresponding `help()` text and
   `log-info` lines that describe the harness as a non-zero-by-design referee.
   The harness now exits `0` against the conformant server; the `help()`
   exit-code table and "Excluded from every run" list are updated to drop the
   `unsupported_method` exclusion and the four-known-defects framing.
3. **Pure-bash port guard.** Replace the `python3` `connect_ex` probe with a
   `/dev/tcp` connect:

   ```
   if (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then
     exec 3>&- 3<&-; fatal "Port $PORT is already served. Stop the occupant before fuzzing (the harness must boot its own freshly-built rest-server)."
   fi
   ```

   Verified in the flake's bash (5.3.9): it detects a bound listener, refuses
   instantly on a closed port with no hang, and needs no timeout. This keeps the
   guard occupant-agnostic while dropping the inline `python3` snippet (`nc -z`
   and an HTTP `curl` probe remain unsuitable, per the `bin/SPEC.md` Port
   Liveness invariant). `python3` remains a hard dependency for the Schemathesis
   venv; only the port probe stops using it.

The `bin/SPEC.md` "Port Liveness" invariant currently prescribes the `python3`
`connect_ex` probe; it must be updated to prescribe the bash `/dev/tcp` probe.
Per the RFC process that invariant change is carried out-of-band by the
spec-sync pass, not by this RFC's Implementation Plan, and so is not listed
under Files Modified.

### Divergence 7 — password reconciliation (`RegistrationValidator.kt`)

Replace `RegistrationValidator`'s UTF-16 length and Unicode character-class
checks with code-point length and ASCII character-class checks, making the
validator match the contract's `password` schema (`minLength: 8`,
`maxLength: 128`, ASCII `pattern (?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])`) exactly.

- **Length** becomes the Unicode code-point count,
  `password.codePointCount(0, password.length)`, compared against `< 8` and
  `> 128`. `String.length` counts UTF-16 code units, so an astral character (a
  surrogate pair) counts twice; the schema's `minLength`/`maxLength` are
  JSON-Schema code-point counts. A 7-code-point astral password is 10 UTF-16
  units and passed the prior `length >= 8`.
- **Character classes** become ASCII ranges: `any { it in 'a'..'z' }`,
  `any { it in 'A'..'Z' }`, `any { it in '0'..'9' }`.
  `Char.isUpperCase()`/`isLowerCase()`/`isDigit()` accept BMP non-ASCII letters
  and digits (Greek `Ω`, Arabic-Indic `٣`) that the schema's ASCII `pattern`
  classes exclude.

This tightens accepted passwords: a non-ASCII uppercase letter or non-ASCII
digit no longer satisfies a complexity requirement. The field-error messages are
unchanged; "characters" now denotes code points. The `name` (`minLength: 1`, no
maximum) and `email` (`format: email`, no length bound) rules carry no
length-unit or class divergence and are untouched by this reconciliation.

### Divergence 7 — negative-data 413 acceptance (`schemathesis.toml`, `bin/test-fuzz`)

Add a committed `schemathesis.toml` at the repository root declaring `413` a
valid `negative_data_rejection` status, and pass it to Schemathesis via
`--config-file`:

```toml
[checks.negative_data_rejection]
expected-statuses = ["400", "401", "403", "404", "405", "406", "409", "413", "422", "428", "5xx"]
```

The application-scope 8 KiB `RequestBodyLimit`
(`rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md`, `StatusPages.kt`)
rejects any over-limit body with `413` before content negotiation, and negative
generation can exceed it. `negative_data_rejection`'s default expected-status
set omits `413`, so it flags that legitimate rejection as "API accepted
schema-violating request". The contract documents `413` as a response on these
operations (divergence 6); this setting aligns the negative-data check with that
documented surface. The setting affects only `negative_data_rejection`; a `413`
arises solely from a body over the limit, so no `2xx` acceptance can hide behind
it.

`bin/test-fuzz` passes the file via the global option preceding `run` —
`--config-file "$PROJECT_ROOT/schemathesis.toml"` ahead of `run "$SPEC_PATH"` —
and emits one `log-info` line stating that a `413` is accepted as a valid
negative-data rejection (the 8 KiB limit legitimately rejects oversized negative
payloads), keeping the harness self-documenting.

### Error Handling / Edge Cases

- **Coercion failure → 400, not 500/415.** A type-mismatched scalar surfaces as
  `BadRequestException` → `400` JSON, verified end-to-end.
- **Unparseable / null / wrong-content-type body → 400 JSON** uniformly (see
  §"Defects 2 & 4").
- **Empty body** already returns `400` JSON (`bad_request`) via the existing
  `BadRequestException` handler and is unchanged.
- **Malformed email → 400 with `email` field error**, not `500`.
- **Non-POST on `/register` → 405 with `Allow: POST`.**
- **Oversized body (> 8 KiB) → 413 `payload_too_large` JSON** on every route,
  unchanged in the server; now documented in the contract for the in-scope write
  operations and `logout`, so Schemathesis accepts the status as a positive-side
  response, and the `schemathesis.toml` config accepts it on the negative side.
- **Password under 8 code points** (incl. astral/multibyte) **→ 400** `password`
  field error (was `201`).
- **Password whose only uppercase/lowercase/digit is non-ASCII** (Greek `Ω`,
  Arabic-Indic `٣`) **→ 400** (was `201`). A valid ASCII password
  (`Password123`) → `201`, unchanged.
- **Oversized _positive_ body** is out of scope: `name` has no `maxLength`, but
  positive generation rarely exceeds 8 KiB and no positive seed has tripped it.
- **Port already served** → `fatal` before any build/boot, unchanged in intent;
  only the probe mechanism changes.

### Dependencies

- `com.fasterxml.jackson.databind.cfg.{CoercionAction, CoercionInputShape}` and
  `com.fasterxml.jackson.databind.type.LogicalType` — already on the classpath
  via the existing `ktor-serialization-jackson` dependency; no build change.
- `ed.unicoach.common.models.{EmailAddress, ValidationResult}` — already a
  `service`→`common` dependency edge.
- `api-specs/openapi.yaml` gains a `413` response on five write operations (see
  §"Contract gap"); it reuses the existing `ErrorResponse` schema component, so
  no component or `$ref` target is added.
- `String.codePointCount` (divergence 7) is JDK-standard; no new library or
  build change.
- `schemathesis.toml` (new, repo root) is read by the pinned Schemathesis 4.21.5
  venv via `--config-file`; it adds no Python dependency.
- No `flake.nix`, `gradle`, `.env*`, or `.gitignore` change.

## Tests

Acceptance is `nix develop -c bin/test-fuzz` exiting `0` across multiple seeds
with `bin/test rest-server` and `bin/test service` green. With all seven
divergences resolved this now holds deterministically — including the seeds that
intermittently failed today: an astral-password body (server defect, divergence
7) and an oversized (> 8 KiB) body on an in-scope write operation, which trips
`Undocumented HTTP status code: 413` on the positive side (divergence 6) and
`negative_data_rejection` on the negative side (divergence 7) until the `413` is
documented and configured. The contract and `schemathesis.toml` changes carry no
unit test of their own — Schemathesis validates the documented surface against
the live server. The unit/integration tests below pin each server fix so a
regression fails fast without a full fuzz run.

**`rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt`**

- _(modified)_ `test header structure verification constraints` — a `text/plain`
  POST to `/register` now asserts `400` (was `415`), and additionally asserts
  the response `Content-Type` is `application/json` and the body carries
  `code == "bad_request"`.
- _(new)_ `register with non-string name returns 400` — POST `/register` with a
  raw body `{"email":"a@b.com","password":"Password123","name":false}` asserts
  `400`, `application/json`, and `code == "bad_request"` (no user created).
- _(new)_ `register with malformed email returns 400` — POST `/register` with
  `email = "useratexample.com"` asserts `400` (not `500`) and a field error on
  `email`.
- _(new)_ `login with null body returns 400 json` — POST `/login` with raw body
  `null` and `Content-Type: application/json` asserts `400` and an
  `application/json` `ErrorResponse` body (not `415`/`text/plain`).
- _(new)_ `PUT register returns 405 with Allow` — a non-POST verb (`PUT`) on
  `/register` asserts `405` and `Allow: POST`, mirroring the existing
  `POST to me returns 405` test.

**`rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt`**

- _(new)_ `update student with non-int version returns 400` — pins the second
  half of the defect-1 fix (the disabled `ALLOW_COERCION_OF_SCALARS`). After
  `registerAndGetCookie()` and a POST that creates the student, PATCH
  `/api/v1/students/me` with a **raw** JSON body whose `version` is a JSON
  string (`{"expectedHighSchoolGraduationDate":"2028","version":"1"}`) — the
  stringy value cannot go through the typed `UpdateStudentRequest` — and assert
  `400`, `application/json`, and `code == "bad_request"`, confirming
  numeric-target strictness on `UpdateStudentRequest.version: Int`.

**`service/src/test/kotlin/ed/unicoach/auth/RegistrationValidatorTest.kt`
(new)**

- `blank email is rejected` — `RegistrationInput(email = "", …)` yields a field
  error on `email`.
- `malformed email without interior at-sign is rejected` —
  `email = "useratexample.com"` yields a field error on `email`.
- `valid email passes` — `email = "user@example.com"` with an otherwise valid
  input yields no `email` field error.
- These complement the existing password-rule coverage in `AuthServiceTest`'s
  `test validation rejection for weak passwords`.

Password code-point/ASCII cases (divergence 7), pinning the contract-matched
length and class semantics:

- `astral password under 8 code points is rejected` — `"򀣀򉶓jP3R񋧰"` (7 code
  points, 10 UTF-16 units, with ASCII `P`/`R`/`j`/`3`) yields a `password` field
  error. Pins code-point `minLength`.
- `astral password of 8 code points passes length` — the same with one extra
  ASCII letter (8 code points) yields no length field error.
- `128 code points does not trigger maxLength` — 128 code points including
  astral fillers yields no "at most 128" error (256 UTF-16 units would trip the
  prior `length > 128`).
- `129 code points triggers maxLength` — yields the "at most 128" error.
- `non-ASCII uppercase does not satisfy the uppercase rule` — `"Ωassword1"`
  (Greek `Ω` + ASCII lower + digit, no ASCII uppercase) yields the uppercase
  field error.
- `non-ASCII lowercase does not satisfy the lowercase rule` — `"PASSWORDσ1"`
  yields the lowercase field error.
- `non-ASCII digit does not satisfy the digit rule` — `"Password٣"` (Arabic `٣`)
  yields the digit field error.
- `valid ASCII password passes all rules` — `"Password123"` yields no `password`
  field error (regression guard).

No new test asserts harness internals: `bin/test-fuzz` remains a runner verified
by execution, and its conformance is the `bin/test-fuzz` exit-`0` acceptance
check.

## Implementation Plan

1. **`Serialization.kt` — strict scalar coercion.** Add imports `MapperFeature`,
   `cfg.CoercionAction`, `cfg.CoercionInputShape`, `type.LogicalType`. In the
   `jackson { }` block add `disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)`
   and the `coercionConfigFor(LogicalType.Textual)` `Fail` settings for
   `Boolean`, `Integer`, `Float`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` succeeds.

2. **`StatusPages.kt` — 415 → 400 JSON.** Replace the
   `exception<UnsupportedMediaTypeException>` block with the
   `status(HttpStatusCode.UnsupportedMediaType)` handler responding `400`
   `ErrorResponse("bad_request", …)`. Remove the now-unused
   `io.ktor.server.plugins.UnsupportedMediaTypeException` import.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` succeeds.

3. **`RegistrationValidator.kt` — email format + password code points/ASCII.**
   Add imports `EmailAddress`, `ValidationResult`; replace the `email.isBlank()`
   check with the `EmailAddress.create(input.email) is ValidationResult.Invalid`
   check (defect 3). In the same file, replace the two `password.length`
   comparisons with `password.codePointCount(0, password.length)` comparisons,
   and the three `isUpperCase`/`isLowerCase`/`isDigit` predicates with
   `in 'a'..'z'` / `in 'A'..'Z'` / `in '0'..'9'` (divergence 7).
   - Verify: `nix develop -c ./gradlew :service:compileKotlin` succeeds.

4. **`AuthRoutes.kt` — wrap `/register`.** Replace `post("/register") { … }`
   with the
   `route("/register") { post { … }; rejectUnsupportedMethods(HttpMethod.Post) }`
   block.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` succeeds.

5. **Tests.** Apply the `AuthRoutingTest` modification and additions, add the
   `StudentRoutingTest` stringy-`version` test, and create
   `RegistrationValidatorTest` per the Tests section — including the
   email-format cases and the divergence-7 code-point/ASCII password cases.
   - Verify: `nix develop -c bin/test rest-server --force` is green (in
     particular the modified and new auth and student-routing tests).
   - Verify: `nix develop -c bin/test service --force` is green.

6. **`api-specs/openapi.yaml` — document the global `413`.** Add a `413`
   `payload_too_large` `ErrorResponse` response — mirroring the conversation
   operations' `413`
   (`description: Request body exceeds the configured size
   limit`,
   `$ref '#/components/schemas/ErrorResponse'`) — to `registerUser`,
   `loginUser`, `createStudent`, `updateStudentMe`, and `logoutUser`.
   - Verify: the document still parses —
     `nix develop -c deno eval 'import {
     parse } from "jsr:@std/yaml"; parse(await
     Deno.readTextFile("api-specs/openapi.yaml"))'`
     exits `0`.
   - Verify: `grep -c "^        '413':" api-specs/openapi.yaml` returns `9`
     (four conversation + five added).

7. **`schemathesis.toml` (new, repo root).** Create the file with the
   `[checks.negative_data_rejection]` `expected-statuses` array including `413`
   (divergence 7).
   - Verify:
     `nix develop -c python3 -c "import tomllib;
     tomllib.load(open('schemathesis.toml','rb'))"`
     exits `0`.

8. **`bin/test-fuzz` — full checks, bash port guard, config wiring.** Remove
   `--exclude-checks unsupported_method`; delete the `KNOWN SERVER DEFECTS`
   comment block and update the `help()` text, exit-code table, and the
   "Excluding from this run" `log-info` to drop the `unsupported_method`
   exclusion and the non-zero-by-design framing; replace the `python3`
   `connect_ex` port guard with the `/dev/tcp` probe; add
   `--config-file "$PROJECT_ROOT/schemathesis.toml"` before `run` in the
   Schemathesis invocation, plus a `log-info` line documenting the `413`
   negative-data acceptance.
   - Verify: `nix develop -c bin/test-fuzz --help` prints usage and exits `0`.
   - Verify: `nix develop -c bin/scripts-tests` passes (no `bin/` harness
     regression).

9. **End-to-end conformance.** Run the referee.
   - Verify: `nix develop -c bin/test-fuzz` exits `0`; repeat across at least
     three invocations (distinct seeds) and confirm each exits `0` with all
     selected operations passing — including the astral-password seed (a
     schema-violating password the server now rejects, divergence 7) and seeds
     whose generated body exceeds 8 KiB, which now resolve as documented `413`s
     accepted on both the positive (`Undocumented HTTP status code: 413` gone,
     divergence 6) and negative (`negative_data_rejection`, divergence 7) sides.
   - Verify: no `rest-server` PID file remains
     (`test ! -f var/run/rest-server.pid`) and the dev `unicoach` DB is
     unwritten (the run is isolated to `unicoach-fuzz-<worktree>`), both
     regardless of exit code.

## Files Modified

- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/Serialization.kt` —
  disable scalar coercion and add `Textual` `CoercionConfig` so type-mismatched
  scalar fields are rejected (defect 1).
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt` —
  replace the dead `exception<UnsupportedMediaTypeException>` handler with a
  `status(HttpStatusCode.UnsupportedMediaType)` handler returning a `400` JSON
  `ErrorResponse`; drop the unused import (defects 2 & 4).
- `service/src/main/kotlin/ed/unicoach/auth/RegistrationValidator.kt` — validate
  email format via `EmailAddress.create` (defect 3); switch password length to
  `codePointCount` and the character-class checks to ASCII ranges to match the
  contract's `password` schema (divergence 7).
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` — wrap
  `/register` with `rejectUnsupportedMethods(HttpMethod.Post)` (defect 5).
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` — update the
  `text/plain` assertion to `400`; add name-coercion, malformed-email,
  null-body, and `/register` method-rejection tests.
- `rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt` — add a
  stringy-`version` PATCH test pinning the numeric-target coercion strictness
  (defect 1's `ALLOW_COERCION_OF_SCALARS` half).
- `service/src/test/kotlin/ed/unicoach/auth/RegistrationValidatorTest.kt` —
  _(new)_ unit tests for email-format and blank-email rejection, plus the
  code-point-length and ASCII-class password cases (divergence 7).
- `api-specs/openapi.yaml` — document the global 8 KiB `413` `payload_too_large`
  response on the five body-bearing non-conversation write operations
  (`registerUser`, `loginUser`, `createStudent`, `updateStudentMe`,
  `logoutUser`), mirroring the conversation operations; the server is already
  conformant (contract gap, not a server defect).
- `schemathesis.toml` — _(new, repo root)_ declare `413` a valid
  `negative_data_rejection` status (divergence 7).
- `bin/test-fuzz` — drop the `unsupported_method` exclusion and the
  `KNOWN SERVER DEFECTS` documentation; replace the `python3` port guard with a
  bash `/dev/tcp` probe; pass `--config-file "$PROJECT_ROOT/schemathesis.toml"`
  before `run` and add a `log-info` line documenting the `413` negative-data
  acceptance (divergence 7).
