package com.reused.common.security;

import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** All marketplace writes use user -> block pair -> listing -> room/trade lock order. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class MarketLocks {
    private final JdbcTemplate jdbc;
    public MarketLocks(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void users(long... userIds) {
        for (long userId : Arrays.stream(userIds).distinct().sorted().toArray()) {
            jdbc.query("SELECT user_id FROM users WHERE user_id = ? FOR SHARE", (rs,n)->rs.getLong(1), userId);
        }
    }
    public void blockPair(long first, long second) {
        String key = "reused:block:" + Math.min(first,second) + ":" + Math.max(first,second);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (rs,n)->true, key);
    }
}
