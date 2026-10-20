package com.campus.stock;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 名额实时同步 / 受理制的观测计数（沿用本项目"每一层都可量化"的惯例）。
 *
 * <p>阶段 20 指标：推送次数 / 发布成功 / 收到信号 / 扇出帧数 / 慢连接摘除 /
 * L1 命中与未命中 / 重建次数 / 降级返回 / 对账纠偏。</p>
 *
 * <p>阶段 21（受理制）新增：受理成功 / 受理满员拒绝 / 重复受理 / 已选拒绝 /
 * 受理降级（回落同步老链路）/ MQ 落库消息发送失败 / 补偿次数 / 落库回执（成功|拒绝）/
 * 超时裁决次数。</p>
 */
@Component
public class StockMetrics {

    private final LongAdder pushes = new LongAdder();          // 写后推送（版本+1 成功）
    private final LongAdder pushSkipped = new LongAdder();     // 推送被跳过（结构缺失且重建未果）
    private final LongAdder published = new LongAdder();       // PUBLISH 成功
    private final LongAdder signalsIn = new LongAdder();       // 本实例收到的信号
    private final LongAdder fanoutFrames = new LongAdder();    // 扇出的 SSE 帧数
    private final LongAdder fanoutDropped = new LongAdder();   // 发送失败被摘除的连接
    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l1Misses = new LongAdder();
    private final LongAdder rebuilt = new LongAdder();         // L3 重建成功写回
    private final LongAdder degraded = new LongAdder();        // 兜底返回（未写缓存）
    private final LongAdder reconciled = new LongAdder();      // 对账纠偏的教学班数

    /* ---- 阶段 21：受理制 ---- */
    private final LongAdder accepted = new LongAdder();        // 受理成功（已发 MQ，等落库）
    private final LongAdder acceptFull = new LongAdder();      // 受理时判定满员（未改任何状态）
    private final LongAdder acceptDup = new LongAdder();       // 受理幂等拦截（5s 窗口）
    private final LongAdder acceptAlready = new LongAdder();   // 快速已选检查拦截
    private final LongAdder acceptInit = new LongAdder();      // 结构/集合未初始化 → 慢路径
    private final LongAdder acceptDegraded = new LongAdder();  // Redis 异常 → 回落同步老链路
    private final LongAdder mqFail = new LongAdder();          // 落库消息发送失败（已补偿）
    private final LongAdder compensated = new LongAdder();     // 补偿成功（名额已退回 + 修正信号）
    private final LongAdder settledOk = new LongAdder();       // 落库成功回执
    private final LongAdder settledReject = new LongAdder();   // 业务拒绝回执（补偿 + 通知）
    private final LongAdder pendingAdjudicated = new LongAdder(); // 超时裁决的受理数
    private final LongAdder settledPushed = new LongAdder();   // settled 回执 SSE 推送

    public void push() { pushes.increment(); }
    public void pushSkipped() { pushSkipped.increment(); }
    public void published() { published.increment(); }
    public void signalIn() { signalsIn.increment(); }
    public void fanout(int frames) { fanoutFrames.add(frames); }
    public void fanoutDropped() { fanoutDropped.increment(); }
    public void l1Hit() { l1Hits.increment(); }
    public void l1Miss() { l1Misses.increment(); }
    public void rebuilt() { rebuilt.increment(); }
    public void degraded() { degraded.increment(); }
    public void reconciled(long n) { reconciled.add(n); }

    public void accepted() { accepted.increment(); }
    public void acceptFull() { acceptFull.increment(); }
    public void acceptDup() { acceptDup.increment(); }
    public void acceptAlready() { acceptAlready.increment(); }
    public void acceptInit() { acceptInit.increment(); }
    public void acceptDegraded() { acceptDegraded.increment(); }
    public void mqFail() { mqFail.increment(); }
    public void compensated() { compensated.increment(); }
    public void settledOk() { settledOk.increment(); }
    public void settledReject() { settledReject.increment(); }
    public void pendingAdjudicated() { pendingAdjudicated.increment(); }
    public void settledPushed() { settledPushed.increment(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pushes", pushes.sum());
        m.put("pushSkipped", pushSkipped.sum());
        m.put("published", published.sum());
        m.put("signalsIn", signalsIn.sum());
        m.put("fanoutFrames", fanoutFrames.sum());
        m.put("fanoutDropped", fanoutDropped.sum());
        m.put("l1Hits", l1Hits.sum());
        m.put("l1Misses", l1Misses.sum());
        m.put("rebuilt", rebuilt.sum());
        m.put("degraded", degraded.sum());
        m.put("reconciled", reconciled.sum());
        m.put("accepted", accepted.sum());
        m.put("acceptFull", acceptFull.sum());
        m.put("acceptDup", acceptDup.sum());
        m.put("acceptAlready", acceptAlready.sum());
        m.put("acceptInit", acceptInit.sum());
        m.put("acceptDegraded", acceptDegraded.sum());
        m.put("mqFail", mqFail.sum());
        m.put("compensated", compensated.sum());
        m.put("settledOk", settledOk.sum());
        m.put("settledReject", settledReject.sum());
        m.put("pendingAdjudicated", pendingAdjudicated.sum());
        m.put("settledPushed", settledPushed.sum());
        return m;
    }
}
