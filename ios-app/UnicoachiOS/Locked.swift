import Foundation

/// A value behind a lock: the one place in this app that knows how a
/// `@unchecked Sendable` type is actually made safe. Reads and mutations both
/// go through `withLock`, so the `defer` that releases the lock is written
/// once rather than at every site that needs guarded state.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    var current: Value { withLock { $0 } }

    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }
}
