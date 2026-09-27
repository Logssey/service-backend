package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

/** Real report HTTP flow against temporary PostgreSQL and Redis, without moderation fakes. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReportContentModerationIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private OAuthProviderClient kakaoClient;
	@MockitoBean private AuthMailSender mailSender;
	@MockitoBean private ImageStorage imageStorage;

	private long admin;
	private long seller;
	private long reporter;

	@BeforeEach
	void resetState() {
		jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
		admin = user("moderator", "ADMIN");
		seller = user("seller", "USER");
		reporter = user("reporter", "USER");
	}

	@Test
	void reportHideCanBeRestoredEvenAfterAnotherNoOpHide() throws Exception {
		long listing = listing("ON_SALE");
		long first = report("LISTING", listing);
		handle(first, "HIDE_LISTING").andExpect(status().isOk());
		assertThat(listingStatus(listing)).isEqualTo("HIDDEN");
		mvc.perform(get("/api/v1/listings/{id}", listing)).andExpect(status().isNotFound());

		Map<String, Object> firstAudit = jdbc.queryForMap("""
				SELECT action, detail ->> 'beforeStatus' AS before_status,
				       detail ->> 'afterStatus' AS after_status, detail ->> 'changed' AS changed
				FROM audit_logs WHERE action = 'ADMIN_LISTING_HIDE' AND target_id = ?
				ORDER BY audit_log_id LIMIT 1
				""", listing);
		assertThat(firstAudit).containsEntry("before_status", "ON_SALE")
				.containsEntry("after_status", "HIDDEN").containsEntry("changed", "true");

		long second = report("LISTING", listing);
		handle(second, "HIDE_LISTING").andExpect(status().isOk());
		Map<String, Object> secondAudit = jdbc.queryForMap("""
				SELECT detail ->> 'beforeStatus' AS before_status, detail ->> 'changed' AS changed
				FROM audit_logs WHERE action = 'ADMIN_LISTING_HIDE' AND target_id = ?
				ORDER BY audit_log_id DESC LIMIT 1
				""", listing);
		assertThat(secondAudit).containsEntry("before_status", null).containsEntry("changed", "false");

		mvc.perform(patch("/api/v1/admin/listings/{id}/status", listing)
				.header("Authorization", adminBearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("status", "RESTORE", "reason", "review complete"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ON_SALE"));
		assertThat(listingStatus(listing)).isEqualTo("ON_SALE");
		mvc.perform(get("/api/v1/listings/{id}", listing)).andExpect(status().isOk());
	}

	@Test
	void reportDeleteRespectsActiveTradeThenSoftDeletes() throws Exception {
		long listing = listing("ON_SALE");
		long buyer = user("buyer", "USER");
		long trade = jdbc.queryForObject("""
				INSERT INTO trades(listing_id, seller_id, buyer_id, status)
				VALUES (?, ?, ?, 'REQUESTED') RETURNING trade_id
				""", Long.class, listing, seller, buyer);
		long report = report("LISTING", listing);
		handle(report, "DELETE_LISTING").andExpect(status().isConflict());
		assertThat(listingDeletedAt(listing)).isNull();
		assertThat(reportStatus(report)).isEqualTo("RECEIVED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();

		jdbc.update("UPDATE trades SET status = 'CANCELED' WHERE trade_id = ?", trade);
		handle(report, "DELETE_LISTING").andExpect(status().isOk());
		assertThat(listingDeletedAt(listing)).isNotNull();
		assertThat(jdbc.queryForObject("SELECT deleted_by FROM listings WHERE listing_id = ?", Long.class, listing))
				.isEqualTo(admin);
		assertThat(jdbc.queryForObject("SELECT detail ->> 'changed' FROM audit_logs "
				+ "WHERE action = 'ADMIN_LISTING_DELETE' AND target_id = ?", String.class, listing)).isEqualTo("true");
	}

	@Test
	void reportHidePostRemovesItFromPublicViewsWithoutDeletingComments() throws Exception {
		long post = post();
		long comment = comment(post);
		long report = report("COMMUNITY_POST", post);
		handle(report, "HIDE_COMMUNITY_POST").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT status FROM community_posts WHERE post_id = ?", String.class, post))
				.isEqualTo("HIDDEN");
		assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts WHERE post_id = ?", Integer.class, post))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM community_comments WHERE comment_id = ?", String.class, comment))
				.isEqualTo("PUBLISHED");
		mvc.perform(get("/api/v1/community/posts/{id}", post)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/community/posts/{id}/comments", post)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/community/posts")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void reportHideCommentDecrementsCountOnlyOnce() throws Exception {
		long post = post();
		long comment = comment(post);
		handle(report("COMMUNITY_COMMENT", comment), "HIDE_COMMUNITY_COMMENT").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts WHERE post_id = ?", Integer.class, post))
				.isZero();
		mvc.perform(get("/api/v1/community/posts/{id}/comments", post)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
		handle(report("COMMUNITY_COMMENT", comment), "HIDE_COMMUNITY_COMMENT").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts WHERE post_id = ?", Integer.class, post))
				.isZero();
		assertThat(jdbc.queryForObject("""
				SELECT detail ->> 'changed' FROM audit_logs WHERE action = 'COMMUNITY_COMMENT_HIDE'
				ORDER BY audit_log_id DESC LIMIT 1
				""", String.class)).isEqualTo("false");
	}

	@Test
	void auditFailureRollsBackListingHideAndReport() throws Exception {
		long listing = listing("ON_SALE");
		long report = report("LISTING", listing);
		jdbc.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			handle(report, "HIDE_LISTING").andExpect(status().isInternalServerError());
		}
		finally {
			jdbc.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}
		assertThat(listingStatus(listing)).isEqualTo("ON_SALE");
		assertThat(reportStatus(report)).isEqualTo("RECEIVED");
	}

	private org.springframework.test.web.servlet.ResultActions handle(long reportId, String action) throws Exception {
		return mvc.perform(patch("/api/v1/admin/reports/{id}", reportId)
				.header("Authorization", adminBearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("status", "RESOLVED", "resolution", "reviewed",
						"action", action))));
	}

	private String adminBearer() {
		return "Bearer " + tokens.issueAccessToken(admin, UserRole.ADMIN);
	}

	private long user(String nickname, String role) {
		return jdbc.queryForObject("""
				INSERT INTO users(nickname, role, terms_agreed_at) VALUES (?, ?, now()) RETURNING user_id
				""", Long.class, nickname, role);
	}

	private long listing(String status) {
		return jdbc.queryForObject("""
				INSERT INTO listings(seller_id, category_id, title, description, price, item_condition, trade_method, status)
				VALUES (?, 1, 'Example item', 'Example description', 1000, 'USED', 'DIRECT', ?)
				RETURNING listing_id
				""", Long.class, seller, status);
	}

	private long post() {
		return jdbc.queryForObject("""
				INSERT INTO community_posts(author_id, category, title, content)
				VALUES (?, 'GENERAL', 'Example post', 'Example post content') RETURNING post_id
				""", Long.class, seller);
	}

	private long comment(long postId) {
		long id = jdbc.queryForObject("""
				INSERT INTO community_comments(post_id, author_id, content)
				VALUES (?, ?, 'Example comment') RETURNING comment_id
				""", Long.class, postId, seller);
		jdbc.update("UPDATE community_posts SET comment_count = comment_count + 1 WHERE post_id = ?", postId);
		return id;
	}

	private long report(String targetType, long targetId) {
		return jdbc.queryForObject("""
				INSERT INTO reports(reporter_id, target_type, target_id, reason_code)
				VALUES (?, ?, ?, 'SPAM') RETURNING report_id
				""", Long.class, reporter, targetType, targetId);
	}

	private String listingStatus(long listingId) {
		return jdbc.queryForObject("SELECT status FROM listings WHERE listing_id = ?", String.class, listingId);
	}

	private Timestamp listingDeletedAt(long listingId) {
		return jdbc.queryForObject("SELECT deleted_at FROM listings WHERE listing_id = ?", Timestamp.class, listingId);
	}

	private String reportStatus(long reportId) {
		return jdbc.queryForObject("SELECT status FROM reports WHERE report_id = ?", String.class, reportId);
	}

}
