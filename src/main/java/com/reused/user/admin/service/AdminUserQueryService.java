package com.reused.user.admin.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.admin.api.ListingStatsPort;
import com.reused.common.pagination.CursorCodec;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.report.api.ReportStatsQuery;
import com.reused.user.admin.dto.request.AdminUserSearchRequest;
import com.reused.user.admin.dto.response.AdminUserResponse;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;

/**
 * 관리자 회원 목록. 조건 조합이 동적이라 SQL을 조립한다. JPQL의 {@code (:x IS NULL OR ...)}는 PostgreSQL이
 * null 파라미터의 타입을 추론하지 못해 쓰지 않는다.
 *
 * <p>정렬은 {@code user_id DESC}(가입 최신순)이고 커서는 마지막 userId다. 게시글 수(A)와 피신고 수는 한 페이지를
 * 읽은 뒤 id 목록으로 한 번씩 모아 읽는다(N+1 없음).
 */
@Service
public class AdminUserQueryService {

	private static final String SELECT_SQL = """
			SELECT user_id, nickname, role, status, suspended_until, created_at, withdrawn_at
			FROM users""";

	/** LIKE 특수문자를 글자 그대로 찾도록 이스케이프 문자를 명시한다 */
	private static final String KEYWORD_CONDITION = "nickname ILIKE :keyword ESCAPE '\\'";

	private static final RowMapper<UserRow> ROW_MAPPER = AdminUserQueryService::mapRow;

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final ReportStatsQuery reportStatsQuery;
	private final ObjectProvider<ListingStatsPort> listingStatsPort;

	public AdminUserQueryService(NamedParameterJdbcTemplate jdbcTemplate, ReportStatsQuery reportStatsQuery,
			ObjectProvider<ListingStatsPort> listingStatsPort) {
		this.jdbcTemplate = jdbcTemplate;
		this.reportStatsQuery = reportStatsQuery;
		this.listingStatsPort = listingStatsPort;
	}

	/**
	 * @throws com.reused.common.error.BusinessException INVALID_INPUT 해석할 수 없는 커서
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<AdminUserResponse> search(AdminUserSearchRequest request) {
		int size = request.sizeOrDefault();
		Long cursorId = CursorCodec.decodeId(request.cursor());

		List<String> conditions = new ArrayList<>();
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (request.status() != null) {
			conditions.add(statusCondition(request.status()));
			params.addValue("status", request.status().name());
		}
		String keyword = request.normalizedKeyword();
		if (keyword != null) {
			conditions.add(KEYWORD_CONDITION);
			params.addValue("keyword", "%" + escapeLike(keyword) + "%");
		}
		if (cursorId != null) {
			conditions.add("user_id < :cursorId");
			params.addValue("cursorId", cursorId);
		}
		params.addValue("limit", size + 1);

		String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
		List<UserRow> rows = jdbcTemplate.query(SELECT_SQL + where + " ORDER BY user_id DESC LIMIT :limit", params,
				ROW_MAPPER);
		CursorPageResponse<UserRow> page = CursorPageResponse.of(rows, size,
				row -> CursorCodec.encodeId(row.userId()));

		List<Long> userIds = page.items().stream().map(UserRow::userId).toList();
		Map<Long, Long> reportedCounts = reportStatsQuery.countAgainstUsers(userIds);
		Map<Long, Long> listingCounts = listingCounts(userIds);
		return page.map(row -> row.toResponse(
				listingCounts.getOrDefault(row.userId(), 0L),
				reportedCounts.getOrDefault(row.userId(), 0L)));
	}

	/**
	 * 탈퇴 시각만 기록된 비정상 행은 탈퇴로 분류한다({@code User.isWithdrawn()}과 같은 기준). 파라미터는 {@code :status}.
	 */
	private static String statusCondition(UserStatus status) {
		if (status == UserStatus.WITHDRAWN) {
			return "(status = :status OR withdrawn_at IS NOT NULL)";
		}
		return "status = :status AND withdrawn_at IS NULL";
	}

	/** A 코드가 없으면 게시글도 없다 */
	private Map<Long, Long> listingCounts(List<Long> userIds) {
		ListingStatsPort port = listingStatsPort.getIfAvailable();
		if (port == null || userIds.isEmpty()) {
			return Map.of();
		}
		return port.countBySellers(userIds);
	}

	private static String escapeLike(String keyword) {
		return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static UserRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new UserRow(
				rs.getLong("user_id"),
				rs.getString("nickname"),
				UserRole.valueOf(rs.getString("role")),
				UserStatus.valueOf(rs.getString("status")),
				toInstant(rs.getObject("suspended_until", OffsetDateTime.class)),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)),
				rs.getObject("withdrawn_at", OffsetDateTime.class) != null);
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private record UserRow(Long userId, String nickname, UserRole role, UserStatus status, Instant suspendedUntil,
			Instant createdAt, boolean withdrawnAtRecorded) {

		/**
		 * 정지 종료 시각은 SUSPENDED일 때만 싣는다. 정지 중 탈퇴한 회원은 종료 시각이 남아 있을 수 있다.
		 */
		AdminUserResponse toResponse(long listingCount, long reportedCount) {
			UserStatus displayed = withdrawnAtRecorded ? UserStatus.WITHDRAWN : status;
			Instant until = displayed == UserStatus.SUSPENDED ? suspendedUntil : null;
			return new AdminUserResponse(userId, nickname, role, displayed, until, listingCount, reportedCount,
					createdAt);
		}

	}

}
