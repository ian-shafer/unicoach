# RFC 269 — The words: say "deleted", say what is kept

**Slice:** `deletion/05/the-words` (brief 0002, _account erasure_). **Status:**
proposed. **Base:** `main@aee71ebe`. **Lane:** rfc.

## Executive Summary

RFCs 263–267 built erasure: Settings → Delete account closes the account at
once, a job erases it seven days later, and one email confirms it. The words a
person reads still describe the RFC 252 closure — "your account is closed" — and
say nothing about erasure, its window, or what is kept. The email's copy was
left interim (RFC 267 §6). `/privacy` still says we collect "essay content", and
carries no notice to minors.

This RFC changes copy only, on four surfaces, and adds no table, column, route
or job:

1. the iOS delete confirmation (both roles) says the account is deleted now,
   everything is erased within 30 days, what we keep, the AI provider's
   retention, and whether a confirmation email will come;
2. the login screen's line after a delete says "deleted", not "closed";
3. the `ACCOUNT_ERASED` email says erasure is complete and names what is kept in
   the same words, with no link;
4. `/privacy` loses "essay content", says what is kept and for how long, gains
   the AI-provider sentence and the California minors notice (Cal. B&P 22581),
   and points at the same door.

The shape of the confirmation does not change: one inline card, Cancel beside
Delete, the subscription notice informing and never blocking, no reason asked.

## Detailed Design

### The kept list, one home per language

D5 fixes three kept things. The same sentence appears on the confirmation, in
the email and on `/privacy`:

> What we keep: a record that your account existed and when it was deleted; your
> subscription record with Apple, if you have one; and our usage records — how
> much you used the coach — with your words removed.

The AI-provider sentence, also used verbatim on all three:

> Our AI provider keeps conversation text for up to 30 days with no account
> attached, then deletes it.

"Up to 30 days" holds for the configured model (`claude-sonnet-4-6`,
`service/src/main/resources/service.conf`): it is not one of Anthropic's Covered
Models, whose 30 days is a floor (research report §7).

In Kotlin both sentences live once, in `object ErasureDisclosure`
(`ed.unicoach.account`, `:service`): `KEPT`, `AI_PROVIDER_RETENTION`, and the
erasure window `ERASURE_WINDOW_DAYS` (30). The email renderer and `PrivacyPage`
read them (`:public-web` already depends on `:service`). Swift cannot share the
strings, so `DeleteAccountControl` holds its own copy, its "within 30 days"
included, and `ErasureDisclosure`'s KDoc names it. Each test keeps its literal,
so the exact words stay pinned.

Two settings the copy depends on say so where they are set
(`service/src/main/resources/service.conf`): the `coaching` model's comment
records that "up to 30 days" is that model's provider term (the `extraction`,
`synthesis` and `fitLens` models point at it); and `accountErasure.graceDays`
(7) fails boot above `ERASURE_WINDOW_DAYS` (`AccountErasureConfig`), since the
window is a promise and the setting must stay inside it.

### 1. iOS delete confirmation (`SettingsView.swift`, `DeleteAccountControl`)

Header unchanged: **"Delete your account?"** Body, replacing the one RFC 252
paragraph (`deleteAccountPrompt`), in order:

> Your account is deleted now. You are signed out on every device, and anyone
> connected to you is told the connection has ended — not why. Their own
> accounts are not affected.
>
> Everything in your account is erased within 30 days. **[verified]** We'll
> email you when it's done. **[unverified]** Your email address isn't verified,
> so we can't email you when it's done.
>
> What we keep: _(the kept list)_
>
> Our AI provider keeps conversation text for up to 30 days with no account
> attached, then deletes it.

The email sentence follows `PublicUser.emailVerified`, which `SettingsView`
already holds: RFC 267 sends the confirmation to a verified address only (its
D-A), so promising one to an unverified address would be false. The control
gains one `emailVerified: Bool` input; nothing else about its state changes.

The subscription notice, failure line, Delete and Cancel buttons are unchanged
(RFC 252), so the card stays one screen with Cancel as easy as Delete.

Apple's support article, beside the place each sentence lives:

| Apple says                                                                                                                    | Where it lives                                                                                         |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| "Offer to delete the entire account record, along with associated personal data."                                             | the Delete account button; "Everything in your account is erased within 30 days."                      |
| "If you're legally required to retain some user information, let people know what information will and won't be retained."    | "What we keep: …" on the confirmation, in the email, on `/privacy`                                     |
| "Inform the person how long it will take to delete the account and provide a confirmation when the deletion is complete."     | "erased within 30 days" + "We'll email you when it's done." on the confirmation; the email itself      |
| "If a person has an auto-renewable subscription, notify them that billing will continue through Apple and ask them to cancel" | RFC 252's subscription notice and Manage subscription control, unchanged, informing and never blocking |
| HIG: "Tell people when account deletion will complete, and notify them when it's finished."                                   | as the third row                                                                                       |

