package com.telemed.intelligentagent.application.port.out;

/**
 * All of a use case's writes, committed together or not at all. Slow calls
 * (the AI model) happen before it, so a transaction is never held open on them.
 */
public interface UnitOfWork {

    void execute(Runnable work);
}
