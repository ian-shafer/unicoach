package ed.unicoach.db

data class DatabaseConfig(
  val url: String,
  val user: String,
  val password: String? = null,
  val maximumPoolSize: Int = 10,
  val connectionTimeoutMs: Long = 3000L,
)
