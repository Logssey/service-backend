package com.reused.attacklab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.bouncycastle.crypto.engines.GOST3412_2015Engine;
import org.bouncycastle.crypto.modes.G3413CTRBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Two independent controls, NOT a claim that the crypto issue causes shell execution. */
@RestController
@Profile("attack-lab")
@ConditionalOnProperty(name = "attack-lab.enabled", havingValue = "true")
public class AttackLabController {
    private final byte[] token;
    private final FixedShellRunner runner;

    public AttackLabController(@Value("${attack-lab.token:}") String token,
                              @Value("${attack-lab.namespace:}") String namespace,
                              FixedShellRunner runner) {
        if (token.length() < 32 || !"reused-attack-lab".equals(namespace)) {
            throw new IllegalStateException("Lab requires an isolated namespace and a strong temporary token");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.runner = runner;
    }

    private void authorize(String supplied, String body) {
        if (supplied == null || supplied.length() > 256
                || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (body != null && !body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No input is accepted");
        }
    }

    @PostMapping("/internal/attack-lab/crypto/probe")
    public Map<String, Object> crypto(@RequestHeader(value = "X-Attack-Lab-Token", required = false) String supplied,
                                      @RequestBody(required = false) String body) {
        authorize(supplied, body);
        // Benign 16-byte use of an existing supported analysis target. No exploit or reused key/IV.
        byte[] key = new byte[32];
        byte[] iv = new byte[8];
        var random = new SecureRandom();
        random.nextBytes(key);
        random.nextBytes(iv);
        var cipher = new G3413CTRBlockCipher(new GOST3412_2015Engine());
        cipher.init(true, new ParametersWithIV(new KeyParameter(key), iv));
        cipher.processBytes(new byte[16], 0, 16, new byte[16], 0);
        return Map.of("scenarioId", "REUSED-REACH-01", "executed", true,
                "simulation", true, "timestamp", Instant.now().toString());
    }

    @PostMapping("/internal/attack-lab/runtime/java-spawns-shell")
    public Map<String, Object> runtime(@RequestHeader(value = "X-Attack-Lab-Token", required = false) String supplied,
                                       @RequestBody(required = false) String body) throws Exception {
        authorize(supplied, body);
        return Map.of("scenarioId", "REUSED-RUNTIME-01", "executed", runner.run(),
                "simulation", true, "timestamp", Instant.now().toString());
    }
}
