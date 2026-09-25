import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { createChatServer } from '../chat-server/dist/server.js';
import { loadConfig } from '../chat-server/dist/config.js';

const requireChat = createRequire(new URL('../chat-server/package.json', import.meta.url));
const { io } = requireChat('socket.io-client');
const origin = 'http://localhost:5173';
const base = process.env.CHAT_API_BASE_URL;
const admin = process.env.MARKET_TEST_ADMIN_TOKEN;
const clients = [];
let gateway;

async function request(method, path, token, body, expected = 200) {
  const response = await fetch(`${base}${path}`, {
    method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error', signal: AbortSignal.timeout(10_000),
  });
  assert.equal(response.status, expected, `${method} ${path}: HTTP ${response.status}`);
  return expected === 204 ? null : response.json();
}

function event(socket, name) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { socket.off(name, receive); reject(new Error(`Timed out: ${name}`)); }, 10_000);
    const receive = data => { clearTimeout(timeout); resolve(data); };
    socket.once(name, receive);
  });
}

async function connect(url, token, room) {
  const socket = io(url, { autoConnect: false, transports: ['websocket'], reconnection: false,
    extraHeaders: { Origin: origin } });
  clients.push(socket);
  const connected = event(socket, 'connect'); socket.connect(); await connected;
  const authenticated = event(socket, 'authenticated');
  socket.emit('authenticate', { token }); await authenticated;
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Timed out: subscribe')), 10_000);
    socket.emit('subscribe', { chatRoomId: room }, result => {
      clearTimeout(timeout);
      if (result?.ok === false || result?.error) reject(new Error('Subscription rejected'));
      else resolve();
    });
  });
  return socket;
}

async function signup(label) {
  return request('POST', '/api/v1/auth/email/signup', null, {
    email: `${randomUUID()}@example.test`, password: 'Marketplace-test-password-42!',
    nickname: `${label}${randomUUID().slice(0, 8)}`, termsOfServiceAgreed: true, privacyPolicyAgreed: true,
  }, 201);
}

