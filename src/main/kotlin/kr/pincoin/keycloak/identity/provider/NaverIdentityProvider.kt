package kr.pincoin.keycloak.identity.provider

import com.fasterxml.jackson.databind.JsonNode
import kr.pincoin.keycloak.identity.config.NaverIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
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
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 네이버 특화 설정
        config.defaultScope = "name email"
    }

    override fun extractIdentityFromProfile(
        event: org.keycloak.events.EventBuilder?,
        profile: JsonNode
    ): BrokeredIdentityContext {
        val context = super.extractIdentityFromProfile(event, profile)

        // 네이버 응답 매핑: {"response": {"id": "123", "email": "...", "name": "..."}}
        val response = profile.get("response")
        if (response != null && !response.isNull) {
            val id = getJsonProperty(response, "id")
            val email = getJsonProperty(response, "email")
            val name = getJsonProperty(response, "name")

            context.id = id
            context.email = email
            context.firstName = name
            context.lastName = ""
            context.username = email ?: id

            // 추가 속성 설정
            context.setUserAttribute("provider", "naver")
            context.setUserAttribute("social_id", id ?: "")
        }

        return context
    }

    override fun getJsonProperty(jsonNode: JsonNode, name: String): String? {
        val child = jsonNode.get(name) ?: return null
        return if (child.isNull) null else child.asText()
    }
}