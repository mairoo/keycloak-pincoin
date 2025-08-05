package kr.pincoin.keycloak.authentication.ratelimit

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.security.MessageDigest

class RateLimitAuthenticator : Authenticator {

    companion object {
        const val REDIS_HOST_CONFIG = "redis.host"
        const val REDIS_PORT_CONFIG = "redis.port"
        const val REDIS_PASSWORD_CONFIG = "redis.password"
        const val REDIS_DATABASE_CONFIG = "redis.database"

        // Rate Limit 설정
        const val IP_LIMIT_CONFIG = "ip.limit"
        const val IP_WINDOW_CONFIG = "ip.window"
        const val USER_LIMIT_CONFIG = "user.limit"
        const val USER_WINDOW_CONFIG = "user.window"
        const val COMBINED_LIMIT_CONFIG = "combined.limit"
        const val COMBINED_WINDOW_CONFIG = "combined.window"

        // 기본값
        const val DEFAULT_IP_LIMIT = 100
        const val DEFAULT_IP_WINDOW = 3600 // 1시간
        const val DEFAULT_USER_LIMIT = 5
        const val DEFAULT_USER_WINDOW = 900 // 15분
        const val DEFAULT_COMBINED_LIMIT = 10
        const val DEFAULT_COMBINED_WINDOW = 300 // 5분

        // Redis 키 타입
        const val KEY_TYPE_IP = "i"
        const val KEY_TYPE_USER = "u"
        const val KEY_TYPE_COMBINED = "c"
    }

    private var jedisPool: JedisPool? = null

    override fun authenticate(
        context: AuthenticationFlowContext,
    ) {
        try {
            val clientIP = getClientIP(context)
            val username = getUsername(context)

            if (clientIP == null) {
                context.success()
                return
            }

            initializeRedisPool(context)

            // Rate Limit 체크
            if (isBlocked(context, clientIP, username)) {
                handleBlocked(context)
                return
            }

            // 통과 시 카운터 증가
            incrementAllCounters(context, clientIP, username)
            context.success()

        } catch (e: Exception) {
            println("Rate Limit Authenticator 오류: ${e.message}")
            context.success()
        }
    }

    override fun action(
        context: AuthenticationFlowContext,
    ) =
        context.success()

