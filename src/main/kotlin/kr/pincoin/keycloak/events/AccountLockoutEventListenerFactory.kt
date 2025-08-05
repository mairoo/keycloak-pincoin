package kr.pincoin.keycloak.events

import org.keycloak.Config
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class AccountLockoutEventListenerFactory : EventListenerProviderFactory {

    companion object {
        const val PROVIDER_ID = "account-lockout-event-listener"
        const val DISPLAY_NAME = "Account Lockout Event Listener"
        const val HELP_TEXT = "로그인 실패 시 계정을 자동으로 차단하는 이벤트 리스너"

        // 설정 키들
        const val REDIS_HOST_CONFIG = "redis.host"
        const val REDIS_PORT_CONFIG = "redis.port"
        const val REDIS_PASSWORD_CONFIG = "redis.password"
        const val REDIS_DATABASE_CONFIG = "redis.database"

        const val FAILURE_THRESHOLD_1H_CONFIG = "failure.threshold.1h"
        const val FAILURE_THRESHOLD_24H_CONFIG = "failure.threshold.24h"
        const val WARNING_THRESHOLD_CONFIG = "warning.threshold"

        const val LOCKOUT_DURATION_1H_CONFIG = "lockout.duration.1h"
        const val LOCKOUT_DURATION_24H_CONFIG = "lockout.duration.24h"
        const val FAILURE_WINDOW_MINUTES_CONFIG = "failure.window.minutes"

        const val SUSPICIOUS_IP_THRESHOLD_CONFIG = "suspicious.ip.threshold"
        const val ENABLE_IP_BLOCKING_CONFIG = "enable.ip.blocking"
        const val ENABLE_NOTIFICATIONS_CONFIG = "enable.notifications"

        // 기본값들
        const val DEFAULT_REDIS_HOST = "localhost"
        const val DEFAULT_REDIS_PORT = 6379
        const val DEFAULT_REDIS_DATABASE = 0

        const val DEFAULT_FAILURE_THRESHOLD_1H = 5
        const val DEFAULT_FAILURE_THRESHOLD_24H = 10
        const val DEFAULT_WARNING_THRESHOLD = 3

        const val DEFAULT_LOCKOUT_DURATION_1H = 60 // 분
        const val DEFAULT_LOCKOUT_DURATION_24H = 1440 // 분 (24시간)
        const val DEFAULT_FAILURE_WINDOW_MINUTES = 60 // 실패 카운트 윈도우

        const val DEFAULT_SUSPICIOUS_IP_THRESHOLD = 10
        const val DEFAULT_ENABLE_IP_BLOCKING = false
        const val DEFAULT_ENABLE_NOTIFICATIONS = true
    }

    private val configProperties = ProviderConfigurationBuilder.create()
        // Redis 연결 설정
        .property()
        .name(REDIS_HOST_CONFIG)
        .label("Redis Host")
        .helpText("Redis 서버 호스트 주소")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(DEFAULT_REDIS_HOST)
        .add()

        .property()
        .name(REDIS_PORT_CONFIG)
        .label("Redis Port")
        .helpText("Redis 서버 포트 번호")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(DEFAULT_REDIS_PORT.toString())
        .add()

        .property()
        .name(REDIS_PASSWORD_CONFIG)
        .label("Redis Password")
        .helpText("Redis 서버 인증 비밀번호 (선택사항)")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(REDIS_DATABASE_CONFIG)
        .label("Redis Database")
        .helpText("Redis 데이터베이스 번호 (0-15)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue(DEFAULT_REDIS_DATABASE.toString())
        .add()

        // 차단 임계값 설정
        .property()
        .name(FAILURE_THRESHOLD_1H_CONFIG)
        .label("1시간 차단 임계값")
        .helpText("1시간 차단을 적용할 로그인 실패 횟수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("3", "5", "7", "10"))
        .defaultValue(DEFAULT_FAILURE_THRESHOLD_1H.toString())
        .add()

        .property()
        .name(FAILURE_THRESHOLD_24H_CONFIG)
        .label("24시간 차단 임계값")
        .helpText("24시간 차단을 적용할 로그인 실패 횟수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("8", "10", "15", "20"))
        .defaultValue(DEFAULT_FAILURE_THRESHOLD_24H.toString())
        .add()

        .property()
        .name(WARNING_THRESHOLD_CONFIG)
        .label("경고 임계값")
        .helpText("경고 알림을 발송할 로그인 실패 횟수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("2", "3", "4", "5"))
        .defaultValue(DEFAULT_WARNING_THRESHOLD.toString())
        .add()

        // 차단 지속 시간 설정
        .property()
        .name(LOCKOUT_DURATION_1H_CONFIG)
        .label("1단계 차단 지속시간(분)")
        .helpText("1단계 차단의 지속 시간(분)")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("30", "60", "120", "180"))
        .defaultValue(DEFAULT_LOCKOUT_DURATION_1H.toString())
        .add()

        .property()
        .name(LOCKOUT_DURATION_24H_CONFIG)
        .label("2단계 차단 지속시간(분)")
        .helpText("2단계 차단의 지속 시간(분)")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("720", "1440", "2880", "4320")) // 12h, 24h, 48h, 72h
        .defaultValue(DEFAULT_LOCKOUT_DURATION_24H.toString())
        .add()

        .property()
        .name(FAILURE_WINDOW_MINUTES_CONFIG)
        .label("실패 카운트 윈도우(분)")
        .helpText("실패 횟수를 계산할 시간 윈도우(분)")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("30", "60", "120", "240"))
        .defaultValue(DEFAULT_FAILURE_WINDOW_MINUTES.toString())
        .add()

        // 보안 및 알림 설정
        .property()
        .name(SUSPICIOUS_IP_THRESHOLD_CONFIG)
        .label("의심스러운 IP 임계값")
        .helpText("동일 IP에서 공격할 수 있는 최대 계정 수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("5", "10", "15", "20"))
        .defaultValue(DEFAULT_SUSPICIOUS_IP_THRESHOLD.toString())
        .add()

        .property()
        .name(ENABLE_IP_BLOCKING_CONFIG)
        .label("IP 차단 활성화")
        .helpText("의심스러운 IP에 대한 차단 기능 활성화")
        .type(ProviderConfigProperty.BOOLEAN_TYPE)
        .defaultValue(DEFAULT_ENABLE_IP_BLOCKING.toString())
        .add()

        .property()
        .name(ENABLE_NOTIFICATIONS_CONFIG)
        .label("알림 활성화")
        .helpText("계정 차단 시 알림 발송 활성화")
        .type(ProviderConfigProperty.BOOLEAN_TYPE)
        .defaultValue(DEFAULT_ENABLE_NOTIFICATIONS.toString())
        .add()

        .build()

    private var factoryConfig: Map<String, String> = emptyMap()

    /**
     * Event Listener 인스턴스 생성
     */
    override fun create(session: KeycloakSession): EventListenerProvider {
        return AccountLockoutEventListener(session, factoryConfig)
    }

    /**
     * Provider ID 반환
     */
    override fun getId(): String = PROVIDER_ID

    /**
     * 팩토리 초기화
     */
    override fun init(config: Config.Scope) {
        println("Account Lockout Event Listener Factory 초기화됨")

        // 설정값 검증
        validateConfiguration(config)
    }

    /**
     * 세션 팩토리 후처리
     */
    override fun postInit(factory: KeycloakSessionFactory) {
        println("Account Lockout Event Listener Factory 후처리 완료")
    }

    /**
     * 팩토리 종료
     */
    override fun close() {
        println("Account Lockout Event Listener Factory 종료됨")
    }

    /**
     * 설정 속성 목록 반환
     * Admin Console에서 이 설정들을 UI로 제공
     */
    fun getConfigProperties(): List<ProviderConfigProperty> = configProperties

    /**
     * Admin Console에서 표시될 이름
     */
    fun getDisplayName(): String = DISPLAY_NAME

    /**
     * Admin Console에서 표시될 도움말
     */
    fun getHelpText(): String = HELP_TEXT

    /**
     * 설정 가능 여부
     */
    fun isConfigurable(): Boolean = true

    /**
     * 설정값 검증
     */
    private fun validateConfiguration(config: Config.Scope) {
        try {
            // Redis 연결 설정 검증
            val redisHost = config.get(REDIS_HOST_CONFIG, DEFAULT_REDIS_HOST)
            val redisPort = config.getInt(REDIS_PORT_CONFIG, DEFAULT_REDIS_PORT)

            if (redisHost.isBlank()) {
                println("⚠️ Warning: Redis Host가 비어있습니다. 기본값 사용: $DEFAULT_REDIS_HOST")
            }

            if (redisPort <= 0 || redisPort > 65535) {
                println("⚠️ Warning: 잘못된 Redis Port: $redisPort. 기본값 사용: $DEFAULT_REDIS_PORT")
            }

            // 임계값 설정 검증
            val threshold1h = config.getInt(FAILURE_THRESHOLD_1H_CONFIG, DEFAULT_FAILURE_THRESHOLD_1H)
            val threshold24h = config.getInt(FAILURE_THRESHOLD_24H_CONFIG, DEFAULT_FAILURE_THRESHOLD_24H)
            val warningThreshold = config.getInt(WARNING_THRESHOLD_CONFIG, DEFAULT_WARNING_THRESHOLD)

            if (threshold1h >= threshold24h) {
                println("⚠️ Warning: 1시간 차단 임계값($threshold1h)이 24시간 차단 임계값($threshold24h)보다 크거나 같습니다")
            }

            if (warningThreshold >= threshold1h) {
                println("⚠️ Warning: 경고 임계값($warningThreshold)이 1시간 차단 임계값($threshold1h)보다 크거나 같습니다")
            }

            // 차단 지속시간 검증
            val duration1h = config.getInt(LOCKOUT_DURATION_1H_CONFIG, DEFAULT_LOCKOUT_DURATION_1H)
            val duration24h = config.getInt(LOCKOUT_DURATION_24H_CONFIG, DEFAULT_LOCKOUT_DURATION_24H)

            if (duration1h >= duration24h) {
                println("⚠️ Warning: 1단계 차단 시간(${duration1h}분)이 2단계 차단 시간(${duration24h}분)보다 크거나 같습니다")
            }

            println("✅ Account Lockout 설정 검증 완료")
            println("  - 1시간 차단: ${threshold1h}회 실패 시 ${duration1h}분 차단")
            println("  - 24시간 차단: ${threshold24h}회 실패 시 ${duration24h}분 차단")
            println("  - 경고 발송: ${warningThreshold}회 실패 시")

        } catch (e: Exception) {
            println("❌ Account Lockout 설정 검증 실패: ${e.message}")
        }
    }

    /**
     * 현재 설정값들을 Map으로 반환 (디버깅용)
     */
    fun getCurrentConfiguration(config: Config.Scope): Map<String, Any> {
        return mapOf(
            "redis.host" to config.get(REDIS_HOST_CONFIG, DEFAULT_REDIS_HOST),
            "redis.port" to config.getInt(REDIS_PORT_CONFIG, DEFAULT_REDIS_PORT),
            "redis.database" to config.getInt(REDIS_DATABASE_CONFIG, DEFAULT_REDIS_DATABASE),
            "failure.threshold.1h" to config.getInt(FAILURE_THRESHOLD_1H_CONFIG, DEFAULT_FAILURE_THRESHOLD_1H),
            "failure.threshold.24h" to config.getInt(FAILURE_THRESHOLD_24H_CONFIG, DEFAULT_FAILURE_THRESHOLD_24H),
            "warning.threshold" to config.getInt(WARNING_THRESHOLD_CONFIG, DEFAULT_WARNING_THRESHOLD),
            "lockout.duration.1h" to config.getInt(LOCKOUT_DURATION_1H_CONFIG, DEFAULT_LOCKOUT_DURATION_1H),
            "lockout.duration.24h" to config.getInt(LOCKOUT_DURATION_24H_CONFIG, DEFAULT_LOCKOUT_DURATION_24H),
            "failure.window.minutes" to config.getInt(FAILURE_WINDOW_MINUTES_CONFIG, DEFAULT_FAILURE_WINDOW_MINUTES),
            "suspicious.ip.threshold" to config.getInt(SUSPICIOUS_IP_THRESHOLD_CONFIG, DEFAULT_SUSPICIOUS_IP_THRESHOLD),
            "enable.ip.blocking" to config.getBoolean(ENABLE_IP_BLOCKING_CONFIG, DEFAULT_ENABLE_IP_BLOCKING),
            "enable.notifications" to config.getBoolean(ENABLE_NOTIFICATIONS_CONFIG, DEFAULT_ENABLE_NOTIFICATIONS)
        )
    }
}