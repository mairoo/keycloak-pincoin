# Keycloak Identity Providers 마이그레이션 전략

## 개요

기존 Django Allauth 기반 소셜 로그인에서 Keycloak 기반 소셜 로그인으로의 점진적 마이그레이션 전략

## 마이그레이션 전략

### Keycloak 서버

- 레거시 Django Allauth 스키마에 소셜 데이터가 저장되어 있어도 **동기화 없이** 그냥 Keycloak 신규 가입 처리
- 기존 데이터와의 연동은 백엔드 서버에서 처리

### 백엔드 서버

- 레거시 Django Allauth 스키마와 상관 없이 **신규 스키마**에 맞춰서 소셜 정보 저장
- **이메일 기준**으로 `auth_user.keycloakId(UUID)` 동기화

## 구현 로직

```kotlin
/**
 * Keycloak을 통한 소셜 로그인 처리
 * 점진적 마이그레이션을 위한 이메일 기반 사용자 매칭
 */
fun handleSocialLogin(keycloakUser: KeycloakUser) {
    val existingUser = userRepository.findByEmail(keycloakUser.email)

    if (existingUser != null && existingUser.keycloakId == null) {
        // 기존 사용자 + 신규 Keycloak 연동
        existingUser.keycloakId = keycloakUser.id
        userRepository.save(existingUser)

        // 새로운 소셜 연동 정보 저장
        saveSocialAccount(existingUser, keycloakUser.socialProvider)

        logger.info("기존 사용자 Keycloak 연동 완료: ${existingUser.email}")
    } else if (existingUser == null) {
        // 완전 신규 사용자
        createNewUser(keycloakUser)

        logger.info("신규 사용자 생성: ${keycloakUser.email}")
    } else {
        // 이미 Keycloak 연동된 사용자
        updateSocialAccount(existingUser, keycloakUser.socialProvider)

        logger.info("기존 Keycloak 사용자 로그인: ${existingUser.email}")
    }
}
```

## 주의 사항

### 1. 이메일 기준 매칭의 한계

동일한 이메일로 다른 소셜 프로바이더를 사용하는 경우:

```
기존: user@gmail.com -> Google 로그인 (Django Allauth)
신규: user@gmail.com -> Facebook 로그인 (Keycloak)
```

**대응 방안:**

- 첫 번째 Keycloak 로그인 시 기존 계정과 자동 연동
- 사용자에게 계정 통합 안내 메시지 표시
- 필요시 수동 계정 연결 기능 제공

### 2. 사용자 경험 고려

**잠재적 문제:**

- 기존 소셜 계정 연결 정보가 사라지는 것처럼 보일 수 있음
- 처음 Keycloak 로그인 시 "새 계정 생성" 메시지로 인한 혼란

**대응 방안:**

- 로그인 후 "계정이 통합되었습니다" 안내 메시지
- 프로필 페이지에서 연동된 소셜 계정 목록 표시
- 고객지원을 위한 문의 채널 안내

# 커스텀 이미지 빌드 및 배포

## `libs/keycloak-pincoin-providers-1.0.0.jar`

## `Dockerfile.keycloak`

```dockerfile
# Dockerfile.keycloak
FROM quay.io/keycloak/keycloak:26.3.1

# libs 디렉토리의 모든 JAR 파일을 프로바이더로 복사
COPY libs/*.jar /opt/keycloak/providers/

# 권한 설정
USER root
RUN chown keycloak:keycloak /opt/keycloak/providers/*.jar
USER keycloak

# 프로바이더 빌드 (선택사항 - 성능 향상)
RUN /opt/keycloak/bin/kc.sh build
```

## `docker-compose.yml`

```yaml
  keycloak:
    container_name: ${PREFIX}-keycloak
    # image: quay.io/keycloak/keycloak:26.3.1
    build:
      context: .
      dockerfile: Dockerfile.keycloak
    restart: unless-stopped
    # 이하 생략
```

## 시작

```shell
docker compose build keycloak && docker compose up -d keycloak
```

# Keycloak 웹 콘솔 설정

## Admin Console에서 각 소셜 프로바이더 설정

### Realm 설정

프로덕션용 별도 Realm 생성 권장 (예: example-realm)
기본 master realm은 관리 전용으로 사용

### 프로바이더별 주요 차이점

각 프로바이더별로 **Add provider** → **[Provider Name] example Custom** 선택 후 아래 표를 참고하여 설정

