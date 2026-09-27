package com.reused.user.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.dto.response.MyProfileResponse;
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatus;
import com.reused.user.repository.UserRepository;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final JdbcTemplate jdbc;

	public UserService(UserRepository userRepository, JdbcTemplate jdbc) {
		this.userRepository = userRepository;
		this.jdbc = jdbc;
	}

	/**
	 * 닉네임은 프로필에 공개되는 값이라 존재 여부를 노출해도 계정 열거 문제가 없다.
	 * 이메일에는 같은 확인 API를 두지 않는다(NFR-AUTH-018).
	 *
	 * <p>예약어는 쓰이지 않았더라도 false가 아니라 400이다. 가입에서도 같은 400이 나기 때문이다.
	 */
	@Transactional(readOnly = true)
	public boolean isNicknameAvailable(String nickname) {
		NicknamePolicy.validate(nickname);
		return !userRepository.existsByNickname(nickname);
	}

	/**
	 * Access Token은 탈퇴 뒤에도 만료 전까지 서명이 유효하다. 탈퇴했거나 인증 수단이 없는 계정은
	 * 인증되지 않은 것으로 본다. 토큰 재발급의 탈퇴 처리와 같은 규칙이다.
	 *
	 * <p>이메일은 제공자와 무관하게 인증 수단에 등록된 값이다. 소셜 계정은 온보딩에서 입력했을 때만 있다(ADR-016).
	 */
	@Transactional(readOnly = true)
	public MyProfileResponse getMyProfile(Long userId) {
		// JDBC returns profile edits from this transaction without a previously cached JPA user.
		return jdbc.query("""
				SELECT u.*, i.provider, i.email, i.email_verified_at FROM users u
				JOIN user_identities i ON i.user_id = u.user_id
				WHERE u.user_id = ? AND u.status <> 'WITHDRAWN' AND u.withdrawn_at IS NULL
				""", (rs, n) -> {
					String email = rs.getString("email");
					return new MyProfileResponse(rs.getLong("user_id"), rs.getString("nickname"),
							rs.getString("profile_image_url"), rs.getString("bio"), UserRole.valueOf(rs.getString("role")),
							UserStatus.valueOf(rs.getString("status")),
							rs.getTimestamp("suspended_until") == null ? null : rs.getTimestamp("suspended_until").toInstant(),
							AuthProvider.valueOf(rs.getString("provider")),
							email,
							email != null && rs.getTimestamp("email_verified_at") != null,
							rs.getTimestamp("created_at").toInstant());
				}, userId).stream().findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
	}

}
