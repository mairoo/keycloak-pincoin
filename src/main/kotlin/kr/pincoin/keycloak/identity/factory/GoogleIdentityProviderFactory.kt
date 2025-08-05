package kr.pincoin.keycloak.identity.factory

import kr.pincoin.keycloak.identity.config.GoogleIdentityProviderConfig
import kr.pincoin.keycloak.identity.provider.GoogleIdentityProvider
import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession

class GoogleIdentityProviderFactory :
    AbstractIdentityProviderFactory<GoogleIdentityProvider>() {

    companion object {
        const val PROVIDER_ID = "google-pincoin"
    }

    override fun getId(): String = PROVIDER_ID

    override fun getName(): String = "Google Pincoin Custom"

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): GoogleIdentityProvider {
        val config = GoogleIdentityProviderConfig(model)
        return GoogleIdentityProvider(session, config)
    }

    override fun createConfig(
    ): GoogleIdentityProviderConfig =
        GoogleIdentityProviderConfig()
}