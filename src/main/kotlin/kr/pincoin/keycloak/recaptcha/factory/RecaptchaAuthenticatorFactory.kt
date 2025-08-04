package kr.pincoin.keycloak.recaptcha.factory

import kr.pincoin.keycloak.recaptcha.authenticator.RecaptchaAuthenticator
import org.keycloak.Config
import org.keycloak.authentication.Authenticator
import org.keycloak.authentication.AuthenticatorFactory
import org.keycloak.models.AuthenticationExecutionModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.provider.ProviderConfigurationBuilder

class RecaptchaAuthenticatorFactory : AuthenticatorFactory {

    override fun create(session: KeycloakSession): Authenticator = RecaptchaAuthenticator()

    override fun getId(): String = RecaptchaAuthenticator.PROVIDER_ID

    override fun getDisplayType(): String = "reCAPTCHA Protection (Pincoin)"

    override fun getHelpText(): String = "Google reCAPTCHA를 사용한 봇 차단 및 스팸 방지 기능을 제공합니다"

    override fun getReferenceCategory(): String = "password"

    override fun isConfigurable(): Boolean = true

    override fun isUserSetupAllowed(): Boolean = false

    override fun getRequirementChoices(): Array<AuthenticationExecutionModel.Requirement> = arrayOf(
        AuthenticationExecutionModel.Requirement.REQUIRED,
        AuthenticationExecutionModel.Requirement.ALTERNATIVE,
        AuthenticationExecutionModel.Requirement.DISABLED
    )

    override fun getConfigProperties(): List<ProviderConfigProperty> =
        ProviderConfigurationBuilder.create()
            .property()
            .name("enabled")
            .label("활성화")
            .helpText("reCAPTCHA 검증 활성화 여부 (개발/테스트 환경에서는 false로 설정)")
            .type(ProviderConfigProperty.BOOLEAN_TYPE)
            .defaultValue("true")
            .add()
            .property()
            .name("siteKey")
            .label("Site Key")
            .helpText("Google reCAPTCHA Site Key (클라이언트 사이드용)")
            .type(ProviderConfigProperty.STRING_TYPE)
            .add()
            .property()
            .name("secretKey")
            .label("Secret Key")
            .helpText("Google reCAPTCHA Secret Key (서버 사이드 검증용)")
            .type(ProviderConfigProperty.PASSWORD)
            .add()
            .property()
            .name("recaptchaType")
            .label("reCAPTCHA 타입")
            .helpText("reCAPTCHA 버전 선택 (v2: 체크박스, v3: 투명한 점수 기반)")
            .type(ProviderConfigProperty.LIST_TYPE)
            .options(listOf("v2", "v3"))
            .defaultValue("v2")
            .add()
            .property()
            .name("minScore")
            .label("최소 점수 (v3 전용)")
            .helpText("reCAPTCHA v3 최소 허용 점수 (0.0 ~ 1.0, 높을수록 엄격)")
            .type(ProviderConfigProperty.STRING_TYPE)
            .defaultValue("0.5")
            .add()
            .build()

    override fun init(config: Config.Scope) {
        // 초기화 로직이 필요한 경우 구현
    }

    override fun postInit(factory: KeycloakSessionFactory) {
        // Post 초기화 로직이 필요한 경우 구현
    }

    override fun close() {
        // 정리 로직이 필요한 경우 구현
    }
}