### 2. Login screen after a delete (`LoginView.swift`, `accountClosedNotice`)

"Your account has been closed." becomes **"Your account has been deleted."** The
Apple-ID line beneath it (TN3194, RFC 252) is unchanged.

### 3. The email (`AccountErasedEmailRenderer.kt`)

Subject unchanged: **"Your unicoach account has been deleted"**. Body:

> Your unicoach account has been deleted, as you asked, and everything in it has
> been erased.
>
> What we keep: _(the kept list)_
>
> Our AI provider keeps conversation text for up to 30 days with no account
> attached, then deletes it.
>
> If you subscribed through Apple, the subscription continues until you cancel
> it in your Apple ID settings.
>
> — unicoach

No link: there is no account to link to. The context stays empty; the KDoc's
"interim" note goes.

### 4. `/privacy` (`PrivacyPage.kt`)

"Information we collect" — "essay content" goes; the coach covers the whole path
to college (`PRODUCT.md`):

> We collect the account information you provide — such as your name and email
> address — and what you share with your coach: your conversations, the details
> you add to your profile, and the colleges on your list.

"How we use information" keeps both sentences and gains the AI-provider
sentence.

"Your choices" becomes:

> You can delete your account in the app: Settings › Delete account. Your
> account is deleted at once, everything in it is erased within 30 days, and we
> email your verified address when it is done. What we keep: _(the kept list)_
> You may request access to your account information by contacting us.

The vague "Some information may be retained as required to operate the service…"
sentence is replaced by the kept list, which is the honest version of it. The
`DeleteAccountControl` cross-reference comment stays.

A new paragraph under "Your choices", the Cal. B&P 22581 notice:

> If you are under 18, you can remove anything you have posted to unicoach by
> deleting your account in the app (Settings › Delete account) or by contacting
> us. Removal does not ensure complete or comprehensive removal: a parent you
> connected keeps their own conversations, and the records listed above are
> kept.

The statute requires the notice and the "does not ensure complete or
comprehensive removal" caveat; the clause after the colon names the two real
reasons (RFC 266 D-A: a row keyed on another account is never erased).

No export, no "download my data" (D5).

### Reachability

Settings → Delete account in the iOS app, both roles (student and parent); the
login screen right after a delete; the `ACCOUNT_ERASED` email, seven days after
(RFC 267); `/privacy` on public-web, linked from every page's footer.

### Schema

None.

## Tests

- `AccountErasedEmailRendererTest` — the rendered subject and body equal the
  copy in §3 exactly; the email contains no `http`; the empty-context refusal
  test stays.
- `AccountErasureConfigTest` — `graceDays` 30 is accepted; 31 is refused with a
  message naming the key, the value, the limit and the copy it protects.
- `LegalPagesTest` — `/privacy` renders the kept list, the AI-provider sentence,
  the minors notice (including "does not ensure complete or comprehensive
  removal") and "Settings › Delete account", and does not contain "essay".
- iOS: the app builds under `xcodebuild`, and the existing snapshot catalogue
  scenes for the open delete confirmation (student and parent) render with the
  new copy; one new scene photographs the confirmation for an unverified user.
  The four paragraphs make Settings taller than the harness's canvas ceiling
  (`SnapshotScene.maxHeight`), so the delete-confirmation scenes lay the screen
  out taller and keep its foot (`SnapshotSeed.foot`): the whole card, Delete and
  Cancel included, at the cost of the screen's top. iOS runs outside the Gradle
  hook, so the implementer reports the `xcodebuild` result.

## Implementation Plan

1. `AccountErasedEmailRenderer.kt` + its test: new body, KDoc. `bin/test`.
2. `PrivacyPage.kt` + `LegalPagesTest`: new paragraphs. `bin/test`.
3. `SettingsView.swift`: `DeleteAccountControl` takes `emailVerified` from
   `user.emailVerified`; the prompt becomes the four paragraphs of §1.
   `LoginView.swift`: "closed" → "deleted". Snapshot scene for the unverified
   confirmation. Build and run the snapshot tests with `xcodebuild`.
4. Full `nix develop -c bin/test`.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/account/ErasureDisclosure.kt` (new)
- `service/src/main/kotlin/ed/unicoach/account/AccountErasureConfig.kt` +
  `AccountErasureConfigTest.kt`
- `service/src/main/resources/service.conf` (comments)
- `service/src/main/kotlin/ed/unicoach/account/AccountErasedEmailRenderer.kt`
- `service/src/test/kotlin/ed/unicoach/account/AccountErasedEmailRendererTest.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/PrivacyPage.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/LegalPagesTest.kt`
- `ios-app/UnicoachiOS/SettingsView.swift`
- `ios-app/UnicoachiOS/LoginView.swift`
- `ios-app/UnicoachiOSTests/SnapshotScenes.swift`
