package com.reused.user.api;

/**
 * 토큰 사용자의 DB 상태 확인(NFR-AUTH-012). Access Token에는 정지·탈퇴가 담기지 않고 최대 30분 유효하므로
 * 인증이 필요한 기능은 이 확인으로 그 창을 막는다. A·B 모든 도메인이 사용한다.
 *
 * <p>정지 판정은 "상태가 SUSPENDED이고 기간이 없거나 아직 끝나지 않음"이다. 기간이 지난 정지는 해제로 본다.
 */
public interface ActiveUserGuard {

	/**
	 * 쓰기 중 문서가 정지 403을 적은 기능용(게시글 등록, 거래 요청, 메시지 전송, 신고 접수, 내 정보 수정 등).
	 *
	 * @throws com.reused.common.error.BusinessException UNAUTHENTICATED 사용자 없음 또는 탈퇴,
	 *         USER_SUSPENDED 정지 중
	 */
	void requireActive(Long userId);

	/** 예외 없이 판정만 한다. 없음·탈퇴·정지 중이면 false */
	boolean isActive(Long userId);

	/**
	 * 정지 회원도 허용하는 기능용(차단, 알림, 챗봇, 비밀번호 변경, 탈퇴, 신고 목록).
	 *
	 * @throws com.reused.common.error.BusinessException UNAUTHENTICATED 사용자 없음 또는 탈퇴
	 */
	void requireMember(Long userId);

}
