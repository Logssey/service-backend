package com.reused.block;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.reused.common.security.*;
import com.reused.listing.query.CursorPageResponse;
import com.reused.block.BlockService.*;

@RestController
@RequestMapping("/api/v1/blocks")
public class BlockController {
    private final BlockService service;
    public BlockController(BlockService service) { this.service = service; }
    @PostMapping public BlockCreateResponse create(@AuthUser AuthPrincipal p, @Valid @RequestBody BlockCreateRequest request) {
        return service.create(p, request.userId());
    }
    @DeleteMapping("/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthUser AuthPrincipal p, @PathVariable Long userId) { service.delete(p, userId); }
    @GetMapping public CursorPageResponse<BlockResponse> list(@AuthUser AuthPrincipal p,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer size) {
        return service.list(p,cursor,size);
    }
    public record BlockCreateRequest(@NotNull @Positive Long userId) {}
}
