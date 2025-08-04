package kr.pincoin.keycloak.factory

import kr.pincoin.keycloak.config.NaverIdentityProviderConfig
import kr.pincoin.keycloak.provider.NaverIdentityProvider
import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession

class NaverIdentityProviderFactory :
    AbstractIdentityProviderFactory<NaverIdentityProvider>() {

    companion object {
        const val PROVIDER_ID = "naver-pincoin"
    }

    override fun getId(): String = PROVIDER_ID

    override fun getName(): String = "Naver Pincoin Custom"

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): NaverIdentityProvider {
        val config = NaverIdentityProviderConfig(model)
        return NaverIdentityProvider(session, config)
    }

    override fun createConfig(): NaverIdentityProviderConfig {
        return NaverIdentityProviderConfig()
    }
}