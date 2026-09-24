package com.reused.image.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

final class S3ImageStorage implements ImageStorage, AutoCloseable {
	private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

	private final String bucket;
	private final S3Client client;
	private final S3Presigner presigner;

	S3ImageStorage(String bucket, S3Client client, S3Presigner presigner) {
		this.bucket = bucket;
		this.client = client;
		this.presigner = presigner;
	}

	@Override
	public String presignUpload(String objectKey, String contentType, Duration ttl) {
		try {
			PutObjectRequest put = PutObjectRequest.builder()
					.bucket(bucket).key(objectKey).contentType(contentType).build();
			return presigner.presignPutObject(PutObjectPresignRequest.builder()
					.signatureDuration(ttl).putObjectRequest(put).build()).url().toString();
		}
		catch (RuntimeException ex) {
			throw new ImageStorageException("이미지 업로드 URL 발급 실패", ex);
		}
	}

	@Override
	public Optional<StoredImage> inspect(String objectKey) {
		try {
			HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
					.bucket(bucket).key(objectKey).build());
			if (head.contentLength() < 1 || head.contentLength() > MAX_IMAGE_SIZE) {
				return Optional.of(new StoredImage(head.contentLength(), head.contentType(), new byte[0], head.eTag()));
			}
			try (ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
						.bucket(bucket).key(objectKey).ifMatch(head.eTag()).build())) {
				return Optional.of(new StoredImage(head.contentLength(), head.contentType(),
						stream.readNBytes(head.contentLength().intValue() + 1), head.eTag()));
			}
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404 || ex.statusCode() == 412) {
				return Optional.empty();
			}
			throw new ImageStorageException("이미지 객체 조회 실패", ex);
		}
		catch (IOException | SdkClientException ex) {
			throw new ImageStorageException("이미지 객체 조회 실패", ex);
		}
	}

	@Override
	public boolean promote(String sourceKey, String verifiedKey, String expectedEtag) {
		try {
			client.copyObject(CopyObjectRequest.builder()
					.bucket(bucket).key(verifiedKey)
					.copySource(bucket + "/" + sourceKey)
					.copySourceIfMatch(expectedEtag).build());
			return true;
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404 || ex.statusCode() == 412) {
				return false;
			}
			throw new ImageStorageException("검증된 이미지 객체 승격 실패", ex);
		}
		catch (SdkClientException ex) {
			throw new ImageStorageException("검증된 이미지 객체 승격 실패", ex);
		}
	}

	@Override
	public String presignRead(String objectKey, Duration ttl) {
		try {
			GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
			return presigner.presignGetObject(GetObjectPresignRequest.builder()
					.signatureDuration(ttl).getObjectRequest(get).build()).url().toString();
		}
		catch (RuntimeException ex) {
			throw new ImageStorageException("이미지 조회 URL 발급 실패", ex);
		}
	}

	@Override
	public void delete(String objectKey) {
		try {
			client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
		}
		catch (S3Exception | SdkClientException ex) {
			throw new ImageStorageException("이미지 객체 삭제 실패", ex);
		}
	}

	@Override
	public void close() {
		presigner.close();
		client.close();
	}
}
