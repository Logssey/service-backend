package com.reused.notification.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.listing.query.CursorPageResponse;
import com.reused.notification.service.NotificationService;
import com.reused.notification.service.NotificationService.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }
    @GetMapping public CursorPageResponse<NotificationResponse> list(@AuthUser AuthPrincipal p,
            @RequestParam(required=false) Boolean unreadOnly, @RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer size) { return service.list(p, unreadOnly, cursor, size); }
    @GetMapping("/unread-count") public Map<String, Long> unread(@AuthUser AuthPrincipal p) {
        return Map.of("count", service.unread(p));
    }
    @PostMapping("/{notificationId}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthUser AuthPrincipal p, @PathVariable Long notificationId) { service.read(p, notificationId); }
    @PostMapping("/read-all") public Map<String, Integer> readAll(@AuthUser AuthPrincipal p) {
        return Map.of("readCount", service.readAll(p));
    }
    @GetMapping("/settings") public NotificationSettingsResponse settings(@AuthUser AuthPrincipal p) { return service.settings(p); }
    @PatchMapping("/settings") public NotificationSettingsResponse settings(@AuthUser AuthPrincipal p,
            @RequestBody NotificationSettingsUpdateRequest request) { return service.updateSettings(p, request); }
}
