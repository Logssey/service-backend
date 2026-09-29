package com.reused.community;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.paging.IdPage;
import com.reused.common.security.ActorGuard;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.MarketLocks;
import com.reused.listing.query.CursorPageResponse;

@Service
@Transactional(readOnly = true)
public class CommunityService {
    private static final Set<String> CATEGORIES = Set.of("GENERAL", "QUESTION", "TIP", "SHARE");
    private static final String PUBLIC_POST = "p.deleted_at IS NULL AND p.status = 'PUBLISHED'";
    private static final String PUBLIC_COMMENT = "c.deleted_at IS NULL AND c.status = 'PUBLISHED'";
    private static final String POST_COLUMNS = """
            SELECT p.post_id, p.author_id, p.category, p.title, p.content, p.comment_count,
                   p.view_count, p.created_at, p.updated_at, u.nickname,
                   (u.status = 'WITHDRAWN' OR u.withdrawn_at IS NOT NULL) AS withdrawn
            FROM community_posts p JOIN users u ON u.user_id = p.author_id
            """;
    private static final String COMMENT_COLUMNS = """
            SELECT c.comment_id, c.author_id, c.content, c.created_at, u.nickname,
                   (u.status = 'WITHDRAWN' OR u.withdrawn_at IS NOT NULL) AS withdrawn
            FROM community_comments c JOIN users u ON u.user_id = c.author_id
            """;

    private final JdbcTemplate jdbc;
    private final ActorGuard actors;
    private final MarketLocks locks;

