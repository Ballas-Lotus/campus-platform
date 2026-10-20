package com.campus.mq.consumer;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量消费指标（阶段 19）：批次 / 消息数 / 平均批大小 / 幂等跳过 / 落库数。
 *
 * <p>批量消费的价值在于"一次拉取、一次 ACK 处理多条"——平均批大小是这个收益的直接度量；
 * 低流量下批次会退化为 1 条/批（与逐条消费等价），突发流量下批大小上升。</p>
 */
@Component
public class BatchConsumeMetrics {

    private final AtomicLong batches = new AtomicLong();       // 消费批次
    private final AtomicLong messages = new AtomicLong();      // 处理消息总数
    private final AtomicLong inserted = new AtomicLong();      // 落库成功数
    private final AtomicLong duplicates = new AtomicLong();    // 幂等跳过（重复投递）
    private final AtomicLong failed = new AtomicLong();        // 处理失败（触发重试）
    private final AtomicLong maxBatchSize = new AtomicLong();
    private final AtomicLong lastBatchSize = new AtomicLong();
    private final AtomicLong lastMs = new AtomicLong();

    public void onBatch(int size) {
        batches.incrementAndGet();
        messages.addAndGet(size);
        lastBatchSize.set(size);
        maxBatchSize.accumulateAndGet(size, Math::max);
    }

    public void onProcessed(int insertedCount, int duplicateCount, long elapsedMs) {
        inserted.addAndGet(insertedCount);
        duplicates.addAndGet(duplicateCount);
        lastMs.set(elapsedMs);
    }

    public void onFailed() {
        failed.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long b = batches.get();
        m.put("batches", b);
        m.put("messages", messages.get());
        m.put("inserted", inserted.get());
        m.put("duplicates", duplicates.get());
        m.put("failed", failed.get());
        m.put("lastBatchSize", lastBatchSize.get());
        m.put("maxBatchSize", maxBatchSize.get());
        m.put("lastMs", lastMs.get());
        m.put("avgBatchSize", b == 0 ? 0d : Math.round(messages.get() * 100.0 / b) / 100.0);
        m.put("note", "批量消费：一次拉取最多 20 条、一次 ACK；低流量退化为 1 条/批（与逐条等价）");
        return m;
    }
}
