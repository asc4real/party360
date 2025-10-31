package com.lms.party360.idem;

import com.lms.party360.exception.Problem;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Component
public class IdempotencyStoreRedis implements IdempotencyStore, AutoCloseable {

    private final StatefulRedisConnection<String, String> stringConn;
    private final StatefulRedisConnection<String, String> binaryConn;
    private final IdempotencyProperties props;
    private final IdemCodec codec;

    public IdempotencyStoreRedis(RedisClient client,
                                 IdempotencyProperties props,
                                 IdemCodec codec) {
        // Two connections: one for strings (scripts & metadata) and one for binary payload field
        this.stringConn = client.connect(new io.lettuce.core.codec.StringCodec());
        this.binaryConn = client.connect();
        this.props = props == null ? IdempotencyProperties.defaults() : props;
        this.codec = Objects.requireNonNull(codec);
    }

    @Override
    public <T> T execute(String opcode, UUID key, byte[] requestHash, Supplier<T> supplier, Runnable onHit) throws Exception {
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(key, "idempotency key");
        Objects.requireNonNull(requestHash, "requestHash");
        String redisKey = redisKey(opcode, key);
        String b64hash  = IdemCodec.b64(requestHash);

        RedisCommands<String, String> cmd = stringConn.sync();

        String state = cmd.eval(LuaScripts.CREATE_OR_VALIDATE, ScriptOutputType.VALUE,
                new String[]{redisKey}, b64hash, String.valueOf(props.pendingTtlSeconds()));

        switch (state) {
            case "HASH_MISMATCH" -> throw Problem.conflict("IDEMPOTENCY_KEY_REUSED_DIFFERENT_REQUEST",
                    "Idempotency-Key was used with a different request body.");
            case "DONE" -> {
                // Return cached result
                byte[] payload = fetchPayload(redisKey, b64hash);
                T cached = codec.deserialize(payload);
                if (onHit != null) onHit.run();
                return cached;
            }
            case "PENDING" -> {
                // Another request is in-flight. Wait (bounded) for it to complete.
                T res = waitAndReuse(redisKey, b64hash);
                if (res != null) {
                    if (onHit != null) onHit.run();
                    return res;
                }
                // Not completed within window—become the leader by trying again from scratch: re-run supplier.
            }
            case "CREATED" -> {
                // We own the execution; proceed to supplier.
            }
            default -> throw Problem.internal("IDEMPOTENCY_REDIS_PROTOCOL",
                    "Unexpected Redis result: " + state);
        }

        // Execute supplier (the "leader" path)
        try {
            T result = supplier.get();

            byte[] payload = codec.serialize(result);
            if (payload.length > props.maxPayloadBytes()) {
                // avoid DOS by gigantic cached response
                throw Problem.internal("IDEMPOTENCY_PAYLOAD_TOO_LARGE",
                        "Response exceeds idempotency cache payload limit.");
            }

            // Atomically mark DONE and store payload
            var binCmd = binaryConn.sync();
            String ok = (String) binCmd.eval(LuaScripts.COMPLETE_SUCCESS, ScriptOutputType.VALUE,
                    redisKey, b64hash, Arrays.toString(payload), String.valueOf(props.ttlSeconds()));

            if (!"OK".equals(ok)) {
                // Another writer beat us (rare) or hash mismatch due to tampering.
                // Fallback: read final payload and return it to ensure consistency.
                byte[] finalPayload = fetchPayload(redisKey, b64hash);
                return codec.deserialize(finalPayload);
            }

            return result;

        } catch (RuntimeException ex) {
            // On failure, clean the PENDING key to allow a retry path.
            cmd.eval(LuaScripts.CLEAN_ON_FAILURE, ScriptOutputType.VALUE, new String[]{redisKey});
            throw ex;
        }
    }

    private <T> T waitAndReuse(String redisKey, String b64hash) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(props.waitMaxMillis()).toNanos();
        RedisCommands<String, String> scmd = stringConn.sync();

        while (System.nanoTime() < deadline) {
            // Fast check
            var status = scmd.hget(redisKey, "status");
            if ("DONE".equals(status)) {
                byte[] payload = fetchPayload(redisKey, b64hash);
                return codec.deserialize(payload);
            }
            // Backoff
            sleep(backoff());
        }
        return null;
    }

    private long backoff() {
        long min = props.waitBackoffMinMillis();
        long max = props.waitBackoffMaxMillis();
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private byte[] fetchPayload(String redisKey, String b64hash) throws Exception {
        var bcmd = binaryConn.sync();
        // Validate hash again to be safe
        String storedHash = stringConn.sync().hget(redisKey, "hash");
        if (storedHash == null) throw Problem.internal("IDEMPOTENCY_MISSING", "Cache record missing.");
        if (!storedHash.equals(b64hash)) {
            throw Problem.conflict("IDEMPOTENCY_KEY_REUSED_DIFFERENT_REQUEST",
                    "Idempotency-Key was used with a different request body.");
        }
        byte[] payload = bcmd.hget(redisKey, "payload").getBytes();
        if (payload == null) throw Problem.internal("IDEMPOTENCY_PAYLOAD_MISSING", "Payload missing for DONE record.");
        return payload;
    }

    private static String redisKey(String opcode, UUID key) {
        return "idem:" + opcode + ":" + key;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try { stringConn.close(); } catch (Exception ignored) {}
        try { binaryConn.close(); } catch (Exception ignored) {}
    }
}

