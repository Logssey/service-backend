package com.reused.report.service;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.reused.report.api.ReportStatsQuery;
import com.reused.report.api.ReportTargetType;

/**
 * reports(B 소유)를 SQL로 집계한다. 신고 엔티티와 무관하게 쓸 수 있도록 JDBC로 둔다.
 *
 * <p>id 목록은 IN으로 펼친다. 호출하는 쪽은 한 페이지(최대 100건) 단위로 부른다.
 */
@Component
public class JdbcReportStatsQuery implements ReportStatsQuery {

	private static final String COUNT_BY_TARGETS_SQL = """
			SELECT target_id, count(*) AS report_count
			FROM reports
			WHERE target_type = :targetType AND target_id IN (:targetIds)
			GROUP BY target_id""";

	private static final String COUNT_RESOLVED_AGAINST_USER_SQL = """
			SELECT count(*)
			FROM reports
			WHERE target_type = 'USER' AND target_id = :userId
			  AND status = 'RESOLVED' AND handled_at >= :since""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public JdbcReportStatsQuery(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, Long> countByTargets(ReportTargetType type, Collection<Long> targetIds) {
		Objects.requireNonNull(type, "type");
		Set<Long> ids = distinctIds(targetIds);
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, Long> counts = new HashMap<>();
		ids.forEach(id -> counts.put(id, 0L));
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("targetType", type.name())
				.addValue("targetIds", ids);
		RowCallbackHandler collect = rs -> counts.put(rs.getLong("target_id"), rs.getLong("report_count"));
		jdbcTemplate.query(COUNT_BY_TARGETS_SQL, params, collect);
		return Map.copyOf(counts);
	}

	@Override
	@Transactional(readOnly = true)
	public long countResolvedAgainstUserSince(Long userId, Instant since) {
		Objects.requireNonNull(since, "since");
		if (userId == null) {
			return 0L;
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("userId", userId)
				.addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
		Long count = jdbcTemplate.queryForObject(COUNT_RESOLVED_AGAINST_USER_SQL, params, Long.class);
		return count == null ? 0L : count;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, Long> countAgainstUsers(Collection<Long> userIds) {
		return countByTargets(ReportTargetType.USER, userIds);
	}

	private static Set<Long> distinctIds(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Set.of();
		}
		return ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
	}

}
