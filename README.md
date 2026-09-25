# service-backend

Re:Used 중고거래 플랫폼의 API 서버. 요구사항·설계·API 계약의 원본은 [service-design-docs](https://github.com/Logssey/service-design-docs) 저장소이며, 이 저장소는 그 문서를 구현한다.

## 기술 스택

| 구분 | 선택 | 근거 |
| --- | --- | --- |
| 프레임워크 | Spring Boot 4.1, Java 21, Gradle | ADR-001 |
| 데이터베이스 | PostgreSQL 18.6, JPA(Hibernate). 스키마는 SQL 스크립트로만 관리 | ADR-009, database-ddl.md |
| 공유 상태 | Redis 7 (Refresh Token, 인증 코드, 분산 락) | ADR-010, redis-keys.md |
| 이미지 저장소 | 비공개 S3 + 짧은 수명의 Presigned URL | ADR-011 |
| 인증 | JWT Access Token + Redis 화이트리스트 Refresh Token | ADR-005 |
| 테스트 | JUnit 5, MockMvc, Testcontainers | |

Base URL은 `/api/v1`이다. 엔드포인트 목록과 구현 여부는 설계 문서의 [endpoints.csv](https://github.com/Logssey/service-design-docs/blob/main/05-api/catalog/endpoints.csv)를 따른다.

## 요구 사항

- JDK 21. Gradle toolchain이 요구하며, 설치되어 있으면 `JAVA_HOME`과 무관하게 자동으로 찾는다
- Docker. 통합 테스트가 Testcontainers로 PostgreSQL·Redis를 띄운다
- 로컬 실행 시 PostgreSQL 18.6, Redis 7, SMTP 대역(Mailpit)

## 로컬 실행

아래 명령은 WSL(bash) 기준이다.

### 1. 인프라

```bash
docker run -d --name reused-postgres -e POSTGRES_DB=reused -e POSTGRES_USER=reused -e POSTGRES_PASSWORD=reused -p 5432:5432 postgres:18.6-alpine
docker run -d --name reused-redis -p 6379:6379 redis:7-alpine
docker run -d --name reused-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
```

Mailpit 웹 UI는 `http://localhost:8025`. 이메일 인증·비밀번호 재설정 코드는 메일 본문에만 존재하므로 여기서 확인한다.

### 2. 스키마 적용

Hibernate가 테이블을 만들지 않는다(`ddl-auto=none`). `schema/` 아래 스크립트를 번호 순서대로 적용한다.

```bash
docker cp schema/001_init.sql reused-postgres:/tmp/001_init.sql
docker cp schema/002_seed_categories.sql reused-postgres:/tmp/002_seed_categories.sql
docker exec reused-postgres psql -U reused -d reused -f /tmp/001_init.sql
docker exec reused-postgres psql -U reused -d reused -f /tmp/002_seed_categories.sql
```

스키마를 다시 적용해야 하면 컨테이너를 지우고 새로 만든다. 메이저 버전이 바뀐 경우도 같다.

### 3. 환경변수와 기동

시크릿은 설정 파일에 두지 않고 환경변수로 주입한다(`NFR-CRED-007`). `application.properties`에는 플레이스홀더만 있다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `JWT_SECRET` | O | HS256 서명 키. 32바이트(256비트) 이상 |
| `KAKAO_CLIENT_ID` | O | 카카오 REST API 키. 카카오 로그인을 쓰지 않을 때도 값은 있어야 한다 |
| `KAKAO_CLIENT_SECRET` | | 카카오 Client Secret |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | O | 위 docker 명령 기준 `jdbc:postgresql://localhost:5432/reused`, `reused`, `reused` |
| `SPRING_DATA_REDIS_HOST` | | 기본 `localhost` |
| `APP_AUTH_COOKIE_SECURE` | | http로 접속하는 로컬 개발에서는 `false`. 기본 `true` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_FROM` | | SMTP 대역. 기본 `localhost` / `1025` / `no-reply@reused.local` |
| `IMAGE_S3_BUCKET` | 이미지 사용 시 O | 비공개 이미지 버킷. 비어 있으면 서버는 기동하지만 이미지 API는 503을 반환 |
| `IMAGE_S3_REGION` | | 이미지 버킷 리전. 기본 `ap-northeast-2` |
| `IMAGE_S3_ENDPOINT` | | LocalStack·MinIO용 endpoint override. AWS에서는 비워 둔다 |
| `IMAGE_ORPHAN_RETENTION` | | 게시글에 연결되지 않은 이미지 보존 기간. 기본 `24h` |
| `IMAGE_CLEANUP_INTERVAL` | | 고아 이미지 정리 주기. 기본 `1h` |
| `IMAGE_UNATTACHED_LIMIT` | | 사용자별 미연결 이미지 상한. 기본 `20` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` | 로컬 S3 사용 시 | AWS SDK 기본 자격증명 체인을 사용한다. 운영에서는 정적 키 대신 workload role을 사용한다 |

```bash
export JWT_SECRET="local-dev-secret-key-must-be-at-least-32-bytes-long"
export KAKAO_CLIENT_ID="not-used-yet"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/reused"
export SPRING_DATASOURCE_USERNAME="reused"
export SPRING_DATASOURCE_PASSWORD="reused"
export APP_AUTH_COOKIE_SECURE="false"
./gradlew bootRun
```

서버는 `http://localhost:8080`에서 뜬다. 프론트엔드 개발 서버(`service-frontend`, 5173)가 `/api` 요청을 이 주소로 프록시한다.

### 이미지 저장소 운영 조건

- 버킷은 공개 접근을 차단하고 암호화를 활성화한다. 런타임 역할에는 대상 버킷의 `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` 권한만 부여한다.
- 브라우저 직접 업로드를 위해 프론트 Origin의 `PUT`과 `Content-Type`을 버킷 CORS에 허용한다. 업로드 URL은 요청한 `Content-Type`과 정확한 `Content-Length`를 함께 서명한다.
- 클라이언트는 URL 발급 요청의 `contentType`과 `fileSize`를 그대로 사용해 원본 바이트를 `PUT`해야 한다. 둘 중 하나라도 달라지면 S3가 서명 불일치로 요청을 거부한다.
- `pending/` prefix에는 1일 만료 S3 Lifecycle 규칙을 반드시 둔다. 완료 후에도 5분짜리 업로드 URL이 만료되기 전까지 원래 key가 다시 생성될 수 있기 때문이다.
- 애플리케이션은 업로드 메타데이터의 `created_at`을 기준으로, 24시간이 지난 미연결 DB 행과 객체를 정리한다. 저장소 삭제 실패 시 행을 `REJECTED` 상태로 남겨 다음 주기에 재시도한다.
- 한 사용자는 기본 20개의 미연결 이미지만 보유할 수 있다. 상한에 도달하면 기존 이미지를 게시글에 연결하거나 삭제할 때까지 새 업로드 URL 발급이 429로 제한된다.
- 게시글 수정에서 빠진 이미지는 연결 해제 후 위 정리 대상이 된다. 이미 24시간이 지난 이미지는 다음 정리 주기에 삭제된다. 소프트 삭제한 게시글의 연결 이미지는 거래·감사 근거 보존을 위해 그대로 유지한다.
- 1차 범위는 `LISTING` 이미지다. `PROFILE` 업로드 연결은 별도 후속 작업이다.

## 테스트

```bash
./gradlew test
```

통합 테스트는 실제 PostgreSQL·Redis 컨테이너 위에서 돌고, `schema/` 스크립트를 그대로 적용한다. 외부 시스템(카카오 API, SMTP, S3)만 인터페이스 뒤의 대역으로 바꾼다.

## 프로젝트 구조

```
com.reused
├── common/        모든 도메인이 공유하는 것
│   ├── error/     ErrorCode, BusinessException, 전역 예외 → api-spec 0.5 오류 응답
│   └── security/  JWT 필터, AuthPrincipal, @AuthUser, SecurityConfig
├── auth/          인증 행위: 로그인·가입·토큰·인증 코드·메일
├── user/          회원과 인증 수단 엔티티, 프로필
├── category/
└── <domain>/      controller · service · repository · entity · dto/{request,response}
```

- 최상위는 **도메인별 패키지**, 그 안은 **역할별 패키지**다(api-spec 0.1).
- 다른 도메인이 인증 정보를 쓸 때는 `common.security`의 `@AuthUser AuthPrincipal` 또는 `CurrentUserProvider`만 사용한다. `auth` 패키지 내부에 의존하지 않는다.
- 외부 시스템 호출은 인터페이스 뒤에 둔다(`auth.client`, `auth.mail`). 테스트에서 `@MockitoBean`으로 바꾸기 위함이다.

## 규칙

- **스키마는 문서에서 코드로만 흐른다.** `schema/*.sql`은 설계 문서 `database-ddl.md`의 사본이다. 컬럼을 바꾸려면 문서 PR을 먼저 올리고 그 결과를 복사해 온다.
- **DTO는 문서 이름 그대로.** 요청은 `*Request`, 응답은 `*Response`, 목록은 `CursorPageResponse<T>`. 엔티티를 직접 응답하지 않는다.
- **오류는 `BusinessException(ErrorCode, message)`로.** 스택 트레이스, 쿼리, 내부 식별자를 응답에 넣지 않는다.
- **정책 값은 설정으로.** 토큰 수명, 코드 유효기간, 시도 제한 같은 값은 `@ConfigurationProperties`로 두고 코드 상수로 박지 않는다.
- **Redis 키는 `reused:도메인:용도:식별자`.** 개인정보(이메일 등)를 키에 넣지 않는다.

## 관련 문서

| 문서 | 위치 |
| --- | --- |
| API 공통 규약과 엔드포인트 | `service-design-docs/05-api/` |
| 아키텍처 결정(ADR) | `service-design-docs/03-architecture/adr/` |
| 비즈니스 규칙과 정책 값 | `service-design-docs/02-functional-design/business-rules.md` |
| DB DDL, Redis 키 규약 | `service-design-docs/04-data/` |
