# 커스텀 이미지 빌드 및 배포

## `libs/keycloak-spi-pincoin-1.0.0.jar`

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