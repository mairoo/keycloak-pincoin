package kr.pincoin.keycloak.provider

import com.fasterxml.jackson.databind.JsonNode
import kr.pincoin.keycloak.config.KakaoIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
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
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 카카오 특화 설정
        config.defaultScope = "profile_nickname profile_image account_email"
    }

    override fun extractIdentityFromProfile(
        event: org.keycloak.events.EventBuilder?,
        profile: JsonNode
    ): BrokeredIdentityContext {
        val context = super.extractIdentityFromProfile(event, profile)

        // 카카오 응답 매핑: {"id": 123, "kakao_account": {"email": "...", "profile": {"nickname": "..."}}}
        val id = getJsonProperty(profile, "id")

        val kakaoAccount = profile.get("kakao_account")
        val email = if (kakaoAccount != null && !kakaoAccount.isNull) {
            getJsonProperty(kakaoAccount, "email")
        } else null

        val profileNode = kakaoAccount?.get("profile")
        val nickname = if (profileNode != null && !profileNode.isNull) {
            getJsonProperty(profileNode, "nickname")
        } else null

        context.id = id
        context.email = email
        context.firstName = nickname
        context.lastName = ""
        context.username = email ?: id

        // 추가 속성 설정
        context.setUserAttribute("provider", "kakao")
        context.setUserAttribute("social_id", id)
        context.setUserAttribute("nickname", nickname ?: "")

        return context
    }

    override fun getJsonProperty(jsonNode: JsonNode, name: String): String? {
        val child = jsonNode.get(name) ?: return null
        return if (child.isNull) null else child.asText()
    }
}