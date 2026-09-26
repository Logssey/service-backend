package com.reused.notice.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공지 등록(공지사항 등록 명세). 앞뒤 공백은 자르지 않고 받은 그대로 저장한다. 공백뿐인 값은 {@code @NotBlank}가 막는다.
 *
 * <p>{@code @Size}는 UTF-16 단위로 세고 VARCHAR(n)은 문자 수로 센다. 이모지는 Java에서 2로 세어 더 엄격하므로
 * 검증을 통과한 값은 항상 컬럼에 들어간다.
 *
 * @param isPinned 생략하거나 null이면 false(DDL 기본값). primitive로 두면 생략 시 Jackson 3이 400을 낸다
 */
public record NoticeCreateRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 5000) String content,
		@JsonProperty("isPinned") Boolean isPinned) {

	public boolean pinnedOrDefault() {
		return Boolean.TRUE.equals(isPinned);
	}

}
