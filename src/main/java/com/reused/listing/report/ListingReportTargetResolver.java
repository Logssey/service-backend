package com.reused.listing.report;

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

/** 판매 게시글 신고 대상 조회. 관리자 요약은 숨김·삭제 이후에도 원래 제목을 보존한다. */
@Component
public class ListingReportTargetResolver implements ReportTargetResolver {

	private final NamedParameterJdbcTemplate jdbc;

	public ListingReportTargetResolver(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReportTargetType type() {
		return ReportTargetType.LISTING;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		return jdbc.query("""
				SELECT l.listing_id, l.seller_id, l.title
				FROM listings l JOIN users seller ON seller.user_id = l.seller_id
				WHERE l.listing_id = :targetId AND l.deleted_at IS NULL AND l.status <> 'HIDDEN'
				  AND seller.status <> 'WITHDRAWN' AND seller.withdrawn_at IS NULL
				""", new MapSqlParameterSource("targetId", targetId),
				(rs, rowNum) -> new ReportTarget(rs.getLong("listing_id"), rs.getLong("seller_id"),
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
				SELECT listing_id, seller_id, title FROM listings WHERE listing_id IN (:ids)
				""", new MapSqlParameterSource("ids", ids), rs -> {
			long id = rs.getLong("listing_id");
			targets.put(id, new ReportTarget(id, rs.getLong("seller_id"), rs.getString("title")));
		});
		return Map.copyOf(targets);
	}

	private static Set<Long> ids(Collection<Long> values) {
		return values == null ? Set.of() : values.stream()
				.filter(id -> id != null && id > 0).collect(Collectors.toSet());
	}

}
