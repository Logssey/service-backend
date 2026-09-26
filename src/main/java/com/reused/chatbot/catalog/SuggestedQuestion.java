package com.reused.chatbot.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 추천 질문과 사전 정의 답변(FR-AI-001, UC-09). 추천 질문은 LLM을 부르지 않고 이 답변을 그대로 돌려준다.
 *
 * <p>문서에는 저장소가 정해져 있지 않다(챗봇 테이블·Redis 키 없음). 편집 API도 없어 코드에 둔다.
 * 질문 문구는 와이어프레임(BOT-001), questionId 1의 답변은 챗봇 메시지 전송 명세의 예시 원문이다.
 * 2~5의 답변은 business-rules와 요구사항을 요약한 초안이다. 거래·후기 규칙(A 도메인)이 바뀌면 함께 고친다.
 *
 * <p>선언 순서가 목록 응답 순서다(questionId 오름차순). id는 한 번 공개되면 바꾸지 않는다(프론트가 그대로 보낸다).
 */
public enum SuggestedQuestion {

	TRADE_FLOW(1L, "거래는 어떻게 진행되나요?",
			"구매자가 거래를 요청하면 판매자가 승인하고, 직거래 후 구매자가 완료를 확정합니다."),

	REPORT(2L, "신고는 어떻게 하나요?",
			"게시글, 사용자, 채팅 메시지, 커뮤니티 게시글·댓글에서 신고를 선택하고 사유를 고른 뒤 필요하면 상세 내용을 적어 접수합니다. "
					+ "운영자가 확인해 조치하며, 처리 결과는 알림으로 알려 드리고 내 신고 목록에서 처리 상태를 확인할 수 있습니다."),

	REVIEW(3L, "후기는 언제 작성할 수 있나요?",
			"거래가 완료된 뒤 거래 당사자인 구매자와 판매자가 각각 한 번씩 작성할 수 있습니다. "
					+ "별점과 내용을 남기면 상대방 프로필에 반영되며, 취소된 거래에는 후기를 작성할 수 없습니다."),

	WITHDRAWAL(4L, "계정을 삭제하고 싶어요",
			"내 정보 화면에서 회원 탈퇴를 진행할 수 있습니다. 진행 중인 거래는 취소되고 상대방에게 알림이 가며, "
					+ "작성한 게시글·후기·메시지는 남지만 작성자 정보는 익명으로 바뀝니다. "
					+ "탈퇴 후 같은 이메일로 다시 가입할 수 있지만 이전 이력과는 연결되지 않습니다."),

	REFUND(5L, "환불 정책이 궁금해요",
			"Re:Used는 결제 기능을 제공하지 않으며 거래 대금은 구매자와 판매자가 직접 주고받습니다. "
					+ "그래서 서비스를 통한 환불은 없고, 환불은 거래 당사자끼리 협의해야 합니다. "
					+ "사기나 약속 불이행이 의심되면 신고 기능을 이용해 주세요.");

	private static final List<SuggestedQuestion> ALL = List.of(values());

	private final Long id;
	private final String question;
	private final String answer;

	SuggestedQuestion(Long id, String question, String answer) {
		this.id = id;
		this.question = question;
		this.answer = answer;
	}

	public static List<SuggestedQuestion> all() {
		return ALL;
	}

	public static Optional<SuggestedQuestion> findById(Long id) {
		if (id == null) {
			return Optional.empty();
		}
		return Arrays.stream(values()).filter(question -> question.id.equals(id)).findFirst();
	}

	public Long id() {
		return id;
	}

	public String question() {
		return question;
	}

	public String answer() {
		return answer;
	}

}
