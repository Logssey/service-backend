package com.reused.report.service;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.reused.admin.repository.AdminRepository;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.report.api.ContentModerationPort;

/** Applies report actions in the same transaction as the report and its audit record. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcContentModeration implements ContentModerationPort {

	private final JdbcTemplate jdbc;
	private final AdminRepository adminRepository;

	public JdbcContentModeration(JdbcTemplate jdbc, AdminRepository adminRepository) {
		this.jdbc = jdbc;
		this.adminRepository = adminRepository;
	}

	@Override
	public ListingHideResult hideListing(long listingId, long adminId, String reason) {
		ListingState listing = listingForUpdate(listingId);
		if (listing.deleted() || "HIDDEN".equals(listing.status())) {
			return new ListingHideResult(false, null);
		}
		adminRepository.updateListingStatus(listingId, "HIDDEN", Instant.now());
		return new ListingHideResult(true, listing.status());
	}

	@Override
	public boolean deleteListing(long listingId, long adminId, String reason) {
		ListingState listing = listingForUpdate(listingId);
		if (listing.deleted()) {
			return false;
		}
		if (adminRepository.hasActiveTrade(listingId)) {
			throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 거래가 있는 게시글은 삭제할 수 없습니다.");
		}
		adminRepository.softDeleteListing(listingId, adminId, Instant.now());
		return true;
	}

	@Override
	public boolean hideCommunityPost(long postId, long adminId, String reason) {
		CommunityState post = communityPostForUpdate(postId);
		if (post.deleted() || "HIDDEN".equals(post.status())) {
			return false;
		}
		jdbc.update("UPDATE community_posts SET status = 'HIDDEN', updated_at = clock_timestamp() WHERE post_id = ?",
				postId);
		return true;
	}

	@Override
	public boolean hideCommunityComment(long commentId, long adminId, String reason) {
		// CommunityService locks the parent post before a comment write. Use the same order.
		List<Long> parentIds = jdbc.query("SELECT post_id FROM community_comments WHERE comment_id = ?",
				(rs, rowNum) -> rs.getLong(1), commentId);
		if (parentIds.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		long postId = parentIds.getFirst();
		communityPostForUpdate(postId);
		List<CommunityState> comments = jdbc.query("""
				SELECT status, deleted_at IS NOT NULL AS deleted FROM community_comments
				WHERE comment_id = ? AND post_id = ? FOR UPDATE
				""", (rs, rowNum) -> new CommunityState(rs.getString("status"), rs.getBoolean("deleted")),
				commentId, postId);
		if (comments.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		CommunityState comment = comments.getFirst();
		if (comment.deleted() || "HIDDEN".equals(comment.status())) {
			return false;
		}
		jdbc.update("UPDATE community_comments SET status = 'HIDDEN' WHERE comment_id = ?", commentId);
		jdbc.update("UPDATE community_posts SET comment_count = comment_count - 1 WHERE post_id = ?", postId);
		return true;
	}

	private ListingState listingForUpdate(long listingId) {
		List<ListingState> listings = jdbc.query("""
				SELECT status, deleted_at IS NOT NULL AS deleted FROM listings
				WHERE listing_id = ? FOR UPDATE
				""", (rs, rowNum) -> new ListingState(rs.getString("status"), rs.getBoolean("deleted")), listingId);
		if (listings.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return listings.getFirst();
	}

	private CommunityState communityPostForUpdate(long postId) {
		List<CommunityState> posts = jdbc.query("""
				SELECT status, deleted_at IS NOT NULL AS deleted FROM community_posts
				WHERE post_id = ? FOR UPDATE
				""", (rs, rowNum) -> new CommunityState(rs.getString("status"), rs.getBoolean("deleted")), postId);
		if (posts.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return posts.getFirst();
	}

	private record ListingState(String status, boolean deleted) {
	}

	private record CommunityState(String status, boolean deleted) {
	}

}
