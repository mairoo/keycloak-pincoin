package kr.pincoin.keycloak.authentication.accountlockout

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kr.pincoin.keycloak.events.AccountLockoutService
import org.keycloak.authentication.AuthenticationFlowContext
import org.keycloak.authentication.Authenticator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel

class AccountLockoutAuthenticator : Authenticator {

    companion object {
        // Redis 설정 키들 (Factory와 동일)
        const val REDIS_HOST_CONFIG = "redis.host"
        const val REDIS_PORT_CONFIG = "redis.port"
        const val REDIS_PASSWORD_CONFIG = "redis.password"
        const val REDIS_DATABASE_CONFIG = "redis.database"

        // 기본값들
        const val DEFAULT_REDIS_HOST = "localhost"
        const val DEFAULT_REDIS_PORT = 6379
        const val DEFAULT_REDIS_DATABASE = 0
    }

    private val accountLockoutService: AccountLockoutService by lazy {
        AccountLockoutService().apply {
            // 기본값으로 Redis 초기화 (Factory에서 설정값 전달받을 수도 있음)
            initializeRedisPool(DEFAULT_REDIS_HOST, DEFAULT_REDIS_PORT, null, DEFAULT_REDIS_DATABASE)
        }
    }

    override fun authenticate(context: AuthenticationFlowContext) {
        val user = context.user
        if (user == null) {
            // 사용자가 식별되지 않은 경우 통과 (Username/Password 단계에서 처리)
            context.success()
            return
        }

        try {
            // Redis 설정값 가져오기 (Factory 설정 활용)
            initializeServiceWithConfig(context)

            // 계정 차단 상태 확인
            val realmName = context.realm.name
            val lockStatus = accountLockoutService.getAccountLockStatus(realmName, user.id)

            if (lockStatus.isLocked) {
                // 차단된 계정인 경우 접근 거부
                handleAccountLocked(context, lockStatus)
                return
            }

            // 차단되지 않은 경우 통과
            context.success()

        } catch (e: Exception) {
            println("Account Lockout 확인 오류: ${e.message}")
            // 오류 발생 시 통과 (fail-open 정책)
            context.success()
        }
    }

    override fun action(context: AuthenticationFlowContext) {
        // 이 Authenticator는 사용자 액션을 처리하지 않음
        context.success()
    }

    private fun initializeServiceWithConfig(context: AuthenticationFlowContext) {
        try {
            // Factory 설정값이 있다면 Service 재초기화
            val config = context.authenticatorConfig?.config
            if (config != null) {
                val redisHost = config[REDIS_HOST_CONFIG] ?: DEFAULT_REDIS_HOST
                val redisPort = config[REDIS_PORT_CONFIG]?.toIntOrNull() ?: DEFAULT_REDIS_PORT
                val redisPassword = config[REDIS_PASSWORD_CONFIG]?.takeIf { it.isNotBlank() }
                val redisDatabase = config[REDIS_DATABASE_CONFIG]?.toIntOrNull() ?: DEFAULT_REDIS_DATABASE

                // Service Redis 연결 재초기화 (설정이 변경된 경우)
                accountLockoutService.initializeRedisPool(redisHost, redisPort, redisPassword, redisDatabase)
            }
        } catch (e: Exception) {
            println("Account Lockout Service 설정 초기화 오류: ${e.message}")
        }
    }

