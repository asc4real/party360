package com.lms.party360.idem;

import java.util.UUID;
import java.util.function.Supplier;

public interface IdempotencyStore {
    /**
     * Executes the supplier only if this (opcode, key, requestHash) hasn't produced a final result.
     * - If a matching DONE record exists -> return cached result and call onHit.run().
     * - If a record exists but hash mismatches -> 409 conflict.
     * - If PENDING by another request -> wait (bounded), then reuse or execute if cleared.
     */
    <T> T execute(String opcode, UUID key, byte[] requestHash, Supplier<T> supplier, Runnable onHit) throws Exception;
}
