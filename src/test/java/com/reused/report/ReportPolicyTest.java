package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.ReportAction;
import com.reused.report.entity.ReportReasonCode;
import com.reused.report.entity.ReportStatus;

/**
 * 신고 사유·조치 허용표. 신고 접수 명세의 "대상 유형별 허용 코드"와 신고 처리 명세의 조치 표를 그대로 옮겼는지 본다.
 */
class ReportPolicyTest {

	@Test
	@DisplayName("대상 유형별 허용 사유는 신고 접수 명세의 표와 같다")
	void reasonCodesByTargetType() {
		assertThat(allowedReasons(ReportTargetType.LISTING)).containsExactlyInAnyOrder(
				ReportReasonCode.PROHIBITED_ITEM, ReportReasonCode.FALSE_INFO, ReportReasonCode.FRAUD_SUSPICION,
				ReportReasonCode.SPAM, ReportReasonCode.OTHER);
		assertThat(allowedReasons(ReportTargetType.USER)).containsExactlyInAnyOrder(
				ReportReasonCode.FRAUD_SUSPICION, ReportReasonCode.ABUSIVE_BEHAVIOR, ReportReasonCode.NO_SHOW,
				ReportReasonCode.OTHER);
		assertThat(allowedReasons(ReportTargetType.MESSAGE)).containsExactlyInAnyOrder(
				ReportReasonCode.ABUSIVE_BEHAVIOR, ReportReasonCode.SEXUAL_CONTENT, ReportReasonCode.SPAM,
				ReportReasonCode.OTHER);
		assertThat(allowedReasons(ReportTargetType.COMMUNITY_POST)).containsExactlyInAnyOrder(
				ReportReasonCode.FALSE_INFO, ReportReasonCode.ABUSIVE_BEHAVIOR, ReportReasonCode.SEXUAL_CONTENT,
				ReportReasonCode.SPAM, ReportReasonCode.OTHER);
		assertThat(allowedReasons(ReportTargetType.COMMUNITY_COMMENT)).containsExactlyInAnyOrder(
				ReportReasonCode.ABUSIVE_BEHAVIOR, ReportReasonCode.SEXUAL_CONTENT, ReportReasonCode.SPAM,
				ReportReasonCode.OTHER);
	}

	@Test
	@DisplayName("조치는 대상과 맞는 것만 허용한다. 커뮤니티는 일치하는 숨김만, 정지와 NONE은 모든 유형에 허용한다")
	void actionsByTargetType() {
		assertThat(allowedActions(ReportTargetType.LISTING)).containsExactlyInAnyOrder(
				ReportAction.HIDE_LISTING, ReportAction.DELETE_LISTING, ReportAction.SUSPEND_USER, ReportAction.NONE);
		assertThat(allowedActions(ReportTargetType.USER))
				.containsExactlyInAnyOrder(ReportAction.SUSPEND_USER, ReportAction.NONE);
		assertThat(allowedActions(ReportTargetType.MESSAGE))
				.containsExactlyInAnyOrder(ReportAction.SUSPEND_USER, ReportAction.NONE);
		assertThat(allowedActions(ReportTargetType.COMMUNITY_POST)).containsExactlyInAnyOrder(
				ReportAction.HIDE_COMMUNITY_POST, ReportAction.SUSPEND_USER, ReportAction.NONE);
		assertThat(allowedActions(ReportTargetType.COMMUNITY_COMMENT)).containsExactlyInAnyOrder(
				ReportAction.HIDE_COMMUNITY_COMMENT, ReportAction.SUSPEND_USER, ReportAction.NONE);
	}

	@Test
	@DisplayName("미처리는 RECEIVED·IN_REVIEW이고 최종 상태는 RESOLVED·REJECTED다")
	void pendingAndClosedStatuses() {
		assertThat(ReportStatus.PENDING).containsExactlyInAnyOrder(ReportStatus.RECEIVED, ReportStatus.IN_REVIEW);
		assertThat(EnumSet.allOf(ReportStatus.class).stream().filter(ReportStatus::isClosed))
				.containsExactlyInAnyOrder(ReportStatus.RESOLVED, ReportStatus.REJECTED);
	}

	private static Set<ReportReasonCode> allowedReasons(ReportTargetType type) {
		return Arrays.stream(ReportReasonCode.values()).filter(code -> code.isAllowedFor(type))
				.collect(Collectors.toSet());
	}

	private static Set<ReportAction> allowedActions(ReportTargetType type) {
		return Arrays.stream(ReportAction.values()).filter(action -> action.isAllowedFor(type))
				.collect(Collectors.toSet());
	}

}
