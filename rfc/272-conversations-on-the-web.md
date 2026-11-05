# RFC 272 — Conversations on the web

**Slice:** `web/09/conversations` (brief 0013, _Unicoach on the web_).
**Status:** approved (Ian, 2026-11-04: "Approve all"). **Base:**
`main@48018319`. **Lane:** rfc.

## Executive Summary

A signed-in student gets "Your conversations" at `/conversations`, reached from
"Conversations" in the signed-in header. It lists their conversations, most
recently used first: each row is the conversation's name, which opens it in the
chat at `/chat/{id}`, the date it was last used, and two buttons, "Archive" and
"Delete". There is no swipe gesture on a desktop, so both are always visible.

Archive happens at once, as on the iPhone. Delete opens a confirmation page that
says what is lost and what is not, and deletes only when confirmed. Both land
back on the list with one line saying what happened.

Every call is in-process (brief 0013 D13), through the same `CoachingService`
methods the REST routes behind the iPhone app call: `listConvos`,
`updateConvo(archived = true)`, `getConvo` and `deleteConvo`. The web adds no
rule of its own. Nothing here holds typing, so there is no session draft and no
schema change of either kind (no DDL, no `SessionMetadata` change).

## Detailed Design

### 1. What the code says, where it differs from the slice

- **The door the slice names does not exist on the web.** The slice says "the
  slide-over menu's conversation list, in the shell". The web shell has no
  slide-over menu: `web/01` chose a wrapping header over a hamburger (Ian,
  2026-09-15), and every later slice put its door in the signed-in header. This
  RFC does the same (decision D-A).
- **iOS archives and deletes by swipe, or by long-press menu.** Neither exists
  on a desktop. The web shows both as buttons on every row.
- **iOS has no way back from Archive.** `ConversationListView` lists only
  unarchived conversations and nothing lists archived ones. An archived
  conversation still opens by its URL. The web keeps that parity (D-B).
- **iOS's delete dialog says only "This can't be undone."** The slice asks for
  more: "says what is lost". Deleting is a soft delete (`ConvosDao.delete` sets
  `deleted_at`): the student can never open the conversation again, here or in
  the iPhone app, and the row is erased with the account. What the coach already
  learned from it is NOT deleted: observations, claims, commitments and
  college-list quotes have no join to `convos.deleted_at`, so they stay and keep
  informing the coach. The page says both (D-C).
- **A supporter has no student conversations.** The web chat already sends a
  supporter to `/account` (RFC 262 `ChatStudentGate`). These pages do the same
  through `getStudent` / `postStudent` (RFC 271).

### 2. Routes

`mapPathToAccess` gains `/conversations` and `/conversations/…` as `ONBOARDED`,
beside `/account`, `/chat` and `/colleges`. Every route is registered with
`getStudent` / `postStudent`, so a supporter is sent to `/account` and an
account with no role to onboarding, with nothing read or written.

| Method + path                      | Does                                                            |
| ---------------------------------- | --------------------------------------------------------------- |
| `GET /conversations`               | the list (`ArchiveScope.UNARCHIVED`), or the empty state        |
| `POST /conversations/{id}/archive` | `updateConvo(ConvoUpdate(archived = true))`; 303 to the list    |
| `GET /conversations/{id}/delete`   | "Delete this conversation?" for a conversation the student owns |
| `POST /conversations/{id}/delete`  | `deleteConvo`; 303 to the list                                  |

- A path id that is not a canonical UUID, or that names no conversation of the
  student's (missing, foreign, already deleted), is the branded 404 — never a
  hint whose it was. `GET …/delete` reads the conversation through `getConvo`; a
  POST's `NotFound` outcome is the same 404.
- Every POST is a signed-in POST: the same-origin guard and the CSRF token in
  `handleAccess` (RFC 265) apply as on every other form. The archive form and
  the delete form each carry only the token.
- After a POST the list says what happened with a receipt, read from the query
  string as a KEY from a closed set (the RFC 271 `ListReceipt` pattern):
  `?archived=<id>` or `?deleted=1`. The page renders the archive receipt only
  for an id that is a canonical UUID and names one of the student's own
  conversations that is archived — so a hand-made link cannot say "Archived" of
  a conversation still on the list; section 4 says where its name comes from.

### 3. Who decides what