    private fun handleAccountLocked(context: AuthenticationFlowContext, lockStatus: AccountLockoutService.AccountLockStatus) {
        // 이벤트 로깅
        context.event.error(org.keycloak.events.Errors.USER_TEMPORARILY_DISABLED)

        // 차단 정보 준비
        val remainingTime = lockStatus.getRemainingTimeFormatted()
        val lockTimestamp = lockStatus.getLockTimestampFormatted() ?: "알 수 없음"
        val failureCount = lockStatus.failureCount
        val lockDuration = when (lockStatus.lockDuration) {
            "1h" -> "1시간"
            "24h" -> "24시간"
            else -> "일시적"
        }

        // 사용자에게 보여줄 HTML 페이지 생성
        val htmlContent = createAccountLockedHtml(
            remainingTime = remainingTime,
            lockTimestamp = lockTimestamp,
            failureCount = failureCount,
            lockDuration = lockDuration,
            realmDisplayName = context.realm.displayName ?: context.realm.name,
            remainingSeconds = lockStatus.remainingSeconds  // 파라미터로 전달
        )

        val response = Response.status(423) // 423 Locked
            .entity(htmlContent)
            .type(MediaType.TEXT_HTML)
            .header("Retry-After", lockStatus.remainingSeconds.toString())
            .build()

        context.challenge(response)

        // 관리자 로깅
        println("🔒 Account Lockout: 차단된 계정 접근 시도")
        println("  - User ID: ${context.user?.id}")
        println("  - Username: ${context.user?.username}")
        println("  - Realm: ${context.realm.name}")
        println("  - Lock Duration: $lockDuration")
        println("  - Remaining Time: $remainingTime")
        println("  - Failure Count: $failureCount")
        println("  - Lock Timestamp: $lockTimestamp")
    }

