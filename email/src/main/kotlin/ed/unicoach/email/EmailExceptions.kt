package ed.unicoach.email

import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

// Misconfigured sender: email.defaultFrom failed EmailAddress.create. Permanent
// because retrying an unchanged config cannot succeed.
class EmailConfigException(
  message: String = "Configured sender address is invalid",
) : RuntimeException(message),
  PermanentError

// Provider permanently rejected the message; no retry helps.
class EmailRejectedException(
  reason: String,
) : RuntimeException(reason),
  PermanentError

// Provider transiently failed to deliver; a retry may succeed.
class EmailDeliveryException(
  reason: String,
) : RuntimeException(reason),
  TransientError
