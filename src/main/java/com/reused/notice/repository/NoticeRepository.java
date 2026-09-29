package com.reused.notice.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.reused.notice.entity.Notice;

import jakarta.persistence.LockModeType;

/**
 * 삭제된 공지(deleted_at IS NOT NULL)를 제외하는 조건은 메서드마다 명시한다. 엔티티에 전역 제한을 걸지 않았다.
 *
 * <p>목록은 "고정 공지 우선, 최신순"(공지사항 목록 조회 명세)을 {@code is_pinned DESC, created_at DESC, notice_id DESC}로 고정한다.
 * 세 컬럼이 모두 내림차순이라 다음 페이지는 행 비교로 "마지막 항목보다 작은 튜플"이다(false &lt; true).
 * 공지는 수가 적어 인덱스 없이 PK와 정렬만으로 충분하다(추가 인덱스는 실측 후에만 둔다는 방침).
 */
public interface NoticeRepository extends JpaRepository<Notice, Long> {

	Optional<Notice> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * {@code SELECT ... FOR NO KEY UPDATE}. 수정과 삭제를 직렬화한다. 잠그지 않으면 삭제와 동시에 들어온 수정이
	 * 읽어 둔 deleted_at(NULL)으로 행 전체를 덮어써 삭제된 공지가 되살아나고, 중복 삭제가 감사 로그를 두 번 남긴다.
	 * 잠금을 기다린 뒤 PostgreSQL이 조건을 다시 평가하므로 먼저 삭제된 공지는 빈 결과(404)가 된다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select n from Notice n where n.id = :id and n.deletedAt is null")
	Optional<Notice> findActiveByIdForUpdate(@Param("id") Long id);

	@Query(value = """
			SELECT * FROM notices
			WHERE deleted_at IS NULL
			ORDER BY is_pinned DESC, created_at DESC, notice_id DESC
			LIMIT :limit""", nativeQuery = true)
	List<Notice> findFirstPage(@Param("limit") int limit);

	/**
	 * 커서(마지막 항목의 정렬 키) 다음부터. 행 비교는 PostgreSQL 전용이다(스키마 자체가 PostgreSQL 전용).
	 */
	@Query(value = """
			SELECT * FROM notices
			WHERE deleted_at IS NULL
			  AND (is_pinned, created_at, notice_id) < (:pinned, :createdAt, :noticeId)
			ORDER BY is_pinned DESC, created_at DESC, notice_id DESC
			LIMIT :limit""", nativeQuery = true)
	List<Notice> findPageAfter(@Param("pinned") boolean pinned, @Param("createdAt") Instant createdAt,
			@Param("noticeId") long noticeId, @Param("limit") int limit);

}
