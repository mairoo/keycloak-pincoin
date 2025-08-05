package kr.pincoin.keycloak.identity.provider

import com.fasterxml.jackson.databind.JsonNode
import kr.pincoin.keycloak.identity.config.FacebookIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
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
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        // 필드를 명시적으로 요청하도록 URL 수정
        config.userInfoUrl = "$USERINFO_URL?fields=id,email,first_name,last_name"

        // 페이스북 특화 설정
        config.defaultScope = "email public_profile"
    }

    override fun extractIdentityFromProfile(
        event: org.keycloak.events.EventBuilder?,
        profile: JsonNode
    ): BrokeredIdentityContext {
        val context = super.extractIdentityFromProfile(event, profile)

        // 페이스북 응답 매핑: {"id": "123", "email": "...", "first_name": "...", "last_name": "..."}
        val id = getJsonProperty(profile, "id")
        val email = getJsonProperty(profile, "email")
        val firstName = getJsonProperty(profile, "first_name")
        val lastName = getJsonProperty(profile, "last_name")

        context.id = id
        context.email = email
        context.firstName = firstName
        context.lastName = lastName
        context.username = email ?: id

        // 추가 속성 설정
        context.setUserAttribute("provider", "facebook")
        context.setUserAttribute("social_id", id ?: "")

        return context
    }

    override fun getJsonProperty(jsonNode: JsonNode, name: String): String? {
        val child = jsonNode.get(name) ?: return null
        return if (child.isNull) null else child.asText()
    }
}