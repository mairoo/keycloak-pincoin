# Keycloak identity providers

## 목적

django-allauth 스키마 구조에 맞는 레거시 소셜 로그인 정보 연동

## 소셜 로그인 비교

| 항목                    | **Google**                                         | **Naver**                                  | **Kakao**                                          | **Facebook**                                          |
|-----------------------|----------------------------------------------------|--------------------------------------------|----------------------------------------------------|-------------------------------------------------------|
| **Provider ID**       | `google-pincoin`                                   | `naver-pincoin`                            | `kakao-pincoin`                                    | `facebook-pincoin`                                    |
| **Authorization URL** | `https://accounts.google.com/o/oauth2/v2/auth`     | `https://nid.naver.com/oauth2.0/authorize` | `https://kauth.kakao.com/oauth/authorize`          | `https://www.facebook.com/v18.0/dialog/oauth`         |
| **Token URL**         | `https://oauth2.googleapis.com/token`              | `https://nid.naver.com/oauth2.0/token`     | `https://kauth.kakao.com/oauth/token`              | `https://graph.facebook.com/v18.0/oauth/access_token` |
| **UserInfo URL**      | `https://openidconnect.googleapis.com/v1/userinfo` | `https://openapi.naver.com/v1/nid/me`      | `https://kapi.kakao.com/v2/user/me`                | `https://graph.facebook.com/v18.0/me`                 |
| **기본 Scope**          | `openid email profile`                             | `name email`                               | `profile_nickname profile_image account_email`     | `email public_profile`                                |
| **OAuth2/OIDC 준수도**   | ✅ OIDC 완전 준수                                       | ❌ OAuth2만 지원                               | ❌ OAuth2만 지원                                       | ❌ OAuth2만 지원                                          |
| **사용자 ID 필드**         | `sub` (JWT 표준)                                     | `response.id`                              | `id`                                               | `id`                                                  |
| **사용자 ID 고유성**        | 🌐 전역 고유                                           | 📱 앱별 고유                                   | 📱 앱별 고유                                           | 📱 앱별 고유                                              |
| **토큰 타입**             | JWT (ID Token) + Opaque                            | Opaque Token                               | Opaque Token                                       | Opaque Token                                          |
| **JWT 지원**            | ✅ ID Token은 JWT                                    | ❌ 미지원                                      | ❌ 미지원                                              | ❌ 미지원                                                 |
| **Refresh Token**     | ✅ 지원                                               | ✅ 지원                                       | ✅ 지원                                               | ✅ 지원                                                  |
| **토큰 검증 API**         | ✅ tokeninfo API                                    | ✅ 연동해제 API                                 | ✅ 토큰 정보 조회                                         | ✅ debug_token API                                     |
| **응답 구조**             | 표준 OIDC Claims                                     | 네이버 래핑 구조                                  | 카카오 중첩 구조                                          | Graph API 구조                                          |
| **주요 사용자 필드**         | `sub`, `name`, `email`, `picture`                  | `response.{id,name,email,nickname}`        | `id`, `kakao_account.email`, `properties.nickname` | `id`, `name`, `email`, `picture.data.url`             |
| **커스터마이징 필요도**        | 🟢 최소                                              | 🔴 높음                                      | 🟡 보통                                              | 🟢 최소                                                 |
| **주요 고려사항**           | 표준 OIDC로 즉시 사용                                     | 응답 래핑 구조 매핑                                | 중첩 응답 구조 매핑                                        | 기존 호환성 확인                                             |