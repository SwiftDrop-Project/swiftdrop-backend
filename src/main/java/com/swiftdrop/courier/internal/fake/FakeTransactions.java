// TEMPORARY - delete with the fakes.
package com.swiftdrop.courier.internal.fake;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Makes the in-memory fakes behave like the database: a change made inside a transaction is undone when that
 * transaction rolls back. Outside a transaction a change is simply kept.
 */
final class FakeTransactions {

    private FakeTransactions() {
    }

    /**
     * Remembers {@code undo} for the current transaction of {@code fake}. On rollback the undos run newest first, so
     * several changes to the same record end at its state before the transaction.
     */
    static void onRollback(Object fake, Runnable undo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Deque<Runnable> undos = (Deque<Runnable>) TransactionSynchronizationManager.getResource(fake);
        if (undos == null) {
            Deque<Runnable> created = new ArrayDeque<>();
            TransactionSynchronizationManager.bindResource(fake, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(fake);
                    if (status == STATUS_ROLLED_BACK) {
                        created.forEach(Runnable::run);
                    }
                }
            });
            undos = created;
        }
        undos.push(undo);
    }
}
