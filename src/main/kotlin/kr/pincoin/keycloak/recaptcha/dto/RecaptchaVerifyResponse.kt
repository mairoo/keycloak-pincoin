package kr.pincoin.keycloak.recaptcha.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class RecaptchaVerifyResponse(
    val success: Boolean,

    val score: Double? = null,

    val action: String? = null,

    val hostname: String? = null,

    @JsonProperty("challenge_ts")
    val challengeTs: String? = null,

    @JsonProperty("error-codes")
    val errorCodes: List<String>? = null,
)