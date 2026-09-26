package com.reused.audit;

import org.springframework.test.context.TestPropertySource;

/**
 * {@link AuthAuditIntegrationTest}를 커넥션 풀 1개로 다시 돌린다.
 *
 * <p>인증 흐름이 자기 트랜잭션의 커넥션을 쥔 채 감사 기록용 별도 트랜잭션(REQUIRES_NEW)을 열면 요청 하나가 커넥션 두 개를
 * 동시에 잡는다. 공개 로그인 엔드포인트에 동시 요청이 풀 크기만큼 몰리면 모두 두 번째 커넥션을 기다리며 풀이 멈추고,
 * 대기 시간이 지나면 실패 기록이 조용히 사라진다. 풀이 1개면 그 경로가 대기 시간 초과로 드러나 기록 누락으로 실패한다.
 */
@TestPropertySource(properties = {
		"spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.connection-timeout=1000" })
class AuthAuditSingleConnectionIntegrationTest extends AuthAuditIntegrationTest {

}
