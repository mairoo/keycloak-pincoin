package kr.pincoin.keycloak.identity.factory

import kr.pincoin.keycloak.identity.config.KakaoIdentityProviderConfig
import kr.pincoin.keycloak.identity.provider.KakaoIdentityProvider
import org.keycloak.broker.provider.AbstractIdentityProviderFactory
import org.keycloak.models.IdentityProviderModel
import org.keycloak.models.KeycloakSession

class KakaoIdentityProviderFactory :
    AbstractIdentityProviderFactory<KakaoIdentityProvider>() {

    companion object {
        const val PROVIDER_ID = "kakao-pincoin"
    }

    override fun getId(): String = PROVIDER_ID

    override fun getName(): String = "Kakao Pincoin Custom"

    override fun create(
        session: KeycloakSession,
        model: IdentityProviderModel,
    ): KakaoIdentityProvider {
        val config = KakaoIdentityProviderConfig(model)
        return KakaoIdentityProvider(session, config)
    }

    override fun createConfig(): KakaoIdentityProviderConfig {
        return KakaoIdentityProviderConfig()
    }
}