package com.reused.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 API 통합 테스트의 공통 준비. 회원은 이메일 가입으로 만들고(실제 토큰이 필요할 때) 나머지는 SQL로 넣는다.
 *
 * <p>관리자 토큰은 가입 → {@code role='ADMIN'} → 재발급으로 얻는다. 역할은 발급 시점 클레임이라 재발급이 DB의 역할로
 * 새 Access Token을 만든다.
 */
public final class AdminTestClient {

	public static final String REFRESH_COOKIE = "refresh_token";
	private static final String PASSWORD = "hunter22!pw";

	private final MockMvc mockMvc;
	private final ObjectMapper objectMapper;
	private final JdbcTemplate jdbcTemplate;
	private final StringRedisTemplate redisTemplate;

	public AdminTestClient(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
			StringRedisTemplate redisTemplate) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
		this.jdbcTemplate = jdbcTemplate;
		this.redisTemplate = redisTemplate;
	}

	/** 관리자 API 테스트가 쓰는 테이블을 비우고 Redis를 비운다 */
	public void reset() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, notification_settings, reports, blocks, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	/** 이메일 가입. 가입은 AUTH_SIGNUP 감사 행을 남긴다 */
	public Member signup(String email, String nickname) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"), body))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Member(response.get("user").get("userId").asLong(), response.get("accessToken").asString(),
				result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	/** 가입 → role=ADMIN → 재발급. 반환하는 refreshToken은 회전된 새 토큰이다 */
	public Member signupAdmin(String email, String nickname) throws Exception {
		Member member = signup(email, nickname);
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", member.userId());
		return refresh(member);
	}

	/** 재발급. DB의 현재 역할로 새 Access Token을 받는다 */
	public Member refresh(Member member) throws Exception {
		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, member.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		return new Member(member.userId(),
				objectMapper.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asString(),
				refreshed.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	/** 토큰이 필요 없는 회원. 이메일 가입보다 빠르다 */
	public long insertUser(String nickname) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
	}

	public void withdraw(long userId) {
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now(), nickname = ? "
				+ "WHERE user_id = ?", "탈퇴회원#" + userId, userId);
	}

	/** @param suspendedUntil SQL 식. 예: {@code NULL}, {@code now() + interval '1 day'} */
	public void suspend(long userId, String suspendedUntil) {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = " + suspendedUntil
				+ " WHERE user_id = ?", userId);
	}

	public void insertReport(long reporterId, String targetType, long targetId, String reasonCode, String status) {
		jdbcTemplate.update("INSERT INTO reports (reporter_id, target_type, target_id, reason_code, status) "
				+ "VALUES (?, ?, ?, ?, ?)", reporterId, targetType, targetId, reasonCode, status);
	}

	public Set<String> refreshKeys(long userId) {
		return redisTemplate.keys("reused:auth:refresh:" + userId + ":*");
	}

	public MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	public static String bearer(Member member) {
		return "Bearer " + member.accessToken();
	}

	public record Member(long userId, String accessToken, String refreshToken) {
	}

}
