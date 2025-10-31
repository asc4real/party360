package com.lms.party360.events.publisher;

import com.lms.party360.util.Headers;
import lombok.NonNull;

// Optional: extend your existing port if needed
public interface OutboxWriter {
    void enqueue(@NonNull String aggregateId, @NonNull String type,
                 @NonNull Object payload, @NonNull Headers headers);

    default void enqueueWithKey(@NonNull String aggregateId, @NonNull String key,
                                @NonNull String type, @NonNull Object payload,
                                @NonNull Headers headers) {
        // adapter; implemented in OutboxWriterJdbc
        throw new UnsupportedOperationException();
    }
}

