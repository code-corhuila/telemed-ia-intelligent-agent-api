package com.telemed.intelligentagent.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** agent-db's idempotency_records: which resource a caller's key already created, per operation. */
public interface IdempotencyRecords {

    enum Operation {
        START_PRECONSULTATION
    }

    Optional<UUID> find(UUID principalId, Operation operation, String key);

    void save(UUID principalId, Operation operation, String key, UUID resourceId, Instant now);
}
