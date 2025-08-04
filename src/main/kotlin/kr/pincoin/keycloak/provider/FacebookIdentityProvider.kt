package kr.pincoin.keycloak.provider

import kr.pincoin.keycloak.config.FacebookIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.models.KeycloakSession

class FacebookIdentityProvider(
    session: KeycloakSession,
    config: FacebookIdentityProviderConfig,
) : OIDCIdentityProvider(session, config) {

    companion object {
        private const val AUTH_URL = "https://www.facebook.com/v18.0/dialog/oauth"
        private const val TOKEN_URL = "https://graph.facebook.com/v18.0/oauth/access_token"
        private const val USERINFO_URL = "https://graph.facebook.com/v18.0/me"
    }

    init {
        // FacebookIdentityProvider 프로퍼티에 URL 설정
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 페이스북 특화 설정
        config.defaultScope = "email public_profile"
    }
}