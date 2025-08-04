package kr.pincoin.keycloak.config

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig
import org.keycloak.models.IdentityProviderModel

class GoogleIdentityProviderConfig : OIDCIdentityProviderConfig {
    constructor() : super()
    constructor(model: IdentityProviderModel) : super(model)
}