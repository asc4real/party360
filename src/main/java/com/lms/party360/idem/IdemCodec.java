package com.lms.party360.idem;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class IdemCodec {

    private final ObjectMapper om;

    public IdemCodec(ObjectMapper om) {
        // Enable default typing ONLY for this dedicated mapper to store type info safely.
        this.om = om.copy()
                .activateDefaultTyping(om.getPolymorphicTypeValidator(),
                        ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    }

    public byte[] serialize(Object value) {
        try {
            return om.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotency payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes) {
        try {
            return (T) om.readValue(bytes, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize idempotency payload", e);
        }
    }

    public static String b64(byte[] hash) {
        return Base64.getEncoder().encodeToString(hash);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

