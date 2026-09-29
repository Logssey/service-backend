package com.reused.block;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.reused.common.error.*;
import com.reused.common.paging.IdPage;
import com.reused.common.security.*;
import com.reused.listing.query.CursorPageResponse;
import com.reused.user.dto.response.UserSummaryResponse;

@Service
@Transactional
public class BlockService {
    private final JdbcTemplate jdbc;
    private final ActorGuard actors;
    private final MarketLocks locks;
    public BlockService(JdbcTemplate jdbc, ActorGuard actors, MarketLocks locks) { this.jdbc = jdbc; this.actors = actors; this.locks = locks; }
    public BlockCreateResponse create(AuthPrincipal p, Long targetId) {
        locks.users(p.userId(), targetId);
        locks.blockPair(p.userId(), targetId);
        Long actor = actors.user(p, false);
        if (actor.equals(targetId)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE user_id = ? AND withdrawn_at IS NULL AND status <> 'WITHDRAWN')", Boolean.class, targetId)))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        // Shared pair lock also serializes message sends against a new block.
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING", actor, targetId);
        Long id = jdbc.queryForObject("SELECT block_id FROM blocks WHERE blocker_id = ? AND blocked_id = ?", Long.class, actor, targetId);
        return new BlockCreateResponse(id, true);
    }
    public void delete(AuthPrincipal p, Long targetId) {
        locks.users(p.userId(), targetId);
        locks.blockPair(p.userId(), targetId);
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", actors.user(p, false), targetId);
    }
    @Transactional(readOnly=true)
    public boolean eitherDirection(Long first, Long second) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM blocks WHERE
                (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, first, second, second, first));
    }
    @Transactional(readOnly=true)
    public CursorPageResponse<BlockResponse> list(AuthPrincipal p, String cursor, Integer requestedSize) {
        Long actor = actors.user(p, false);
        int size = IdPage.size(requestedSize);
        String scope = "blocks:" + actor;
        long before = IdPage.before(cursor, scope);
        List<BlockResponse> rows = jdbc.query("""
                SELECT b.*, u.nickname, u.profile_image_url FROM blocks b
                JOIN users u ON u.user_id = b.blocked_id
                WHERE b.blocker_id = ? AND b.block_id < ? ORDER BY b.block_id DESC LIMIT ?
                """, (rs,n)->new BlockResponse(rs.getLong("block_id"),
                new UserSummaryResponse(rs.getLong("blocked_id"), rs.getString("nickname"), rs.getString("profile_image_url")),
                rs.getTimestamp("created_at").toInstant()), actor, before, size+1);
        return IdPage.of(rows,size,scope,BlockResponse::blockId);
    }
    public record BlockCreateResponse(Long blockId, boolean blocked) {}
    public record BlockResponse(Long blockId, UserSummaryResponse blockedUser, Instant createdAt) {}
}
