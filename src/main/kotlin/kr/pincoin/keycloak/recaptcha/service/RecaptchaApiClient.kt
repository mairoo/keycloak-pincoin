package kr.pincoin.keycloak.recaptcha.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.pincoin.keycloak.recaptcha.dto.RecaptchaVerifyResponse
import org.apache.http.HttpStatus
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class RecaptchaApiClient(
    private val secretKey: String,
    private val timeout: Int = 5000,
) : AutoCloseable { // AutoCloseable 구현으로 try-with-resources 지원
    private val logger = LoggerFactory.getLogger(RecaptchaApiClient::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    // CloseableHttpClient로 타입 명시
    private val httpClient: CloseableHttpClient = HttpClients.custom()
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build()
        )
        .build()

    companion object {
        private const val VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify"
    }

    fun verifyToken(
        token: String,
        remoteIp: String? = null,
    ): RecaptchaVerifyResponse? {
        // 입력 검증 추가
        if (token.isBlank()) {
            logger.warn("reCAPTCHA 토큰이 비어있습니다")
            return null
        }

        return try {
            val httpPost = HttpPost(VERIFY_URL)

            val params = buildList {
                add(BasicNameValuePair("secret", secretKey))
                add(BasicNameValuePair("response", token))
                remoteIp?.let { add(BasicNameValuePair("remoteip", it)) }
            }

            httpPost.entity = UrlEncodedFormEntity(params, StandardCharsets.UTF_8)

            httpClient.execute(httpPost).use { response ->
                val statusCode = response.statusLine.statusCode
                if (statusCode != HttpStatus.SC_OK) {
                    logger.warn("reCAPTCHA 검증 HTTP 오류: $statusCode")
                    return null
                }

                val responseBody = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)
                if (responseBody.isBlank()) {
                    logger.warn("reCAPTCHA 응답이 비어있습니다")
                    return null
                }

                logger.debug("reCAPTCHA 응답: $responseBody")
                objectMapper.readValue(responseBody, RecaptchaVerifyResponse::class.java)
            }
        } catch (e: Exception) {
            logger.warn("reCAPTCHA 검증 중 오류: ${e.message}", e)  // 스택트레이스도 로깅
            null
        }
    }

    override fun close() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            logger.debug("HttpClient 종료 중 오류: ${e.message}")
        }
    }
}