package com.reused.image.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ImageStorageTest {

	@Test
	void presignedPutRequiresContentTypeAndExactLengthFromBrowser() {
		try (S3Presigner presigner = S3Presigner.builder()
				.region(Region.AP_NORTHEAST_2)
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create("test-access", "test-secret")))
				.build()) {
			S3ImageStorage storage = new S3ImageStorage("private-test-bucket", null, presigner);
			String url = storage.presignUpload("pending/listing-images/1/id.jpg", "image/jpeg", 2048,
					Duration.ofMinutes(5));
			String query = URLDecoder.decode(URI.create(url).getRawQuery(), StandardCharsets.UTF_8);
			assertThat(query).contains("X-Amz-SignedHeaders=content-length;content-type;host");
		}
	}
}
