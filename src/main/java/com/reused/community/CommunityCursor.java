package com.reused.community;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

record CommunityCursor(Instant createdAt, long id) {
    static CommunityCursor decode(String encoded, String scope) {
        if (encoded == null) return null;
        try {
            if (encoded.isBlank() || encoded.length() > 512) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 4 || !"community-v1".equals(parts[0]) || !scope.equals(parts[1]))
                throw new IllegalArgumentException();
            Instant time = Instant.parse(parts[2]);
            long id = Long.parseLong(parts[3]);
            if (id < 1 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))
                throw new IllegalArgumentException();
            return new CommunityCursor(time, id);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "유효하지 않은 커서입니다.");
        }
    }

    String encode(String scope) {
        String raw = "community-v1|" + scope + "|" + createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
