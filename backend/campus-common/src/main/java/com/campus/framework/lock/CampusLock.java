package com.campus.framework.lock;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 分布式锁门面（阶段 16 起用：缓存防击穿 → 阶段 18 扩展到定时任务/幂等）。
 *
 * <p>实现选型说明（重要，面试会被问）：<b>没有引入 Redisson</b>，而是基于
 * {@code SET key token NX PX ttl} + {@code Lua} 校验后删除自研实现。理由：</p>
 * <ol>
 *   <li>本项目锁的用途都是"短临界区"（防击穿回源 / 定时任务防重跑 / 预热初始化），
 *       不需要可重入、看门狗续期、公平锁等 Redisson 的高级形态；</li>
 *   <li>不新增重量级传递依赖（Redisson 会带入 netty 等一整套），减少与
 *       RocketMQ/Sentinel 现有 netty 版本的冲突面；</li>
 *   <li>{@code SET NX PX + Lua 释放}本身就是 Redis 官方推荐的锁实现，语义透明、可压测。
 *       若将来需要可重入/续期，{@link CampusLock} 接口不变，替换实现即可（Redisson RLock 适配）。</li>
 * </ol>
 *
 * <p>安全性三点：① 值用唯一 token（释放时校验，绝不误删他人锁）；② 必设 TTL
 * （持有者崩溃后锁自动释放，不会永久死锁）；③ 释放走 Lua（校验 + 删除原子）。</p>
 *
 * <p>诚实的边界：单 Redis 主从架构下，主节点故障切换可能丢锁（RedLock 争议同源）。
 * 本项目锁保护的都是"重复执行无害/可幂等"的动作（回源重建、扫描），不承担唯一正确性——
 * 真正的唯一性仍由数据库唯一索引兜底。</p>
 */
public interface CampusLock {

    /**
     * 获取锁（自旋等待）。
     *
     * @param key    锁 key（调用方自带业务前缀）
     * @param waitMs 最长等待毫秒（0 = 只试一次）
     * @param ttlMs  锁自动过期毫秒（须大于临界区耗时）
     * @return 持有凭证 token；null = 未获取到
     */
    String tryAcquire(String key, long waitMs, long ttlMs);

    /** 释放锁（Lua 校验 token 后删除）。返回是否真正释放。 */
    boolean release(String key, String token);

    /** 便捷封装：抢到锁执行 action 并返回；抢不到返回 null（调用方按"跳过"处理）。 */
    <T> T executeWithLock(String key, long waitMs, long ttlMs, Supplier<T> action);

    /** 观测：获取/竞争/超时计数 + 当前持有者快照 */
    Map<String, Object> stats();
}
