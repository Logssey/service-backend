package com.reused.chat;

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

/** 메시지 신고는 채팅방 당사자에게만 허용한다. 관리자 요약은 삭제된 메시지도 조회한다. */
@Component
public class MessageReportTargetResolver implements ReportTargetResolver {

	private final NamedParameterJdbcTemplate jdbc;

	public MessageReportTargetResolver(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReportTargetType type() {
		return ReportTargetType.MESSAGE;
	}

	@Override
	public Optional<ReportTarget> resolve(long targetId, long reporterId) {
		return jdbc.query("""
				SELECT m.message_id, m.sender_id, m.content
				FROM messages m JOIN chat_rooms room ON room.chat_room_id = m.chat_room_id
				WHERE m.message_id = :targetId AND m.deleted_at IS NULL
				  AND (room.seller_id = :reporterId OR room.buyer_id = :reporterId)
				""", new MapSqlParameterSource("targetId", targetId).addValue("reporterId", reporterId),
				(rs, rowNum) -> new ReportTarget(rs.getLong("message_id"), rs.getLong("sender_id"),
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
				SELECT message_id, sender_id, content FROM messages WHERE message_id IN (:ids)
				""", new MapSqlParameterSource("ids", ids), rs -> {
			long id = rs.getLong("message_id");
			targets.put(id, new ReportTarget(id, rs.getLong("sender_id"), rs.getString("content")));
		});
		return Map.copyOf(targets);
	}

	private static Set<Long> ids(Collection<Long> values) {
		return values == null ? Set.of() : values.stream()
				.filter(id -> id != null && id > 0).collect(Collectors.toSet());
	}

}
