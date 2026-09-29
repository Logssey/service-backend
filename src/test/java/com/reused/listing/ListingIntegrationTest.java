package com.reused.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListingIntegrationTest {

	private static final String LISTINGS = "/api/v1/listings";
	private static final Instant BASE_TIME = Instant.parse("2026-03-14T08:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private ImageStorage imageStorage;

	@BeforeEach
	void resetState() {
		// FK를 따라 게시글·이미지·관심·거래·후기를 지운다. 초기 카테고리는 유지한다.
		jdbcTemplate.execute("TRUNCATE users RESTART IDENTITY CASCADE");
		when(imageStorage.presignRead(anyString(), any(Duration.class)))
				.thenAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0));
	}

	@Test
	@DisplayName("USER는 이미지 없이 게시글을 등록하고 판매자·상태·가격이 저장된다")
	void createWithoutImagesPersistsListing() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");

		MvcResult result = mockMvc.perform(json(post(LISTINGS), createBody(categoryId))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.listingId").isNumber())
				.andReturn();

		Long listingId = response(result).get("listingId").asLong();
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT seller_id, category_id, title, description, price, item_condition, trade_method, status, wish_count, view_count FROM listings WHERE listing_id = ?",
				listingId);
		assertThat(((Number) row.get("seller_id")).longValue()).isEqualTo(sellerId);
		assertThat(((Number) row.get("category_id")).longValue()).isEqualTo(categoryId);
		assertThat(row.get("title")).isEqualTo("아이패드 프로 11인치");
		assertThat(row.get("description")).isEqualTo("2년 사용했습니다");
		assertThat(row.get("price")).isEqualTo(650000);
		assertThat(row.get("item_condition")).isEqualTo("LIKE_NEW");
		assertThat(row.get("trade_method")).isEqualTo("BOTH");
		assertThat(row.get("status")).isEqualTo("ON_SALE");
		assertThat(row.get("wish_count")).isEqualTo(0);
		assertThat(row.get("view_count")).isEqualTo(0);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM listing_images WHERE listing_id = ?", Long.class, listingId)).isZero();
	}

	@Test
	@DisplayName("등록은 로그인한 USER만 가능하며 발급 후 정지된 계정도 거절한다")
	void createRequiresActiveUserRole() throws Exception {
		Long categoryId = categoryId("디지털기기");
		Map<String, Object> body = createBody(categoryId);
		Long adminId = insertUser("관리자", UserRole.ADMIN);
		Long suspendedId = insertUser("정지회원", UserRole.USER);
		String tokenIssuedBeforeSuspension = bearer(suspendedId, UserRole.USER);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspendedId);

		mockMvc.perform(json(post(LISTINGS), body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(json(post(LISTINGS), body)
					.header("Authorization", bearer(adminId, UserRole.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(json(post(LISTINGS), body)
					.header("Authorization", tokenIssuedBeforeSuspension))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listings", Long.class)).isZero();
	}

	@Test
	@DisplayName("등록 필드·열거값·이미지 개수 제약 위반은 400이며 게시글을 만들지 않는다")
	void createRejectsInvalidInput() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		String bearer = bearer(sellerId, UserRole.USER);
		List<Map<String, Object>> invalidBodies = new ArrayList<>();

		Map<String, Object> blankTitle = createBody(categoryId);
		blankTitle.put("title", " ");
		invalidBodies.add(blankTitle);
		Map<String, Object> negativePrice = createBody(categoryId);
		negativePrice.put("price", -1);
		invalidBodies.add(negativePrice);
		Map<String, Object> excessivePrice = createBody(categoryId);
		excessivePrice.put("price", 100000001);
		invalidBodies.add(excessivePrice);
		Map<String, Object> invalidCondition = createBody(categoryId);
		invalidCondition.put("itemCondition", "UNKNOWN");
		invalidBodies.add(invalidCondition);
		Map<String, Object> tooManyImages = createBody(categoryId);
		tooManyImages.put("imageIds", List.of(1, 2, 3, 4, 5, 6));
		invalidBodies.add(tooManyImages);

		for (Map<String, Object> body : invalidBodies) {
			mockMvc.perform(json(post(LISTINGS), body).header("Authorization", bearer))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listings", Long.class)).isZero();
	}

	@Test
	@DisplayName("검증된 이미지를 요청 순서로 게시글에 연결하고 공개 조회에 서명 URL을 제공한다")
	void createAttachesVerifiedImagesAndPublishesSignedUrls() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Map<String, Object> body = createBody(categoryId("디지털기기"));
		String authorization = bearer(sellerId, UserRole.USER);
		Long firstImageId = insertVerifiedImage(sellerId, null);
		Long secondImageId = insertVerifiedImage(sellerId, null);
		body.put("imageIds", List.of(secondImageId, firstImageId));

		MvcResult created = mockMvc.perform(json(post(LISTINGS), body).header("Authorization", authorization))
				.andExpect(status().isCreated())
				.andReturn();
		Long listingId = response(created).get("listingId").asLong();
		List<Map<String, Object>> imageRows = jdbcTemplate.queryForList(
				"SELECT image_id, listing_id, display_order FROM listing_images WHERE listing_id = ? ORDER BY display_order",
				listingId);
		assertThat(imageRows).hasSize(2);
		assertThat(((Number) imageRows.get(0).get("image_id")).longValue()).isEqualTo(secondImageId);
		assertThat(((Number) imageRows.get(1).get("image_id")).longValue()).isEqualTo(firstImageId);
		assertThat(((Number) imageRows.get(0).get("display_order")).intValue()).isZero();
		assertThat(((Number) imageRows.get(1).get("display_order")).intValue()).isEqualTo(1);

		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.images[0].imageId").value(secondImageId))
				.andExpect(jsonPath("$.images[0].displayOrder").value(0))
				.andExpect(jsonPath("$.images[0].url").value("https://images.example.test/" + imageKey(secondImageId)))
				.andExpect(jsonPath("$.images[1].imageId").value(firstImageId));
		mockMvc.perform(get(LISTINGS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].thumbnailUrl")
						.value("https://images.example.test/" + imageKey(secondImageId)));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listings", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("이미지 연결은 검증 상태와 업로더를 확인하고 중복 ID를 거절한다")
	void createRejectsInvalidImageAttachmentsWithoutSavingListing() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long otherId = insertUser("다른회원", UserRole.USER);
		Long pendingId = insertImage(sellerId, null, "PENDING");
		Long otherImageId = insertVerifiedImage(otherId, null);
		Long validId = insertVerifiedImage(sellerId, null);
		String authorization = bearer(sellerId, UserRole.USER);
		Long categoryId = categoryId("디지털기기");

		assertInvalidCreateImages(categoryId, authorization, List.of(validId, validId), 400, "INVALID_INPUT");
		assertInvalidCreateImages(categoryId, authorization, List.of(pendingId), 400, "INVALID_INPUT");
		assertInvalidCreateImages(categoryId, authorization, List.of(otherImageId), 403, "FORBIDDEN");
		assertInvalidCreateImages(categoryId, authorization, List.of(999999L), 400, "INVALID_INPUT");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listings", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT listing_id FROM listing_images WHERE image_id = ?", Long.class, validId)).isNull();
	}

	@Test
	@DisplayName("이미 연결된 이미지는 다른 게시글에 재사용할 수 없다")
	void createRejectsImageAttachedToAnotherListing() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		Long existingListingId = insertListing(sellerId, categoryId, "기존 게시글", 100, "ON_SALE", BASE_TIME);
		Long attachedImageId = insertVerifiedImage(sellerId, existingListingId);

		assertInvalidCreateImages(categoryId, bearer(sellerId, UserRole.USER),
				List.of(attachedImageId), 409, "CONFLICT");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listings", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("수정에서 imageIds 생략은 유지, ID 배열은 재정렬·교체, 빈 배열은 모두 해제한다")
	void updateImageIdsPreservesReplacesAndClearsImages() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId("디지털기기"), "기존 게시글", 100,
				"ON_SALE", BASE_TIME);
		Long firstId = insertVerifiedImage(sellerId, listingId);
		Long secondId = insertVerifiedImage(sellerId, listingId);
		Long newId = insertVerifiedImage(sellerId, null);
		jdbcTemplate.update("UPDATE listing_images SET display_order = 1 WHERE image_id = ?", secondId);
		String authorization = bearer(sellerId, UserRole.USER);

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), Map.of("title", "수정 제목"))
				.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.images[0].imageId").value(firstId))
				.andExpect(jsonPath("$.images[1].imageId").value(secondId))
				.andExpect(jsonPath("$.viewCount").value(0));

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
					Map.of("imageIds", List.of(newId, secondId)))
				.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.images[0].imageId").value(newId))
				.andExpect(jsonPath("$.images[0].displayOrder").value(0))
				.andExpect(jsonPath("$.images[1].imageId").value(secondId))
				.andExpect(jsonPath("$.images[1].displayOrder").value(1))
				.andExpect(jsonPath("$.viewCount").value(0));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM listing_images WHERE listing_id = ?", Long.class, listingId))
				.isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT listing_id FROM listing_images WHERE image_id = ?", Long.class, firstId)).isNull();

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
					Map.of("imageIds", List.of()))
				.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.images.length()").value(0));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM listing_images WHERE listing_id = ?", Long.class, listingId))
				.isZero();
	}

	@Test
	@DisplayName("작성자는 일부 필드만 수정할 수 있고 수정 응답은 조회수를 늘리지 않는다")
	void updatePartialFieldsDoesNotCountAsView() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long originalCategoryId = categoryId("디지털기기");
		Long nextCategoryId = categoryId("도서");
		Long listingId = insertListing(sellerId, originalCategoryId, "수정 전 제목", 650000,
				"ON_SALE", BASE_TIME);

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
					Map.of("title", "수정한 제목", "price", 350000, "categoryId", nextCategoryId,
							"imageIds", List.of()))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.listingId").value(listingId))
				.andExpect(jsonPath("$.title").value("수정한 제목"))
				.andExpect(jsonPath("$.description").value("수정 전 제목 설명"))
				.andExpect(jsonPath("$.price").value(350000))
				.andExpect(jsonPath("$.category.categoryId").value(nextCategoryId))
				.andExpect(jsonPath("$.viewCount").value(0))
				.andExpect(jsonPath("$.isMine").value(true));

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT title, description, price, category_id, view_count, updated_at FROM listings WHERE listing_id = ?",
				listingId);
		assertThat(row.get("title")).isEqualTo("수정한 제목");
		assertThat(row.get("description")).isEqualTo("수정 전 제목 설명");
		assertThat(row.get("price")).isEqualTo(350000);
		assertThat(((Number) row.get("category_id")).longValue()).isEqualTo(nextCategoryId);
		assertThat(row.get("view_count")).isEqualTo(0);
		assertThat(row.get("updated_at")).isNotNull();

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
					Map.of("description", "새 설명", "itemCondition", "NEW", "tradeMethod", "DIRECT"))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("수정한 제목"))
				.andExpect(jsonPath("$.description").value("새 설명"))
				.andExpect(jsonPath("$.itemCondition").value("NEW"))
				.andExpect(jsonPath("$.tradeMethod").value("DIRECT"))
				.andExpect(jsonPath("$.viewCount").value(0));
	}

	@Test
	@DisplayName("게시글 수정은 현재 활성 상태인 작성자만 허용한다")
	void updateRequiresActiveOwner() throws Exception {
		Long categoryId = categoryId("디지털기기");
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long otherId = insertUser("다른회원", UserRole.USER);
		Long adminId = insertUser("관리자", UserRole.ADMIN);
		Long suspendedId = insertUser("정지회원", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId, "판매중 상품", 100, "ON_SALE", BASE_TIME);
		Long suspendedListingId = insertListing(suspendedId, categoryId, "정지회원 상품", 100,
				"ON_SALE", BASE_TIME);
		String tokenIssuedBeforeSuspension = bearer(suspendedId, UserRole.USER);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspendedId);
		Map<String, Object> body = Map.of("title", "변경된 제목");

		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), body))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), body)
					.header("Authorization", bearer(otherId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), body)
					.header("Authorization", bearer(adminId, UserRole.ADMIN)))
				.andExpect(status().isForbidden());
		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", suspendedListingId), body)
					.header("Authorization", tokenIssuedBeforeSuspension))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		jdbcTemplate.update("UPDATE listings SET deleted_at = now() WHERE listing_id = ?", listingId);
		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), body)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("수정 입력과 카테고리 및 존재하지 않는 이미지를 검증한다")
	void updateValidatesFieldsAndImages() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId("디지털기기"), "원래 제목", 100,
				"ON_SALE", BASE_TIME);
		String authorization = bearer(sellerId, UserRole.USER);
		List<Map<String, Object>> invalidBodies = List.of(
				Map.of("title", " "),
				Map.of("description", "  "),
				Map.of("price", -1),
				Map.of("itemCondition", "UNKNOWN"),
				Map.of("tradeMethod", "UNKNOWN"),
				Map.of("categoryId", -1),
				Map.of("imageIds", List.of(1, 2, 3, 4, 5, 6)));
		for (Map<String, Object> body : invalidBodies) {
			mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId), body)
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		}
		mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
					Map.of("imageIds", List.of(7)))
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		Long inactiveCategoryId = categoryId("도서");
		jdbcTemplate.update("UPDATE categories SET is_active = false WHERE category_id = ?", inactiveCategoryId);
		try {
			mockMvc.perform(json(patch(LISTINGS + "/{listingId}", listingId),
						Map.of("categoryId", inactiveCategoryId))
						.header("Authorization", authorization))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		}
		finally {
			jdbcTemplate.update("UPDATE categories SET is_active = true WHERE category_id = ?", inactiveCategoryId);
		}
		assertThat(jdbcTemplate.queryForObject(
				"SELECT title FROM listings WHERE listing_id = ?", String.class, listingId)).isEqualTo("원래 제목");
	}

	@Test
	@DisplayName("작성자의 삭제는 논리 삭제이며 완료된 과거 거래는 삭제를 막지 않는다")
	void deleteSoftDeletesOwnedListing() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId("디지털기기"), "삭제할 게시글", 100,
				"COMPLETED", BASE_TIME);
		Long imageId = insertVerifiedImage(sellerId, listingId);
		insertCompletedTrade(listingId, sellerId, buyerId);

		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isNoContent());

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT deleted_at, deleted_by, updated_at FROM listings WHERE listing_id = ?", listingId);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(((Number) row.get("deleted_by")).longValue()).isEqualTo(sellerId);
		assertThat(row.get("updated_at")).isEqualTo(row.get("deleted_at"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT listing_id FROM listing_images WHERE image_id = ?", Long.class, imageId))
				.isEqualTo(listingId);
		mockMvc.perform(get(LISTINGS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("요청 또는 승인 상태의 거래가 있으면 게시글 삭제는 409다")
	void deleteRejectsActiveTrades() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		String authorization = bearer(sellerId, UserRole.USER);
		for (String tradeStatus : List.of("REQUESTED", "ACCEPTED")) {
			Long listingId = insertListing(sellerId, categoryId, tradeStatus + " 게시글", 100,
					tradeStatus.equals("ACCEPTED") ? "RESERVED" : "ON_SALE", BASE_TIME);
			jdbcTemplate.update("INSERT INTO trades (listing_id, seller_id, buyer_id, status) VALUES (?, ?, ?, ?)",
					listingId, sellerId, buyerId, tradeStatus);

			mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
						.header("Authorization", authorization))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("CONFLICT"));
			assertThat(jdbcTemplate.queryForObject(
					"SELECT deleted_at FROM listings WHERE listing_id = ?", Timestamp.class, listingId)).isNull();
		}
	}

	@Test
	@DisplayName("게시글 삭제도 로그인·작성자·현재 계정 상태를 확인한다")
	void deleteRequiresActiveOwner() throws Exception {
		Long categoryId = categoryId("디지털기기");
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long otherId = insertUser("다른회원", UserRole.USER);
		Long adminId = insertUser("관리자", UserRole.ADMIN);
		Long suspendedId = insertUser("정지회원", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId, "판매중 상품", 100, "ON_SALE", BASE_TIME);
		Long suspendedListingId = insertListing(suspendedId, categoryId, "정지회원 상품", 100,
				"ON_SALE", BASE_TIME);
		String tokenIssuedBeforeSuspension = bearer(suspendedId, UserRole.USER);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspendedId);

		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(otherId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(adminId, UserRole.ADMIN)))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete(LISTINGS + "/{listingId}", suspendedListingId)
					.header("Authorization", tokenIssuedBeforeSuspension))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("비로그인 목록은 공개되고 삭제·숨김 게시글을 제외한다")
	void publicListOmitsDeletedAndHiddenListings() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		Long visible = insertListing(sellerId, categoryId, "판매중 기기", 100, "ON_SALE", BASE_TIME);
		Long reserved = insertListing(sellerId, categoryId, "예약중 기기", 200, "RESERVED", BASE_TIME.plusSeconds(1));
		insertListing(sellerId, categoryId, "숨김 기기", 300, "HIDDEN", BASE_TIME.plusSeconds(2));
		Long deleted = insertListing(sellerId, categoryId, "삭제 기기", 400, "ON_SALE", BASE_TIME.plusSeconds(3));
		jdbcTemplate.update("UPDATE listings SET deleted_at = now() WHERE listing_id = ?", deleted);

		mockMvc.perform(get(LISTINGS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].listingId").value(reserved))
				.andExpect(jsonPath("$.items[1].listingId").value(visible))
				.andExpect(jsonPath("$.items[0].seller.userId").value(sellerId))
				.andExpect(jsonPath("$.items[0].seller.nickname").value("판매자"))
				.andExpect(jsonPath("$.items[0].status").value("RESERVED"))
				.andExpect(jsonPath("$.items[0].thumbnailUrl").value((Object) null))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("목록 검색은 키워드·카테고리·판매 상태·상품 상태·가격 조건을 함께 적용한다")
	void listAppliesFilters() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long devices = categoryId("디지털기기");
		Long books = categoryId("도서");
		Long expected = insertListing(sellerId, devices, "아이패드 프로", 650000, "ON_SALE", BASE_TIME);
		Long otherCondition = insertListing(sellerId, devices, "아이패드 에어", 600000, "ON_SALE", BASE_TIME.plusSeconds(4));
		jdbcTemplate.update("UPDATE listings SET item_condition = 'USED' WHERE listing_id = ?", otherCondition);
		insertListing(sellerId, devices, "아이패드 미니", 300000, "RESERVED", BASE_TIME.plusSeconds(1));
		insertListing(sellerId, books, "아이패드 사용 설명서", 5000, "ON_SALE", BASE_TIME.plusSeconds(2));
		insertListing(sellerId, devices, "갤럭시 탭", 700000, "ON_SALE", BASE_TIME.plusSeconds(3));

		mockMvc.perform(get(LISTINGS)
					.param("keyword", "아이패드")
					.param("categoryId", devices.toString())
					.param("status", "ON_SALE")
					.param("itemCondition", "LIKE_NEW")
					.param("minPrice", "500000")
					.param("maxPrice", "700000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(expected))
				.andExpect(jsonPath("$.items[0].price").value(650000));
	}

	@Test
	@DisplayName("목록 검색은 상품 상태로 좁힐 수 있다(HOME-002 필터)")
	void listFiltersByItemCondition() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long devices = categoryId("디지털기기");
		Long likeNew = insertListing(sellerId, devices, "거의 새것", 100, "ON_SALE", BASE_TIME);
		Long used = insertListing(sellerId, devices, "사용감 있음", 100, "ON_SALE", BASE_TIME.plusSeconds(1));
		jdbcTemplate.update("UPDATE listings SET item_condition = 'USED' WHERE listing_id = ?", used);

		mockMvc.perform(get(LISTINGS).param("itemCondition", "USED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(used))
				.andExpect(jsonPath("$.items[0].itemCondition").value("USED"));
		mockMvc.perform(get(LISTINGS).param("itemCondition", "LIKE_NEW"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(likeNew));
	}

	@Test
	@DisplayName("목록의 최대 크기·정렬값·숫자·상품 상태 파라미터 오류는 모두 400이다")
	void listRejectsInvalidQueryParameters() throws Exception {
		mockMvc.perform(get(LISTINGS).param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(get(LISTINGS).param("itemCondition", "BROKEN"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(get(LISTINGS).param("sort", "unknown"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(get(LISTINGS).param("itemCondition", "unknown"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(get(LISTINGS).param("minPrice", "not-a-number"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("최신순 커서는 동일 시각 게시글을 중복·누락 없이 넘기고 정렬 변경을 거절한다")
	void latestCursorIsStableAcrossTies() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		Long oldest = insertListing(sellerId, categoryId, "동일 시각 A", 100, "ON_SALE", BASE_TIME);
		Long middle = insertListing(sellerId, categoryId, "동일 시각 B", 100, "ON_SALE", BASE_TIME);
		Long newest = insertListing(sellerId, categoryId, "동일 시각 C", 100, "ON_SALE", BASE_TIME);

		MvcResult first = mockMvc.perform(get(LISTINGS).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].listingId").value(newest))
				.andExpect(jsonPath("$.items[1].listingId").value(middle))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andReturn();
		String cursor = response(first).get("nextCursor").asString();
		insertListing(sellerId, categoryId, "새 게시글", 100, "ON_SALE", BASE_TIME.plusSeconds(1));

		mockMvc.perform(get(LISTINGS).param("size", "2").param("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(oldest))
				.andExpect(jsonPath("$.hasNext").value(false));
		mockMvc.perform(get(LISTINGS).param("size", "2").param("sort", "priceAsc").param("cursor", cursor))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("가격 오름차순·내림차순 커서는 동가 게시글도 모두 한 번씩 반환한다")
	void priceCursorsCoverEqualPricesWithoutDuplicates() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		List<Long> ids = List.of(
				insertListing(sellerId, categoryId, "가격 100 A", 100, "ON_SALE", BASE_TIME),
				insertListing(sellerId, categoryId, "가격 100 B", 100, "ON_SALE", BASE_TIME),
				insertListing(sellerId, categoryId, "가격 200", 200, "ON_SALE", BASE_TIME),
				insertListing(sellerId, categoryId, "가격 300", 300, "ON_SALE", BASE_TIME));

		assertPricePages("priceAsc", List.of(100, 100, 200, 300), ids);
		assertPricePages("priceDesc", List.of(300, 200, 100, 100), ids);
	}

	@Test
	@DisplayName("차단한 판매자는 로그인 목록에서만 빠지고 게시글 상세에는 직접 접근할 수 있다")
	void blocksFilterOnlyAuthenticatedLists() throws Exception {
		Long viewerId = insertUser("탐색자", UserRole.USER);
		Long blockedSellerId = insertUser("차단한판매자", UserRole.USER);
		Long otherSellerId = insertUser("다른판매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		Long blockedListing = insertListing(blockedSellerId, categoryId, "차단 대상 글", 100, "ON_SALE", BASE_TIME);
		Long otherListing = insertListing(otherSellerId, categoryId, "다른 판매자 글", 200, "ON_SALE", BASE_TIME);
		jdbcTemplate.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", viewerId, blockedSellerId);

		mockMvc.perform(get(LISTINGS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
		mockMvc.perform(get(LISTINGS).header("Authorization", bearer(viewerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(otherListing));
		mockMvc.perform(get(LISTINGS + "/{listingId}", blockedListing)
					.header("Authorization", bearer(viewerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.listingId").value(blockedListing));
	}

	@Test
	@DisplayName("상세는 익명·작성자·관심 사용자 표시와 판매자 완료 거래·평점을 반환하고 조회수를 센다")
	void detailIncludesViewerAndSellerTrustInformation() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long otherBuyerId = insertUser("두번째구매자", UserRole.USER);
		Long categoryId = categoryId("디지털기기");
		Long listingId = insertListing(sellerId, categoryId, "판매중 상품", 650000, "ON_SALE", BASE_TIME);
		Long firstPastListing = insertListing(sellerId, categoryId, "거래완료 A", 100, "COMPLETED", BASE_TIME.minusSeconds(1));
		Long secondPastListing = insertListing(sellerId, categoryId, "거래완료 B", 200, "COMPLETED", BASE_TIME.minusSeconds(2));
		Long firstTrade = insertCompletedTrade(firstPastListing, sellerId, buyerId);
		Long secondTrade = insertCompletedTrade(secondPastListing, sellerId, otherBuyerId);
		jdbcTemplate.update("INSERT INTO reviews (trade_id, reviewer_id, reviewee_id, rating) VALUES (?, ?, ?, ?)",
				firstTrade, buyerId, sellerId, 5);
		jdbcTemplate.update("INSERT INTO reviews (trade_id, reviewer_id, reviewee_id, rating) VALUES (?, ?, ?, ?)",
				secondTrade, otherBuyerId, sellerId, 3);
		jdbcTemplate.update("INSERT INTO wishes (user_id, listing_id) VALUES (?, ?)", buyerId, listingId);
		jdbcTemplate.update("UPDATE listings SET wish_count = 1 WHERE listing_id = ?", listingId);

		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("판매중 상품"))
				.andExpect(jsonPath("$.description").value("판매중 상품 설명"))
				.andExpect(jsonPath("$.category.categoryId").value(categoryId))
				.andExpect(jsonPath("$.category.name").value("디지털기기"))
				.andExpect(jsonPath("$.images.length()").value(0))
				.andExpect(jsonPath("$.wishCount").value(1))
				.andExpect(jsonPath("$.viewCount").value(1))
				.andExpect(jsonPath("$.isMine").value(false))
				.andExpect(jsonPath("$.isWished").value(false))
				.andExpect(jsonPath("$.seller.userId").value(sellerId))
				.andExpect(jsonPath("$.seller.completedTradeCount").value(2))
				.andExpect(jsonPath("$.seller.averageRating").value(4.0));
		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isMine").value(true))
				.andExpect(jsonPath("$.isWished").value(false))
				.andExpect(jsonPath("$.viewCount").value(2));
		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId)
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isMine").value(false))
				.andExpect(jsonPath("$.isWished").value(true))
				.andExpect(jsonPath("$.viewCount").value(3));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT view_count FROM listings WHERE listing_id = ?", Integer.class, listingId)).isEqualTo(3);
	}

	@Test
	@DisplayName("없는 게시글과 삭제된 게시글 상세는 404다")
	void detailRejectsMissingAndDeletedListings() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long listingId = insertListing(sellerId, categoryId("디지털기기"), "삭제할 게시글", 100,
				"ON_SALE", BASE_TIME);
		jdbcTemplate.update("UPDATE listings SET deleted_at = now() WHERE listing_id = ?", listingId);

		mockMvc.perform(get(LISTINGS + "/{listingId}", listingId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mockMvc.perform(get(LISTINGS + "/{listingId}", 999999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private void assertPricePages(String sort, List<Integer> expectedPrices, List<Long> expectedIds) throws Exception {
		List<Integer> prices = new ArrayList<>();
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		for (int page = 0; page < expectedIds.size(); page++) {
			MockHttpServletRequestBuilder request = get(LISTINGS).param("sort", sort).param("size", "1");
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
			JsonNode body = response(result);
			assertThat(body.get("items").size()).isEqualTo(1);
			JsonNode item = body.get("items").get(0);
			prices.add(item.get("price").asInt());
			ids.add(item.get("listingId").asLong());
			if (page < expectedIds.size() - 1) {
				assertThat(body.get("hasNext").asBoolean()).isTrue();
				cursor = body.get("nextCursor").asString();
				assertThat(cursor).isNotBlank();
			}
			else {
				assertThat(body.get("hasNext").asBoolean()).isFalse();
			}
		}
		assertThat(prices).containsExactlyElementsOf(expectedPrices);
		assertThat(ids).containsExactlyInAnyOrderElementsOf(expectedIds);
	}

	private Long insertUser(String nickname, UserRole role) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, role, terms_agreed_at) VALUES (?, ?, now()) RETURNING user_id",
				Long.class, nickname, role.name());
	}

	private Long insertVerifiedImage(Long uploaderId, Long listingId) {
		return insertImage(uploaderId, listingId, "VERIFIED");
	}

	private void assertInvalidCreateImages(Long categoryId, String authorization, List<Long> imageIds,
			int expectedStatus, String expectedCode) throws Exception {
		Map<String, Object> body = createBody(categoryId);
		body.put("imageIds", imageIds);
		mockMvc.perform(json(post(LISTINGS), body).header("Authorization", authorization))
				.andExpect(status().is(expectedStatus))
				.andExpect(jsonPath("$.code").value(expectedCode));
	}

	private Long insertImage(Long uploaderId, Long listingId, String status) {
		String objectKey = "listing-tests/" + UUID.randomUUID() + ".jpg";
		return jdbcTemplate.queryForObject("""
				INSERT INTO listing_images (listing_id, uploader_id, object_key, content_type, file_size, status)
				VALUES (?, ?, ?, 'image/jpeg', 1024, ?) RETURNING image_id
				""", Long.class, listingId, uploaderId, objectKey, status);
	}

	private String imageKey(Long imageId) {
		return jdbcTemplate.queryForObject("SELECT object_key FROM listing_images WHERE image_id = ?",
				String.class, imageId);
	}

	private Long categoryId(String name) {
		return jdbcTemplate.queryForObject("SELECT category_id FROM categories WHERE name = ?", Long.class, name);
	}

	private Long insertListing(Long sellerId, Long categoryId, String title, int price, String status,
			Instant createdAt) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, trade_method, status, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, 'LIKE_NEW', 'BOTH', ?, ?) RETURNING listing_id",
				Long.class, sellerId, categoryId, title, title + " 설명", price, status, Timestamp.from(createdAt));
	}

	private Long insertCompletedTrade(Long listingId, Long sellerId, Long buyerId) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO trades (listing_id, seller_id, buyer_id, status, completed_at) "
						+ "VALUES (?, ?, ?, 'COMPLETED', now()) RETURNING trade_id",
				Long.class, listingId, sellerId, buyerId);
	}

	private Map<String, Object> createBody(Long categoryId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", "아이패드 프로 11인치");
		body.put("description", "2년 사용했습니다");
		body.put("price", 650000);
		body.put("itemCondition", "LIKE_NEW");
		body.put("tradeMethod", "BOTH");
		body.put("categoryId", categoryId);
		body.put("imageIds", List.of());
		return body;
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private String bearer(Long userId, UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, role);
	}

	private JsonNode response(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

}
