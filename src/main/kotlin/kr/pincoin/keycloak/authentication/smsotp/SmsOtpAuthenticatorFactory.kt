package kr.pincoin.keycloak.authentication.smsotp

import org.keycloak.Config
import org.keycloak.authentication.Authenticator
import org.keycloak.authentication.AuthenticatorFactory
import org.keycloak.authentication.ConfigurableAuthenticatorFactory
import org.keycloak.models.AuthenticationExecutionModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class SmsOtpAuthenticatorFactory : AuthenticatorFactory, ConfigurableAuthenticatorFactory {

    companion object {
        const val PROVIDER_ID = "sms-otp-authenticator"
        const val DISPLAY_TYPE = "SMS OTP"
        const val HELP_TEXT = "휴대전화로 6자리 인증 코드를 발송하는 2FA 인증"
        const val REFERENCE_CATEGORY = "otp"
    }

    private val configProperties = ProviderConfigurationBuilder.create()
        // Redis 연결 설정
        .property()
        .name(SmsOtpAuthenticator.REDIS_HOST_CONFIG)
        .label("Redis Host")
        .helpText("Redis 서버 호스트 주소")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("localhost")
        .add()

        .property()
        .name(SmsOtpAuthenticator.REDIS_PORT_CONFIG)
        .label("Redis Port")
        .helpText("Redis 서버 포트 번호")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("6379")
        .add()

        .property()
        .name(SmsOtpAuthenticator.REDIS_PASSWORD_CONFIG)
        .label("Redis Password")
        .helpText("Redis 서버 인증 비밀번호 (선택사항)")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(SmsOtpAuthenticator.REDIS_DATABASE_CONFIG)
        .label("Redis Database")
        .helpText("Redis 데이터베이스 번호 (0-15)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("0")
        .add()

        // OTP 설정
        .property()
        .name(SmsOtpAuthenticator.OTP_LENGTH_CONFIG)
        .label("OTP 길이")
        .helpText("생성할 OTP 코드의 자릿수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("4", "6", "8"))
        .defaultValue("6")
        .add()

        .property()
        .name(SmsOtpAuthenticator.OTP_EXPIRY_MINUTES_CONFIG)
        .label("OTP 만료 시간(분)")
        .helpText("OTP 코드의 유효 시간(분)")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("1", "3", "5", "10"))
        .defaultValue("3")
        .add()

        .property()
        .name(SmsOtpAuthenticator.MAX_ATTEMPTS_CONFIG)
        .label("최대 시도 횟수")
        .helpText("OTP 입력 최대 시도 횟수")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("3", "5", "10"))
        .defaultValue("3")
        .add()

        .property()
        .name(SmsOtpAuthenticator.RESEND_COOLDOWN_SECONDS_CONFIG)
        .label("재발송 대기시간(초)")
        .helpText("OTP 재발송 요청 간격(초)")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("30", "60", "120", "300"))
        .defaultValue("60")
        .add()

        // SMS API 설정 (Aligo)
        .property()
        .name(SmsOtpAuthenticator.SMS_API_BASE_URL_CONFIG)
        .label("SMS API Base URL")
        .helpText("SMS 발송 API 기본 URL")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("https://apis.aligo.in")
        .add()

        .property()
        .name(SmsOtpAuthenticator.SMS_API_KEY_CONFIG)
        .label("SMS API Key")
        .helpText("SMS API 인증 키")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(SmsOtpAuthenticator.SMS_API_USER_ID_CONFIG)
        .label("SMS API User ID")
        .helpText("SMS API 사용자 ID")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("")
        .add()

        .property()
        .name(SmsOtpAuthenticator.SMS_API_SENDER_CONFIG)
        .label("SMS 발신번호")
        .helpText("SMS 발송 시 표시될 발신번호")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("")
        .add()

        .build()

    /**
     * Authenticator 인스턴스 생성
     */
    override fun create(
        session: KeycloakSession,
    ): Authenticator =
        SmsOtpAuthenticator()

    /**
     * 팩토리 초기화
     */
    override fun init(
        config: Config.Scope,
    ) {
        // 필요시 초기화 로직 추가
    }

    /**
     * 팩토리 종료 시 정리
     */
    override fun postInit(
        factory: KeycloakSessionFactory,
    ) {
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
    override fun getId(
    ): String =
        PROVIDER_ID

    /**
     * 관리자 콘솔에서 표시될 이름
     */
    override fun getDisplayType(
    ): String =
        DISPLAY_TYPE

    /**
     * 참조 카테고리
     */
    override fun getReferenceCategory(
    ): String =
        REFERENCE_CATEGORY

    /**
     * 사용자가 필요한지 여부
     */
    override fun isUserSetupAllowed(
    ): Boolean =
        false

    /**
     * 설정 가능한지 여부
     */
    override fun isConfigurable(
    ): Boolean =
        true

    /**
     * 실행 요구사항 목록
     */
    override fun getRequirementChoices(): Array<AuthenticationExecutionModel.Requirement> =
        arrayOf(
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.CONDITIONAL,
            AuthenticationExecutionModel.Requirement.DISABLED,
        )

    /**
     * 관리자 콘솔에서 표시될 도움말 텍스트
     */
    override fun getHelpText(
    ): String =
        HELP_TEXT

    /**
     * 설정 속성 목록
     */
    override fun getConfigProperties(
    ): List<ProviderConfigProperty> =
        configProperties
}