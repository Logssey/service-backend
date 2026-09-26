package com.reused.notice.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공지사항(notices). 관리자만 등록·수정·삭제한다(FR-ADMIN-011).
 *
 * <p>삭제는 논리 삭제다(deleted_at, schema-and-erd "소프트 삭제"). 삭제자 컬럼이 없어 누가 지웠는지는 감사 로그에만 남는다.
 * 삭제된 행을 제외하는 조건은 {@code @SQLRestriction} 대신 리포지토리 쿼리에 명시한다. 감사·테스트에서 삭제된 행도 읽기 위해서다.
 *
 * <p>작성자는 연관 대신 id로 둔다. 응답에 작성자를 싣지 않는다(공지 DTO에 없음).
 *
 * <p>시각은 호출자가 마이크로초로 잘라 넘긴다. PostgreSQL TIMESTAMPTZ 정밀도와 같아야 수정 응답의 updatedAt과
 * 이후 조회 값, 목록 커서의 createdAt이 DB 값과 일치한다.
 */
@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notice_id")
	private Long id;

	@Column(name = "author_id", nullable = false, updatable = false)
	private Long authorId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 5000)
	private String content;

	@Column(name = "is_pinned", nullable = false)
	private boolean pinned;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	private Notice(Long authorId, String title, String content, boolean pinned, Instant now) {
		this.authorId = authorId;
		this.title = title;
		this.content = content;
		this.pinned = pinned;
		this.createdAt = now;
	}

	/**
	 * 등록. 한 번도 수정하지 않은 공지는 updatedAt이 null이다(상세 조회 명세 예시).
	 */
	public static Notice publish(Long authorId, String title, String content, boolean pinned, Instant now) {
		return new Notice(authorId, title, content, pinned, now);
	}

	/**
	 * null 인자는 바꾸지 않는다. 값이 같아도 updatedAt을 갱신한다(비교하지 않는다).
	 */
	public void edit(String title, String content, Boolean pinned, Instant now) {
		if (title != null) {
			this.title = title;
		}
		if (content != null) {
			this.content = content;
		}
		if (pinned != null) {
			this.pinned = pinned;
		}
		this.updatedAt = now;
	}

	/**
	 * 논리 삭제. updatedAt과 고정 여부는 그대로 둔다.
	 */
	public void delete(Instant now) {
		this.deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

}
