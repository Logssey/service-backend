package com.reused.notification.service;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.paging.IdPage;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.listing.query.CursorPageResponse;

@Service
@Transactional
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ActorGuard actors;
    public NotificationService(JdbcTemplate jdbc, ActorGuard actors) { this.jdbc = jdbc; this.actors = actors; }

    /** Call within the same transaction as the source action; settings are enforced before inserting. */
    public void createFor(Long userId, String type, String title, String body, String targetType, Long targetId) {
        String setting = type.startsWith("TRADE_") ? "trade_enabled" : switch (type) {
            case "CHAT_RECEIVED" -> "chat_enabled";
            case "REVIEW_RECEIVED" -> "review_enabled";
            case "REPORT_RESOLVED" -> "report_enabled";
            case "NOTICE_PUBLISHED" -> "notice_enabled";
            default -> throw new IllegalArgumentException("Unknown notification type");
        };
        jdbc.update("""
                INSERT INTO notifications (user_id, type, title, body, target_type, target_id)
                SELECT u.user_id, ?, ?, ?, ?, ? FROM users u
                LEFT JOIN notification_settings s ON s.user_id = u.user_id
                WHERE u.user_id = ? AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL
                AND COALESCE(s.""" + setting + ", true)", type, title, body, targetType, targetId, userId);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<NotificationResponse> list(AuthPrincipal p, Boolean unreadOnly, String cursor, Integer requestedSize) {
        Long userId = actors.user(p, false);
        int size = IdPage.size(requestedSize);
        String scope = "notifications:" + userId + ":" + Boolean.TRUE.equals(unreadOnly);
        long before = IdPage.before(cursor, scope);
        List<NotificationResponse> rows = jdbc.query("""
                SELECT * FROM notifications WHERE user_id = ? AND notification_id < ?
                """ + (Boolean.TRUE.equals(unreadOnly) ? " AND read_at IS NULL" : "") +
                " ORDER BY notification_id DESC LIMIT ?", (rs, n) -> new NotificationResponse(
                    rs.getLong("notification_id"), rs.getString("type"), rs.getString("title"), rs.getString("body"),
                    rs.getString("target_type"), (Long) rs.getObject("target_id"),
                    rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant()), userId, before, size + 1);
        return IdPage.of(rows, size, scope, NotificationResponse::notificationId);
    }

    @Transactional(readOnly = true)
    public long unread(AuthPrincipal p) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, actors.user(p, false));
    }

    public void read(AuthPrincipal p, Long id) {
        Long userId = actors.user(p, false);
        List<Long> owners = jdbc.query("SELECT user_id FROM notifications WHERE notification_id = ?",
                (rs, n) -> rs.getLong(1), id);
        if (owners.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!owners.getFirst().equals(userId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        jdbc.update("UPDATE notifications SET read_at = now() WHERE notification_id = ? AND read_at IS NULL", id);
    }

    public int readAll(AuthPrincipal p) {
        return jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL",
                actors.user(p, false));
    }

    public NotificationSettingsResponse settings(AuthPrincipal p) {
        Long userId = actors.user(p, false);
        return settingsFor(userId);
    }

    public NotificationSettingsResponse updateSettings(AuthPrincipal p, NotificationSettingsUpdateRequest request) {
        Long userId = actors.user(p, false);
        jdbc.update("INSERT INTO notification_settings (user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                UPDATE notification_settings SET trade_enabled = COALESCE(?, trade_enabled),
                chat_enabled = COALESCE(?, chat_enabled), review_enabled = COALESCE(?, review_enabled),
                report_enabled = COALESCE(?, report_enabled), notice_enabled = COALESCE(?, notice_enabled),
                updated_at = now() WHERE user_id = ?
                """, request.tradeEnabled(), request.chatEnabled(), request.reviewEnabled(),
                request.reportEnabled(), request.noticeEnabled(), userId);
        return settingsFor(userId);
    }

    private NotificationSettingsResponse settingsFor(Long id) {
        var rows = jdbc.query("SELECT * FROM notification_settings WHERE user_id = ?", (rs, n) ->
                new NotificationSettingsResponse(rs.getBoolean("trade_enabled"), rs.getBoolean("chat_enabled"),
                        rs.getBoolean("review_enabled"), rs.getBoolean("report_enabled"), rs.getBoolean("notice_enabled")), id);
        return rows.isEmpty() ? new NotificationSettingsResponse(true, true, true, true, true) : rows.getFirst();
    }
    public record NotificationResponse(Long notificationId, String type, String title, String body,
            String targetType, Long targetId, Instant readAt, Instant createdAt) {}
    public record NotificationSettingsResponse(boolean tradeEnabled, boolean chatEnabled, boolean reviewEnabled,
            boolean reportEnabled, boolean noticeEnabled) {}
    public record NotificationSettingsUpdateRequest(Boolean tradeEnabled, Boolean chatEnabled, Boolean reviewEnabled,
            Boolean reportEnabled, Boolean noticeEnabled) {}
}
