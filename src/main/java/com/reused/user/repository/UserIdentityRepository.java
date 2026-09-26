package com.reused.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserIdentity;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	/**
	 * 회원을 함께 읽는다. 로그인은 트랜잭션 없이 {@code getUser()}를 쓴다({@code AuthService#login}).
	 */
	@EntityGraph(attributePaths = "user")
	Optional<UserIdentity> findWithUserByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

	/**
	 * 이메일은 LOCAL 범위에서만 유일하다(부분 UNIQUE 인덱스). provider를 함께 조건에 넣어야 한다.
	 * 소셜 행도 이메일을 가질 수 있으므로(ADR-019) provider 없이 이메일로 조회하는 메서드를 만들지 않는다.
	 * 이메일 로그인·가입 중복 검사·비밀번호 재설정은 LOCAL로만 조회한다.
	 */
	Optional<UserIdentity> findByProviderAndEmail(AuthProvider provider, String email);

	/**
	 * {@link #findByProviderAndEmail}과 같고 회원을 함께 읽는다. 로그인은 트랜잭션 없이 {@code getUser()}를 쓴다
	 * ({@code EmailAuthService#login}).
	 */
	@EntityGraph(attributePaths = "user")
	Optional<UserIdentity> findWithUserByProviderAndEmail(AuthProvider provider, String email);

	boolean existsByProviderAndEmail(AuthProvider provider, String email);

	/**
	 * 다른 소셜 인증 수단이 이 이메일의 소유 확인을 이미 마쳤는지. 소셜 이메일 소유 확인에서만 쓴다(ADR-019).
	 * 판정 범위는 부분 UNIQUE 인덱스 {@code uq_user_identities_email_social_verified}와 같다. LOCAL 행은 보지 않는다
	 * (제공자가 다르면 별개 계정, ADR-016).
	 */
	@Query("""
			select count(i) > 0 from UserIdentity i
			where i.email = :email and i.provider <> com.reused.user.entity.AuthProvider.LOCAL
			  and i.emailVerifiedAt is not null and i.id <> :identityId""")
	boolean existsVerifiedSocialEmailElsewhere(@Param("email") String email, @Param("identityId") Long identityId);

	/**
	 * 1차 릴리스는 회원당 인증 수단이 하나이므로 단건으로 조회한다(ADR-017).
	 */
	Optional<UserIdentity> findByUserId(Long userId);

}
