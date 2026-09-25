package com.reused.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.listing.query.CursorPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
public class ChatController {
	private final ChatService service;
	public ChatController(ChatService service) { this.service = service; }
	@PostMapping("/api/v1/chat-rooms")
	public ChatResponses.RoomCreated create(@AuthUser AuthPrincipal p, @Valid @RequestBody RoomCreateRequest r) {
		return service.create(p, r.listingId());
	}
	@GetMapping("/api/v1/chat-rooms")
	public CursorPageResponse<ChatResponses.RoomSummary> rooms(@AuthUser AuthPrincipal p,
			@RequestParam(required=false) String cursor, @RequestParam(required=false) Integer size) {
		return service.rooms(p, cursor, size);
	}
	@PostMapping("/api/v1/chat-rooms/{chatRoomId}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public ChatResponses.Message send(@AuthUser AuthPrincipal p, @PathVariable Long chatRoomId,
			@Valid @RequestBody MessageSendRequest r) { return service.send(p, chatRoomId, r.content()); }
	@GetMapping("/api/v1/chat-rooms/{chatRoomId}/messages")
	public CursorPageResponse<ChatResponses.Message> messages(@AuthUser AuthPrincipal p, @PathVariable Long chatRoomId,
			@RequestParam(required=false) String cursor, @RequestParam(required=false) Integer size) {
		return service.messages(p, chatRoomId, cursor, size);
	}
	@PostMapping("/api/v1/chat-rooms/{chatRoomId}/read")
	public ChatResponses.Read read(@AuthUser AuthPrincipal p, @PathVariable Long chatRoomId,
			@Valid @RequestBody MessageReadRequest r) { return service.read(p, chatRoomId, r.lastReadMessageId()); }
	@DeleteMapping("/api/v1/chat-rooms/{chatRoomId}/messages/{messageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthUser AuthPrincipal p, @PathVariable Long chatRoomId, @PathVariable Long messageId) {
		service.delete(p, chatRoomId, messageId);
	}
	@GetMapping("/api/v1/chat/session")
	public ChatResponses.Session session(@AuthUser AuthPrincipal p) { return service.session(p); }
	@GetMapping("/api/v1/chat-rooms/{chatRoomId}/subscription")
	public ChatResponses.Subscription subscription(@AuthUser AuthPrincipal p, @PathVariable Long chatRoomId) {
		return service.subscription(p, chatRoomId);
	}
	public record RoomCreateRequest(@NotNull @Positive Long listingId) {}
	public record MessageSendRequest(@NotBlank @Size(max=1000) String content) {}
	public record MessageReadRequest(@NotNull @Positive Long lastReadMessageId) {}
}
