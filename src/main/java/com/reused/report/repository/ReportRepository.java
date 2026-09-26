package com.reused.report.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportReasonCode;
import com.reused.report.entity.ReportStatus;

import jakarta.persistence.LockModeType;

public interface ReportRepository extends JpaRepository<Report, Long> {

	/**
	 * 미처리 중복 신고 확인. statuses에는 부분 UNIQUE 인덱스(uq_reports_pending_duplicate)와 같은
	 * {@link ReportStatus#PENDING}을 넘긴다.
	 */
	boolean existsByReporterIdAndTargetTypeAndTargetIdAndReasonCodeAndStatusIn(Long reporterId,
			ReportTargetType targetType, Long targetId, ReportReasonCode reasonCode, Collection<ReportStatus> statuses);

	/**
	 * 내 신고 목록 한 페이지. report_id 내림차순이고 cursorId 미만만 읽는다. 첫 페이지는 {@code Long.MAX_VALUE}를 넘긴다.
	 */
	List<Report> findByReporterIdAndIdLessThanOrderByIdDesc(Long reporterId, Long cursorId, Limit limit);

	/**
	 * {@code SELECT ... FOR NO KEY UPDATE}. 두 관리자가 같은 신고를 동시에 처리하면 뒤의 요청은 앞의 커밋을 기다렸다가
	 * 최종 상태를 보고 409가 된다. reports에는 version 컬럼이 없어 비관적 잠금을 쓴다. 트랜잭션 안에서만 부른다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Report r where r.id = :id")
	Optional<Report> findByIdForUpdate(@Param("id") Long id);

}
