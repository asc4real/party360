package com.lms.party360.idem;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(
        long ttlSeconds,            // e.g., 172800 (48h)
        long pendingTtlSeconds,     // e.g., 30
        int  maxPayloadBytes,       // e.g., 262144 (256 KiB)
        long waitMaxMillis,         // e.g., 1500
        long waitBackoffMinMillis,  // e.g., 25
        long waitBackoffMaxMillis   // e.g., 100
) {
    public static IdempotencyProperties defaults() {
        return new IdempotencyProperties(172800, 30, 262_144, 1500, 25, 100);
    }
}
