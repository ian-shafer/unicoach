package ed.unicoach.account

/**
 * What an erased account leaves behind, as told to the person who erased it:
 * the records unicoach keeps ([KEPT]) and what the AI provider keeps
 * ([AI_PROVIDER_RETENTION]). The ACCOUNT_ERASED email and `/privacy` both read
 * these, so the two Kotlin surfaces cannot disagree. The iOS delete
 * confirmation (`DeleteAccountControl`, ios-app/UnicoachiOS/SettingsView.swift)
 * holds the Swift copy, which cannot share these strings or
 * [ERASURE_WINDOW_DAYS] (its "within 30 days" is a literal); match it here.
 *
 * [AI_PROVIDER_RETENTION] is true only for the configured models (`model` in
 * service.conf's `coaching`, `extraction`, `synthesis` and `fitLens` blocks):
 * the 30 days is the provider's retention term for those models, which no API
 * reports. Re-check it before changing any of them.
 */
object ErasureDisclosure {
  /** The "within N days" every deletion surface promises; also the ceiling of `accountErasure.graceDays`. */
  const val ERASURE_WINDOW_DAYS = 30

  const val KEPT =
    "What we keep: a record that your account existed and when it was deleted; your subscription " +
      "record with Apple, if you have one; and our usage records — how much you used the coach — with " +
      "your words removed."

  const val AI_PROVIDER_RETENTION =
    "Our AI provider keeps conversation text for up to 30 days with no account attached, then deletes it."
}
