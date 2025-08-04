package kr.pincoin.keycloak.storage

import kr.pincoin.keycloak.adapter.DjangoUserAdapter
import kr.pincoin.keycloak.model.DjangoUser
import org.keycloak.component.ComponentModel
import org.keycloak.credential.CredentialInput
import org.keycloak.credential.CredentialInputValidator
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel
import org.keycloak.models.credential.PasswordCredentialModel
import org.keycloak.storage.StorageId
import org.keycloak.storage.UserStorageProvider
import org.keycloak.storage.user.UserLookupProvider
import org.keycloak.storage.user.UserRegistrationProvider
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import javax.sql.DataSource

/**
 * Django allauth 스키마와 연동하는 User Storage Provider
 */
class DjangoUserStorageProvider(
    private val session: KeycloakSession,
    private val model: ComponentModel,
    private val dataSource: DataSource
) : UserStorageProvider,
    UserLookupProvider,
    UserRegistrationProvider,
    CredentialInputValidator {

    override fun getUserById(
        realm: RealmModel,
        id: String,
    ): UserModel? {
        val storageId = StorageId(id)
        val userId = storageId.externalId.toLongOrNull() ?: return null

        return findDjangoUserById(userId)
            ?.let { djangoUser ->
                DjangoUserAdapter(session, realm, model, djangoUser)
            }
    }

    override fun getUserByUsername(
        realm: RealmModel,
        username: String,
    ): UserModel? =
        findDjangoUserByEmail(username)
            ?.let { djangoUser ->
                DjangoUserAdapter(session, realm, model, djangoUser)
            }

    override fun getUserByEmail(
        realm: RealmModel,
        email: String,
    ): UserModel? =
        findDjangoUserByEmail(email)
            ?.let { djangoUser ->
                DjangoUserAdapter(session, realm, model, djangoUser)
            }

    override fun addUser(
        realm: RealmModel,
        username: String,
    ): UserModel =
        DjangoUserAdapter(session, realm, model, createDjangoUser(username, username))

    override fun removeUser(
        realm: RealmModel,
        user: UserModel,
    ): Boolean {
        return deleteDjangoUser(StorageId(user.id).externalId.toLongOrNull() ?: return false)
    }

    /**
     * 소셜 로그인 사용자 찾기 또는 생성
     */
    fun findOrCreateSocialUser(
        provider: String,
        socialUid: String,
        email: String,
        name: String,
        realm: RealmModel,
    ): UserModel {
        // 1. 기존 소셜 계정으로 검색
        findDjangoUserBySocialAccount(provider, socialUid)?.let { djangoUser ->
            return DjangoUserAdapter(session, realm, model, djangoUser)
        }

        // 2. 이메일로 기존 사용자 검색 후 소셜 계정 연결
        findDjangoUserByEmail(email)?.let { existingUser ->
            linkSocialAccount(existingUser.id, provider, socialUid)
            return DjangoUserAdapter(session, realm, model, existingUser)
        }

        // 3. 신규 사용자 + 소셜 계정 생성
        val newUser = createDjangoUserWithSocialAccount(email, name, provider, socialUid)
        return DjangoUserAdapter(session, realm, model, newUser)
    }

    // === Private Helper Methods ===

    private fun findDjangoUserById(
        userId: Long,
    ): DjangoUser? {
        val sql = "SELECT * FROM auth_user WHERE id = ? AND is_active = true"

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        DjangoUser(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            email = rs.getString("email"),
                            firstName = rs.getString("first_name") ?: "",
                            lastName = rs.getString("last_name") ?: "",
                            isActive = rs.getBoolean("is_active")
                        )
                    } else null
                }
            }
        }
    }

    private fun findDjangoUserByEmail(email: String): DjangoUser? {
        val sql = "SELECT * FROM auth_user WHERE email = ? AND is_active = true"

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        DjangoUser(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            email = rs.getString("email"),
                            firstName = rs.getString("first_name") ?: "",
                            lastName = rs.getString("last_name") ?: "",
                            isActive = rs.getBoolean("is_active")
                        )
                    } else null
                }
            }
        }
    }

    private fun findDjangoUserBySocialAccount(
        provider: String,
        uid: String,
    ): DjangoUser? {
        val sql = """
            SELECT u.* FROM auth_user u
            JOIN socialaccount_socialaccount sa ON u.id = sa.user_id
            WHERE sa.provider = ? AND sa.uid = ? AND u.is_active = true
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, provider)
                stmt.setString(2, uid)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        DjangoUser(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            email = rs.getString("email"),
                            firstName = rs.getString("first_name") ?: "",
                            lastName = rs.getString("last_name") ?: "",
                            isActive = rs.getBoolean("is_active")
                        )
                    } else null
                }
            }
        }
    }

    private fun createDjangoUser(
        username: String,
        email: String,
    ): DjangoUser {
        val sql = """
            INSERT INTO auth_user (username, email, first_name, last_name, is_active, is_staff, is_superuser, date_joined, last_login, password)
            VALUES (?, ?, '', '', true, false, false, ?, ?, '')
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { stmt ->
                val now = Timestamp.valueOf(LocalDateTime.now())
                stmt.setString(1, username)
                stmt.setString(2, email)
                stmt.setTimestamp(3, now) // date_joined
                stmt.setTimestamp(4, now) // last_login
                stmt.executeUpdate()

                stmt.generatedKeys.use { rs ->
                    if (rs.next()) {
                        val userId = rs.getLong(1)
                        DjangoUser(
                            id = userId,
                            username = username,
                            email = email,
                            firstName = "",
                            lastName = "",
                            isActive = true
                        )
                    } else {
                        throw RuntimeException("사용자 생성 실패")
                    }
                }
            }
        }
    }

    private fun createDjangoUserWithSocialAccount(
        email: String,
        name: String,
        provider: String,
        uid: String,
    ): DjangoUser {
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // 사용자 생성
                val user = createDjangoUser(email, email)

                // first_name 업데이트
                updateUserName(conn, user.id, name)

                // 소셜 계정 연결
                linkSocialAccount(conn, user.id, provider, uid)

                conn.commit()
                user.copy(firstName = name)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun updateUserName(
        conn: Connection,
        userId: Long,
        name: String,
    ) {
        val sql = "UPDATE auth_user SET first_name = ? WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, name)
            stmt.setLong(2, userId)
            stmt.executeUpdate()
        }
    }

    private fun linkSocialAccount(
        userId: Long,
        provider: String,
        uid: String,
    ) =
        dataSource.connection.use { conn ->
            linkSocialAccount(conn, userId, provider, uid)
        }

    private fun linkSocialAccount(
        conn: Connection,
        userId: Long,
        provider: String,
        uid: String,
    ) {
        val sql = """
            INSERT INTO socialaccount_socialaccount (provider, uid, user_id, date_joined, extra_data)
            VALUES (?, ?, ?, ?, '{}')
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, provider)
            stmt.setString(2, uid)
            stmt.setLong(3, userId)
            stmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()))
            stmt.executeUpdate()
        }
    }

    private fun deleteDjangoUser(
        userId: Long,
    ): Boolean {
        val sql = "UPDATE auth_user SET is_active = false WHERE id = ?"

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate() > 0
            }
        }
    }

    // === Credential Validation ===

    override fun supportsCredentialType(
        credentialType: String,
    ): Boolean =
        PasswordCredentialModel.TYPE == credentialType

    override fun isConfiguredFor(
        realm: RealmModel,
        user: UserModel,
        credentialType: String,
    ): Boolean =
        supportsCredentialType(credentialType)

    override fun isValid(
        realm: RealmModel,
        user: UserModel,
        credentialInput: CredentialInput,
    ): Boolean = // Django는 소셜 로그인만 사용하므로 패스워드 검증은 false
        false

    override fun close() {
        // DataSource는 Factory에서 관리
    }
}