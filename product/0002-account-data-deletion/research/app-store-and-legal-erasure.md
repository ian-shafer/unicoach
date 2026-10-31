# App Store review and legal requirements for account deletion and data erasure

Research report for brief 0002 (account data deletion). Compiled 2026-10-30.
Scope: what an iOS-only app with account creation, Sign in with Apple, Google
sign-in, Apple in-app subscriptions and an LLM coach must do about ACCOUNT
DELETION and DATA ERASURE to pass App Store review and to meet GDPR/CCPA. The
app already offers in-app account CLOSURE (soft delete; access ends at once;
sign-in identities released) but keeps the rows; this report informs the erasure
design.

This is research, not legal advice. Every load-bearing claim carries its source
URL and the date of the source where the page shows one; "fetched 2026-10-30"
means the page shows no date of its own.

## Method

Web searches (standard and extended mode) and direct page fetches, 2026-10-30.
Primary sources preferred: Apple's guideline text, support article, technote and
REST docs; the GDPR articles; ICO guidance; the California statutes and CPPA
regulations; the IRS; Anthropic's and OpenAI's own policy pages. Forum posts and
vendor blogs are used only for section 9 and are labelled anecdotal.

Pages fetched successfully (primary):

- Apple App Store Review Guidelines (5.1.1) —
  https://developer.apple.com/app-store/review/guidelines/
- Apple, "5.1.1(v) - Offering account deletion in your app" —
  https://developer.apple.com/support/offering-account-deletion-in-your-app/
- Apple Developer News, "Account deletion requirement starts June 30"
  (2022-05-24) — https://developer.apple.com/news/?id=12m75xbj
- Apple HIG, "Managing accounts" (Deleting accounts section) —
  https://developer.apple.com/design/human-interface-guidelines/managing-accounts
- Apple TN3194 (first published 2025-10-03) —
  https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple
- Apple REST API, "Token revocation" —
  https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens
- Apple Support, "Manage your apps with Sign in with Apple" (2026-09-14) —
  https://support.apple.com/en-us/102571
- Apple App Store Server API, `originalTransactionId` —
  https://developer.apple.com/documentation/appstoreserverapi/originaltransactionid
- Apple Developer Forums threads 708415 (DTS, June 2022), 706792, 707364,
  765160, 775209, 761486, 709985, 776865
- Google OAuth 2.0 for web server apps, "Token revocation" —
  https://developers.google.com/identity/protocols/oauth2/web-server
- Google Sign-In for iOS, "Disconnecting" (updated 2025-05-19) —
  https://developers.google.com/identity/sign-in/ios/disconnect
- Google API Services User Data Policy (updated 2024-02-15) —
  https://developers.google.com/terms/api-services-user-data-policy
- Google Play account deletion policy —
  https://support.google.com/googleplay/android-developer/answer/13327111
- GDPR Articles 8, 12, 17, 19, 28 via https://gdpr-info.eu/
- ICO, "Right to erasure" —
  https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/individual-rights/individual-rights/right-to-erasure/
- ICO, "How does the right to erasure apply to children?" —
  https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/childrens-information/children-and-the-uk-gdpr/how-does-the-right-to-erasure-apply-to-children/
- ICO anonymisation guidance, "Pseudonymisation" chapter —
  https://ico.org.uk/for-organisations/uk-gdpr-guidance-and-resources/data-sharing/anonymisation/pseudonymisation/
- IRS, "How long should I keep records?" (reviewed 2026-06-30) —
  https://www.irs.gov/businesses/small-businesses-self-employed/how-long-should-i-keep-records
- Cal. Civ. Code 1798.105 and 1798.130, Cal. Bus. & Prof. Code 22581 — the
  official leginfo.legislature.ca.gov pages returned HTTP 403 to the fetcher;
  text taken from FindLaw's mirror marked "Current as of January 01, 2026":
  https://codes.findlaw.com/ca/civil-code/civ-sect-1798-105/ ,
  https://codes.findlaw.com/ca/civil-code/civ-sect-1798-130/ ,
  https://codes.findlaw.com/ca/business-and-professions-code/bpc-sect-22581/
- 11 CCR 7022 (CPPA regulations, operative 2023-03-29) —
  https://www.law.cornell.edu/regulations/california/11-CCR-7022
- FTC, "Complying with COPPA: FAQs" —
  https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions
- Anthropic Privacy Center, "How long do you store my organization's data?"
  (updated 2026-07-01) —
  https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-personal-data
- Anthropic, "API and data retention" (fetched 2026-10-30) —
  https://platform.claude.com/docs/en/manage-claude/api-and-data-retention
- Anthropic, "Covered Models" support article (updated 2026-09-01) —
  https://support.claude.com/en/articles/15425695
- OpenAI, "Your data" (fetched 2026-10-30) —
  https://developers.openai.com/api/docs/guides/your-data
- DEV Community, "Why apps get rejected: 285 public App Review cases analysed"
  (2026-10-27) —
  https://dev.to/tychelabs/why-apps-get-rejected-285-public-app-review-cases-analysed-4h1o
- Apple Community thread 254032717 (July 2022, fintech rejection)

Unreachable or only partly reachable:

- leginfo.legislature.ca.gov and law.justia.com: HTTP 403 (FindLaw/Cornell
  mirrors used instead; verify against the official text before citing in a
  legal document).
- openai.com/enterprise-privacy and
  openai.com/index/response-to-nyt-data-demands: HTTP 403. The court-order
  discussion in section 7 therefore rests on press and law-firm reports of
  OpenAI's statements, not on OpenAI's page.
- ICO anonymisation guidance main page: body is rendered client-side and came
  back as headings only; the "Pseudonymisation" chapter page did load. The
  publication date (March/April 2025) comes from law-firm summaries.
- WP29 Opinion 05/2014 on anonymisation (wp216): the PDF fetched as binary and
  could not be read; cited by reference only.
