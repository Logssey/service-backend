package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.support.AdminTestClient;
import com.reused.user.entity.AuthProvider;

/**
 * 소셜 온보딩의 선택 이메일(ADR-019). 실제 Postgres·Redis 위에서 돌고 카카오 호출과 메일 발송만 대역으로 바꾼다.
 * 카카오 대역은 인가 코드를 그대로 회원번호로 돌려주어 한 테스트에서 카카오 계정을 여럿 만든다.
 *
 * <p>입력(선택·동의·형식), 커밋 뒤 확인 메일, 소유 확인, 인증 완료 이메일의 소셜 범위 유일성, LOCAL 전용 기능과의 격리,
 * 본인·공개 응답, 탈퇴, 감사 기록, 인증 수단 저장 실패의 구분, 제약 위반 로그의 이메일 비노출을 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SocialEmailIntegrationTest {

	private static final String EMAIL = "kakao@example.com";
	private static final String PASSWORD = "hunter22!pw";
	private static final String CONSENT_REQUIRED = "이메일을 등록하려면 이메일 수집·이용에 동의해야 합니다.";
	private static final String VERIFIED_ELSEWHERE = "이미 다른 계정에서 인증된 이메일입니다.";
	private static final String REJECT_SETTINGS_CONSTRAINT = "ck_notification_settings_test_reject";
	private static final String REJECT_SOCIAL_EMAIL_CONSTRAINT = "ck_user_identities_test_reject_social_email";
	private static final String SOCIAL_VERIFIED_EMAIL_INDEX = "uq_user_identities_email_social_verified";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		// 인가 코드를 회원번호로 쓴다. 코드마다 다른 카카오 계정이 된다.
		given(kakaoOAuthClient.fetchProviderUserId(any(), any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	// --- 온보딩 입력 ---

	@Test
	@DisplayName("이메일과 선택 동의로 온보딩하면 201이고 정규화한 이메일과 동의 시각이 KAKAO 인증 수단에 저장되며, 커밋 뒤 확인 메일이 1회 나간다")
	void onboardingWithEmailStoresEmailAndSendsVerificationAfterCommit() throws Exception {
		List<Boolean> transactionActiveAtSend = new CopyOnWriteArrayList<>();
		List<Long> committedRowsAtSend = new CopyOnWriteArrayList<>();
		willAnswer(invocation -> {
			transactionActiveAtSend.add(TransactionSynchronizationManager.isActualTransactionActive());
			// 다른 스레드(다른 커넥션)에서 세면 커밋된 행만 보인다.
			committedRowsAtSend.add(CompletableFuture.supplyAsync(() -> count(
					"SELECT count(*) FROM user_identities WHERE provider = 'KAKAO' AND email = ?", EMAIL))
					.get(10, TimeUnit.SECONDS));
			return null;
		}).given(mailSender).sendVerificationCode(eq(EMAIL), any());

		mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", "Kakao@Example.COM", true))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.email").doesNotExist());

		Map<String, Object> identity = jdbcTemplate.queryForMap("""
				SELECT i.provider, i.email, i.password_hash, i.email_verified_at, i.email_consent_at,
				       i.email_consent_at = u.terms_agreed_at AS consent_with_terms
				FROM user_identities i JOIN users u ON u.user_id = i.user_id""");
		assertThat(identity.get("provider")).isEqualTo("KAKAO");
		assertThat(identity.get("email")).isEqualTo(EMAIL); // 소문자로 저장
		assertThat(identity.get("password_hash")).isNull();
		assertThat(identity.get("email_verified_at")).isNull();
		assertThat(identity.get("email_consent_at")).isNotNull();
		assertThat(identity.get("consent_with_terms")).isEqualTo(true);

		verify(mailSender, times(1)).sendVerificationCode(eq(EMAIL), any());
		assertThat(transactionActiveAtSend).containsExactly(false);
		assertThat(committedRowsAtSend).containsExactly(1L);
	}

	@ParameterizedTest(name = "email={0}, 동의={1}")
	@CsvSource({ "OMIT,", "NULL, true", "EMPTY, false" })
	@DisplayName("이메일을 생략·null·빈 문자열로 보내면 이메일 없이 가입되고, 동의 값은 무시되어 저장되지 않으며 메일은 나가지 않는다")
	void onboardingWithoutEmail(String emailCase, Boolean agreed) throws Exception {
		Map<String, Object> extra = new LinkedHashMap<>();
		switch (emailCase) {
			case "NULL" -> extra.put("email", null);
			case "EMPTY" -> extra.put("email", "");
			default -> {
			}
		}
		if (agreed != null) {
			extra.put("emailCollectionAgreed", agreed);
		}

		mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", extra)).andExpect(status().isCreated());

		Map<String, Object> identity = jdbcTemplate.queryForMap("SELECT email, email_consent_at FROM user_identities");
		assertThat(identity.get("email")).isNull();
		assertThat(identity.get("email_consent_at")).isNull();
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	@ParameterizedTest(name = "동의={0}")
	@ValueSource(strings = { "OMIT", "NULL", "FALSE" })
	@DisplayName("이메일을 입력했는데 선택 동의가 true가 아니면 400이고 가입·메일이 없다. 같은 signupToken으로 동의해 다시 보내면 201이다")
	void emailRequiresConsent(String consentCase) throws Exception {
		String signupToken = signupToken("kakao-1");
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("email", EMAIL);
		switch (consentCase) {
			case "NULL" -> extra.put("emailCollectionAgreed", null);
			case "FALSE" -> extra.put("emailCollectionAgreed", false);
			default -> {
			}
		}

		mockMvc.perform(onboarding(signupToken, "카카오유저", extra))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(CONSENT_REQUIRED));
		assertThat(count("SELECT count(*) FROM users")).isZero();
		verify(mailSender, never()).sendVerificationCode(any(), any());

		mockMvc.perform(onboarding(signupToken, "카카오유저", EMAIL, true)).andExpect(status().isCreated());
	}

	static Stream<String> malformedEmails() {
		// 로컬파트 64자 + 도메인 190자 = 255자. 형식은 맞고 길이만 한도(254)를 넘는다.
		String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com";
		return Stream.of("not-an-email", "   ", " kakao@example.com ", tooLong);
	}

	@ParameterizedTest
	@MethodSource("malformedEmails")
	@DisplayName("이메일 형식·길이 위반(공백만, 앞뒤 공백, 255자 포함)은 400이고 가입·메일이 없다. 같은 signupToken으로 고쳐 보내면 201이다")
	void malformedEmailIsRejected(String email) throws Exception {
		String signupToken = signupToken("kakao-1");

		mockMvc.perform(onboarding(signupToken, "카카오유저", email, true))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(count("SELECT count(*) FROM users")).isZero();
		verify(mailSender, never()).sendVerificationCode(any(), any());

		mockMvc.perform(onboarding(signupToken, "카카오유저", EMAIL, true)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("254자 이메일은 그대로 저장된다(길이 경계)")
	void longestEmailIsAccepted() throws Exception {
		String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
		assertThat(email).hasSize(254);

		mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", email, true)).andExpect(status().isCreated());

		assertThat(jdbcTemplate.queryForObject("SELECT email FROM user_identities", String.class)).isEqualTo(email);
	}

	@Test
	@DisplayName("확인 메일 발송이 실패해도 가입은 201로 남고, 재발송으로 받은 코드로 확인할 수 있다")
	void mailFailureKeepsSignup() throws Exception {
		willThrow(new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR)).willDoNothing()
				.given(mailSender).sendVerificationCode(any(), any());

		Tokens tokens = onboard("kakao-1", "카카오유저", EMAIL);

		assertThat(count("SELECT count(*) FROM user_identities WHERE provider = 'KAKAO' AND email = ?", EMAIL))
				.isEqualTo(1);
		clearResendGap(tokens.userId());
		mockMvc.perform(resend(tokens)).andExpect(status().isNoContent());
		mockMvc.perform(confirm(tokens, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("커밋에 실패한 온보딩은 이메일을 입력했어도 확인 메일을 보내지 않는다")
	void rolledBackOnboardingSendsNoMail() throws Exception {
		// 알림 설정 INSERT는 커밋할 때 나간다. 그 INSERT를 거부해 가입 본문이 다 실행된 뒤 롤백되게 한다.
		jdbcTemplate.execute("ALTER TABLE notification_settings ADD CONSTRAINT " + REJECT_SETTINGS_CONSTRAINT
				+ " CHECK (false) NOT VALID");
		try {
			mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", EMAIL, true))
					.andExpect(status().isInternalServerError());
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE notification_settings DROP CONSTRAINT " + REJECT_SETTINGS_CONSTRAINT);
		}

		assertThat(count("SELECT count(*) FROM users")).isZero();
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	// --- 소유 확인 ---

	@Test
	@DisplayName("카카오 계정도 가입 메일의 코드로 확인하면 204이고 email_verified_at이 기록된다. 코드는 재사용할 수 없고 확인 뒤 재발송은 409다")
	void kakaoEmailCanBeVerified() throws Exception {
		Tokens tokens = onboard("kakao-1", "카카오유저", EMAIL);
		String code = lastCodeSentTo(EMAIL);

		// 가입 직후 발송이 재발송 간격(60초)을 건다.
		mockMvc.perform(resend(tokens))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		mockMvc.perform(confirm(tokens, code)).andExpect(status().isNoContent());

		assertThat(verifiedAtOf(tokens.userId())).isNotNull();
		mockMvc.perform(confirm(tokens, code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		clearResendGap(tokens.userId());
		mockMvc.perform(resend(tokens))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("이미 소유 확인이 완료된 이메일입니다."));
	}

	@Test
	@DisplayName("간격이 지나면 카카오 계정의 재발송도 204이고 새 코드가 나가며, 새 코드로 확인된다")
	void resendIssuesNewCode() throws Exception {
		Tokens tokens = onboard("kakao-1", "카카오유저", EMAIL);
		clearResendGap(tokens.userId());

		mockMvc.perform(resend(tokens)).andExpect(status().isNoContent());

		verify(mailSender, times(2)).sendVerificationCode(eq(EMAIL), any());
		mockMvc.perform(confirm(tokens, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("카카오 계정도 코드당 5회를 넘기면 429이고 그 코드는 폐기되어 정답도 통하지 않는다")
	void attemptsAreLimitedPerCode() throws Exception {
		Tokens tokens = onboard("kakao-1", "카카오유저", EMAIL);
		String code = lastCodeSentTo(EMAIL);
		String wrong = code.equals("000000") ? "000001" : "000000";

		for (int i = 0; i < 5; i++) {
			mockMvc.perform(confirm(tokens, wrong)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(confirm(tokens, wrong))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));

		mockMvc.perform(confirm(tokens, code)).andExpect(status().isBadRequest());
		assertThat(verifiedAtOf(tokens.userId())).isNull();
	}

	@Test
	@DisplayName("이메일 없이 온보딩한 카카오 계정은 재발송이 409이고, 임의 코드로 확인하면 400이며 확인 시각이 남지 않는다")
	void kakaoWithoutEmailHasNothingToVerify() throws Exception {
		Tokens tokens = onboard("kakao-1", "카카오유저", null);

		mockMvc.perform(resend(tokens))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("등록된 이메일이 없습니다."));
		mockMvc.perform(confirm(tokens, "000000"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(verifiedAtOf(tokens.userId())).isNull();
		verify(mailSender, never()).sendVerificationCode(any(), any());
	}

	// --- 인증을 마친 소셜 이메일은 한 계정만 ---

	@Test
	@DisplayName("다른 카카오 계정이 확인까지 마친 주소로도 온보딩은 201이고 미확인으로 저장되어 확인 메일이 나간다. 409는 코드 확인에서만 난다")
	void onboardingDoesNotCheckEmailVerifiedElsewhere() throws Exception {
		Tokens first = onboard("kakao-1", "카카오하나", EMAIL);
		mockMvc.perform(confirm(first, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		// 입력 단계에서는 중복을 검사하지 않는다. 온보딩 응답은 이 주소의 가입 여부를 드러내지 않는다(NFR-AUTH-018).
		Tokens second = onboard("kakao-2", "카카오둘", EMAIL);

		assertThat(jdbcTemplate.queryForObject("SELECT email FROM user_identities WHERE user_id = ?", String.class,
				second.userId())).isEqualTo(EMAIL);
		assertThat(verifiedAtOf(second.userId())).isNull();
		List<String> codes = codesSentTo(EMAIL);
		assertThat(codes).hasSize(2);
		mockMvc.perform(confirm(second, codes.get(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(VERIFIED_ELSEWHERE));
		assertThat(verifiedAtOf(second.userId())).isNull();
	}

	@Test
	@DisplayName("같은 이메일을 입력한 카카오 계정 둘은 모두 가입된다. 한쪽이 확인을 마치면 다른 쪽의 올바른 코드는 409이고 그 코드는 소비되지 않는다")
	void secondVerificationOfSameEmailIsConflict() throws Exception {
		Tokens first = onboard("kakao-1", "카카오하나", EMAIL);
		Tokens second = onboard("kakao-2", "카카오둘", EMAIL);
		List<String> codes = codesSentTo(EMAIL);
		assertThat(codes).hasSize(2);

		mockMvc.perform(confirm(first, codes.get(0))).andExpect(status().isNoContent());

		// 코드가 틀리면 다른 계정의 확인 여부를 드러내지 않고 여느 때처럼 400이다(NFR-AUTH-018).
		String wrong = codes.get(1).equals("000000") ? "000001" : "000000";
		mockMvc.perform(confirm(second, wrong))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(confirm(second, codes.get(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value(VERIFIED_ELSEWHERE));
		assertThat(verifiedAtOf(second.userId())).isNull();

		// 409는 코드를 소비하지 않는다. 먼저 확인한 계정이 탈퇴해 주소가 풀리면 같은 코드로 확인된다.
		mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(first)))
				.andExpect(status().isNoContent());
		mockMvc.perform(confirm(second, codes.get(1))).andExpect(status().isNoContent());
		assertThat(verifiedAtOf(second.userId())).isNotNull();
	}

	@Test
	@DisplayName("다른 카카오 계정이 이미 확인한 주소여도 재발송은 막지 않는다. 판정은 코드 확인에서 한다")
	void resendIsAllowedEvenIfVerifiedElsewhere() throws Exception {
		Tokens first = onboard("kakao-1", "카카오하나", EMAIL);
		Tokens second = onboard("kakao-2", "카카오둘", EMAIL);
		mockMvc.perform(confirm(first, codesSentTo(EMAIL).get(0))).andExpect(status().isNoContent());
		clearResendGap(second.userId());

		mockMvc.perform(resend(second)).andExpect(status().isNoContent());

		verify(mailSender, times(3)).sendVerificationCode(eq(EMAIL), any());
		mockMvc.perform(confirm(second, lastCodeSentTo(EMAIL)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(VERIFIED_ELSEWHERE));
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class) // 메서드에 걸어 이 테스트의 출력만 잡는다
	@DisplayName("사전 검사와 저장 사이에 같은 이메일의 확인이 먼저 커밋되면 부분 UNIQUE 인덱스가 막아 409다. 제약 위반 로그에 이메일이 남지 않는다")
	void concurrentVerificationIsConflict(CapturedOutput output) throws Exception {
		Tokens first = onboard("kakao-1", "카카오하나", EMAIL);
		Tokens second = onboard("kakao-2", "카카오둘", EMAIL);
		String secondCode = codesSentTo(EMAIL).get(1);
		long firstIdentityId = identityIdOf(first.userId());

		CountDownLatch verified = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		// 앞 계정의 확인이 커밋 직전에 멈춘다. 커밋 전이라 뒤 계정의 사전 검사에는 보이지 않는다.
		CompletableFuture<Void> rival = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					jdbcTemplate.update("UPDATE user_identities SET email_verified_at = now() WHERE identity_id = ?",
							firstIdentityId);
					verified.countDown();
					awaitQuietly(commit);
				}));
		assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();

		// 뒤 계정의 UPDATE는 부분 UNIQUE 인덱스에서 앞 트랜잭션을 기다린다.
		CompletableFuture<MvcResult> request = CompletableFuture.supplyAsync(() -> perform(confirm(second, secondCode)));
		awaitLockWait();
		commit.countDown();
		rival.join();

		MvcResult result = request.join();
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asString())
				.isEqualTo(VERIFIED_ELSEWHERE);
		assertThat(verifiedAtOf(second.userId())).isNull();
		assertThat(count("SELECT count(*) FROM user_identities WHERE email_verified_at IS NOT NULL")).isEqualTo(1);

		// Hibernate는 번역 전에 SQL 오류를 WARN으로 남긴다. 제약 이름은 남되 드라이버가 서버 DETAIL(행 값)을 빼므로
		// 주소는 남지 않는다(NFR-LOG-003). 제약 이름 단언은 로그를 실제로 잡았는지 확인한다.
		assertThat(output.getAll()).contains(SOCIAL_VERIFIED_EMAIL_INDEX).doesNotContain(EMAIL);
	}

	// --- LOCAL과의 관계 (제공자가 다르면 별개 계정) ---

	@Test
	@DisplayName("LOCAL 계정의 이메일로 카카오 온보딩을 해도 201이고, 두 계정 모두 같은 주소의 소유 확인을 마칠 수 있다")
	void localAndKakaoCanShareEmail() throws Exception {
		Tokens local = signupLocal(EMAIL, "이메일유저");
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		List<String> codes = codesSentTo(EMAIL);

		mockMvc.perform(confirm(local, codes.get(0))).andExpect(status().isNoContent());
		mockMvc.perform(confirm(kakao, codes.get(1))).andExpect(status().isNoContent());

		assertThat(count("SELECT count(*) FROM user_identities WHERE email = ? AND email_verified_at IS NOT NULL", EMAIL))
				.isEqualTo(2);
		// LOCAL의 이메일은 가입 필수 항목이라 선택 동의 시각을 쓰지 않는다.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT email_consent_at FROM user_identities WHERE provider = 'LOCAL'", Object.class)).isNull();
	}

	@Test
	@DisplayName("카카오 계정이 확인까지 마친 주소로도 이메일 가입은 201이고 LOCAL의 소유 확인도 204다. LOCAL끼리는 여전히 가입 단계에서 409다")
	void emailSignupIgnoresKakaoEmail() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		Tokens local = signupLocal(EMAIL, "이메일유저");
		// 인증 완료 이메일의 유일성은 소셜 인증 수단 사이에서만 따진다. LOCAL의 확인은 그 판정 대상이 아니다.
		mockMvc.perform(confirm(local, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());
		assertThat(verifiedAtOf(local.userId())).isNotNull();
		assertThat(count("SELECT count(*) FROM user_identities WHERE email = ? AND email_verified_at IS NOT NULL", EMAIL))
				.isEqualTo(2);

		mockMvc.perform(localSignup(EMAIL, "다른이메일유저"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("이미 가입된 이메일입니다."));
	}

	// --- 로그인·재설정·비밀번호 변경은 LOCAL 전용 ---

	@Test
	@DisplayName("카카오 계정에만 있는 이메일(확인 완료)로는 재설정 메일이 나가지 않고 재설정 확정은 400, 이메일 로그인은 없는 주소와 같은 401이다")
	void kakaoEmailIsNotALoginOrResetTarget() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", EMAIL)))
				.andExpect(status().isNoContent());
		verify(mailSender, never()).sendPasswordResetCode(any(), any());
		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", "000000", "newPassword", "new-password-99")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		String kakaoEmailLogin = mockMvc.perform(login(EMAIL, PASSWORD))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		String unknownEmailLogin = mockMvc.perform(login("nobody@example.com", PASSWORD))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		assertThat(kakaoEmailLogin).isEqualTo(unknownEmailLogin);
		assertThat(jdbcTemplate.queryForList(
				"SELECT detail->>'reason' FROM audit_logs WHERE action = 'AUTH_LOGIN' ORDER BY audit_log_id", String.class))
				.containsExactly("UNKNOWN_ACCOUNT", "UNKNOWN_ACCOUNT");
	}

	@Test
	@DisplayName("LOCAL과 카카오가 같은 이메일을 가지면 재설정 메일은 1회만 나가고 LOCAL 비밀번호만 바뀌며 카카오에는 비밀번호가 생기지 않는다")
	void resetTouchesOnlyLocalIdentity() throws Exception {
		Tokens local = signupLocal(EMAIL, "이메일유저");
		onboard("kakao-1", "카카오유저", EMAIL);
		clearResendGap(local.userId());

		mockMvc.perform(json(post("/api/v1/auth/password/reset"), Map.of("email", EMAIL)))
				.andExpect(status().isNoContent());
		ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
		verify(mailSender, times(1)).sendPasswordResetCode(eq(EMAIL), codeCaptor.capture());
		mockMvc.perform(json(post("/api/v1/auth/password/reset/confirm"),
						Map.of("email", EMAIL, "code", codeCaptor.getValue(), "newPassword", "new-password-99")))
				.andExpect(status().isNoContent());

		mockMvc.perform(login(EMAIL, "new-password-99"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.userId").value(local.userId()));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT password_hash FROM user_identities WHERE provider = 'KAKAO'", String.class)).isNull();
	}

	@Test
	@DisplayName("이메일을 등록해 확인까지 마친 카카오 계정도 비밀번호 변경은 409다")
	void kakaoWithEmailCannotChangePassword() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		mockMvc.perform(json(patch("/api/v1/users/me/password"),
						Map.of("currentPassword", PASSWORD, "newPassword", "new-password-99"))
						.header("Authorization", bearer(kakao)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("소셜 계정은 변경할 비밀번호가 없습니다."));
	}

	// --- 본인 응답과 공개 응답 ---

	@Test
	@DisplayName("내 정보에는 본인이 등록한 이메일이 실리고 emailVerified는 확인 전 false, 확인 뒤 true다. 내 정보 수정 응답도 같다")
	void myProfileShowsOwnEmail() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);

		mockMvc.perform(me(kakao))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("KAKAO"))
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.emailVerified").value(false))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.providerUserId").doesNotExist())
				.andExpect(jsonPath("$.emailConsentAt").doesNotExist());
		mockMvc.perform(json(patch("/api/v1/users/me"), Map.of("bio", "안녕하세요")).header("Authorization", bearer(kakao)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.emailVerified").value(false));

		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		mockMvc.perform(me(kakao))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(EMAIL))
				.andExpect(jsonPath("$.emailVerified").value(true));
	}

	@Test
	@DisplayName("다른 사람에게도 보이는 응답(온보딩·로그인의 사용자 요약, 판매자 프로필, 관리자 회원 목록)에는 이메일이 없다")
	void publicResponsesDoNotExposeEmail() throws Exception {
		String signupBody = mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", EMAIL, true))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.email").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		long userId = objectMapper.readTree(signupBody).get("user").get("userId").asLong();

		String loginBody = mockMvc.perform(oauthLogin("kakao-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOGIN"))
				.andExpect(jsonPath("$.user.email").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		String profileBody = mockMvc.perform(get("/api/v1/users/{userId}/profile", userId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		AdminTestClient admins = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		AdminTestClient.Member admin = admins.signupAdmin("admin@example.com", "관리자");
		String adminListBody = mockMvc.perform(get("/api/v1/admin/users").header("Authorization", AdminTestClient.bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].userId", hasItem(Math.toIntExact(userId))))
				.andReturn().getResponse().getContentAsString();

		assertThat(List.of(signupBody, loginBody, profileBody, adminListBody))
				.allSatisfy(body -> assertThat(body).doesNotContain(EMAIL));
	}

	// --- 탈퇴와 감사 기록 ---

	@Test
	@DisplayName("탈퇴하면 인증 수단 행과 함께 이메일이 사라지고, 같은 카카오 계정으로 다시 온보딩하면 새 회원에 미확인 상태로 저장되어 메일이 다시 나간다")
	void withdrawalRemovesEmail() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(kakao)))
				.andExpect(status().isNoContent());
		assertThat(count("SELECT count(*) FROM user_identities WHERE email = ?", EMAIL)).isZero();

		Tokens again = onboard("kakao-1", "다시온유저", EMAIL);
		assertThat(again.userId()).isNotEqualTo(kakao.userId());
		assertThat(verifiedAtOf(again.userId())).isNull();
		verify(mailSender, times(2)).sendVerificationCode(eq(EMAIL), any());
	}

	@Test
	@DisplayName("이메일을 입력한 온보딩의 AUTH_SIGNUP detail은 제공자만 담고, 확인·로그인까지 감사 로그 어디에도 이메일이 없다")
	void auditLogsHaveNoEmail() throws Exception {
		Tokens kakao = onboard("kakao-1", "카카오유저", EMAIL);
		mockMvc.perform(confirm(kakao, lastCodeSentTo(EMAIL))).andExpect(status().isNoContent());
		mockMvc.perform(oauthLogin("kakao-1")).andExpect(status().isOk());

		assertThat(jdbcTemplate.queryForObject(
				"SELECT detail::jsonb = '{\"provider\": \"KAKAO\"}'::jsonb FROM audit_logs WHERE action = 'AUTH_SIGNUP'",
				Boolean.class)).isTrue();
		assertThat(count("SELECT count(*) FROM audit_logs")).isGreaterThanOrEqualTo(2);
		assertThat(count("SELECT count(*) FROM audit_logs WHERE detail::text LIKE '%@%'")).isZero();
	}

	// --- 인증 수단 저장 실패의 구분 ---

	@Test
	@DisplayName("같은 signupToken으로 다른 닉네임을 보내 온보딩을 두 번 완료하려 하면 409 이미 가입된 소셜 계정이다")
	void reusedSignupTokenIsConflict() throws Exception {
		String signupToken = signupToken("kakao-1");
		mockMvc.perform(onboarding(signupToken, "카카오유저", EMAIL, true)).andExpect(status().isCreated());

		mockMvc.perform(onboarding(signupToken, "다른닉네임", "other@example.com", true))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("이미 가입된 소셜 계정입니다."));

		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
		verify(mailSender, never()).sendVerificationCode(eq("other@example.com"), any());
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	@DisplayName("인증 수단 저장이 중복 가입 외의 제약(예: 예상하지 못한 CHECK)에 걸리면 409가 아니라 500 INTERNAL_ERROR이고, 로그에 이메일이 남지 않는다")
	void otherIdentityConstraintIsInternalError(CapturedOutput output) throws Exception {
		jdbcTemplate.execute("ALTER TABLE user_identities ADD CONSTRAINT " + REJECT_SOCIAL_EMAIL_CONSTRAINT
				+ " CHECK (provider = 'LOCAL' OR email IS NULL) NOT VALID");
		try {
			String body = mockMvc.perform(onboarding(signupToken("kakao-1"), "카카오유저", EMAIL, true))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
					.andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain(EMAIL, REJECT_SOCIAL_EMAIL_CONSTRAINT);
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE user_identities DROP CONSTRAINT " + REJECT_SOCIAL_EMAIL_CONSTRAINT);
		}

		assertThat(count("SELECT count(*) FROM users")).isZero();
		verify(mailSender, never()).sendVerificationCode(any(), any());
		// CHECK 위반의 서버 DETAIL("Failing row contains (...)")에는 행 값이 담긴다. Hibernate WARN과 서비스 로그 모두
		// 제약 이름만 남기고 주소는 남기지 않는다(NFR-LOG-003).
		assertThat(output.getAll()).contains(REJECT_SOCIAL_EMAIL_CONSTRAINT).doesNotContain(EMAIL);
	}

	// --- helpers ---

	/** 인가 코드가 곧 카카오 회원번호다({@link #resetState}의 대역 설정). */
	private MockHttpServletRequestBuilder oauthLogin(String kakaoId) {
		return json(post("/api/v1/auth/oauth/kakao"),
				Map.of("code", kakaoId, "redirectUri", "https://reused.app/oauth/callback"));
	}

	private String signupToken(String kakaoId) throws Exception {
		MvcResult result = mockMvc.perform(oauthLogin(kakaoId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SIGNUP_REQUIRED"))
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("signupToken").asString();
	}

	private MockHttpServletRequestBuilder onboarding(String signupToken, String nickname, String email, Boolean agreed) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("email", email);
		extra.put("emailCollectionAgreed", agreed);
		return onboarding(signupToken, nickname, extra);
	}

	private MockHttpServletRequestBuilder onboarding(String signupToken, String nickname, Map<String, Object> extra) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("signupToken", signupToken);
		body.put("nickname", nickname);
		body.putAll(extra);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		return json(post("/api/v1/auth/signup"), body);
	}

	/** @param email null이면 이메일 없이 온보딩한다. 있으면 선택 동의와 함께 보낸다 */
	private Tokens onboard(String kakaoId, String nickname, String email) throws Exception {
		Map<String, Object> extra = new LinkedHashMap<>();
		if (email != null) {
			extra.put("email", email);
			extra.put("emailCollectionAgreed", true);
		}
		return tokens(mockMvc.perform(onboarding(signupToken(kakaoId), nickname, extra))
				.andExpect(status().isCreated())
				.andReturn());
	}

	private MockHttpServletRequestBuilder localSignup(String email, String nickname) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		return json(post("/api/v1/auth/email/signup"), body);
	}

	private Tokens signupLocal(String email, String nickname) throws Exception {
		return tokens(mockMvc.perform(localSignup(email, nickname)).andExpect(status().isCreated()).andReturn());
	}

	private Tokens tokens(MvcResult result) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new Tokens(body.get("user").get("userId").asLong(), body.get("accessToken").asString());
	}

	private MockHttpServletRequestBuilder login(String email, String password) {
		return json(post("/api/v1/auth/email/login"), Map.of("email", email, "password", password));
	}

	private MockHttpServletRequestBuilder resend(Tokens tokens) {
		return post("/api/v1/auth/email/verification").header("Authorization", bearer(tokens));
	}

	private MockHttpServletRequestBuilder confirm(Tokens tokens, String code) {
		return json(post("/api/v1/auth/email/verification/confirm"), Map.of("code", code))
				.header("Authorization", bearer(tokens));
	}

	private MockHttpServletRequestBuilder me(Tokens tokens) {
		return get("/api/v1/users/me").header("Authorization", bearer(tokens));
	}

	private static String bearer(Tokens tokens) {
		return "Bearer " + tokens.accessToken();
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 이 주소로 나간 소유 확인 코드. 발송 순서대로다. */
	private List<String> codesSentTo(String email) {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(mailSender, atLeastOnce()).sendVerificationCode(eq(email), captor.capture());
		return captor.getAllValues();
	}

	private String lastCodeSentTo(String email) {
		return codesSentTo(email).getLast();
	}

	private long identityIdOf(long userId) {
		return jdbcTemplate.queryForObject("SELECT identity_id FROM user_identities WHERE user_id = ?", Long.class, userId);
	}

	private Object verifiedAtOf(long userId) {
		return jdbcTemplate.queryForObject("SELECT email_verified_at FROM user_identities WHERE user_id = ?", Object.class,
				userId);
	}

	/** 재발송 간격 잠금(60초)을 테스트에서 기다릴 수 없으므로 키를 직접 지운다. 키는 이메일이 아니라 identityId 기준이다. */
	private void clearResendGap(long userId) {
		redisTemplate.delete("reused:auth:resend-gap:" + identityIdOf(userId));
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	private void awaitLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
					+ "WHERE wait_event_type = 'Lock' AND datname = current_database()", Long.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("소유 확인 UPDATE가 부분 UNIQUE 인덱스 대기에 들어가지 않았다");
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record Tokens(long userId, String accessToken) {
	}

}