| 설정 항목             | Google                           | Facebook                          | Kakao                                          | Naver                              |
|-------------------|----------------------------------|-----------------------------------|------------------------------------------------|------------------------------------|
| **Alias**         | `google`                         | `facebook`                        | `kakao`                                        | `naver`                            |
| **Display Name**  | `Google`                         | `Facebook`                        | `카카오`                                          | `네이버`                              |
| **Enabled**       | ON                               | ON                                | ON                                             | ON                                 |
| **Client ID**     | Google Console<br/>Client ID     | Facebook Developer<br/>App ID     | Kakao Developers<br/>REST API 키                | Naver Developers<br/>Client ID     |
| **Client Secret** | Google Console<br/>Client Secret | Facebook Developer<br/>App Secret | Kakao Developers<br/>Client Secret             | Naver Developers<br/>Client Secret |
| **Scopes**        | `openid email profile`           | `email public_profile`            | `profile_nickname profile_image account_email` | `name email`                       |
| **Store Tokens**  | ON (선택사항)                        | ON (선택사항)                         | ON (선택사항)                                      | ON (선택사항)                          |
| **Trust Email**   | ✅ ON<br/>(검증된 이메일)               | ✅ ON<br/>(검증된 이메일)                | ❌ OFF<br/>(이메일 선택사항)                           | ✅ ON<br/>(필수 이메일)                  |

각 프로바이더 설정 완료 후 **Save** 버튼을 클릭하여 저장합니다.

### Redirect URI

각 프로바이더 설정 후 반드시 Redirect URI를 복사해서 각 소셜 플랫폼에 등록

- 패턴: https://your-domain/realms/{realm-name}/broker/{provider-alias}/endpoint

## Client ID/Secret 등록

# 소셜 로그인 설정

## 구글

### 공통 설정 단계

1. **구글 클라우드 콘솔** (https://console.cloud.google.com/) 접속
2. **프로젝트 생성 또는 선택**
3. **API 및 서비스(APIs & Services)** → **사용자 인증 정보(Credentials)** 이동
4. **사용자 인증 정보 만들기(CREATE CREDENTIALS)**
    - OAuth 클라이언트 ID 선택
    - 애플리케이션 유형(Application type): **웹 애플리케이션(Web Application)** 선택
5. **OAuth 동의 화면** 설정
    - 대상: **외부(External)** 선택

### 환경별 상세 설정

#### OAuth 클라이언트 ID 설정

| 설정 항목            | 개발 환경 (localhost)                                                                   | 운영 환경 (example.com)                                                          |
|------------------|-------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| **이름(Name)**     | `Keycloak Local Test`                                                               | `example.com Keycloak Production`                                            |
| **승인된 리디렉션 URI** | `http://localhost:8081/realms/example-local-jonghwa/broker/google-pincoin/endpoint` | `https://keycloak.example.com/realms/example/broker/google-example/endpoint` |

#### OAuth 동의 화면 - 브랜딩 설정

| 설정 항목           | 개발 환경 (localhost)       | 운영 환경 (example.com)               |
|-----------------|-------------------------|-----------------------------------|
| **앱 이름**        | `Keycloak Local Test`   | `example.com`                     |
| **사용자 지원 이메일**  | 본인 Gmail 주소             | 공식 지원 이메일                         |
| **개발자 연락처 정보**  | 본인 Gmail 주소             | 개발팀 이메일                           |
| **앱 로고**        | 비워두기                    | 회사 로고 업로드                         |
| **애플리케이션 홈페이지** | `http://localhost:8081` | `https://www.example.com`         |
| **개인정보처리방침 링크** | 비워두기                    | `https://www.example.com/privacy` |
| **서비스 약관 링크**   | 비워두기                    | `https://www.example.com/terms`   |
| **승인된 도메인**     | **완전히 비워두기**            | `example.com`<br/>`example.kr`    |

#### OAuth 동의 화면 - 클라이언트 설정

| 설정 항목       | 개발 환경 (localhost) | 운영 환경 (example) |
|-------------|-------------------|-----------------|
| **테스트 사용자** | 개발자 Gmail 계정 추가   | 내부 테스터 계정들 추가   |
| **게시 상태**   | 테스트 모드 유지         | 프로덕션 게시 신청      |

### 네이버

### 카카오

### 페이스북

## 소셜 로그인 테스트

## 백엔드 연동 구현