# Re:Used chat server

Socket.IO 기반 실시간 **수신 전용** 게이트웨이다. 메시지 저장·조회와 사용자/채팅방 권한 판정은 Spring API가 담당한다. 이 서버는 JWT 비밀키를 가지지 않으며, Access Token을 Spring 내부 API에 전달해 현재 계정 상태와 방 참여 권한을 확인한다.

## 실행

Node.js 22 또는 24가 필요하다.

```bash
npm ci
npm run build
npm start
```

필수 운영 환경 변수는 다음과 같다.

| 변수 | 설명 | 기본값 |
| --- | --- | --- |
| `CHAT_API_BASE_URL` | Spring API origin. 예: `http://api:8080` | 필수 |
| `CHAT_REDIS_URL` | Redis URL. `redis://` 또는 `rediss://` | 필수 |
| `CHAT_ALLOWED_ORIGINS` | 쉼표로 구분한 정확한 브라우저 origin allowlist | 운영 필수 |
| `PORT` | HTTP/Socket.IO 포트 | `3001` |
| `HOST` | listen 주소 | `0.0.0.0` |
| `CHAT_REQUEST_TIMEOUT_MS` | Spring 내부 API 제한 시간 | `2000` |
| `CHAT_MAX_TOKEN_LENGTH` | 인증 토큰 최대 문자 수 | `8192` |
| `CHAT_MAX_SUBSCRIPTIONS` | 소켓당 동시 방 구독 수 | `50` |
| `CHAT_MAX_CLIENT_PAYLOAD_BYTES` | 클라이언트 Socket.IO 패킷 최대 크기 | `16384` |
| `CHAT_MAX_REDIS_PAYLOAD_BYTES` | Redis 이벤트 최대 바이트 | `65536` |
| `CHAT_DELIVERY_AUTH_CONCURRENCY` | 전달 권한 재검증 동시 요청 수 | `50` |
| `CHAT_DELIVERY_AUTH_QUEUE_LIMIT` | 전달 권한 재검증 대기열 상한 | `2000` |
| `CHAT_SHUTDOWN_TIMEOUT_MS` | 정상 종료 제한 시간 | `10000` |

`NODE_ENV=production`에서는 `CHAT_ALLOWED_ORIGINS`가 비어 있거나 `*`이면 시작하지 않는다. Origin은 `https://app.example.com`처럼 path 없는 origin만 허용한다. 브라우저가 아닌 내부 테스트 클라이언트는 Origin 헤더가 없어도 연결할 수 있다.

Docker 배포 예시:

```bash
docker build -t reused-chat-server ./chat-server
docker run --rm -p 3001:3001 \
  -e CHAT_API_BASE_URL=http://api:8080 \
  -e CHAT_REDIS_URL=redis://redis:6379 \
  -e CHAT_ALLOWED_ORIGINS=https://reused.example.com \
  reused-chat-server
```

컨테이너는 non-root `node` 사용자로 실행한다. `SIGTERM`/`SIGINT`를 받으면 새 전달을 중단하고 소켓과 Redis 연결을 닫는다. `GET /healthz`는 두 Redis 연결이 준비된 경우에만 `200 {"status":"ok"}`를 반환한다.

## Socket.IO 계약

연결 path는 `/socket.io`다. 토큰을 URL query, Socket.IO handshake `auth`, 또는 Authorization 헤더로 보내면 연결을 거부한다.

연결 후 5초 안에 아래 이벤트를 한 번 보내야 한다. 문서 표준 필드는 `token`이고, 배포 전 클라이언트 호환을 위해 `accessToken`도 단독 사용 시 지원한다. 두 필드를 동시에 보내면 모호한 요청으로 거부한다.

```js
socket.emit("authenticate", { token: accessToken });
```

성공하면 `authenticated`를 payload 없이 발행한다. 실패하면 아래 `auth_error` 중 하나를 발행하고 연결을 종료한다.

```json
{ "reason": "TIMEOUT" }
```

- `TIMEOUT`: 5초 안에 인증 이벤트가 없음
- `INVALID`: payload/토큰이 잘못됐거나 만료됨, 또는 현재 계정 자격이 유효하지 않음
- `UNAVAILABLE`: Spring 인증 API를 안전하게 확인할 수 없음

인증 후 방을 구독한다. 서버는 매번 `GET /api/v1/chat-rooms/{id}/subscription`으로 참여자·차단 관계를 확인한다.

```js
socket.emit("subscribe", { chatRoomId: 12 }, (result) => {
  // success: { ok: true, chatRoomId: 12 }
  // denied:  { ok: false, chatRoomId: 12, code: "FORBIDDEN" }
  // duplicate request still being checked: { ok: false, chatRoomId: 12, code: "PENDING" }
});
socket.emit("unsubscribe", { chatRoomId: 12 });
```

권한이 없거나 구독 상한을 넘으면 `forbidden`의 `{ "chatRoomId": 12 }`도 발행한다. 인증 전에 보낸 구독/해제 이벤트와 형식이 잘못된 payload는 처리하지 않는다.

Spring은 `reused:chat:room:{chatRoomId}` Redis 채널에 다음 envelope을 발행한다.

```json
{
  "event": "message",
  "chatRoomId": 12,
  "data": {}
}
```

허용 event는 `message`, `read`, `message_deleted`뿐이다. 채널의 방 ID와 envelope의 `chatRoomId`가 다르거나 payload가 제한을 넘으면 폐기한다. 전달 직전에도 **각 연결마다** 구독 API를 다시 호출한다. API 오류·만료·차단·탈퇴·역할 변경을 확인할 수 없으면 내용을 전송하지 않는다.

서버가 내보내는 이벤트:

- `message`: Spring `MessageResponse`의 허용 필드만 전달하며 `isMine`은 수신 연결의 `userId`와 `senderId`를 비교해 다시 계산한다.
- `read`: `{ chatRoomId, lastReadMessageId }`. 읽은 본인 연결에는 되돌려 보내지 않는다.
- `message_deleted`: `{ chatRoomId, messageId }`.

Redis Pub/Sub은 유실 가능한 실시간 경로다. 재연결 후 누락분은 Spring의 메시지 목록 API로 복구해야 한다.

## 재사용 API와 테스트

빌드 후 다른 E2E 러너에서 서버를 임시 포트로 띄울 수 있다.

```js
import { createChatServer } from "./chat-server/dist/server.js";
import { loadConfig } from "./chat-server/dist/config.js";

const config = loadConfig({
  ...process.env,
  NODE_ENV: "test",
  CHAT_API_BASE_URL: springUrl,
  CHAT_REDIS_URL: redisUrl,
  CHAT_ALLOWED_ORIGINS: "http://localhost",
});
const gateway = createChatServer(config);
const { port, url } = await gateway.start(0);
// ...test...
await gateway.close();
```

서비스 테스트는 실제 임시 Redis Docker 컨테이너와 실제 `socket.io-client` 연결을 사용한다. 테스트가 만든 컨테이너 ID만 종료한다.

```bash
npm test
```
