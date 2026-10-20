package com.campus.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 教学班余量读模型对账器（阶段 19）：每 30 秒把 {@code campus:cache:class_stock:*} 拉回数据库真值。
 *
 * <p>兜住的异常路径与预扣对账同源：绕过读模型的库内变更（夹具重置/管理脚本）、进程崩溃导致的
 * 漏增量、以及"增量与对账并发"留下的 ±1 显示偏差。对账只修<b>展示读模型</b>——闸门判定与
 * MySQL 真相不受影响。多实例下用分布式锁（{@code campus:lock:cache:stock:reconcile}）只跑一个。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassStockReconciler {

    private final ClassStockCache classStockCache;

    @Value("${campus.cache.class-stock.reconcile-enabled:true}")
    private boolean enabled;

    /** 单轮最多检查的 Hash 数（SCAN 采样上限，避免大基数下对账过重） */
    @Value("${campus.cache.class-stock.reconcile-max-keys:50}")
    private int maxKeys;

    /** 启动即对账一轮：认领重启前遗留的余量 Hash（与预扣对账同一动机） */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reconcile();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    public void reconcile() {
        if (!enabled) {
            return;
        }
        var result = classStockCache.reconcile(maxKeys);
        if (result.containsKey("skipped")) {
            log.debug("[读模型·对账] 本轮跳过: {}", result.get("skipped"));
            return;
        }
        int fixed = ((Number) result.getOrDefault("fixed", 0)).intValue();
        if (fixed > 0) {
            log.warn("[读模型·对账] 本轮扫描 {} 个 Hash，检查 {} 个，纠偏/清理 {} 个",
                    result.get("scanned"), result.get("checked"), fixed);
        }
    }
}
