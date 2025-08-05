package kr.pincoin.keycloak.authentication.smsotp

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration

class SmsOtpAuthenticator : Authenticator {

    companion object {
        // Redis 설정
        const val REDIS_HOST_CONFIG = "redis.host"
        const val REDIS_PORT_CONFIG = "redis.port"
        const val REDIS_PASSWORD_CONFIG = "redis.password"
        const val REDIS_DATABASE_CONFIG = "redis.database"

        // OTP 설정
        const val OTP_LENGTH_CONFIG = "otp.length"
        const val OTP_EXPIRY_MINUTES_CONFIG = "otp.expiry.minutes"
        const val MAX_ATTEMPTS_CONFIG = "max.attempts"
        const val RESEND_COOLDOWN_SECONDS_CONFIG = "resend.cooldown.seconds"

        // SMS API 설정 (Aligo)
        const val SMS_API_BASE_URL_CONFIG = "sms.api.base.url"
        const val SMS_API_KEY_CONFIG = "sms.api.key"
        const val SMS_API_USER_ID_CONFIG = "sms.api.user.id"
        const val SMS_API_SENDER_CONFIG = "sms.api.sender"

        // 기본값
        const val DEFAULT_OTP_LENGTH = 6
        const val DEFAULT_OTP_EXPIRY_MINUTES = 3
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RESEND_COOLDOWN_SECONDS = 60
        const val DEFAULT_SMS_API_BASE_URL = "https://apis.aligo.in"

        // Redis 키 타입
        const val KEY_TYPE_OTP = "otp"
        const val KEY_TYPE_ATTEMPTS = "att"
        const val KEY_TYPE_RESEND = "res"
    }

    private var jedisPool: JedisPool? = null
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun authenticate(context: AuthenticationFlowContext) {
        val user = context.user
        val phoneNumber = getUserPhoneNumber(user)

        if (phoneNumber.isNullOrBlank()) {
            handleError(context, "휴대전화 번호가 등록되지 않았습니다")
            return
        }

        if (!isValidPhoneNumber(phoneNumber)) {
            handleError(context, "올바른 휴대전화 번호 형식이 아닙니다")
            return
        }

        initializeRedisPool(context)

        // 기존 OTP 상태 확인
        val otpStatus = checkOTPStatus(context, user.id)
        when (otpStatus.status) {
            OTPStatus.ACTIVE -> {
                showOTPForm(context, phoneNumber, "이미 발송된 인증 코드를 입력하세요")
                return
            }

            OTPStatus.MAX_ATTEMPTS_EXCEEDED -> {
                handleError(context, "최대 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요")
                return
            }

            OTPStatus.RESEND_COOLDOWN -> {
                showOTPForm(context, phoneNumber, "${otpStatus.cooldownSeconds}초 후에 재발송이 가능합니다")
                return
            }

            OTPStatus.READY -> {
                // 새 OTP 생성 및 발송
                sendNewOTP(context, user, phoneNumber)
            }
        }
    }

    override fun action(context: AuthenticationFlowContext) {
        val formData = context.httpRequest.decodedFormParameters
        val inputOtp = formData.getFirst("otp")?.trim()
        val action = formData.getFirst("action")

        when (action) {
            "resend" -> handleResend(context)
            else -> handleVerification(context, inputOtp)
        }
    }

    private fun sendNewOTP(context: AuthenticationFlowContext, user: UserModel, phoneNumber: String) {
        try {
            val otp = generateOTP(context)

            // 1단계: SMS 발송 (실패 가능성이 높은 작업을 먼저)
            sendOTPSms(context, phoneNumber, otp)

            // 2단계: 발송 성공 시에만 Redis에 저장
            val result = storeOTPAtomic(context, user.id, otp)
            if (result) {
                showOTPForm(context, phoneNumber, "인증 코드가 발송되었습니다")
            } else {
                handleError(context, "인증 코드 저장에 실패했습니다")
            }
        } catch (e: Exception) {
            println("SMS OTP 발송 실패: ${e.message}")
            handleError(context, "인증 코드 발송에 실패했습니다")
        }
    }

