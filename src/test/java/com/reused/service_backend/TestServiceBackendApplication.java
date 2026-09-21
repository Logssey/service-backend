package com.reused.service_backend;

import org.springframework.boot.SpringApplication;

public class TestServiceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ServiceBackendApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}

}
