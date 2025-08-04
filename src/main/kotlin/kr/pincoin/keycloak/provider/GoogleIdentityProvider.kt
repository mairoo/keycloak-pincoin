package kr.pincoin.keycloak.provider

import kr.pincoin.keycloak.config.GoogleIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.models.KeycloakSession

class GoogleIdentityProvider(
    session: KeycloakSession,
    config: GoogleIdentityProviderConfig,
) : OIDCIdentityProvider(session, config) {

    companion object {
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"
    }

    init {
        // GoogleIdentityProvider 프로퍼티에 URL 설정
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL
    }
}