package com.campus.framework.cache;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.function.Supplier;

/**
 * 缓存门面（阶段 16）。
 *
 * <p>三个入口，语义各不相同（对应三个真实问题）：</p>
 * <ol>
 *   <li>{@link #get}：普通读透缓存（miss 即回源）——用在"不被高并发击穿"的普通查询；</li>
 *   <li>{@link #safeGet}：<b>防击穿</b>读透（miss 时互斥回源 + 双检 + 空值回填）——
 *       用在热点 key（如某场地全天的时段格子）；</li>
 *   <li>{@link #getDirect}：绕缓存直查（对比演示/强一致读）。</li>
 * </ol>
 *
 * <p>实现约定：值以 JSON 存储；loader 返回 null 时写"空值回填"标记（短 TTL），
 * 用于挡住"查不存在的资源 id"这类穿透流量。</p>
 */
public interface CampusCache {

    /**
     * 读透（miss 直接回源，不加锁）。
     *
     * @param key        缓存 key（见 {@link CampusCacheKeys}）
     * @param type       反序列化类型（泛型需用 {@link TypeReference}）
     * @param ttlSeconds 写入 TTL（秒）
     * @param loader     回源函数
     */
    <T> T get(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader);

    /**
     * 防击穿读透：miss 时先抢分布式锁，抢到者单线程回源（双检），未抢到者短暂等待后重读。
     */
    <T> T safeGet(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader);

    /** 绕缓存直查（回源并刷新缓存）——用于"有缓存 vs 无缓存"对照演示。 */
    <T> T getDirect(String key, TypeReference<T> type, long ttlSeconds, Supplier<T> loader);

    /** 主动失效（写后失效，cache-aside 的标准动作）。 */
    void evict(String key);

    /** 按前缀批量失效（列表页缓存用：分页参数组合多，按前缀一把清）。 */
    long evictByPrefix(String prefix);

    boolean hasKey(String key);
}
