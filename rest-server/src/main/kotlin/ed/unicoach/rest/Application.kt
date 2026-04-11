package ed.unicoach.rest

import ed.unicoach.auth.AuthService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.plugins.configureSerialization
import ed.unicoach.rest.plugins.configureStatusPages
import ed.unicoach.util.JwtGenerator
import io.ktor.server.application.Application

fun main(args: Array<String>) {
  io.ktor.server.netty.EngineMain
    .main(args)
}

fun Application.module() {
  configureSerialization()
  configureStatusPages()

  val dbConfig = DatabaseConfig(
    url = environment.config.propertyOrNull("database.url")?.getString() ?: "jdbc:postgresql://localhost:5432/unicoach",
    user = environment.config.propertyOrNull("database.user")?.getString() ?: "unicoach",
  )
  val database = Database(dbConfig)

  val jwtSecret = environment.config.propertyOrNull("jwt.secret")?.getString() ?: "secret"
  val jwtIssuer = environment.config.propertyOrNull("jwt.issuer")?.getString() ?: "issuer"
  val jwtGenerator = JwtGenerator(jwtSecret, jwtIssuer)

  val authService = AuthService(database, jwtGenerator)

  configureRouting(authService)
}
