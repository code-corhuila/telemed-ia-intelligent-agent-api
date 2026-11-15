package com.telemed.intelligentagent.application.service;

import com.telemed.intelligentagent.application.port.out.UnitOfWork;

import java.util.ArrayList;
import java.util.List;

/** Runs work right away, or holds it until commit() to prove every write sits inside one unit. */
class TestUnitOfWork implements UnitOfWork {

    private final List<Runnable> pending = new ArrayList<>();
    private boolean deferring;
    int units;

    void defer() {
        deferring = true;
    }

    void commit() {
        pending.forEach(Runnable::run);
        pending.clear();
    }

    @Override
    public void execute(Runnable work) {
        units++;
        if (deferring) {
            pending.add(work);
        } else {
            work.run();
        }
    }
}
