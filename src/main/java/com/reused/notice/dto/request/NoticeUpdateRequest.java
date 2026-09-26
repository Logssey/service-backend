package com.reused.notice.dto.request;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 공지 수정(공지사항 등록 명세의 NoticeUpdateRequest, "모든 필드 선택"). null은 "변경 안 함"이고 필드 생략과 같다.
 * 모든 컬럼이 NOT NULL이라 null로 바꾸는 요청은 없다.
 *
 * <p>값이 있으면 등록과 같은 제약이다(길이, 공백만은 불가). 공백 판정은 {@code @NotBlank}와 같게
 * "U+0020 이하 문자만으로 이루어짐"이다. 셋 다 없으면 서비스가 400을 낸다.
 */
public record NoticeUpdateRequest(
		@Size(max = 200) @Pattern(regexp = NOT_BLANK, message = BLANK_MESSAGE) String title,
		@Size(max = 5000) @Pattern(regexp = NOT_BLANK, message = BLANK_MESSAGE) String content,
		@JsonProperty("isPinned") Boolean isPinned) {

	/** trim()으로 지워지지 않는 문자가 하나 이상 있다. null은 {@code @Pattern}이 통과시킨다 */
	private static final String NOT_BLANK = "(?s).*[^\\x00-\\x20].*";
	private static final String BLANK_MESSAGE = "공백만 입력할 수 없습니다.";

	/**
	 * 요청에 담긴 필드 이름(JSON 이름). 값이 현재와 같은지는 비교하지 않는다. 감사 로그 detail에 쓴다.
	 */
	public List<String> changedFields() {
		List<String> fields = new ArrayList<>(3);
		if (title != null) {
			fields.add("title");
		}
		if (content != null) {
			fields.add("content");
		}
		if (isPinned != null) {
			fields.add("isPinned");
		}
		return List.copyOf(fields);
	}

}
