package com.reused.auth.token;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.reused.auth.config.AuthProperties;
import com.reused.user.entity.UserRole;

/**
 * Access Token과 signupToken을 발급·검증한다.
 *
 * <p>서명은 대칭키 HS256이며 복호화 시 알고리즘을 HS256으로 고정 검증한다(ADR-005).
 * {@code MacAlgorithm.HS256}을 디코더에 명시했으므로 alg를 바꾼 토큰은 서명 검증 단계에서 거부된다.
 *
 * <p>두 토큰은 같은 키로 서명되므로 용도 클레임({@code typ})으로 구분한다.
 * 이 구분이 없으면 signupToken을 Access Token 자리에 넣어 인증을 통과시킬 수 있다.
 */
@Component
public class JwtTokenProvider {

	static final String CLAIM_TYPE = "typ";
	static final String CLAIM_ROLE = "role";

	private static final String TYPE_ACCESS = "access";
	private static final String TYPE_SIGNUP = "signup";

	private final NimbusJwtEncoder encoder;
	private final NimbusJwtDecoder decoder;
	private final AuthProperties properties;

	public JwtTokenProvider(AuthProperties properties) {
		this.properties = properties;
		SecretKey key = new SecretKeySpec(
				properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		this.decoder = NimbusJwtDecoder.withSecretKey(key)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
	}

	public String issueAccessToken(Long userId, UserRole role) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(String.valueOf(userId))
				.claim(CLAIM_TYPE, TYPE_ACCESS)
				.claim(CLAIM_ROLE, role.name())
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.build();
		return encode(claims);
	}

	/**
	 * 인가 코드는 일회용이라 온보딩 중 만료되면 복구할 수 없다.
	 * 그래서 카카오 회원번호를 담은 단기 토큰을 따로 발급한다(카카오 로그인 명세).
	 */
	public String issueSignupToken(String providerUserId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(providerUserId)
				.claim(CLAIM_TYPE, TYPE_SIGNUP)
				.issuedAt(now)
				.expiresAt(now.plus(properties.signupTokenTtl()))
				.build();
		return encode(claims);
	}

	/**
	 * @return 검증에 실패하거나 용도가 access가 아니면 빈 결과
	 */
	public AccessTokenClaims parseAccessToken(String token) {
		Jwt jwt = decodeAs(token, TYPE_ACCESS);
		return new AccessTokenClaims(
				Long.valueOf(jwt.getSubject()),
				UserRole.valueOf(jwt.getClaimAsString(CLAIM_ROLE)));
	}

	/**
	 * @return signupToken에 담긴 카카오 회원번호
	 */
	public String parseSignupToken(String token) {
		return decodeAs(token, TYPE_SIGNUP).getSubject();
	}

	private Jwt decodeAs(String token, String expectedType) {
		Jwt jwt;
		try {
			jwt = decoder.decode(token);
		}
		catch (JwtException e) {
			throw new InvalidTokenException("토큰을 검증할 수 없습니다.", e);
		}
		if (!expectedType.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
			throw new InvalidTokenException("토큰 용도가 올바르지 않습니다.");
		}
		return jwt;
	}

	private String encode(JwtClaimsSet claims) {
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public record AccessTokenClaims(Long userId, UserRole role) {
	}

}
