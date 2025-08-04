package kr.pincoin.keycloak.recaptcha.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class RecaptchaVerifyResponse(
    val success: Boolean,

    val score: Double? = null,

    val action: String? = null,

    val hostname: String? = null,

    @param:JsonProperty("challenge_ts")
    val challengeTs: String? = null,

    @param:JsonProperty("error-codes")
    val errorCodes: List<String>? = null,
)