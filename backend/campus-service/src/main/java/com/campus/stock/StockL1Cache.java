package com.campus.stock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段 20 · 读路径第一层：进程内快照缓存（键 = 课程，条目带课程版本）。
 *
 * <p>三条设计约束（与需求对齐）：</p>
 * <ol>
 *   <li><b>以「课程 + 版本号」为逻辑键</b>：快照天然不可变（版本变了就换新条目），
 *       所以<b>不需要主动失效也能正确</b>；</li>
 *   <li><b>必须有 TTL</b>：Pub/Sub 是尽力而为——本实例订阅断线漏信号时，
 *       "本地已知最新版本"会停滞，2 秒 TTL 把最坏陈旧窗口封顶；</li>
 *   <li><b>必须有容量上限</b>：超限整体清空（TTL 只有秒级，重建极廉价），防无界增长。</li>
 * </ol>
 *
 * <p>正常路径上，信号到达时会主动 {@link #invalidate(Long)} 对应课程，命中率由信号驱动；
 * TTL 只兜"漏信号"的底。</p>
 */
@Slf4j
@Component
public class StockL1Cache {

    private record Entry(long cver, StockSnapshot snapshot, long expireAt) {
    }

    private final ConcurrentHashMap<Long, Entry> map = new ConcurrentHashMap<>();

    @Value("${campus.stock.l1-ttl-ms:2000}")
    private long ttlMs;

    @Value("${campus.stock.l1-max-entries:2000}")
    private int maxEntries;

    /**
     * 命中返回快照（0 次网络调用），否则 null。
     * 命中条件：客户端携带的版本号 == 本地条目版本 且 未过期。
     */
    public StockSnapshot get(Long courseId, long clientVersion) {
        Entry e = map.get(courseId);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() >= e.expireAt()) {
            map.remove(courseId, e);
            return null;
        }
        return e.cver() == clientVersion ? e.snapshot() : null;
    }

    public void put(Long courseId, StockSnapshot snapshot) {
        if (snapshot == null || snapshot.degraded()) {
            return;   // 兜底快照不入缓存
        }
        if (map.size() >= maxEntries) {
            map.clear();   // 粗粒度保护：TTL 秒级，清空后自然重建
            log.info("[名额同步] L1 达到上限 {}，整体清空", maxEntries);
        }
        map.put(courseId, new Entry(snapshot.cver(), snapshot, System.currentTimeMillis() + ttlMs));
    }

    /** 收到该课程信号后调用：让下一次查询必然走 L2 */
    public void invalidate(Long courseId) {
        if (courseId != null) {
            map.remove(courseId);
        }
    }

    /** 惰性清扫：兜住"无人再查"的过期条目 */
    @Scheduled(fixedDelay = 10_000)
    public void sweep() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now >= e.getValue().expireAt());
    }

    public int size() {
        return map.size();
    }
}
