import os

extension Logger {
    /// This app's one logging subsystem — the bundle identifier, spelled once.
    /// A call site chooses a `category` and nothing else, so a typo in the
    /// subsystem cannot split one app's log across two of them.
    static func unicoach(category: String) -> Logger {
        Logger(subsystem: "coach.uni.UnicoachiOS", category: category)
    }
}
