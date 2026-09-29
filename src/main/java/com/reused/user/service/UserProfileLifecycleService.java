package com.reused.user.service;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.auth.token.RefreshTokenStore;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.repository.ImageRepository;
import com.reused.image.service.ProfileImageService;
import com.reused.notification.service.NotificationService;
import com.reused.user.dto.request.ProfileUpdateRequest;
import com.reused.user.dto.response.MyProfileResponse;
import com.reused.user.entity.UserRole;

@Service
@Transactional
public class UserProfileLifecycleService {
    private final JdbcTemplate jdbc;
    private final ProfileImageService profiles;
    private final ImageRepository images;
    private final UserService users;
    private final NotificationService notifications;
    private final RefreshTokenStore refreshTokens;

    public UserProfileLifecycleService(JdbcTemplate jdbc, ProfileImageService profiles, ImageRepository images,
            UserService users, NotificationService notifications, RefreshTokenStore refreshTokens) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.images = images;
        this.users = users;
        this.notifications = notifications;
        this.refreshTokens = refreshTokens;
    }

    public MyProfileResponse update(AuthPrincipal principal, ProfileUpdateRequest request) {
        Long userId = lockActor(principal, true);
        String nickname = request.getNickname();
        if (nickname != null) {
            nickname = nickname.strip();
            if (nickname.length() < 2 || nickname.length() > 20 || nickname.startsWith("탈퇴회원#")) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "닉네임은 2~20자여야 하며 탈퇴회원 이름은 사용할 수 없습니다.");
            }
            try { jdbc.update("UPDATE users SET nickname = ?, updated_at = now() WHERE user_id = ?", nickname, userId); }
            catch (DataIntegrityViolationException e) { throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.", e); }
        }
        if (request.hasBio()) {
            String bio = request.getBio() == null ? null : request.getBio().strip();
            jdbc.update("UPDATE users SET bio = ?, updated_at = now() WHERE user_id = ?", bio, userId);
        }
        if (request.hasImage()) {
            String objectKey = profiles.replace(userId, request.getImageId());
            jdbc.update("UPDATE users SET profile_image_url = ?, updated_at = now() WHERE user_id = ?", objectKey, userId);
        }
        return users.getMyProfile(userId);
    }

    public void withdraw(AuthPrincipal principal) {
        Long userId = lockActor(principal, false);
        // Trade writers first lock participant users, then listings. Keep the same ordering here.
        jdbc.query("""
                SELECT l.listing_id FROM listings l WHERE l.seller_id = ? OR EXISTS
                (SELECT 1 FROM trades t WHERE t.listing_id = l.listing_id
                 AND (t.seller_id = ? OR t.buyer_id = ?) AND t.status IN ('REQUESTED', 'ACCEPTED'))
                ORDER BY l.listing_id FOR UPDATE
                """, (rs, n) -> rs.getLong(1), userId, userId, userId);
        List<ActiveTrade> trades = jdbc.query("""
                SELECT trade_id, listing_id, seller_id, buyer_id, status FROM trades
                WHERE (seller_id = ? OR buyer_id = ?) AND status IN ('REQUESTED', 'ACCEPTED')
                ORDER BY listing_id, trade_id FOR UPDATE
                """, (rs, n) -> new ActiveTrade(rs.getLong("trade_id"), rs.getLong("listing_id"),
                        rs.getLong("seller_id"), rs.getLong("buyer_id"), rs.getString("status")), userId, userId);
        for (ActiveTrade trade : trades) {
            jdbc.update("""
                    UPDATE trades SET status = 'CANCELED', closed_by = ?, closed_at = now(), version = version + 1
                    WHERE trade_id = ?
                    """, userId, trade.id());
            jdbc.update("""
                    INSERT INTO trade_status_histories (trade_id, before_status, after_status, changed_by, reason)
                    VALUES (?, ?, 'CANCELED', ?, '회원 탈퇴')
                    """, trade.id(), trade.status(), userId);
            if ("ACCEPTED".equals(trade.status())) {
                jdbc.update("""
                        UPDATE listings SET status = 'ON_SALE', updated_at = now()
                        WHERE listing_id = ? AND seller_id <> ? AND status = 'RESERVED' AND deleted_at IS NULL
                        """, trade.listingId(), userId);
            }
            Long recipient = userId.equals(trade.sellerId()) ? trade.buyerId() : trade.sellerId();
            notifications.createFor(recipient, "TRADE_CANCELED", "거래가 취소되었습니다.",
                    "상대방의 회원 탈퇴로 진행 중인 거래가 취소되었습니다.", "TRADE", trade.id());
        }
        images.detachProfile(userId);
        jdbc.update("DELETE FROM user_identities WHERE user_id = ?", userId);
        String nickname = withdrawnNickname(userId);
        jdbc.update("""
                UPDATE users SET nickname = ?, bio = NULL, profile_image_url = NULL, status = 'WITHDRAWN',
                    suspended_until = NULL, withdrawn_at = now(), updated_at = now() WHERE user_id = ?
                """, nickname, userId);
        // Failure aborts SQL changes. Already revoked sessions remain safely revoked if a later commit fails.
        refreshTokens.revokeAll(userId);
    }

    private Long lockActor(AuthPrincipal principal, boolean blockSuspension) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var rows = jdbc.query("SELECT role, status, withdrawn_at FROM users WHERE user_id = ? FOR NO KEY UPDATE",
                (rs, n) -> new Actor(rs.getString("role"), rs.getString("status"), rs.getTimestamp("withdrawn_at") != null),
                principal.userId());
        if (rows.isEmpty() || rows.getFirst().withdrawn() || "WITHDRAWN".equals(rows.getFirst().status())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Actor actor = rows.getFirst();
        if (principal.role() != UserRole.USER || !"USER".equals(actor.role())) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (blockSuspension && "SUSPENDED".equals(actor.status())) throw new BusinessException(ErrorCode.USER_SUSPENDED);
        return principal.userId();
    }

    private String withdrawnNickname(Long userId) {
        String name = "탈퇴회원#" + userId;
        // Legacy users could have claimed a reserved nickname before this feature existed.
        if (name.length() > 20 || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE nickname = ? AND user_id <> ?)", Boolean.class, name, userId))) {
            do {
                name = "탈퇴회원#" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
            } while (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE nickname = ?)", Boolean.class, name)));
        }
        return name;
    }

    private record Actor(String role, String status, boolean withdrawn) {}
    private record ActiveTrade(Long id, Long listingId, Long sellerId, Long buyerId, String status) {}
}
