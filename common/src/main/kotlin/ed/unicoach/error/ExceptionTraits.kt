package ed.unicoach.error

/**
 * Trait for errors that are transient and may succeed if retried.
 */
interface TransientError

/**
 * Trait for errors that are permanent and should not be retried.
 */
interface PermanentError
