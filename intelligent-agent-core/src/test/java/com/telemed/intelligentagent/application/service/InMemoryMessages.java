package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.MessageRepository;
import com.telemed.intelligentagent.domain.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test double of the messages collection. */
class InMemoryMessages implements MessageRepository {

    final List<Message> stored = new ArrayList<>();

    @Override
    public List<Message> findByConversation(UUID conversationId) {
        return stored.stream().filter(m -> m.conversationId().equals(conversationId)).toList();
    }

    @Override
    public void save(Message message) {
        stored.add(message);
    }
}
