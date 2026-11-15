package com.telemed.intelligentagent.application.port.out;

import com.telemed.intelligentagent.domain.model.Message;

import java.util.List;
import java.util.UUID;

/** agent-db's messages collection. */
public interface MessageRepository {

    /** The conversation's messages, oldest first. */
    List<Message> findByConversation(UUID conversationId);

    void save(Message message);
}