    public CommunityService(JdbcTemplate jdbc, ActorGuard actors, MarketLocks locks) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.locks = locks;
    }

    public CursorPageResponse<CommunityResponses.PostSummary> posts(String category, String cursor,
            Integer requestedSize, Long viewerId) {
        if (category != null) category = validCategory(category);
        int size = IdPage.size(requestedSize);
        String scope = "posts:" + (category == null ? "ALL" : category);
        CommunityCursor boundary = CommunityCursor.decode(cursor, scope);
        StringBuilder sql = new StringBuilder(POST_COLUMNS).append(" WHERE ").append(PUBLIC_POST);
        List<Object> params = new ArrayList<>();
        if (category != null) {
            sql.append(" AND p.category = ?");
            params.add(category);
        }
        if (viewerId != null) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = p.author_id)");
            params.add(viewerId);
        }
        if (boundary != null) {
            sql.append(" AND (p.created_at, p.post_id) < (?, ?)");
            params.add(Timestamp.from(boundary.createdAt()));
            params.add(boundary.id());
        }
        sql.append(" ORDER BY p.created_at DESC, p.post_id DESC LIMIT ?");
        params.add(size + 1);
        List<PostRow> rows = jdbc.query(sql.toString(), (rs, n) -> postRow(rs), params.toArray());
        boolean hasNext = rows.size() > size;
        List<PostRow> page = rows.subList(0, Math.min(size, rows.size()));
        List<CommunityResponses.PostSummary> items = page.stream().map(row -> summary(row, viewerId)).toList();
        PostRow last = hasNext ? page.getLast() : null;
        return new CursorPageResponse<>(items,
                last == null ? null : new CommunityCursor(last.createdAt(), last.postId()).encode(scope), hasNext);
    }

    @Transactional
    public CommunityResponses.PostDetail post(Long postId, Long viewerId) {
        requireId(postId);
        int updated = jdbc.update("""
                UPDATE community_posts SET view_count = view_count + 1
                WHERE post_id = ? AND deleted_at IS NULL AND status = 'PUBLISHED'
                """, postId);
        if (updated == 0) throw new BusinessException(ErrorCode.NOT_FOUND);
        return detail(publicPost(postId), viewerId);
    }

    public CursorPageResponse<CommunityResponses.Comment> comments(Long postId, String cursor,
            Integer requestedSize, Long viewerId) {
        requireId(postId);
        requirePublishedPost(postId);
        int size = IdPage.size(requestedSize);
        String scope = "comments:" + postId;
        CommunityCursor boundary = CommunityCursor.decode(cursor, scope);
        StringBuilder sql = new StringBuilder(COMMENT_COLUMNS)
                .append(" WHERE c.post_id = ? AND ").append(PUBLIC_COMMENT)
                .append(" AND EXISTS (SELECT 1 FROM community_posts p WHERE p.post_id = c.post_id AND ")
                .append(PUBLIC_POST).append(")");
        List<Object> params = new ArrayList<>();
        params.add(postId);
        if (viewerId != null) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = ? AND b.blocked_id = c.author_id)");
            params.add(viewerId);
        }
        if (boundary != null) {
            sql.append(" AND (c.created_at, c.comment_id) < (?, ?)");
            params.add(Timestamp.from(boundary.createdAt()));
            params.add(boundary.id());
        }
        sql.append(" ORDER BY c.created_at DESC, c.comment_id DESC LIMIT ?");
        params.add(size + 1);
        List<CommentRow> rows = jdbc.query(sql.toString(), (rs, n) -> commentRow(rs), params.toArray());
        boolean hasNext = rows.size() > size;
        List<CommentRow> page = rows.subList(0, Math.min(size, rows.size()));
        List<CommunityResponses.Comment> items = page.stream().map(row -> comment(row, viewerId)).toList();
        CommentRow last = hasNext ? page.getLast() : null;
        return new CursorPageResponse<>(items,
                last == null ? null : new CommunityCursor(last.createdAt(), last.commentId()).encode(scope), hasNext);
    }

    @Transactional
    public CommunityResponses.PostCreated create(AuthPrincipal principal, CommunityController.PostWriteRequest request) {
        long actorId = activeUser(principal);
        if (request == null || request.category() == null || request.title() == null || request.content() == null)
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        String category = validCategory(request.category());
        String title = trimmed(request.title(), 2, 100);
        String content = trimmed(request.content(), 10, 3000);
        Long id = jdbc.queryForObject("""
                INSERT INTO community_posts (author_id, category, title, content)
                VALUES (?, ?, ?, ?) RETURNING post_id
                """, Long.class, actorId, category, title, content);
        return new CommunityResponses.PostCreated(id);
    }

    @Transactional
    public CommunityResponses.PostDetail update(AuthPrincipal principal, Long postId,
            CommunityController.PostWriteRequest request) {
        long actorId = activeUser(principal);
        requireId(postId);
        if (request == null || (request.category() == null && request.title() == null && request.content() == null))
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        String category = request.category() == null ? null : validCategory(request.category());
        String title = request.title() == null ? null : trimmed(request.title(), 2, 100);
        String content = request.content() == null ? null : trimmed(request.content(), 10, 3000);
        PostRow existing = lockedPost(postId);
        requireOwner(existing.authorId(), actorId);
        jdbc.update("""
                UPDATE community_posts SET category = COALESCE(?, category), title = COALESCE(?, title),
                    content = COALESCE(?, content), updated_at = clock_timestamp()
                WHERE post_id = ?
                """, category, title, content, postId);
        return detail(publicPost(postId), actorId);
    }

    @Transactional
    public void delete(AuthPrincipal principal, Long postId) {
        long actorId = activeUser(principal);
        requireId(postId);
        PostRow existing = lockedPost(postId);
        requireOwner(existing.authorId(), actorId);
        jdbc.update("UPDATE community_posts SET deleted_at = clock_timestamp(), deleted_by = ? WHERE post_id = ?",
                actorId, postId);
    }

    @Transactional
    public CommunityResponses.Comment createComment(AuthPrincipal principal, Long postId,
            CommunityController.CommentWriteRequest request) {
        long actorId = activeUser(principal);
        requireId(postId);
        if (request == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        String content = trimmed(request.content(), 1, 500);
        lockedPost(postId); // Serializes post deletion, moderation and all comment counter updates.
        Long id = jdbc.queryForObject("""
                INSERT INTO community_comments (post_id, author_id, content)
                VALUES (?, ?, ?) RETURNING comment_id
                """, Long.class, postId, actorId, content);
        jdbc.update("UPDATE community_posts SET comment_count = comment_count + 1 WHERE post_id = ?", postId);
        return comment(publicComment(postId, id), actorId);
    }

    @Transactional
    public void deleteComment(AuthPrincipal principal, Long postId, Long commentId) {
        long actorId = activeUser(principal);
        requireId(postId);
        requireId(commentId);
        lockedPost(postId);
        List<Long> authors = jdbc.query("""
                SELECT author_id FROM community_comments
                WHERE comment_id = ? AND post_id = ? AND deleted_at IS NULL AND status = 'PUBLISHED'
                FOR UPDATE
                """, (rs, n) -> rs.getLong(1), commentId, postId);
        if (authors.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        requireOwner(authors.getFirst(), actorId);
        jdbc.update("UPDATE community_comments SET deleted_at = clock_timestamp(), deleted_by = ? WHERE comment_id = ?",
                actorId, commentId);
        jdbc.update("UPDATE community_posts SET comment_count = comment_count - 1 WHERE post_id = ?", postId);
    }

    private long activeUser(AuthPrincipal principal) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        locks.users(principal.userId());
        return actors.user(principal, true);
    }

    private PostRow publicPost(long postId) {
        List<PostRow> rows = jdbc.query(POST_COLUMNS + " WHERE p.post_id = ? AND " + PUBLIC_POST,
                (rs, n) -> postRow(rs), postId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private void requirePublishedPost(long postId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM community_posts WHERE post_id = ? AND deleted_at IS NULL AND status = 'PUBLISHED'
                """, Integer.class, postId);
        if (count == null || count == 0) throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    private PostRow lockedPost(long postId) {
        List<PostRow> rows = jdbc.query(POST_COLUMNS + " WHERE p.post_id = ? AND " + PUBLIC_POST + " FOR UPDATE OF p",
                (rs, n) -> postRow(rs), postId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private CommentRow publicComment(long postId, long commentId) {
        List<CommentRow> rows = jdbc.query(COMMENT_COLUMNS + " WHERE c.post_id = ? AND c.comment_id = ? AND "
                + PUBLIC_COMMENT, (rs, n) -> commentRow(rs), postId, commentId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private static PostRow postRow(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new PostRow(rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("category"),
                rs.getString("title"), rs.getString("content"), rs.getInt("comment_count"),
                rs.getInt("view_count"), rs.getTimestamp("created_at").toInstant(),
                updated == null ? null : updated.toInstant(), rs.getString("nickname"), rs.getBoolean("withdrawn"));
    }

    private static CommentRow commentRow(ResultSet rs) throws SQLException {
        return new CommentRow(rs.getLong("comment_id"), rs.getLong("author_id"), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("nickname"), rs.getBoolean("withdrawn"));
    }

    private static CommunityResponses.PostSummary summary(PostRow row, Long viewerId) {
        String excerpt = row.content().replaceAll("\\s+", " ");
        if (excerpt.length() > 160) excerpt = excerpt.substring(0, 160);
        return new CommunityResponses.PostSummary(row.postId(), row.category(), row.title(), excerpt,
                author(row.authorId(), row.nickname(), row.withdrawn()), row.commentCount(), row.viewCount(),
                isMine(row.authorId(), row.withdrawn(), viewerId), row.createdAt(), row.updatedAt());
    }

    private static CommunityResponses.PostDetail detail(PostRow row, Long viewerId) {
        return new CommunityResponses.PostDetail(row.postId(), row.category(), row.title(), row.content(),
                author(row.authorId(), row.nickname(), row.withdrawn()), row.commentCount(), row.viewCount(),
                isMine(row.authorId(), row.withdrawn(), viewerId), row.createdAt(), row.updatedAt());
    }

    private static CommunityResponses.Comment comment(CommentRow row, Long viewerId) {
        return new CommunityResponses.Comment(row.commentId(), row.content(),
                author(row.authorId(), row.nickname(), row.withdrawn()),
                isMine(row.authorId(), row.withdrawn(), viewerId), row.createdAt());
    }

    private static CommunityResponses.Author author(long id, String nickname, boolean withdrawn) {
        return withdrawn ? new CommunityResponses.Author(null, "탈퇴한 사용자")
                : new CommunityResponses.Author(id, nickname);
    }

    private static boolean isMine(long authorId, boolean withdrawn, Long viewerId) {
        return !withdrawn && viewerId != null && authorId == viewerId;
    }

    private static String validCategory(String category) {
        if (category == null || !CATEGORIES.contains(category)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return category;
    }

    private static String trimmed(String value, int min, int max) {
        if (value == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        String trimmed = value.strip();
        if (trimmed.length() < min || trimmed.length() > max) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return trimmed;
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
    }

    private static void requireOwner(long authorId, long actorId) {
        if (authorId != actorId) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private record PostRow(long postId, long authorId, String category, String title, String content,
                           int commentCount, int viewCount, Instant createdAt, Instant updatedAt,
                           String nickname, boolean withdrawn) {}
    private record CommentRow(long commentId, long authorId, String content, Instant createdAt,
                              String nickname, boolean withdrawn) {}
}
