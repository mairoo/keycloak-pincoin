package kr.pincoin.keycloak.model

data class DjangoUser(
    val id: Long,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val isActive: Boolean,
)