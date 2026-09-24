package com.reused.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.image.storage.StoredImage;
import com.reused.user.entity.UserRole;

@Import({TestcontainersConfiguration.class, ImageIntegrationTest.FakeStorageConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class ImageIntegrationTest {

	private static final String IMAGES = "/api/v1/images";
	private static final byte[] JPEG = imageBytes("jpg");
	private static final byte[] PNG = imageBytes("png");
	private static final byte[] WEBP = Base64.getDecoder().decode(
			"UklGRjoAAABXRUJQVlA4IC4AAACyAgCdASoCAAIALmk0mk0iIiIiIgBoSygABc6WWgAA/veff/0PP8bA//LwYAAA");

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private FakeImageStorage storage;
	@MockitoBean private OAuthProviderClient kakaoClient;
	@MockitoBean private AuthMailSender mailSender;

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
		storage.reset();
	}

	@Test
	void uploadRequiresActiveUserAndValidMetadata() throws Exception {
		Long userId = user("USER", "ACTIVE");
		Long adminId = user("ADMIN", "ACTIVE");
		Long suspendedId = user("USER", "SUSPENDED");

		mvc.perform(uploadRequest(null, "LISTING", "photo.jpg", "image/jpeg", 12))
				.andExpect(status().isUnauthorized());
		mvc.perform(uploadRequest(adminId, "LISTING", "photo.jpg", "image/jpeg", 12))
				.andExpect(status().isForbidden());
		mvc.perform(uploadRequest(suspendedId, "LISTING", "photo.jpg", "image/jpeg", 12))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		mvc.perform(uploadRequest(userId, "PROFILE", "photo.jpg", "image/jpeg", 12))
				.andExpect(status().isBadRequest());
		mvc.perform(uploadRequest(userId, "LISTING", "photo.png", "image/jpeg", 12))
				.andExpect(status().isBadRequest());
		mvc.perform(uploadRequest(userId, "LISTING", "photo.jpg", "image/gif", 12))
				.andExpect(status().isBadRequest());
		mvc.perform(uploadRequest(userId, "LISTING", "photo.jpg", "image/jpeg", 10_485_761))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images", Long.class)).isZero();
	}

	@Test
	void completePromotesInspectedBytesToUnwritableVerifiedKey() throws Exception {
		Long owner = user("USER", "ACTIVE");
		JsonNode upload = upload(owner, "photo.JPG", "image/jpeg", JPEG.length);
		Long id = upload.get("imageId").asLong();
		String pendingKey = storage.lastUploadKey;
		assertThat(upload.get("uploadUrl").asString()).contains("/upload/pending/");
		assertThat(upload.get("expiresAt").asString()).isNotBlank();
		storage.put(pendingKey, "image/jpeg", JPEG);

		MvcResult completed = mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VERIFIED"))
				.andExpect(jsonPath("$.thumbnailUrl").value(
						org.hamcrest.Matchers.containsString("/read/verified/")))
				.andReturn();
		String finalKey = jdbc.queryForObject(
				"SELECT object_key FROM listing_images WHERE image_id = ?", String.class, id);
		assertThat(finalKey).startsWith("verified/listing-images/");
		assertThat(storage.bytes(finalKey)).containsExactly(JPEG);
		assertThat(storage.bytes(pendingKey)).isNull();
		assertThat(mapper.readTree(completed.getResponse().getContentAsString()).get("url").asString())
				.contains(finalKey);

		// A 5-minute upload URL can still PUT to its original key; the published key is separate.
		storage.put(pendingKey, "image/jpeg", new byte[] {1, 2, 3});
		assertThat(storage.bytes(finalKey)).containsExactly(JPEG);
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT status FROM listing_images WHERE image_id = ?", String.class, id))
				.isEqualTo("VERIFIED");
	}

	@Test
	void pngAndWebpSignaturesCanBeVerified() throws Exception {
		Long owner = user("USER", "ACTIVE");
		for (var image : new Object[][] {
				{"photo.png", "image/png", PNG},
				{"photo.webp", "image/webp", WEBP}}) {
			String fileName = (String) image[0];
			String contentType = (String) image[1];
			byte[] bytes = (byte[]) image[2];
			Long id = upload(owner, fileName, contentType, bytes.length).get("imageId").asLong();
			storage.put(storage.lastUploadKey, contentType, bytes);
			mvc.perform(post(IMAGES + "/{imageId}/complete", id)
						.header("Authorization", bearer(owner, UserRole.USER)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("VERIFIED"));
		}
	}

	@Test
	void invalidActualObjectIsRejectedAndCannotBeCompletedAgain() throws Exception {
		Long owner = user("USER", "ACTIVE");
		JsonNode upload = upload(owner, "wrong.jpg", "image/jpeg", JPEG.length);
		Long id = upload.get("imageId").asLong();
		storage.put(storage.lastUploadKey, "image/jpeg", new byte[JPEG.length]);
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(jdbc.queryForObject("SELECT status FROM listing_images WHERE image_id = ?", String.class, id))
				.isEqualTo("REJECTED");
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void truncatedFilesWithValidHeadersAreRejected() throws Exception {
		Long owner = user("USER", "ACTIVE");
		for (var image : new Object[][] {
				{"cut.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}},
				{"cut.png", "image/png", Arrays.copyOf(PNG, 8)},
				{"cut.webp", "image/webp", Arrays.copyOf(WEBP, 12)}}) {
			String fileName = (String) image[0];
			String contentType = (String) image[1];
			byte[] bytes = (byte[]) image[2];
			Long id = upload(owner, fileName, contentType, bytes.length).get("imageId").asLong();
			storage.put(storage.lastUploadKey, contentType, bytes);
			mvc.perform(post(IMAGES + "/{imageId}/complete", id)
						.header("Authorization", bearer(owner, UserRole.USER)))
					.andExpect(status().isBadRequest());
			assertThat(statusOf(id)).isEqualTo("REJECTED");
		}
	}

	@Test
	void missingOversizedAndMismatchedContentTypeObjectsAreRejected() throws Exception {
		Long owner = user("USER", "ACTIVE");
		Long missing = upload(owner, "missing.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		mvc.perform(post(IMAGES + "/{imageId}/complete", missing)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
		assertThat(statusOf(missing)).isEqualTo("REJECTED");

		Long empty = upload(owner, "empty.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		storage.put(storage.lastUploadKey, "image/jpeg", new byte[0]);
		mvc.perform(post(IMAGES + "/{imageId}/complete", empty)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
		assertThat(statusOf(empty)).isEqualTo("REJECTED");

		Long wrongSize = upload(owner, "size.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		storage.put(storage.lastUploadKey, "image/jpeg", Arrays.copyOf(JPEG, JPEG.length + 1));
		mvc.perform(post(IMAGES + "/{imageId}/complete", wrongSize)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
		assertThat(statusOf(wrongSize)).isEqualTo("REJECTED");

		Long wrongType = upload(owner, "type.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		storage.put(storage.lastUploadKey, "image/png", JPEG);
		mvc.perform(post(IMAGES + "/{imageId}/complete", wrongType)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
		assertThat(statusOf(wrongType)).isEqualTo("REJECTED");
	}

	@Test
	void changingObjectBetweenInspectionAndCopyRejectsIt() throws Exception {
		Long owner = user("USER", "ACTIVE");
		Long id = upload(owner, "photo.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		storage.put(storage.lastUploadKey, "image/jpeg", JPEG);
		storage.changeBeforePromotion = true;
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT status FROM listing_images WHERE image_id = ?", String.class, id))
				.isEqualTo("REJECTED");
	}

	@Test
	void onlyUploaderCanCompleteOrDeleteAndAttachedImageCannotBeDeleted() throws Exception {
		Long owner = user("USER", "ACTIVE");
		Long other = user("USER", "ACTIVE");
		Long id = upload(owner, "photo.jpg", "image/jpeg", JPEG.length).get("imageId").asLong();
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(other, UserRole.USER)))
				.andExpect(status().isForbidden());
		mvc.perform(delete(IMAGES + "/{imageId}", id)
					.header("Authorization", bearer(other, UserRole.USER)))
				.andExpect(status().isForbidden());
		mvc.perform(delete(IMAGES + "/{imageId}", 99999)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isNotFound());

		storage.put(storage.lastUploadKey, "image/jpeg", JPEG);
		mvc.perform(post(IMAGES + "/{imageId}/complete", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isOk());
		Long category = jdbc.queryForObject("SELECT category_id FROM categories LIMIT 1", Long.class);
		Long listing = jdbc.queryForObject("""
				INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, trade_method)
				VALUES (?, ?, '판매 상품', '설명', 100, 'USED', 'DIRECT') RETURNING listing_id
				""", Long.class, owner, category);
		jdbc.update("UPDATE listing_images SET listing_id = ? WHERE image_id = ?", listing, id);
		mvc.perform(delete(IMAGES + "/{imageId}", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isConflict());
		jdbc.update("UPDATE listing_images SET listing_id = NULL WHERE image_id = ?", id);
		String finalKey = jdbc.queryForObject("SELECT object_key FROM listing_images WHERE image_id = ?", String.class, id);
		mvc.perform(delete(IMAGES + "/{imageId}", id)
					.header("Authorization", bearer(owner, UserRole.USER)))
				.andExpect(status().isNoContent());
		assertThat(storage.lastDeleteSawNoReference).isTrue();
		assertThat(storage.bytes(finalKey)).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE image_id = ?", Long.class, id))
				.isZero();
	}

	private JsonNode upload(Long userId, String fileName, String contentType, long size) throws Exception {
		MvcResult result = mvc.perform(uploadRequest(userId, "LISTING", fileName, contentType, size))
				.andExpect(status().isCreated()).andReturn();
		return mapper.readTree(result.getResponse().getContentAsString());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uploadRequest(
			Long userId, String purpose, String fileName, String contentType, long size) throws Exception {
		var request = post(IMAGES + "/upload-url").contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("purpose", purpose, "fileName", fileName,
						"contentType", contentType, "fileSize", size)));
		if (userId != null) {
			String role = jdbc.queryForObject("SELECT role FROM users WHERE user_id = ?", String.class, userId);
			request.header("Authorization", bearer(userId, UserRole.valueOf(role)));
		}
		return request;
	}

	private Long user(String role, String status) {
		return jdbc.queryForObject("""
				INSERT INTO users (nickname, role, status, terms_agreed_at)
				VALUES (?, ?, ?, now()) RETURNING user_id
				""", Long.class, UUID.randomUUID().toString().substring(0, 12), role, status);
	}

	private String statusOf(Long imageId) {
		return jdbc.queryForObject("SELECT status FROM listing_images WHERE image_id = ?", String.class, imageId);
	}

	private static byte[] imageBytes(String format) {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x22aa66);
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, format, out)) {
				throw new IllegalStateException("테스트 이미지 인코더를 찾지 못했습니다: " + format);
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String bearer(Long id, UserRole role) {
		return "Bearer " + tokens.issueAccessToken(id, role);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeStorageConfig {
		@Bean
		@Primary
		FakeImageStorage fakeImageStorage(JdbcTemplate jdbc) {
			return new FakeImageStorage(jdbc);
		}
	}

	static class FakeImageStorage implements ImageStorage {
		private final JdbcTemplate jdbc;
		private final Map<String, ObjectData> objects = new HashMap<>();
		private int nextEtag;
		private String lastUploadKey;
		private boolean changeBeforePromotion;
		private boolean lastDeleteSawNoReference;

		FakeImageStorage(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		void reset() {
			objects.clear();
			nextEtag = 0;
			lastUploadKey = null;
			changeBeforePromotion = false;
			lastDeleteSawNoReference = false;
		}

		void put(String key, String contentType, byte[] bytes) {
			objects.put(key, new ObjectData(contentType, bytes.clone(), "etag-" + ++nextEtag));
		}

		byte[] bytes(String key) {
			ObjectData object = objects.get(key);
			return object == null ? null : object.bytes().clone();
		}

		@Override
		public String presignUpload(String key, String contentType, Duration ttl) {
			assertThat(ttl).isEqualTo(Duration.ofMinutes(5));
			lastUploadKey = key;
			return "https://storage.test/upload/" + key;
		}

		@Override
		public Optional<StoredImage> inspect(String key) {
			ObjectData object = objects.get(key);
			return object == null ? Optional.empty() : Optional.of(new StoredImage(object.bytes().length,
					object.contentType(), object.bytes().clone(),
					object.etag()));
		}

		@Override
		public boolean promote(String sourceKey, String verifiedKey, String expectedEtag) {
			if (changeBeforePromotion) {
				put(sourceKey, "image/jpeg", new byte[] {1, 2, 3});
			}
			ObjectData source = objects.get(sourceKey);
			if (source == null || !source.etag().equals(expectedEtag)) {
				return false;
			}
			put(verifiedKey, source.contentType(), source.bytes());
			return true;
		}

		@Override
		public String presignRead(String key, Duration ttl) {
			assertThat(objects).containsKey(key);
			return "https://storage.test/read/" + key;
		}

		@Override
		public void delete(String key) {
			lastDeleteSawNoReference = jdbc.queryForObject(
					"SELECT count(*) FROM listing_images WHERE object_key = ?", Long.class, key) == 0;
			objects.remove(key);
		}

		private record ObjectData(String contentType, byte[] bytes, String etag) {
		}
	}
}
