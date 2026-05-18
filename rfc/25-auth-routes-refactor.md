# RFC-25: Auth Routes Refactor

## Executive Summary
This RFC proposes refactoring the authentication routing logic in `AuthRoutes.kt` to enforce the practice of keeping functions short. We will extract the inline procedural code blocks for each route (`/register`, `/me`, and `/logout`) into smaller, discrete, private helper functions within the same file.

## Detailed Design
We will refactor `AuthRoutes.kt` to define an `AuthRouteHandler` class (or similar). This class will encapsulate the dependencies (`AuthService`, `SessionConfig`, etc.) that were previously passed to the top-level extension function. 

Inside the class, we will define a public function (e.g., `fun registerRoutes(route: Route)`) that sets up the routing blocks, and delegate the logic of each route to private suspend functions that are extensions on `PipelineContext<Unit, ApplicationCall>`.

Example signature:
```kotlin
class AuthRouteHandler(
  private val authService: AuthService,
  private val sessionConfig: SessionConfig,
) {
  fun registerRoutes(route: Route) {
     route.route("/api/v1/auth") {
        post("/register") { handleRegister() }
        route("/me") { get { handleMe() } }
        route("/logout") { post { handleLogout() } }
     }
  }

  private suspend fun PipelineContext<Unit, ApplicationCall>.handleRegister() { ... }
  private suspend fun PipelineContext<Unit, ApplicationCall>.handleMe() { ... }
  private suspend fun PipelineContext<Unit, ApplicationCall>.handleLogout() { ... }
}
```

Since `database` and `tokenGenerator` are no longer needed by `AuthRouteHandler`, we will also remove them from the overarching `Application.configureRouting` extension function in `Routing.kt`. The caller in `Application.kt` will be updated to stop passing these unused dependencies into the routing layer.

## Tests
Since this is purely a structural refactoring that moves logic without changing its behavior, the existing integration tests for authentication routes must continue to pass without any modifications. We will verify correctness by ensuring all existing tests in `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` pass.

## Implementation Plan
1. **Refactor `AuthRoutes.kt`:**
   - Define `class AuthRouteHandler(private val authService: AuthService, private val sessionConfig: SessionConfig)`. Note that the unused `database` and `tokenGenerator` parameters from the old function will be dropped.
   - Define `fun registerRoutes(route: Route)` to configure the Ktor routing tree for `/api/v1/auth`.
   - Extract the inline logic for `/register`, `/me`, and `/logout` into private `suspend fun PipelineContext<Unit, ApplicationCall>.handleX()` methods.
   - **Verification:** Run `./gradlew :rest-server:compileKotlin` to ensure syntax is correct (though routing will fail to link until step 2).
2. **Update Routing Configuration:**
   - Modify `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` to instantiate `AuthRouteHandler` and call `registerRoutes(this)` instead of `authRoutes(...)`.
   - Update the `Application.configureRouting` function signature in `Routing.kt` to remove the unused `database` and `tokenGenerator` parameters.
   - Update the caller of `configureRouting` in `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` to stop passing the `database` and `tokenGenerator` parameters.
   - **Verification:** Run `./gradlew :rest-server:compileKotlin` and `./gradlew :rest-server:test` to ensure the project compiles and all route tests pass.
3. **Update Specifications:**
   - Update `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SPEC.md` to reflect `AuthRouteHandler.registerRoutes`, and remove `Database` and `TokenGenerator` from the injected dependencies list since they are no longer used by the routing layer.
   - Update `rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md` and `rest-server/src/main/kotlin/ed/unicoach/rest/auth/SPEC.md` to reference the new handler class rather than the top-level function.
   - **Verification:** No compilation required; visually verify the SPEC files accurately reflect the new code structure.

## Files Modified
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SPEC.md`
- `rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md`
- `rest-server/src/main/kotlin/ed/unicoach/rest/auth/SPEC.md`
