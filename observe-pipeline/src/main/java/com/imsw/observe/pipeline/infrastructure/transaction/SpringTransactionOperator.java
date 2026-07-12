package com.imsw.observe.pipeline.infrastructure.transaction;

import org.springframework.transaction.support.TransactionTemplate;

import com.imsw.observe.kernel.transaction.spi.TransactionOperator;

public final class SpringTransactionOperator implements TransactionOperator {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionOperator(final TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void execute(final TransactionCallback action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
