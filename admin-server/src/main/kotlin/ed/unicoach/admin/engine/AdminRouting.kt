package ed.unicoach.admin.engine

import ed.unicoach.admin.render.adminPage
import ed.unicoach.admin.render.renderDetail
import ed.unicoach.admin.render.renderForm
import ed.unicoach.admin.render.renderList
import ed.unicoach.admin.render.respondConflict
import ed.unicoach.admin.render.respondDaoError
import ed.unicoach.admin.render.respondNotFound
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.models.SoftDeleteScope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.ul

private const val PAGE_SIZE = 50

/** Registers the dashboard and the generic per-resource routes for every topLevel resource. */
fun Route.registerAdminRoutes(
  registry: AdminRegistry,
  database: Database,
) {
  get("/") {
    call.respondHtml {
      adminPage("Dashboard", topLevelResources = registry.topLevel) {
        h1 { +"Dashboard" }
        p { +"Select a section:" }
        ul {
          registry.topLevel.forEach { resource ->
            li { a(href = "/${resource.slug}") { +resource.title } }
          }
        }
      }
    }
  }

  registry.topLevel.forEach { resource ->
    registerResourceRoutes(resource, registry, database)
  }

  // Owner-nested action endpoints (e.g. embedded student under user) register
  // for every resource, behind the same gate scope as the generic routes.
  registry.all.forEach { resource -> resource.registerExtraRoutes(this, database) }
}

private fun <ROW, ID> Route.registerResourceRoutes(
  resource: AdminResource<ROW, ID>,
  registry: AdminRegistry,
  database: Database,
) {
  val slug = resource.slug

  get("/$slug") {
    val offset = call.offset()
    val listResult = resource.list(database, limit = PAGE_SIZE + 1, offset = offset, scope = SoftDeleteScope.ALL)
    listResult.fold(
      onSuccess = { fetched ->
        val hasNext = fetched.size > PAGE_SIZE
        val rows = if (hasNext) fetched.dropLast(1) else fetched
        call.respondHtml {
          adminPage(resource.title, topLevelResources = registry.topLevel) {
            renderList(resource, rows, offset, PAGE_SIZE, hasNext)
          }
        }
      },
      onFailure = { call.respondDaoError(it) },
    )
  }

  if (resource.create != null) {
    get("/$slug/new") {
      call.respondHtml {
        adminPage("New ${resource.title}", topLevelResources = registry.topLevel) {
          h1 { +"New ${resource.title}" }
          renderForm(
            action = "/$slug",
            editableFields = resource.fields + resource.createExtraInputs,
            values = emptyMap(),
            submitLabel = "Create",
          )
        }
      }
    }

    post("/$slug") {
      val form = call.formMap()
      val createResult = resource.create!!.invoke(database, form)
      createResult.fold(
        onSuccess = { newId -> call.respondRedirect("/$slug/${resource.idToPath(newId)}") },
        onFailure = { error ->
          val message = createFormErrorMessage(error) ?: return@fold call.respondDaoError(error)
          call.respondHtml(HttpStatusCode.BadRequest) {
            adminPage("New ${resource.title}", topLevelResources = registry.topLevel) {
              h1 { +"New ${resource.title}" }
              p("error") { +message }
              renderForm(
                action = "/$slug",
                editableFields = resource.fields + resource.createExtraInputs,
                values = form,
                submitLabel = "Create",
              )
            }
          }
        },
      )
    }
  }

  get("/$slug/{id}/edit") {
    if (resource.update == null) return@get call.respondNotFound()
    val id = resource.parseId(call.pathId()) ?: return@get call.respondNotFound()
    val idPath = resource.idToPath(id)
    resource.get(database, id, includeDeleted = true).fold(
      onSuccess = { row ->
        val values = resource.cells(row)
        call.respondHtml {
          adminPage("Edit ${resource.title}", topLevelResources = registry.topLevel) {
            h1 { +"Edit ${resource.title} $idPath" }
            renderForm(
              action = "/$slug/$idPath",
              editableFields = resource.fields,
              values = values,
              version = values["version"]?.toIntOrNull(),
              submitLabel = "Save",
            )
          }
        }
      },
      onFailure = { call.respondDaoError(it) },
    )
  }

  post("/$slug/{id}/delete") {
    if (resource.delete == null) return@post call.respondNotFound()
    val id = resource.parseId(call.pathId()) ?: return@post call.respondNotFound()
    resource.delete!!.invoke(database, id).fold(
      onSuccess = { call.respondRedirect("/$slug") },
      onFailure = { call.respondDaoError(it) },
    )
  }

  if (resource.undelete != null) {
    post("/$slug/{id}/undelete") {
      val id = resource.parseId(call.pathId()) ?: return@post call.respondNotFound()
      resource.undelete!!.invoke(database, id).fold(
        onSuccess = { call.respondRedirect("/$slug/${resource.idToPath(id)}") },
        onFailure = { call.respondDaoError(it) },
      )
    }
  }

  post("/$slug/{id}") {
    if (resource.update == null) return@post call.respondNotFound()
    val id = resource.parseId(call.pathId()) ?: return@post call.respondNotFound()
    val idPath = resource.idToPath(id)
    val form = call.formMap()
    resource.update!!.invoke(database, id, form).fold(
      onSuccess = { call.respondRedirect("/$slug/$idPath") },
      onFailure = { error ->
        when (error) {
          is ConcurrentModificationException -> call.respondConflict()
          else -> {
            val message = createFormErrorMessage(error) ?: return@fold call.respondDaoError(error)
            call.respondHtml(HttpStatusCode.BadRequest) {
              adminPage("Edit ${resource.title}", topLevelResources = registry.topLevel) {
                h1 { +"Edit ${resource.title} $idPath" }
                p("error") { +message }
                renderForm(
                  action = "/$slug/$idPath",
                  editableFields = resource.fields,
                  values = form,
                  version = form["version"]?.toIntOrNull(),
                  submitLabel = "Save",
                )
              }
            }
          }
        }
      },
    )
  }

  get("/$slug/{id}") {
    val id = resource.parseId(call.pathId()) ?: return@get call.respondNotFound()
    resource.get(database, id, includeDeleted = true).fold(
      onSuccess = { row ->
        resource.resolveEdges(database, row).fold(
          onSuccess = { edges ->
            call.respondHtml {
              adminPage(resource.title, topLevelResources = registry.topLevel) {
                renderDetail(resource, row, edges)
              }
            }
          },
          onFailure = { call.respondDaoError(it) },
        )
      },
      onFailure = { call.respondDaoError(it) },
    )
  }
}

private suspend fun ApplicationCall.formMap(): Map<String, String> {
  val params = receiveParameters()
  return params.names().associateWith { params[it] ?: "" }
}

private fun ApplicationCall.pathId(): String = parameters["id"].orEmpty()

private fun ApplicationCall.offset(): Int = request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

/** Returns a form-level error message for create/update constraint failures, or null if not a form error. */
private fun createFormErrorMessage(error: Throwable): String? =
  when (error) {
    is DuplicateEmailException -> "Email already in use."
    is ConstraintViolationException -> "A field violates a database constraint."
    is IllegalArgumentException -> error.message ?: "Invalid input."
    else -> null
  }
