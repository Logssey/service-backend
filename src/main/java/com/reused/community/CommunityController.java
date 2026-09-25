package com.reused.community;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.common.security.CurrentUserProvider;
import com.reused.listing.query.CursorPageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/community/posts")
public class CommunityController {
    private final CommunityService service;
    private final CurrentUserProvider currentUsers;

    public CommunityController(CommunityService service, CurrentUserProvider currentUsers) {
        this.service = service;
        this.currentUsers = currentUsers;
    }

    @GetMapping
    public CursorPageResponse<CommunityResponses.PostSummary> posts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return service.posts(category, cursor, size, viewerId());
    }

    @GetMapping("/{postId}")
    public CommunityResponses.PostDetail post(@PathVariable Long postId) {
        return service.post(postId, viewerId());
    }

    @GetMapping("/{postId}/comments")
    public CursorPageResponse<CommunityResponses.Comment> comments(@PathVariable Long postId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return service.comments(postId, cursor, size, viewerId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityResponses.PostCreated create(@AuthUser AuthPrincipal principal,
            @Valid @RequestBody PostWriteRequest request) {
        return service.create(principal, request);
    }

    @PatchMapping("/{postId}")
    public CommunityResponses.PostDetail update(@AuthUser AuthPrincipal principal, @PathVariable Long postId,
            @Valid @RequestBody PostWriteRequest request) {
        return service.update(principal, postId, request);
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthUser AuthPrincipal principal, @PathVariable Long postId) {
        service.delete(principal, postId);
    }

    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommunityResponses.Comment createComment(@AuthUser AuthPrincipal principal, @PathVariable Long postId,
            @Valid @RequestBody CommentWriteRequest request) {
        return service.createComment(principal, postId, request);
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@AuthUser AuthPrincipal principal, @PathVariable Long postId,
            @PathVariable Long commentId) {
        service.deleteComment(principal, postId, commentId);
    }

    private Long viewerId() {
        return currentUsers.current().map(AuthPrincipal::userId).orElse(null);
    }

    public record PostWriteRequest(String category, String title, String content) {}
    public record CommentWriteRequest(String content) {}
}
