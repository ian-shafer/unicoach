package ed.unicoach.rest.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun Application.configureSerialization() {
  install(ContentNegotiation) {
    jackson {
      enable(SerializationFeature.INDENT_OUTPUT)
      configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
      configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
      // Serialize java.time.Instant as ISO-8601 strings rather than numeric epoch arrays.
      registerModule(JavaTimeModule())
      disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
  }
}
