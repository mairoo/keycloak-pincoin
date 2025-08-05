package kr.pincoin.keycloak.authentication.ratelimit

import org.keycloak.Config
import org.keycloak.authentication.Authenticator
import org.keycloak.authentication.AuthenticatorFactory
import org.keycloak.authentication.ConfigurableAuthenticatorFactory
import org.keycloak.models.AuthenticationExecutionModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class RateLimitAuthenticatorFactory : AuthenticatorFactory, ConfigurableAuthenticatorFactory {
    companion object {
        const val PROVIDER_ID = "rate-limit-authenticator"
        const val DISPLAY_TYPE = "Token Bucket Rate Limit"
        const val HELP_TEXT = "Redis 기반 Token Bucket 방식의 Rate Limit"
        const val REFERENCE_CATEGORY = "security"
    }

    private val configProperties = ProviderConfigurationBuilder.create()
        // Redis 연결 설정
        .property()
        .name(RateLimitAuthenticator.REDIS_HOST_CONFIG)
        .label("Redis Host")
        .helpText("Redis 서버 호스트 주소")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("localhost")
        .add()

        .property()
        .name(RateLimitAuthenticator.REDIS_PORT_CONFIG)
        .label("Redis Port")
        .helpText("Redis 서버 포트 번호")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("6379")
        .add()

        .property()
        .name(RateLimitAuthenticator.REDIS_PASSWORD_CONFIG)
        .label("Redis Password")
        .helpText("Redis 서버 인증 비밀번호 (선택사항)")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(RateLimitAuthenticator.REDIS_DATABASE_CONFIG)
        .label("Redis Database")
        .helpText("Redis 데이터베이스 번호 (0-15)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("0")
        .add()

        // IP 기준 Token Bucket 설정
        .property()
        .name(RateLimitAuthenticator.IP_CAPACITY_CONFIG)
        .label("IP 버킷 크기")
        .helpText("IP별 최대 burst 허용량 (동시 요청 수)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("30")
        .add()

        .property()
        .name(RateLimitAuthenticator.IP_REFILL_RATE_CONFIG)
        .label("IP 토큰 보충률")
        .helpText("IP별 초당 토큰 보충 개수 (1.0 = 초당 1개)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("1.0")
        .add()

        .property()
        .name(RateLimitAuthenticator.IP_TTL_CONFIG)
        .label("IP 데이터 보관시간(초)")
        .helpText("IP 관련 Redis 데이터 보관 시간")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("3600")
        .add()

        // 사용자 기준 Token Bucket 설정
        .property()
        .name(RateLimitAuthenticator.USER_CAPACITY_CONFIG)
        .label("사용자 버킷 크기")
        .helpText("사용자별 최대 burst 허용량")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("5")
        .add()

        .property()
        .name(RateLimitAuthenticator.USER_REFILL_RATE_CONFIG)
        .label("사용자 토큰 보충률")
        .helpText("사용자별 초당 토큰 보충 개수 (0.0167 = 분당 1개)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("0.0167")
        .add()

        .property()
        .name(RateLimitAuthenticator.USER_TTL_CONFIG)
        .label("사용자 데이터 보관시간(초)")
        .helpText("사용자 관련 Redis 데이터 보관 시간")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("900")
        .add()

        // 조합 기준 Token Bucket 설정
        .property()
        .name(RateLimitAuthenticator.COMBINED_CAPACITY_CONFIG)
        .label("조합 버킷 크기")
        .helpText("IP+사용자 조합별 최대 burst 허용량")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("3")
        .add()

        .property()
        .name(RateLimitAuthenticator.COMBINED_REFILL_RATE_CONFIG)
        .label("조합 토큰 보충률")
        .helpText("조합별 초당 토큰 보충 개수 (0.0033 = 5분당 1개)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("0.0033")
        .add()

        .property()
        .name(RateLimitAuthenticator.COMBINED_TTL_CONFIG)
        .label("조합 데이터 보관시간(초)")
        .helpText("조합 관련 Redis 데이터 보관 시간")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("300")
        .add()

        .build()

    /**
     * Authenticator 인스턴스 생성
     */
    override fun create(session: KeycloakSession): Authenticator {
        return RateLimitAuthenticator()
    }

    /**
     * 팩토리 초기화
     */
    override fun init(config: Config.Scope) {
        // 필요시 초기화 로직 추가
    }

    /**
     * 팩토리 종료 시 정리
     */
    override fun postInit(factory: KeycloakSessionFactory) {
        // 필요시 종료 로직 추가
    }

    /**
     * 팩토리 종료
     */
    override fun close() {
        // 필요시 리소스 정리
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
}