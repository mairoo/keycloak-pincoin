package kr.pincoin.keycloak.provider

import kr.pincoin.keycloak.config.NaverIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.models.KeycloakSession

class NaverIdentityProvider(
    session: KeycloakSession,
    config: NaverIdentityProviderConfig,
) : OIDCIdentityProvider(session, config) {

    companion object {
        private const val AUTH_URL = "https://nid.naver.com/oauth2.0/authorize"
        private const val TOKEN_URL = "https://nid.naver.com/oauth2.0/token"
        private const val USERINFO_URL = "https://openapi.naver.com/v1/nid/me"
    }

    init {
        // NaverIdentityProvider 프로퍼티에 URL 설정
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 네이버 특화 설정
        config.defaultScope = "name email"
    }
}