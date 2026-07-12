package com.imsw.observe.pipeline.infrastructure.transaction;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.imsw.observe.kernel.transaction.spi.TransactionOperator;

/**
 * dry-run 用的事务操作器：开真事务（让 db.* 复用），但 callback 跑完强制 setRollbackOnly，
 * 事务结束时 Spring 自动回滚。
 *
 * <p>效果：db.queryOne/queryAll 真查（读已返回），db.update/call + 告警落库真执行但回滚，
 * DB 零副作用。dry-run 调 SP 前请确认 SP 无事务外副作用（发邮件/调外部 HTTP 等，这些不回滚）。
 */
public final class DryRunTransactionOperator implements TransactionOperator {

    private final PlatformTransactionManager txManager;

    public DryRunTransactionOperator(final PlatformTransactionManager txManager) {
        this.txManager = txManager;
    }

    @Override
    public void execute(final TransactionCallback action) {
        new TransactionTemplate(txManager).execute(status -> {
            action.run();
            status.setRollbackOnly();
            return null;
        });
    }
}
