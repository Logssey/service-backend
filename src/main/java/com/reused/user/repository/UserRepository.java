package com.reused.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.reused.user.entity.User;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByNickname(String nickname);

	/**
	 * {@code SELECT ... FOR NO KEY UPDATE}. 정지·해제·역할 변경·탈퇴가 같은 회원을 동시에 바꾸지 않게 직렬화한다.
	 * 트랜잭션 안에서만 부른다.
	 *
	 * <p>같은 트랜잭션에서 이 회원을 이미 읽었으면(ActiveUserGuard, UserQueryService, findById 등) 행은 잠그지만
	 * 엔티티를 다시 채우지 않는다. 잠그기 전에 다른 트랜잭션이 커밋한 값이 보이지 않으므로, 먼저 읽었을 수 있는 호출자는
	 * 이어서 {@code EntityManager.refresh(user)}로 다시 읽는다({@code UserModerationService} 참고).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") Long id);

}
