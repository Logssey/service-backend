package com.reused.audit.api;

/**
 * 감사 로그 기록(NFR-AUTH-013, FR-LOG-001~006). A·B 모든 도메인이 이 인터페이스로만 기록한다.
 * 쓰기 경로는 INSERT 하나뿐이며 수정·삭제 API는 두지 않는다(NFR-LOG-002).
 */
public interface AuditLogger {

	/**
	 * 호출자 트랜잭션에 참여한다(REQUIRED). 관리자 조치처럼 업무 변경과 함께 커밋·롤백되어야 할 때 쓴다.
	 * readOnly 트랜잭션 안에서 부르지 않는다(INSERT가 거부된다).
	 *
	 * @throws RuntimeException 기록 실패. 호출자 트랜잭션이 함께 롤백되어 기록 없는 변경이 남지 않는다
	 */
	void record(AuditEntry entry);

	/**
	 * 별도 트랜잭션(REQUIRES_NEW)으로 즉시 커밋한다. 인증 이벤트·실패 기록·외부 연동처럼
	 * 업무의 롤백과 무관하게 남겨야 할 때 쓴다. 기록 실패는 로그만 남기고 삼킨다 — 업무 흐름을 깨뜨리지 않는다.
	 *
	 * <p>트랜잭션 안(커밋 뒤 콜백 포함)에서 부르면 호출자의 커넥션을 쥔 채 커넥션을 하나 더 잡는다. 이런 요청이 풀 크기만큼
	 * 동시에 몰리면 모두 두 번째 커넥션을 기다리며 풀이 멈추고, 대기 시간이 지나면 기록이 사라진다. 공개 엔드포인트처럼
	 * 요청이 몰릴 수 있는 경로는 트랜잭션을 DB 작업으로 좁히고 그 트랜잭션이 끝난 뒤에 부른다({@code EmailAuthService} 참고).
	 *
	 * <p>호출자 트랜잭션에서 만든 행을 actor·target으로 가리키면 그 행이 아직 커밋되지 않아 FK 검사에 실패한다.
	 * 이때도 커밋 뒤에 부른다. 바깥 트랜잭션이 있을 수 있으면 {@code AfterCommit.run(() -> auditLogger.recordSeparately(entry))}로
	 * 감싼다. 트랜잭션이 없으면 바로 실행된다.
	 */
	void recordSeparately(AuditEntry entry);

}
