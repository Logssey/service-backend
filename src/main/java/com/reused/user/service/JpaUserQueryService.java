package com.reused.user.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.user.api.UserQueryService;
import com.reused.user.api.UserSnapshot;
import com.reused.user.entity.User;
import com.reused.user.entity.UserStatus;
import com.reused.user.repository.UserRepository;

@Service
public class JpaUserQueryService implements UserQueryService {

	private final UserRepository userRepository;

	public JpaUserQueryService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserSnapshot> findSnapshot(Long userId) {
		if (userId == null) {
			return Optional.empty();
		}
		return userRepository.findById(userId).map(JpaUserQueryService::toSnapshot);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, UserSnapshot> findSnapshots(Collection<Long> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Map.of();
		}
		Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, UserSnapshot> snapshots = new HashMap<>();
		for (User user : userRepository.findAllById(ids)) {
			snapshots.put(user.getId(), toSnapshot(user));
		}
		return Map.copyOf(snapshots);
	}

	/**
	 * withdrawn_at만 기록된 비정상 행도 탈퇴로 보이게 한다({@link User#isWithdrawn()}과 같은 기준).
	 */
	private static UserSnapshot toSnapshot(User user) {
		UserStatus status = user.isWithdrawn() ? UserStatus.WITHDRAWN : user.getStatus();
		return new UserSnapshot(user.getId(), user.getNickname(), user.getProfileImageUrl(), status,
				user.getCreatedAt());
	}

}
