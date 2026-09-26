package com.reused.notification.api;

/**
 * 인앱 알림 발행(신고 처리 결과·공지 등록용). 저장은 업무 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 한다.
 * 거래·채팅·후기·탈퇴 알림은 팀 공용 {@code NotificationService.createFor}가 업무 트랜잭션 안에서 만든다.
 * 업무가 롤백되면 알림도 만들지 않고, 알림 저장이 실패해도 업무를 되돌리지 않는다(ADR-014).
 * 커밋과 저장 사이에 프로세스가 죽으면 알림은 사라진다(ADR-014가 받아들인 손실).
 *
 * <p>수신자가 없거나 탈퇴했으면, 또는 수신자가 해당 유형을 꺼 두었으면 만들지 않는다.
 */
public interface NotificationEventPublisher {

	/**
	 * 업무 트랜잭션 안에서 호출한다. 트랜잭션 밖이면 즉시 저장한다. 저장 실패는 호출자에게 전파되지 않는다.
	 */
	void publish(NotificationEvent event);

	/**
	 * 공지 등록 시 전체 회원에게 NOTICE_PUBLISHED를 보낸다. 커밋 뒤 INSERT…SELECT 한 문장으로 처리한다.
	 *
	 * @param noticeTitle 알림 body가 된다
	 */
	void publishNoticeToAll(Long noticeId, String noticeTitle);

}
