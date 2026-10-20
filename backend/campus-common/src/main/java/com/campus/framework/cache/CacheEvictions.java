package com.campus.framework.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 写后失效的时序工具（阶段 16）。
 *
 * <p>Cache-Aside 的经典坑：<b>在事务提交前失效缓存</b>，会留下这个竞态窗口——</p>
 * <pre>
 *   T1: 更新数据库（未提交）
 *   T1: 删缓存
 *   T2: 读缓存 → miss → 回源读到「旧值」（因为 T1 还没提交）→ 把旧值写回缓存
 *   T1: 提交
 *   → 缓存里长期是旧值（直到 TTL 到期）
 * </pre>
 *
 * <p>把失效动作挂到<b>事务提交之后</b>（afterCommit），窗口就缩到"提交完成 → 删除完成"之间，
 * 而这段时间里并发的回源读到的是新值——即使写回缓存也是新值。这是成本最低的修法；
 * 更严格的做法（延迟双删 / binlog 驱动失效）见 docs/09。</p>
 */
@Slf4j
public final class CacheEvictions {

    private CacheEvictions() {
    }

    /** 事务提交后执行（无事务上下文则立即执行） */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    log.warn("[缓存] 提交后失效失败（等 TTL 自然过期）: {}", e.getMessage());
                }
            }
        });
    }
}
