package ed.unicoach.rest.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
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
      // Reject scalar type-punning: a JSON string supplied for a numeric/boolean
      // target (e.g. UpdateStudentRequest.version: Int) must fail rather than coerce.
      disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
      // ALLOW_COERCION_OF_SCALARS governs only string-shaped inputs; a JSON
      // boolean/number supplied for a String target (e.g. RegisterRequest.name)
      // still stringifies unless the Textual logical type rejects those shapes.
      coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
      // Serialize java.time.Instant as ISO-8601 strings rather than numeric epoch arrays.
      registerModule(JavaTimeModule())
      disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
  }
}
