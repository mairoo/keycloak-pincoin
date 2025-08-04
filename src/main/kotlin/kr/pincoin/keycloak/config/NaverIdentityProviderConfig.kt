package kr.pincoin.keycloak.config

import org.keycloak.broker.oidc.OIDCIdentityProviderConfig
import org.keycloak.models.IdentityProviderModel

class NaverIdentityProviderConfig : OIDCIdentityProviderConfig {
    constructor() : super()
    constructor(model: IdentityProviderModel) : super(model)
}