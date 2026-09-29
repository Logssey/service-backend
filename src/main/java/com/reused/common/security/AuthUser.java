package com.reused.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 인증 주체를 주입한다. 비로그인이면 401이다.
 *
 * <pre>
 * public MyProfileResponse me(&#64;AuthUser AuthPrincipal principal) { ... }
 * </pre>
 *
 * <p>로그인이 선택인 공개 엔드포인트는 JSpecify {@code @Nullable}을 함께 단다. 비로그인이면 null이다. Authorization 헤더를 보냈는데 토큰이 잘못되었거나 만료되었으면 JwtAuthenticationFilter가 컨트롤러 전에 401을 낸다.
 *
 * <pre>
 * public ProfileResponse profile(&#64;PathVariable Long userId, &#64;AuthUser &#64;Nullable AuthPrincipal viewer) { ... }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthUser {
}
