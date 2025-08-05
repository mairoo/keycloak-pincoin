package kr.pincoin.keycloak.authentication.recaptcha

import org.keycloak.Config
import org.keycloak.authentication.Authenticator
import org.keycloak.authentication.AuthenticatorFactory
import org.keycloak.authentication.ConfigurableAuthenticatorFactory
import org.keycloak.models.AuthenticationExecutionModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class RecaptchaAuthenticatorFactory : AuthenticatorFactory, ConfigurableAuthenticatorFactory {

    companion object {
        const val PROVIDER_ID = "recaptcha-authenticator"
        const val DISPLAY_TYPE = "Google reCAPTCHA"
        const val HELP_TEXT = "Google reCAPTCHA v2/v3를 사용한 봇 방지 검증"
        const val REFERENCE_CATEGORY = "captcha"
    }

    private val configProperties = ProviderConfigurationBuilder.create()
        .property()
        .name(RecaptchaAuthenticator.RECAPTCHA_SITE_KEY_CONFIG)
        .label("reCAPTCHA Site Key")
        .helpText("Google reCAPTCHA 사이트 키 (클라이언트에서 사용)")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("")
        .add()

        .property()
        .name(RecaptchaAuthenticator.RECAPTCHA_SECRET_KEY_CONFIG)
        .label("reCAPTCHA Secret Key")
        .helpText("Google reCAPTCHA 비밀 키 (서버 검증용)")
        .type(ProviderConfigProperty.PASSWORD)
        .secret(true)
        .defaultValue("")
        .add()

        .property()
        .name(RecaptchaAuthenticator.RECAPTCHA_VERSION_CONFIG)
        .label("reCAPTCHA 버전")
        .helpText("사용할 reCAPTCHA 버전을 선택하세요")
        .type(ProviderConfigProperty.LIST_TYPE)
        .options(listOf("v2", "v3"))
        .defaultValue("v2")
        .add()

        .property()
        .name(RecaptchaAuthenticator.RECAPTCHA_MIN_SCORE_CONFIG)
        .label("최소 점수 (v3용)")
        .helpText("reCAPTCHA v3 최소 점수 (0.0 ~ 1.0, 기본값: 0.5). v2에서는 무시됩니다.")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("0.5")
        .add()

        .property()
        .name(RecaptchaAuthenticator.RECAPTCHA_ACTION_CONFIG)
        .label("액션 (v3용)")
        .helpText("reCAPTCHA v3 액션 이름 (예: login, register). v2에서는 무시됩니다.")
        .type(ProviderConfigProperty.STRING_TYPE)
        .defaultValue("login")
        .add()

        .build()

    /**
     * Authenticator 인스턴스 생성
     */
    override fun create(session: KeycloakSession): Authenticator {
        return RecaptchaAuthenticator()
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