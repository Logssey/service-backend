package com.reused.attacklab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.reused.common.error.GlobalExceptionHandler;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttackLabControllerTest {
    private static final String TOKEN = "test-only-random-token-32-characters-minimum";

    @Test void missingProfileOrFlagDoesNotRegisterEndpoints() {
        new ApplicationContextRunner().withUserConfiguration(AttackLabController.class, FixedShellRunner.class)
                .run(c -> assertTrue(c.getBeansOfType(AttackLabController.class).isEmpty()));
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=attack-lab")
                .withUserConfiguration(AttackLabController.class, FixedShellRunner.class)
                .run(c -> assertTrue(c.getBeansOfType(AttackLabController.class).isEmpty()));
    }

    @Test void invalidNamespaceOrWeakTokenFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new AttackLabController(TOKEN, "reused", mock(FixedShellRunner.class)));
        assertThrows(IllegalStateException.class, () -> new AttackLabController("short", "reused-attack-lab", mock(FixedShellRunner.class)));
    }

    @Test void unauthorizedAndCommandBodiesNeverExecute() throws Exception {
        var runner = mock(FixedShellRunner.class);
        var c = new AttackLabController(TOKEN, "reused-attack-lab", runner);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> c.runtime(null, null)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> c.runtime("wrong", null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> c.runtime(TOKEN, "id")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test void cryptoProbeIsBenignAndSeparateFromShell() {
        var runner = mock(FixedShellRunner.class);
        var c = new AttackLabController(TOKEN, "reused-attack-lab", runner);
        assertEquals(true, c.crypto(TOKEN, null).get("executed"));
        verifyNoInteractions(runner);
    }

    @Test void runtimeCallsOnlyFixedRunner() throws Exception {
        var runner = mock(FixedShellRunner.class);
        when(runner.run()).thenReturn(true);
        var c = new AttackLabController(TOKEN, "reused-attack-lab", runner);
        assertEquals(true, c.runtime(TOKEN, null).get("executed"));
        verify(runner).run();
    }

    @Test void httpRejectionsRemainClientErrorsWithProductionAdvice() throws Exception {
        var runner = mock(FixedShellRunner.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AttackLabController(TOKEN, "reused-attack-lab", runner))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var path = "/internal/attack-lab/runtime/java-spawns-shell";
        mvc.perform(post(path)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Attack-Lab-Token", TOKEN).contentType("text/plain").content("id"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runner);
    }
}
