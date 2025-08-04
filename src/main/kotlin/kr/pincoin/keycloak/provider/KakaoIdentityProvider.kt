package kr.pincoin.keycloak.provider

import kr.pincoin.keycloak.config.KakaoIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.models.KeycloakSession

class KakaoIdentityProvider(
    session: KeycloakSession,
    config: KakaoIdentityProviderConfig,
) : OIDCIdentityProvider(session, config) {

    companion object {
        private const val AUTH_URL = "https://kauth.kakao.com/oauth/authorize"
        private const val TOKEN_URL = "https://kauth.kakao.com/oauth/token"
        private const val USERINFO_URL = "https://kapi.kakao.com/v2/user/me"
    }

    init {
        // KakaoIdentityProvider 프로퍼티에 URL 설정
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 카카오 특화 설정
        config.defaultScope = "profile_nickname profile_image account_email"
    }
}