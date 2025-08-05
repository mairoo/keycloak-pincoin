package kr.pincoin.keycloak.events

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AccountLockoutService {

    companion object {
        // Redis 키 패턴
        const val LOCKOUT_FAILURES_KEY = "lockout:failures"      // 실패 횟수
        const val LOCKOUT_LOCKED_KEY = "lockout:locked"          // 차단 상태
        const val LOCKOUT_SUSPICIOUS_IP_KEY = "lockout:suspicious_ip" // 의심스러운 IP
        const val LOCKOUT_IP_ATTACKS_KEY = "lockout:ip_attacks"  // IP별 공격 대상 계정들

        // 기본 설정값 (Factory에서 오버라이드 가능)
        const val DEFAULT_FAILURE_THRESHOLD_1H = 5
        const val DEFAULT_FAILURE_THRESHOLD_24H = 10
        const val DEFAULT_WARNING_THRESHOLD = 3
        const val DEFAULT_LOCKOUT_DURATION_1H_MINUTES = 60
        const val DEFAULT_LOCKOUT_DURATION_24H_MINUTES = 1440
        const val DEFAULT_FAILURE_WINDOW_MINUTES = 60
        const val DEFAULT_SUSPICIOUS_IP_THRESHOLD = 10

        // TTL 값들
        const val FAILURE_COUNT_TTL = 3600L // 1시간
        const val SUSPICIOUS_IP_TTL = 7200L // 2시간
    }

    private var jedisPool: JedisPool? = null

    /**
     * Redis 연결 풀 초기화
     */
    fun initializeRedisPool(
        host: String = "localhost",
        port: Int = 6379,
        password: String? = null,
        database: Int = 0
    ) {
        if (jedisPool == null) {
            val poolConfig = JedisPoolConfig().apply {
                maxTotal = 20
                maxIdle = 10
                minIdle = 2
                testOnBorrow = true
                testOnReturn = true
                testWhileIdle = true
            }

            jedisPool = if (password.isNullOrBlank()) {
                JedisPool(poolConfig, host, port, 2000, null, database)
            } else {
                JedisPool(poolConfig, host, port, 2000, password, database)
            }

            println("Account Lockout Redis Pool 초기화 완료: $host:$port (DB: $database)")
        }
    }

    /**
     * 로그인 실패 횟수 증가 및 차단 여부 결정
     */
    fun incrementFailureCount(
        realmName: String,
        userId: String,
        failureThreshold1h: Int = DEFAULT_FAILURE_THRESHOLD_1H,
        failureThreshold24h: Int = DEFAULT_FAILURE_THRESHOLD_24H,
        warningThreshold: Int = DEFAULT_WARNING_THRESHOLD,
        lockoutDuration1h: Int = DEFAULT_LOCKOUT_DURATION_1H_MINUTES,
        lockoutDuration24h: Int = DEFAULT_LOCKOUT_DURATION_24H_MINUTES
    ): LockoutResult {
        ensureRedisPool()

        val failureKey = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, userId)
        val lockKey = buildRedisKey(LOCKOUT_LOCKED_KEY, realmName, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val luaScript = """
                    local failure_key = KEYS[1]
                    local lock_key = KEYS[2]
                    local failure_threshold_1h = tonumber(ARGV[1])
                    local failure_threshold_24h = tonumber(ARGV[2])
                    local warning_threshold = tonumber(ARGV[3])
                    local lockout_duration_1h = tonumber(ARGV[4]) * 60  -- 분을 초로 변환
                    local lockout_duration_24h = tonumber(ARGV[5]) * 60 -- 분을 초로 변환
                    local failure_ttl = tonumber(ARGV[6])
                    local now_timestamp = tonumber(ARGV[7])

                    -- 현재 실패 횟수 증가
                    local failure_count = redis.call('INCR', failure_key)
                    redis.call('EXPIRE', failure_key, failure_ttl)

                    -- 이미 차단된 상태인지 확인
                    local existing_lock = redis.call('GET', lock_key)
                    if existing_lock then
                        local lock_ttl = redis.call('TTL', lock_key)
                        return {'ALREADY_LOCKED', failure_count, lock_ttl, existing_lock}
                    end

                    -- 차단 여부 결정
                    if failure_count >= failure_threshold_24h then
                        -- 24시간 차단
                        local lock_info = now_timestamp .. ':24h:' .. failure_count
                        redis.call('SETEX', lock_key, lockout_duration_24h, lock_info)
                        return {'LOCKED_24H', failure_count, lockout_duration_24h, '24h'}
                    elseif failure_count >= failure_threshold_1h then
                        -- 1시간 차단
                        local lock_info = now_timestamp .. ':1h:' .. failure_count
                        redis.call('SETEX', lock_key, lockout_duration_1h, lock_info)
                        return {'LOCKED_1H', failure_count, lockout_duration_1h, '1h'}
                    elseif failure_count >= warning_threshold then
                        -- 경고 수준
                        return {'WARNING', failure_count, 0, 'warning'}
                    else
                        -- 일반 실패
                        return {'NORMAL', failure_count, 0, 'normal'}
                    end
                """.trimIndent()

                val now = System.currentTimeMillis() / 1000 // Unix timestamp
                val rawResult = jedis.eval(
                    luaScript,
                    2,
                    failureKey, lockKey,
                    failureThreshold1h.toString(),
                    failureThreshold24h.toString(),
                    warningThreshold.toString(),
                    lockoutDuration1h.toString(),
                    lockoutDuration24h.toString(),
                    FAILURE_COUNT_TTL.toString(),
                    now.toString()
                )

                parseLockoutResult(rawResult)
            } ?: createErrorResult("Redis 연결 실패")
        } catch (e: Exception) {
            println("실패 횟수 증가 처리 오류: ${e.message}")
            createErrorResult("처리 오류: ${e.message}")
        }
    }

    /**
     * 로그인 성공 시 실패 카운터 리셋
     */
    fun resetFailureCount(realmName: String, userId: String): Int {
        ensureRedisPool()

        val failureKey = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val previousCount = jedis.get(failureKey)?.toIntOrNull() ?: 0
                jedis.del(failureKey)
                previousCount
            } ?: 0
        } catch (e: Exception) {
            println("실패 카운터 리셋 오류: ${e.message}")
            0
        }
    }

    /**
     * 계정 차단 상태 확인
     */
    fun getAccountLockStatus(realmName: String, userId: String): AccountLockStatus {
        ensureRedisPool()

        val lockKey = buildRedisKey(LOCKOUT_LOCKED_KEY, realmName, userId)
        val failureKey = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val lockInfo = jedis.get(lockKey)
                val failureCount = jedis.get(failureKey)?.toIntOrNull() ?: 0

                if (lockInfo != null) {
                    val lockTtl = jedis.ttl(lockKey)
                    val lockParts = lockInfo.split(":")

                    if (lockParts.size >= 3) {
                        val lockTimestamp = lockParts[0].toLongOrNull() ?: 0
                        val lockDuration = lockParts[1]
                        val lockFailureCount = lockParts[2].toIntOrNull() ?: failureCount

                        AccountLockStatus(
                            isLocked = true,
                            lockDuration = lockDuration,
                            remainingSeconds = lockTtl.toInt(),
                            failureCount = lockFailureCount,
                            lockTimestamp = lockTimestamp
                        )
                    } else {
                        // 레거시 형식 지원
                        AccountLockStatus(
                            isLocked = true,
                            lockDuration = "unknown",
                            remainingSeconds = lockTtl.toInt(),
                            failureCount = failureCount,
                            lockTimestamp = System.currentTimeMillis() / 1000
                        )
                    }
                } else {
                    AccountLockStatus(
                        isLocked = false,
                        lockDuration = null,
                        remainingSeconds = 0,
                        failureCount = failureCount,
                        lockTimestamp = null
                    )
                }
            } ?: AccountLockStatus(false, null, 0, 0, null)
        } catch (e: Exception) {
            println("계정 차단 상태 확인 오류: ${e.message}")
            AccountLockStatus(false, null, 0, 0, null)
        }
    }

    /**
     * 의심스러운 IP 활동 감지
     */
    fun detectSuspiciousIpActivity(
        realmName: String,
        clientIp: String,
        targetUserId: String,
        suspiciousThreshold: Int = DEFAULT_SUSPICIOUS_IP_THRESHOLD
    ): SuspiciousIpResult {
        ensureRedisPool()

        val ipAttacksKey = buildRedisKey(LOCKOUT_IP_ATTACKS_KEY, realmName, clientIp)
        val suspiciousIpKey = buildRedisKey(LOCKOUT_SUSPICIOUS_IP_KEY, realmName, clientIp)

        return try {
            jedisPool?.resource?.use { jedis ->
                val luaScript = """
                    local ip_attacks_key = KEYS[1]
                    local suspicious_ip_key = KEYS[2]
                    local target_user_id = ARGV[1]
                    local suspicious_threshold = tonumber(ARGV[2])
                    local ttl = tonumber(ARGV[3])

                    -- IP별 공격 대상 계정 목록에 추가
                    redis.call('SADD', ip_attacks_key, target_user_id)
                    redis.call('EXPIRE', ip_attacks_key, ttl)

                    -- 현재 공격 대상 계정 수 확인
                    local target_count = redis.call('SCARD', ip_attacks_key)

                    -- 의심스러운 수준인지 확인
                    local is_suspicious = target_count >= suspicious_threshold
                    
                    if is_suspicious then
                        -- 의심스러운 IP로 마킹
                        redis.call('SETEX', suspicious_ip_key, ttl, target_count)
                        return {'SUSPICIOUS', target_count, ttl}
                    else
                        return {'NORMAL', target_count, 0}
                    end
                """.trimIndent()

                val rawResult = jedis.eval(
                    luaScript,
                    2,
                    ipAttacksKey, suspiciousIpKey,
                    targetUserId,
                    suspiciousThreshold.toString(),
                    SUSPICIOUS_IP_TTL.toString()
                )

                parseSuspiciousIpResult(rawResult)
            } ?: SuspiciousIpResult(false, 0)
        } catch (e: Exception) {
            println("의심스러운 IP 활동 감지 오류: ${e.message}")
            SuspiciousIpResult(false, 0)
        }
    }

    /**
     * 계정 수동 차단 해제
     */
    fun unlockAccount(realmName: String, userId: String): Boolean {
        ensureRedisPool()

        val lockKey = buildRedisKey(LOCKOUT_LOCKED_KEY, realmName, userId)
        val failureKey = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, userId)

        return try {
            jedisPool?.resource?.use { jedis ->
                val deletedCount = jedis.del(lockKey, failureKey)
                deletedCount > 0
            } ?: false
        } catch (e: Exception) {
            println("계정 차단 해제 오류: ${e.message}")
            false
        }
    }

    /**
     * 사용자 삭제 시 관련 데이터 정리
     */
    fun cleanupUserData(realmName: String, userId: String) {
        ensureRedisPool()

        val failureKey = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, userId)
        val lockKey = buildRedisKey(LOCKOUT_LOCKED_KEY, realmName, userId)

        try {
            jedisPool?.resource?.use { jedis ->
                val deletedCount = jedis.del(failureKey, lockKey)
                if (deletedCount > 0) {
                    println("사용자 데이터 정리 완료: $userId (삭제된 키: ${deletedCount}개)")
                }
            }
        } catch (e: Exception) {
            println("사용자 데이터 정리 오류: ${e.message}")
        }
    }

    /**
     * 통계 조회 (관리자용)
     */
    fun getAccountLockoutStats(realmName: String): AccountLockoutStats {
        ensureRedisPool()

        return try {
            jedisPool?.resource?.use { jedis ->
                val lockPattern = buildRedisKey(LOCKOUT_LOCKED_KEY, realmName, "*")
                val failurePattern = buildRedisKey(LOCKOUT_FAILURES_KEY, realmName, "*")
                val suspiciousIpPattern = buildRedisKey(LOCKOUT_SUSPICIOUS_IP_KEY, realmName, "*")

                val lockedAccounts = jedis.keys(lockPattern)?.size ?: 0
                val accountsWithFailures = jedis.keys(failurePattern)?.size ?: 0
                val suspiciousIps = jedis.keys(suspiciousIpPattern)?.size ?: 0

                AccountLockoutStats(
                    lockedAccountCount = lockedAccounts,
                    accountsWithFailuresCount = accountsWithFailures,
                    suspiciousIpCount = suspiciousIps,
                    generatedAt = LocalDateTime.now()
                )
            } ?: AccountLockoutStats(0, 0, 0, LocalDateTime.now())
        } catch (e: Exception) {
            println("통계 조회 오류: ${e.message}")
            AccountLockoutStats(0, 0, 0, LocalDateTime.now())
        }
    }

    /**
     * Redis 연결 상태 확인
     */
    fun isRedisHealthy(): Boolean {
        return try {
            jedisPool?.resource?.use { jedis ->
                jedis.ping() == "PONG"
            } ?: false
        } catch (e: Exception) {
            println("Redis 연결 상태 확인 실패: ${e.message}")
            false
        }
    }

    /**
     * 서비스 종료
     */
    fun close() {
        try {
            jedisPool?.close()
            println("Account Lockout Service 종료됨")
        } catch (e: Exception) {
            println("Account Lockout Service 종료 오류: ${e.message}")
        }
    }

    // Private 헬퍼 메서드들
    private fun ensureRedisPool() {
        if (jedisPool == null) {
            initializeRedisPool()
        }
    }

    private fun buildRedisKey(type: String, realm: String, identifier: String): String =
        "$type:$realm:$identifier"

    private fun parseLockoutResult(rawResult: Any?): LockoutResult {
        return when (rawResult) {
            is List<*> -> {
                val status = rawResult[0] as? String ?: "UNKNOWN"
                val failureCount = (rawResult.getOrNull(1) as? Number)?.toInt() ?: 0
                val remainingSeconds = (rawResult.getOrNull(2) as? Number)?.toInt() ?: 0
                val lockDuration = rawResult.getOrNull(3) as? String ?: "unknown"

                when (status) {
                    "LOCKED_24H" -> LockoutResult(
                        LockoutStatus.LOCKED_24H,
                        failureCount,
                        remainingSeconds,
                        lockDuration
                    )

                    "LOCKED_1H" -> LockoutResult(LockoutStatus.LOCKED_1H, failureCount, remainingSeconds, lockDuration)
                    "ALREADY_LOCKED" -> LockoutResult(
                        LockoutStatus.ALREADY_LOCKED,
                        failureCount,
                        remainingSeconds,
                        lockDuration
                    )

                    "WARNING" -> LockoutResult(LockoutStatus.WARNING, failureCount, remainingSeconds, lockDuration)
                    "NORMAL" -> LockoutResult(LockoutStatus.NORMAL, failureCount, remainingSeconds, lockDuration)
                    else -> LockoutResult(LockoutStatus.ERROR, failureCount, remainingSeconds, "error")
                }
            }

            else -> createErrorResult("잘못된 응답 형식")
        }
    }

    private fun parseSuspiciousIpResult(rawResult: Any?): SuspiciousIpResult {
        return when (rawResult) {
            is List<*> -> {
                val status = rawResult[0] as? String ?: "UNKNOWN"
                val targetCount = (rawResult.getOrNull(1) as? Number)?.toInt() ?: 0

                SuspiciousIpResult(
                    isSuspicious = status == "SUSPICIOUS",
                    targetAccountCount = targetCount
                )
            }

            else -> SuspiciousIpResult(false, 0)
        }
    }

    private fun createErrorResult(message: String): LockoutResult {
        println("Account Lockout 오류: $message")
        return LockoutResult(LockoutStatus.ERROR, 0, 0, message)
    }

    // 데이터 클래스들
    data class LockoutResult(
        val status: LockoutStatus,
        val failureCount: Int,
        val remainingSeconds: Int = 0,
        val lockDuration: String? = null
    )

    data class AccountLockStatus(
        val isLocked: Boolean,
        val lockDuration: String?,
        val remainingSeconds: Int,
        val failureCount: Int,
        val lockTimestamp: Long?
    ) {
        fun getRemainingTimeFormatted(): String {
            if (!isLocked || remainingSeconds <= 0) return "차단되지 않음"

            val hours = remainingSeconds / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60

            return when {
                hours > 0 -> "${hours}시간 ${minutes}분"
                minutes > 0 -> "${minutes}분 ${seconds}초"
                else -> "${seconds}초"
            }
        }

        fun getLockTimestampFormatted(): String? {
            return lockTimestamp?.let {
                val dateTime = java.time.Instant.ofEpochSecond(it)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
                dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            }
        }
    }

    data class SuspiciousIpResult(
        val isSuspicious: Boolean,
        val targetAccountCount: Int
    )

    data class AccountLockoutStats(
        val lockedAccountCount: Int,
        val accountsWithFailuresCount: Int,
        val suspiciousIpCount: Int,
        val generatedAt: LocalDateTime
    ) {
        fun getFormattedStats(): String {
            return """
                |Account Lockout Statistics (${generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))})
                |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                |🔒 Locked Accounts: $lockedAccountCount
                |⚠️  Accounts with Failures: $accountsWithFailuresCount  
                |🚨 Suspicious IPs: $suspiciousIpCount
                |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimMargin()
        }
    }

    enum class LockoutStatus {
        NORMAL,          // 일반 상태
        WARNING,         // 경고 수준 (3-4회 실패)
        LOCKED_1H,       // 1시간 차단 (5회 실패)
        LOCKED_24H,      // 24시간 차단 (10회 실패)
        ALREADY_LOCKED,  // 이미 차단된 상태
        ERROR            // 오류 상태
    }
}