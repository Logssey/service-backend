package com.reused.common.security;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final AuthUserArgumentResolver authUserArgumentResolver;
	private final AdminAccessInterceptor adminAccessInterceptor;

	public WebMvcConfig(AuthUserArgumentResolver authUserArgumentResolver,
			AdminAccessInterceptor adminAccessInterceptor) {
		this.authUserArgumentResolver = authUserArgumentResolver;
		this.adminAccessInterceptor = adminAccessInterceptor;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(authUserArgumentResolver);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminAccessInterceptor).addPathPatterns("/api/v1/admin/**");
	}

}
