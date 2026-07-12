package com.imsw.observe.kernel.transaction.spi;

public interface TransactionOperator {

    void execute(TransactionCallback action);

    @FunctionalInterface
    interface TransactionCallback {

        void run();
    }
}
