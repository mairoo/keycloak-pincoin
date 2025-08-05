package kr.pincoin.keycloak.authentication.ratelimit

import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
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

    override fun authenticate(context: AuthenticationFlowContext) {
        try {
            val clientIP = getClientIP(context)
            val username = context.user?.username ?: context.authenticationSession.getAuthNote("ATTEMPTED_USERNAME")

            if (clientIP == null) {
                context.success()
                return
            }

            initializeRedisPool(context)

            if (isRateLimited(context, clientIP, username)) {
                handleRateLimit(context)
                return
            }

            // Rate limit 통과 시 계속 진행
            context.success()

        } catch (e: Exception) {
            // Redis 연결 실패 등의 경우 통과 처리
            println("Rate Limit Authenticator 오류: ${e.message}")
            context.success()
        }
    }

    override fun action(context: AuthenticationFlowContext) {
        // 이 Authenticator는 action을 사용하지 않음
        context.success()
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

    private fun isRateLimited(context: AuthenticationFlowContext, clientIP: String, username: String?): Boolean {
        val realmName = context.realm.name

        // L1: IP 기준 체크
        if (checkRateLimit(
                context,
                buildRedisKey(KEY_TYPE_IP, realmName, clientIP),
                getIPLimit(context),
                getIPWindow(context)
            )
        ) {
            println("IP Rate Limit 초과: $clientIP")
            return true
        }

        // L2: 사용자 기준 체크 (username이 있는 경우만)
        if (username != null && checkRateLimit(
                context,
                buildRedisKey(KEY_TYPE_USER, realmName, username),
                getUserLimit(context),
                getUserWindow(context)
            )
        ) {
            println("사용자 Rate Limit 초과: $username")
            return true
        }

        // L3: 조합 기준 체크 (username이 있는 경우만)
        if (username != null && checkRateLimit(
                context,
                buildRedisKey(KEY_TYPE_COMBINED, realmName, "$clientIP:${hashUsername(username)}"),
                getCombinedLimit(context),
                getCombinedWindow(context)
            )
        ) {
            println("조합 Rate Limit 초과: $clientIP + $username")
            return true
        }

        return false
    }

    private fun checkRateLimit(
        context: AuthenticationFlowContext,
        redisKey: String,
        limit: Int,
        windowSeconds: Int
    ): Boolean {
        return try {
            jedisPool?.resource?.use { jedis ->
                val current = jedis.get(redisKey)?.toIntOrNull() ?: 0

                if (current >= limit) {
                    true
                } else {
                    // 카운터 증가
                    val newCount = jedis.incr(redisKey)
                    if (newCount == 1L) {
                        // 첫 번째 시도인 경우 TTL 설정
                        jedis.expire(redisKey, windowSeconds.toLong())
                    }
                    false
                }
            } ?: false

        } catch (e: Exception) {
            println("Redis Rate Limit 체크 오류: ${e.message}")
            false
        }
    }

    private fun handleRateLimit(context: AuthenticationFlowContext) {
        // Rate Limit 차단 시 인증 실패로 처리
        context.getEvent().error(org.keycloak.events.Errors.USER_TEMPORARILY_DISABLED)

        // AuthenticationFlowError로 실패 처리
        context.failure(org.keycloak.authentication.AuthenticationFlowError.INVALID_CREDENTIALS)
    }

    private fun getClientIP(context: AuthenticationFlowContext): String? {
        val request = context.httpRequest

        // 프록시를 통한 접속인 경우 실제 IP 확인
        return request.getHttpHeaders().getHeaderString("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.getHttpHeaders().getHeaderString("X-Real-IP")
            ?: request.getHttpHeaders().getHeaderString("X-Forwarded-Host")
            ?: context.connection.remoteAddr
    }

    private fun buildRedisKey(type: String, realm: String, identifier: String): String {
        return "rl:$type:$realm:$identifier"
    }

    private fun hashUsername(username: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(username.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    // 설정값 가져오기 메서드들
    private fun getIPLimit(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(IP_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_LIMIT
    }

    private fun getIPWindow(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(IP_WINDOW_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_WINDOW
    }

    private fun getUserLimit(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(USER_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_LIMIT
    }

    private fun getUserWindow(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(USER_WINDOW_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_WINDOW
    }

    private fun getCombinedLimit(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(COMBINED_LIMIT_CONFIG)?.toIntOrNull() ?: DEFAULT_COMBINED_LIMIT
    }

    private fun getCombinedWindow(context: AuthenticationFlowContext): Int {
        return context.authenticatorConfig?.config?.get(COMBINED_WINDOW_CONFIG)?.toIntOrNull()
            ?: DEFAULT_COMBINED_WINDOW
    }

    override fun requiresUser(): Boolean = false
    override fun configuredFor(session: KeycloakSession, realm: RealmModel, user: UserModel): Boolean = true
    override fun setRequiredActions(session: KeycloakSession, realm: RealmModel, user: UserModel) {}

    override fun close() {
        jedisPool?.close()
    }
}