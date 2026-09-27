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

/** 댓글 신고는 댓글과 부모 글이 모두 공개 중일 때만 받는다. */
@Component
public class CommunityCommentReportTargetResolver implements ReportTargetResolver {

	private final NamedParameterJdbcTemplate jdbc;

	public CommunityCommentReportTargetResolver(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReportTargetType type() {
		return ReportTargetType.COMMUNITY_COMMENT;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		return jdbc.query("""
				SELECT comment.comment_id, comment.author_id, comment.content
				FROM community_comments comment
				JOIN community_posts post ON post.post_id = comment.post_id
				WHERE comment.comment_id = :targetId
				  AND comment.deleted_at IS NULL AND comment.status = 'PUBLISHED'
				  AND post.deleted_at IS NULL AND post.status = 'PUBLISHED'
				""", new MapSqlParameterSource("targetId", targetId),
				(rs, rowNum) -> new ReportTarget(rs.getLong("comment_id"), rs.getLong("author_id"),
						rs.getString("content"))).stream().findFirst();
	}

	@Override
	public Map<Long, ReportTarget> describe(Collection<Long> targetIds) {
		Set<Long> ids = ids(targetIds);
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, ReportTarget> targets = new HashMap<>();
		jdbc.query("""
				SELECT comment_id, author_id, content FROM community_comments WHERE comment_id IN (:ids)
				""", new MapSqlParameterSource("ids", ids), rs -> {
			long id = rs.getLong("comment_id");
			targets.put(id, new ReportTarget(id, rs.getLong("author_id"), rs.getString("content")));
		});
		return Map.copyOf(targets);
	}

	private static Set<Long> ids(Collection<Long> values) {
		return values == null ? Set.of() : values.stream()
				.filter(id -> id != null && id > 0).collect(Collectors.toSet());
	}

}
