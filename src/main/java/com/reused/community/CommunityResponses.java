package com.reused.community;

import java.time.Instant;

public final class CommunityResponses {
    private CommunityResponses() {}

    public record Author(Long userId, String nickname) {}
    public record PostCreated(long postId) {}
    public record PostSummary(long postId, String category, String title, String excerpt, Author author,
                              int commentCount, int viewCount, boolean isMine, Instant createdAt, Instant updatedAt) {}
    public record PostDetail(long postId, String category, String title, String content, Author author,
                             int commentCount, int viewCount, boolean isMine, Instant createdAt, Instant updatedAt) {}
    public record Comment(long commentId, String content, Author author, boolean isMine, Instant createdAt) {}
}
