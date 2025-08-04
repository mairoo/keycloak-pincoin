package kr.pincoin.keycloak.adapter

import kr.pincoin.keycloak.model.DjangoUser
import org.keycloak.component.ComponentModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.storage.StorageId
import org.keycloak.storage.adapter.AbstractUserAdapterFederatedStorage

/**
 * Django User를 Keycloak UserModel로 변환하는 어댑터
 */
class DjangoUserAdapter(
    session: KeycloakSession,
    realm: RealmModel,
    storageProviderModel: ComponentModel,
    private val djangoUser: DjangoUser,
) : AbstractUserAdapterFederatedStorage(session, realm, storageProviderModel) {

    override fun getUsername(): String =
        djangoUser.username

    override fun setUsername(username: String) {
        // Django DB 업데이트 로직 필요시 구현
    }

    override fun getEmail(): String =
        djangoUser.email

    override fun setEmail(email: String) {
        // Django DB 업데이트 로직 필요시 구현
    }

    override fun isEmailVerified(): Boolean =
        true

    override fun setEmailVerified(verified: Boolean) {
        // Django에서는 소셜 로그인으로 검증된 것으로 간주
    }

    override fun getFirstName(): String =
        djangoUser.firstName

    override fun setFirstName(firstName: String) {
        // Django DB 업데이트 로직 필요시 구현
    }

    override fun getLastName(): String =
        djangoUser.lastName

    override fun setLastName(lastName: String) {
        // Django DB 업데이트 로직 필요시 구현
    }

    override fun isEnabled(): Boolean =
        djangoUser.isActive

    override fun setEnabled(enabled: Boolean) {
        // Django DB 업데이트 로직 필요시 구현
    }

    override fun getId(): String =
        StorageId(storageProviderModel.id, djangoUser.id.toString()).id
}