    private fun createAccountLockedHtml(
        remainingTime: String,
        lockTimestamp: String,
        failureCount: Int,
        lockDuration: String,
        realmDisplayName: String,
        remainingSeconds: Int  // 파라미터로 추가
    ): String {
        return """
        <!DOCTYPE html>
        <html lang="ko">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>계정 차단 - $realmDisplayName</title>
            <style>
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                }
                
                body {
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    color: #333;
                }
                
                .lockout-container {
                    background: white;
                    border-radius: 20px;
                    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
                    max-width: 500px;
                    width: 90%;
                    padding: 40px;
                    text-align: center;
                    position: relative;
                    overflow: hidden;
                }
                
                .lockout-container::before {
                    content: '';
                    position: absolute;
                    top: 0;
                    left: 0;
                    right: 0;
                    height: 4px;
                    background: linear-gradient(90deg, #ff6b6b, #feca57, #48dbfb, #ff9ff3);
                }
                
                .lock-icon {
                    font-size: 80px;
                    color: #e74c3c;
                    margin-bottom: 20px;
                    animation: shake 0.5s ease-in-out;
                }
                
                @keyframes shake {
                    0%, 100% { transform: translateX(0); }
                    25% { transform: translateX(-5px); }
                    75% { transform: translateX(5px); }
                }
                
                .lockout-title {
                    font-size: 28px;
                    font-weight: bold;
                    color: #2c3e50;
                    margin-bottom: 15px;
                }
                
                .lockout-subtitle {
                    font-size: 16px;
                    color: #7f8c8d;
                    margin-bottom: 30px;
                }
                
                .lockout-info {
                    background: #f8f9fa;
                    border-radius: 15px;
                    padding: 25px;
                    margin: 20px 0;
                    border-left: 5px solid #e74c3c;
                }
                
                .info-row {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin: 10px 0;
                    padding: 8px 0;
                    border-bottom: 1px solid #ecf0f1;
                }
                
                .info-row:last-child {
                    border-bottom: none;
                }
                
                .info-label {
                    font-weight: 600;
                    color: #34495e;
                }
                
                .info-value {
                    color: #e74c3c;
                    font-weight: bold;
                }
                
                .remaining-time {
                    font-size: 24px;
                    color: #e74c3c;
                    font-weight: bold;
                    margin: 20px 0;
                    padding: 15px;
                    background: linear-gradient(135deg, #fff5f5, #ffe6e6);
                    border-radius: 10px;
                    border: 2px solid #ffcdd2;
                }
                
                .help-section {
                    margin-top: 30px;
                    padding-top: 20px;
                    border-top: 1px solid #ecf0f1;
                }
                
                .help-title {
                    font-size: 18px;
                    font-weight: 600;
                    color: #2c3e50;
                    margin-bottom: 15px;
                }
                
                .help-list {
                    text-align: left;
                    color: #555;
                    line-height: 1.6;
                }
                
                .help-list li {
                    margin: 8px 0;
                    padding-left: 10px;
                    position: relative;
                }
                
                .help-list li::before {
                    content: "•";
                    color: #3498db;
                    font-weight: bold;
                    position: absolute;
                    left: 0;
                }
                
                .contact-info {
                    margin-top: 20px;
                    padding: 15px;
                    background: #e8f4fd;
                    border-radius: 10px;
                    color: #2980b9;
                    font-size: 14px;
                }
                
                .footer {
                    margin-top: 30px;
                    padding-top: 20px;
                    border-top: 1px solid #ecf0f1;
                    color: #95a5a6;
                    font-size: 12px;
                }
                
                @media (max-width: 600px) {
                    .lockout-container {
                        padding: 30px 20px;
                    }
                    
                    .lock-icon {
                        font-size: 60px;
                    }
                    
                    .lockout-title {
                        font-size: 24px;
                    }
                    
                    .info-row {
                        flex-direction: column;
                        align-items: flex-start;
                        gap: 5px;
                    }
                }
            </style>
        </head>
        <body>
            <div class="lockout-container">
                <div class="lock-icon">🔒</div>
                
                <h1 class="lockout-title">계정이 차단되었습니다</h1>
                <p class="lockout-subtitle">보안을 위해 귀하의 계정에 대한 접근이 임시적으로 제한되었습니다</p>
                
                <div class="lockout-info">
                    <div class="info-row">
                        <span class="info-label">차단 유형</span>
                        <span class="info-value">${lockDuration} 차단</span>
                    </div>
                    <div class="info-row">
                        <span class="info-label">로그인 실패 횟수</span>
                        <span class="info-value">${failureCount}회</span>
                    </div>
                    <div class="info-row">
                        <span class="info-label">차단 시작 시간</span>
                        <span class="info-value">${lockTimestamp}</span>
                    </div>
                </div>
                
                <div class="remaining-time">
                    ⏰ 남은 시간: $remainingTime
                </div>
                
                <div class="help-section">
                    <h3 class="help-title">❓ 이런 경우에 계정이 차단됩니다</h3>
                    <ul class="help-list">
                        <li>잘못된 비밀번호를 여러 번 입력한 경우</li>
                        <li>2차 인증(OTP) 코드를 잘못 입력한 경우</li>
                        <li>의심스러운 로그인 활동이 감지된 경우</li>
                        <li>보안 정책에 따른 자동 차단</li>
                    </ul>
                    
                    <div class="contact-info">
                        <strong>📞 문의사항이 있으시다면</strong><br>
                        시스템 관리자에게 연락하시거나 고객지원센터로 문의해주세요.
                    </div>
                </div>
                
                <div class="footer">
                    <p>${realmDisplayName} 보안 시스템</p>
                    <p>이 페이지는 자동으로 새로고침됩니다</p>
                </div>
            </div>
            
            <script>
                // 1분마다 페이지 자동 새로고침 (차단 해제 확인용)
                setTimeout(function() {
                    window.location.reload();
                }, 60000);
                
                // 남은 시간 실시간 업데이트 (선택적)
                let remainingSeconds = ${remainingSeconds};
                
                if (remainingSeconds > 0) {
                    const updateTimer = setInterval(function() {
                        remainingSeconds--;
                        
                        if (remainingSeconds <= 0) {
                            clearInterval(updateTimer);
                            window.location.reload();
                            return;
                        }
                        
                        const hours = Math.floor(remainingSeconds / 3600);
                        const minutes = Math.floor((remainingSeconds % 3600) / 60);
                        const seconds = remainingSeconds % 60;
                        
                        let timeStr = '';
                        if (hours > 0) timeStr += hours + '시간 ';
                        if (minutes > 0) timeStr += minutes + '분 ';
                        if (seconds > 0 || timeStr === '') timeStr += seconds + '초';
                        
                        const timeElement = document.querySelector('.remaining-time');
                        if (timeElement) {
                            timeElement.innerHTML = '⏰ 남은 시간: ' + timeStr;
                        }
                    }, 1000);
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    override fun requiresUser(): Boolean = false // 사용자 식별 전에도 실행 가능

    override fun configuredFor(session: KeycloakSession, realm: RealmModel, user: UserModel): Boolean = true

    override fun setRequiredActions(session: KeycloakSession, realm: RealmModel, user: UserModel) = Unit

    override fun close() {
        try {
            accountLockoutService.close()
        } catch (e: Exception) {
            println("Account Lockout Authenticator 종료 오류: ${e.message}")
        }
    }
}