package kr.pincoin.keycloak.recaptcha.authenticator

import kr.pincoin.keycloak.recaptcha.service.RecaptchaApiClient
import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.slf4j.LoggerFactory

class RecaptchaAuthenticator : Authenticator {
    private val logger = LoggerFactory.getLogger(RecaptchaAuthenticator::class.java)

    companion object {
        const val PROVIDER_ID = "recaptcha-pincoin"
        private const val RECAPTCHA_RESPONSE_PARAM = "g-recaptcha-response"
        private const val SITE_KEY_CONFIG = "siteKey"
        private const val SECRET_KEY_CONFIG = "secretKey"
        private const val MIN_SCORE_CONFIG = "minScore"
        private const val RECAPTCHA_TYPE_CONFIG = "recaptchaType"
        private const val ENABLED_CONFIG = "enabled"
    }

    override fun authenticate(context: AuthenticationFlowContext) {
        try {
            // reCAPTCHA 비활성화 상태면 통과
            if (!isRecaptchaEnabled(context)) {
                logger.debug("reCAPTCHA가 비활성화되어 있어 검증을 건너뜁니다")
                context.success()
                return
            }

            val httpRequest = context.httpRequest
            val formData = httpRequest.decodedFormParameters

            // GET 요청이거나 reCAPTCHA 토큰이 없으면 폼 표시
            if (!formData.containsKey(RECAPTCHA_RESPONSE_PARAM)) {
                showRecaptchaForm(context)
                return
            }

            val recaptchaResponse = formData.getFirst(RECAPTCHA_RESPONSE_PARAM)
            if (recaptchaResponse.isNullOrBlank()) {
                showRecaptchaForm(context, "reCAPTCHA 검증이 필요합니다")
                return
            }

            // reCAPTCHA 검증
            if (verifyRecaptcha(context, recaptchaResponse)) {
                logger.debug("reCAPTCHA 검증 성공")
                context.success()
            } else {
                logger.warn("reCAPTCHA 검증 실패")
                showRecaptchaForm(context, "reCAPTCHA 검증에 실패했습니다")
            }
        } catch (e: Exception) {
            logger.error("reCAPTCHA 인증 중 예상치 못한 오류: ${e.message}", e)
            showRecaptchaForm(context, "인증 중 오류가 발생했습니다")
        }
    }

    private fun verifyRecaptcha(context: AuthenticationFlowContext, token: String): Boolean {
        val secretKey = getConfigValue(context, SECRET_KEY_CONFIG)
        if (secretKey.isNullOrBlank()) {
            logger.error("reCAPTCHA Secret Key가 설정되지 않았습니다")
            return false
        }

        val remoteIp = getClientIp(context)

        // try-with-resources 패턴 사용
        return RecaptchaApiClient(secretKey).use { apiClient ->
            val result = apiClient.verifyToken(token, remoteIp)
            if (result == null) {
                logger.warn("reCAPTCHA API 호출 실패")
                return false
            }

            // 기본 성공 여부 확인
            if (!result.success) {
                logger.warn("reCAPTCHA 검증 실패: ${result.errorCodes}")
                return false
            }

            // v3인 경우 점수 확인
            val recaptchaType = getConfigValue(context, RECAPTCHA_TYPE_CONFIG) ?: "v2"
            if (recaptchaType == "v3" && result.score != null) {
                val minScore = getConfigValue(context, MIN_SCORE_CONFIG)?.toDoubleOrNull() ?: 0.5
                if (result.score < minScore) {
                    logger.warn("reCAPTCHA 점수 부족: ${result.score} < $minScore")
                    return false
                }
                logger.debug("reCAPTCHA v3 점수: ${result.score}")
            }

            true
        }
    }

    private fun showRecaptchaForm(context: AuthenticationFlowContext, error: String? = null) {
        val siteKey = getConfigValue(context, SITE_KEY_CONFIG)
        val recaptchaType = getConfigValue(context, RECAPTCHA_TYPE_CONFIG) ?: "v2"

        // Site Key 검증
        if (siteKey.isNullOrBlank()) {
            logger.error("reCAPTCHA Site Key가 설정되지 않았습니다")
            val response = context.form()
                .setError("reCAPTCHA 설정 오류")
                .createForm("recaptcha-form.ftl")  // createErrorPage 대신 createForm 사용
            context.challenge(response)
            return
        }

        val response = context.form()
            .setAttribute("siteKey", siteKey)
            .setAttribute("recaptchaType", recaptchaType)
            .setError(error)
            .createForm("recaptcha-form.ftl")

        context.challenge(response)
    }

    private fun getClientIp(context: AuthenticationFlowContext): String? {
        val httpRequest = context.httpRequest
        val headers = httpRequest.httpHeaders

        // 우선순위에 따른 IP 추출
        return sequenceOf(
            "CF-Connecting-IP", // Cloudflare
            "X-Forwarded-For", // 일반적인 프록시
            "X-Real-IP", // Nginx
            "X-Client-IP", // Apache
            "X-Cluster-Client-IP", // 클러스터
        ).mapNotNull { headerName ->
            headers.getHeaderString(headerName)
                ?.split(",")
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "unknown" }
        }.firstOrNull()
    }

    private fun getConfigValue(context: AuthenticationFlowContext, key: String): String? =
        context.authenticatorConfig?.config?.get(key)

    private fun isRecaptchaEnabled(context: AuthenticationFlowContext): Boolean {
        val enabled = getConfigValue(context, ENABLED_CONFIG)
        return enabled.isNullOrBlank() || enabled.toBoolean()
    }

    override fun action(context: AuthenticationFlowContext) {
        authenticate(context)
    }

    override fun requiresUser(): Boolean = false

    override fun configuredFor(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel
    ): Boolean = true

    override fun setRequiredActions(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel
    ) {
        // reCAPTCHA는 Required Action이 필요하지 않음
    }

    override fun close() {
        // Authenticator는 stateless이므로 정리할 리소스 없음
    }
}