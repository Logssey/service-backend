# service-backend

Re:Used 중고거래 플랫폼의 API 서버. 요구사항·설계·API 계약의 원본은 [service-design-docs](https://github.com/Logssey/service-design-docs) 저장소이며, 이 저장소는 그 문서를 구현한다.

## 기술 스택

| 구분 | 선택 | 근거 |
| --- | --- | --- |
| 프레임워크 | Spring Boot 4.1, Java 21, Gradle | ADR-001 |
| 데이터베이스 | PostgreSQL 18.6, JPA(Hibernate), Flyway 버전 관리 | ADR-009, database-ddl.md |
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

### 2. DB 스키마와 마이그레이션

**새 빈 DB**에서 애플리케이션을 시작하면 Flyway가 `V1` 초기 스키마, `V2` 카테고리 기준 데이터, `V3` 프로필 이미지, `V4` 소셜 계정 선택 이메일 변경을 순서대로 적용한다. Hibernate는 테이블을 만들지 않는다(`ddl-auto=none`). `schema/001_init.sql`·`002_seed_categories.sql`·`003_profile_images.sql`·`004_social_identity_email.sql`이 저장소의 유일한 SQL 원본이고, Gradle `processResources`가 이 파일들을 `db/migration/V1__init.sql` 등의 이름으로 JAR에 패키징한다. SQL 사본을 따로 수정하지 않는다. 새 변경은 `schema/`에 다음 번호 파일을 추가하고 `build.gradle`의 패키징 매핑에 등록한다.

**기존 데이터가 있는 DB**에는 자동 기준선 설정을 사용하지 않는다(`spring.flyway.baseline-on-migrate=false`). `flyway_schema_history`가 없는 비어 있지 않은 DB로 새 버전을 기동하면 안전하게 실패한다. 기존 `reused`·`reused_web_20260926` 및 운영 DB에는 이 문서의 절차를 검토 없이 실행하지 않는다.

기존 DB를 도입할 때는 담당자가 다음을 수동으로 수행한다.

1. 대상 DB의 서버·DB명·스키마를 읽기 전용으로 확인하고 백업과 복구 가능성을 확인한다. 실제 스키마를 `schema/` SQL과 비교한다. `V2` 여부는 카테고리 기준 데이터, `V3` 여부는 `listing_images.purpose`·`profile_user_id`, `V4` 여부는 `user_identities.email_consent_at` 컬럼과 새 제약·부분 UNIQUE 인덱스로 판단한다. 일부만 적용되었거나 문서와 다른 DB라면 중단하고 별도 수정 계획을 세운다.
2. 정확히 `V1`까지만 적용된 DB는 baseline version `1`, `V2`까지는 `2`, `V3`까지는 `3`, `V4`까지는 `4`로 정한다. Flyway CLI의 연결 정보(`FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`)는 검증된 대상과 비밀값 저장소에서 주입하고, 예를 들어 `V4`까지 동일한 DB에만 `flyway -baselineVersion=4 baseline`을 한 번 실행한다. `baseline`은 기존 SQL을 검증·재실행하지 않고 해당 버전까지 적용된 것으로 기록한다.
3. `V4`를 아직 적용하지 않은 DB는 적용 전에 `SELECT count(*) FROM user_identities WHERE email IS NULL AND email_verified_at IS NOT NULL` 결과가 0인지 확인한다. `V3` 기준선에서 다음 기동 시 Flyway가 `V4`를 적용하지만, 운영 배포에서는 기존 버전과 호환되는 `V4`를 새 애플리케이션보다 먼저 적용하도록 계획한다. `email_consent_at`이 없으면 새 인증 코드가 실패한다.
4. `flyway info`에서 기준선과 대상 DB를 다시 확인한 다음 애플리케이션을 기동한다. 기준선보다 뒤의 마이그레이션만 적용된다. 배포 전에 같은 상태를 복제한 일회용 DB에서 절차를 연습한다. CI/CD에는 기존 DB를 자동 baseline하는 작업을 넣지 않는다.

화면·API 시연용 가상 데이터는 [로컬 데모 가이드](scripts/README-demo.md)에 따라 별도의 새 `reused_demo_*` DB에만 명시적으로 생성한다. 일반 로컬 DB·CI·운영에는 자동 시드하지 않는다.

### 3. 환경변수와 기동

시크릿은 설정 파일에 두지 않고 환경변수로 주입한다(`NFR-CRED-007`). `application.properties`에는 플레이스홀더만 있다.

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `JWT_SECRET` | O | HS256 서명 키. 32바이트(256비트) 이상 |
| `SPRING_PROFILES_ACTIVE` | | 로컬 개발은 `local`. 카카오 대역이 켜진다(아래) |
| `KAKAO_CLIENT_ID` | O | 카카오 REST API 키. `local` 프로파일의 대역 모드에서는 필요 없다 |
| `KAKAO_STUB` | | `local` 프로파일에서 `false`로 두면 대역 대신 실제 카카오를 호출한다. 기본 `true` |
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
| `ANTHROPIC_API_KEY` | | 자유 입력용 LLM 키. 키가 있어도 자유 입력은 기본 차단된다 |
| `ANTHROPIC_BASE_URL` | | LLM API 주소. 게이트웨이를 거칠 때만 둔다. 기본은 SDK 기본 주소 |
| `CHATBOT_ENABLED` | | `false`면 챗봇 두 엔드포인트가 모두 503이다(ADR-003 비활성화 스위치). 기본 `true` |
| `CHATBOT_FREE_INPUT_ENABLED` | | 기본 `false`. 추천 질문은 유지하고 자유 입력만 503으로 차단한다. 개인정보 외부 전송 정책 검토 전에는 활성화하지 않는다 |

```bash
export SPRING_PROFILES_ACTIVE="local"
export JWT_SECRET="local-dev-secret-key-must-be-at-least-32-bytes-long"
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/reused"
export SPRING_DATASOURCE_USERNAME="reused"
export SPRING_DATASOURCE_PASSWORD="reused"
export APP_AUTH_COOKIE_SECURE="false"
./gradlew bootRun
```

서버는 `http://localhost:8080`에서 뜬다. 프론트엔드 개발 서버(`service-frontend`, 5173)가 `/api` 요청을 이 주소로 프록시한다.

**카카오 대역.** `local` 프로파일에서는 `KakaoStubClient`가 카카오를 호출하지 않고 인가 코드를 그대로 회원번호로 쓴다. 프론트 `.env`에 `VITE_KAKAO_STUB=true`를 두면 브라우저마다 고정된 코드를 보내므로 같은 계정으로 계속 로그인된다. 대역은 `local` 프로파일과 `app.kakao.stub=true`가 모두 있어야 뜨고, 이때 실제 클라이언트는 꺼진다. 프로파일 없이 stub만 켜면 카카오 로그인은 404로 막힌다.

### 이미지 저장소 운영 조건

- 버킷은 공개 접근을 차단하고 암호화를 활성화한다. 런타임 역할에는 대상 버킷의 `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` 권한만 부여한다.
- 브라우저 직접 업로드를 위해 프론트 Origin의 `PUT`과 `Content-Type`을 버킷 CORS에 허용한다. 업로드 URL은 요청한 `Content-Type`과 정확한 `Content-Length`를 함께 서명한다.
- 클라이언트는 URL 발급 요청의 `contentType`과 `fileSize`를 그대로 사용해 원본 바이트를 `PUT`해야 한다. 둘 중 하나라도 달라지면 S3가 서명 불일치로 요청을 거부한다.
- `pending/` prefix에는 1일 만료 S3 Lifecycle 규칙을 반드시 둔다. 완료 후에도 5분짜리 업로드 URL이 만료되기 전까지 원래 key가 다시 생성될 수 있기 때문이다.
- 애플리케이션은 업로드 메타데이터의 `created_at`을 기준으로, 24시간이 지난 미연결 DB 행과 객체를 정리한다. 저장소 삭제 실패 시 행을 `REJECTED` 상태로 남겨 다음 주기에 재시도한다.
- 한 사용자는 기본 20개의 미연결 이미지만 보유할 수 있다. 상한에 도달하면 기존 이미지를 게시글에 연결하거나 삭제할 때까지 새 업로드 URL 발급이 429로 제한된다.
- 게시글 수정에서 빠진 이미지는 연결 해제 후 위 정리 대상이 된다. 이미 24시간이 지난 이미지는 다음 정리 주기에 삭제된다. 소프트 삭제한 게시글의 연결 이미지는 거래·감사 근거 보존을 위해 그대로 유지한다.
- `LISTING`과 `PROFILE`을 모두 지원한다. PROFILE은 `pending/profile-images/` → `verified/profile-images/`로 같은 바이트 검증을 수행한다.
- `PATCH /users/me`에 본인의 검증된 PROFILE `imageId`를 보내 연결한다. 생략하면 유지하고 명시적 `null`은 기본 이미지로 되돌린다. 임의 URL은 입력받지 않는다.
- 프로필은 회원별 하나만 연결하고 사용 중인 이미지는 직접 삭제할 수 없다. 교체·초기화·탈퇴는 기존 연결을 해제하며 고아 정리 정책을 적용한다.
- DB에는 내부 key를 보관하고 모든 API 응답의 프로필 이미지 필드에서는 15분 서명 URL로 변환한다. 프로필용 별도 AWS 비밀키는 필요 없다.

## 테스트

```bash
./gradlew test
```

통합 테스트는 Testcontainers의 새 PostgreSQL·Redis 위에서 돌고, 애플리케이션 기동 시 운영 JAR와 동일한 Flyway 마이그레이션이 적용된다. PostgreSQL 컨테이너의 초기화 스크립트는 사용하지 않는다. 외부 시스템(카카오 API, SMTP, S3)만 인터페이스 뒤의 대역으로 바꾼다.

### 실시간 채팅과 전체 흐름 검증

채팅 런타임은 같은 저장소의 [`chat-server/`](chat-server/README.md)에 있다. Node.js 24와 Docker가 필요하다.

```bash
cd chat-server
npm ci
npm run build
npm test
cd ..
./gradlew test marketplaceE2E
```

`marketplaceE2E`는 채팅 소스를 빌드한 뒤 실제 HTTP 서버와 Socket.IO 서버, PostgreSQL·Redis를 연결해 가입→상품→관심→채팅·읽음·삭제→거래→후기→관리자 조치→탈퇴를 검증한다. 테스트 전용 계정과 토큰만 사용하며 운영 환경에는 요청하지 않는다. 일반 `test`와 별도 태그라 Node 의존성을 설치한 뒤 명시적으로 실행한다. 채팅 소스·실행 스크립트 변경도 테스트 입력으로 추적한다.

PR에는 `Marketplace tests` 워크플로가 동일한 회귀 검증을 수행한다. 기존 이미지 빌드·배포 워크플로와 별도이며 배포 자격증명을 요구하지 않는다.

### 배포 연결

- Spring API는 `/api`, 별도 채팅 런타임은 `/socket.io`로 라우팅한다. WebSocket 업그레이드와 채팅 Origin 허용 목록을 설정한다.
- 채팅의 `CHAT_API_BASE_URL`은 Spring 주소, `CHAT_REDIS_URL`은 API와 같은 Redis, `CHAT_ALLOWED_ORIGINS`는 실제 프론트 Origin이다. JWT 비밀키를 채팅 서버에 복제하지 않는다.
- Spring의 `/actuator/health/liveness`, `/actuator/health/readiness`는 인증 없는 컨테이너 프로브용이다. 다른 관리 엔드포인트를 공개하지 않는다.
- 기능 구현·로컬/CI 검증과 운영 배포는 구분한다. 실제 S3·SMTP·카카오 자격증명, 003·004 DB 적용, Socket.IO 라우팅은 운영 담당자가 해당 환경에 반영해야 한다.
- 구현 상태의 원본은 [설계 저장소 현황](https://github.com/Logssey/service-design-docs/blob/main/05-api/backend-implementation-status.md)이다.

## 프로젝트 구조

```
com.reused
├── common/        모든 도메인이 공유하는 것
│   ├── error/     ErrorCode, BusinessException, 전역 예외 → api-spec 0.5 오류 응답
│   ├── pagination/ CursorPageRequest, CursorPageResponse, CursorCodec → api-spec 0.4 커서 페이지
│   ├── paging/    IdPage(업스트림 커서 헬퍼)
│   ├── security/  JWT 필터, AuthPrincipal, @AuthUser, SecurityConfig, 관리자 DB 재확인
│   └── tx/        AfterCommit(커밋 후 실행)
├── audit/         감사 로그. B 코드는 `audit.api.AuditLogger`, A 관리자 게시글 조치는 `audit.service.AuditService`로 기록한다(같은 audit_logs, 행 형식 호환)
├── auth/          인증 행위: 로그인·가입·토큰·인증 코드·메일
├── user/          회원과 인증 수단 엔티티, 프로필, 이용정지·해제(UserModerationService, 만료 해제 작업)
│   └── api/       ActiveUserGuard(정지·탈퇴 DB 확인), UserQueryService(회원 요약)
├── block/         차단(BlockService). 다른 도메인은 BlockService.eitherDirection으로 차단 관계를 확인한다
├── report/        신고. api/에 신고 대상·콘텐츠 조치 포트와 ReportStatsQuery(신고 집계)
├── notification/  인앱 알림. 거래·채팅·후기·탈퇴 알림은 service/NotificationService.createFor(업무 트랜잭션 안), 신고 처리·공지 알림은 api/NotificationEventPublisher(커밋 뒤 별도 트랜잭션)
├── chatbot/       추천 질문·자유 입력 안내. LLM은 llm/LlmClient(공급자 중립) 뒤에 있고 전용 스레드 풀에서 호출한다
├── category/
└── <domain>/      controller · service · repository · entity · dto/{request,response}
```

- 최상위는 **도메인별 패키지**, 그 안은 **역할별 패키지**다(api-spec 0.1).
- 다른 도메인이 인증 정보를 쓸 때는 `common.security`의 `@AuthUser AuthPrincipal` 또는 `CurrentUserProvider`만 사용한다. `auth` 패키지 내부에 의존하지 않는다. 로그인이 선택인 공개 엔드포인트는 `@AuthUser @Nullable AuthPrincipal`(JSpecify)로 받는다.
- 다른 개발자가 쓰는 계약 인터페이스는 제공 도메인의 `api` 하위 패키지에 두고, 쓰는 쪽은 `*.api`만 import한다.
- 외부 시스템 호출은 인터페이스 뒤에 둔다(`auth.client`, `auth.mail`, `chatbot.llm`). 테스트에서 `@MockitoBean`으로 바꾸기 위함이다.

## 규칙

- **스키마는 문서에서 코드로만 흐른다.** `schema/*.sql`은 설계 문서 `database-ddl.md`에 대응하는 마이그레이션의 유일한 원본이다. 이미 적용된 버전은 수정하지 않고 다음 번호의 변경 스크립트를 추가한다. 컬럼을 바꾸려면 설계 문서도 함께 갱신한다.
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
