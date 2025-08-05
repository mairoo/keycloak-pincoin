package kr.pincoin.keycloak.authentication.recaptcha

import com.fasterxml.jackson.databind.ObjectMapper
import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RecaptchaAuthenticator : Authenticator {

    companion object {
        const val RECAPTCHA_RESPONSE_PARAM = "g-recaptcha-response"
        const val RECAPTCHA_SITE_KEY_CONFIG = "site.key"
        const val RECAPTCHA_SECRET_KEY_CONFIG = "secret.key"
        const val RECAPTCHA_VERSION_CONFIG = "version"
        const val RECAPTCHA_MIN_SCORE_CONFIG = "min.score"
        const val RECAPTCHA_ACTION_CONFIG = "action"
        const val RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify"
    }

    override fun authenticate(context: AuthenticationFlowContext) {
        val siteKey = getSiteKey(context)

        if (siteKey.isNullOrBlank()) {
            context.success()
            return
        }

        val version = getVersion(context)
        val action = getAction(context)

        val challenge = context.form()
            .setAttribute("recaptchaSiteKey", siteKey)
            .setAttribute("recaptchaVersion", version)
            .setAttribute("recaptchaAction", action)
            .createForm("recaptcha-form.ftl")

        context.challenge(challenge)
    }

    override fun action(context: AuthenticationFlowContext) {
        val formData = context.httpRequest.decodedFormParameters
        val recaptchaResponse = formData.getFirst(RECAPTCHA_RESPONSE_PARAM)

        if (recaptchaResponse.isNullOrBlank()) {
            handleError(context, "reCAPTCHA 검증이 필요합니다")
            return
        }

        if (verifyRecaptcha(context, recaptchaResponse)) {
            context.success()
        } else {
            handleError(context, "reCAPTCHA 검증에 실패했습니다")
        }
    }

    private fun verifyRecaptcha(context: AuthenticationFlowContext, recaptchaResponse: String): Boolean {
        val secretKey = getSecretKey(context)
        if (secretKey.isNullOrBlank()) {
            return true
        }

        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()

            val formData = "secret=$secretKey&response=$recaptchaResponse"

            val request = HttpRequest.newBuilder()
                .uri(URI.create(RECAPTCHA_VERIFY_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                parseAndValidateResponse(context, response.body())
            } else {
                // 데이터베이스 이벤트 로깅 제거
                false
            }
        } catch (e: Exception) {
            // 데이터베이스 이벤트 로깅 제거, 콘솔 로그만 사용
            println("reCAPTCHA 검증 오류: ${e.message}")
            false
        }
    }

    private fun parseAndValidateResponse(context: AuthenticationFlowContext, responseBody: String): Boolean {
        return try {
            val objectMapper = ObjectMapper()
            // Map으로 파싱하여 Jackson 호환성 문제 해결
            val responseMap = objectMapper.readValue(responseBody, Map::class.java) as Map<*, *>

            val success = responseMap["success"] as? Boolean ?: false
            if (!success) {
                val errorCodes = (responseMap["error-codes"] as? List<*>)?.joinToString(", ") ?: "알 수 없는 오류"
                println("reCAPTCHA 검증 실패: $errorCodes")
                return false
            }

            // v3 점수 검증
            val score = responseMap["score"] as? Double
            if (score != null) {
                val minScore = getMinScore(context)
                if (score < minScore) {
                    println("reCAPTCHA 점수 부족: $score < $minScore")
                    return false
                }
            }

            // action 검증
            val expectedAction = getAction(context)
            if (getVersion(context) == "v3" && expectedAction.isNotBlank()) {
                val actualAction = responseMap["action"] as? String
                if (actualAction != expectedAction) {
                    println("reCAPTCHA action 불일치: expected=$expectedAction, actual=$actualAction")
                    return false
                }
            }

            true
        } catch (e: Exception) {
            println("reCAPTCHA 응답 파싱 실패: ${e.message}")
            false
        }
    }

    private fun handleError(context: AuthenticationFlowContext, errorMessage: String) {
        val siteKey = getSiteKey(context)
        val version = getVersion(context)
        val action = getAction(context)

        val challenge = context.form()
            .setError(errorMessage)
            .setAttribute("recaptchaSiteKey", siteKey)
            .setAttribute("recaptchaVersion", version)
            .setAttribute("recaptchaAction", action)
            .createForm("recaptcha-form.ftl")

        context.challenge(challenge)
    }

    private fun getSiteKey(context: AuthenticationFlowContext): String? {
        return context.authenticatorConfig?.config?.get(RECAPTCHA_SITE_KEY_CONFIG)
    }

    private fun getSecretKey(context: AuthenticationFlowContext): String? {
        return context.authenticatorConfig?.config?.get(RECAPTCHA_SECRET_KEY_CONFIG)
    }

    private fun getVersion(context: AuthenticationFlowContext): String {
        return context.authenticatorConfig?.config?.get(RECAPTCHA_VERSION_CONFIG) ?: "v2"
    }

    private fun getMinScore(context: AuthenticationFlowContext): Double {
        return context.authenticatorConfig?.config?.get(RECAPTCHA_MIN_SCORE_CONFIG)?.toDoubleOrNull() ?: 0.5
    }

    private fun getAction(context: AuthenticationFlowContext): String {
        return context.authenticatorConfig?.config?.get(RECAPTCHA_ACTION_CONFIG) ?: "login"
    }

    override fun requiresUser(): Boolean = false
    override fun configuredFor(session: KeycloakSession, realm: RealmModel, user: UserModel): Boolean = true
    override fun setRequiredActions(session: KeycloakSession, realm: RealmModel, user: UserModel) {}
    override fun close() {}
}