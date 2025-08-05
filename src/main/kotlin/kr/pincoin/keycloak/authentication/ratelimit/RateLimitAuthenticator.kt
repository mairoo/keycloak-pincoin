package kr.pincoin.keycloak.authentication.ratelimit

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
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

        // Token Bucket 설정 추가
        const val IP_CAPACITY_CONFIG = "ip.capacity"
        const val IP_REFILL_RATE_CONFIG = "ip.refill.rate"
        const val USER_CAPACITY_CONFIG = "user.capacity"
        const val USER_REFILL_RATE_CONFIG = "user.refill.rate"
        const val COMBINED_CAPACITY_CONFIG = "combined.capacity"
        const val COMBINED_REFILL_RATE_CONFIG = "combined.refill.rate"

        // Token Bucket 기본값
        const val DEFAULT_IP_CAPACITY = 30
        const val DEFAULT_IP_REFILL_RATE = 1.0 // 초당 1개
        const val DEFAULT_USER_CAPACITY = 5
        const val DEFAULT_USER_REFILL_RATE = 0.0167 // 분당 1개
        const val DEFAULT_COMBINED_CAPACITY = 3
        const val DEFAULT_COMBINED_REFILL_RATE = 0.0033 // 5분당 1개

        // TTL 설정 (정리용)
        const val IP_TTL_CONFIG = "ip.ttl"
        const val USER_TTL_CONFIG = "user.ttl"
        const val COMBINED_TTL_CONFIG = "combined.ttl"

        const val DEFAULT_IP_TTL = 3600 // 1시간
        const val DEFAULT_USER_TTL = 900 // 15분
        const val DEFAULT_COMBINED_TTL = 300 // 5분

        // Redis 키 타입
        const val KEY_TYPE_IP = "i"
        const val KEY_TYPE_USER = "u"
        const val KEY_TYPE_COMBINED = "c"
    }

    private var jedisPool: JedisPool? = null

    override fun authenticate(context: AuthenticationFlowContext) {
        try {
            val clientIP = getClientIP(context)
            val username = getUsername(context)

            if (clientIP == null) {
                context.success()
                return
            }

            initializeRedisPool(context)

            // Token Bucket으로 Rate Limit 체크 (카운터 증가도 함께 처리됨)
            if (isBlocked(context, clientIP, username)) {
                handleBlocked(context)
                return
            }

            // Token Bucket에서 이미 토큰을 소모했으므로 별도 카운터 증가 불필요
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
        username: String?
    ): Boolean {
        val realmName = context.realm.name

        // L1: IP 기준 (DDoS 방어)
        if (isTokenBucketExceeded(
                buildRedisKey(KEY_TYPE_IP, realmName, clientIP),
                getIPCapacity(context),
                getIPRefillRate(context),
                getIPTTL(context)
            )
        ) {
            return true
        }

        // L2: 사용자 기준 (brute force 방어)
        if (username != null) {
            if (isTokenBucketExceeded(
                    buildRedisKey(KEY_TYPE_USER, realmName, username),
                    getUserCapacity(context),
                    getUserRefillRate(context),
                    getUserTTL(context)
                )
            ) {
                return true
            }
        }

        // L3: 조합 기준 (targeted attack 방어)
        if (username != null) {
            val combinedKey = buildRedisKey(KEY_TYPE_COMBINED, realmName, "$clientIP:${hashUsername(username)}")
            if (isTokenBucketExceeded(
                    combinedKey,
                    getCombinedCapacity(context),
                    getCombinedRefillRate(context),
                    getCombinedTTL(context)
                )
            ) {
                return true
            }
        }

        return false
    }


    private fun isTokenBucketExceeded(
        redisKey: String,
        capacity: Int,
        refillRate: Double,
        windowSeconds: Int
    ): Boolean {
        return try {
            jedisPool?.resource?.use { jedis ->
                val now = System.currentTimeMillis()

                val luaScript = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local window = tonumber(ARGV[4])
            
            -- 현재 버킷 상태 조회
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])
            
            -- 초기화 (첫 요청)
            if tokens == nil then
                tokens = capacity
                last_refill = now
            end
            
            -- 토큰 보충 계산
            local elapsed_seconds = (now - last_refill) / 1000
            tokens = math.min(capacity, tokens + elapsed_seconds * refill_rate)
            
            -- 토큰 사용 가능한지 확인
            if tokens >= 1 then
                -- 토큰 소모하고 허용
                tokens = tokens - 1
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, window + 300)
                return {0, math.floor(tokens)}
            else
                -- 토큰 부족으로 차단
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, window + 300)
                local wait_time = math.ceil((1 - tokens) / refill_rate)
                return {1, wait_time}
            end
        """.trimIndent()

                val rawResult = jedis.eval(
                    luaScript,
                    1,
                    redisKey,
                    capacity.toString(),
                    refillRate.toString(),
                    now.toString(),
                    windowSeconds.toString()
                )

                val result = when (rawResult) {
                    is List<*> -> {
                        // List의 각 요소를 안전하게 Long으로 변환
                        rawResult.mapNotNull {
                            when (it) {
                                is Number -> it.toLong()
                                is String -> it.toLongOrNull()
                                else -> null
                            }
                        }
                    }

                    else -> {
                        println("Unexpected Redis result type: ${rawResult?.javaClass}")
                        return false
                    }
                }

                // 결과 검증
                if (result.size < 2) {
                    println("Invalid Redis result size: ${result.size}")
                    return false
                }

                val isBlocked = result[0] == 1L
                val info = result[1]

                if (isBlocked) {
                    println("Token Bucket 차단: $redisKey, 대기시간: ${info}초")
                } else {
                    println("Token Bucket 허용: $redisKey, 남은토큰: $info")
                }

                isBlocked
            } ?: false
        } catch (e: Exception) {
            println("Token Bucket 오류: ${e.message}")
            false // 장애 시 허용 (fail-open)
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
                    잠시 후 다시 시도할 수 있습니다.
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()

        val response = Response.status(429)
            .entity(htmlContent)
            .type(MediaType.TEXT_HTML)
            .header("Retry-After", "60")
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

    // Token Bucket 설정값 가져오기 메서드들
    private fun getIPCapacity(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(IP_CAPACITY_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_CAPACITY

    private fun getIPRefillRate(
        context: AuthenticationFlowContext,
    ): Double =
        context.authenticatorConfig?.config?.get(IP_REFILL_RATE_CONFIG)?.toDoubleOrNull() ?: DEFAULT_IP_REFILL_RATE

    private fun getIPTTL(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(IP_TTL_CONFIG)?.toIntOrNull() ?: DEFAULT_IP_TTL

    private fun getUserCapacity(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(USER_CAPACITY_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_CAPACITY

    private fun getUserRefillRate(
        context: AuthenticationFlowContext,
    ): Double =
        context.authenticatorConfig?.config?.get(USER_REFILL_RATE_CONFIG)?.toDoubleOrNull() ?: DEFAULT_USER_REFILL_RATE

    private fun getUserTTL(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(USER_TTL_CONFIG)?.toIntOrNull() ?: DEFAULT_USER_TTL

    private fun getCombinedCapacity(
        context: AuthenticationFlowContext,
    ): Int =
        context.authenticatorConfig?.config?.get(COMBINED_CAPACITY_CONFIG)?.toIntOrNull() ?: DEFAULT_COMBINED_CAPACITY

    private fun getCombinedRefillRate(
        context: AuthenticationFlowContext,
    ): Double =
        context.authenticatorConfig?.config?.get(COMBINED_REFILL_RATE_CONFIG)?.toDoubleOrNull()
            ?: DEFAULT_COMBINED_REFILL_RATE

    private fun getCombinedTTL(context: AuthenticationFlowContext): Int =
        context.authenticatorConfig?.config?.get(COMBINED_TTL_CONFIG)?.toIntOrNull() ?: DEFAULT_COMBINED_TTL

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