- CNIL: no English-language anonymisation page found; French-only.
- EDPB Guidelines 01/2025 on pseudonymisation: only the public-consultation
  listing (17 Jan – 14 Mar 2025) was reachable; whether a final version has been
  adopted could not be confirmed.
- Apple forum thread 776865 (March 2025) has zero replies; no Apple answer.
- No r/iOSProgramming threads about 5.1.1(v) from 2025–2026 surfaced.

Local check (not web): the unicoach backend validates Apple and Google identity
tokens by issuer + JWKS only (`service/src/main/resources/service.conf` lines
746–762); a grep of the Kotlin and Swift sources finds no `/auth/token`
exchange, no stored refresh token, and no `/auth/revoke` call. This bears
directly on section 2.

## 1. App Store Review Guideline 5.1.1(v)

### Current guideline text (verbatim)

From https://developer.apple.com/app-store/review/guidelines/ (fetched
2026-10-30; the page shows no "last updated" date):

> If your app doesn't include significant account-based features, let people use
> it without a login. If your app supports account creation, you must also offer
> account deletion within the app. Apps may not require users to enter personal
> information to function, except when directly relevant to the core
> functionality of the app or required by law. If your core app functionality is
> not related to a specific social network (such as Facebook, WeChat, Weibo, X,
> etc.), you must provide access without a login or via another mechanism.
> Pulling basic profile information, sharing to the social network, or inviting
> friends to use the app are not considered core app functionality. The app must
> also include a mechanism to revoke social network credentials and disable data
> access between the app and social network from within the app. An app may not
> store credentials or tokens to social networks off of the device and may only
> use such credentials or tokens to directly connect to the social network from
> the app itself while the app is in use.

The guideline itself is one sentence on deletion. All the detail (deactivation,
retention, timing, support flows, subscriptions) lives in the linked support
article "5.1.1(v) - Offering account deletion in your app",
https://developer.apple.com/support/offering-account-deletion-in-your-app/
(fetched 2026-10-30; page shows only "Copyright © 2026 Apple Inc."). Its opening
text:

> Apps submitted to the App Store that support account creation must also let
> people initiate deletion of their account within the app.
>
> Deleting an account removes the account from the developer's records, along
> with any data associated with the account that the developer isn't legally
> required to maintain.

Note the verb: users must be able to **initiate** deletion in the app. The
guideline does not say deletion must complete in the app or at once.

### (a) Deactivation vs deletion

Support article, "Account deletion guidance":

> Offer to delete the entire account record, along with associated personal
> data. You may include additional options, but only offering to temporarily
> deactivate or disable an account is insufficient.

The HIG says the same: "If you help people create an account within your app or
game, you must also help them delete it, not just deactivate it."
(https://developer.apple.com/design/human-interface-guidelines/managing-accounts,
fetched 2026-10-30.)

Consequence for unicoach: the existing "closure" (soft delete, rows kept) is
exactly what Apple calls deactivation. It may stay as an _additional_ option,
but it does not satisfy 5.1.1(v) on its own.

### (b) Legally required retention

Support article:

> Keep people informed. [...] If you're legally required to retain some user
> information, let people know what information will and won't be retained.

> Note: Follow applicable legal requirements for storing and retaining user
> account information and for handling account deletion. This includes complying
> with local laws where your app is available.

and in the FAQ on user-generated content: "If local laws or regulations require
you to maintain some data, let people know."

HIG: "If legal requirements compel your app to maintain accounts or information
— such as digital health records — or to follow a specific account-deletion
process, clearly describe the situation so people can understand the information
or accounts you must maintain and the process you must follow."

So Apple accepts legally-required retention, and the condition is disclosure:
tell the user, in the deletion flow, what is and is not retained.

### (c) Deletion may take time

Support article, FAQ:

> **I manually delete accounts and this takes time. Does account deletion need
> to be immediate and automatic?**
>
> No. If your process for account deletion is manual or otherwise takes a
> reasonable amount of time to complete, this is acceptable. Inform the person
> how long it will take to delete the account and provide a confirmation when
> the deletion is complete. Ensure the time it takes to delete accounts complies
> with local laws where your app is available.

and in the guidance list: "If the deletion request will take additional time to
complete, let them know."

HIG: "Tell people when account deletion will complete, and notify them when it's
finished. Because it can sometimes take a while to fully delete an account, it's
essential to keep people informed about the status of the deletion process so
they know what to expect."

So a delayed purge is acceptable if the user is told the duration up front and
gets a confirmation when it completes. Apple does not name a maximum; "complies
with local laws" points at the GDPR one-month and CCPA 45-day windows (sections
4 and 6).

### (d) Website / support flows

Support article:

> If people need to visit a website to finish deleting their account, include a
> link directly to the page on your website where they can complete the process.

> **Can I direct people to a customer service flow to complete account
> deletion?**
>
> It depends. Apps in highly regulated industries, as described in App Review
> Guideline 5.1.1(ix), may use additional customer service flows to confirm and
> facilitate the account deletion process. Apps not operating in highly
> regulated industries should not require people to make a phone call, send an
> email, use a chat support flow, or go through other support flows.

> **Can I require reauthentication or add confirmation steps [...]?**
>
> Yes. [...] However, apps that make it unnecessarily difficult for someone to
> delete their account will not pass review.

Guideline 5.1.1(ix) defines "highly regulated" as "banking and financial
services, healthcare, gambling, legal cannabis use, air travel and crypto
exchanges". A college-coaching app is not in that list, so "email support to
delete" is out. A direct deep link to a web page is permitted but, per the HIG,
"Make the link easy to discover — for example, don't bury it in your Privacy
Policy or Terms of Service pages" and keep the web flow no longer than the
in-app one. The HIG also forbids linking out to Safari for sign-in; forum
anecdotes (section 9) say reviewers have objected to Safari link-outs for
deletion too, so an in-app flow is the safe choice.

### (e) Subscriptions

Support article, FAQ:

