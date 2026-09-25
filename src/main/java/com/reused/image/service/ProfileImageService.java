package com.reused.image.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.image.repository.ImageRecord;
import com.reused.image.repository.ImageRepository;

@Service
public class ProfileImageService {
    private final ImageRepository images;
    public ProfileImageService(ImageRepository images) { this.images = images; }

    /** Caller must lock the user first; this serializes replacement and withdrawal. */
    @Transactional
    public String replace(Long userId, Long imageId) {
        images.requireProfiles();
        ImageRecord selected = null;
        if (imageId != null) {
            if (imageId <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
            selected = images.findForUpdate(imageId).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
            if (!selected.uploaderId().equals(userId)) throw new BusinessException(ErrorCode.FORBIDDEN);
            if (!"PROFILE".equals(selected.purpose()) || !"VERIFIED".equals(selected.status())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "검증 완료된 프로필 이미지만 연결할 수 있습니다.");
            }
            if (selected.listingId() != null || (selected.profileUserId() != null && !selected.profileUserId().equals(userId))) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        }
        images.detachProfile(userId);
        if (selected == null) return null;
        images.attachProfile(imageId, userId);
        return selected.objectKey();
    }
}
