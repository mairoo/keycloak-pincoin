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

## 네이버

### 공통 설정 단계

1. **네이버 개발자 센터** (https://developers.naver.com/main/) 접속
2. **애플리케이션 등록** → **애플리케이션 등록하기** 클릭
3. **애플리케이션 정보 입력**
    - 애플리케이션 이름 입력
    - 사용 API: **네이버 로그인** 선택
4. **로그인 오픈API 서비스 환경** 설정
    - 서비스 URL과 Callback URL 등록

### 환경별 상세 설정

#### 애플리케이션 등록 설정

| 설정 항목                    | 개발 환경 (localhost)                                                                  | 운영 환경 (example.com)                                                         |
|--------------------------|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| **애플리케이션 이름**            | `Keycloak Local Test`                                                              | `example.com`                                                               |
| **사용 API**               | 네이버 로그인                                                                            | 네이버 로그인                                                                     |
| **제공 정보**                | 회원이름, 이메일 주소, 프로필 사진                                                               | 회원이름, 이메일 주소, 프로필 사진                                                        |
| **서비스 URL**              | `http://localhost:8081`                                                            | `https://www.example.com`                                                   |
| **네이버 로그인 Callback URL** | `http://localhost:8081/realms/example-local-jonghwa/broker/naver-pincoin/endpoint` | `https://keycloak.example.com/realms/example/broker/naver-example/endpoint` |

#### 애플리케이션 정보 - 상세 설정

| 설정 항목          | 개발 환경 (localhost) | 운영 환경 (example.com)      |
|----------------|-------------------|--------------------------|
| **애플리케이션 설명**  | `개발 테스트용 애플리케이션`  | `example.com 소셜 로그인 서비스` |
| **애플리케이션 아이콘** | 비워두기              | 회사 로고 업로드                |
| **브랜드 제휴**     | 사용 안함             | 필요시 신청                   |
| **검수 상태**      | 개발 중              | 검수 완료 후 서비스 적용           |

## 카카오

### 공통 설정 단계

1. **카카오 개발자센터** (https://developers.kakao.com/) 접속
2. **내 애플리케이션** → **애플리케이션 추가하기** 클릭
3. **애플리케이션 정보 입력**
    - 앱 이름, 회사명 입력
    - 카테고리 선택
4. **플랫폼 등록** → **Web** 플랫폼 추가
5. **카카오 로그인** 활성화 및 Redirect URI 등록
6. **동의항목** 설정

### 환경별 상세 설정

#### 애플리케이션 기본 정보

| 설정 항목     | 개발 환경 (localhost)     | 운영 환경 (example.com)   |
|-----------|-----------------------|-----------------------|
| **앱 이름**  | `Keycloak Local Test` | `example.com`         |
| **회사명**   | `개인 개발자`              | `example Inc.`        |
| **카테고리**  | `기타`                  | `비즈니스/생산성` 또는 해당 카테고리 |
| **앱 아이콘** | 비워두기                  | 회사 로고 업로드             |

#### 플랫폼 설정

| 설정 항목       | 개발 환경 (localhost)       | 운영 환경 (example.com)       |
|-------------|-------------------------|---------------------------|
| **플랫폼**     | Web                     | Web                       |
| **사이트 도메인** | `http://localhost:8081` | `https://www.example.com` |

#### 카카오 로그인 설정

| 설정 항목            | 개발 환경 (localhost)                                                                  | 운영 환경 (example.com)                                                         |
|------------------|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| **카카오 로그인 활성화**  | ON                                                                                 | ON                                                                          |
| **Redirect URI** | `http://localhost:8081/realms/example-local-jonghwa/broker/kakao-pincoin/endpoint` | `https://keycloak.example.com/realms/example/broker/kakao-example/endpoint` |

#### 동의항목 설정

| 동의항목           | 설정 상태 | 필수 여부 | 비고                |
|----------------|-------|-------|-------------------|
| **프로필 정보**     | 선택 동의 | 선택    | 닉네임, 프로필 사진       |
| **카카오계정(이메일)** | 선택 동의 | 선택    | 이메일 주소 (선택 제공 가능) |

## 페이스북 (Meta)

### 공통 설정 단계

1. **Meta for Developers** (https://developers.facebook.com/) 접속
2. **내 앱** → **앱 만들기** 클릭
3. **앱 유형 선택**: **소비자** 또는 **비즈니스** 선택
4. **앱 정보 입력**
    - 앱 이름, 앱 연락처 이메일 입력
5. **Facebook 로그인** 제품 추가
6. **앱 도메인** 및 **리디렉션 URI** 설정

### 환경별 상세 설정

#### 앱 기본 정보

| 설정 항목         | 개발 환경 (localhost)     | 운영 환경 (example.com)     |
|---------------|-----------------------|-------------------------|
| **앱 이름**      | `Keycloak Local Test` | `example.com`           |
| **앱 연락처 이메일** | 개발자 개인 이메일            | 공식 지원 이메일               |
| **카테고리**      | `기타`                  | 해당 비즈니스 카테고리            |
| **앱 아이콘**     | 비워두기                  | 회사 로고 업로드 (1024x1024px) |

#### 앱 도메인 설정

| 설정 항목     | 개발 환경 (localhost) | 운영 환경 (example.com) |
|-----------|-------------------|---------------------|
| **앱 도메인** | `localhost`       | `example.com`       |

#### Facebook 로그인 설정

| 설정 항목                  | 개발 환경 (localhost)                                                                     | 운영 환경 (example.com)                                                            |
|------------------------|---------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| **클라이언트 OAuth 설정**     | ON                                                                                    | ON                                                                             |
| **웹 OAuth 로그인**        | ON                                                                                    | ON                                                                             |
| **유효한 OAuth 리디렉션 URI** | `http://localhost:8081/realms/example-local-jonghwa/broker/facebook-pincoin/endpoint` | `https://keycloak.example.com/realms/example/broker/facebook-example/endpoint` |

#### 앱 검토 및 권한

| 설정 항목            | 개발 환경 (localhost) | 운영 환경 (example.com)               |
|------------------|-------------------|-----------------------------------|
| **개발 모드**        | ON (기본값)          | OFF (라이브 모드로 전환)                  |
| **앱 검토**         | 불필요               | 필요 (public_profile, email 권한)     |
| **개인정보처리방침 URL** | 비워두기              | `https://www.example.com/privacy` |
| **서비스 약관 URL**   | 비워두기              | `https://www.example.com/terms`   |
| **앱 카테고리**       | 기타                | 해당 비즈니스 카테고리                      |

#### 권한 및 기능

| 권한/기능              | 개발 환경 | 운영 환경 | 비고                    |
|--------------------|-------|-------|-----------------------|
| **public_profile** | 기본 제공 | 기본 제공 | 이름, 프로필 사진 등          |
| **email**          | 기본 제공 | 검토 필요 | 이메일 주소 (앱 검토 후 사용 가능) |

### 중요 참고사항

#### 페이스북 앱 검토 프로세스

**개발 환경:**

- 개발 모드에서는 앱 관리자 및 개발자만 로그인 가능
- 테스터 역할 추가 시 해당 사용자도 로그인 가능

**운영 환경:**

- `email` 권한 사용을 위해서는 앱 검토 필요
- 개인정보처리방침 및 서비스 약관 URL 필수 제공
- 스크린샷 및 앱 사용 설명 제출 필요

#### 도메인 검증

- 운영 환경에서는 도메인 소유권 검증 필요
- Meta Business Manager를 통한 도메인 등록 권장

## 백엔드 연동 구현

# Authenticator

## reCAPTCHA 지원: Authentication flow에 추가

- Realm settings > Security defenses > Content-Security-Policy:
  `frame-src 'self' https://www.google.com https://recaptcha.google.com https://www.gstatic.com https://*.google.com; frame-ancestors 'self'; object-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.google.com https://www.gstatic.com https://*.google.com; connect-src 'self' https://www.google.com https://*.google.com; style-src 'self' 'unsafe-inline';`
  (기본값: `frame-src 'self'; frame-ancestors 'self'; object-src 'none';`)
- Realm settings > Themes > Login Theme: Pincoin
- Authentication> Browser (built-in) flow 복제
    - Username Password Form(step) 아래에 reCAPTCHA(step) 추가
    - Bind flow 드롭다운: Browser flow 선택

## Rate Limit 구현

```
Redis Host: example-redis (도커 이름 또는 호스트 이름)
Redis Port: 6379  
Redis Password: (비어둠 또는 패스워드)
Redis Database: 0

IP 버킷 크기: 30
IP 토큰 보충률: 1.0
IP 데이터 보관시간(초): 3600

사용자 버킷 크기: 5
사용자 토큰 보충률: 0.0167
사용자 데이터 보관시간(초): 900

조합 버킷 크기: 3
조합 토큰 보충률: 0.0033
조합 데이터 보관시간(초): 300
```

보충률 계산 참조

```
분당 N개 → 초당 = N ÷ 60
시간당 N개 → 초당 = N ÷ 3600

예시:
분당 1개 = 1 ÷ 60 = 0.0167
시간당 10개 = 10 ÷ 3600 = 0.0028
5분당 1개 = 1 ÷ 300 = 0.0033
```

# 구간 제한 방법

## 1. Fixed Window

```
❌ 윈도우 경계 문제 (burst attack 취약)
❌ 사용자 경험 나쁨 (갑작스런 차단)
✅ 구현 간단, 성능 좋음
✅ 메모리 효율적
```

## 2. Sliding Window

```
✅ 정확한 rate limiting
❌ 메모리 사용량 많음 (요청마다 timestamp 저장)
❌ Redis 성능 부담 (ZSET 연산)
❌ 복잡한 구현
```

## 3. Token Bucket (현재 구현)

```
✅ 자연스러운 burst 허용
✅ 사용자 경험 최고 (부드러운 제한)
✅ 메모리 효율적 (토큰 수와 시간만 저장)
✅ 성능 좋음
✅ 설정 유연함
```

## 기존 Fixed Window vs Token Bucket 비교

### IP 기준 비교
**기존 설정:**
```
IP 제한 횟수: 100회 / 3600초(1시간)
→ 시간당 최대 100회, 초과 시 1시간 차단
```

**Token Bucket 설정:**
```
IP 버킷 크기: 30
IP 토큰 보충률: 1.0 (초당 1개)
→ 시간당 3600개 토큰 보충 + 초기 30개 burst
```

**실제 동작 비교:**
```
기존: 한 시간에 딱 100회만 허용
Token Bucket: 
- 평상시: 시간당 3600회 허용 (훨씬 관대함)
- 급할 때: 30회까지 연속 요청 가능
- 지속적 공격: 초당 1회로 제한됨
```

### 사용자 기준 비교
**기존 설정:**
```
사용자 제한 횟수: 5회 / 900초(15분)
→ 15분간 5회, 초과 시 15분 차단
```

**Token Bucket 설정:**
```
사용자 버킷 크기: 5
사용자 토큰 보충률: 0.0167 (분당 1개)
→ 15분간 15개 토큰 보충 + 초기 5개 burst
```

**실제 동작 비교:**
```
기존: 15분에 딱 5회만, 6번째는 차단
Token Bucket:
- 평상시: 15분에 20회 허용 (15+5)
- 급할 때: 처음 5회는 연속 가능
- 지속적 시도: 분당 1회로 제한됨
```

### 조합 기준 비교
**기존 설정:**
```
조합 제한 횟수: 10회 / 300초(5분)
→ 5분간 10회, 초과 시 5분 차단
```

**Token Bucket 설정:**
```
조합 버킷 크기: 3
조합 토큰 보충률: 0.0033 (5분당 1개)
→ 5분간 1개 토큰 보충 + 초기 3개 burst
```

**실제 동작 비교:**
```
기존: 5분에 10회까지 허용
Token Bucket:
- 평상시: 5분에 4회 허용 (1+3) - 더 엄격함
- 급할 때: 처음 3회는 연속 가능
- 지속적 시도: 5분당 1회로 제한됨
```

## 🔥 핵심 차이점

**1. 사용자 경험**
```
기존: 갑작스런 차단 (벽에 부딪히는 느낌)
Token Bucket: 부드러운 제한 (속도 조절하는 느낌)
```

**2. 보안 수준**
```
IP: Token Bucket이 더 관대함 (정상 사용자 편의)
사용자: Token Bucket이 더 관대함 (로그인 실수 허용)  
조합: Token Bucket이 더 엄격함 (타겟 공격 차단)
```

**3. 실제 시나리오**
```kotlin
// 사용자가 로그인 실수하는 경우
기존: 5번 틀리면 15분 차단
Token Bucket: 5번 틀린 후 1분마다 1번씩 시도 가능

// 공격자가 지속적으로 시도하는 경우  
기존: 처음 5번 빠르게 시도 후 15분 기다림
Token Bucket: 처음 5번 후 1분마다 1번씩만 가능 (더 효과적)
```

## 권장 조정안

현재 설정을 기존과 비슷하게 맞추려면:

```
IP 버킷 크기: 10 (burst 줄임)
IP 토큰 보충률: 0.028 (시간당 100개)

사용자 버킷 크기: 3 (burst 줄임)  
사용자 토큰 보충률: 0.0056 (15분에 5개)

조합 버킷 크기: 5 (burst 늘림)
조합 토큰 보충률: 0.011 (5분에 10개)
```

**결론:** Token Bucket이 **더 스마트하고 사용자 친화적**이면서도 **공격 방어에는 더 효과적**