> **How do I handle people with auto-renewable subscriptions? I don't want to
> accidentally charge someone after they've deleted their account.**
>
> If a person has an auto-renewable subscription, notify them that billing will
> continue through Apple and ask them to cancel their subscription before
> continuing. If you use App Store Server Notifications for auto-renewable
> subscriptions, you can verify the status of the person's subscription in real
> time, or use the Subscription Status API to identify subscription status.
>
> Use showManageSubscriptions in iOS 15 and iPadOS 15, or later, or provide this
> link to let people manage their subscriptions:
> https://apps.apple.com/account/subscriptions/. [...]
>
> You can also use beginRefundRequest in iOS 15 and iPadOS 15, or later, or
> provide this Apple Support link to let customers submit refund requests:
> https://support.apple.com/118223.
>
> Additionally, you can offer to schedule account deletion for a later date,
> aligned with the subscription's expiration, as long as there's also an option
> to delete the account immediately.

HIG: "Billing for an auto-renewable subscription continues through Apple until
people cancel the subscription, regardless of whether they delete their
account." and "Note: Even if people didn't use your app to purchase the
subscription, you still need to support account deletion."

Reading: Apple asks you to _warn and point to cancellation_; it does not say an
active subscription may block deletion, and the "immediate deletion must also be
offered" rule for scheduled deletion implies it may not. The honest caveat: the
text says "ask them to cancel their subscription before continuing", which some
developers read as permitting a blocking step. The safer reading, consistent
with "apps that make it unnecessarily difficult [...] will not pass review", is
to warn, show `showManageSubscriptions`, and still allow deletion.

## 2. Sign in with Apple token revocation

### The requirement and its sources

