package com.reused.attacklab;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("attack-lab")
@ConditionalOnProperty(name = "attack-lab.enabled", havingValue = "true")
public class FixedShellRunner {
    private long lastRun;
    private int count;

    public synchronized boolean run() throws Exception {
        long now = System.nanoTime();
        if (count >= 10 || (count > 0 && now - lastRun < TimeUnit.SECONDS.toNanos(5))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        lastRun = now;
        count++;
        var builder = new ProcessBuilder("/bin/sh", "-c", "/usr/bin/id");
        builder.environment().clear();
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) return false;
            return process.exitValue() == 0;
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
