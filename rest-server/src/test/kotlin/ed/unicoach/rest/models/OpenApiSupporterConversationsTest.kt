package ed.unicoach.rest.models

import ed.unicoach.rest.routing.SUPPORTER_CONVERSATIONS_PATH
import ed.unicoach.rest.routing.SUPPORTER_CONVERSATION_ID_PARAM
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Holds the published supporter-coach contract (RFC 258 §7) to the Kotlin
 * declarations it restates — the [OpenApiSupporterCoachingUsageTest] idiom.
 * Field sets are read off the data classes Jackson serialises, never retyped,
 * so a field added to a model and not to `api-specs/openapi.yaml` fails here.
 * `SupporterConvoRoutingTest` proves the served body has the same fields.
 */
class OpenApiSupporterConversationsTest {
  private val list = SUPPORTER_CONVERSATIONS_PATH
  private val start = "$SUPPORTER_CONVERSATIONS_PATH/stream"
  private val messages = "$SUPPORTER_CONVERSATIONS_PATH/{$SUPPORTER_CONVERSATION_ID_PARAM}/messages"
  private val turn = "$messages/stream"

  private fun assertPublishedExactly(
    schema: String,
    model: kotlin.reflect.KClass<*>,
  ) {
    val fields = OpenApiSpec.declaredFieldNames(model)
    assertEquals(
      fields,
      OpenApiSpec.requiredProperties(schema).toSet(),
      "[$schema] required must be exactly [${model.simpleName}]'s fields",
    )
    assertEquals(
      fields,
      OpenApiSpec
        .schema(schema)
        .path("properties")
        .fieldNames()
        .asSequence()
        .toSet(),
      "[$schema] must publish them as properties",
    )
    assertEquals(false, OpenApiSpec.schema(schema).path("additionalProperties").asBoolean(true), "[$schema] is closed")
  }

  @Test
  fun `the published list item, list body and start body are exactly the Kotlin models`() {
    assertPublishedExactly("SupporterConversation", SupporterConversation::class)
    assertPublishedExactly("SupporterConversationListResponse", SupporterConversationListResponse::class)
    assertPublishedExactly("CreateSupporterConversationRequest", CreateSupporterConversationRequest::class)
  }

  @Test
  fun `the turn and transcript reuse the student's published shapes, by reference`() {
    fun requestRef(path: String) =
      OpenApiSpec
        .operation(path, "post")
        .path("requestBody")
        .path("content")
        .path("application/json")
        .path("schema")
        .path("\$ref")
        .asText()

    fun okRef(
      path: String,
      method: String,
      type: String,
    ) = OpenApiSpec
      .operation(path, method)
      .path("responses")
      .path("200")
      .path("content")
      .path(type)
      .path("schema")
      .path("\$ref")
      .asText()

    assertEquals("#/components/schemas/PostMessageRequest", requestRef(turn))
    assertEquals("#/components/schemas/StreamEvent", okRef(start, "post", "text/event-stream"))
    assertEquals("#/components/schemas/StreamEvent", okRef(turn, "post", "text/event-stream"))
    assertEquals("#/components/schemas/MessageListResponse", okRef(messages, "get", "application/json"))
    assertEquals("#/components/schemas/SupporterConversationListResponse", okRef(list, "get", "application/json"))
  }

  @Test
  fun `every route requires a session and publishes the refusal table`() {
    val expected =
      mapOf(
        (list to "get") to listOf("200", "401", "403", "404", "500"),
        (start to "post") to listOf("200", "400", "401", "402", "403", "404", "413", "500"),
        (messages to "get") to listOf("200", "400", "401", "403", "404", "500"),
        (turn to "post") to listOf("200", "400", "401", "402", "403", "404", "413", "500"),
      )
    for ((route, statuses) in expected) {
      val operation = OpenApiSpec.operation(route.first, route.second)
      assertEquals(
        listOf("cookieAuth"),
        operation.path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
        "[$route] requires a session",
      )
      assertEquals(
        statuses,
        operation
          .path("responses")
          .fieldNames()
          .asSequence()
          .toList(),
        "[$route] publishes exactly its answers",
      )
    }
  }
}
