package com.reused.review.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
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
import com.reused.notification.service.NotificationService;
import com.reused.review.dto.ReviewCreateRequest;
import com.reused.review.dto.ReviewResponse;
import com.reused.review.dto.SellerProfileResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Service
@Transactional(readOnly = true)
public class ReviewService {

    private final JdbcTemplate jdbc;
    private final ActorGuard actors;
    private final NotificationService notifications;

    public ReviewService(JdbcTemplate jdbc, ActorGuard actors, NotificationService notifications) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.notifications = notifications;
    }

    @Transactional
    public ReviewCreateResponse create(AuthPrincipal principal, ReviewCreateRequest request) {
        Long reviewerId = actors.user(principal, true);
        List<TradeParties> trades = jdbc.query(
                "SELECT seller_id, buyer_id, status FROM trades WHERE trade_id = ?",
                (rs, n) -> new TradeParties(rs.getLong("seller_id"), rs.getLong("buyer_id"),
                        rs.getString("status")), request.tradeId());
        if (trades.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        TradeParties trade = trades.getFirst();
        if (!reviewerId.equals(trade.sellerId()) && !reviewerId.equals(trade.buyerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!"COMPLETED".equals(trade.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "완료된 거래에만 후기를 작성할 수 있습니다.");
        }
        Long revieweeId = reviewerId.equals(trade.sellerId()) ? trade.buyerId() : trade.sellerId();
        String content = request.content() == null ? null : request.content().strip();
        if (content != null && content.isEmpty()) content = null;
        // The unique constraint arbitrates concurrent submissions, including previously hidden reviews.
        List<Long> ids = jdbc.query("""
                INSERT INTO reviews (trade_id, reviewer_id, reviewee_id, rating, content)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (trade_id, reviewer_id) DO NOTHING RETURNING review_id
                """, (rs, n) -> rs.getLong(1), request.tradeId(), reviewerId, revieweeId,
                request.rating(), content);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "이미 작성한 후기입니다.");
        Long reviewId = ids.getFirst();
        notifications.createFor(revieweeId, "REVIEW_RECEIVED", "새 거래 후기가 도착했습니다.",
                "완료한 거래의 후기를 확인해 주세요.", "REVIEW", reviewId);
        return new ReviewCreateResponse(reviewId);
    }

    public CursorPageResponse<ReviewResponse> mine(AuthPrincipal principal, String cursor, Integer size) {
        return received(actors.user(principal, false), cursor, size);
    }

    public CursorPageResponse<ReviewResponse> received(Long userId, String cursor, Integer requestedSize) {
        requireVisibleUser(userId);
        int size = IdPage.size(requestedSize);
        ReviewCursor boundary = ReviewCursor.decode(cursor, userId);
        String sql = """
                SELECT r.review_id, r.rating, r.content, r.created_at, u.user_id, u.nickname,
                       u.profile_image_url, (u.status = 'WITHDRAWN' OR u.withdrawn_at IS NOT NULL) AS withdrawn
                FROM reviews r JOIN users u ON u.user_id = r.reviewer_id
                WHERE r.reviewee_id = ? AND r.deleted_at IS NULL
                """;
        Object[] args;
        if (boundary == null) args = new Object[] { userId, size + 1 };
        else {
            sql += " AND (r.created_at, r.review_id) < (?, ?)";
            args = new Object[] { userId, Timestamp.from(boundary.createdAt()), boundary.id(), size + 1 };
        }
        sql += " ORDER BY r.created_at DESC, r.review_id DESC LIMIT ?";
        List<ReviewResponse> rows = jdbc.query(sql, (rs, n) -> {
            boolean withdrawn = rs.getBoolean("withdrawn");
            return new ReviewResponse(rs.getLong("review_id"), new UserSummaryResponse(
                    withdrawn ? null : rs.getLong("user_id"),
                    withdrawn ? "탈퇴회원" : rs.getString("nickname"),
                    withdrawn ? null : rs.getString("profile_image_url")), rs.getInt("rating"),
                    rs.getString("content"), rs.getTimestamp("created_at").toInstant());
        }, args);
        boolean hasNext = rows.size() > size;
        List<ReviewResponse> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new CursorPageResponse<>(items,
                hasNext ? ReviewCursor.encode(userId, items.getLast()) : null, hasNext);
    }

    public SellerProfileResponse profile(Long userId) {
        requireVisibleUser(userId);
        return jdbc.queryForObject("""
                SELECT u.user_id, u.nickname, u.profile_image_url, u.bio, u.created_at,
                    (SELECT count(*) FROM trades t
                     WHERE t.seller_id = u.user_id AND t.status = 'COMPLETED') AS completed_count,
                    (SELECT round(avg(r.rating), 1) FROM reviews r
                     WHERE r.reviewee_id = u.user_id AND r.deleted_at IS NULL) AS average_rating,
                    (SELECT count(*) FROM reviews r
                     WHERE r.reviewee_id = u.user_id AND r.deleted_at IS NULL) AS review_count,
                    (SELECT count(*) >= 3 FROM reports r
                     WHERE r.status = 'RESOLVED' AND r.handled_at >= now() - interval '90 days'
                     AND ((r.target_type = 'USER' AND r.target_id = u.user_id)
                       OR (r.target_type = 'LISTING' AND EXISTS
                           (SELECT 1 FROM listings l WHERE l.listing_id = r.target_id AND l.seller_id = u.user_id))
                       OR (r.target_type = 'MESSAGE' AND EXISTS
                           (SELECT 1 FROM messages m WHERE m.message_id = r.target_id AND m.sender_id = u.user_id))
                       OR (r.target_type = 'COMMUNITY_POST' AND EXISTS
                           (SELECT 1 FROM community_posts p WHERE p.post_id = r.target_id AND p.author_id = u.user_id))
                       OR (r.target_type = 'COMMUNITY_COMMENT' AND EXISTS
                           (SELECT 1 FROM community_comments c WHERE c.comment_id = r.target_id AND c.author_id = u.user_id))))
                      AS report_flag
                FROM users u WHERE u.user_id = ?
                """, (rs, n) -> {
                    var average = rs.getBigDecimal("average_rating");
                    return new SellerProfileResponse(rs.getLong("user_id"), rs.getString("nickname"),
                            rs.getString("profile_image_url"), rs.getString("bio"), rs.getLong("completed_count"),
                            average == null ? null : average.doubleValue(), rs.getLong("review_count"),
                            rs.getTimestamp("created_at").toInstant(), rs.getBoolean("report_flag"));
                }, userId);
    }

    private void requireVisibleUser(Long userId) {
        if (userId == null || userId <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ?
                    AND status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
                """, Boolean.class, userId))) throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    public record ReviewCreateResponse(Long reviewId) {}
    private record TradeParties(Long sellerId, Long buyerId, String status) {}

    private record ReviewCursor(Instant createdAt, long id) {
        static ReviewCursor decode(String cursor, Long userId) {
            if (cursor == null) return null;
            try {
                if (cursor.length() > 512) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 4 || !parts[0].equals("reviews") || !parts[1].equals(userId.toString())) {
                    throw new IllegalArgumentException();
                }
                long id = Long.parseLong(parts[3]);
                if (id <= 0) throw new IllegalArgumentException();
                return new ReviewCursor(Instant.parse(parts[2]), id);
            } catch (RuntimeException e) { throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다."); }
        }
        static String encode(Long userId, ReviewResponse review) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("reviews|" + userId + "|" + review.createdAt() + "|" + review.reviewId()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