Apple Developer News, "Account deletion requirement starts June 30" (2022-05-24,
https://developer.apple.com/news/?id=12m75xbj): if an app offers Sign in with
Apple, developers must use the Sign in with Apple REST API to revoke user tokens
when an account is deleted. The same sentence was in the App Review reminder
email quoted by a developer in forum thread 706792 (June 2022): "If your app
offers Sign in with Apple, use the Sign in with Apple REST API to revoke user
tokens."

Support article, FAQ:

> **My app uses Sign in with Apple to provide account creation and
> authentication. What changes are necessary to support people who delete their
> accounts?**
>
> Apps that support Sign in with Apple should use the Sign in with Apple REST
> API to revoke user tokens. Use server-to-server notification endpoints to
> process changes for Sign in with Apple accounts.

HIG developer note: "If people used Sign in with Apple to create an account
within your app, you revoke the associated tokens when they delete their
account."

TN3194 (first published 2025-10-03,
https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple):

> Apps submitted to the App Store that support account creation must also let
> users initiate deletion of their account within the app, as of June 30, 2022.

> The Token revocation endpoint (`/auth/revoke`) is the only way to
> programmatically invalidate user tokens associated to your developer account
> without user interaction. [...] Because this endpoint manages your user
> sessions, either a valid refresh token or access token for invalidation is
> required.

Note the wording drift: the 2022 news item says "must"; the support article and
TN3194 say "should"/"are expected". The guideline text itself (5.1.1(v)) does
not mention revocation at all.

### What the revoke call needs

From
https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens
(fetched 2026-10-30):

> In order to revoke authorization for a user, you must obtain a valid refresh
> token or access token. If you don't have either token for the user, you can
> generate tokens when validating an authorization code.

```
POST https://appleid.apple.com/auth/revoke
content-type: application/x-www-form-urlencoded
client_id=CLIENT_ID&client_secret=CLIENT_SECRET&token=REFRESH_TOKEN&token_type_hint=refresh_token
```

> For either token revocation request, the `revoke` endpoint returns a `200`
> response code without a response body after the server invalidates the `token`
> value, or if the `token` value was previously invalidated.

`client_secret` is a JWT signed (ES256) with the Sign in with Apple private key
(Apple's "Creating a client secret" doc, linked from TN3194). The refresh token
comes from exchanging the one-time authorization code at `/auth/token`
(`grant_type=authorization_code`). TN3194's recommended flow for every new
account: send identity token AND authorization code to the server, validate the
code at `/auth/token`, store the refresh token, and validate it "up to once per
day" to keep it usable "for future token revocation requests".

### If the server only ever validated the identity token

Answer: no programmatic revocation is possible for that user, because the server
holds nothing the revoke endpoint accepts. TN3194:

> If you don't have the user's refresh token, access token, or authorization
> code, you must still fulfill the user's account deletion request and meet the
> account deletion requirement. To manually revoke the user credentials, follow
> the steps below:
>
> 1. Delete the user's account data from your systems.
> 2. Direct the user to manually revoke access for your client.
> 3. Respond to the credential revoked notification to revert the client to an
>    unauthenticated state

and the consequence of not doing so:

> Important: If the manual token revocation isn't completed, the next time the
> user authenticates with your client using Sign in with Apple, they won't be
> presented with the initial authorization flow to enter their full name, email
> address, or both. This is because the user credential state managed by Sign in
> with Apple remains unchanged and returns
> `ASAuthorizationAppleIDProvider.CredentialState.authorized` [...]

This is the same text Apple DTS posted in forum thread 708415 (June 2022,
https://developer.apple.com/forums/thread/708415), which DTS pointed other
threads (706792, 707364) at as the official answer.

The manual route (https://support.apple.com/en-us/102571, published 2026-09-14):
on iPhone, Settings > [your name] > Sign in with Apple > select the app or
developer > Delete; on the web, account.apple.com > Sign-In & Security > Sign in
with Apple. (The "Sign-In & Security" label is the web path, not the iOS path.)
The same page notes: "If you delete your account in the app, you may get an
email that the app revoked your Sign in with Apple account" — i.e. a successful
`/auth/revoke` triggers an Apple email to the user, confirmed by Auth0's
community thread as expected behaviour (listed in search results; the page
itself returned 404 on fetch).

A third route — ask the user to re-authenticate with Sign in with Apple inside
the deletion flow, which yields a fresh authorization code, exchange it at
`/auth/token`, then revoke — follows from the REST doc's "you can generate
tokens when validating an authorization code". It is consistent with Apple's
docs but Apple's DTS post does not name it as the workaround; it is an
inference, not a quoted recommendation.

Unicoach today: the backend validates identity tokens against
`https://appleid.apple.com/auth/keys` and never exchanges the code (local grep,
see Method), so it holds no refresh token for any existing user. For those users
only the manual (Settings) route or the re-authenticate route is available; for
future users the sign-in path would have to start exchanging the code and
storing the refresh token.

### Does App Review enforce the revoke call?

Public evidence is thin and all anecdotal. The reviewer rejection texts quoted
in forum threads 765160 (Oct 2024) and 775209 (Feb 2025) list only three bullets
(deactivation insufficient; web-link rule; support flows only for regulated
industries) — none mentions token revocation. A reviewer has no way to observe a
server-side `/auth/revoke` call except indirectly (the Apple email, or
re-sign-in showing the first-run name/email sheet again). The March 2025 thread
776865 asking "must I always call the REST API?" got no reply. The honest
summary: the published requirement says "must" (2022 news) / "should" (support
article); there is no public report of a rejection for omitting the call; and
the practical, user-visible consequence of omitting it is that a deleted user
re-signing-in gets the "Continue with Apple" flow with no name/email sheet, and
the developer-side `sub` is unchanged.

## 3. Google Sign-In

There is no Google equivalent of Apple's account-deletion-triggers-revocation
rule for a sign-in-only integration. What Google says:

- Google Sign-In for iOS, "Disconnecting" (updated 2025-05-19,
  https://developers.google.com/identity/sign-in/ios/disconnect):
  `GIDSignIn.sharedInstance.disconnect { error in ... }` "signs the user out in
  addition to disconnecting their account and revoking tokens", and: "If the
  user deletes their account, you must delete the information that your app
  obtained from the Google APIs." That is a data-deletion duty, satisfied by
  erasing the profile fields (name, email, Google `sub`) you stored; it is not a
  duty to call a revoke endpoint.
- OAuth 2.0 token revocation
  (https://developers.google.com/identity/protocols/oauth2/web-server, fetched
  2026-10-30): `POST https://oauth2.googleapis.com/revoke?token=...`; "The token
  can be an access token or a refresh token"; "If the token is an access token
  and it has a corresponding refresh token, the refresh token will also be
  revoked"; success is HTTP 200. Google frames this as good practice when "a
  user unsubscribes, removes an application, or the API resources required by an
  app have significantly changed", not as a review requirement.
- Google API Services User Data Policy (updated 2024-02-15,
  https://developers.google.com/terms/api-services-user-data-policy): Limited
  Use rules on what you may do with scope data; no deletion-on-disconnect clause
  in the policy text itself.

Unicoach's iOS client uses `GIDSignIn` only to obtain an ID token (local grep);
the backend validates it by JWKS and stores no Google access/refresh token, so
there is nothing to revoke. Calling `disconnect()` on the device at deletion
time is cheap hygiene (it clears the user's grant so the next sign-in shows the
consent screen again) but optional.

Google Play's account-deletion policy
(https://support.google.com/googleplay/android-developer/answer/13327111:
"provide users with an in-app path to delete their app accounts and associated
data" plus "a web link resource where users can request app account deletion",
deadlines 2023-12-07 / 2024-05-31) applies only to apps distributed on Google
Play. Unicoach is iOS-only, so it is irrelevant today; if an Android build ever
ships, the web deletion-request URL becomes mandatory there.

## 4. GDPR Article 17 right to erasure

Caveat on applicability: unicoach is a US product for US students. GDPR applies
only if it offers services to, or monitors, people in the EU/EEA (Art. 3(2)); UK
GDPR likewise for the UK. The ICO guidance is cited here because it is the
clearest regulator text on the mechanics, and because Apple's own FAQ says
deletion must be offered to everyone regardless of location, so building to the
GDPR standard is the simplest way to satisfy all three regimes at once.

### Text (verbatim, https://gdpr-info.eu/art-17-gdpr/)

Art. 17(1): "The data subject shall have the right to obtain from the controller
the erasure of personal data concerning him or her without undue delay and the
controller shall have the obligation to erase personal data without undue delay
where one of the following grounds applies:" (a) "the personal data are no
longer necessary in relation to the purposes for which they were collected or
otherwise processed;" (b) "the data subject withdraws consent on which the
processing is based [...] and where there is no other legal ground for the
processing;" (c) objection under Art. 21; (d) "the personal data have been
unlawfully processed;" (e) erasure required by law; (f) "the personal data have
been collected in relation to the offer of information society services referred
to in Article 8(1)." — (f) is the children's ground.

Art. 17(3): "Paragraphs 1 and 2 shall not apply to the extent that processing is
necessary:" (a) freedom of expression and information; (b) "for compliance with
a legal obligation which requires processing by Union or Member State law to
which the controller is subject or for the performance of a task carried out in
the public interest [...]"; (c) public health; (d) archiving/research/
statistics under Art. 89(1); (e) "for the establishment, exercise or defence of
legal claims."

Note the scope words "to the extent that": an exemption covers only the data the
obligation or claim actually needs, not the whole account.

### Timing — Art. 12(3), 12(4) (https://gdpr-info.eu/art-12-gdpr/)

"The controller shall provide information on action taken on a request under
Articles 15 to 22 [...] without undue delay and in any event within one month of
receipt of the request. That period may be extended by two further months where
necessary, taking into account the complexity and number of the requests. The
controller shall inform the data subject of any such extension within one month
of receipt of the request, together with the reasons for the delay." If
refusing: inform "at the latest within one month" of the reasons, and of the
right to complain to a supervisory authority and seek a judicial remedy.

ICO adds (right-to-erasure page, fetched 2026-10-30; page says it is under
review following the Data (Use and Access) Act): the clock starts on receipt, or
on receipt of identity confirmation if you need one; "You should not have a
blanket policy" of refusal — assess each request.

### Is a grace period (30-day soft-delete, then purge) acceptable?

Neither the Regulation nor the ICO names a number of days; the standard is
"without undue delay" with a one-month outer bound for telling the person what
you did. A design in which access ends at once, the data are put beyond use at
once, and physical purge runs on a schedule inside the month fits that standard;
the ICO's backup passage (below) explicitly accepts "instantly fulfilled in
respect of live systems" plus later overwrite elsewhere. What is NOT supported
by any source is holding the data in a _usable_ state for 30 days "in case they
come back": a cancellable grace period is a product choice that must be
disclosed, and the Art. 17 obligation is to erase, not to offer an undo. A
defensible pattern: immediate deactivation + beyond-use marking; a short,
disclosed cancellation window (days, not a month) if wanted; purge by a periodic
job within one month of the request; confirmation to the user when done (which
also satisfies Apple's "provide a confirmation when the deletion is complete").

### Does anonymisation count as erasure?

Under GDPR, data that are anonymous are not personal data (Recital 26), so
turning personal data into anonymous data takes them outside Art. 17. The
regulators' consistent position is that this only holds if re-identification is
not reasonably likely, and that pseudonymisation is NOT anonymisation:

- ICO anonymisation guidance, "Pseudonymisation" chapter (fetched 2026-10-30;
  published March/April 2025 per law-firm summaries, e.g.
  https://wiggin.co.uk/insight/anonymisation-and-pseudonymisation-ico-issues-guidance/):
  "Pseudonymised data is personal data in the hands of someone who holds the
  additional information." Pseudonymisation "is not a way of transforming
  personal data to the extent the law no longer applies." For a recipient
  without the key, "it may be anonymous information in their hands."
- EDPB Guidelines 01/2025 on Pseudonymisation (adopted for consultation
  2025-01-16; consultation 17 Jan – 14 Mar 2025,
  https://www.edpb.europa.eu/our-work-tools/documents/public-consultations/2025/guidelines-012025-pseudonymisation_en):
  same position; a final version could not be confirmed as published.
- WP29 Opinion 05/2014 on anonymisation techniques (WP216, 2014-04-10,
  https://ec.europa.eu/justice/article-29/documentation/opinion-recommendation/files/2014/wp216_en.pdf;
  PDF not readable by the fetcher) is the origin of the three-criteria test —
  singling out, linkability, inference — that the CNIL also uses (per secondary
  summaries; no English CNIL page found).

Practical consequence: a row that keeps `user_id` or any join key back to a live
identity is pseudonymised, still personal data, and still in scope. A row that
keeps only a figure and a date, with the key severed and no other row able to
re-link it, is anonymous and may be retained. For free text (chat transcripts)
anonymisation is rarely achievable — names, schools, family details inside the
text re-identify — so transcripts are an erase-not-anonymise case.

### Backups — ICO guidance (verbatim, right-to-erasure page)

> If a valid erasure request is received and no exemption applies then you will
> have to take steps to ensure erasure from backup systems as well as live
> systems. Those steps will depend on your particular circumstances, your
> retention schedule (particularly in the context of its backups), and the
> technical mechanisms that are available to you.
>
> You must be absolutely clear with individuals as to what will happen to their
> data when their erasure request is fulfilled, including in respect of backup
> systems.
>
> It may be that the erasure request can be instantly fulfilled in respect of
> live systems but that the data will remain within the backup environment for a
> certain period of time until it is overwritten.
>
> The key issue is to put the backup data 'beyond use', even if it cannot be
> immediately overwritten. You must ensure that you do not use the data within
> the backup for any other purpose ie that the backup is simply held on your
> systems until it is replaced in line with an established schedule. Provided
> this is the case it may be unlikely that the retention of personal data within
> the backup would pose a significant risk, although this will be context
> specific.

So backups may age out on a stated schedule, with two conditions: a schedule
actually exists, and the deletion notice tells the user that backups persist
until then and are not used.

### Art. 19 and Art. 28 — recipients and processors

Art. 19 (https://gdpr-info.eu/art-19-gdpr/): "The controller shall communicate
any rectification or erasure of personal data or restriction of processing [...]
to each recipient to whom the personal data have been disclosed, unless this
proves impossible or involves disproportionate effort. The controller shall
inform the data subject about those recipients if the data subject requests it."
The LLM provider is such a recipient; so is any email provider, analytics
service or crash reporter that received identifiers.

Art. 28(3) (https://gdpr-info.eu/art-28-gdpr/): the processor contract must
oblige the processor to (e) "assist[] the controller by appropriate technical
and organisational measures, insofar as this is possible, for the fulfilment of
the controller's obligation to respond to requests for exercising the data
subject's rights" and (g) "at the choice of the controller, delete[] or return[]
all the personal data to the controller after the end of the provision of
services relating to processing". In practice, with an API provider on a fixed
retention window (section 7), the Art. 19 duty is discharged by the provider's
own TTL, and the user is told so.

## 5. Financial record retention vs erasure

### US — IRS

https://www.irs.gov/businesses/small-businesses-self-employed/how-long-should-i-keep-records
(reviewed 2026-06-30): "Keep records for 3 years if situations (4), (5), and (6)
below do not apply to you." "Keep records for 6 years if you do not report
income that you should report, and it is more than 25% of the gross income shown
on your return." "Keep records for 7 years if you file a claim for a loss from
worthless securities or bad debt deduction." "Keep records indefinitely if you
do not file a return." "Keep employment tax records for at least 4 years." The
widely used "7 years" rule is the conservative envelope of these periods, not a
single statutory number. State sales-tax rules add their own periods (not
researched here).

### GDPR

Art. 17(3)(b) (legal obligation) covers retention required by tax/accounting
law, and 17(3)(e) (legal claims) covers e.g. refund/chargeback disputes — but
only "to the extent that" the records are necessary. The ICO's guidance on
exemptions says to assess per request and not apply a blanket policy; the common
practice (and the CPPA's explicit option, section 6) is to keep the transaction
record and sever the personal identifiers.

### What the developer actually needs to keep for an Apple subscription

Apple is the merchant of record: it holds the customer's payment method,
invoice, tax and refund records. The developer's financial record is the App
Store payout report, which is already aggregated and not per-user. What the
developer's own database holds is the entitlement history keyed by
`originalTransactionId`
(https://developer.apple.com/documentation/appstoreserverapi/originaltransactionid,
fetched 2026-10-30): "The App Store generates an original transaction identifier
when a customer makes a successful in-app purchase. [...] Tip: If you maintain a
database to manage your subscribers, save the original transaction identifier to
uniquely identify auto-renewable subscriptions."

Is `originalTransactionId` personal data? Not settled by any source found. The
reasoning: it is an identifier that Apple can resolve to a named customer, and
that the developer can resolve to a user while the `user_id` link exists. Under
GDPR Art. 4(1) and Recital 26 ("reasonably likely to be used" by the controller
"or by another person"), an identifier another party can resolve is usually
treated as personal data — the same reasoning the EDPB applies to pseudonymised
data. Treat it as personal data. After the `user_id` link is severed, the
developer can no longer resolve it; whether Apple's ability to do so keeps it
personal in the developer's hands is exactly the "whose hands" question the ICO
guidance raises, and the conservative answer is to also drop or hash it once no
legal/dispute purpose remains. A reasonable retention posture: keep the
subscription row with `user_id` severed, `originalTransactionId` retained only
while refund/dispute exposure exists (Apple's refund window and App Store Server
Notifications `REFUND` events point at months, not years), then purge; keep
amounts, product IDs, dates indefinitely as anonymous aggregates. The LLM cost
ledger follows the same rule: cost rows are only financial records if they are
reconciled to anything; identifiers off, figures stay.

## 6. CCPA/CPRA deletion right

Caveat on applicability: the CCPA applies to a "business" meeting its thresholds
(currently >$26.6M revenue, or 100,000+ consumers'/households' data, or 50%+
revenue from selling/sharing — per the CPPA's 2026 statute compilation). A
pre-revenue app may be below them; the design should still meet the rule because
the thresholds are crossed silently.

Cal. Civ. Code 1798.105 (FindLaw mirror, "Current as of January 01, 2026",
https://codes.findlaw.com/ca/civil-code/civ-sect-1798-105/):

- (a) "A consumer shall have the right to request that a business delete any
  personal information about the consumer which the business has collected from
  the consumer."
- (c)(1): the business "shall delete the consumer's personal information from
  its records, notify any service providers or contractors to delete the
  consumer's personal information from their records, and notify all third
  parties to whom the business has sold or shared the personal information to
  delete the consumer's personal information unless this proves impossible or
  involves disproportionate effort."
- (d) exceptions, where the information is reasonably necessary to: (1)
  "Complete the transaction for which the personal information was collected,
  fulfill the terms of a written warranty or product recall [...], provide a
  good or service requested by the consumer, or reasonably anticipated by the
  consumer within the context of a business's ongoing business relationship with
  the consumer, or otherwise perform a contract"; (2) "Help to ensure security
  and integrity"; (3) "Debug to identify and repair errors"; (4) free speech;
  (5) CalECPA; (6) research; (7) "To enable solely internal uses that are
  reasonably aligned with the expectations of the consumer based on the
  consumer's relationship with the business"; (8) "Comply with a legal
  obligation."

Response window, 1798.130(a)(2)(A) (same mirror): "within 45 days of receiving a
verifiable consumer request", extendable "once by an additional 45 days when
reasonably necessary, provided the consumer is provided notice of the extension
within the first 45-day period."

CPPA regulations, 11 CCR 7022 (operative 2023-03-29,
https://www.law.cornell.edu/regulations/california/11-CCR-7022): a business
complies by "Permanently and completely erasing the personal information on its
existing systems with the exception of archived or backup systems, deidentifying
the personal information, or aggregating the consumer information" — so
deidentification/aggregation is an explicitly permitted way to comply, and
archived/backup systems are carved out (they must be deleted when next accessed
or used); the business must notify "service providers or contractors" and "all
third parties to whom the business has sold or shared the personal information".
When an exception applies the business must "Provide to the consumer a detailed
explanation of the basis for the denial", "Delete the consumer's personal
information that is not subject to the exception", and "Not use the consumer's
personal information retained for any other purpose than provided for by that
exception."

Mapping to unicoach: the LLM provider and any email/analytics vendor are
"service providers" to be notified (same practical answer as GDPR Art. 19: their
fixed TTL discharges it, and the user is told). Retaining a severed subscription
record falls under (d)(1)/(d)(8) plus the regulation's deidentification route.

## 7. LLM providers as processors

### Anthropic (Claude API)

- Default: "we automatically delete inputs and outputs on our backend within 30
  days of receipt or generation" (Privacy Center, updated 2026-07-01,
  https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-personal-data),
  with exceptions "When you use a service with longer retention under your
  control (e.g. Files API)", "If we need to retain them for longer to enforce
  our Usage Policy", "In compliance with the law", and trust-and-safety: "We
  retain inputs and outputs for up to 2 years and trust and safety
  classification scores for up to 7 years if your chat is flagged by our
  automated trust and safety systems as violating our Usage Policy."
- The platform doc
  (https://platform.claude.com/docs/en/manage-claude/api-and-data-retention,
  fetched 2026-10-30) frames it differently: "Conversation content (your prompts
  and Claude's outputs) is not retained by default; the exception is Covered
  Models, which require 30-day retention." The two pages are not obviously
  consistent; the Privacy Center "within 30 days" is the one to quote to users,
  as the outer bound.
- Covered Models: "Claude Fable 5.1, Claude Mythos 5.1, Claude Fable 5, and
  Claude Mythos 5: These models require 30-day data retention and are not
  available under ZDR unless expressly authorized by Anthropic." The support
  article (updated 2026-09-01, https://support.claude.com/en/articles/15425695)
  says prompts and completions are kept for at least 30 days then deleted
  automatically, "Retained data is assessed by automated safety systems designed
  to flag harmful content", with exceptions for safety investigations and legal
  requirements.
- ZDR: "Under a ZDR arrangement, Anthropic does not store customer prompts or
  responses at rest after the API response is returned. To request ZDR for your
  organization, contact the Anthropic sales team." Enabled per organization, by
  approval; not available for Covered Models.
- Training: "Retained data is never used for model training without your express
  permission."
- Deletion on request for non-ZDR features: the doc says "Contact your Anthropic
  account representative to discuss deletion options" — there is no self-serve
  per-user deletion API for request logs.

### OpenAI (API)

https://developers.openai.com/api/docs/guides/your-data (fetched 2026-10-30):
abuse-monitoring logs "retained for up to 30 days, unless longer retention is
required by law"; "data sent to the OpenAI API is not used to train or improve
OpenAI models" by default; table: Responses and Chat Completions — 30 days abuse
monitoring, no application state, ZDR-eligible "with limitations"; Files,
Batches, Vector stores — 30 days plus "Until deleted", not ZDR-eligible. ZDR
"subject to prior approval by OpenAI and acceptance of additional requirements"
via sales.

Court-ordered preservation: in the NYT copyright case a 2025-05-13 order
required OpenAI to preserve output logs it would otherwise delete; OpenAI's
statement (reported by Simon Willison, 2025-06-05,
https://simonwillison.net/2025/Jun/5/openai-court-order, and law-firm notes,
e.g.
https://www.loeb.com/en/insights/passle/2025/06/court-orders-openai-to-retain-all-output-log-data-considerations-for-chatgpt-users)
was that ZDR API customers are unaffected because nothing is stored, and that
Enterprise data was exempt. OpenAI's own page returned 403 to the fetcher, and
no 2026 report on the order's current status was found — so the "30 days" figure
for non-ZDR OpenAI API use carried a litigation-hold asterisk in 2025 and its
2026 status is unverified.

### What this means for the deletion notice

Whichever provider is in use, the honest sentence to show a deleting user is:
"Your conversations are deleted from our systems [now / within N days]. Our AI
provider keeps API inputs and outputs for up to 30 days for abuse monitoring and
then deletes them; they are not used to train models." If a Covered Model is in
use, the 30 days is a floor, not a ceiling ("at least 30 days"). If a transcript
was flagged by the provider's safety systems, the provider may hold it up to 2
years — a case the notice cannot promise away.

## 8. Minors 13–17

- COPPA: applies only under 13. FTC FAQ
  (https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions;
  notes "The COPPA Rule was amended on April 22, 2025"): "Congress determined to
  apply the statute's protections only to children under 13." and "Although
  COPPA does not apply to teenagers, the FTC is concerned about teen privacy."
  Nothing in COPPA bears on erasure for a 13+ user.
- California "eraser" law, Bus. & Prof. Code 22581 (FindLaw mirror, current as
  of 2026-01-01,
  https://codes.findlaw.com/ca/business-and-professions-code/bpc-sect-22581/):
  an operator of a site/app "directed to minors" or with "actual knowledge that
  a minor is using" it must "permit a minor who is a registered user [...] to
  remove or, if the operator prefers, to request and obtain removal of, content
  or information posted" by the minor; must "Provide notice to a minor who is a
  registered user" that they may do so; and must tell them removal "does not
  ensure complete or comprehensive removal". Exceptions: other law requires
  retention; content reposted by another user; "The operator [...] anonymizes
  the content or information posted by the minor"; the minor doesn't follow the
  instructions; the minor was compensated. Unicoach knows its users are minors
  (13–17 students), so this applies to content a student "posted" — arguably
  their chat messages and profile claims. A full erasure path exceeds it; the
  only extra is the NOTICE obligation (tell minors they can remove their
  content), which belongs in the privacy policy / settings copy.
- GDPR Art. 8(1) (https://gdpr-info.eu/art-8-gdpr/): consent of a child for
  information society services is valid from 16, "Member States may provide by
  law for a lower age for those purposes provided that such lower age is not
  below 13 years"; the UK uses 13. Art. 17(1)(f) makes data collected under Art.
  8(1) a named erasure ground. ICO, "How does the right to erasure apply to
  children?" (fetched 2026-10-30): the right "is relevant in particular where
  the data subject has given his or her consent as a child" (Recital 65); where
  data were provided as a child "you should comply with their wishes whenever
  you can"; erasure is "more likely to prevail in this circumstance then if data
  was provided by an adult", and this persists after they turn 18.
- UK Children's Code (Age Appropriate Design Code): applies to services likely
  to be accessed by under-18s; its relevant expectation is that children get
  practical tools to delete their data (ICO summaries), which an in-app deletion
  flow meets. No age-band-specific erasure rule for 13–17 was found.
- Parents: a parent account's deletion is an ordinary adult request; a student's
  deletion request should not require the parent's consent (the right is the
  data subject's), and a parent cannot delete the student's data on the
  student's behalf without authority — out of scope here but worth a product
  decision.

## 9. Public accounts of 5.1.1(v) rejections (anecdotal)

All of the following are forum/blog reports, not Apple statements, except where
the reviewer's message is quoted by the developer.

- Reviewer boilerplate, quoted verbatim by developers in
  https://developer.apple.com/forums/thread/765160 (Oct 2024, e-learning app)
  and https://developer.apple.com/forums/thread/775209 (Feb 2025): "The app
  supports account creation but does not include an option to initiate account
  deletion. [...] Only offering to temporarily deactivate or disable an account
  is insufficient. If users need to visit a website to finish deleting their
  account, include a link directly to the website page where they can complete
  the process. Apps may include confirmation steps to prevent users from
  accidentally deleting their account. However, only apps in highly-regulated
  industries may require users to use customer service resources, such as making
  a phone call or sending an email, to complete account deletion." Both apps had
  NO deletion option at all; neither thread records the resolution.
- https://discussions.apple.com/thread/254032717 (July 2022, fintech app): a
  button that messaged customer support was rejected repeatedly — "Your app only
  offers to deactivate the account" / "Temporarily deactivating accounts is not
  sufficient" — and on the fourth round the reviewer asked: "Can you please cite
  the specific laws (and point to the specific portion) that apply to why you
  are not able to delete an account from within the app." No outcome recorded.
  Lesson: a legal-retention claim gets challenged; be ready to name the law.
- https://developer.apple.com/forums/thread/761486 (Aug 2024, chatbot app with
  Google and Apple sign-in): update rejected under 5.1.1(v) after adding
  sign-in; developer says Apple never explained; escalated to a call. No detail
  on what the app offered.
- https://dev.to/tychelabs/why-apps-get-rejected-285-public-app-review-cases-analysed-4h1o
  (2026-10-27; Reddit, Apple forums, SO, HN posts 2023–Oct 2026): account
  deletion was 3 of 285 cases (~1%); no patterns described; outcomes missing for
  173 cases. Too small to conclude anything beyond "it is cited, rarely".
- Vendor blogs (e.g.
  https://dev.to/russel_dsouza_bd584a3cb2a/why-was-my-app-rejected-for-account-deletion-and-how-to-fix-it-3gd6,
  undated; ptkd.com; support-url-generator.com) repeat the support-article rules
  and add generic advice: put the path in the review notes ("Settings → Account
  → Delete Account"), make sure the demo account can reach it, and make sure it
  works for Sign in with Apple accounts. None is first-hand.
- No public report was found of a rejection for (i) omitting the Sign in with
  Apple revoke call, (ii) a disclosed "completes within N days" delay, or (iii)
  a deactivate-first-then-purge design that also disclosed the purge. Absence of
  reports is weak evidence; the reviewer boilerplate above is the only thing
  reviewers are documented to check, and it is about the presence,
  discoverability and non-support-flow nature of the option.

## What this means for unicoach's erasure slice

**Minimum that satisfies App Store review (5.1.1(v) + support article):**

1. An in-app "Delete account" action, in account settings, that any signed-in
   user (student or parent, any sign-in method) can reach without contacting
   support. The existing closure flow is the right place; it must become
   deletion, with closure at most an extra option.
2. It must delete "the entire account record, along with associated personal
   data" — rows kept with identity attached do not qualify. Legally-required
   retention is allowed, but the flow must say what is kept.
3. If the purge is deferred, tell the user how long it takes and confirm when
   done (email is the obvious channel; the closure already releases the sign-in
   identity so there is no in-app surface afterwards).
4. If the user has an active subscription: say billing continues through Apple
   until they cancel, present `showManageSubscriptions`, and do not block
   deletion on cancellation. Optional: offer "delete when my subscription ends"
   alongside immediate deletion.
5. Sign in with Apple: Apple says tokens "should"/"must" be revoked. The backend
   holds no refresh token today, so for existing users the only compliant routes
   are (a) TN3194's manual route — delete the data, tell the user to remove the
   app under Settings > [name] > Sign in with Apple — or (b) re-authenticate
   inside the deletion flow to get a fresh authorization code, exchange it at
   `/auth/token`, then `/auth/revoke`. For new users, start exchanging the code
   at sign-in and storing the refresh token so (b) is not needed. Enforcement by
   review is undocumented; the user-visible symptom of skipping it is that a
   re-sign-in shows no name/email sheet.
6. Put the path in App Review notes and make sure the demo account reaches it.

**What GDPR/CCPA add (and the ICO mechanics worth adopting everywhere):**

7. Act "without undue delay": access off and data beyond use at once; physical
   purge within one month of the request (CCPA: 45 days). A multi-week "undo"
   window with data still usable is not supported by any source; a short
   disclosed cancellation window is a product choice, not a legal one.
8. Erase, don't pseudonymise: any row that keeps a join key to the user is still
   personal data. Severed, key-free financial rows (amount, product, date) may
   stay. Chat transcripts, claims, observations, raw LLM bodies are erase cases,
   not anonymise cases.
9. Retention carve-out must be narrow and named: subscription history with
   `user_id` severed; `originalTransactionId` kept only while refund/dispute
   exposure exists, then dropped; IRS periods (3–7 years) apply to the
   business's own books, which for Apple IAP are the payout reports, not the
   per-user rows.
10. Backups: allowed to age out on the stated schedule provided a schedule
    exists and the notice says so.
11. Processors/recipients: name the LLM provider's retention in the notice (30
    days default; "at least 30 days" for Covered Models; up to 2 years if
    flagged); no per-user deletion API exists at Anthropic, so the TTL is the
    mechanism.
12. Minors: no extra erasure mechanics for 13–17, but Cal. B&P 22581 wants a
    NOTICE that minors can remove content they posted — one sentence in the
    privacy policy/settings copy. Erasure requests from minors should be
    honoured readily (ICO: "comply with their wishes whenever you can").

**Optional / nice:**

13. Export-before-delete (GDPR Art. 20 portability is a separate right; Apple
    does not require it).
14. Scheduled deletion aligned to subscription expiry (Apple explicitly allows,
    with immediate deletion also offered).
15. `GIDSignIn.disconnect()` on the device at deletion so the next Google
    sign-in shows consent again; no backend revocation is needed since no Google
    tokens are stored.
16. A Sign in with Apple server-to-server `consent-revoked` endpoint, so a user
    who revokes from Settings first still triggers the same purge path.

**Open uncertainties, stated plainly:** whether App Review actually tests the
Apple revoke call (no public evidence either way); whether
`originalTransactionId` is personal data once severed (treat as yes); the
current status of OpenAI's litigation hold (unverified for 2026); whether the
EDPB pseudonymisation guidelines have a final version; and the California
statute text was read from a mirror, not leginfo.
