package com.reused.common.security;

import org.springframework.stereotype.Component;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.repository.UserRepository;

/** Rechecks current database state because an issued token can outlive suspension or role changes. */
@Component
public class ActorGuard {
    private final UserRepository users;
    public ActorGuard(UserRepository users) { this.users = users; }
    public Long user(AuthPrincipal principal, boolean suspensionBlocked) {
        return require(principal, UserRole.USER, suspensionBlocked).getId();
    }
    public Long admin(AuthPrincipal principal) { return require(principal, UserRole.ADMIN, true).getId(); }
    private User require(AuthPrincipal principal, UserRole role, boolean suspensionBlocked) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        User user = users.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.isWithdrawn()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (principal.role() != role || user.getRole() != role) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (suspensionBlocked && user.isSuspended()) throw new BusinessException(ErrorCode.USER_SUSPENDED);
        return user;
    }
}
