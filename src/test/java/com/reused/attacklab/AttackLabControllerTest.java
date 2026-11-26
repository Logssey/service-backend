package com.reused.attacklab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttackLabControllerTest {
    private static final String TOKEN = "test-only-random-token-32-characters-minimum";

    @Test void missingProfileOrFlagDoesNotRegisterEndpoints() {
        new ApplicationContextRunner().withUserConfiguration(AttackLabController.class, FixedShellRunner.class)
                .run(c -> assertFalse(c.containsBean("attackLabController")));
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=attack-lab")
                .withUserConfiguration(AttackLabController.class, FixedShellRunner.class)
                .run(c -> assertFalse(c.containsBean("attackLabController")));
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
}
