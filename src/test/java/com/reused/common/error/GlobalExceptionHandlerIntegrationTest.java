package com.reused.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.support.ProbeController;
import com.reused.support.ProbeSecurityConfiguration;
import com.reused.user.entity.UserRole;

/**
 * 전역 예외 처리기의 매핑. 실제 엔드포인트가 없는 경우만 테스트 전용 {@link ProbeController}를 쓴다.
 * 어떤 경우에도 응답은 {code, message}이고 내부 타입 이름이 새지 않아야 한다.
 */
@Import({ TestcontainersConfiguration.class, ProbeController.class, ProbeSecurityConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider tokenProvider;

	// --- 경로 변수·쿼리 파라미터 ---

	@Test
	@DisplayName("경로 변수 타입이 틀리면 400이고 파라미터 이름만 알려준다")
	void pathVariableTypeMismatch() throws Exception {
		mockMvc.perform(get("/test/probe/items/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("itemId: 값의 형식이 올바르지 않습니다."))
				.andExpect(content().string(not(containsString("java."))));
	}

	@Test
	@DisplayName("필수 쿼리 파라미터가 빠지면 400이다")
	void missingRequestParameter() throws Exception {
		mockMvc.perform(get("/test/probe/required"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("keyword: 필수 값입니다."));
	}

	@Test
	@DisplayName("메서드 파라미터에 직접 단 제약을 어기면 400이다")
	void handlerMethodValidation() throws Exception {
		mockMvc.perform(get("/test/probe/method-validation").param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));

		mockMvc.perform(get("/test/probe/method-validation").param("size", "100"))
				.andExpect(status().isOk());
	}

	// --- CursorPageRequest 바인딩 ---

	@Test
	@DisplayName("페이지 요청을 생략하면 size 20, 커서 없음이다")
	void pageDefaults() throws Exception {
		mockMvc.perform(get("/test/probe/page"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.cursorId").isEmpty());
	}

	@Test
	@DisplayName("size 경계 1과 100은 통과하고 문서 예시 커서는 해석된다")
	void pageBoundaries() throws Exception {
		mockMvc.perform(get("/test/probe/page").param("size", "1").param("cursor", "eyJpZCI6MTIzfQ=="))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.cursorId").value(123));
		mockMvc.perform(get("/test/probe/page").param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(100));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "101" })
	@DisplayName("size가 1~100 밖이면 잘라내지 않고 400이다")
	void pageSizeOutOfRange(String size) throws Exception {
		mockMvc.perform(get("/test/probe/page").param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("size: ")));
	}

	@Test
	@DisplayName("@ModelAttribute 필드의 타입 변환 실패는 400이고 변환 대상 타입 이름을 노출하지 않는다")
	void modelAttributeTypeMismatch() throws Exception {
		mockMvc.perform(get("/test/probe/page").param("size", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("size: 값의 형식이 올바르지 않습니다."))
				.andExpect(content().string(not(containsString("Integer"))));
	}

	@Test
	@DisplayName("해석할 수 없는 커서는 400이다")
	void invalidCursor() throws Exception {
		mockMvc.perform(get("/test/probe/page").param("cursor", "not-a-cursor!"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
	}

	// --- 경로·메서드·미디어 타입 ---

	@Test
	@DisplayName("인증된 사용자가 없는 경로를 부르면 404 JSON이다")
	void unknownPathIsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/no-such-resource").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("없는 경로라도 토큰이 없으면 보안 필터가 먼저 401을 낸다")
	void unknownPathWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/no-such-resource"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("지원하지 않는 HTTP 메서드는 코드표에 405가 없으므로 400이다")
	void methodNotSupported() throws Exception {
		// DELETE /users/me는 회원 탈퇴로 구현되었다. PUT은 매핑이 없다.
		mockMvc.perform(put("/api/v1/users/me").header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("지원하지 않는 Content-Type은 코드표에 415가 없으므로 400이다")
	void mediaTypeNotSupported() throws Exception {
		mockMvc.perform(post("/api/v1/auth/email/login")
						.contentType(MediaType.TEXT_PLAIN)
						.content("email=user@example.com"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	// --- 동시성·기타 ---

	@Test
	@DisplayName("낙관적 락 충돌은 409 CONFLICT이고 다시 시도하라고 안내한다")
	void optimisticLockConflict() throws Exception {
		mockMvc.perform(get("/test/probe/optimistic-lock"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("다른 요청과 충돌했습니다. 다시 시도해 주세요."))
				.andExpect(content().string(not(containsString("Probe"))));
	}

	@Test
	@DisplayName("비관적 락 획득 실패도 409 CONFLICT다")
	void pessimisticLockConflict() throws Exception {
		mockMvc.perform(get("/test/probe/pessimistic-lock"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("다른 요청과 충돌했습니다. 다시 시도해 주세요."));
	}

	@Test
	@DisplayName("그 밖의 예외는 500이고 예외 메시지를 싣지 않는다")
	void otherExceptionsAreInternalError() throws Exception {
		mockMvc.perform(get("/test/probe/illegal-transaction"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다."))
				.andExpect(content().string(not(containsString("probe internal detail"))));
	}

	private String bearer() {
		return "Bearer " + tokenProvider.issueAccessToken(1L, UserRole.USER);
	}

}
