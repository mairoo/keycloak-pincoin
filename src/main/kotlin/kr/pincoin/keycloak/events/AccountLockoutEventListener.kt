package kr.pincoin.keycloak.events

import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventType
import org.keycloak.events.admin.AdminEvent
import org.keycloak.models.KeycloakSession

class AccountLockoutEventListener(
    private val session: KeycloakSession,
    private val config: Map<String, String> = emptyMap()
) : EventListenerProvider {

    private val accountLockoutService: AccountLockoutService by lazy {
        AccountLockoutService().apply {
            // Factory에서 전달받은 설정값으로 Redis 초기화
            val redisHost = config[AccountLockoutEventListenerFactory.REDIS_HOST_CONFIG]
                ?: AccountLockoutEventListenerFactory.DEFAULT_REDIS_HOST
            val redisPort = config[AccountLockoutEventListenerFactory.REDIS_PORT_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_REDIS_PORT
            val redisPassword = config[AccountLockoutEventListenerFactory.REDIS_PASSWORD_CONFIG]
                ?.takeIf { it.isNotBlank() }
            val redisDatabase = config[AccountLockoutEventListenerFactory.REDIS_DATABASE_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_REDIS_DATABASE

            initializeRedisPool(redisHost, redisPort, redisPassword, redisDatabase)
        }
    }

    companion object {
        // 모니터링할 이벤트 타입들
        private val MONITORED_EVENTS = setOf(
            EventType.LOGIN_ERROR,              // 일반 로그인 실패 (패스워드, OTP 등 모든 실패)
            EventType.USER_DISABLED_BY_TEMPORARY_LOCKOUT, // 임시 차단으로 인한 비활성화
            EventType.LOGIN,                    // 로그인 성공 (카운터 리셋용)
            EventType.LOGOUT                    // 로그아웃 (선택적 처리)
        )
    }

    override fun onEvent(event: Event) {
        try {
            // 관심 있는 이벤트만 처리
            if (event.type !in MONITORED_EVENTS) {
                return
            }

            // 사용자 ID가 없는 이벤트는 무시
            val userId = event.userId
            if (userId.isNullOrBlank()) {
                println("Account Lockout: 사용자 ID가 없는 이벤트 무시 - ${event.type}")
                return
            }

            val realmName = event.realmId ?: "unknown"
            println("Account Lockout Event: ${event.type}, User: $userId, Realm: $realmName")

            when (event.type) {
                EventType.LOGIN_ERROR -> {
                    handleLoginFailure(realmName, userId, event)
                }
                EventType.LOGIN -> {
                    handleLoginSuccess(realmName, userId, event)
                }
                EventType.USER_DISABLED_BY_TEMPORARY_LOCKOUT -> {
                    handleTemporaryLockout(realmName, userId, event)
                }
                EventType.LOGOUT -> {
                    // 필요시 로그아웃 처리 (현재는 특별한 처리 없음)
                    println("Account Lockout: 사용자 로그아웃 - $userId")
                }
                else -> {
                    // 기타 이벤트 (현재는 처리하지 않음)
                }
            }

        } catch (e: Exception) {
            // 이벤트 처리 실패가 전체 인증 플로우에 영향을 주지 않도록 예외 처리
            println("Account Lockout Event 처리 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onEvent(adminEvent: AdminEvent, includeRepresentation: Boolean) {
        try {
            // 관리자 이벤트 처리 (필요시)
            when (adminEvent.operationType) {
                org.keycloak.events.admin.OperationType.UPDATE -> {
                    // 사용자 업데이트 시 처리 (예: 관리자가 수동으로 계정 잠금 해제)
                    handleAdminUserUpdate(adminEvent)
                }
                org.keycloak.events.admin.OperationType.DELETE -> {
                    // 사용자 삭제 시 관련 차단 데이터 정리
                    handleAdminUserDelete(adminEvent)
                }
                else -> {
                    // 기타 관리자 이벤트는 처리하지 않음
                }
            }
        } catch (e: Exception) {
            println("Account Lockout Admin Event 처리 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleLoginFailure(realmName: String, userId: String, event: Event) {
        try {
            val clientIp = event.details?.get("ip_address") ?: "unknown"
            val username = event.details?.get("username") ?: "unknown"

            println("Account Lockout: 로그인 실패 처리 - User: $userId, IP: $clientIp, Username: $username")

            // 실패 횟수 증가 및 차단 여부 확인 (Factory 설정값 사용)
            val failureThreshold1h = config[AccountLockoutEventListenerFactory.FAILURE_THRESHOLD_1H_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_FAILURE_THRESHOLD_1H
            val failureThreshold24h = config[AccountLockoutEventListenerFactory.FAILURE_THRESHOLD_24H_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_FAILURE_THRESHOLD_24H
            val warningThreshold = config[AccountLockoutEventListenerFactory.WARNING_THRESHOLD_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_WARNING_THRESHOLD
            val lockoutDuration1h = config[AccountLockoutEventListenerFactory.LOCKOUT_DURATION_1H_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_LOCKOUT_DURATION_1H
            val lockoutDuration24h = config[AccountLockoutEventListenerFactory.LOCKOUT_DURATION_24H_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_LOCKOUT_DURATION_24H

            val lockoutResult = accountLockoutService.incrementFailureCount(
                realmName, userId,
                failureThreshold1h, failureThreshold24h, warningThreshold,
                lockoutDuration1h, lockoutDuration24h
            )

            when (lockoutResult.status) {
                AccountLockoutService.LockoutStatus.LOCKED_24H -> {
                    println("Account Lockout: 계정 24시간 차단 적용 - User: $userId, 누적 실패: ${lockoutResult.failureCount}")
                    // 필요시 추가 알림 처리 (이메일, Slack 등)
                    notifyAccountLocked(userId, username, "24시간", lockoutResult.failureCount)
                }
                AccountLockoutService.LockoutStatus.LOCKED_1H -> {
                    println("Account Lockout: 계정 1시간 차단 적용 - User: $userId, 누적 실패: ${lockoutResult.failureCount}")
                    notifyAccountLocked(userId, username, "1시간", lockoutResult.failureCount)
                }
                AccountLockoutService.LockoutStatus.WARNING -> {
                    println("Account Lockout: 차단 경고 - User: $userId, 누적 실패: ${lockoutResult.failureCount}")
                    // 경고 수준의 실패 (예: 3-4회)
                }
                AccountLockoutService.LockoutStatus.NORMAL -> {
                    println("Account Lockout: 일반 실패 기록 - User: $userId, 누적 실패: ${lockoutResult.failureCount}")
                }

                else -> {}
            }

            // 의심스러운 패턴 감지 (동일 IP에서 여러 계정 공격)
            if (clientIp != "unknown") {
                detectSuspiciousActivity(realmName, clientIp, userId)
            }

        } catch (e: Exception) {
            println("로그인 실패 처리 오류: ${e.message}")
        }
    }

    private fun handleLoginSuccess(realmName: String, userId: String, event: Event) {
        try {
            val clientIp = event.details?.get("ip_address") ?: "unknown"
            val username = event.details?.get("username") ?: "unknown"

            println("Account Lockout: 로그인 성공 - User: $userId, IP: $clientIp")

            // 성공 시 실패 카운터 리셋
            val resetResult = accountLockoutService.resetFailureCount(realmName, userId)
            if (resetResult > 0) {
                println("Account Lockout: 실패 카운터 리셋 - User: $userId, 이전 실패 횟수: $resetResult")
            }

        } catch (e: Exception) {
            println("로그인 성공 처리 오류: ${e.message}")
        }
    }

    private fun handleTemporaryLockout(realmName: String, userId: String, event: Event) {
        try {
            println("Account Lockout: 임시 차단 이벤트 감지 - User: $userId")

            // Keycloak 자체의 임시 차단과 우리의 차단 시스템 동기화
            val lockStatus = accountLockoutService.getAccountLockStatus(realmName, userId)
            if (lockStatus.isLocked) {
                println("Account Lockout: 기존 차단과 동기화됨 - User: $userId")
            }

        } catch (e: Exception) {
            println("임시 차단 처리 오류: ${e.message}")
        }
    }

    private fun handleAdminUserUpdate(adminEvent: AdminEvent) {
        try {
            // 관리자가 사용자를 업데이트할 때 (예: 계정 잠금 해제)
            if (adminEvent.resourcePath?.contains("/users/") == true) {
                val userId = extractUserIdFromPath(adminEvent.resourcePath)
                if (userId != null) {
                    println("Account Lockout: 관리자 사용자 업데이트 감지 - User: $userId")
                    // 필요시 차단 상태 동기화
                }
            }
        } catch (e: Exception) {
            println("관리자 사용자 업데이트 처리 오류: ${e.message}")
        }
    }

    private fun handleAdminUserDelete(adminEvent: AdminEvent) {
        try {
            // 사용자 삭제 시 관련 차단 데이터 정리
            if (adminEvent.resourcePath?.contains("/users/") == true) {
                val userId = extractUserIdFromPath(adminEvent.resourcePath)
                if (userId != null) {
                    val realmName = adminEvent.realmId ?: "unknown"
                    println("Account Lockout: 사용자 삭제로 인한 데이터 정리 - User: $userId")
                    accountLockoutService.cleanupUserData(realmName, userId)
                }
            }
        } catch (e: Exception) {
            println("관리자 사용자 삭제 처리 오류: ${e.message}")
        }
    }

    private fun detectSuspiciousActivity(realmName: String, clientIp: String, userId: String) {
        try {
            // 동일 IP에서 여러 계정 공격 감지 (Factory 설정값 사용)
            val suspiciousThreshold = config[AccountLockoutEventListenerFactory.SUSPICIOUS_IP_THRESHOLD_CONFIG]?.toIntOrNull()
                ?: AccountLockoutEventListenerFactory.DEFAULT_SUSPICIOUS_IP_THRESHOLD
            val suspiciousResult = accountLockoutService.detectSuspiciousIpActivity(realmName, clientIp, userId, suspiciousThreshold)

            if (suspiciousResult.isSuspicious) {
                println("Account Lockout: 의심스러운 IP 활동 감지 - IP: $clientIp, 공격 대상 계정 수: ${suspiciousResult.targetAccountCount}")

                // 심각한 수준의 공격이면 IP 차단 고려
                if (suspiciousResult.targetAccountCount >= 10) {
                    println("Account Lockout: 대규모 공격 감지 - IP 차단 권장: $clientIp")
                    // 필요시 방화벽 API 호출 또는 관리자 알림
                }
            }
        } catch (e: Exception) {
            println("의심스러운 활동 감지 오류: ${e.message}")
        }
    }

    private fun notifyAccountLocked(userId: String, username: String, lockDuration: String, failureCount: Int) {
        try {
            // 계정 차단 알림 (로그, 이메일, Slack 등)
            println("🚨 ACCOUNT LOCKED 🚨")
            println("- User ID: $userId")
            println("- Username: $username")
            println("- Lock Duration: $lockDuration")
            println("- Failure Count: $failureCount")
            println("- Time: ${java.time.LocalDateTime.now()}")

            // TODO: 필요시 추가 알림 시스템 연동
            // - 이메일 발송
            // - Slack 알림
            // - 보안팀 대시보드 업데이트

        } catch (e: Exception) {
            println("계정 차단 알림 발송 오류: ${e.message}")
        }
    }

    private fun extractUserIdFromPath(resourcePath: String?): String? {
        return try {
            // "/admin/realms/{realm}/users/{userId}" 패턴에서 userId 추출
            resourcePath?.let {
                val segments = it.split("/")
                val userIndex = segments.indexOf("users")
                if (userIndex != -1 && userIndex + 1 < segments.size) {
                    segments[userIndex + 1]
                } else null
            }
        } catch (e: Exception) {
            println("사용자 ID 추출 오류: ${e.message}")
            null
        }
    }

    override fun close() {
        try {
            accountLockoutService.close()
            println("Account Lockout Event Listener 종료됨")
        } catch (e: Exception) {
            println("Account Lockout Event Listener 종료 오류: ${e.message}")
        }
    }
}