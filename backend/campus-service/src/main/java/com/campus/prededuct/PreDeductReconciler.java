package com.campus.prededuct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预扣计数器对账器（阶段 17）：每 30 秒把 Redis 预计数拉回数据库真值。
 *
 * <p>它兜住的是这些异常路径：进程崩溃导致漏回补、Redis 手动清 key 后局部状态不一致、
 * 绕过闸门的写入（如压测夹具直接改库）造成的长期漂移。</p>
 *
 * <p>并发安全：对账逻辑整体持分布式锁（{@code campus:lock:prededuct:reconcile}），
 * 多实例部署时只有一个实例真正执行——这正是阶段 18 分布式锁的第一个生产用途。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreDeductReconciler {

    private final PreDeductService preDeductService;

    @Value("${campus.prededuct.reconcile-enabled:true}")
    private boolean enabled;

    /**
     * 启动即对账一轮：认领 Redis 里遗留的计数器并校验偏差。
     *
     * <p>没有这一步，服务重启后要等 30 秒才有第一轮——而 restart 往往紧跟着"手工清库/夹具重置"，
     * 那正是旧计数器最容易变成误拦值（如残留 0）的时刻。启动立即校准，把窗口压到秒级。</p>
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        reconcile();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reconcile() {
        if (!enabled) {
            return;
        }
        var result = preDeductService.reconcileAll();
        if (result.containsKey("skipped")) {
            log.debug("[预扣·对账] 本轮跳过: {}", result.get("skipped"));
            return;
        }
        int fixed = ((Number) result.getOrDefault("fixed", 0)).intValue();
        if (fixed > 0) {
            log.warn("[预扣·对账] 本轮检查 {} 个计数器，纠偏 {} 个（累计偏差 {}）",
                    result.get("checked"), fixed, result.get("driftSum"));
        }
    }
}
