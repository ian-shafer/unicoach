package ed.unicoach.rest.models

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String
)
