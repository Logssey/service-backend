package com.reused.report.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.reused.report.api.ReportTargetType;
import com.reused.report.entity.Report;
import com.reused.report.entity.ReportStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 관리자 신고 목록 검색. 선택 조건(status, targetType)이 있어 Criteria로 조건을 조립한다.
 *
 * <p>JPQL 한 문장에 {@code (:status IS NULL OR r.status = :status)}로 넣으면 null 파라미터의 타입을
 * PostgreSQL이 추론하지 못해 실패한다(0단계 계약 §8.5). 주어진 조건만 WHERE에 넣는다.
 */
@Repository
public class ReportSearchRepository {

	private final EntityManager entityManager;

	public ReportSearchRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * report_id 내림차순.
	 *
	 * @param status null이면 모든 상태
	 * @param targetType null이면 모든 대상 유형
	 * @param cursorId null이면 첫 페이지. 있으면 그 id 미만
	 * @param limit 읽을 최대 행 수(페이지 크기 + 1)
	 */
	public List<Report> search(ReportStatus status, ReportTargetType targetType, Long cursorId, int limit) {
		CriteriaBuilder cb = entityManager.getCriteriaBuilder();
		CriteriaQuery<Report> query = cb.createQuery(Report.class);
		Root<Report> report = query.from(Report.class);

		List<Predicate> conditions = new ArrayList<>();
		if (status != null) {
			conditions.add(cb.equal(report.get("status"), status));
		}
		if (targetType != null) {
			conditions.add(cb.equal(report.get("targetType"), targetType));
		}
		if (cursorId != null) {
			conditions.add(cb.lessThan(report.<Long>get("id"), cursorId));
		}
		query.select(report)
				.where(conditions.toArray(Predicate[]::new))
				.orderBy(cb.desc(report.get("id")));
		return entityManager.createQuery(query).setMaxResults(limit).getResultList();
	}

}
