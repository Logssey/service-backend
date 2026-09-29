package com.reused.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CursorPageResponseTest {

	@Test
	@DisplayName("size+1개를 읽었으면 size개로 자르고 마지막 항목으로 다음 커서를 만든다")
	void overflowMeansNextPage() {
		CursorPageResponse<Long> page = CursorPageResponse.of(List.of(30L, 20L, 10L), 2, CursorCodec::encodeId);

		assertThat(page.items()).containsExactly(30L, 20L);
		assertThat(page.hasNext()).isTrue();
		assertThat(CursorCodec.decodeId(page.nextCursor())).isEqualTo(20L);
	}

	@Test
	@DisplayName("size개 이하면 마지막 페이지다. nextCursor는 null이다")
	void lastPage() {
		CursorPageResponse<Long> exact = CursorPageResponse.of(List.of(30L, 20L), 2, CursorCodec::encodeId);
		CursorPageResponse<Long> fewer = CursorPageResponse.of(List.of(30L), 2, CursorCodec::encodeId);

		assertThat(exact.items()).containsExactly(30L, 20L);
		assertThat(exact.hasNext()).isFalse();
		assertThat(exact.nextCursor()).isNull();
		assertThat(fewer.hasNext()).isFalse();
		assertThat(fewer.nextCursor()).isNull();
	}

	@Test
	@DisplayName("빈 페이지는 항목이 없고 다음 페이지도 없다")
	void empty() {
		CursorPageResponse<String> page = CursorPageResponse.empty();

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.hasNext()).isFalse();
		assertThat(CursorPageResponse.of(List.<Long>of(), 20, CursorCodec::encodeId)).isEqualTo(page);
	}

	@Test
	@DisplayName("map은 항목만 바꾸고 커서와 hasNext는 유지한다")
	void mapKeepsCursor() {
		CursorPageResponse<Long> page = CursorPageResponse.of(List.of(3L, 2L, 1L), 2, CursorCodec::encodeId);

		CursorPageResponse<String> mapped = page.map(id -> "item-" + id);

		assertThat(mapped.items()).containsExactly("item-3", "item-2");
		assertThat(mapped.nextCursor()).isEqualTo(page.nextCursor());
		assertThat(mapped.hasNext()).isTrue();
	}

	@Test
	@DisplayName("항목 목록은 원본 변경의 영향을 받지 않고 수정할 수 없다")
	void itemsAreImmutableCopy() {
		ArrayList<Long> rows = new ArrayList<>(List.of(1L));
		CursorPageResponse<Long> page = CursorPageResponse.of(rows, 5, CursorCodec::encodeId);
		rows.add(2L);

		assertThat(page.items()).containsExactly(1L);
		assertThatThrownBy(() -> page.items().add(3L)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("size가 1보다 작으면 프로그래밍 오류다")
	void sizeMustBePositive() {
		assertThatThrownBy(() -> CursorPageResponse.of(List.of(1L), 0, CursorCodec::encodeId))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("요청 size를 생략하면 20이다")
	void requestDefaultSize() {
		assertThat(new CursorPageRequest(null, null).sizeOrDefault()).isEqualTo(20);
		assertThat(new CursorPageRequest(null, 5).sizeOrDefault()).isEqualTo(5);
	}

}
