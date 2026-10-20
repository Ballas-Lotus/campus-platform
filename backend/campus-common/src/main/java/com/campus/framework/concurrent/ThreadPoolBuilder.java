package com.campus.framework.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池构建器（Builder 模式，阶段 18）。
 *
 * <p>为什么要有它：JDK 的 {@link ThreadPoolExecutor} 构造器有 7 个参数、顺序易错，
 * 且 {@code Executors.newFixedThreadPool} 这类工厂把队列设成无界（
 * {@code LinkedBlockingQueue} 默认容量 Integer.MAX_VALUE）——高并发下任务堆积到 OOM 才暴露。
 * Builder 强制调用方显式回答三个问题：<b>核心/最大线程数、队列容量、满了怎么办</b>。</p>
 *
 * <p>默认值参考 12306 的 {@code ThreadPoolBuilder}：核心 = CPU/0.2（即 CPU×5），
 * 最大 = 核心×1.5，队列有界 4096，拒绝策略 AbortPolicy（宁可快速失败，不无限堆积）。</p>
 */
public class ThreadPoolBuilder {

    /** 队列形态：有界（默认）/ 无界 / 直接交接（SynchronousQueue） */
    private enum QueueType { BOUNDED, UNBOUNDED, SYNCHRONOUS }

    private final String name;
    private int coreSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 5);
    private int maxSize = coreSize * 3 / 2;
    private int queueCapacity = 4096;
    private long keepAliveSeconds = 60;
    private boolean allowCoreThreadTimeout = true;
    private QueueType queueType = QueueType.BOUNDED;
    private RejectedExecutionHandler rejectedHandler = new ThreadPoolExecutor.AbortPolicy();

    private ThreadPoolBuilder(String name) {
        this.name = name;
    }

    public static ThreadPoolBuilder builder(String name) {
        return new ThreadPoolBuilder(name);
    }

    public ThreadPoolBuilder core(int core) {
        this.coreSize = core;
        return this;
    }

    public ThreadPoolBuilder max(int max) {
        this.maxSize = max;
        return this;
    }

    /** 队列容量（有界队列——把"排队"变成"可观测的等待"，而不是无界堆积） */
    public ThreadPoolBuilder queue(int capacity) {
        this.queueCapacity = capacity;
        this.queueType = QueueType.BOUNDED;
        return this;
    }

    /** 无界队列（谨慎使用：任务会无限堆积，是 Executors.newFixedThreadPool 的经典隐患） */
    public ThreadPoolBuilder unboundedQueue() {
        this.queueType = QueueType.UNBOUNDED;
        return this;
    }

    /**
     * 直接交接：{@link java.util.concurrent.SynchronousQueue}（不排队，来了就交给线程；
     * 无线程可用则触发拒绝策略）——栅栏式齐射场景用，"并发数超出池容量"必须显式失败而非静默排队。
     */
    public ThreadPoolBuilder directHandoff() {
        this.queueType = QueueType.SYNCHRONOUS;
        return this;
    }

    public ThreadPoolBuilder keepAliveSeconds(long seconds) {
        this.keepAliveSeconds = seconds;
        return this;
    }

    public ThreadPoolBuilder allowCoreThreadTimeout(boolean allow) {
        this.allowCoreThreadTimeout = allow;
        return this;
    }

    public ThreadPoolBuilder rejected(RejectedExecutionHandler handler) {
        this.rejectedHandler = handler;
        return this;
    }

    public ThreadPoolExecutor build() {
        BlockingQueue<Runnable> queue = switch (queueType) {
            case BOUNDED -> new ArrayBlockingQueue<>(queueCapacity);
            case UNBOUNDED -> new LinkedBlockingQueue<>();
            case SYNCHRONOUS -> new SynchronousQueue<>();
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize, Math.max(maxSize, coreSize), keepAliveSeconds, TimeUnit.SECONDS,
                queue, new NamedThreadFactory(name), rejectedHandler);
        executor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        return executor;
    }

    /** 具名线程工厂：线程名带池名与序号，jstack/日志一眼定位（"哪个池的线程卡住了"） */
    static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}
