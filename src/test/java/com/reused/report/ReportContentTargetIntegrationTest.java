package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.report.RecordingContentModerationPort.Call;
import com.reused.user.entity.AuthProvider;

/**
 * 실제 PostgreSQL 게시글·메시지·커뮤니티 행을 신고한다. 콘텐츠 조치 자체만 기록 대역으로 교체한다.
 * 대상 해석(404·본인 대상 400), 관리자 목록의 요약, 조치 포트 호출과 그 감사·알림, 작성자 정지를 확인한다.
 */
@Import({ TestcontainersConfiguration.class, ReportContentTargetIntegrationTest.ModerationRecordingConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class ReportContentTargetIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class ModerationRecordingConfiguration {
		@Bean
		@Primary
		RecordingContentModerationPort recordingContentModerationPort() {
			return new RecordingContentModerationPort();
		}
	}

	private static final String PASSWORD = "hunter22!pw";
	private static final long LISTING_ID = 101L;
	private static final long OWN_LISTING_ID = 102L;
	private static final long MESSAGE_ID = 501L;
	private static final long OWN_MESSAGE_ID = 502L;
	private static final long POST_ID = 301L;
	private static final long COMMENT_ID = 401L;
	private static final String LONG_MESSAGE = "안녕하세요 거래 관련해서 드릴 말씀이 있는데요 입금 먼저 해 주시면 바로 택배로 보내 드리겠습니다 믿어 주세요";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RecordingContentModerationPort contentModeration;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private Member admin;
	private Member reporter;
	private Member owner;
	private Member outsider;

	@BeforeEach
	void resetState() throws Exception {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, reports, blocks, notification_settings, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		contentModeration.reset();

		admin = admin("admin@example.com", "관리자");
		reporter = signup("reporter@example.com", "신고자");
		owner = signup("owner@example.com", "작성자");
		outsider = signup("outsider@example.com", "제3자");
		seedRealTargets();
	}

	// --- 신고 접수 ---

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "LISTING, 101, FRAUD_SUSPICION", "MESSAGE, 501, ABUSIVE_BEHAVIOR",
			"COMMUNITY_POST, 301, FALSE_INFO", "COMMUNITY_COMMENT, 401, SPAM" })
	@DisplayName("게시글·메시지·커뮤니티 글·댓글도 같은 API로 접수된다")
	void reportContent(String targetType, long targetId, String reasonCode) throws Exception {
		mockMvc.perform(report(reporter, targetType, targetId, reasonCode))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("RECEIVED"));

		assertThat(jdbcTemplate.queryForMap("SELECT target_type, target_id, reporter_id FROM reports"))
				.containsEntry("target_type", targetType).containsEntry("target_id", targetId)
				.containsEntry("reporter_id", reporter.userId());
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "LISTING, 102, SPAM", "MESSAGE, 502, SPAM" })
	@DisplayName("본인이 작성한 게시글·보낸 메시지는 신고할 수 없다(400)")
	void ownContentIsInvalid(String targetType, long targetId, String reasonCode) throws Exception {
		mockMvc.perform(report(reporter, targetType, targetId, reasonCode))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("본인이 작성한 콘텐츠는 신고할 수 없습니다."));

		assertThat(reportCount()).isZero();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "COMMUNITY_POST, 301", "COMMUNITY_COMMENT, 401" })
	@DisplayName("커뮤니티 글·댓글 작성자도 본인 콘텐츠를 신고할 수 없다")
	void ownCommunityContentIsInvalid(String targetType, long targetId) throws Exception {
		mockMvc.perform(report(owner, targetType, targetId, "SPAM"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("없거나 숨김·삭제된 대상은 404다")
	void missingOrRemovedContentIsNotFound() throws Exception {
		mockMvc.perform(report(reporter, "LISTING", 999L, "SPAM"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("신고 대상을 찾을 수 없습니다."));

		jdbcTemplate.update("UPDATE community_posts SET status = 'HIDDEN' WHERE post_id = ?", POST_ID);
		mockMvc.perform(report(reporter, "COMMUNITY_POST", POST_ID, "SPAM"))
				.andExpect(status().isNotFound());
		mockMvc.perform(report(reporter, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM"))
				.andExpect(status().isNotFound());

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("숨긴 판매글, 삭제한 메시지, 숨긴 댓글은 새 신고를 받지 않는다")
	void removedTargetsCannotBeReported() throws Exception {
		jdbcTemplate.update("UPDATE listings SET status = 'HIDDEN' WHERE listing_id = ?", LISTING_ID);
		jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE message_id = ?", MESSAGE_ID);
		jdbcTemplate.update("UPDATE community_comments SET status = 'HIDDEN' WHERE comment_id = ?", COMMENT_ID);

		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "SPAM")).andExpect(status().isNotFound());
		mockMvc.perform(report(reporter, "MESSAGE", MESSAGE_ID, "SPAM")).andExpect(status().isNotFound());
		mockMvc.perform(report(reporter, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM"))
				.andExpect(status().isNotFound());
		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("소프트 삭제된 상품·커뮤니티 글·댓글도 새 신고를 받지 않는다")
	void softDeletedTargetsCannotBeReported() throws Exception {
		jdbcTemplate.update("UPDATE listings SET deleted_at = now(), deleted_by = ? WHERE listing_id = ?",
				owner.userId(), LISTING_ID);
		jdbcTemplate.update("UPDATE community_comments SET deleted_at = now(), deleted_by = ? WHERE comment_id = ?",
				owner.userId(), COMMENT_ID);
		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "SPAM")).andExpect(status().isNotFound());
		mockMvc.perform(report(reporter, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM"))
				.andExpect(status().isNotFound());
		jdbcTemplate.update("UPDATE community_posts SET deleted_at = now(), deleted_by = ? WHERE post_id = ?",
				owner.userId(), POST_ID);
		mockMvc.perform(report(reporter, "COMMUNITY_POST", POST_ID, "SPAM")).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("탈퇴 판매자의 게시글은 공개되지 않으므로 새 신고 대상도 아니다")
	void withdrawnSellerListingCannotBeReported() throws Exception {
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?",
				owner.userId());
		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "SPAM")).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("채팅에 참여하지 않은 회원이 메시지를 신고하면 존재를 드러내지 않고 404다")
	void nonParticipantCannotReportMessage() throws Exception {
		mockMvc.perform(report(outsider, "MESSAGE", MESSAGE_ID, "SPAM"))
				.andExpect(status().isNotFound());

		mockMvc.perform(report(reporter, "MESSAGE", MESSAGE_ID, "SPAM"))
				.andExpect(status().isCreated());
	}

	@ParameterizedTest(name = "{0} + {2}")
	@CsvSource({ "LISTING, 101, NO_SHOW", "MESSAGE, 501, FRAUD_SUSPICION", "COMMUNITY_POST, 301, PROHIBITED_ITEM",
			"COMMUNITY_COMMENT, 401, FALSE_INFO" })
	@DisplayName("대상 유형에 허용되지 않는 사유는 400이다")
	void reasonNotAllowed(String targetType, long targetId, String reasonCode) throws Exception {
		mockMvc.perform(report(reporter, targetType, targetId, reasonCode))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("대상 유형에 허용되지 않는 신고 사유입니다."));
	}

	@Test
	@DisplayName("같은 게시글·사유의 미처리 신고가 있으면 409다")
	void duplicateContentReport() throws Exception {
		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION"))
				.andExpect(status().isCreated());

		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION"))
				.andExpect(status().isConflict());
		mockMvc.perform(report(reporter, "LISTING", LISTING_ID, "SPAM"))
				.andExpect(status().isCreated());
	}

	// --- 관리자 목록 요약 ---

	@Test
	@DisplayName("targetSummary는 게시글·커뮤니티 글 제목, 메시지·댓글 내용 일부다. 숨긴 대상도 요약되고 행이 없으면 null이다")
	void targetSummaries() throws Exception {
		long listing = submit(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION");
		long message = submit(reporter, "MESSAGE", MESSAGE_ID, "ABUSIVE_BEHAVIOR");
		long post = submit(reporter, "COMMUNITY_POST", POST_ID, "SPAM");
		long comment = submit(reporter, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM");
		long gone = insertReport(reporter.userId(), "LISTING", 999L, "SPAM");
		jdbcTemplate.update("UPDATE community_posts SET status = 'HIDDEN' WHERE post_id = ?", POST_ID);
		jdbcTemplate.update("UPDATE listings SET status = 'HIDDEN' WHERE listing_id = ?", LISTING_ID);
		jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE message_id = ?", MESSAGE_ID);
		jdbcTemplate.update("UPDATE community_comments SET status = 'HIDDEN' WHERE comment_id = ?", COMMENT_ID);

		JsonNode page = readJson(mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", admin.bearer()))
				.andExpect(status().isOk())
				.andReturn());
		Map<Long, JsonNode> items = new LinkedHashMap<>();
		page.get("items").forEach(item -> items.put(item.get("reportId").asLong(), item.get("targetSummary")));

		assertThat(items.keySet()).containsExactly(gone, comment, post, message, listing);
		assertThat(items.get(listing).asString()).isEqualTo("아이패드 프로 11인치");
		assertThat(items.get(post).asString()).isEqualTo("동네 맛집 추천합니다");
		assertThat(items.get(comment).asString()).isEqualTo("광고 댓글입니다");
		assertThat(items.get(message).asString())
				.isEqualTo(LONG_MESSAGE.substring(0, LONG_MESSAGE.offsetByCodePoints(0, 50)) + "…");
		assertThat(items.get(gone).isNull()).isTrue();
	}

	@Test
	@DisplayName("targetType 필터로 메시지 신고만 볼 수 있다")
	void filterByMessage() throws Exception {
		submit(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION");
		long message = submit(reporter, "MESSAGE", MESSAGE_ID, "ABUSIVE_BEHAVIOR");

		mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", admin.bearer())
						.param("targetType", "MESSAGE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(message));
	}

	// --- 콘텐츠 조치 ---

	@ParameterizedTest(name = "{1}")
	@CsvSource({
			"LISTING, HIDE_LISTING, 101, FRAUD_SUSPICION, hideListing, ADMIN_LISTING_HIDE, LISTING, 게시글 숨김 조치",
			"LISTING, DELETE_LISTING, 101, FRAUD_SUSPICION, deleteListing, ADMIN_LISTING_DELETE, LISTING, 게시글 삭제 조치",
			"COMMUNITY_POST, HIDE_COMMUNITY_POST, 301, SPAM, hideCommunityPost, COMMUNITY_POST_HIDE, COMMUNITY_POST, "
					+ "게시글 숨김 조치",
			"COMMUNITY_COMMENT, HIDE_COMMUNITY_COMMENT, 401, SPAM, hideCommunityComment, COMMUNITY_COMMENT_HIDE, "
					+ "COMMUNITY_COMMENT, 댓글 숨김 조치" })
	@DisplayName("콘텐츠 조치는 신고 처리 트랜잭션 안에서 포트를 부르고, 조치 감사와 REPORT_HANDLE을 남기며 요약을 알린다")
	void contentAction(String targetType, String action, long targetId, String reasonCode, String portMethod,
			String auditAction, String auditTarget, String notificationBody) throws Exception {
		long reportId = submit(reporter, targetType, targetId, reasonCode);

		mockMvc.perform(handle(reportId, "RESOLVED", "운영 정책 위반", action))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"));

		assertThat(contentModeration.calls()).containsExactly(
				new Call(portMethod, targetId, admin.userId(), "운영 정책 위반", true));

		List<Map<String, Object>> audits = audits();
		assertThat(audits).extracting(audit -> audit.get("action")).containsExactly(auditAction, "REPORT_HANDLE");
		assertThat(audits.get(0)).containsEntry("actor_id", admin.userId()).containsEntry("target_type", auditTarget)
				.containsEntry("target_id", targetId).containsEntry("result", "SUCCESS");
		JsonNode actionDetail = detail(audits.get(0));
		assertThat(actionDetail.get("reportId").asLong()).isEqualTo(reportId);
		assertThat(actionDetail.get("reason").asString()).isEqualTo("운영 정책 위반");
		assertThat(actionDetail.get("changed").asBoolean()).isTrue();
		JsonNode handleDetail = detail(audits.get(1));
		assertThat(handleDetail.get("action").asString()).isEqualTo(action);
		assertThat(handleDetail.get("targetType").asString()).isEqualTo(targetType);

		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("user_id", reporter.userId()).containsEntry("body", notificationBody)
				.containsEntry("target_type", "REPORT").containsEntry("target_id", reportId));
		assertThat(userStatus(owner.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("같은 댓글을 두 번 숨겨도 실제 숨김(commentCount 감소)은 한 번이고 두 번째 감사는 changed=false다")
	void hidingSameCommentTwiceIsIdempotent() throws Exception {
		long first = submit(reporter, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM");
		long second = submit(outsider, "COMMUNITY_COMMENT", COMMENT_ID, "SPAM");

		mockMvc.perform(handle(first, "RESOLVED", "광고", "HIDE_COMMUNITY_COMMENT"))
				.andExpect(status().isOk());
		mockMvc.perform(handle(second, "RESOLVED", "광고", "HIDE_COMMUNITY_COMMENT"))
				.andExpect(status().isOk());

		assertThat(contentModeration.calls()).hasSize(2);
		assertThat(contentModeration.appliedCount()).isEqualTo(1);
		List<Map<String, Object>> hides = audits().stream()
				.filter(audit -> "COMMUNITY_COMMENT_HIDE".equals(audit.get("action"))).toList();
		assertThat(hides).extracting(audit -> detail(audit).get("changed").asBoolean()).containsExactly(true, false);
		assertThat(notifications()).extracting(notification -> notification.get("user_id"))
				.containsExactly(reporter.userId(), outsider.userId());
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "LISTING, 101, FRAUD_SUSPICION", "MESSAGE, 501, ABUSIVE_BEHAVIOR", "COMMUNITY_POST, 301, SPAM",
			"COMMUNITY_COMMENT, 401, SPAM" })
	@DisplayName("콘텐츠 신고의 SUSPEND_USER는 신고자가 아니라 작성자를 정지하고 포트는 부르지 않는다")
	void suspendContentOwner(String targetType, long targetId, String reasonCode) throws Exception {
		long reportId = submit(reporter, targetType, targetId, reasonCode);

		mockMvc.perform(handle(reportId, "RESOLVED", "반복 위반", "SUSPEND_USER"))
				.andExpect(status().isOk());

		assertThat(userStatus(owner.userId())).isEqualTo("SUSPENDED");
		assertThat(userStatus(reporter.userId())).isEqualTo("ACTIVE");
		assertThat(contentModeration.calls()).isEmpty();
		List<Map<String, Object>> audits = audits();
		assertThat(audits).extracting(audit -> audit.get("action")).containsExactly("USER_SUSPEND", "REPORT_HANDLE");
		assertThat(audits.get(0)).containsEntry("target_id", owner.userId());
		assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM user_status_histories", Long.class))
				.isEqualTo(owner.userId());
		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("body", "이용정지 조치"));
	}

	@Test
	@DisplayName("대상 행이 없어 작성자를 알 수 없으면 SUSPEND_USER는 404다")
	void suspendUnknownOwnerIsNotFound() throws Exception {
		long reportId = insertReport(reporter.userId(), "MESSAGE", 777L, "SPAM");

		mockMvc.perform(handle(reportId, "RESOLVED", "반복 위반", "SUSPEND_USER"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("정지할 회원을 찾을 수 없습니다."));

		assertReportUnchanged(reportId);
	}

	@ParameterizedTest(name = "{0} + {3}")
	@CsvSource({ "LISTING, 101, SPAM, HIDE_COMMUNITY_POST", "LISTING, 101, SPAM, HIDE_COMMUNITY_COMMENT",
			"MESSAGE, 501, SPAM, HIDE_LISTING", "MESSAGE, 501, SPAM, DELETE_LISTING",
			"COMMUNITY_POST, 301, SPAM, HIDE_LISTING", "COMMUNITY_POST, 301, SPAM, HIDE_COMMUNITY_COMMENT",
			"COMMUNITY_COMMENT, 401, SPAM, HIDE_COMMUNITY_POST", "COMMUNITY_COMMENT, 401, SPAM, DELETE_LISTING" })
	@DisplayName("대상과 맞지 않는 조치는 400이고 포트를 부르지 않는다")
	void mismatchedAction(String targetType, long targetId, String reasonCode, String action) throws Exception {
		long reportId = submit(reporter, targetType, targetId, reasonCode);

		mockMvc.perform(handle(reportId, "RESOLVED", "처리", action))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("신고 대상에 맞지 않는 조치입니다."));

		assertThat(contentModeration.calls()).isEmpty();
		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("포트가 실패하면 그 오류로 응답하고 신고 처리·감사·알림이 남지 않는다")
	void portFailureRollsBack() throws Exception {
		long reportId = submit(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION");
		contentModeration.failWith(new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다."));

		mockMvc.perform(handle(reportId, "RESOLVED", "허위 매물", "DELETE_LISTING"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("게시글을 찾을 수 없습니다."));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("콘텐츠 신고를 반려하면 포트를 부르지 않고 반려 알림만 간다")
	void rejectContentReport() throws Exception {
		long reportId = submit(reporter, "LISTING", LISTING_ID, "FRAUD_SUSPICION");

		mockMvc.perform(handle(reportId, "REJECTED", "정상 게시글", null))
				.andExpect(status().isOk());

		assertThat(contentModeration.calls()).isEmpty();
		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("body", "신고가 반려되었습니다"));
	}

	@Test
	@DisplayName("검토 시작 응답의 handledAt은 null이다")
	void startReviewOfContentReport() throws Exception {
		long reportId = submit(reporter, "MESSAGE", MESSAGE_ID, "ABUSIVE_BEHAVIOR");

		mockMvc.perform(handle(reportId, "IN_REVIEW", null, null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.handledAt").value(nullValue()));
	}

	// --- helpers ---

	private void seedRealTargets() {
		Long categoryId = jdbcTemplate.queryForObject("SELECT category_id FROM categories ORDER BY category_id LIMIT 1",
				Long.class);
		jdbcTemplate.update("""
				INSERT INTO listings (listing_id, seller_id, category_id, title, description, price,
				                     item_condition, trade_method)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, '신고 테스트 게시글', 10000, 'USED', 'DIRECT')
				""", LISTING_ID, owner.userId(), categoryId, "아이패드 프로 11인치");
		jdbcTemplate.update("""
				INSERT INTO listings (listing_id, seller_id, category_id, title, description, price,
				                     item_condition, trade_method)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, '신고 테스트 게시글', 10000, 'USED', 'DIRECT')
				""", OWN_LISTING_ID, reporter.userId(), categoryId, "내가 올린 게시글");
		Long roomId = jdbcTemplate.queryForObject("""
				INSERT INTO chat_rooms (listing_id, seller_id, buyer_id)
				VALUES (?, ?, ?) RETURNING chat_room_id
				""", Long.class, LISTING_ID, owner.userId(), reporter.userId());
		jdbcTemplate.update("""
				INSERT INTO messages (message_id, chat_room_id, sender_id, content)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)
				""", MESSAGE_ID, roomId, owner.userId(), LONG_MESSAGE);
		jdbcTemplate.update("""
				INSERT INTO messages (message_id, chat_room_id, sender_id, content)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)
				""", OWN_MESSAGE_ID, roomId, reporter.userId(), "내가 보낸 메시지");
		jdbcTemplate.update("""
				INSERT INTO community_posts (post_id, author_id, category, title, content)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, 'GENERAL', ?, '커뮤니티 신고 테스트 게시글입니다')
				""", POST_ID, owner.userId(), "동네 맛집 추천합니다");
		jdbcTemplate.update("""
				INSERT INTO community_comments (comment_id, post_id, author_id, content)
				OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, '광고 댓글입니다')
				""", COMMENT_ID, POST_ID, owner.userId());
	}

	private MockHttpServletRequestBuilder report(Member from, String targetType, long targetId, String reasonCode) {
		return json(post("/api/v1/reports"), Map.of("targetType", targetType, "targetId", targetId,
				"reasonCode", reasonCode)).header("Authorization", from.bearer());
	}

	private long submit(Member from, String targetType, long targetId, String reasonCode) throws Exception {
		MvcResult result = mockMvc.perform(report(from, targetType, targetId, reasonCode))
				.andExpect(status().isCreated())
				.andReturn();
		return readJson(result).get("reportId").asLong();
	}

	private long insertReport(long reporterId, String targetType, long targetId, String reasonCode) {
		return jdbcTemplate.queryForObject("INSERT INTO reports (reporter_id, target_type, target_id, reason_code) "
				+ "VALUES (?, ?, ?, ?) RETURNING report_id", Long.class, reporterId, targetType, targetId, reasonCode);
	}

	private MockHttpServletRequestBuilder handle(long reportId, String status, String resolution, String action) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		if (resolution != null) {
			body.put("resolution", resolution);
		}
		if (action != null) {
			body.put("action", action);
		}
		return json(patch("/api/v1/admin/reports/" + reportId), body).header("Authorization", admin.bearer());
	}

	private long reportCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM reports", Long.class);
	}

	private void assertReportUnchanged(long reportId) {
		assertThat(jdbcTemplate.queryForMap("SELECT status, handled_by, handled_at, resolution FROM reports "
				+ "WHERE report_id = ?", reportId)).containsEntry("status", "RECEIVED")
				.containsEntry("handled_by", null).containsEntry("handled_at", null).containsEntry("resolution", null);
		assertThat(audits()).isEmpty();
		assertThat(notifications()).isEmpty();
		assertThat(userStatus(owner.userId())).isEqualTo("ACTIVE");
	}

	/** 가입·로그인 감사(AUTH_*)는 뺀다 */
	private List<Map<String, Object>> audits() {
		return jdbcTemplate.queryForList("SELECT action, actor_id, target_type, target_id, result, detail::text AS "
				+ "detail FROM audit_logs WHERE action NOT LIKE 'AUTH%' ORDER BY audit_log_id");
	}

	private JsonNode detail(Map<String, Object> audit) {
		return objectMapper.readTree((String) audit.get("detail"));
	}

	private List<Map<String, Object>> notifications() {
		return jdbcTemplate.queryForList("SELECT user_id, type, body, target_type, target_id FROM notifications "
				+ "ORDER BY notification_id");
	}

	private String userStatus(long userId) {
		return jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, userId);
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private Member signup(String email, String nickname) throws Exception {
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"),
						Map.of("email", email, "password", PASSWORD, "nickname", nickname,
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = readJson(result);
		return new Member(json.get("user").get("userId").asLong(), json.get("accessToken").asString(),
				result.getResponse().getCookie("refresh_token").getValue());
	}

	/** 가입 → role=ADMIN → 재발급. 역할은 발급 시점 클레임이다. */
	private Member admin(String email, String nickname) throws Exception {
		Member member = signup(email, nickname);
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", member.userId());
		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", member.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		return new Member(member.userId(), readJson(refreshed).get("accessToken").asString(),
				refreshed.getResponse().getCookie("refresh_token").getValue());
	}

	private record Member(long userId, String accessToken, String refreshToken) {

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

}
