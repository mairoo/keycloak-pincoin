package kr.pincoin.keycloak.authentication.accountlockout

import org.keycloak.Config
import org.keycloak.authentication.Authenticator
import org.keycloak.authentication.AuthenticatorFactory
import org.keycloak.authentication.ConfigurableAuthenticatorFactory
import org.keycloak.models.AuthenticationExecutionModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class AccountLockoutAuthenticatorFactory : AuthenticatorFactory, ConfigurableAuthenticatorFactory {

    companion object {
        const val PROVIDER_ID = "account-lockout-authenticator"
        const val DISPLAY_TYPE = "Account Lockout Check"
        const val HELP_TEXT = "로그인 실패로 차단된 계정의 접근을 제한하는 인증기"
        const val REFERENCE_CATEGORY = "security"
    }

    private val configProperties = ProviderConfigurationBuilder.create()
        // Redis 연결 설정
        .property()
        .name(AccountLockoutAuthenticator.REDIS_HOST_CONFIG)
        .label("Redis Host")
        .helpText("Redis 서버 호스트 주소")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(AccountLockoutAuthenticator.DEFAULT_REDIS_HOST)
        .add()

        .property()
        .name(AccountLockoutAuthenticator.REDIS_PORT_CONFIG)
        .label("Redis Port")
        .helpText("Redis 서버 포트 번호")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(AccountLockoutAuthenticator.DEFAULT_REDIS_PORT.toString())
        .add()

        .property()
        .name(AccountLockoutAuthenticator.REDIS_PASSWORD_CONFIG)
        .label("Redis Password")
        .helpText("Redis 서버 인증 비밀번호 (선택사항)")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(AccountLockoutAuthenticator.REDIS_DATABASE_CONFIG)
        .label("Redis Database")
        .helpText("Redis 데이터베이스 번호 (0-15)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(AccountLockoutAuthenticator.DEFAULT_REDIS_DATABASE.toString())
        .add()

        .build()

    /**
     * Authenticator 인스턴스 생성
     */
    override fun create(session: KeycloakSession): Authenticator {
        return AccountLockoutAuthenticator()
    }

    /**
     * 팩토리 초기화
     */
    override fun init(config: Config.Scope) {
        println("Account Lockout Authenticator Factory 초기화됨")

        // Redis 연결 설정 검증
        validateRedisConfiguration(config)
    }

    /**
     * 팩토리 종료 시 정리
     */
    override fun postInit(factory: KeycloakSessionFactory) {
        println("Account Lockout Authenticator Factory 후처리 완료")
    }

    /**
     * 팩토리 종료
     */
    override fun close() {
        println("Account Lockout Authenticator Factory 종료됨")
    }

    /**
     * Provider ID 반환
     */
    override fun getId(): String = PROVIDER_ID

    /**
     * 관리자 콘솔에서 표시될 이름
     */
    override fun getDisplayType(): String = DISPLAY_TYPE

    /**
     * 참조 카테고리
     */
    override fun getReferenceCategory(): String = REFERENCE_CATEGORY

    /**
     * 사용자가 필요한지 여부
     */
    override fun isUserSetupAllowed(): Boolean = false

    /**
     * 설정 가능한지 여부
     */
    override fun isConfigurable(): Boolean = true

    /**
     * 실행 요구사항 목록
     */
    override fun getRequirementChoices(): Array<AuthenticationExecutionModel.Requirement> {
        return arrayOf(
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.CONDITIONAL,
            AuthenticationExecutionModel.Requirement.DISABLED
        )
    }

    /**
     * 관리자 콘솔에서 표시될 도움말 텍스트
     */
    override fun getHelpText(): String = HELP_TEXT

    /**
     * 설정 속성 목록
     */
    override fun getConfigProperties(): List<ProviderConfigProperty> = configProperties

    /**
     * Redis 설정 검증
     */
    private fun validateRedisConfiguration(config: Config.Scope) {
        try {
            val redisHost = config.get(
                AccountLockoutAuthenticator.REDIS_HOST_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_HOST
            )
            val redisPort = config.getInt(
                AccountLockoutAuthenticator.REDIS_PORT_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_PORT
            )
            val redisDatabase = config.getInt(
                AccountLockoutAuthenticator.REDIS_DATABASE_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_DATABASE
            )

            // 기본 검증
            if (redisHost.isBlank()) {
                println("⚠️ Warning: Redis Host가 비어있습니다. 기본값 사용: ${AccountLockoutAuthenticator.DEFAULT_REDIS_HOST}")
            }

            if (redisPort <= 0 || redisPort > 65535) {
                println("⚠️ Warning: 잘못된 Redis Port: $redisPort. 기본값 사용: ${AccountLockoutAuthenticator.DEFAULT_REDIS_PORT}")
            }

            if (redisDatabase < 0 || redisDatabase > 15) {
                println("⚠️ Warning: 잘못된 Redis Database: $redisDatabase. 기본값 사용: ${AccountLockoutAuthenticator.DEFAULT_REDIS_DATABASE}")
            }

            println("✅ Account Lockout Authenticator Redis 설정 검증 완료")
            println("  - Redis 연결: $redisHost:$redisPort (DB: $redisDatabase)")

        } catch (e: Exception) {
            println("❌ Account Lockout Authenticator Redis 설정 검증 실패: ${e.message}")
        }
    }

    /**
     * 현재 설정값들을 Map으로 반환 (디버깅용)
     */
    fun getCurrentConfiguration(config: Config.Scope): Map<String, Any> {
        return mapOf(
            "redis.host" to config.get(
                AccountLockoutAuthenticator.REDIS_HOST_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_HOST
            ),
            "redis.port" to config.getInt(
                AccountLockoutAuthenticator.REDIS_PORT_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_PORT
            ),
            "redis.database" to config.getInt(
                AccountLockoutAuthenticator.REDIS_DATABASE_CONFIG,
                AccountLockoutAuthenticator.DEFAULT_REDIS_DATABASE
            ),
            "redis.password.configured" to !config.get(AccountLockoutAuthenticator.REDIS_PASSWORD_CONFIG, "").isBlank()
        )
    }

    /**
     * 설정 도움말 표시 (관리자용)
     */
    fun getConfigurationHelp(): String {
        return """
            |Account Lockout Authenticator 설정 가이드
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            |
            |📍 위치: Rate Limit 바로 다음, Username/Password 이전
            |   Browser Flow Forms (ALTERNATIVE)
            |   ├── 1. Token Bucket Rate Limit (REQUIRED)
            |   ├── 2. Account Lockout Check (REQUIRED)  ← 여기!
            |   ├── 3. Username Password Form (REQUIRED)
            |   └── ...
            |
            |⚙️  Redis 설정:
            |   - Event Listener와 동일한 Redis 서버 사용 권장
            |   - 별도 설정 시 차단 정보 동기화 불가
            |
            |🔧 권장 설정:
            |   - Requirement: REQUIRED (항상 실행)
            |   - Redis Host: Event Listener와 동일
            |   - Redis Database: Event Listener와 동일
            |
            |⚠️  주의사항:
            |   - Event Listener가 비활성화되면 차단 기능 동작 안함
            |   - Redis 연결 실패 시 fail-open (통과 허용)
            |
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimMargin()
    }

    /**
     * Event Listener와의 연동 상태 확인
     */
    fun checkEventListenerIntegration(): Map<String, Any> {
        return mapOf(
            "event_listener_required" to true,
            "event_listener_provider_id" to "account-lockout-event-listener",
            "integration_method" to "Redis 기반 데이터 공유",
            "recommended_flow_position" to "Rate Limit 다음, Username/Password 이전",
            "failure_mode" to "fail-open (Redis 장애 시 통과)"
        )
    }
}