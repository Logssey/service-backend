package com.reused.image.service;

import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Service;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.image.storage.ImageStorage;
import com.reused.image.storage.ImageStorageException;

/** Internal profile keys are never exposed or stored as expiring signed URLs. */
@Service
public class ProfileImageUrlResolver {
    private final ImageStorage storage;
    public ProfileImageUrlResolver(ImageStorage storage) { this.storage = storage; }

    public String resolve(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.matches("verified/profile-images/[1-9][0-9]*/[a-zA-Z0-9-]+\\.(jpg|png|webp)")) {
            try { return storage.presignRead(value, Duration.ofMinutes(15)); }
            catch (ImageStorageException e) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "프로필 이미지 조회 URL 발급에 실패했습니다.", e);
            }
        }
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return value;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
