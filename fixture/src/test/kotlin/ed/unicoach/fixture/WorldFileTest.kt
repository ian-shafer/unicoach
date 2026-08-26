package ed.unicoach.fixture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldFileTest {
  @Test
  fun `parses the full documented format`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: test1@example.com
            password: hunter2
            name: Test One
            verified: true
            admin: false
          - email: admin@example.com
            password: hunter2
            admin: true
        """.trimIndent(),
      )

    assertEquals(2, world.users.size)
    val first = world.users[0]
    assertEquals("test1@example.com", first.email)
    assertEquals("hunter2", first.password)
    assertEquals("Test One", first.resolvedName)
    assertTrue(first.verified)
    assertFalse(first.admin)
    assertTrue(world.users[1].admin)
  }

  @Test
  fun `defaults name from the email local part and verified to true and admin to false`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: test1@example.com
            password: hunter2
        """.trimIndent(),
      )

    val user = world.users.single()
    assertEquals("test1", user.resolvedName)
    assertTrue(user.verified, "verified must default to true")
    assertFalse(user.admin, "admin must default to false")
  }

  @Test
  fun `unknown key is a hard error`() {
    assertThrows<Exception> {
      WorldFile.parse(
        """
        users:
          - email: test1@example.com
            password: hunter2
            verifed: true
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `unknown top-level key is a hard error`() {
    assertThrows<Exception> {
      WorldFile.parse(
        """
        userz: []
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `missing email is a hard error`() {
    assertThrows<Exception> {
      WorldFile.parse(
        """
        users:
          - password: hunter2
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `missing password is a hard error`() {
    assertThrows<Exception> {
      WorldFile.parse(
        """
        users:
          - email: test1@example.com
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `empty users list is a valid empty world`() {
    val world = WorldFile.parse("users: []")
    assertTrue(world.users.isEmpty())
  }

  @Test
  fun `empty file is a valid empty world`() {
    assertTrue(WorldFile.parse("").users.isEmpty())
    assertTrue(WorldFile.parse("   \n").users.isEmpty())
  }
}
