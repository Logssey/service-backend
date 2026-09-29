package com.reused.listing.query;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor, boolean hasNext) {
}