try {
  const seller = await signup('판매'); const buyer = await signup('구매'); const outsider = await signup('외부');
  const sellerToken = seller.accessToken; const buyerToken = buyer.accessToken;
  const categories = await request('GET', '/api/v1/categories');
  const categoryId = (Array.isArray(categories) ? categories : categories.items)[0].categoryId;
  const listingBody = { title: '실제 흐름 검증 상품', description: '구매자와 판매자의 중고거래 검증 상품입니다.',
    price: 10000, itemCondition: 'LIKE_NEW', tradeMethod: 'BOTH', categoryId, imageIds: [] };
  const listing = await request('POST', '/api/v1/listings', sellerToken, listingBody, 201);
  for (let i = 0; i < 2; i++) {
    const wished = await request('POST', `/api/v1/listings/${listing.listingId}/wish`, buyerToken);
    assert.equal(wished.wishCount, 1);
  }
  const wishes = await request('GET', '/api/v1/wishes', buyerToken);
  assert.equal(wishes.items[0].listingId, listing.listingId);
  const room = await request('POST', '/api/v1/chat-rooms', buyerToken, { listingId: listing.listingId });
  assert.equal((await request('POST', '/api/v1/chat-rooms', buyerToken, { listingId: listing.listingId })).created, false);
  await request('GET', `/api/v1/chat-rooms/${room.chatRoomId}/messages`, outsider.accessToken, undefined, 403);

  gateway = createChatServer(loadConfig({ ...process.env, NODE_ENV: 'test', CHAT_ALLOWED_ORIGINS: origin, PORT: '3001' }));
  const address = await gateway.start(0);
  const sellerSocket = await connect(address.url, sellerToken, room.chatRoomId);
  const buyerSocket = await connect(address.url, buyerToken, room.chatRoomId);
  const sellerMessage = event(sellerSocket, 'message'); const buyerEcho = event(buyerSocket, 'message');
  const message = await request('POST', `/api/v1/chat-rooms/${room.chatRoomId}/messages`, buyerToken,
    { content: '안녕하세요. 직거래 가능한가요?' }, 201);
  const [received, echo] = await Promise.all([sellerMessage, buyerEcho]);
  assert.equal(received.messageId, message.messageId); assert.equal(received.isMine, false); assert.equal(echo.isMine, true);
  const read = event(buyerSocket, 'read');
  await request('POST', `/api/v1/chat-rooms/${room.chatRoomId}/read`, sellerToken, { lastReadMessageId: message.messageId });
  assert.equal((await read).lastReadMessageId, message.messageId);
  const deleted = event(sellerSocket, 'message_deleted');
  await request('DELETE', `/api/v1/chat-rooms/${room.chatRoomId}/messages/${message.messageId}`, buyerToken, undefined, 204);
  assert.equal((await deleted).messageId, message.messageId);
  const history = await request('GET', `/api/v1/chat-rooms/${room.chatRoomId}/messages`, sellerToken);
  assert.equal(history.items[0].isDeleted, true); assert.equal(history.items[0].content, null);

  const trade = await request('POST', '/api/v1/trades', buyerToken, { listingId: listing.listingId }, 201);
  assert.equal((await request('GET', '/api/v1/chat-rooms', buyerToken)).items[0].tradeId, trade.tradeId);
  await request('POST', `/api/v1/trades/${trade.tradeId}/accept`, sellerToken);
  await request('POST', `/api/v1/trades/${trade.tradeId}/complete`, buyerToken);
  await request('POST', '/api/v1/reviews', buyerToken, { tradeId: trade.tradeId, rating: 5, content: '좋은 거래였습니다.' }, 201);
  await request('POST', '/api/v1/reviews', sellerToken, { tradeId: trade.tradeId, rating: 4 }, 201);
  await request('POST', '/api/v1/reviews', buyerToken, { tradeId: trade.tradeId, rating: 1 }, 409);
  const profile = await request('GET', `/api/v1/users/${seller.user.userId}/profile`);
  assert.equal(profile.completedTradeCount, 1); assert.equal(profile.averageRating, 5);
  await request('PATCH', `/api/v1/admin/listings/${listing.listingId}/status`, admin, { status: 'HIDDEN', reason: '운영 검증' });
  await request('GET', `/api/v1/listings/${listing.listingId}`, null, undefined, 404);
  assert.equal((await request('PATCH', `/api/v1/admin/listings/${listing.listingId}/status`, admin,
    { status: 'RESTORE', reason: '운영 검증 종료' })).status, 'COMPLETED');

  await request('POST', '/api/v1/blocks', buyerToken, { userId: seller.user.userId });
  await request('POST', `/api/v1/chat-rooms/${room.chatRoomId}/messages`, sellerToken, { content: '차단 중 전송' }, 403);
  await request('DELETE', `/api/v1/blocks/${seller.user.userId}`, buyerToken, undefined, 204);
  const selling = await request('GET', '/api/v1/me/selling/listings', sellerToken);
  assert.equal(selling.items[0].status, 'COMPLETED');
  assert.ok((await request('GET', '/api/v1/notifications/unread-count', sellerToken)).count > 0);
  await request('PATCH', '/api/v1/users/me', sellerToken, { bio: '중고거래 검증 완료' });

  const secondListing = await request('POST', '/api/v1/listings', sellerToken, listingBody, 201);
  const pending = await request('POST', '/api/v1/trades', buyerToken, { listingId: secondListing.listingId }, 201);
  await request('POST', `/api/v1/trades/${pending.tradeId}/accept`, sellerToken);
  await request('DELETE', '/api/v1/users/me', buyerToken, undefined, 204);
  await request('GET', '/api/v1/chat/session', buyerToken, undefined, 401);
  assert.equal((await request('GET', `/api/v1/trades/${pending.tradeId}`, sellerToken)).status, 'CANCELED');
  assert.equal((await request('GET', `/api/v1/listings/${secondListing.listingId}`)).status, 'ON_SALE');
  const reviews = await request('GET', `/api/v1/users/${seller.user.userId}/reviews`);
  assert.equal(reviews.items[0].reviewer.userId, null);
  assert.equal((await request('GET', `/api/v1/users/${seller.user.userId}/profile`)).averageRating, 5);
  console.log('Marketplace E2E passed: signup, listing, wishes, real Socket.IO/Redis, trade, review, moderation, withdrawal.');
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Marketplace E2E failed');
  process.exitCode = 1;
} finally {
  for (const socket of clients) socket.disconnect();
  await gateway?.close();
}
