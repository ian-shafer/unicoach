# Net Handlers Specification

## I. Overview
This directory contains the integration layer between the asynchronous queue infrastructure and domain logic for the `net` module. Its singular purpose is to house queue job handlers that bridge `queue` module interfaces (`JobHandler`) with database operations (`db` module DAOs) to process background jobs.

## II. Invariants
- Handlers MUST implement the `JobHandler` interface from the `queue` module.
- Handlers MUST declare a specific `JobType` and a `JobTypeConfig` that governs their concurrency, retry limits, and timeouts.
- Handlers MUST rely exclusively on database-level Optimistic Concurrency Control (OCC) for deduplication. They MUST NOT use application-level metadata writes (e.g., to JSONB columns) to track job execution state.
- Handlers MUST execute blocking JDBC operations securely by wrapping them in `database.withConnection { ... }`. 
- Handlers MUST map all internal domain errors to explicit `JobResult` types (`Success`, `RetriableFailure`, `PermanentFailure`).

## III. Behavioral Contracts

### `SessionExpiryHandler`
See [SessionExpiryHandler.kt](./SessionExpiryHandler.kt)

- **Side Effects**: Reads session state and conditionally executes an `UPDATE` on the `sessions` table via `SessionsDao`.
- **Error Handling**:
  - Malformed JSON payloads or invalid Base64 decoding MUST return `JobResult.PermanentFailure`.
  - Transient database errors during lookup or update MUST return `JobResult.RetriableFailure` to leverage queue backoff.
  - If the session is missing, revoked, or already expired during lookup, the handler MUST return `JobResult.Success` (treated as a graceful no-op).
  - If the `extendExpiry` operation returns an OCC version mismatch (`SessionUpdateResult.NotFound`), the handler MUST return `JobResult.Success`.
- **Idempotency**: **Idempotent: yes**. Concurrent or duplicate executions are safely ignored due to OCC version checks and sliding window threshold validation.

## IV. Infrastructure & Environment
- **Configuration**: Handlers in this directory depend on the `net.conf` configuration block, specifically `net.session.slidingWindowThreshold`. The runtime orchestrator (`queue-worker`) MUST inject this configuration during handler registration.
- **Execution Context**: Handlers are executed on an IO-bound dispatcher provided by the `QueueWorker` framework.

## V. History
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
