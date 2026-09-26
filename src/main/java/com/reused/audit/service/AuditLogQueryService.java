package com.reused.audit.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditResult;
import com.reused.audit.dto.request.AuditLogSearchRequest;
import com.reused.audit.dto.response.AuditLogResponse;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.pagination.CursorCodec;
import com.reused.common.pagination.CursorPageResponse;
import com.reused.user.api.UserQueryService;
import com.reused.user.api.UserSnapshot;

/**
 * 감사 로그 조회(FR-LOG-007). 읽기 경로만 있다. 쓰기는 {@link JdbcAuditLogger}의 INSERT 하나뿐이다(NFR-LOG-002).
 *
 * <p>조건 조합이 동적이라 SQL을 조립한다. {@code ip_address}와 {@code detail}은 SELECT하지 않는다.
 * 한 트랜잭션의 여러 행은 {@code created_at}이 같을 수 있어 정렬·커서는 {@code audit_log_id}로 한다.
 * 행위자는 한 페이지의 id를 모아 {@link UserQueryService}로 한 번에 읽는다.
 */
@Service
public class AuditLogQueryService {

	private static final String SELECT_SQL = """
			SELECT audit_log_id, actor_id, action, target_type, target_id, result, created_at
			FROM audit_logs""";

	private static final RowMapper<AuditLogRow> ROW_MAPPER = AuditLogQueryService::mapRow;

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final UserQueryService userQueryService;

	public AuditLogQueryService(NamedParameterJdbcTemplate jdbcTemplate, UserQueryService userQueryService) {
		this.jdbcTemplate = jdbcTemplate;
		this.userQueryService = userQueryService;
	}

	/**
	 * @throws BusinessException INVALID_INPUT from이 to보다 늦음, 해석할 수 없는 커서
	 */
	@Transactional(readOnly = true)
	public CursorPageResponse<AuditLogResponse> search(AuditLogSearchRequest request) {
		if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "조회 시작 시각은 끝 시각보다 늦을 수 없습니다.");
		}
		int size = request.sizeOrDefault();
		Long cursorId = CursorCodec.decodeId(request.cursor());

		List<String> conditions = new ArrayList<>();
		MapSqlParameterSource params = new MapSqlParameterSource();
		if (request.action() != null) {
			conditions.add("action = :action");
			params.addValue("action", request.action().name());
		}
		if (request.actorId() != null) {
			conditions.add("actor_id = :actorId");
			params.addValue("actorId", request.actorId());
		}
		if (request.from() != null) {
			conditions.add("created_at >= :from");
			params.addValue("from", toTimestamp(request.from()), Types.TIMESTAMP_WITH_TIMEZONE);
		}
		if (request.to() != null) {
			conditions.add("created_at <= :to");
			params.addValue("to", toTimestamp(request.to()), Types.TIMESTAMP_WITH_TIMEZONE);
		}
		if (cursorId != null) {
			conditions.add("audit_log_id < :cursorId");
			params.addValue("cursorId", cursorId);
		}
		params.addValue("limit", size + 1);

		String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
		List<AuditLogRow> rows = jdbcTemplate.query(
				SELECT_SQL + where + " ORDER BY audit_log_id DESC LIMIT :limit", params, ROW_MAPPER);
		CursorPageResponse<AuditLogRow> page = CursorPageResponse.of(rows, size,
				row -> CursorCodec.encodeId(row.auditLogId()));

		Set<Long> actorIds = page.items().stream()
				.map(AuditLogRow::actorId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<Long, UserSnapshot> actors = userQueryService.findSnapshots(actorIds);
		// findSnapshots는 불변 맵을 돌려주고 불변 맵은 null 키 조회를 거부한다.
		return page.map(row -> row.toResponse(row.actorId() == null ? null : actors.get(row.actorId())));
	}

	private static OffsetDateTime toTimestamp(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static AuditLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new AuditLogRow(
				rs.getLong("audit_log_id"),
				rs.getObject("actor_id", Long.class),
				rs.getString("action"),
				rs.getString("target_type"),
				rs.getObject("target_id", Long.class),
				AuditResult.valueOf(rs.getString("result")),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private record AuditLogRow(Long auditLogId, Long actorId, String action, String targetType, Long targetId,
			AuditResult result, Instant createdAt) {

		/**
		 * @param actor actor_id가 NULL이면 null. FK(ON DELETE RESTRICT)가 있어 id가 있으면 회원 행도 있다
		 */
		AuditLogResponse toResponse(UserSnapshot actor) {
			return new AuditLogResponse(auditLogId, actor == null ? null : actor.toSummary(), action, targetType,
					targetId, result, createdAt);
		}

	}

}
