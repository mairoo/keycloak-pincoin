package kr.pincoin.keycloak.adapter

data class SocialAccount(
    val id: Long,
    val provider: String,
    val uid: String,
    val userId: Long,
    val dateJoined: java.time.LocalDateTime,
    val extraData: String = "{}",
)