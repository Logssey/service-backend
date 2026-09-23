package com.reused;

import org.springframework.boot.SpringApplication;

/**
 * 로컬 실행용 진입점. Postgres·Redis 컨테이너를 띄운 뒤 앱을 기동한다.
 */
public class TestServiceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ServiceBackendApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}

}
