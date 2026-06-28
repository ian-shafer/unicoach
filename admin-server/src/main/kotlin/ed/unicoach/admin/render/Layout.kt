package ed.unicoach.admin.render

import ed.unicoach.admin.engine.AdminResource
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

private const val STYLES = """
  body { font-family: system-ui, sans-serif; margin: 0; display: flex; }
  nav.sidebar { width: 200px; background: #1f2933; color: #e4e7eb; padding: 1rem; min-height: 100vh; }
  nav.sidebar a { color: #9fb3c8; text-decoration: none; display: block; padding: 0.25rem 0; }
  nav.sidebar a:hover { color: #fff; }
  main { padding: 1.5rem; flex: 1; }
  table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
  th, td { border: 1px solid #cbd2d9; padding: 0.4rem 0.6rem; text-align: left; }
  th { background: #f5f7fa; }
  .panel { border: 1px solid #cbd2d9; border-radius: 4px; padding: 1rem; margin: 1rem 0; }
  .error { color: #b91c1c; font-weight: bold; }
  .deleted { color: #b91c1c; }
  .deleted-badge { background: #b91c1c; color: #fff; padding: 0 0.3rem; border-radius: 3px; font-size: 0.8rem; }
  .pager a { margin-right: 1rem; }
  .bool-true { color: #15803d; }
  .bool-false { color: #b91c1c; }
  a.id-link { text-decoration: none; }
  label { display: block; font-weight: bold; margin-top: 0.5rem; }
  input, textarea, select { width: 320px; padding: 0.3rem; }
  button { margin-top: 0.75rem; padding: 0.4rem 0.8rem; }
"""

/**
 * The shared page chrome: a nav sidebar of top-level sections plus the content
 * area. Pass `nav = false` for unauthenticated/standalone pages (login, errors).
 */
fun HTML.adminPage(
  pageTitle: String,
  nav: Boolean = true,
  topLevelResources: List<AdminResource<*, *>> = emptyList(),
  content: MAIN.() -> Unit,
) {
  head {
    title { +"unicoach admin — $pageTitle" }
    style { unsafe { +STYLES } }
  }
  body {
    if (nav) {
      nav("sidebar") {
        h2 { +"unicoach admin" }
        ul {
          li { a(href = "/") { +"Dashboard" } }
          topLevelResources.forEach { resource ->
            li { a(href = "/${resource.slug}") { +resource.title } }
          }
        }
        form(action = "/logout", method = FormMethod.post) {
          button(type = ButtonType.submit) { +"Log out" }
        }
      }
    }
    main { content() }
  }
}
