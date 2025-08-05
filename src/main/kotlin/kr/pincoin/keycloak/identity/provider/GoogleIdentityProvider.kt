package kr.pincoin.keycloak.identity.provider

import com.fasterxml.jackson.databind.JsonNode
import kr.pincoin.keycloak.identity.config.GoogleIdentityProviderConfig
import org.keycloak.broker.oidc.OIDCIdentityProvider
import org.keycloak.broker.provider.BrokeredIdentityContext
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
        config.authorizationUrl = AUTH_URL
        config.tokenUrl = TOKEN_URL
        config.userInfoUrl = USERINFO_URL

        // 구글 특화 설정
        config.defaultScope = "openid email profile"
    }

    override fun extractIdentityFromProfile(
        event: org.keycloak.events.EventBuilder?,
        profile: JsonNode
    ): BrokeredIdentityContext {
        val context = super.extractIdentityFromProfile(event, profile)

        // 구글 응답 매핑: {"sub": "123", "email": "user@gmail.com", "given_name": "John", "family_name": "Doe"}
        val id = getJsonProperty(profile, "sub")
        val email = getJsonProperty(profile, "email")
        val firstName = getJsonProperty(profile, "given_name")
        val lastName = getJsonProperty(profile, "family_name")

        context.id = id
        context.email = email
        context.firstName = firstName
        context.lastName = lastName
        context.username = email ?: id

        // 추가 속성 설정
        context.setUserAttribute("provider", "google")
        context.setUserAttribute("social_id", id)

        return context
    }

    override fun getJsonProperty(jsonNode: JsonNode, name: String): String? {
        val child = jsonNode.get(name) ?: return null
        return if (child.isNull) null else child.asText()
    }
}