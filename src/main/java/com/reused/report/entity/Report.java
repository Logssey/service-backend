package com.reused.report.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.reused.report.api.ReportTargetType;

/**
 * 신고(reports). 대상은 target_type과 target_id의 다형 참조라 FK가 없다. 존재 여부는 접수 때 확인한다(DDL 주석).
 *
 * <p>신고자와 처리 관리자는 연관 대신 id로 둔다. 같은 신고자·대상·사유의 미처리 신고는 부분 UNIQUE
 * (uq_reports_pending_duplicate)로 하나만 존재한다. 처리가 끝나면 같은 사유로 다시 신고할 수 있다.
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "report_id")
	private Long id;

	@Column(name = "reporter_id", nullable = false)
	private Long reporterId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 20)
	private ReportTargetType targetType;

	@Column(name = "target_id", nullable = false)
	private Long targetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, length = 30)
	private ReportReasonCode reasonCode;

	@Column(length = 500)
	private String detail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportStatus status;

	/** 검토를 시작했거나 처리한 관리자 */
	@Column(name = "handled_by")
	private Long handledBy;

	/** RESOLVED·REJECTED로 처리한 시각. 검토 중에는 null */
	@Column(name = "handled_at")
	private Instant handledAt;

	/** 처리 결과. 처리 완료 전까지 null(내 신고 목록 조회 명세) */
	@Column(length = 500)
	private String resolution;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	private Report(Long reporterId, ReportTargetType targetType, Long targetId, ReportReasonCode reasonCode,
			String detail) {
		this.reporterId = reporterId;
		this.targetType = targetType;
		this.targetId = targetId;
		this.reasonCode = reasonCode;
		this.detail = detail;
		this.status = ReportStatus.RECEIVED;
		this.createdAt = Instant.now();
	}

	/**
	 * @param detail 없으면 null
	 */
	public static Report receive(Long reporterId, ReportTargetType targetType, Long targetId,
			ReportReasonCode reasonCode, String detail) {
		return new Report(reporterId, targetType, targetId, reasonCode, detail);
	}

	/**
	 * 검토 시작. 처리 시각과 결과는 처리할 때 남긴다.
	 */
	public void startReview(Long adminId) {
		this.status = ReportStatus.IN_REVIEW;
		this.handledBy = adminId;
	}

	public void resolve(Long adminId, String resolution, Instant now) {
		close(ReportStatus.RESOLVED, adminId, resolution, now);
	}

	public void reject(Long adminId, String resolution, Instant now) {
		close(ReportStatus.REJECTED, adminId, resolution, now);
	}

	private void close(ReportStatus result, Long adminId, String resolution, Instant now) {
		this.status = result;
		this.handledBy = adminId;
		this.handledAt = now;
		this.resolution = resolution;
	}

}
