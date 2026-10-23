package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.IdempotencyRecords;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double of the idempotency_records collection. */
class InMemoryIdempotencyRecords implements IdempotencyRecords {

    final Map<String, UUID> stored = new LinkedHashMap<>();

    @Override
    public Optional<UUID> find(UUID principalId, Operation operation, String key) {
        return Optional.ofNullable(stored.get(principalId + "|" + operation + "|" + key));
    }

    @Override
    public void save(UUID principalId, Operation operation, String key, UUID resourceId, Instant now) {
        stored.put(principalId + "|" + operation + "|" + key, resourceId);
    }
}