    private fun handleVerification(context: AuthenticationFlowContext, inputOtp: String?) {
        val user = context.user
        if (user == null) {
            context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_USER)
            return
        }

        val phoneNumber = getUserPhoneNumber(user) ?: ""

        if (inputOtp.isNullOrBlank()) {
            showOTPForm(context, phoneNumber, "인증 코드를 입력해주세요")
            return
        }

        // 원자적 OTP 검증 및 시도 횟수 관리
        val verificationResult = verifyOTPAtomic(context, user.id, inputOtp)

        when (verificationResult.status) {
            VerificationStatus.SUCCESS -> {
                context.success()
            }

            VerificationStatus.INVALID_CODE -> {
                showOTPForm(context, phoneNumber, "잘못된 인증 코드입니다")
            }

            VerificationStatus.EXPIRED -> {
                showOTPForm(context, phoneNumber, "인증 코드가 만료되었습니다. 재발송을 클릭해주세요")
            }

            VerificationStatus.MAX_ATTEMPTS -> {
                handleError(context, "최대 시도 횟수를 초과했습니다")
            }
        }
    }

    private fun handleResend(context: AuthenticationFlowContext) {
        val user = context.user
        if (user == null) {
            context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_USER)
            return
        }

        val phoneNumber = getUserPhoneNumber(user) ?: ""

        // 재발송 가능 여부 확인 및 처리
        val resendResult = attemptResend(context, user.id)

        when (resendResult.status) {
            ResendStatus.SUCCESS -> {
                try {
                    val otp = generateOTP(context)
                    sendOTPSms(context, phoneNumber, otp)
                    updateOTPAfterResend(context, user.id, otp)
                    showOTPForm(context, phoneNumber, "인증 코드가 재발송되었습니다")
                } catch (e: Exception) {
                    println("SMS OTP 재발송 실패: ${e.message}")
                    showOTPForm(context, phoneNumber, "인증 코드 재발송에 실패했습니다")
                }
            }

            ResendStatus.COOLDOWN -> {
                showOTPForm(context, phoneNumber, "${resendResult.cooldownSeconds}초 후에 재발송이 가능합니다")
            }

            ResendStatus.MAX_ATTEMPTS -> {
                handleError(context, "최대 시도 횟수를 초과했습니다")
            }
        }
    }

    private fun sendOTPSms(context: AuthenticationFlowContext, phoneNumber: String, otp: String) {
        val baseUrl = getSmsApiBaseUrl(context)
        val apiKey = getSmsApiKey(context)
        val userId = getSmsApiUserId(context)
        val sender = getSmsApiSender(context)

        if (apiKey.isNullOrBlank() || userId.isNullOrBlank() || sender.isNullOrBlank()) {
            throw RuntimeException("SMS API 설정이 완료되지 않았습니다")
        }

        val message =
            "[${context.realm.displayName ?: context.realm.name}] 인증번호: $otp (${getOTPExpiryMinutes(context)}분간 유효)"

        // Form 데이터 구성
        val formData = buildString {
            append("receiver=").append(URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8))
            append("&msg=").append(URLEncoder.encode(message, StandardCharsets.UTF_8))
            append("&key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8))
            append("&user_id=").append(URLEncoder.encode(userId, StandardCharsets.UTF_8))
            append("&sender=").append(URLEncoder.encode(sender, StandardCharsets.UTF_8))
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/send/"))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formData, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(30))
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

            if (response.statusCode() != 200) {
                throw RuntimeException("SMS API 응답 오류: ${response.statusCode()}")
            }

            // 응답 파싱 및 검증
            val responseBody = response.body()
            val smsResponse = parseAligoResponse(responseBody)

            if (smsResponse.resultCode != "1") {
                throw RuntimeException("SMS 발송 실패: ${smsResponse.message}")
            }

            println("SMS 발송 성공: $phoneNumber, MSG_ID: ${smsResponse.msgId}")

        } catch (e: Exception) {
            println("SMS 발송 오류: ${e.message}")
            throw e
        }
    }

    private fun parseAligoResponse(responseBody: String): AligoSmsResponse {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(responseBody, AligoSmsResponse::class.java)
        } catch (e: Exception) {
            println("SMS API 응답 파싱 실패: $responseBody")
            throw RuntimeException("SMS API 응답 파싱 실패", e)
        }
    }

    private fun getUserPhoneNumber(user: UserModel?): String? {
        if (user == null) return null

        // 사용자 속성에서 휴대전화 번호 추출
        return user.getFirstAttribute("mobile")
            ?: user.getFirstAttribute("phone")
            ?: user.getFirstAttribute("phoneNumber")
    }

    private fun isValidPhoneNumber(phoneNumber: String): Boolean {
        // 한국 휴대전화 번호 형식 검증 (010, 011, 016, 017, 018, 019)
        val phoneRegex = "^01[016789]\\d{7,8}$".toRegex()
        return phoneRegex.matches(phoneNumber.replace("-", ""))
    }

    private fun initializeRedisPool(context: AuthenticationFlowContext) {
        if (jedisPool == null) {
            val config = context.authenticatorConfig?.config

            val host = config?.get(REDIS_HOST_CONFIG) ?: "localhost"
            val port = config?.get(REDIS_PORT_CONFIG)?.toIntOrNull() ?: 6379
            val password = config?.get(REDIS_PASSWORD_CONFIG)
            val database = config?.get(REDIS_DATABASE_CONFIG)?.toIntOrNull() ?: 0

            val poolConfig = JedisPoolConfig().apply {
                maxTotal = 10
                maxIdle = 5
                minIdle = 1
                testOnBorrow = true
                testOnReturn = true
            }

            jedisPool = if (password.isNullOrBlank()) {
                JedisPool(poolConfig, host, port, 2000, null, database)
            } else {
                JedisPool(poolConfig, host, port, 2000, password, database)
            }
        }
    }

    private fun generateOTP(context: AuthenticationFlowContext): String {
        val length = getOTPLength(context)
        val random = SecureRandom()

        return buildString {
            repeat(length) {
                append(random.nextInt(10))
            }
        }
    }

    // Redis 관련 메서드들 (EmailOtpAuthenticator와 동일한 Lua 스크립트 방식)
    private fun checkOTPStatus(context: AuthenticationFlowContext, userId: String): OTPStatusResult {
        val otpKey = buildRedisKey(KEY_TYPE_OTP, context.realm.name, userId)
        val attemptsKey = buildRedisKey(KEY_TYPE_ATTEMPTS, context.realm.name, userId)
        val resendKey = buildRedisKey(KEY_TYPE_RESEND, context.realm.name, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val luaScript = """
                    local otp_key = KEYS[1]
                    local attempts_key = KEYS[2]
                    local resend_key = KEYS[3]
                    local max_attempts = tonumber(ARGV[1])
                    
                    local otp = redis.call('GET', otp_key)
                    local attempts = tonumber(redis.call('GET', attempts_key) or 0)
                    local resend_ttl = redis.call('TTL', resend_key)
                    
                    if attempts >= max_attempts then
                        return {'MAX_ATTEMPTS'}
                    end
                    
                    if otp then
                        return {'ACTIVE'}
                    end
                    
                    if resend_ttl > 0 then
                        return {'RESEND_COOLDOWN', resend_ttl}
                    end
                    
                    return {'READY'}
                """.trimIndent()

                val rawResult = jedis.eval(
                    luaScript,
                    3,
                    otpKey, attemptsKey, resendKey,
                    getMaxAttempts(context).toString()
                )

                parseOTPStatusResult(rawResult)
            } ?: OTPStatusResult(OTPStatus.READY)
        } catch (e: Exception) {
            println("OTP 상태 확인 실패: ${e.message}")
            OTPStatusResult(OTPStatus.READY)
        }
    }

    private fun storeOTPAtomic(context: AuthenticationFlowContext, userId: String, otp: String): Boolean {
        val otpKey = buildRedisKey(KEY_TYPE_OTP, context.realm.name, userId)
        val expiry = (getOTPExpiryMinutes(context) * 60).toLong()

        return try {
            jedisPool?.resource?.use { jedis ->
                jedis.setex(otpKey, expiry, otp)
                true
            } ?: false
        } catch (e: Exception) {
            println("OTP 저장 실패: ${e.message}")
            false
        }
    }

    private fun verifyOTPAtomic(
        context: AuthenticationFlowContext,
        userId: String,
        inputOtp: String
    ): VerificationResult {
        val otpKey = buildRedisKey(KEY_TYPE_OTP, context.realm.name, userId)
        val attemptsKey = buildRedisKey(KEY_TYPE_ATTEMPTS, context.realm.name, userId)
        val resendKey = buildRedisKey(KEY_TYPE_RESEND, context.realm.name, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val luaScript = """
                    local otp_key = KEYS[1]
                    local attempts_key = KEYS[2]
                    local resend_key = KEYS[3]
                    local input_otp = ARGV[1]
                    local max_attempts = tonumber(ARGV[2])
                    local expiry = tonumber(ARGV[3])
                    
                    local stored_otp = redis.call('GET', otp_key)
                    local attempts = tonumber(redis.call('GET', attempts_key) or 0)
                    
                    if not stored_otp then
                        return {'EXPIRED'}
                    end
                    
                    if attempts >= max_attempts then
                        return {'MAX_ATTEMPTS'}
                    end
                    
                    if stored_otp == input_otp then
                        redis.call('DEL', otp_key, attempts_key, resend_key)
                        return {'SUCCESS'}
                    else
                        redis.call('INCR', attempts_key)
                        redis.call('EXPIRE', attempts_key, expiry)
                        
                        local new_attempts = attempts + 1
                        if new_attempts >= max_attempts then
                            return {'MAX_ATTEMPTS'}
                        else
                            return {'INVALID_CODE'}
                        end
                    end
                """.trimIndent()

                val rawResult = jedis.eval(
                    luaScript,
                    3,
                    otpKey, attemptsKey, resendKey,
                    inputOtp,
                    getMaxAttempts(context).toString(),
                    (getOTPExpiryMinutes(context) * 60).toString()
                )

                parseVerificationResult(rawResult)
            } ?: VerificationResult(VerificationStatus.EXPIRED)
        } catch (e: Exception) {
            println("OTP 검증 실패: ${e.message}")
            VerificationResult(VerificationStatus.EXPIRED)
        }
    }

    private fun attemptResend(context: AuthenticationFlowContext, userId: String): ResendResult {
        val attemptsKey = buildRedisKey(KEY_TYPE_ATTEMPTS, context.realm.name, userId)
        val resendKey = buildRedisKey(KEY_TYPE_RESEND, context.realm.name, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val luaScript = """
                    local attempts_key = KEYS[1]
                    local resend_key = KEYS[2]
                    local max_attempts = tonumber(ARGV[1])
                    local cooldown = tonumber(ARGV[2])
                    
                    local attempts = tonumber(redis.call('GET', attempts_key) or 0)
                    local resend_ttl = redis.call('TTL', resend_key)
                    
                    if attempts >= max_attempts then
                        return {'MAX_ATTEMPTS'}
                    end
                    
                    if resend_ttl > 0 then
                        return {'COOLDOWN', resend_ttl}
                    end
                    
                    redis.call('SETEX', resend_key, cooldown, '1')
                    return {'SUCCESS'}
                """.trimIndent()

                val rawResult = jedis.eval(
                    luaScript,
                    2,
                    attemptsKey, resendKey,
                    getMaxAttempts(context).toString(),
                    getResendCooldown(context).toString()
                )

                parseResendResult(rawResult)
            } ?: ResendResult(ResendStatus.SUCCESS)
        } catch (e: Exception) {
            println("재발송 확인 실패: ${e.message}")
            ResendResult(ResendStatus.SUCCESS)
        }
    }

    private fun updateOTPAfterResend(context: AuthenticationFlowContext, userId: String, otp: String) {
        val otpKey = buildRedisKey(KEY_TYPE_OTP, context.realm.name, userId)
        val expiry = (getOTPExpiryMinutes(context) * 60).toLong()

        try {
            jedisPool?.resource?.use { jedis ->
                jedis.setex(otpKey, expiry, otp)
            }
        } catch (e: Exception) {
            println("OTP 업데이트 실패: ${e.message}")
        }
    }

    private fun showOTPForm(context: AuthenticationFlowContext, phoneNumber: String, errorMessage: String?) {
        val form = context.form()

        if (errorMessage != null) {
            form.setError(errorMessage)
        }

        // 휴대전화 번호 마스킹 (010-1234-5678 -> 010-****-5678)
        val maskedPhone = maskPhoneNumber(phoneNumber)

        form.setAttribute("phoneNumber", maskedPhone)
        form.setAttribute("expiryMinutes", getOTPExpiryMinutes(context).toString())

        val challenge = form.createForm("sms-otp-form.ftl")
        context.challenge(challenge)
    }

    private fun maskPhoneNumber(phoneNumber: String): String {
        return if (phoneNumber.length >= 8) {
            val cleaned = phoneNumber.replace("-", "")
            "${cleaned.substring(0, 3)}-****-${cleaned.substring(cleaned.length - 4)}"
        } else {
            phoneNumber
        }
    }

    private fun handleError(context: AuthenticationFlowContext, errorMessage: String) {
        context.event.error(org.keycloak.events.Errors.NOT_ALLOWED)
        val response = Response.status(400)
            .entity(createErrorHtml(errorMessage))
            .type(MediaType.TEXT_HTML)
            .build()
        context.challenge(response)
    }

    private fun createErrorHtml(errorMessage: String): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>인증 오류</title>
            <style>
                body { font-family: Arial, sans-serif; text-align: center; margin-top: 100px; }
                .error-container { max-width: 400px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
                .error-title { color: #d32f2f; font-size: 24px; margin-bottom: 20px; }
                .error-message { color: #666; font-size: 16px; line-height: 1.5; }
            </style>
        </head>
        <body>
            <div class="error-container">
                <div class="error-title">SMS 인증 오류</div>
                <div class="error-message">$errorMessage</div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildRedisKey(type: String, realm: String, identifier: String): String =
        "sms_otp:$type:$realm:$identifier"

    // 결과 파싱 헬퍼 메서드들 (EmailOtpAuthenticator와 동일)
    private fun parseOTPStatusResult(rawResult: Any?): OTPStatusResult {
        return when (rawResult) {
            is List<*> -> {
                val status = rawResult[0] as? String
                when (status) {
                    "MAX_ATTEMPTS" -> OTPStatusResult(OTPStatus.MAX_ATTEMPTS_EXCEEDED)
                    "ACTIVE" -> OTPStatusResult(OTPStatus.ACTIVE)
                    "RESEND_COOLDOWN" -> {
                        val cooldown = (rawResult.getOrNull(1) as? Number)?.toInt() ?: 0
                        OTPStatusResult(OTPStatus.RESEND_COOLDOWN, cooldown)
                    }

                    else -> OTPStatusResult(OTPStatus.READY)
                }
            }

            else -> OTPStatusResult(OTPStatus.READY)
        }
    }

    private fun parseVerificationResult(rawResult: Any?): VerificationResult {
        return when (rawResult) {
            is List<*> -> {
                val status = rawResult[0] as? String
                when (status) {
                    "SUCCESS" -> VerificationResult(VerificationStatus.SUCCESS)
                    "INVALID_CODE" -> VerificationResult(VerificationStatus.INVALID_CODE)
                    "EXPIRED" -> VerificationResult(VerificationStatus.EXPIRED)
                    "MAX_ATTEMPTS" -> VerificationResult(VerificationStatus.MAX_ATTEMPTS)
                    else -> VerificationResult(VerificationStatus.EXPIRED)
                }
            }

            else -> VerificationResult(VerificationStatus.EXPIRED)
        }
    }

    private fun parseResendResult(rawResult: Any?): ResendResult {
        return when (rawResult) {
            is List<*> -> {
                val status = rawResult[0] as? String
                when (status) {
                    "SUCCESS" -> ResendResult(ResendStatus.SUCCESS)
                    "MAX_ATTEMPTS" -> ResendResult(ResendStatus.MAX_ATTEMPTS)
                    "COOLDOWN" -> {
                        val cooldown = (rawResult.getOrNull(1) as? Number)?.toInt() ?: 0
                        ResendResult(ResendStatus.COOLDOWN, cooldown)
                    }

                    else -> ResendResult(ResendStatus.SUCCESS)
                }
            }

            else -> ResendResult(ResendStatus.SUCCESS)
        }
    }

    // 설정값 가져오기 메서드들
    private fun getOTPLength(context: AuthenticationFlowContext): Int =
        context.authenticatorConfig?.config?.get(OTP_LENGTH_CONFIG)?.toIntOrNull() ?: DEFAULT_OTP_LENGTH

    private fun getOTPExpiryMinutes(context: AuthenticationFlowContext): Int =
        context.authenticatorConfig?.config?.get(OTP_EXPIRY_MINUTES_CONFIG)?.toIntOrNull() ?: DEFAULT_OTP_EXPIRY_MINUTES

    private fun getMaxAttempts(context: AuthenticationFlowContext): Int =
        context.authenticatorConfig?.config?.get(MAX_ATTEMPTS_CONFIG)?.toIntOrNull() ?: DEFAULT_MAX_ATTEMPTS

    private fun getResendCooldown(context: AuthenticationFlowContext): Int =
        context.authenticatorConfig?.config?.get(RESEND_COOLDOWN_SECONDS_CONFIG)?.toIntOrNull()
            ?: DEFAULT_RESEND_COOLDOWN_SECONDS

    private fun getSmsApiBaseUrl(context: AuthenticationFlowContext): String =
        context.authenticatorConfig?.config?.get(SMS_API_BASE_URL_CONFIG) ?: DEFAULT_SMS_API_BASE_URL

    private fun getSmsApiKey(context: AuthenticationFlowContext): String? =
        context.authenticatorConfig?.config?.get(SMS_API_KEY_CONFIG)

    private fun getSmsApiUserId(context: AuthenticationFlowContext): String? =
        context.authenticatorConfig?.config?.get(SMS_API_USER_ID_CONFIG)

    private fun getSmsApiSender(context: AuthenticationFlowContext): String? =
        context.authenticatorConfig?.config?.get(SMS_API_SENDER_CONFIG)

    override fun requiresUser(): Boolean = true
    override fun configuredFor(session: KeycloakSession, realm: RealmModel, user: UserModel): Boolean {
        val phoneNumber = getUserPhoneNumber(user)
        return !phoneNumber.isNullOrBlank() && isValidPhoneNumber(phoneNumber)
    }

    override fun setRequiredActions(session: KeycloakSession, realm: RealmModel, user: UserModel) = Unit
    override fun close() {
        jedisPool?.close()
    }

    // 결과 데이터 클래스들
    data class OTPStatusResult(val status: OTPStatus, val cooldownSeconds: Int = 0)
    data class VerificationResult(val status: VerificationStatus)
    data class ResendResult(val status: ResendStatus, val cooldownSeconds: Int = 0)
    data class AligoSmsResponse(
        val result_code: String,
        val message: String,
        val msg_id: String,
        val success_cnt: String,
        val error_cnt: String,
        val msg_type: String
    ) {
        val resultCode: String get() = result_code
        val msgId: String get() = msg_id
    }

    enum class OTPStatus {
        READY, ACTIVE, MAX_ATTEMPTS_EXCEEDED, RESEND_COOLDOWN
    }

    enum class VerificationStatus {
        SUCCESS, INVALID_CODE, EXPIRED, MAX_ATTEMPTS
    }

    enum class ResendStatus {
        SUCCESS, COOLDOWN, MAX_ATTEMPTS
    }
}