The list order is `listByStudentWithActivity`'s: most recent activity first, the
order the iPhone app shows. The date shown is `lastActivityAt` (falling back to
`updatedAt`, as iOS does), as a calendar date in `COACH_ZONE` — the zone the
coach already uses for "today" — formatted by `UsDate.of(LocalDate)` ("Nov 3,
2026"). The web does not show iOS's relative "yesterday": a server cannot know
the reader's clock, and the absolute date is never wrong.

### 4. The pages

**Your conversations** (`/conversations`), copy from iOS where it exists:

- Heading "Your conversations"; a "New conversation" link to `/chat`.
- One row per conversation, as a list of cards at every width (each row is one
  line of text and two buttons, so it never needs a table): the name as a link
  to `/chat/{id}`, the date beneath it, then a form with an "Archive" button and
  a "Delete" link to the confirmation page. Every control is at least 44px (the
  shell's tap-target mechanism, RFC 210).
- Empty: "No conversations yet", "Start a conversation to get coaching.", and a
  "Start a conversation" link to `/chat` (iOS verbatim).
- Receipts, **new**: "Archived “{name}”." and "Conversation deleted."

The receipt's name: the handler redirects with the id only, and the list page
cannot read the name of a conversation it no longer lists. So the POST handler
redirects to `/conversations?archived=<id>` and the list page renders the
receipt with the name from `getConvo`, which still finds an archived
conversation. A deleted one is gone from every read, so the delete receipt is
"Conversation deleted." with no name.

**Delete this conversation?** (`/conversations/{id}/delete`):

- Heading "Delete this conversation?" (iOS verbatim), then the name.
- Body, **new** (D-C): "You won't be able to open it or read it again, here or
  in the iPhone app. This can't be undone." and "What your coach has already
  learned from it is kept. Your college list and Your details don't change."
- A form whose "Delete" button posts the delete, and a "Cancel" link back to the
  list.

No TypeScript: every control is a link or a server-rendered form.

## Files Modified

Expected scope, not a manifest:

- `public-web/src/main/kotlin/ed/unicoach/web/conversations/ConversationRoutes.kt`
  — new: the four routes.
- `public-web/src/main/kotlin/ed/unicoach/web/render/ConversationsPage.kt` —
  new: the list page and the confirmation page.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — the header's
  "Conversations" entry after "Chat".
- `public-web/src/main/kotlin/ed/unicoach/web/auth/WebAuthGate.kt` (and the path
  constant's home) — `/conversations` is `ONBOARDED`.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` — registration.
- `public-web/src/main/resources/static/site.css` — the row layout, if the
  existing card styles do not cover it.
- `public-web/src/test/kotlin/ed/unicoach/web/ConversationsRoutingTest.kt` —
  new.
- `public-web/src/test/kotlin/ed/unicoach/web/ShellNavTest.kt` — the new header
  entry.
- `public-web/src/main/kotlin/ed/unicoach/web/render/ChatPage.kt` and
  `chat/ChatRoutes.kt` — `conversationPath` and the path's id parameter move to
  the chat page's render file, so the list links to a conversation without
  restating `/chat/{id}`.
- `GatedPathSpellingTest`, `CspConformanceTest` and `ShellMechanismTest` — the
  new paths, pages and classes join their existing walks.

## Implementation Plan

1. Add the path constant and the `ONBOARDED` mapping; add the header entry.
   Verify: `ShellNavTest` and `GatedPathSpellingTest` updated and green.
2. Add the list route and page (rows, empty state, receipts). Verify: the
   routing tests for the list.
3. Add the archive POST. Verify: its tests.
4. Add the delete confirmation page and its POST. Verify: their tests.
5. CSS for the row, if needed, using the shell's existing tokens and tap-target
   mechanism; no fixed pixel widths.
6. `nix develop -c bin/test` unscoped, then the D19 device matrix on the two new
   pages and the first-session test in a real browser.

## Tests

`ConversationsRoutingTest`, against the real database (the `ChatRoutingTest` and
`CollegeListRoutingTest` harness):

- The list shows the student's unarchived conversations, most recent activity
  first, each linking to `/chat/{id}` and showing its date; it omits archived
  ones, deleted ones and another student's.
- The empty state, with its link to `/chat`.
- Archive: the conversation is archived (read back through `CoachingService`),
  the response is a 303 to the list, and the list shows "Archived “{name}”." and
  no longer lists it.
- Delete confirmation: shows the name and the D-C copy, with a form posting to
  the same path.
- Delete: the conversation is soft-deleted (no longer found by `getConvo`), the
  response is a 303 to the list, and the list says "Conversation deleted.".
- Another student's conversation, an unknown id and a non-UUID id are the
  branded 404 on all three id routes, and nothing is changed.
- A supporter is sent to `/account` from the list and from each POST, with
  nothing changed.
- A signed-out caller is sent to sign-in; a POST without the CSRF token is
  refused and changes nothing.
- A receipt id that is not a canonical UUID renders no receipt.
- The header carries "Conversations" for a signed-in caller (`ShellNavTest`).

Per D9 the tests pin mechanisms only. The D19 device matrix is run by hand on
both pages: 375x667, 412x915, 820x1180, 1180x820, 1440x900 and 1280x720 — no
sideways scroll, 44px tap targets.

## Decisions

**D-A — the door.** "Conversations" in the signed-in header, after "Chat". The
slice's slide-over menu does not exist on the web (section 1).

**D-B — no archived list.** As on the iPhone, archived conversations are not
listed anywhere and cannot be unarchived from the web.

**D-C — the delete page's words.** As in section 4. It says the conversation
cannot be opened again and that what the coach learned is kept, and does NOT say
"erased": the row is kept, unreadable, until the account is deleted.
