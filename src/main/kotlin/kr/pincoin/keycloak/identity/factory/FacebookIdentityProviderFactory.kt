package kr.pincoin.keycloak.identity.factory

import kr.pincoin.keycloak.identity.config.FacebookIdentityProviderConfig
import kr.pincoin.keycloak.identity.provider.FacebookIdentityProvider
import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession

class FacebookIdentityProviderFactory :
    AbstractIdentityProviderFactory<FacebookIdentityProvider>() {

    companion object {
        const val PROVIDER_ID = "facebook-pincoin"
    }

    override fun getId(): String = PROVIDER_ID

    override fun getName(): String = "Facebook Pincoin Custom"

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): FacebookIdentityProvider {
        val config = FacebookIdentityProviderConfig(model)
        return FacebookIdentityProvider(session, config)
    }

    override fun createConfig(): FacebookIdentityProviderConfig {
        return FacebookIdentityProviderConfig()
    }
}