    private fun initializeRedisPool(
        context: AuthenticationFlowContext,
    ) {
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

    private fun isBlocked(
        context: AuthenticationFlowContext,
        clientIP: String,
        username: String?,
    ): Boolean {
        val realmName = context.realm.name

        // L1: IP 기준 체크
        if (isCounterExceeded(buildRedisKey(KEY_TYPE_IP, realmName, clientIP), getIPLimit(context))) {
            println("IP Rate Limit 초과: $clientIP")
            return true
        }

        // L2: 사용자 기준 체크
        if (username != null) {
            if (isCounterExceeded(buildRedisKey(KEY_TYPE_USER, realmName, username), getUserLimit(context))) {
                println("사용자 Rate Limit 초과: $username")
                return true
            }
        }

        // L3: 조합 기준 체크
        if (username != null) {
            val combinedKey = buildRedisKey(KEY_TYPE_COMBINED, realmName, "$clientIP:${hashUsername(username)}")
            if (isCounterExceeded(combinedKey, getCombinedLimit(context))) {
                println("조합 Rate Limit 초과: $clientIP + $username")
                return true
            }
        }

        return false
    }

    private fun isCounterExceeded(
        redisKey: String,
        limit: Int,
    ): Boolean =
        try {
            jedisPool?.resource?.use { jedis ->
                val current = jedis.get(redisKey)?.toIntOrNull() ?: 0
                current >= limit
            } ?: false
        } catch (e: Exception) {
            println("Redis 카운터 확인 오류: ${e.message}")
            false
        }

    private fun incrementAllCounters(
        context: AuthenticationFlowContext,
        clientIP: String,
        username: String?,
    ) =
        try {
            val realmName = context.realm.name

            jedisPool?.resource?.use { jedis ->
                // IP 카운터 증가
                incrementSingleCounter(jedis, buildRedisKey(KEY_TYPE_IP, realmName, clientIP), getIPWindow(context))

                // 사용자 관련 카운터 증가
                if (username != null) {
                    incrementSingleCounter(
                        jedis,
                        buildRedisKey(KEY_TYPE_USER, realmName, username),
                        getUserWindow(context)
                    )
                    val combinedKey = buildRedisKey(KEY_TYPE_COMBINED, realmName, "$clientIP:${hashUsername(username)}")
                    incrementSingleCounter(jedis, combinedKey, getCombinedWindow(context))
                }
            }
        } catch (e: Exception) {
            println("Rate Limit 카운터 증가 오류: ${e.message}")
        }

    private fun incrementSingleCounter(
        jedis: Jedis,
        redisKey: String,
        windowSeconds: Int,
    ) {
        val newCount = jedis.incr(redisKey)
        if (newCount == 1L) {
            jedis.expire(redisKey, windowSeconds.toLong())
        } else {
            // TTL 안전장치: jedis.expire() 호출이 실패하면
            // Redis에 TTL 없는 키가 남음 (ttl = -1)
            // 그 키는 영구히 존재하게 되어 사용자가 영원히 차단될 수 있음
            val ttl = jedis.ttl(redisKey)
            if (ttl == -1L) { // TTL이 설정되지 않은 경우
                jedis.expire(redisKey, windowSeconds.toLong())
            }
        }
    }

    private fun handleBlocked(
        context: AuthenticationFlowContext,
    ) {
        context.event.error(org.keycloak.events.Errors.NOT_ALLOWED)

        val htmlContent = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <title>요청 제한</title>
            <style>
                body { font-family: Arial, sans-serif; text-align: center; margin-top: 100px; }
                .error-container { max-width: 400px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }
                .error-title { color: #d32f2f; font-size: 24px; margin-bottom: 20px; }
                .error-message { color: #666; font-size: 16px; line-height: 1.5; }
                .retry-info { margin-top: 20px; font-size: 14px; color: #888; }
            </style>
        </head>
        <body>
            <div class="error-container">
                <div class="error-title">요청 제한 초과</div>
                <div class="error-message">
                    요청이 너무 많습니다.<br>
                    잠시 후 다시 시도해주세요.
                </div>
                <div class="retry-info">
                    5분 후에 다시 시도할 수 있습니다.
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()

        val response = Response.status(429)
            .entity(htmlContent)
            .type(MediaType.TEXT_HTML)
            .header("Retry-After", "300")
            .build()

        context.challenge(response)
    }

    private fun getClientIP(
        context: AuthenticationFlowContext,
    ): String? {
        val request = context.httpRequest

        return request.httpHeaders.getHeaderString("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.httpHeaders.getHeaderString("X-Real-IP")
            ?: request.httpHeaders.getHeaderString("X-Forwarded-Host")
            ?: context.connection.remoteAddr
    }

    private fun getUsername(
        context: AuthenticationFlowContext,
    ): String? {
        // 1차 시도: 현재 인증된 사용자 (재인증, 패스워드 변경 등의 경우)
        context.user?.username?.let { return it }

        // 2차 시도: 이전 인증 단계에서 설정된 시도 사용자명 (일반적인 경우)
        context.authenticationSession.getAuthNote("ATTEMPTED_USERNAME")?.let { return it }

        // 3차 시도: HTTP 폼 파라미터에서 직접 추출 (폴백)
        try {
            val decodedFormParameters = context.httpRequest.decodedFormParameters
            decodedFormParameters?.getFirst("username")?.let { return it }
        } catch (_: Exception) {
            // 폼 파라미터 읽기 실패 시 무시하고 계속 진행
        }

        return null
    }

    private fun buildRedisKey(
        type: String,
        realm: String,
        identifier: String,
    ): String =
        "rl:$type:$realm:$identifier"

    private fun hashUsername(
        username: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(username.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    // 설정값 가져오기 메서드들
    private fun getIPLimit(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(IP_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_LIMIT

    private fun getIPWindow(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(IP_WINDOW_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_WINDOW

    private fun getUserLimit(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(USER_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_LIMIT

    private fun getUserWindow(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(USER_WINDOW_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_WINDOW

    private fun getCombinedLimit(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(COMBINED_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_COMBINED_LIMIT

    private fun getCombinedWindow(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(COMBINED_WINDOW_CONFIG)?.toIntOrNull()
            ?: DEFAULT_COMBINED_WINDOW

    override fun requiresUser(
    ): Boolean =
        false

    override fun configuredFor(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel,
    ): Boolean =
        true

    override fun setRequiredActions(
        session: KeycloakSession,
        realm: RealmModel,
        user: UserModel,
    ) = Unit

    override fun close() {
        jedisPool?.close()
    }
}