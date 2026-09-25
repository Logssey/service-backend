package com.reused.common.paging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.listing.query.CursorPageResponse;

public final class IdPage {
    private IdPage() {}
    public static int size(Integer size) {
        if (size == null) return 20;
        if (size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return size;
    }
    public static long before(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) return Long.MAX_VALUE;
        try {
            if (cursor.length() > 1024) throw new IllegalArgumentException();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith(scope + ":")) throw new IllegalArgumentException();
            long id = Long.parseLong(raw.substring(scope.length() + 1));
            if (id < 1) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_INPUT); }
    }
    public static <T> CursorPageResponse<T> of(List<T> rows, int size, String scope, Function<T, Long> id) {
        boolean hasNext = rows.size() > size;
        List<T> items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        String cursor = hasNext ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                (scope + ":" + id.apply(items.getLast())).getBytes(StandardCharsets.UTF_8)) : null;
        return new CursorPageResponse<>(items, cursor, hasNext);
    }
}
