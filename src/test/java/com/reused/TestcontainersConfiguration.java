package com.reused;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 컨테이너. Postgres에는 운영과 동일한 스키마와 초기 데이터를 적용한다.
 * 이 파일은 Gradle processTestResources가 테스트 클래스패스로 복사한 것이며 사본이 아니다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
				.withInitScripts("schema/001_init.sql", "schema/002_seed_categories.sql", "schema/003_profile_images.sql");
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
