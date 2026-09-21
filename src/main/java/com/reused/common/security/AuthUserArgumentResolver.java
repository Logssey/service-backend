package com.reused.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

	private final CurrentUserProvider currentUserProvider;

	public AuthUserArgumentResolver(CurrentUserProvider currentUserProvider) {
		this.currentUserProvider = currentUserProvider;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(AuthUser.class)
				&& AuthPrincipal.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		// 인증이 필요 없는 엔드포인트에서도 쓸 수 있도록 Optional 파라미터는 null을 허용한다.
		if (parameter.isOptional()) {
			return currentUserProvider.current().orElse(null);
		}
		return currentUserProvider.require();
	}

}
