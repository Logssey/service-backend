package com.reused.me.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.paging.IdPage;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.service.ListingImageService;
import com.reused.listing.query.CursorPageResponse;
import com.reused.listing.query.ListingSummaryResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Service
@Transactional(readOnly = true)
public class MyListingService {
    private static final Set<String> STATUSES = Set.of("ON_SALE", "RESERVED", "COMPLETED", "HIDDEN");
    private final JdbcTemplate jdbc;
    private final ActorGuard actors;
    private final ListingImageService images;

    public MyListingService(JdbcTemplate jdbc, ActorGuard actors, ListingImageService images) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.images = images;
    }

    public CursorPageResponse<MyListingResponse> mine(AuthPrincipal principal, String status,
            String cursor, Integer requestedSize) {
        Long userId = actors.user(principal, false);
        if (status != null && !STATUSES.contains(status)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        int size = IdPage.size(requestedSize);
        String scope = "selling:" + userId + ":" + (status == null ? "all" : status);
        List<ListingRow> rows = find(userId, status, null, ListingCursor.decode(cursor, scope), size + 1);
        boolean hasNext = rows.size() > size;
        List<ListingRow> page = rows.subList(0, Math.min(rows.size(), size));
        Map<Long, String> thumbnails = images.thumbnailsForListings(page.stream().map(ListingRow::listingId).toList());
        List<MyListingResponse> items = page.stream().map(row -> new MyListingResponse(row.listingId(),
                row.title(), row.price(), row.status(), thumbnails.get(row.listingId()), row.wishCount(),
                row.viewCount(), row.pendingTradeCount(), row.createdAt())).toList();
        return new CursorPageResponse<>(items, hasNext ? ListingCursor.encode(scope, page.getLast()) : null, hasNext);
    }

    public CursorPageResponse<ListingSummaryResponse> publicListings(Long sellerId, Long viewerId,
            String cursor, Integer requestedSize) {
        if (sellerId == null || sellerId <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ?
                    AND status <> 'WITHDRAWN' AND withdrawn_at IS NULL)
                """, Boolean.class, sellerId))) throw new BusinessException(ErrorCode.NOT_FOUND);
        int size = IdPage.size(requestedSize);
        String scope = "seller:" + sellerId;
        List<ListingRow> rows = find(sellerId, "ON_SALE", viewerId, ListingCursor.decode(cursor, scope), size + 1);
        boolean hasNext = rows.size() > size;
        List<ListingRow> page = rows.subList(0, Math.min(rows.size(), size));
        Map<Long, String> thumbnails = images.thumbnailsForListings(page.stream().map(ListingRow::listingId).toList());
        List<ListingSummaryResponse> items = page.stream().map(row -> new ListingSummaryResponse(row.listingId(),
                row.title(), row.price(), row.status(), row.itemCondition(), thumbnails.get(row.listingId()),
                row.wishCount(), row.seller(), row.createdAt())).toList();
        return new CursorPageResponse<>(items, hasNext ? ListingCursor.encode(scope, page.getLast()) : null, hasNext);
    }

    private List<ListingRow> find(Long sellerId, String status, Long viewerId, ListingCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.listing_id, l.title, l.price, l.status, l.item_condition, l.wish_count,
                       l.view_count, l.created_at, u.user_id, u.nickname, u.profile_image_url,
                       (SELECT count(*) FROM trades t WHERE t.listing_id = l.listing_id
                        AND t.status = 'REQUESTED') AS pending_count
                FROM listings l JOIN users u ON u.user_id = l.seller_id
                WHERE l.seller_id = ? AND l.deleted_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(sellerId);
        if (status != null) {
            sql.append(" AND l.status = ?");
            args.add(status);
        }
        if (viewerId != null) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = l.seller_id)");
            args.add(viewerId);
        }
        if (cursor != null) {
            sql.append(" AND (l.created_at, l.listing_id) < (?, ?)");
            args.add(Timestamp.from(cursor.createdAt()));
            args.add(cursor.id());
        }
        sql.append(" ORDER BY l.created_at DESC, l.listing_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new ListingRow(rs.getLong("listing_id"),
                rs.getString("title"), rs.getInt("price"), rs.getString("status"),
                rs.getString("item_condition"), rs.getInt("wish_count"), rs.getInt("view_count"),
                rs.getLong("pending_count"), rs.getTimestamp("created_at").toInstant(),
                new UserSummaryResponse(rs.getLong("user_id"), rs.getString("nickname"),
                        rs.getString("profile_image_url"))), args.toArray());
    }

    public record MyListingResponse(Long listingId, String title, int price, String status,
            String thumbnailUrl, int wishCount, int viewCount, long pendingTradeCount, Instant createdAt) {}
    private record ListingRow(Long listingId, String title, int price, String status, String itemCondition,
            int wishCount, int viewCount, long pendingTradeCount, Instant createdAt, UserSummaryResponse seller) {}

    private record ListingCursor(Instant createdAt, long id) {
        static ListingCursor decode(String encoded, String scope) {
            if (encoded == null) return null;
            try {
                if (encoded.length() > 512) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !scope.equals(parts[0])) throw new IllegalArgumentException();
                long id = Long.parseLong(parts[2]);
                if (id <= 0) throw new IllegalArgumentException();
                return new ListingCursor(Instant.parse(parts[1]), id);
            } catch (RuntimeException e) { throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다."); }
        }
        static String encode(String scope, ListingRow row) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (scope + "|" + row.createdAt() + "|" + row.listingId()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
