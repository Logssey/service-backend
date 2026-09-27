package com.reused.report;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.reused.report.api.ReportTargetType;

/**
 * A(게시글·채팅)와 커뮤니티 담당의 신고 계약 구현 대신 쓰는 대역. 쓰는 테스트가 {@code @Import}로 등록한다.
 * USER는 실제 구현(UserReportTargetResolver)을 그대로 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeReportTargetsConfiguration {

	@Bean
	FakeReportTargetResolver listingReportTargets() {
		return new FakeReportTargetResolver(ReportTargetType.LISTING);
	}

	@Bean
	FakeReportTargetResolver messageReportTargets() {
		return new FakeReportTargetResolver(ReportTargetType.MESSAGE);
	}

	@Bean
	FakeReportTargetResolver communityPostReportTargets() {
		return new FakeReportTargetResolver(ReportTargetType.COMMUNITY_POST);
	}

	@Bean
	FakeReportTargetResolver communityCommentReportTargets() {
		return new FakeReportTargetResolver(ReportTargetType.COMMUNITY_COMMENT);
	}

	@Bean
	@Primary
	RecordingContentModerationPort recordingContentModerationPort() {
		return new RecordingContentModerationPort();
	}

}
