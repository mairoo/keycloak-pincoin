package kr.pincoin.keycloak.util

import org.keycloak.models.KeycloakSession
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * Django allauth 스키마와 매핑하는 핵심 유틸리티
 *
 * 모든 Custom Identity Provider에서 공통으로 사용
 * auth_user, socialaccount_socialaccount 테이블과 연동
 */
class DjangoUserMappingUtil(
    private val session: KeycloakSession,
) {
    // Keycloak에서 Django DB 접근을 위한 DataSource
    private val dataSource: DataSource = getDjangoDataSource()

    /**
     * 🔥 핵심 메서드: 소셜 로그인 사용자를 Django DB와 매핑
     *
     * 우선순위:
     * 1. socialaccount_socialaccount에서 동일한 provider+uid 검색
     * 2. auth_user에서 이메일로 기존 사용자 검색 → 소셜 계정 연결
     * 3. 둘 다 없으면 신규 사용자 + 소셜 계정 생성
     */
    fun findOrCreateUser(
        provider: String,
        socialUid: String,
        email: String,
        name: String,
    ): DjangoUser =
        dataSource.connection.use { conn ->
            // 1. 기존 소셜 계정으로 사용자 검색
            findUserBySocialAccount(conn, provider, socialUid)?.let { existingUser ->
                return existingUser
            }

            // 2. 이메일로 기존 사용자 검색
            findUserByEmail(conn, email)?.let { existingUser ->
                // 기존 사용자에 소셜 계정 연결
                linkSocialAccount(conn, existingUser.id, provider, socialUid)
                return existingUser
            }

            // 3. 신규 사용자 + 소셜 계정 생성
            return createUserWithSocialAccount(conn, email, name, provider, socialUid)
        }

    /**
     * socialaccount_socialaccount 테이블에서 기존 소셜 계정 검색
     */
    private fun findUserBySocialAccount(
        conn: Connection,
        provider: String,
        uid: String,
    ): DjangoUser? {
        val sql = """
            SELECT u.id, u.username, u.email, u.first_name, u.last_name, u.is_active
            FROM auth_user u
            JOIN socialaccount_socialaccount sa ON u.id = sa.user_id
            WHERE sa.provider = ? AND sa.uid = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, provider)
            stmt.setString(2, uid)

            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return DjangoUser(
                        id = rs.getLong("id"),
                        username = rs.getString("username"),
                        email = rs.getString("email"),
                        firstName = rs.getString("first_name"),
                        lastName = rs.getString("last_name"),
                        isActive = rs.getBoolean("is_active")
                    )
                }
            }
        }
        return null
    }

    /**
     * auth_user 테이블에서 이메일로 기존 사용자 검색
     */
    private fun findUserByEmail(conn: Connection, email: String): DjangoUser? {
        val sql = """
            SELECT id, username, email, first_name, last_name, is_active
            FROM auth_user
            WHERE email = ? AND is_active = true
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, email)

            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return DjangoUser(
                        id = rs.getLong("id"),
                        username = rs.getString("username"),
                        email = rs.getString("email"),
                        firstName = rs.getString("first_name"),
                        lastName = rs.getString("last_name"),
                        isActive = rs.getBoolean("is_active")
                    )
                }
            }
        }
        return null
    }

    /**
     * 기존 사용자에 소셜 계정 연결
     */
    private fun linkSocialAccount(
        conn: Connection,
        userId: Long,
        provider: String,
        uid: String,
    ) {
        val sql = """
            INSERT INTO socialaccount_socialaccount 
            (provider, uid, user_id, date_joined, extra_data) 
            VALUES (?, ?, ?, NOW(), '{}')
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, provider)
            stmt.setString(2, uid)
            stmt.setLong(3, userId)
            stmt.executeUpdate()
        }
    }

    /**
     * 신규 사용자 + 소셜 계정 생성
     */
    private fun createUserWithSocialAccount(
        conn: Connection,
        email: String,
        name: String,
        provider: String,
        uid: String,
    ): DjangoUser {
        conn.autoCommit = false

        try {
            // 1. 신규 사용자 생성
            val userId = createUser(conn, email, name)

            // 2. 소셜 계정 연결
            linkSocialAccount(conn, userId, provider, uid)

            conn.commit()

            return DjangoUser(
                id = userId,
                username = email,
                email = email,
                firstName = name,
                lastName = "",
                isActive = true,
            )

        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * auth_user 테이블에 신규 사용자 생성
     */
    private fun createUser(
        conn: Connection,
        email: String,
        name: String,
    ): Long {
        val sql = """
            INSERT INTO auth_user 
            (username, email, first_name, last_name, is_active, is_staff, is_superuser, 
             date_joined, password) 
            VALUES (?, ?, ?, '', true, false, false, NOW(), '')
        """.trimIndent()

        conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { stmt ->
            stmt.setString(1, email) // username = email
            stmt.setString(2, email)
            stmt.setString(3, name)

            stmt.executeUpdate()

            stmt.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                } else {
                    throw RuntimeException("사용자 생성 실패")
                }
            }
        }
    }

    /**
     * Django DB DataSource 가져오기
     */
    private fun getDjangoDataSource(): DataSource {
        // Keycloak에서 Django DB 연결 설정
        // 환경변수나 시스템 프로퍼티에서 DB 정보 가져오기
        // 실제 구현 시 HikariCP 등 사용
        throw NotImplementedError("Django DB DataSource 설정 필요")
    }

    /**
     * Django 사용자 정보 데이터 클래스
     */
    data class DjangoUser(
        val id: Long,
        val username: String,
        val email: String,
        val firstName: String,
        val lastName: String,
        val isActive: Boolean
    )
}