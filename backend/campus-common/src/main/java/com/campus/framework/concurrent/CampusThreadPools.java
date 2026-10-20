package com.campus.framework.concurrent;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池注册表 + 观测（阶段 18）。
 *
 * <p>解决的问题：{@code Executors.newFixedThreadPool(...)} 散落各处 → 没有名字、
 * 没有指标、出问题只能靠 jstack 猜。本组件把线程池集中登记：<b>具名、有界、可观测</b>，
 * 前端「🔒 并发控制」页直接展示每个池的活跃线程数 / 队列长度 / 峰值 / 拒绝次数。</p>
 *
 * <p>隔离的意义（呼应"线程池隔离"）：压测齐射、缓存回源、定时结算各用各的池——
 * 一个池打满不影响另一个，避免"压测把定时任务饿死"这类跨业务干扰。</p>
 */
@Slf4j
@Component
public class CampusThreadPools {

    /** 池名 → 池（含拒绝计数） */
    private final Map<String, PoolHolder> pools = new ConcurrentHashMap<>();

    /** 注册一个池（池名重复即覆盖） */
    public ThreadPoolExecutor register(String name, ThreadPoolBuilder builder) {
        PoolHolder holder = new PoolHolder();
        builder.rejected(new CountingRejectedHandler(name, holder));
        holder.executor = builder.build();
        pools.put(name, holder);
        log.info("[线程池] 注册 {}：core={} max={} queueCapacity={}", name,
                holder.executor.getCorePoolSize(), holder.executor.getMaximumPoolSize(),
                holder.executor.getQueue().remainingCapacity() + holder.executor.getQueue().size());
        return holder.executor;
    }

    /** 取或建（默认参数：有界队列 + 计数后快速失败） */
    public ThreadPoolExecutor get(String name) {
        PoolHolder holder = pools.computeIfAbsent(name, n -> {
            PoolHolder h = new PoolHolder();
            ThreadPoolBuilder b = ThreadPoolBuilder.builder(n);
            b.rejected(new CountingRejectedHandler(n, h));
            h.executor = b.build();
            return h;
        });
        return holder.executor;
    }

    /**
     * 压测齐射专用池：池大小 = 并发数（栅栏齐射要求所有任务先"就位"）。
     *
     * <p>用 SynchronousQueue（不排队）：第 N+1 个任务直接走拒绝策略（计数后抛异常），
     * 让"并发数超出池容量"成为显式错误而不是静默排队；核心线程允许超时回收，压测后不长期占线程。</p>
     */
    public ThreadPoolExecutor stressPool(int concurrency) {
        String name = "campus-stress-pool-" + concurrency;
        PoolHolder holder = pools.computeIfAbsent(name, n -> {
            PoolHolder h = new PoolHolder();
            ThreadPoolBuilder b = ThreadPoolBuilder.builder(n)
                    .core(concurrency).max(concurrency)
                    .directHandoff()
                    .allowCoreThreadTimeout(true)
                    .keepAliveSeconds(30);
            b.rejected(new CountingRejectedHandler(n, h));
            h.executor = b.build();
            log.info("[线程池] 注册压测池 {}：core=max={}（SynchronousQueue，不排队）", n, concurrency);
            return h;
        });
        return holder.executor;
    }

    /** 各池水位（前端看板） */
    public List<Map<String, Object>> stats() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, PoolHolder> e : pools.entrySet()) {
            ThreadPoolExecutor p = e.getValue().executor;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("core", p.getCorePoolSize());
            m.put("max", p.getMaximumPoolSize());
            m.put("poolSize", p.getPoolSize());
            m.put("active", p.getActiveCount());
            m.put("largest", p.getLargestPoolSize());
            m.put("queueSize", p.getQueue().size());
            // SynchronousQueue 的 remainingCapacity 是 Integer.MAX_VALUE（不排队，直接交接），单独标记
            boolean directHandoff = p.getQueue() instanceof java.util.concurrent.SynchronousQueue;
            m.put("directHandoff", directHandoff);
            m.put("queueCapacity", directHandoff ? 0 : p.getQueue().remainingCapacity() + p.getQueue().size());
            m.put("completed", p.getCompletedTaskCount());
            m.put("rejected", e.getValue().rejected.get());
            list.add(m);
        }
        list.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return list;
    }

    /** 全局拒绝总次数 */
    public long totalRejected() {
        return pools.values().stream().mapToLong(h -> h.rejected.get()).sum();
    }

    @PreDestroy
    public void shutdown() {
        pools.forEach((name, holder) -> {
            holder.executor.shutdown();
            log.info("[线程池] 关闭 {}（累计完成任务 {}，拒绝 {}）", name,
                    holder.executor.getCompletedTaskCount(), holder.rejected.get());
        });
    }

    /** 池 + 拒绝计数（拒绝计数与池一一绑定，避免 handler 与池错配） */
    private static class PoolHolder {
        ThreadPoolExecutor executor;
        final AtomicLong rejected = new AtomicLong();
    }

    /**
     * 拒绝处理器：先计数，再快速失败。
     *
     * <p>计数是关键——"任务被静默丢弃"是线程池最危险的失败模式：调用方以为提交成功，
     * 业务却永远等不到结果。这里的策略是抛 {@link java.util.concurrent.RejectedExecutionException}，
     * 让调用方显式感知（压测场景会把它算作一次失败请求）。</p>
     */
    private static class CountingRejectedHandler implements RejectedExecutionHandler {
        private final String name;
        private final PoolHolder holder;

        CountingRejectedHandler(String name, PoolHolder holder) {
            this.name = name;
            this.holder = holder;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            holder.rejected.incrementAndGet();
            log.warn("[线程池] {} 拒绝任务（活跃 {}/{}，队列 {}）", name,
                    executor.getActiveCount(), executor.getMaximumPoolSize(), executor.getQueue().size());
            throw new java.util.concurrent.RejectedExecutionException(
                    "线程池 " + name + " 已满（活跃 " + executor.getActiveCount() + "）");
        }
    }
}
