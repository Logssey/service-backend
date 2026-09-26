package com.reused.support;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.pagination.CursorCodec;
import com.reused.common.pagination.CursorPageRequest;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;

/**
 * 공통 인프라(예외 처리기, 선택 토큰, 페이지 요청 바인딩)를 실제 MVC 경로로 확인하기 위한 테스트 전용 컨트롤러.
 * 아직 이 기능을 쓰는 실제 엔드포인트가 없어서 둔다.
 *
 * <p>{@code @TestComponent}라 컴포넌트 스캔에 잡히지 않는다. 쓰는 테스트가 {@code @Import}로 등록한다.
 * 보안 규칙은 {@link ProbeSecurityConfiguration}이 {@code /test/**}만 따로 연다.
 */
@TestComponent
@RestController
@RequestMapping("/test/probe")
public class ProbeController {

	@GetMapping("/items/{itemId}")
	public Map<String, Object> item(@PathVariable Long itemId) {
		return Map.of("itemId", itemId);
	}

	@GetMapping("/required")
	public Map<String, Object> required(@RequestParam String keyword) {
		return Map.of("keyword", keyword);
	}

	@GetMapping("/method-validation")
	public Map<String, Object> methodValidation(@RequestParam @Max(100) Integer size) {
		return Map.of("size", size);
	}

	@GetMapping("/page")
	public Map<String, Object> page(@Valid CursorPageRequest page) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("size", page.sizeOrDefault());
		body.put("cursorId", CursorCodec.decodeId(page.cursor()));
		return body;
	}

	@GetMapping("/optimistic-lock")
	public void optimisticLock() {
		throw new ObjectOptimisticLockingFailureException("com.reused.Probe", 1L);
	}

	@GetMapping("/pessimistic-lock")
	public void pessimisticLock() {
		throw new PessimisticLockingFailureException("probe lock timeout");
	}

	@GetMapping("/illegal-transaction")
	public void illegalTransaction() {
		throw new IllegalTransactionStateException("probe internal detail");
	}

	@GetMapping("/optional-principal")
	public Map<String, Object> optionalPrincipal(@AuthUser @Nullable AuthPrincipal principal) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("userId", principal == null ? null : principal.userId());
		return body;
	}

	@GetMapping("/required-principal")
	public Map<String, Object> requiredPrincipal(@AuthUser AuthPrincipal principal) {
		return Map.of("userId", principal.userId());
	}

}
