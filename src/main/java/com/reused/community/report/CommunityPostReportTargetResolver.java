package com.reused.community.report;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.reused.report.api.ReportTarget;
import com.reused.report.api.ReportTargetResolver;
import com.reused.report.api.ReportTargetType;

/** 공개 중인 커뮤니티 글만 새로 신고할 수 있고, 기존 신고 요약은 숨김·삭제 뒤에도 남는다. */
@Component
public class CommunityPostReportTargetResolver implements ReportTargetResolver {

	private final NamedParameterJdbcTemplate jdbc;

	public CommunityPostReportTargetResolver(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReportTargetType type() {
		return ReportTargetType.COMMUNITY_POST;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		return jdbc.query("""
				SELECT post_id, author_id, title FROM community_posts
				WHERE post_id = :targetId AND deleted_at IS NULL AND status = 'PUBLISHED'
				""", new MapSqlParameterSource("targetId", targetId),
				(rs, rowNum) -> new ReportTarget(rs.getLong("post_id"), rs.getLong("author_id"),
						rs.getString("title"))).stream().findFirst();
	}

	@Override
	public Map<Long, ReportTarget> describe(Collection<Long> targetIds) {
		Set<Long> ids = ids(targetIds);
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, ReportTarget> targets = new HashMap<>();
		jdbc.query("""
				SELECT post_id, author_id, title FROM community_posts WHERE post_id IN (:ids)
				""", new MapSqlParameterSource("ids", ids), rs -> {
			long id = rs.getLong("post_id");
			targets.put(id, new ReportTarget(id, rs.getLong("author_id"), rs.getString("title")));
		});
		return Map.copyOf(targets);
	}

	private static Set<Long> ids(Collection<Long> values) {
		return values == null ? Set.of() : values.stream()
				.filter(id -> id != null && id > 0).collect(Collectors.toSet());
	}

}
