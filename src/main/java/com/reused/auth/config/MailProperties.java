package com.reused.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 메일 발신 정보. SMTP 접속 정보는 spring.mail.*로 주입한다.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(@DefaultValue("no-reply@reused.local") String from) {
}
