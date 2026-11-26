package com.reused.attacklab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** DEMO BRANCH ONLY. No production security chain is changed. */
@Configuration
@Profile("attack-lab")
@ConditionalOnProperty(name = "attack-lab.enabled", havingValue = "true")
public class AttackLabConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain attackLabChain(HttpSecurity http) throws Exception {
        // Every controller method below independently verifies the one-time lab token.
        return http.securityMatcher("/internal/attack-lab/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
