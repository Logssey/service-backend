package com.reused.image.storage;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ImageStorageConfig {

	@Bean
	ImageStorage imageStorage(@Value("${app.image.s3.bucket:}") String bucket,
			@Value("${app.image.s3.region:ap-northeast-1}") String region,
			@Value("${app.image.s3.endpoint:}") String endpoint) {
		if (bucket.isBlank()) {
			return new UnavailableImageStorage();
		}
		Region awsRegion = Region.of(region);
		var clientBuilder = S3Client.builder().region(awsRegion)
				.credentialsProvider(DefaultCredentialsProvider.create());
		var presignerBuilder = S3Presigner.builder().region(awsRegion)
				.credentialsProvider(DefaultCredentialsProvider.create());
		if (!endpoint.isBlank()) {
			URI uri = URI.create(endpoint);
			S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
			clientBuilder.endpointOverride(uri).serviceConfiguration(pathStyle);
			presignerBuilder.endpointOverride(uri).serviceConfiguration(pathStyle);
		}
		return new S3ImageStorage(bucket, clientBuilder.build(), presignerBuilder.build());
	}
}
