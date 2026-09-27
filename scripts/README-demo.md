# 로컬 데모 데이터 (명시적 실행 전용)

이 시드는 **로컬의 새 PostgreSQL DB**에서만 화면·API 흐름을 확인하기 위한 가상 데이터다. CI의 일반 애플리케이션 실행과 운영 배포에서는 활성화하지 않는다. 기존 DB를 대상으로 실행하거나 운영 비밀번호를 재사용하지 않는다.

## 준비

1. 로컬 PostgreSQL에 `reused_demo_local`처럼 이름이 `reused_demo_`로 시작하는 **별도 빈 DB**를 만든다. 기존 `reused` 또는 실제 서비스 DB를 재사용하지 않는다.
2. 빈 DB에는 SQL을 수동 적용하지 않는다. 백엔드 기동 시 Flyway가 `schema/` 원본에서 패키징한 V1~V3를 순서대로 적용한다.
3. 로컬 Redis를 실행한다. 데모 시드 자체는 Redis에 쓰지 않지만 로그인·API 실행에 필요하다.

PowerShell에서 예를 들어 다음처럼 새 DB를 만들 수 있다. `psql` 접속 계정·비밀번호는 자신의 로컬 개발 환경에 맞춘다.

```powershell
psql -h 127.0.0.1 -U reused -d postgres -c 'CREATE DATABASE reused_demo_local'
```

위 README의 `reused-postgres` 컨테이너를 사용 중이고 호스트에 `psql`이 없다면 다음 명령으로 **새 DB만** 만들 수 있다.

```powershell
docker exec reused-postgres psql -U reused -d postgres -c 'CREATE DATABASE reused_demo_local'
```

## 시드 실행

백엔드를 **호스트에서** 실행하며 다음 환경값을 그 프로세스에만 전달한다. DB JDBC 주소의 호스트는 `localhost`·`127.0.0.1`·`::1` 중 하나여야 하고, 실제 DB 이름도 `reused_demo_`로 시작해야 한다. 둘 중 하나라도 아니면 데이터 생성 전에 시작이 거부된다. `local-demo` 프로필과 `APP_DEMO_SEED=true`가 **동시에** 필요하다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local,local-demo'
$env:APP_DEMO_SEED = 'true'
$env:APP_DEMO_PASSWORD = '<개발 전용 임의 비밀번호 8~128자>'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/reused_demo_local'
$env:SPRING_DATASOURCE_USERNAME = '<로컬 DB 계정>'
$env:SPRING_DATASOURCE_PASSWORD = '<로컬 DB 비밀번호>'
$env:SPRING_DATA_REDIS_HOST = '127.0.0.1'
$env:JWT_SECRET = '<로컬 전용 32바이트 이상 문자열>'
$env:APP_AUTH_COOKIE_SECURE = 'false'
$env:DEBUG = 'false'
.\gradlew.bat bootRun
```

`APP_DEMO_PASSWORD`는 샘플 계정 세 개의 공통 **개발 전용 비밀번호**이며 저장소에 기록되지 않는다. 만들어지는 이메일은 발송되지 않는 `.invalid` 도메인이다.
함께 켠 `local` 프로필은 카카오 대역을 사용하고, `APP_AUTH_COOKIE_SECURE=false`는 HTTP 로컬 개발에서 새로고침 토큰 쿠키가 동작하게 한다. 운영에서는 이 값을 사용하지 않는다.

| 계정 | 이메일 | 역할 |
| --- | --- | --- |
| 판매자 | `demo-seller@reused.invalid` | USER |
| 구매자 | `demo-buyer@reused.invalid` | USER |
| 관리자 | `demo-admin@reused.invalid` | ADMIN |

상품 2개(판매 중·거래 승인), 거래 1개, 채팅방·메시지 각 1개, 커뮤니티 글·댓글 각 1개, 신고 1개, 공지 1개가 연결된다. 이미지 업로드·외부 LLM·실시간 Socket.IO 인프라는 이 시드가 제공하지 않는다. 반복 실행해도 같은 샘플 행을 늘리거나 기존 계정 비밀번호·수정한 상태를 덮어쓰지 않는다. 변경된 샘플을 처음 상태로 되돌리려면 **이 데모 전용 DB만** 새로 만들어야 한다.

시드 확인을 마친 뒤 다른 DB로 앱을 실행할 때는 이 셸의 `APP_DEMO_SEED`와 `SPRING_PROFILES_ACTIVE`를 해제한다. 운영 CI/CD 환경에는 이 두 값과 `APP_DEMO_PASSWORD`를 등록하지 않는다.
