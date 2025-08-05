package kr.pincoin.keycloak.identity.config

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig
import org.keycloak.models.IdentityProviderModel

class FacebookIdentityProviderConfig : OIDCIdentityProviderConfig {
    constructor() : super()
    constructor(model: IdentityProviderModel) : super(model)
}