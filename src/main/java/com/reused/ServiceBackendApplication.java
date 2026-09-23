package com.reused;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ServiceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServiceBackendApplication.class, args);
	}

}
