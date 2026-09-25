package com.reused.image.repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ImageRepository {

	private static final RowMapper<ImageRecord> IMAGE_MAPPER = (rs, rowNum) -> new ImageRecord(
			rs.getLong("image_id"), (Long) rs.getObject("listing_id"), rs.getLong("uploader_id"),
			rs.getString("object_key"), rs.getString("thumbnail_key"), rs.getString("content_type"),
			rs.getLong("file_size"), rs.getString("status"), rs.getInt("display_order"),
			rs.getTimestamp("created_at").toInstant());

	private static final String IMAGE_COLUMNS = "image_id, listing_id, uploader_id, object_key, "
			+ "thumbnail_key, content_type, file_size, status, display_order, created_at";

	private final NamedParameterJdbcTemplate jdbc;

	public ImageRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Long insertPending(Long uploaderId, String objectKey, String contentType, long fileSize) {
		return jdbc.queryForObject("""
				INSERT INTO listing_images (uploader_id, object_key, content_type, file_size, status)
				VALUES (:uploaderId, :objectKey, :contentType, :fileSize, 'PENDING')
				RETURNING image_id
				""", new MapSqlParameterSource().addValue("uploaderId", uploaderId)
					.addValue("objectKey", objectKey).addValue("contentType", contentType)
					.addValue("fileSize", fileSize), Long.class);
	}

	public Optional<ImageRecord> findForUpdate(Long imageId) {
		List<ImageRecord> rows = jdbc.query("SELECT " + IMAGE_COLUMNS
				+ " FROM listing_images WHERE image_id = :imageId FOR UPDATE",
				new MapSqlParameterSource("imageId", imageId), IMAGE_MAPPER);
		return rows.stream().findFirst();
	}

	public List<ImageRecord> findAllForUpdate(List<Long> imageIds) {
		if (imageIds.isEmpty()) {
			return List.of();
		}
		return jdbc.query("SELECT " + IMAGE_COLUMNS
				+ " FROM listing_images WHERE image_id IN (:imageIds) ORDER BY image_id FOR UPDATE",
				new MapSqlParameterSource("imageIds", imageIds), IMAGE_MAPPER);
	}

	public void updateStatus(Long imageId, String status) {
		jdbc.update("UPDATE listing_images SET status = :status WHERE image_id = :imageId",
				new MapSqlParameterSource("imageId", imageId).addValue("status", status));
	}

	public void markVerified(Long imageId, String verifiedKey) {
		jdbc.update("""
				UPDATE listing_images SET status = 'VERIFIED', object_key = :verifiedKey
				WHERE image_id = :imageId
				""", new MapSqlParameterSource("imageId", imageId).addValue("verifiedKey", verifiedKey));
	}

	public void delete(Long imageId) {
		jdbc.update("DELETE FROM listing_images WHERE image_id = :imageId",
				new MapSqlParameterSource("imageId", imageId));
	}

	public void lockUploader(Long uploaderId) {
		jdbc.queryForObject("SELECT user_id FROM users WHERE user_id = :uploaderId FOR UPDATE",
				new MapSqlParameterSource("uploaderId", uploaderId), Long.class);
	}

	public int countUnattachedByUploader(Long uploaderId) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*)
				FROM listing_images
				WHERE uploader_id = :uploaderId AND listing_id IS NULL
				""", new MapSqlParameterSource("uploaderId", uploaderId), Integer.class);
		return count == null ? 0 : count;
	}

	/** Claims a small orphan batch so it can no longer be attached while object deletion is retried. */
	@Transactional
	public List<ImageRecord> claimExpiredOrphans(Instant cutoff, int limit) {
		return jdbc.query("""
				WITH candidates AS (
				    SELECT image_id
				    FROM listing_images
				    WHERE listing_id IS NULL AND created_at < :cutoff
				    ORDER BY created_at, image_id
				    FOR UPDATE SKIP LOCKED
				    LIMIT :limit
				)
				UPDATE listing_images image
				SET status = 'REJECTED'
				FROM candidates
				WHERE image.image_id = candidates.image_id
				RETURNING image.image_id, image.listing_id, image.uploader_id, image.object_key,
				          image.thumbnail_key, image.content_type, image.file_size, image.status,
				          image.display_order, image.created_at
				""", new MapSqlParameterSource()
					.addValue("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC),
							Types.TIMESTAMP_WITH_TIMEZONE)
					.addValue("limit", limit), IMAGE_MAPPER);
	}

	@Transactional
	public boolean deleteClaimedOrphan(Long imageId) {
		return jdbc.update("""
				DELETE FROM listing_images
				WHERE image_id = :imageId AND listing_id IS NULL AND status = 'REJECTED'
				""", new MapSqlParameterSource("imageId", imageId)) == 1;
	}

	public void detachAllFromListing(Long listingId) {
		jdbc.update("UPDATE listing_images SET listing_id = NULL, display_order = 0 WHERE listing_id = :listingId",
				new MapSqlParameterSource("listingId", listingId));
	}

	public void attach(Long imageId, Long listingId, int displayOrder) {
		jdbc.update("""
				UPDATE listing_images SET listing_id = :listingId, display_order = :displayOrder
				WHERE image_id = :imageId
				""", new MapSqlParameterSource("listingId", listingId)
					.addValue("imageId", imageId).addValue("displayOrder", displayOrder));
	}

	public List<ImageRecord> findByListing(Long listingId) {
		return jdbc.query("SELECT " + IMAGE_COLUMNS + " FROM listing_images "
				+ "WHERE listing_id = :listingId AND status = 'VERIFIED' ORDER BY display_order, image_id",
				new MapSqlParameterSource("listingId", listingId), IMAGE_MAPPER);
	}

	public Map<Long, String> thumbnailKeys(List<Long> listingIds) {
		if (listingIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.query("""
				SELECT DISTINCT ON (listing_id) listing_id, COALESCE(thumbnail_key, object_key) AS image_key
				FROM listing_images
				WHERE listing_id IN (:listingIds) AND status = 'VERIFIED'
				ORDER BY listing_id, display_order, image_id
				""", new MapSqlParameterSource("listingIds", listingIds), rs -> {
					java.util.Map<Long, String> keys = new java.util.HashMap<>();
					while (rs.next()) {
						keys.put(rs.getLong("listing_id"), rs.getString("image_key"));
					}
					return keys;
				});
	}
}
